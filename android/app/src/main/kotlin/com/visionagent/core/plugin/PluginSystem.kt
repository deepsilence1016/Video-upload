package com.visionagent.core.plugin

import com.visionagent.core.event.AgentEventBus
import com.visionagent.core.event.AgentEvent
import com.visionagent.utils.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

// ============================================================
// Plugin System — Runtime extensibility without recompilation
//
// Design:
// - Plugins implement AgentPlugin interface
// - Registered via PluginRegistry at runtime
// - Each plugin gets its own CoroutineScope (isolated)
// - Plugins can subscribe to EventBus events
// - Plugin lifecycle: REGISTERED → INITIALIZED → ACTIVE → STOPPED
// - Hot-pluggable: add/remove without restarting agent
//
// Security:
// - Plugins run in restricted sandbox scope
// - Resource limits: max coroutines, max memory markers
// - Permission-based event access
//
// Use cases:
// - Custom UI detectors for specific screens
// - Business-specific rule injection
// - Analytics collectors
// - Third-party integrations (future)
// ============================================================

enum class PluginState { REGISTERED, INITIALIZING, ACTIVE, STOPPING, STOPPED, FAILED }

data class PluginMetadata(
    val id:          String,
    val name:        String,
    val version:     String,
    val author:      String,
    val description: String,
    val permissions: Set<PluginPermission> = emptySet(),
    val minAgentVersion: String = "1.0.0"
)

enum class PluginPermission {
    READ_SCREEN,        // Can receive FrameCapturedEvent
    READ_OCR,           // Can receive OCRCompletedEvent
    READ_VISION,        // Can receive UIElementDetectedEvent
    WRITE_RULES,        // Can register rules in RuleEngine
    WRITE_MEMORY,       // Can write to MemoryEngine
    EXECUTE_ACTIONS,    // Can publish RuleEvaluatedEvent with decisions
    READ_PERFORMANCE,   // Can receive PerformanceMetricEvent
    READ_ALL,           // Read all events
    ADMIN               // Full access
}

// ─────────────────────────────────────────────────────────────────────────────
// AgentPlugin Interface — implement this to create a plugin
// ─────────────────────────────────────────────────────────────────────────────

interface AgentPlugin {
    val metadata: PluginMetadata

    /** Called once when plugin is loaded. Return false to abort. */
    suspend fun onInitialize(context: PluginContext): Boolean

    /** Called when agent starts a new session. */
    suspend fun onSessionStart(sessionId: String) {}

    /** Called when agent stops a session. */
    suspend fun onSessionStop(sessionId: String) {}

    /** Called when plugin is being removed. Cleanup here. */
    suspend fun onStop() {}
}

// ─────────────────────────────────────────────────────────────────────────────
// PluginContext — Sandboxed access to agent internals
// ─────────────────────────────────────────────────────────────────────────────

class PluginContext(
    val pluginId:    String,
    val permissions: Set<PluginPermission>,
    // FIX PLUGIN-1: inline functions cannot access private members.
    // @PublishedApi + internal allows inline functions to access these.
    @PublishedApi internal val eventBus: AgentEventBus,
    @PublishedApi internal val logger: Logger,
    val scope: CoroutineScope
) {
    /** Subscribe to events — permission checked at subscribe time */
    inline fun <reified T : AgentEvent> subscribe(
        requiredPermission: PluginPermission,
        crossinline handler: suspend (T) -> Unit
    ) {
        if (!hasPermission(requiredPermission)) {
            logger.w("PluginContext[$pluginId]",
                     "Permission denied: $requiredPermission for ${T::class.simpleName}")
            return
        }
        eventBus.subscribe<T>()
            .onEach { event ->
                try {
                    handler(event)
                } catch (e: Exception) {
                    logger.e("Plugin[$pluginId]", "Event handler error", e)
                }
            }
            .launchIn(scope)
    }

    /** Publish an event — only if plugin has EXECUTE_ACTIONS permission */
    fun publish(event: AgentEvent) {
        if (!hasPermission(PluginPermission.EXECUTE_ACTIONS) &&
            !hasPermission(PluginPermission.ADMIN)) {
            logger.w("PluginContext[$pluginId]", "No EXECUTE_ACTIONS permission")
            return
        }
        eventBus.publish(event)
    }

    fun log(message: String) = logger.d("Plugin[$pluginId]", message)
    fun logError(message: String, t: Throwable? = null) =
        logger.e("Plugin[$pluginId]", message, t)

    fun hasPermission(perm: PluginPermission) =
        PluginPermission.ADMIN in permissions || perm in permissions
}

// ─────────────────────────────────────────────────────────────────────────────
// PluginRegistry — Central plugin storage and lifecycle management
// ─────────────────────────────────────────────────────────────────────────────

data class PluginRecord(
    val plugin:  AgentPlugin,
    val state:   PluginState,
    val context: PluginContext,
    val scope:   CoroutineScope
)

@Singleton
class PluginRegistry @Inject constructor(
    private val eventBus: AgentEventBus,
    private val logger:   Logger
) {
    private val plugins  = ConcurrentHashMap<String, PluginRecord>()
    private val regScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Register and initialize a plugin */
    suspend fun register(plugin: AgentPlugin): Boolean {
        val id = plugin.metadata.id
        if (plugins.containsKey(id)) {
            logger.w("PluginRegistry", "Plugin already registered: $id")
            return false
        }

        // Create isolated scope for this plugin
        val pluginScope = CoroutineScope(
            SupervisorJob(regScope.coroutineContext[Job]) +
            Dispatchers.Default +
            CoroutineName("Plugin[$id]")
        )

        val context = PluginContext(
            pluginId    = id,
            permissions = plugin.metadata.permissions,
            eventBus    = eventBus,
            logger      = logger,
            scope       = pluginScope
        )

        plugins[id] = PluginRecord(plugin, PluginState.INITIALIZING, context, pluginScope)
        logger.i("PluginRegistry", "Initializing plugin: $id v${plugin.metadata.version}")

        return try {
            val success = withTimeout(5000L) { plugin.onInitialize(context) }
            val state   = if (success) PluginState.ACTIVE else PluginState.FAILED
            plugins[id] = plugins[id]!!.copy(state = state)
            logger.i("PluginRegistry", "Plugin $id: $state")
            success
        } catch (e: Exception) {
            plugins[id] = plugins[id]!!.copy(state = PluginState.FAILED)
            logger.e("PluginRegistry", "Plugin $id initialization failed", e)
            false
        }
    }

    /** Remove and cleanup a plugin */
    suspend fun unregister(pluginId: String) {
        val record = plugins[pluginId] ?: return
        try {
            withTimeout(3000L) { record.plugin.onStop() }
        } catch (e: Exception) {
            logger.w("PluginRegistry", "Plugin $pluginId stop error: ${e.message}")
        }
        record.scope.cancel()
        plugins.remove(pluginId)
        logger.i("PluginRegistry", "Plugin unregistered: $pluginId")
    }

    fun getActivePlugins(): List<AgentPlugin> =
        plugins.values.filter { it.state == PluginState.ACTIVE }.map { it.plugin }

    fun getPluginState(id: String): PluginState? = plugins[id]?.state

    fun getAllPluginInfo(): List<Map<String, Any>> = plugins.map { (id, rec) ->
        mapOf(
            "id"      to id,
            "name"    to rec.plugin.metadata.name,
            "version" to rec.plugin.metadata.version,
            "state"   to rec.state.name
        )
    }

    suspend fun notifySessionStart(sessionId: String) {
        getActivePlugins().forEach { plugin ->
            try { plugin.onSessionStart(sessionId) }
            catch (e: Exception) { logger.e("PluginRegistry", "Plugin session start error", e) }
        }
    }

    suspend fun notifySessionStop(sessionId: String) {
        getActivePlugins().forEach { plugin ->
            try { plugin.onSessionStop(sessionId) }
            catch (e: Exception) { logger.e("PluginRegistry", "Plugin session stop error", e) }
        }
    }
}
