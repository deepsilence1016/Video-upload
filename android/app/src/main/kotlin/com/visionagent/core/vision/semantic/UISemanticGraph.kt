package com.visionagent.core.vision.semantic

import com.visionagent.core.event.DetectedUIElement
import com.visionagent.core.event.Rect
import com.visionagent.core.event.UIElementType
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

// ============================================================
// UISemanticGraph — Spatial + Semantic UI Relationship Engine
//
// Problem: Vision Engine detects elements individually.
// But UI has relationships: "This button is inside that dialog",
// "These list items are siblings", "Label belongs to this field".
//
// Solution: Build a graph where:
// - Nodes = Detected UI elements
// - Edges = Spatial/semantic relationships
//
// Relationships detected:
// - CONTAINS       : Parent/child (spatial containment)
// - SIBLING        : Same parent, similar size/position
// - LABELS         : Text element adjacent to input field
// - CONFIRMS       : "OK" button in a dialog
// - DISMISSES      : "Cancel"/"X" button
// - SCROLL_TARGET  : Scrollable content area
// - NAVIGATION     : Nav item → screen association
// - FORM_FIELD     : Input + label pair
//
// Applications:
// - Better tap targeting (tap the right element in a group)
// - Context-aware OCR (label text belongs to which field?)
// - Smarter rule matching (dialog has confirm + dismiss buttons)
// - Planner gets semantic context not just raw elements
//
// Algorithm: O(N²) relationship detection, N < 50 elements typical
// ============================================================

@Serializable
enum class RelationshipType {
    CONTAINS,         // A spatially contains B
    CONTAINED_BY,     // A is contained in B
    SIBLING,          // Same parent, parallel layout
    LABELS,           // Text A is label for element B
    CONFIRMS,         // A is the confirm action for B
    DISMISSES,        // A dismisses/cancels B
    SCROLL_TARGET,    // B is scrollable content under A
    FORM_FIELD_PAIR,  // Label + Input pairing
    NAVIGATION_TARGET,// Nav item leads to content
    ABOVE,            // A is directly above B
    BELOW,            // A is directly below B
    LEFT_OF,          // A is to the left of B
    RIGHT_OF,         // A is to the right of B
    OVERLAY           // A overlays B (dialog/popup)
}

@Serializable
data class SemanticNode(
    val elementId:    String,
    // FIX SERIAL-1: DetectedUIElement is not @Serializable — use @Contextual to defer
    // to a runtime serializer, or store only the serializable fields we need.
    // DetectedUIElement lives in EventBus — add @Serializable there is simplest fix.
    @kotlinx.serialization.Contextual
    val element:      DetectedUIElement,
    val semanticRole: SemanticRole,
    val depth:        Int = 0,     // Layout depth (0 = root)
    val groupId:      String = ""  // Which visual group this belongs to
)

@Serializable
enum class SemanticRole {
    ACTION_PRIMARY,    // Main CTA button
    ACTION_SECONDARY,  // Cancel / back button
    ACTION_DESTRUCTIVE,// Delete / remove
    INPUT_FIELD,       // Text input
    INPUT_LABEL,       // Label for input
    CONTENT_TITLE,     // Screen/section title
    CONTENT_BODY,      // Body text
    CONTENT_LIST_ITEM, // List entry
    NAVIGATION_ITEM,   // Tab/nav entry
    CONTAINER,         // Card, dialog, section
    DECORATIVE,        // Icons, images (non-interactive)
    UNKNOWN
}

@Serializable
data class SemanticEdge(
    val fromId:       String,
    val toId:         String,
    val relationship: RelationshipType,
    val confidence:   Float,
    val metadata:     Map<String, String> = emptyMap()
)

@Serializable
data class UISemanticGraph(
    val nodes:      List<SemanticNode>,
    val edges:      List<SemanticEdge>,
    val rootIds:    List<String>,       // Top-level elements (not contained by any)
    val dialogId:   String?,            // If a dialog is present
    val focusedId:  String?,            // Most likely user-intended interaction target
    val screenWidth:  Int,
    val screenHeight: Int
) {
    fun getNode(id: String) = nodes.find { it.elementId == id }
    fun getEdgesFrom(id: String) = edges.filter { it.fromId == id }
    fun getEdgesTo(id: String)   = edges.filter { it.toId   == id }
    fun getRelated(id: String, type: RelationshipType) =
        edges.filter { it.fromId == id && it.relationship == type }
             .mapNotNull { getNode(it.toId) }
    fun getChildren(id: String) = getRelated(id, RelationshipType.CONTAINS)
    fun getParent(id: String)   = getRelated(id, RelationshipType.CONTAINED_BY).firstOrNull()
    fun getSiblings(id: String) = getRelated(id, RelationshipType.SIBLING)
    fun getPrimaryAction() = nodes.find { it.semanticRole == SemanticRole.ACTION_PRIMARY }
}

// ─────────────────────────────────────────────────────────────────────────────
// Geometry Helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun Rect.contains(other: Rect): Boolean =
    left <= other.left && top <= other.top &&
    right >= other.right && bottom >= other.bottom

private fun Rect.area() = (right - left) * (bottom - top)
private fun Rect.centerX() = (left + right) / 2
private fun Rect.centerY() = (top + bottom) / 2
private fun Rect.width()  = right - left
private fun Rect.height() = bottom - top

private fun distance(a: Rect, b: Rect): Float {
    val dx = (a.centerX() - b.centerX()).toFloat()
    val dy = (a.centerY() - b.centerY()).toFloat()
    return sqrt(dx * dx + dy * dy)
}

private fun verticalOverlap(a: Rect, b: Rect): Boolean {
    val aCenter = a.centerY()
    return aCenter in b.top..b.bottom
}

private fun horizontalOverlap(a: Rect, b: Rect): Boolean {
    val aCenter = a.centerX()
    return aCenter in b.left..b.right
}

private fun isDirectlyAbove(a: Rect, b: Rect, tolerance: Int = 50): Boolean =
    abs(a.bottom - b.top) < tolerance && horizontalOverlap(a, b)

private fun isDirectlyBelow(a: Rect, b: Rect, tolerance: Int = 50): Boolean =
    abs(b.bottom - a.top) < tolerance && horizontalOverlap(a, b)

// ─────────────────────────────────────────────────────────────────────────────
// SemanticGraphBuilder
// ─────────────────────────────────────────────────────────────────────────────

@Singleton
class SemanticGraphBuilder @Inject constructor() {

    fun build(
        elements:     List<DetectedUIElement>,
        screenWidth:  Int,
        screenHeight: Int
    ): UISemanticGraph {

        if (elements.isEmpty()) return UISemanticGraph(
            emptyList(), emptyList(), emptyList(),
            null, null, screenWidth, screenHeight)

        // Step 1: Assign IDs and initial semantic roles
        val nodes = elements.mapIndexed { i, el ->
            SemanticNode(
                elementId    = "el_$i",
                element      = el,
                semanticRole = inferSemanticRole(el, i),
                depth        = 0
            )
        }

        // Step 2: Build edges
        val edges = mutableListOf<SemanticEdge>()

        for (i in nodes.indices) {
            for (j in nodes.indices) {
                if (i == j) continue
                val a = nodes[i]
                val b = nodes[j]
                detectRelationships(a, b, screenWidth, screenHeight)
                    .forEach { edges.add(it) }
            }
        }

        // Step 3: Compute layout depths via containment
        val depths = computeDepths(nodes, edges)
        val depthNodes = nodes.map { n -> n.copy(depth = depths[n.elementId] ?: 0) }

        // Step 4: Assign group IDs (sibling clusters)
        val grouped = assignGroups(depthNodes, edges)

        // Step 5: Find root nodes (not contained by anything)
        val containedIds = edges.filter { it.relationship == RelationshipType.CONTAINED_BY }
                                .map { it.fromId }.toSet()
        val rootIds = grouped.filter { it.elementId !in containedIds }.map { it.elementId }

        // Step 6: Find dialog ID
        val dialogId = grouped.find {
            it.element.type == UIElementType.DIALOG ||
            it.element.type == UIElementType.POPUP
        }?.elementId

        // Step 7: Determine focused/primary element
        val focusedId = determineFocusedElement(grouped, edges, dialogId)

        return UISemanticGraph(
            nodes        = grouped,
            edges        = edges,
            rootIds      = rootIds,
            dialogId     = dialogId,
            focusedId    = focusedId,
            screenWidth  = screenWidth,
            screenHeight = screenHeight
        )
    }

    private fun detectRelationships(
        a:            SemanticNode,
        b:            SemanticNode,
        screenWidth:  Int,
        screenHeight: Int
    ): List<SemanticEdge> {
        val edges = mutableListOf<SemanticEdge>()
        val aBounds = a.element.bounds
        val bBounds = b.element.bounds

        // CONTAINS relationship
        if (aBounds.contains(bBounds) && aBounds.area() > bBounds.area() * 1.5) {
            val conf = minOf(1.0f, (aBounds.area().toFloat() / bBounds.area()) * 0.1f)
            edges.add(SemanticEdge(a.elementId, b.elementId,
                RelationshipType.CONTAINS, conf.coerceIn(0.5f, 0.95f)))
            edges.add(SemanticEdge(b.elementId, a.elementId,
                RelationshipType.CONTAINED_BY, conf.coerceIn(0.5f, 0.95f)))
        }

        // SIBLING — similar size, same row or column
        val sizeRatio = minOf(aBounds.area(), bBounds.area()).toFloat() /
                        maxOf(aBounds.area(), bBounds.area()).toFloat()
        val sameRow = abs(aBounds.centerY() - bBounds.centerY()) < 30
        val sameCol = abs(aBounds.centerX() - bBounds.centerX()) < 30
        if (sizeRatio > 0.7f && (sameRow || sameCol)) {
            edges.add(SemanticEdge(a.elementId, b.elementId,
                RelationshipType.SIBLING, sizeRatio * 0.8f))
        }

        // LABELS — text element directly above/left of input
        if (a.element.type == UIElementType.TEXT_FIELD ||
            a.element.type == UIElementType.CHECKBOX) {
            if (b.element.type == UIElementType.UNKNOWN &&
                b.element.text != null &&
                (isDirectlyAbove(bBounds, aBounds, 40) ||
                 isLeftOf(bBounds, aBounds, 20))) {
                edges.add(SemanticEdge(b.elementId, a.elementId,
                    RelationshipType.LABELS, 0.8f,
                    mapOf("label_text" to (b.element.text ?: ""))))
                edges.add(SemanticEdge(a.elementId, b.elementId,
                    RelationshipType.FORM_FIELD_PAIR, 0.8f))
            }
        }

        // CONFIRMS / DISMISSES — button text analysis
        if (b.element.type == UIElementType.BUTTON) {
            val text = b.element.text?.lowercase() ?: ""
            if (text in setOf("ok", "yes", "confirm", "continue", "submit", "done", "accept")) {
                edges.add(SemanticEdge(a.elementId, b.elementId,
                    RelationshipType.CONFIRMS, 0.9f,
                    mapOf("button_text" to text)))
            }
            if (text in setOf("cancel", "no", "dismiss", "close", "skip", "back", "×", "x")) {
                edges.add(SemanticEdge(a.elementId, b.elementId,
                    RelationshipType.DISMISSES, 0.9f,
                    mapOf("button_text" to text)))
            }
        }

        // ABOVE / BELOW / LEFT_OF / RIGHT_OF — directional
        when {
            isDirectlyAbove(aBounds, bBounds) ->
                edges.add(SemanticEdge(a.elementId, b.elementId, RelationshipType.ABOVE, 0.85f))
            isDirectlyBelow(aBounds, bBounds) ->
                edges.add(SemanticEdge(a.elementId, b.elementId, RelationshipType.BELOW, 0.85f))
            isLeftOf(aBounds, bBounds, 30) ->
                edges.add(SemanticEdge(a.elementId, b.elementId, RelationshipType.LEFT_OF, 0.8f))
            isRightOf(aBounds, bBounds, 30) ->
                edges.add(SemanticEdge(a.elementId, b.elementId, RelationshipType.RIGHT_OF, 0.8f))
        }

        // OVERLAY — smaller element overlapping larger (dialog/popup)
        if (a.element.type == UIElementType.DIALOG ||
            a.element.type == UIElementType.POPUP) {
            if (boundsOverlap(aBounds, bBounds)) {
                edges.add(SemanticEdge(a.elementId, b.elementId,
                    RelationshipType.OVERLAY, 0.9f))
            }
        }

        return edges
    }

    private fun inferSemanticRole(el: DetectedUIElement, index: Int): SemanticRole {
        val text = el.text?.lowercase() ?: ""
        return when (el.type) {
            UIElementType.BUTTON -> when {
                text in setOf("ok", "confirm", "submit", "done", "continue", "accept", "yes") ->
                    SemanticRole.ACTION_PRIMARY
                text in setOf("cancel", "no", "dismiss", "skip", "close") ->
                    SemanticRole.ACTION_SECONDARY
                text in setOf("delete", "remove", "clear", "reset") ->
                    SemanticRole.ACTION_DESTRUCTIVE
                else -> SemanticRole.ACTION_SECONDARY
            }
            UIElementType.TEXT_FIELD -> SemanticRole.INPUT_FIELD
            UIElementType.DIALOG,
            UIElementType.POPUP      -> SemanticRole.CONTAINER
            UIElementType.NAVIGATION_BAR -> SemanticRole.NAVIGATION_ITEM
            UIElementType.LIST_ITEM  -> SemanticRole.CONTENT_LIST_ITEM
            UIElementType.IMAGE      -> SemanticRole.DECORATIVE
            UIElementType.ICON       -> SemanticRole.DECORATIVE
            else                     -> SemanticRole.UNKNOWN
        }
    }

    private fun computeDepths(
        nodes: List<SemanticNode>,
        edges: List<SemanticEdge>
    ): Map<String, Int> {
        val depths = mutableMapOf<String, Int>()
        nodes.forEach { depths[it.elementId] = 0 }

        // BFS from root nodes
        val containedBy = edges.filter { it.relationship == RelationshipType.CONTAINED_BY }
                               .associate { it.fromId to it.toId }
        nodes.forEach { node ->
            var current = node.elementId
            var depth   = 0
            val visited = mutableSetOf<String>()
            while (current in containedBy && current !in visited) {
                visited.add(current)
                current = containedBy[current] ?: break
                depth++
            }
            depths[node.elementId] = depth
        }
        return depths
    }

    private fun assignGroups(
        nodes: List<SemanticNode>,
        edges: List<SemanticEdge>
    ): List<SemanticNode> {
        val groups = mutableMapOf<String, String>()  // elementId -> groupId
        var groupCounter = 0

        edges.filter { it.relationship == RelationshipType.SIBLING }.forEach { edge ->
            val gA = groups[edge.fromId]
            val gB = groups[edge.toId]
            when {
                gA == null && gB == null -> {
                    val gId = "g_${groupCounter++}"
                    groups[edge.fromId] = gId
                    groups[edge.toId]   = gId
                }
                gA != null && gB == null -> groups[edge.toId]   = gA
                gA == null && gB != null -> groups[edge.fromId] = gB
                else -> { /* already in groups */ }
            }
        }

        return nodes.map { it.copy(groupId = groups[it.elementId] ?: "") }
    }

    private fun determineFocusedElement(
        nodes:    List<SemanticNode>,
        edges:    List<SemanticEdge>,
        dialogId: String?
    ): String? {
        // If dialog present → primary action in dialog
        if (dialogId != null) {
            val dialogChildren = edges.filter {
                it.fromId == dialogId && it.relationship == RelationshipType.CONTAINS
            }.map { it.toId }
            val primaryInDialog = nodes.find {
                it.elementId in dialogChildren &&
                it.semanticRole == SemanticRole.ACTION_PRIMARY
            }
            if (primaryInDialog != null) return primaryInDialog.elementId
        }
        // Otherwise highest-confidence primary action
        return nodes.maxByOrNull {
            when (it.semanticRole) {
                SemanticRole.ACTION_PRIMARY -> it.element.confidence + 0.3f
                SemanticRole.INPUT_FIELD    -> it.element.confidence + 0.1f
                else                        -> it.element.confidence
            }
        }?.elementId
    }

    private fun isLeftOf(a: Rect, b: Rect, gap: Int) =
        a.right < b.left + gap && abs(a.centerY() - b.centerY()) < 30
    private fun isRightOf(a: Rect, b: Rect, gap: Int) =
        b.right < a.left + gap && abs(a.centerY() - b.centerY()) < 30
    private fun boundsOverlap(a: Rect, b: Rect) =
        a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
}
