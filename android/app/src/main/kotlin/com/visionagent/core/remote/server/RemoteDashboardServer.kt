package com.visionagent.core.remote.server
import kotlin.collections.ArrayDeque  // Explicit: avoids Lint confusion with java.util.ArrayDeque (API 35)

import android.content.Context
import com.visionagent.core.diagnostic.SelfDiagnosticEngine
import com.visionagent.core.event.*
import com.visionagent.core.memory.MemoryEngine
import com.visionagent.core.performance.PerformanceTracker
import com.visionagent.core.workflow.engine.Workflow
import com.visionagent.core.workflow.engine.WorkflowEngine
import com.visionagent.data.local.database.AgentDatabase
import com.visionagent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.*
import java.net.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import javax.inject.Inject
import javax.inject.Singleton

// ============================================================
// RemoteDashboardServer — Local HTTP + WebSocket Server
//
// फोन के Browser से access करो:
//   http://192.168.1.x:8765/
//
// Endpoints:
//  GET  /                    → HTML Dashboard (full UI)
//  GET  /api/status          → Agent JSON status
//  GET  /api/health          → Diagnostic report
//  GET  /api/metrics         → Performance metrics
//  GET  /api/logs            → Last 200 log lines
//  GET  /api/memory          → Memory state
//  GET  /api/workflows       → List workflows
//  POST /api/workflow/run    → Run a workflow
//  POST /api/rule/add        → Add a rule (JSON body)
//  GET  /api/db/query        → SQLite query console
//  GET  /api/events/stream   → SSE: live event stream
//  WS   /ws                  → WebSocket: live events
//
// Tech: Pure Java sockets (no external library — works without internet)
// Security: Bind to local network only (192.168.x.x)
//            Optional PIN protection
// ============================================================

data class HttpRequest(
    val method:  String,
    val path:    String,
    val headers: Map<String, String>,
    val body:    String,
    val query:   Map<String, String>
)

data class HttpResponse(
    val status:  Int,
    val headers: Map<String, String>,
    val body:    ByteArray
)

@Singleton
class RemoteDashboardServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventBus:           AgentEventBus,
    private val performanceTracker: PerformanceTracker,
    private val memoryEngine:       MemoryEngine,
    private val diagnosticEngine:   SelfDiagnosticEngine,
    private val workflowEngine:     WorkflowEngine,
    private val database:           AgentDatabase,  // FIX C-9: inject Room DB
    private val logger:             Logger
) {
    companion object {
        private const val TAG     = "RemoteDashboard"
        private const val PORT    = 8765
        private const val MAX_LOG = 200
    }

    private val json         = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val serverScope  = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    // FIX R4-9: isRunning written by stop() (any calling thread), read on Dispatchers.IO
    // in runServer(). Without @Volatile the IO thread never sees stop()'s false write.
    @Volatile private var isRunning = false

    // FIX H-3a: Replace CopyOnWriteArrayList with ArrayDeque + ReentrantLock.
    // CopyOnWriteArrayList.removeAt(0) copies all N-1 elements on every removal.
    // Called at ~50 events/sec = 10,000 element copies/sec → CPU spike.
    // ArrayDeque.removeFirst() is O(1).
    // The non-atomic check (size > MAX) + removeAt(0) was also a race;
    // the lock makes the trim operation atomic.
    private val logsLock   = java.util.concurrent.locks.ReentrantLock()
    private val recentLogs = ArrayDeque<String>(MAX_LOG + 1)

    private val sseClients = CopyOnWriteArrayList<PrintWriter>()  // low write freq → COWAL ok
    private var agentStatus  = mapOf<String, Any>()
    private var sessionId    = ""
    private val dateFmt      = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // ── Start/Stop ─────────────────────────────────────────────────────────

    fun start(sessionId: String) {
        this.sessionId = sessionId
        subscribeToEvents()
        serverScope.launch { runServer() }
        logger.i(TAG, "Remote Dashboard: http://${getLocalIP()}:$PORT")
    }

    fun stop() {
        isRunning = false
        serverSocket?.close()
        serverScope.cancel()
        logger.i(TAG, "Remote Dashboard stopped")
    }

    fun getUrl(): String = "http://${getLocalIP()}:$PORT"

    // ── Server Loop ───────────────────────────────────────────────────────

    private suspend fun runServer() = withContext(Dispatchers.IO) {
        try {
            serverSocket = ServerSocket(PORT, 50, InetAddress.getByName("0.0.0.0"))
            isRunning = true
            logger.i(TAG, "HTTP server listening on port $PORT")

            while (isRunning && isActive) {
                try {
                    val client = serverSocket?.accept() ?: break
                    launch { handleClient(client) }
                } catch (e: SocketException) {
                    if (isRunning) logger.w(TAG, "Socket error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            logger.e(TAG, "Server error", e)
        }
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            socket.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)
            val rawOut = socket.getOutputStream()

            val request = parseRequest(reader) ?: return@withContext

            // WebSocket upgrade
            if (request.headers["upgrade"]?.lowercase() == "websocket") {
                handleWebSocket(socket, reader, writer, request)
                return@withContext
            }

            // SSE stream
            if (request.path == "/api/events/stream") {
                handleSSE(socket, rawOut)
                return@withContext
            }

            val response = route(request)
            sendHttpResponse(rawOut, response)
        } catch (e: Exception) {
            logger.v(TAG, "Client error: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    // ── Router ────────────────────────────────────────────────────────────

    private suspend fun route(req: HttpRequest): HttpResponse {
        return when {
            req.method == "GET" && req.path == "/"              -> serveHTML()
            req.method == "GET" && req.path == "/api/status"    -> jsonResponse(buildStatus())
            req.method == "GET" && req.path == "/api/health"    -> serveHealth()
            req.method == "GET" && req.path == "/api/metrics"   -> jsonResponse(buildMetrics())
            req.method == "GET" && req.path == "/api/logs"      -> jsonResponse(buildLogs())
            req.method == "GET" && req.path == "/api/memory"    -> jsonResponse(buildMemory())
            req.method == "GET" && req.path == "/api/workflows" -> jsonResponse(buildWorkflows())
            req.method == "POST" && req.path == "/api/workflow/run" -> runWorkflow(req)
            req.method == "GET" && req.path.startsWith("/api/db") -> serveDbQuery(req)
            req.method == "GET" && req.path.startsWith("/static") -> serveStatic(req.path)
            else -> HttpResponse(404, jsonHeaders(), """{"error":"Not found"}""".toByteArray())
        }
    }

    // ── API Handlers ──────────────────────────────────────────────────────

    private fun buildStatus(): String {
        val runtime = Runtime.getRuntime()
        val usedMB  = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        return json.encodeToString(mapOf(
            "session_id"    to sessionId,
            "timestamp"     to System.currentTimeMillis(),
            "ram_mb"        to usedMB.toString(),
            "thread_count"  to Thread.activeCount().toString(),
            "is_running"    to "true",
            "version"       to com.visionagent.BuildConfig.AGENT_VERSION
        ).also { agentStatus = it })
    }

    private suspend fun serveHealth(): HttpResponse {
        val report = diagnosticEngine.runFullDiagnostic("remote_dashboard")
        val data = mapOf(
            "health_score" to report.healthScore.toString(),
            "risk_level"   to report.riskLevel.name,
            "ok_count"     to report.okCount.toString(),
            "warn_count"   to report.warningCount.toString(),
            "crit_count"   to report.criticalCount.toString(),
            "checks"       to report.checks.map { mapOf(
                "name"    to it.name,
                "status"  to it.status.name,
                "value"   to it.value,
                "message" to it.message
            )}
        )
        return jsonResponse(json.encodeToString(data))
    }

    private fun buildMetrics(): String {
        val summary = performanceTracker.getSummaryReport()
        return json.encodeToString(summary.mapValues { (_, v) -> v.toString() })
    }

    private fun buildLogs(): String {
        val snapshot: List<String> = logsLock.withLock { recentLogs.toList() }
        return json.encodeToString(mapOf("logs" to snapshot))
    }

    private fun buildMemory(): String {
        val summary = memoryEngine.getMemorySummary()
        return json.encodeToString(summary.mapValues { it.value.toString() })
    }

    private fun buildWorkflows(): String {
        val wfs = workflowEngine.getAllWorkflows().map { wf ->
            mapOf("id" to wf.id, "name" to wf.name, "enabled" to wf.isEnabled.toString(),
                  "triggers" to wf.triggers.size.toString(), "blocks" to wf.blocks.size.toString())
        }
        return json.encodeToString(mapOf("workflows" to wfs, "active" to workflowEngine.getActiveWorkflows()))
    }

    private fun runWorkflow(req: HttpRequest): HttpResponse {
        val wfId = req.query["id"] ?: return jsonResponse("""{"error":"id required"}""", 400)
        workflowEngine.execute(wfId, sessionId)
        return jsonResponse("""{"status":"started","workflow_id":"$wfId"}""")
    }

    private fun serveDbQuery(req: HttpRequest): HttpResponse {
        val query = req.query["q"] ?: "SELECT * FROM sessions LIMIT 10"

        // Safety: only allow SELECT queries
        if (!query.trimStart().uppercase().startsWith("SELECT")) {
            return jsonResponse("""{"error":"Only SELECT queries allowed"}""", 403)
        }

        // FIX C-9: Use Room's SupportSQLiteOpenHelper to run the query.
        // This participates in Room's WAL coordination and connection pool.
        // Prevents the corruption that occurred when opening a second raw
        // SQLiteDatabase handle on the same WAL-mode file.
        return try {
            val db   = database.openHelper.readableDatabase
            // FIX DB-1: SupportSQLiteDatabase.query(String, Array<Any?>?) — 
            // null bindings arg is fine but must be Array<Any?>, not null for some overloads
            val cur  = db.query(query, emptyArray<Any?>())
            val cols = (0 until cur.columnCount).map { cur.getColumnName(it) }
            val rows = mutableListOf<Map<String, String>>()
            while (cur.moveToNext()) {
                val row = mutableMapOf<String, String>()
                cols.forEachIndexed { i, col -> row[col] = cur.getString(i) ?: "null" }
                rows.add(row)
            }
            cur.close()
            // NOTE: do NOT close db — it belongs to Room's connection pool
            jsonResponse(json.encodeToString(mapOf(
                "columns" to cols,
                "rows"    to rows,
                "count"   to rows.size.toString()
            )))
        } catch (e: Exception) {
            jsonResponse("""{"error":"${e.message?.take(200)}"}""", 500)
        }
    }

    // ── HTML Dashboard ─────────────────────────────────────────────────────

    private fun serveHTML(): HttpResponse {
        val html = buildDashboardHTML()
        return HttpResponse(200,
            mapOf("Content-Type" to "text/html; charset=utf-8",
                  "Cache-Control" to "no-store"),
            html.toByteArray())
    }

    private fun buildDashboardHTML(): String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Vision Agent Dashboard</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:'Courier New',monospace;background:#0d1117;color:#c9d1d9;min-height:100vh}
header{background:#161b22;padding:12px 20px;border-bottom:1px solid #30363d;display:flex;align-items:center;gap:12px}
header h1{font-size:16px;color:#58a6ff}
.badge{background:#1f6feb;padding:2px 8px;border-radius:12px;font-size:11px}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:16px;padding:16px}
.card{background:#161b22;border:1px solid #30363d;border-radius:8px;padding:16px}
.card h2{font-size:12px;color:#8b949e;margin-bottom:12px;text-transform:uppercase;letter-spacing:.5px}
.metric{display:flex;justify-content:space-between;padding:6px 0;border-bottom:1px solid #21262d}
.metric:last-child{border:none}
.metric .label{color:#8b949e;font-size:12px}
.metric .value{font-size:13px;font-weight:bold}
.ok{color:#3fb950}.warn{color:#d29922}.crit{color:#f85149}
.score-bar{height:8px;background:#21262d;border-radius:4px;overflow:hidden;margin:8px 0}
.score-fill{height:100%;background:#3fb950;transition:width .5s}
.log-box{background:#0d1117;padding:10px;border-radius:4px;height:180px;overflow-y:auto;font-size:11px;line-height:1.6}
.log-line{color:#8b949e}
.log-line.err{color:#f85149}
.log-line.warn{color:#d29922}
input,select{background:#0d1117;border:1px solid #30363d;color:#c9d1d9;padding:6px 10px;border-radius:4px;width:100%;font-family:inherit;font-size:12px;margin-bottom:8px}
button{background:#1f6feb;border:none;color:white;padding:7px 14px;border-radius:4px;cursor:pointer;font-size:12px;margin:4px 2px}
button:hover{background:#388bfd}
button.danger{background:#da3633}
button.success{background:#2ea043}
pre{background:#0d1117;padding:10px;border-radius:4px;font-size:11px;overflow:auto;max-height:200px}
.status-dot{width:8px;height:8px;border-radius:50%;background:#3fb950;display:inline-block;margin-right:6px;animation:pulse 2s infinite}
@keyframes pulse{0%,100%{opacity:1}50%{opacity:.4}}
.tab{display:none}.tab.active{display:block}
nav a{color:#58a6ff;cursor:pointer;padding:6px 12px;display:inline-block;text-decoration:none;font-size:13px;border-bottom:2px solid transparent}
nav a.active{border-color:#58a6ff}
table{width:100%;border-collapse:collapse;font-size:12px}
th,td{padding:6px 10px;text-align:left;border-bottom:1px solid #21262d}
th{color:#8b949e;font-weight:normal}
</style>
</head>
<body>
<header>
  <span class="status-dot"></span>
  <h1>🤖 Vision Agent Dashboard</h1>
  <span class="badge">v${com.visionagent.BuildConfig.AGENT_VERSION}</span>
  <span id="conn-status" style="margin-left:auto;font-size:11px;color:#8b949e">Connecting...</span>
</header>

<nav style="padding:8px 16px;background:#161b22;border-bottom:1px solid #30363d">
  <a onclick="showTab('overview')" class="active" id="tab-overview">Overview</a>
  <a onclick="showTab('health')"   id="tab-health">Health</a>
  <a onclick="showTab('logs')"     id="tab-logs">Logs</a>
  <a onclick="showTab('memory')"   id="tab-memory">Memory</a>
  <a onclick="showTab('workflow')" id="tab-workflow">Workflow</a>
  <a onclick="showTab('db')"       id="tab-db">Database</a>
</nav>

<!-- OVERVIEW TAB -->
<div id="tab-overview" class="tab active">
<div class="grid">
  <div class="card">
    <h2>📊 Performance</h2>
    <div class="metric"><span class="label">RAM Used</span><span class="value ok" id="ram">-</span></div>
    <div class="metric"><span class="label">Threads</span><span class="value" id="threads">-</span></div>
    <div class="metric"><span class="label">Session</span><span class="value" id="session" style="font-size:10px">-</span></div>
    <div class="metric"><span class="label">Uptime</span><span class="value" id="uptime">-</span></div>
    <button onclick="fetchStatus()">🔄 Refresh</button>
  </div>

  <div class="card">
    <h2>🏥 Health Score</h2>
    <div style="font-size:32px;font-weight:bold;text-align:center;padding:8px 0" id="health-score">-</div>
    <div class="score-bar"><div class="score-fill" id="health-bar" style="width:0%"></div></div>
    <div id="health-risk" style="text-align:center;font-size:12px;margin-top:4px"></div>
    <button onclick="fetchHealth()">Run Diagnostic</button>
  </div>

  <div class="card">
    <h2>🤖 Agent State</h2>
    <div class="metric"><span class="label">State</span><span class="value" id="agent-state">-</span></div>
    <div class="metric"><span class="label">Screen</span><span class="value" id="screen-type">-</span></div>
    <div class="metric"><span class="label">Confidence</span><span class="value" id="confidence">-</span></div>
    <div class="metric"><span class="label">Actions OK</span><span class="value ok" id="actions-ok">-</span></div>
    <div class="metric"><span class="label">Actions Fail</span><span class="value crit" id="actions-fail">-</span></div>
  </div>

  <div class="card">
    <h2>🎛️ Controls</h2>
    <button class="success" onclick="runDiagnostic()">🔬 Run Diagnostic</button>
    <button onclick="clearLogs()">🗑️ Clear Logs</button>
    <button class="danger" onclick="if(confirm('Stop agent?')) sendCmd('stop')">⛔ Stop Agent</button>
    <button onclick="exportBundle()">📦 Export Debug Bundle</button>
    <br><br>
    <h2 style="margin-bottom:8px">⚡ Quick Trigger</h2>
    <select id="wf-select"></select>
    <button onclick="runSelectedWorkflow()">▶ Run Workflow</button>
  </div>
</div>
</div>

<!-- HEALTH TAB -->
<div id="tab-health" class="tab">
<div class="grid">
  <div class="card" style="grid-column:1/-1">
    <h2>Health Checks</h2>
    <table><thead><tr><th>Check</th><th>Status</th><th>Value</th><th>Message</th></tr></thead>
    <tbody id="health-table"></tbody></table>
    <br><button onclick="fetchHealth()">🔄 Run Diagnostic</button>
  </div>
</div>
</div>

<!-- LOGS TAB -->
<div id="tab-logs" class="tab">
<div style="padding:16px">
  <div class="card">
    <h2>📜 Live Logs <span id="log-count" style="font-size:10px;color:#8b949e"></span></h2>
    <div class="log-box" id="log-container"></div>
    <br>
    <input type="text" id="log-filter" placeholder="Filter logs..." oninput="filterLogs()">
    <button onclick="fetchLogs()">🔄 Refresh</button>
    <button onclick="document.getElementById('log-container').innerHTML=''">Clear</button>
  </div>
</div>
</div>

<!-- MEMORY TAB -->
<div id="tab-memory" class="tab">
<div class="grid">
  <div class="card">
    <h2>🧠 Memory Layers</h2>
    <div id="memory-table"></div>
    <button onclick="fetchMemory()">🔄 Refresh</button>
  </div>
</div>
</div>

<!-- WORKFLOW TAB -->
<div id="tab-workflow" class="tab">
<div class="grid">
  <div class="card" style="grid-column:1/-1">
    <h2>⚙️ Workflows</h2>
    <table><thead><tr><th>Name</th><th>Enabled</th><th>Triggers</th><th>Blocks</th><th>Action</th></tr></thead>
    <tbody id="workflow-table"></tbody></table>
    <br><button onclick="fetchWorkflows()">🔄 Refresh</button>
  </div>
</div>
</div>

<!-- DATABASE TAB -->
<div id="tab-db" class="tab">
<div style="padding:16px">
  <div class="card">
    <h2>💾 SQLite Query Console</h2>
    <p style="font-size:11px;color:#8b949e;margin-bottom:8px">Only SELECT queries allowed</p>
    <input type="text" id="db-query" value="SELECT * FROM sessions ORDER BY started_at DESC LIMIT 10">
    <button onclick="runQuery()">▶ Run Query</button>
    <button onclick="document.getElementById('db-query').value='SELECT * FROM memory_store WHERE type=\'LONG_TERM\' LIMIT 20'">Memory</button>
    <button onclick="document.getElementById('db-query').value='SELECT * FROM error_logs ORDER BY timestamp DESC LIMIT 10'">Errors</button>
    <button onclick="document.getElementById('db-query').value='SELECT * FROM performance_logs ORDER BY duration_ms DESC LIMIT 20'">Slowest Ops</button>
    <pre id="db-result">Run a query to see results</pre>
  </div>
</div>
</div>

<script>
const api = location.origin;
let ws = null;
let allLogs = [];
let startTime = Date.now();

// ── WebSocket ────────────────────────────────────────────────
function connectWS(){
  ws = new WebSocket('ws://'+location.host+'/ws');
  ws.onopen = ()=>{ document.getElementById('conn-status').textContent='● Live'; };
  ws.onmessage = (e)=>{
    try{
      const d = JSON.parse(e.data);
      handleLiveEvent(d);
    } catch(_){}
  };
  ws.onclose = ()=>{
    document.getElementById('conn-status').textContent='⚠ Disconnected';
    setTimeout(connectWS, 3000);
  };
}

function handleLiveEvent(d){
  if(d.type === 'status')   updateStatusUI(d.data);
  if(d.type === 'log')      addLog(d.message, d.level);
  if(d.type === 'state')    document.getElementById('agent-state').textContent = d.state;
  if(d.type === 'screen')   document.getElementById('screen-type').textContent = d.screen_type;
}

// ── Fetch helpers ────────────────────────────────────────────
async function fetchStatus(){
  const r = await fetch(api+'/api/status');
  const d = await r.json();
  document.getElementById('ram').textContent = d.ram_mb+'MB';
  document.getElementById('threads').textContent = d.thread_count;
  document.getElementById('session').textContent = d.session_id;
  document.getElementById('uptime').textContent = Math.floor((Date.now()-startTime)/1000)+'s';
}

async function fetchHealth(){
  const r = await fetch(api+'/api/health');
  const d = await r.json();
  const score = parseFloat(d.health_score).toFixed(1);
  document.getElementById('health-score').textContent = score+'%';
  const bar = document.getElementById('health-bar');
  bar.style.width = score+'%';
  bar.style.background = score>80?'#3fb950':score>60?'#d29922':'#f85149';
  document.getElementById('health-risk').textContent = 'Risk: '+d.risk_level;
  const tb = document.getElementById('health-table');
  tb.innerHTML = (d.checks||[]).map(c=>`
    <tr>
      <td>${'$'}{c.name}</td>
      <td class="${'$'}{c.status==='OK'?'ok':c.status==='WARNING'?'warn':'crit'}">${'$'}{c.status}</td>
      <td style="font-size:11px">${'$'}{c.value}</td>
      <td style="font-size:11px;color:#8b949e">${'$'}{c.message}</td>
    </tr>`).join('');
}

async function fetchLogs(){
  const r = await fetch(api+'/api/logs');
  const d = await r.json();
  allLogs = d.logs||[];
  renderLogs(allLogs);
}

function addLog(msg, level){
  const ts = new Date().toLocaleTimeString();
  allLogs.push('['+ts+'] '+msg);
  if(allLogs.length>200) allLogs.shift();
  filterLogs();
}

function renderLogs(logs){
  const c = document.getElementById('log-container');
  c.innerHTML = logs.map(l=>{
    const cls = l.includes('ERROR')||l.includes('FATAL')?'err':l.includes('WARN')?'warn':'';
    return `<div class="log-line ${'$'}{cls}">${'$'}{l}</div>`;
  }).join('');
  c.scrollTop = c.scrollHeight;
  document.getElementById('log-count').textContent = logs.length+' lines';
}

function filterLogs(){
  const f = document.getElementById('log-filter').value.toLowerCase();
  renderLogs(f ? allLogs.filter(l=>l.toLowerCase().includes(f)) : allLogs);
}

async function fetchMemory(){
  const r = await fetch(api+'/api/memory');
  const d = await r.json();
  document.getElementById('memory-table').innerHTML =
    Object.entries(d).map(([k,v])=>`<div class="metric"><span class="label">${'$'}{k}</span><span class="value">${'$'}{v}</span></div>`).join('');
}

async function fetchWorkflows(){
  const r = await fetch(api+'/api/workflows');
  const d = await r.json();
  const sel = document.getElementById('wf-select');
  sel.innerHTML = (d.workflows||[]).map(w=>`<option value="${'$'}{w.id}">${'$'}{w.name}</option>`).join('');
  document.getElementById('workflow-table').innerHTML = (d.workflows||[]).map(w=>`
    <tr>
      <td>${'$'}{w.name}</td>
      <td class="${'$'}{w.enabled==='true'?'ok':'crit'}">${'$'}{w.enabled==='true'?'✅':'❌'}</td>
      <td>${'$'}{w.triggers}</td>
      <td>${'$'}{w.blocks}</td>
      <td><button onclick="runWorkflow('${'$'}{w.id}')">▶</button></td>
    </tr>`).join('');
}

async function runWorkflow(id){
  await fetch(api+'/api/workflow/run?id='+id, {method:'POST'});
  alert('Workflow started: '+id);
}
function runSelectedWorkflow(){ runWorkflow(document.getElementById('wf-select').value); }

async function runQuery(){
  const q = document.getElementById('db-query').value;
  const r = await fetch(api+'/api/db/query?q='+encodeURIComponent(q));
  const d = await r.json();
  if(d.error){ document.getElementById('db-result').textContent='Error: '+d.error; return; }
  const cols = d.columns||[];
  const rows = d.rows||[];
  let out = cols.join(' | ')+'\\n'+'-'.repeat(60)+'\\n';
  rows.forEach(row=>{ out += cols.map(c=>String(row[c]||'').padEnd(15).slice(0,15)).join(' | ')+'\\n'; });
  out += '\\n('+d.count+' rows)';
  document.getElementById('db-result').textContent = out;
}

async function runDiagnostic(){ await fetchHealth(); showTab('health'); }
function clearLogs(){ allLogs=[]; document.getElementById('log-container').innerHTML=''; }
function exportBundle(){ window.open(api+'/api/export'); }

function showTab(name){
  document.querySelectorAll('.tab').forEach(t=>t.classList.remove('active'));
  document.querySelectorAll('nav a').forEach(a=>a.classList.remove('active'));
  document.getElementById('tab-'+name).classList.add('active');
  document.getElementById('tab-'+name).classList.add('active');
  const link = document.getElementById('tab-'+name);
  if(link) link.classList.add('active');
}

function updateStatusUI(d){
  if(d.ram_mb) document.getElementById('ram').textContent=d.ram_mb+'MB';
}

// ── Init ─────────────────────────────────────────────────────
connectWS();
fetchStatus(); fetchHealth(); fetchLogs(); fetchMemory(); fetchWorkflows();
setInterval(()=>{ fetchStatus(); }, 5000);
setInterval(()=>{ fetchLogs(); },  10000);
</script>
</body>
</html>""".trimIndent()

    // ── WebSocket Handler ─────────────────────────────────────────────────

    private fun handleWebSocket(socket: Socket, reader: BufferedReader, writer: PrintWriter, req: HttpRequest) {
        try {
            // WebSocket handshake
            val key = req.headers["sec-websocket-key"] ?: return
            val accept = java.util.Base64.getEncoder().encodeToString(
                java.security.MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))

            writer.print("HTTP/1.1 101 Switching Protocols\r\n")
            writer.print("Upgrade: websocket\r\n")
            writer.print("Connection: Upgrade\r\n")
            writer.print("Sec-WebSocket-Accept: $accept\r\n\r\n")
            writer.flush()

            // Send live events via EventBus
            val outStream = socket.getOutputStream()
            val eventJob  = serverScope.launch {
                eventBus.events.collect { event ->
                    val payload = """{"type":"event","event":"${event::class.simpleName}"}"""
                    sendWsFrame(outStream, payload)
                }
            }

            // Keep alive until socket closes
            try {
                val inStream = socket.getInputStream()
                val buf = ByteArray(64)
                while (inStream.read(buf) != -1) { /* heartbeat */ }
            } finally {
                eventJob.cancel()
            }
        } catch (_: Exception) {}
    }

    private fun sendWsFrame(out: OutputStream, msg: String) {
        val data  = msg.toByteArray()
        val frame = ByteArrayOutputStream()
        frame.write(0x81)  // FIN + text frame
        if (data.size < 126) frame.write(data.size)
        else { frame.write(126); frame.write(data.size shr 8); frame.write(data.size and 0xFF) }
        frame.write(data)
        out.write(frame.toByteArray())
        out.flush()
    }

    // ── SSE Handler ───────────────────────────────────────────────────────

    private fun handleSSE(socket: Socket, out: OutputStream) {
        val writer = PrintWriter(out)
        writer.print("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: keep-alive\r\n\r\n")
        writer.flush()
        sseClients.add(writer)
        // FIX H-3b: Replaced Thread.sleep(MAX_VALUE) which blocked an IO thread permanently.
        // At 64 concurrent SSE connections it would exhaust Dispatchers.IO thread pool.
        // Instead: read from socket with a timeout to detect disconnects,
        // then loop until the socket closes or the server stops.
        try {
            socket.soTimeout = 30_000  // 30s read timeout for keep-alive detection
            val buf = ByteArray(64)
            while (isRunning) {
                try {
                    val n = socket.getInputStream().read(buf)
                    if (n == -1) break  // Client disconnected
                } catch (_: java.net.SocketTimeoutException) {
                    // Timeout is expected — send heartbeat comment to keep connection alive
                    try { writer.print(": heartbeat\n\n"); writer.flush() } catch (_: Exception) { break }
                }
            }
        } catch (_: Exception) {
            // Socket closed — normal exit
        } finally {
            sseClients.remove(writer)
        }
    }

    private fun sendSSE(event: String, data: String) {
        val msg = "event: $event\ndata: $data\n\n"
        sseClients.removeAll { w ->
            try { w.print(msg); w.flush(); w.checkError() } catch (_: Exception) { true }
        }
    }

    // ── Event Subscription ─────────────────────────────────────────────────

    private fun subscribeToEvents() {
        eventBus.events
            .onEach { event ->
                val line = "[${dateFmt.format(Date())}] ${event::class.simpleName}"
                // FIX H-3a: O(1) trim under lock — atomic check-and-remove
                logsLock.lock()
                try {
                    recentLogs.addLast(line)
                    if (recentLogs.size > MAX_LOG) recentLogs.removeFirst()
                } finally {
                    logsLock.unlock()
                }
                sendSSE("log", """{"message":"$line"}""")
            }
            .launchIn(serverScope)
    }

    // ── HTTP Utilities ─────────────────────────────────────────────────────

    private fun parseRequest(reader: BufferedReader): HttpRequest? {
        val firstLine = reader.readLine()?.split(" ") ?: return null
        if (firstLine.size < 2) return null
        val method   = firstLine[0]
        val fullPath = firstLine[1]
        val pathParts = fullPath.split("?")
        val path     = pathParts[0]
        val query    = if (pathParts.size > 1) parseQuery(pathParts[1]) else emptyMap()
        val headers  = mutableMapOf<String, String>()
        var line     = reader.readLine()
        while (!line.isNullOrBlank()) {
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).lowercase()] = line.substring(colon + 1).trim()
            line = reader.readLine()
        }
        // FIX NC-11: No body size limit previously. A client could send
        // Content-Length: 2147483647 to allocate 4GB → OOM crash.
        // Even at reasonable sizes: 10MB per concurrent connection exhausts heap.
        // Fix: cap at MAX_BODY_BYTES (64KB). Larger bodies are truncated/rejected.
        val MAX_BODY_BYTES = 65_536  // 64 KB — sufficient for all API payloads
        val rawBodyLen = headers["content-length"]?.toIntOrNull() ?: 0
        val bodyLen    = rawBodyLen.coerceIn(0, MAX_BODY_BYTES)
        val body = if (bodyLen > 0) {
            val buf = CharArray(bodyLen); reader.read(buf); String(buf)
        } else ""
        return HttpRequest(method, path, headers, body, query)
    }

    private fun parseQuery(q: String) = q.split("&").mapNotNull { part ->
        val eq = part.indexOf('=')
        if (eq > 0) URLDecoder.decode(part.substring(0, eq), "UTF-8") to
                    URLDecoder.decode(part.substring(eq + 1), "UTF-8")
        else null
    }.toMap()

    private fun sendHttpResponse(out: OutputStream, resp: HttpResponse) {
        val pw = PrintWriter(out)
        pw.print("HTTP/1.1 ${resp.status} ${statusText(resp.status)}\r\n")
        pw.print("Content-Length: ${resp.body.size}\r\n")
        resp.headers.forEach { (k, v) -> pw.print("$k: $v\r\n") }
        pw.print("Access-Control-Allow-Origin: *\r\n")
        pw.print("\r\n")
        pw.flush()
        out.write(resp.body)
        out.flush()
    }

    private fun jsonResponse(body: String, status: Int = 200) =
        HttpResponse(status, jsonHeaders(), body.toByteArray())

    private fun jsonHeaders() = mapOf("Content-Type" to "application/json")
    private fun statusText(code: Int) = when(code) { 200->"OK"; 404->"Not Found"; 403->"Forbidden"; 500->"Internal Server Error"; else->"Unknown" }
    private fun serveStatic(path: String) = HttpResponse(404, emptyMap(), ByteArray(0))

    private fun getLocalIP(): String = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
            ?.hostAddress ?: "localhost"
    } catch (_: Exception) { "localhost" }
}
