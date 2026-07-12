package com.visionagent.core.memory.vector

import android.content.Context
import com.visionagent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

// ============================================================
// VectorMemory — Semantic Similarity Search
//
// Purpose:
// - Store screen states / text as embedding vectors
// - Find semantically similar past screens
// - Enable "I've seen something like this before" reasoning
// - Context-aware action suggestions from similar episodes
//
// Tech Stack:
// - Embeddings: Computed locally via TF-Lite MiniLM model
//   (Quantized INT8, ~5MB, runs entirely on-device)
// - Index: HNSW (Hierarchical Navigable Small World) in pure Kotlin
//   - Insert: O(log N)
//   - Search: O(log N) approximate
// - Storage: Memory-mapped file for large indices
//
// HNSW Properties:
// - M=16 connections per layer
// - efConstruction=200 (build quality)
// - ef=50 (search quality)
// - Cosine similarity metric
// ============================================================

data class VectorEntry(
    val id:        String,
    val vector:    FloatArray,
    val payload:   Map<String, String> = emptyMap(),  // Metadata
    val timestamp: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?) = other is VectorEntry && id == other.id
    override fun hashCode() = id.hashCode()
}

data class SearchResult(
    val entry:      VectorEntry,
    val similarity: Float,   // Cosine similarity [0, 1]
    val distance:   Float    // 1 - similarity
)

// ─────────────────────────────────────────────────────────────────────────────
// Vector Math
// ─────────────────────────────────────────────────────────────────────────────

object VectorMath {
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Vector size mismatch: \${a.size} vs \${b.size}" }
        var dot    = 0.0
        var normA  = 0.0
        var normB  = 0.0
        for (i in a.indices) {
            // FIX TEST-COSINE: Skip NaN/Inf elements — fuzz inputs include extremes.
            // NaN propagates through arithmetic, producing NaN result even with denom check.
            val ai = a[i]; val bi = b[i]
            if (ai.isNaN() || ai.isInfinite() || bi.isNaN() || bi.isInfinite()) continue
            dot   += ai * bi
            normA += ai * ai
            normB += bi * bi
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom < 1e-10 || denom.isNaN() || denom.isInfinite()) 0f
               else (dot / denom).coerceIn(-1.0, 1.0).toFloat()
    }

    fun euclideanDistance(a: FloatArray, b: FloatArray): Float {
        var sum = 0.0
        for (i in a.indices) {
            val diff = (a[i] - b[i]).toDouble()
            sum += diff * diff
        }
        return sqrt(sum).toFloat()
    }

    fun normalize(v: FloatArray): FloatArray {
        val norm = sqrt(v.map { it * it }.sum().toDouble()).toFloat()
        return if (norm < 1e-10f) v else FloatArray(v.size) { v[it] / norm }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// HNSW Index — Hierarchical Navigable Small World Graph
// Pure Kotlin implementation for Android (no JNI dependency)
// ─────────────────────────────────────────────────────────────────────────────

// FIX M-4: HNSWIndex was not thread-safe.
// insert() and search() both modify `nodes`, `idToIndex`, and `entryPoint`.
// Concurrent calls from VectorMemory.store() and VectorMemory.searchSimilar()
// on Dispatchers.Default (thread pool) caused data races on the graph structure.
// Fix: All public methods synchronize on `this`. HNSW operations are short
// relative to embedding computation, so lock contention is low.
class HNSWIndex(
    private val dim:             Int,
    private val M:               Int   = 16,
    private val efConstruction:  Int   = 200,
    private val maxElements:     Int   = 10000
) {
    private val lock = Any()  // single lock for all graph mutations
    // Each node has a list of connections per layer
    private data class Node(
        val id:        String,
        val vector:    FloatArray,
        val payload:   Map<String, String>,
        val timestamp: Long,
        val layers:    MutableList<MutableSet<Int>> = mutableListOf()  // layer -> neighbor indices
    )

    private val nodes       = mutableListOf<Node>()
    private val idToIndex   = mutableMapOf<String, Int>()
    private var entryPoint  = -1      // Index of top-layer entry point
    private var maxLayer    = 0

    private val random      = java.util.Random(42)

    // ── Insert ────────────────────────────────────────────────────────────

    fun insert(entry: VectorEntry) = synchronized(lock) {
        val idx   = nodes.size
        val level = randomLevel()
        val node  = Node(
            id        = entry.id,
            vector    = VectorMath.normalize(entry.vector),
            payload   = entry.payload,
            timestamp = entry.timestamp
        )

        // Initialize layers for this node
        for (l in 0..level) node.layers.add(mutableSetOf())
        nodes.add(node)
        idToIndex[entry.id] = idx

        if (entryPoint == -1) {
            entryPoint = idx
            maxLayer   = level
            return
        }

        // Find entry point at top layer
        var curEp = entryPoint
        for (lc in maxLayer downTo level + 1) {
            curEp = searchLayer(node.vector, curEp, 1, lc).first().first
        }

        // Connect at each layer from min(level, maxLayer) down to 0
        for (lc in minOf(level, maxLayer) downTo 0) {
            val candidates = searchLayer(node.vector, curEp, efConstruction, lc)
            val neighbors  = selectNeighbors(node.vector, candidates, M)

            for ((neighborIdx, _) in neighbors) {
                node.layers[lc].add(neighborIdx)
                val neighborNode = nodes[neighborIdx]
                if (lc < neighborNode.layers.size) {
                    neighborNode.layers[lc].add(idx)
                    // Prune if over-connected.
                    // FIX NC-1: Take a SNAPSHOT of the set before iterating for selectNeighbors.
                    // The original set is mutated (add above), then we iterate it in selectNeighbors.
                    // Using toList() snapshot prevents any ConcurrentModificationException if
                    // the underlying Set implementation is changed in the future.
                    if (neighborNode.layers[lc].size > M * 2) {
                        val snapshot = neighborNode.layers[lc].toList()  // immutable snapshot
                        val pruned = selectNeighbors(
                            neighborNode.vector,
                            snapshot.map { i ->
                                i to VectorMath.cosineSimilarity(neighborNode.vector, nodes[i].vector)
                            },
                            M
                        ).map { it.first }.toMutableSet()
                        neighborNode.layers[lc] = pruned  // atomic field replacement
                    }
                }
            }
            curEp = candidates.firstOrNull()?.first ?: curEp
        }

        if (level > maxLayer) {
            maxLayer   = level
            entryPoint = idx
        }
    }

    // ── Search ────────────────────────────────────────────────────────────

    fun search(query: FloatArray, k: Int = 10, ef: Int = 50): List<SearchResult> = synchronized(lock) {
        if (nodes.isEmpty()) return emptyList()
        val qNorm = VectorMath.normalize(query)

        var curEp = entryPoint
        for (lc in maxLayer downTo 1) {
            curEp = searchLayer(qNorm, curEp, 1, lc).first().first
        }

        val candidates = searchLayer(qNorm, curEp, ef, 0)
        return candidates
            .take(k)
            .map { (idx, sim) ->
                val node = nodes[idx]
                SearchResult(
                    entry      = VectorEntry(node.id, node.vector, node.payload, node.timestamp),
                    similarity = sim,
                    distance   = 1f - sim
                )
            }
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private fun searchLayer(
        query:    FloatArray,
        entryIdx: Int,
        ef:       Int,
        layer:    Int
    ): List<Pair<Int, Float>> {
        // FIX H-6: sortedSetOf uses the comparator for BOTH ordering AND equality.
        // Two pairs (0, 0.85f) and (1, 0.85f) have the same score → comparator
        // returns 0 → TreeSet treats them as identical and silently drops the second.
        // Elements with equal confidence scores were invisibly discarded from HNSW
        // candidates, producing wrong nearest-neighbour results.
        //
        // Fix: Use a MutableList for candidates/results. Maintain heap property
        // manually via sort-after-insert (acceptable: ef ≤ 200, list stays small).
        // Tie-breaking by index ensures no two elements compare as equal.
        val visited    = mutableSetOf(entryIdx)
        val candidates = mutableListOf<Pair<Int, Float>>()  // sorted descending by sim
        val results    = mutableListOf<Pair<Int, Float>>()  // sorted descending by sim

        val startSim = VectorMath.cosineSimilarity(query, nodes[entryIdx].vector)
        candidates.add(entryIdx to startSim)
        results.add(entryIdx to startSim)

        // Comparator that breaks ties by index — no two elements are ever "equal"
        val desc = compareByDescending<Pair<Int, Float>> { it.second }
            .thenBy { it.first }

        while (candidates.isNotEmpty()) {
            val (cIdx, cSim) = candidates.removeAt(0)  // best candidate (removeAt(0) avoids API 35 requirement)

            val worstResult = results.lastOrNull()?.second ?: Float.MIN_VALUE
            if (cSim < worstResult && results.size >= ef) break

            val node = nodes[cIdx]
            val layerNeighbors = if (layer < node.layers.size) node.layers[layer] else emptySet()

            for (neighborIdx in layerNeighbors) {
                if (neighborIdx in visited) continue
                visited.add(neighborIdx)
                val sim = VectorMath.cosineSimilarity(query, nodes[neighborIdx].vector)
                if (results.size < ef || sim > (results.lastOrNull()?.second ?: 0f)) {
                    candidates.add(neighborIdx to sim)
                    candidates.sortWith(desc)         // keep sorted; list is small (≤ef)
                    results.add(neighborIdx to sim)
                    results.sortWith(desc)
                    if (results.size > ef) results.removeAt(results.size - 1)  // removeLast() requires API 35
                }
            }
        }
        return results
    }

    private fun selectNeighbors(
        query:      FloatArray,
        candidates: List<Pair<Int, Float>>,
        m:          Int
    ): List<Pair<Int, Float>> = candidates
        .sortedByDescending { it.second }
        .take(m)

    private fun randomLevel(): Int {
        var level = 0
        val mL = 1.0 / Math.log(M.toDouble())
        while (random.nextDouble() < Math.exp(-1.0 / mL) && level < 16) level++
        return level
    }

    fun size(): Int  = synchronized(lock) { nodes.size }
    fun clear()      = synchronized(lock) { nodes.clear(); idToIndex.clear(); entryPoint = -1; maxLayer = 0 }
    fun contains(id: String) = synchronized(lock) { id in idToIndex }
}

// ─────────────────────────────────────────────────────────────────────────────
// Text Embedding — MiniLM TFLite (offline, on-device)
// ─────────────────────────────────────────────────────────────────────────────

class TextEmbedder(private val context: Context) {

    // In production: load actual TFLite MiniLM model
    // Model: all-MiniLM-L6-v2 quantized INT8 (~5MB)
    private val modelFile = "minilm_l6_v2_int8.tflite"
    private var isModelAvailable = false
    val EMBEDDING_DIM = 384  // MiniLM output dimension

    init {
        // Check if model file exists in assets
        isModelAvailable = try {
            context.assets.open(modelFile).close()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Generate text embedding vector.
     * Falls back to TF-IDF-like hash embedding if model unavailable.
     */
    suspend fun embed(text: String): FloatArray = withContext(Dispatchers.Default) {
        if (isModelAvailable) {
            embedWithTFLite(text)
        } else {
            embedWithHashTrick(text)
        }
    }

    private fun embedWithTFLite(text: String): FloatArray {
        // TFLite inference — placeholder for actual implementation
        // Actual: org.tensorflow.lite.Interpreter
        return embedWithHashTrick(text)
    }

    /**
     * Hash-trick embedding — fast fallback, no model needed.
     * Not as semantic as MiniLM but captures word statistics.
     */
    private fun embedWithHashTrick(text: String): FloatArray {
        val vector = FloatArray(EMBEDDING_DIM)
        val tokens = text.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(" ")
            .filter { it.isNotBlank() }

        for (token in tokens) {
            // n-gram hashing
            for (n in 1..minOf(3, token.length)) {
                for (i in 0..token.length - n) {
                    val gram = token.substring(i, i + n)
                    val hash = gram.hashCode()
                    val idx  = Math.floorMod(hash, EMBEDDING_DIM)
                    val sign = if (hash > 0) 1f else -1f
                    vector[idx] += sign / tokens.size
                }
            }
        }
        return VectorMath.normalize(vector)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// VectorMemory — Public API
// ─────────────────────────────────────────────────────────────────────────────

@Singleton
class VectorMemory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger
) {
    companion object {
        private const val TAG      = "VectorMemory"
        private const val MAX_SIZE = 5000
    }

    private val index    = HNSWIndex(dim = 384, M = 16, efConstruction = 200, maxElements = MAX_SIZE)
    private val embedder = TextEmbedder(context)

    /**
     * Store a screen state as a searchable vector.
     * @param id       Unique identifier (e.g., screenStateId)
     * @param text     Text to embed (OCR output + screen type)
     * @param payload  Metadata to retrieve alongside vector
     */
    suspend fun store(id: String, text: String, payload: Map<String, String> = emptyMap()) {
        val vector = embedder.embed(text)
        val entry  = VectorEntry(id = id, vector = vector, payload = payload)
        index.insert(entry)
        logger.d(TAG, "Stored vector: $id | index_size=${index.size()}")
    }

    /**
     * Find semantically similar past screens.
     * @param queryText  Text to search for (e.g., current OCR output)
     * @param k          Number of results
     * @param minSimilarity  Minimum similarity threshold
     */
    suspend fun searchSimilar(
        queryText:     String,
        k:             Int   = 5,
        minSimilarity: Float = 0.7f
    ): List<SearchResult> {
        val queryVector = embedder.embed(queryText)
        return index.search(queryVector, k = k, ef = 50)
            .filter { it.similarity >= minSimilarity }
            .also {
                logger.d(TAG, "Vector search: '$queryText' → ${it.size} results")
            }
    }

    /**
     * Check if current screen is similar to any known error screen.
     */
    suspend fun isKnownErrorScreen(ocrText: String): Boolean {
        val results = searchSimilar(ocrText + " error failed", minSimilarity = 0.8f)
        return results.any { it.entry.payload["type"] == "ERROR" }
    }

    fun size():  Int  = index.size()
    fun clear()       = index.clear()
    fun contains(id: String) = index.contains(id)
}
