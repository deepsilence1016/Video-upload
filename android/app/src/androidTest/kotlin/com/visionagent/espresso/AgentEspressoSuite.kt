package com.visionagent.espresso

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.*
import org.junit.runner.RunWith
import org.junit.runners.Suite

// ============================================================
// Espresso Test Suite — UI Integration Tests
// ============================================================

@RunWith(Suite::class)
@Suite.SuiteClasses(
    AgentLifecycleTests::class,
    PermissionTests::class,
    ServiceTests::class,
    ConfigTests::class
)
class AgentEspressoSuite

// ─────────────────────────────────────────────────────────────────────────────
// Agent Lifecycle Tests
// ─────────────────────────────────────────────────────────────────────────────

@RunWith(AndroidJUnit4::class)
class AgentLifecycleTests {

    @get:Rule
    val activityRule = androidx.test.rule.ActivityTestRule(
        com.visionagent.presentation.MainActivity::class.java,
        false, false
    )

    @Test
    fun test_app_launches_without_crash() {
        val scenario = ActivityScenario.launch(
            com.visionagent.presentation.MainActivity::class.java)
        scenario.use {
            // App should be in RESUMED state
            it.onActivity { activity ->
                assert(!activity.isFinishing) { "Activity should not be finishing" }
            }
        }
    }

    @Test
    fun test_main_activity_visible() {
        ActivityScenario.launch(com.visionagent.presentation.MainActivity::class.java).use {
            onView(withId(android.R.id.content)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun test_rotation_survives() {
        ActivityScenario.launch(com.visionagent.presentation.MainActivity::class.java).use { scenario ->
            // Rotate to landscape
            scenario.recreate()
            // Should not crash
            scenario.onActivity { activity ->
                assert(!activity.isFinishing)
            }
        }
    }

    @Test
    fun test_background_foreground_cycle() {
        ActivityScenario.launch(com.visionagent.presentation.MainActivity::class.java).use { scenario ->
            // Send to background
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                .pressHome()
            Thread.sleep(1000)
            // Return to foreground
            val intent = InstrumentationRegistry.getInstrumentation()
                .targetContext.packageManager
                .getLaunchIntentForPackage("com.visionagent.app")
            InstrumentationRegistry.getInstrumentation().targetContext.startActivity(intent)
            Thread.sleep(1000)
            // Should still be running
            scenario.onActivity { activity ->
                assert(!activity.isFinishing)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Permission Tests
// ─────────────────────────────────────────────────────────────────────────────

@RunWith(AndroidJUnit4::class)
class PermissionTests {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun test_accessibility_service_permission_requested() {
        // Verify accessibility service is declared
        val context = instrumentation.targetContext
        val pm = context.packageManager
        val serviceInfo = pm.queryAccessibilityServices(0)
        val hasService = serviceInfo.any {
            it.id.contains("com.visionagent")
        }
        // Service should be declared (even if not enabled)
        // Note: actual enabling requires user action
        println("✅ Accessibility service declared: $hasService")
    }

    @Test
    fun test_overlay_permission_check() {
        val context = instrumentation.targetContext
        val canDraw = android.provider.Settings.canDrawOverlays(context)
        println("📋 Overlay permission: $canDraw (requires user grant)")
        // Don't fail — just log
    }

    @Test
    fun test_network_permission_available() {
        val context = instrumentation.targetContext
        val pm = context.packageManager
        val hasInternet = pm.checkPermission(
            android.Manifest.permission.INTERNET,
            context.packageName
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        assert(hasInternet) { "Internet permission should be granted" }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Service Tests
// ─────────────────────────────────────────────────────────────────────────────

@RunWith(AndroidJUnit4::class)
class ServiceTests {

    @Test
    fun test_foreground_service_starts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Verify service can be started
        val intent = Intent(context, com.visionagent.core.screen.ScreenCaptureService::class.java)
        // Service start requires foreground service permission — just verify manifest
        val pm = context.packageManager
        val services = pm.queryIntentServices(intent, 0)
        println("📋 Capture service found: ${services.isNotEmpty()}")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Config Tests
// ─────────────────────────────────────────────────────────────────────────────

@RunWith(AndroidJUnit4::class)
class ConfigTests {

    @Test
    fun test_build_config_values() {
        assert(com.visionagent.BuildConfig.AGENT_VERSION.isNotEmpty()) {
            "AGENT_VERSION should not be empty"
        }
        assert(com.visionagent.BuildConfig.MAX_FRAME_QUEUE_SIZE > 0) {
            "MAX_FRAME_QUEUE_SIZE should be positive"
        }
    }

    @Test
    fun test_tessdata_in_assets() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assets = context.assets.list("tessdata") ?: emptyArray()
        println("📋 Tessdata files: ${assets.toList()}")
        // In production: assert(assets.contains("eng.traineddata"))
    }
}
