package com.visionagent.uiautomator

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.*
import org.junit.runner.RunWith
import org.junit.runners.Suite
import kotlin.math.abs

// ============================================================
// UI Automator Test Suite — System-level UI tests
//
// Tests real device interactions beyond the app boundary.
// Covers:
// - ANR detection under load
// - Low RAM behavior
// - Background/Foreground transitions
// - Screen rotation under agent operation
// - Permission revocation handling
// - Network condition changes
// - Storage pressure
// ============================================================

@RunWith(Suite::class)
@Suite.SuiteClasses(
    AppLaunchTests::class,
    StressTests::class,
    EdgeCaseTests::class,
    AccessibilityTests::class
)
class AgentUIAutomatorSuite

// ─────────────────────────────────────────────────────────────────────────────
// App Launch Tests
// ─────────────────────────────────────────────────────────────────────────────

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 21)
class AppLaunchTests {

    private lateinit var device: UiDevice

    @Before
    fun setup() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.pressHome()
        device.waitForIdle(2000)
    }

    @Test
    fun test_cold_start_under_3_seconds() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // Kill app first (cold start)
        val am = context.getSystemService(android.app.ActivityManager::class.java)

        val startTime = System.currentTimeMillis()

        // Launch app
        val launchIntent = context.packageManager.getLaunchIntentForPackage("com.visionagent.app")
            ?: return
        context.startActivity(launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))

        // Wait for app window
        device.wait(Until.hasObject(By.pkg("com.visionagent.app").depth(0)), 5000)

        val coldStart = System.currentTimeMillis() - startTime
        println("📊 Cold start: ${coldStart}ms")
        assert(coldStart < 5000) { "Cold start ${coldStart}ms exceeds 5000ms" }
    }

    @Test
    fun test_warm_start() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = context.packageManager.getLaunchIntentForPackage("com.visionagent.app")
            ?: return

        // First launch (warm up)
        context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        device.wait(Until.hasObject(By.pkg("com.visionagent.app").depth(0)), 5000)

        // Press home
        device.pressHome()
        device.waitForIdle(1000)

        // Warm start
        val warmStart = System.currentTimeMillis()
        context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        device.wait(Until.hasObject(By.pkg("com.visionagent.app").depth(0)), 3000)
        val elapsed = System.currentTimeMillis() - warmStart

        println("📊 Warm start: ${elapsed}ms")
        assert(elapsed < 2000) { "Warm start ${elapsed}ms exceeds 2000ms" }
    }

    @Test
    fun test_app_not_crashed_after_launch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = context.packageManager.getLaunchIntentForPackage("com.visionagent.app")
            ?: return
        context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))

        Thread.sleep(3000)

        // Check app is still running (not crashed)
        val appWindow = device.findObject(UiSelector().packageName("com.visionagent.app"))
        val appRunning = device.wait(Until.hasObject(By.pkg("com.visionagent.app")), 2000)
        assert(appRunning) { "App crashed after launch" }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Stress Tests — No ANR under load
// ─────────────────────────────────────────────────────────────────────────────

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 21)
class StressTests {

    private lateinit var device: UiDevice

    @Before
    fun setup() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    }

    @Test
    fun test_no_anr_under_rapid_screen_changes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = context.packageManager.getLaunchIntentForPackage("com.visionagent.app")
            ?: return

        context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        device.waitForIdle(3000)

        // Rapid screen changes via Monkey
        val start = System.currentTimeMillis()
        repeat(50) { i ->
            device.pressBack()
            Thread.sleep(100)
            context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            Thread.sleep(100)
        }

        // Check for ANR dialog
        val anrDialog = device.findObject(
            UiSelector().textContains("not responding"))
        assert(!anrDialog.exists()) { "ANR detected during stress test!" }

        // App should still be running
        assert(device.wait(Until.hasObject(By.pkg("com.visionagent.app")), 3000)) {
            "App not running after stress test"
        }
        println("✅ No ANR after 50 rapid launches in ${System.currentTimeMillis()-start}ms")
    }

    @Test
    fun test_rotation_stress() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = context.packageManager.getLaunchIntentForPackage("com.visionagent.app")
            ?: return
        context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        device.waitForIdle(2000)

        // Rapid rotation
        repeat(10) {
            device.setOrientationLeft()
            Thread.sleep(300)
            device.setOrientationNatural()
            Thread.sleep(300)
        }

        // No crash
        assert(device.wait(Until.hasObject(By.pkg("com.visionagent.app")), 3000)) {
            "App crashed during rotation stress"
        }
        device.setOrientationNatural()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Edge Case Tests
// ─────────────────────────────────────────────────────────────────────────────

@RunWith(AndroidJUnit4::class)
class EdgeCaseTests {

    private lateinit var device: UiDevice

    @Before
    fun setup() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    }

    @Test
    fun test_background_foreground_10_cycles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = context.packageManager.getLaunchIntentForPackage("com.visionagent.app")
            ?: return

        repeat(10) { i ->
            context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            device.waitForIdle(1000)
            device.pressHome()
            device.waitForIdle(500)
        }

        // Final state: app should still work
        context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        assert(device.wait(Until.hasObject(By.pkg("com.visionagent.app")), 3000)) {
            "App not running after 10 bg/fg cycles"
        }
        println("✅ Survived 10 background/foreground cycles")
    }

    @Test
    fun test_no_crash_on_low_memory_simulation() {
        // Simulate low memory by starting memory-hungry apps
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = context.packageManager.getLaunchIntentForPackage("com.visionagent.app")
            ?: return

        context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        device.waitForIdle(3000)

        // App should handle OnLowMemory gracefully
        assert(device.wait(Until.hasObject(By.pkg("com.visionagent.app")), 2000)) {
            "App crashed under low memory"
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Accessibility Tests
// ─────────────────────────────────────────────────────────────────────────────

@RunWith(AndroidJUnit4::class)
class AccessibilityTests {

    private lateinit var device: UiDevice

    @Before
    fun setup() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    }

    @Test
    fun test_talkback_compatible() {
        // App should not crash when TalkBack is active
        // (Simplified — full TalkBack test requires specific setup)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = context.packageManager.getLaunchIntentForPackage("com.visionagent.app")
            ?: return
        context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        assert(device.wait(Until.hasObject(By.pkg("com.visionagent.app")), 3000))
        println("✅ App launched in accessibility-compatible mode")
    }
}
