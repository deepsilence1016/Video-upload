package com.visionagent.core.action

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.visionagent.core.AgentOrchestrator
import com.visionagent.core.event.*
import com.visionagent.utils.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

// ============================================================
// AgentAccessibilityService — Own App Automation
//
// Purpose: Accessibility service used for automating the
// developer's OWN app only (as per project requirements).
//
// Security Notes:
// - packageNames restricted to own app only in config
// - No cross-app automation
// - All actions logged for audit
//
// Usage:
// - GestureDescription API for taps/swipes
// - AccessibilityNodeInfo for text input
// - Window state events for screen change detection
// ============================================================

@AndroidEntryPoint
class AgentAccessibilityService : AccessibilityService() {

    @Inject lateinit var eventBus: AgentEventBus
    @Inject lateinit var logger: Logger

    companion object {
        private const val TAG = "AgentAccessibilityService"
        private const val TARGET_PACKAGE = "com.visionagent.app"  // ← Your app package
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onServiceConnected() {
        super.onServiceConnected()

        // Configure service — restrict to own package
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            packageNames = arrayOf(TARGET_PACKAGE)  // Own app only
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }

        logger.i(TAG, "AccessibilityService connected | package=$TARGET_PACKAGE")

        // Notify engines that service is ready
        // ActionEngine picks this up via injection
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        // Only process events from our own app
        if (event.packageName?.toString() != TARGET_PACKAGE) return

        serviceScope.launch {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    handleWindowStateChange(event)
                }
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    logger.v(TAG, "Click event: ${event.contentDescription}")
                }
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    logger.v(TAG, "Text changed: ${event.text}")
                }
                else -> { /* ignore */ }
            }
        }
    }

    private fun handleWindowStateChange(event: AccessibilityEvent) {
        logger.d(TAG, "Window state changed: ${event.className} | package=${event.packageName}")
        // Window change detected — this triggers a new frame capture in ScreenCaptureEngine
    }

    override fun onInterrupt() {
        logger.w(TAG, "AccessibilityService interrupted")
        serviceScope.launch {
            eventBus.publish(
                AgentErrorEvent(
                    errorCode = AgentErrorCode.UNKNOWN,
                    message = "AccessibilityService interrupted",
                    isFatal = false,
                    sessionId = ""
                )
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        logger.i(TAG, "AccessibilityService destroyed")
    }

    /**
     * Find a node in the current window by text content.
     * Useful for text input targeting.
     */
    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        return rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.firstOrNull()
    }

    /**
     * Find a node by its view resource ID.
     */
    fun findNodeById(viewId: String): AccessibilityNodeInfo? {
        return rootInActiveWindow?.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull()
    }

    /**
     * Get all interactive nodes in current window (buttons, inputs).
     */
    fun getInteractiveNodes(): List<AccessibilityNodeInfo> {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        traverseNodes(rootInActiveWindow, nodes)
        return nodes.filter { it.isClickable || it.isEditable }
    }

    private fun traverseNodes(
        node: AccessibilityNodeInfo?,
        collector: MutableList<AccessibilityNodeInfo>
    ) {
        node ?: return
        collector.add(node)
        for (i in 0 until node.childCount) {
            traverseNodes(node.getChild(i), collector)
        }
    }
}
