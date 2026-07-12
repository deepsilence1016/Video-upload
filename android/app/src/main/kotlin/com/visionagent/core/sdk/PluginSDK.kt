package com.visionagent.core.sdk

import com.visionagent.core.event.*
import com.visionagent.core.memory.MemoryEngine
import com.visionagent.core.rule.Rule
import com.visionagent.core.rule.RuleRegistry
import com.visionagent.core.workflow.engine.Workflow
import com.visionagent.core.workflow.engine.WorkflowEngine

// ============================================================
// Plugin SDK — Public API for Plugin Developers
//
// Plugin बनाने के लिए:
// 1. AgentPlugin interface implement करो
// 2. PluginContext का use करो (sandboxed access)
// 3. Register करो: pluginRegistry.register(MyPlugin())
//
// Example Plugin:
//   class MyPlugin : AgentPlugin {
//     override val metadata = PluginMetadata(
//       id = "com.example.myplugin",
//       name = "My Plugin",
//       version = "1.0.0",
//       permissions = setOf(PluginPermission.READ_SCREEN)
//     )
//     override suspend fun onInitialize(ctx: PluginContext): Boolean {
//       ctx.subscribe<UIElementDetectedEvent>(PluginPermission.READ_SCREEN) { event ->
//         ctx.log("Screen: ${event.screenType}")
//       }
//       return true
//     }
//   }
// ============================================================

/**
 * SDK Version for backward compatibility
 */
const val PLUGIN_SDK_VERSION = "1.0.0"
const val MIN_AGENT_VERSION  = "1.0.0"

// ─────────────────────────────────────────────────────────────────────────────
// SDK Builder — Fluent API for creating rules and workflows from plugins
// ─────────────────────────────────────────────────────────────────────────────

class SDKRuleBuilder(private val pluginId: String) {
    private var id          = "$pluginId.rule.${System.nanoTime()}"
    private var name        = "Plugin Rule"
    private var priority    = 50
    private val conditions  = mutableListOf<com.visionagent.core.rule.RuleCondition>()
    private var action: com.visionagent.core.rule.RuleAction? = null
    private val tags        = mutableSetOf(pluginId)

    fun id(id: String)                = apply { this.id = "$pluginId.$id" }
    fun name(name: String)            = apply { this.name = name }
    fun priority(priority: Int)       = apply { this.priority = priority.coerceIn(0, 9000) }
    fun tag(tag: String)              = apply { tags.add(tag) }

    fun whenScreen(screenType: ScreenType) = apply {
        conditions.add(com.visionagent.core.rule.RuleCondition(
            com.visionagent.core.rule.ConditionType.SCREEN_TYPE,
            com.visionagent.core.rule.ConditionOperator.EQUALS, screenType))
    }

    fun whenTextContains(text: String) = apply {
        conditions.add(com.visionagent.core.rule.RuleCondition(
            com.visionagent.core.rule.ConditionType.TEXT_CONTAINS,
            com.visionagent.core.rule.ConditionOperator.CONTAINS, text))
    }

    fun whenElementPresent(type: UIElementType) = apply {
        conditions.add(com.visionagent.core.rule.RuleCondition(
            com.visionagent.core.rule.ConditionType.ELEMENT_PRESENT,
            com.visionagent.core.rule.ConditionOperator.EQUALS, type))
    }

    fun thenTap(byText: String? = null, byType: UIElementType? = null) = apply {
        action = com.visionagent.core.rule.RuleAction(
            ActionType.TAP,
            com.visionagent.core.rule.ElementSelector(byType = byType, byText = byText)
        )
    }

    fun thenWait(durationMs: Long = 1000L) = apply {
        action = com.visionagent.core.rule.RuleAction(
            ActionType.WAIT, null, mapOf("durationMs" to durationMs))
    }

    fun thenNavigateBack() = apply {
        action = com.visionagent.core.rule.RuleAction(ActionType.NAVIGATE_BACK, null)
    }

    fun build(): Rule {
        require(conditions.isNotEmpty()) { "Rule must have at least one condition" }
        require(action != null) { "Rule must have an action" }
        return Rule(id, name, priority, conditions, action!!, tags = tags)
    }
}

class SDKWorkflowBuilder(private val pluginId: String) {
    private var name         = "Plugin Workflow"
    private var description  = ""
    private val triggers     = mutableListOf<com.visionagent.core.workflow.engine.WorkflowTriggerConfig>()
    private val blocks       = mutableListOf<com.visionagent.core.workflow.engine.WorkflowBlock>()

    fun name(name: String)              = apply { this.name = name }
    fun description(desc: String)       = apply { this.description = desc }
    fun onScreenChange(screenType: ScreenType) = apply {
        triggers.add(com.visionagent.core.workflow.engine.WorkflowTriggerConfig(
            triggerType = "SCREEN_CHANGE", parameters = mapOf("screen_type" to screenType.name)))
    }
    fun onManual() = apply {
        triggers.add(com.visionagent.core.workflow.engine.WorkflowTriggerConfig(triggerType = "MANUAL"))
    }
    fun addBlock(block: com.visionagent.core.workflow.engine.WorkflowBlock) = apply { blocks.add(block) }
    fun log(message: String) = apply {
        blocks.add(com.visionagent.core.workflow.engine.LogBlock(message = "[$pluginId] $message"))
    }
    fun wait(ms: Long) = apply {
        blocks.add(com.visionagent.core.workflow.engine.WaitBlock(
            waitType = com.visionagent.core.workflow.engine.WaitType.FIXED, durationMs = ms))
    }
    fun tap(byText: String) = apply {
        blocks.add(com.visionagent.core.workflow.engine.ActionBlock(
            actionType = ActionType.TAP,
            targetSelector = com.visionagent.core.workflow.engine.TargetSelector(byText = byText)))
    }

    fun build(): Workflow {
        require(triggers.isNotEmpty()) { "Workflow must have at least one trigger" }
        return Workflow(name = name, description = description, triggers = triggers, blocks = blocks)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Plugin Context Extensions — Convenience methods
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Extension function for creating rule builders within plugin context
 */
fun com.visionagent.core.plugin.PluginContext.ruleBuilder() = SDKRuleBuilder(pluginId)

/**
 * Extension function for creating workflow builders within plugin context
 */
fun com.visionagent.core.plugin.PluginContext.workflowBuilder() = SDKWorkflowBuilder(pluginId)

// ─────────────────────────────────────────────────────────────────────────────
// Example Plugin (included as SDK documentation)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * ExamplePlugin — Demonstrates how to write a Vision Agent plugin.
 * Copy this template to create your own plugin.
 */
class ExamplePlugin : com.visionagent.core.plugin.AgentPlugin {

    override val metadata = com.visionagent.core.plugin.PluginMetadata(
        id          = "com.example.myplugin",
        name        = "Example Plugin",
        version     = "1.0.0",
        author      = "Your Name",
        description = "Example plugin that logs screen changes",
        permissions = setOf(
            com.visionagent.core.plugin.PluginPermission.READ_SCREEN,
            com.visionagent.core.plugin.PluginPermission.READ_OCR,
            com.visionagent.core.plugin.PluginPermission.WRITE_RULES
        ),
        minAgentVersion = MIN_AGENT_VERSION
    )

    override suspend fun onInitialize(ctx: com.visionagent.core.plugin.PluginContext): Boolean {

        ctx.log("ExamplePlugin initializing...")

        // 1. Subscribe to screen changes
        ctx.subscribe<UIElementDetectedEvent>(
            com.visionagent.core.plugin.PluginPermission.READ_SCREEN
        ) { event ->
            ctx.log("Screen: ${event.screenType} | Elements: ${event.elements.size}")
        }

        // 2. Subscribe to OCR results
        ctx.subscribe<OCRCompletedEvent>(
            com.visionagent.core.plugin.PluginPermission.READ_OCR
        ) { event ->
            if (event.text.contains("your_keyword", ignoreCase = true)) {
                ctx.log("Keyword detected: ${event.text.take(50)}")
            }
        }

        // 3. Register a custom rule
        val myRule = ctx.ruleBuilder()
            .name("Example: Auto-dismiss dialog")
            .priority(100)
            .whenScreen(ScreenType.DIALOG)
            .thenTap(byText = "OK")
            .build()
        // In production: publish via EventBus or use RuleEngine directly
        ctx.log("Rule created: ${myRule.name}")

        // 4. Create a workflow
        val myWorkflow = ctx.workflowBuilder()
            .name("Example Workflow")
            .onScreenChange(ScreenType.ERROR)
            .log("Error detected by plugin")
            .wait(1000)
            .tap("Retry")
            .build()
        ctx.log("Workflow created: ${myWorkflow.name}")

        ctx.log("ExamplePlugin initialized successfully")
        return true
    }

    override suspend fun onSessionStart(sessionId: String) {
        println("[ExamplePlugin] Session started: $sessionId")
    }

    override suspend fun onStop() {
        println("[ExamplePlugin] Plugin stopped")
    }
}
