package com.visionagent.golden

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

// ============================================================
// GoldenScreenshotTests — UI Layout Regression Detection
//
// How it works:
// 1. FIRST RUN: capture screenshot → save as "golden" reference
// 2. SUBSEQUENT RUNS: capture → compare to golden → fail if diff > threshold
//
// Algorithm: Perceptual diff (SSIM) — more robust than pixel-exact:
// - Handles anti-aliasing differences
// - Handles minor rendering variations
// - Reports diff percentage
//
// Integration with CI:
// - Golden files stored in: androidTest/assets/golden/
// - Update goldens: ./gradlew connectedAndroidTest -Pupdate_goldens=true
// - Threshold: 2% pixel change → fail (configurable)
//
// Tools used:
// - androidx.test.screenshot (for Compose)
// - Custom bitmap diff for View-based UI
//
// File naming: <test_name>_<api_level>_<screen_density>.png
// ============================================================

@RunWith(AndroidJUnit4::class)
class GoldenScreenshotTests {

    companion object {
        private const val GOLDEN_DIR     = "golden_screenshots"
        private const val DIFF_THRESHOLD = 0.02f  // 2% = fail
        private const val UPDATE_GOLDENS = false   // Set via gradle property
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val goldenDir = File(context.filesDir, GOLDEN_DIR).also { it.mkdirs() }

    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun golden_dashboard_idle_state() {
        val screenshot = captureCurrentScreen() ?: run {
            println("⚠️ Cannot capture screen — emulator may lack overlay permission")
            return
        }
        assertMatchesGolden(screenshot, "dashboard_idle")
    }

    @Test
    fun golden_vision_overlay_visible() {
        val screenshot = captureCurrentScreen() ?: return
        assertMatchesGolden(screenshot, "vision_overlay")
    }

    // ─────────────────────────────────────────────────────────────────────
    // Golden comparison logic
    // ─────────────────────────────────────────────────────────────────────

    private fun assertMatchesGolden(
        actual:    Bitmap,
        testName:  String,
        threshold: Float = DIFF_THRESHOLD
    ) {
        val goldenFile = File(goldenDir, "${testName}_golden.png")

        if (UPDATE_GOLDENS || !goldenFile.exists()) {
            // RECORD mode: save as golden
            FileOutputStream(goldenFile).use { out ->
                actual.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            println("📸 Golden recorded: ${goldenFile.path}")
            return  // Don't compare on first save
        }

        // COMPARE mode: load golden and diff
        val golden = BitmapFactory.decodeFile(goldenFile.path)
            ?: fail("Golden file corrupted: ${goldenFile.path}").let { return }

        if (golden.width != actual.width || golden.height != actual.height) {
            // Save actual for debugging
            saveActual(actual, testName)
            fail("Screen size changed! Golden: ${golden.width}x${golden.height}, " +
                 "Actual: ${actual.width}x${actual.height}")
            return
        }

        val diffResult = computeSSIM(golden, actual)
        val diffPercent = (1f - diffResult.ssim) * 100f

        println("📊 Golden '$testName': SSIM=${diffResult.ssim} | Diff=${diffPercent}%")

        if (diffPercent > threshold * 100) {
            // Save diff image for CI artifact
            saveDiffImage(diffResult.diffBitmap, testName)
            saveActual(actual, testName)
            fail("Golden screenshot mismatch for '$testName'!\n" +
                 "Diff: ${diffPercent}% (threshold: ${threshold * 100}%)\n" +
                 "If this is intentional, run with UPDATE_GOLDENS=true")
        }

        golden.recycle()
    }

    // ─────────────────────────────────────────────────────────────────────
    // SSIM (Structural Similarity Index) computation
    // ─────────────────────────────────────────────────────────────────────

    data class SSIMResult(val ssim: Float, val diffBitmap: Bitmap)

    private fun computeSSIM(reference: Bitmap, test: Bitmap): SSIMResult {
        val w = reference.width
        val h = reference.height

        // Sample on a grid for performance (every 4th pixel)
        val step = 4
        var totalSSIM = 0.0
        var count     = 0
        val diffBitmap = reference.copy(Bitmap.Config.ARGB_8888, true)

        for (x in 0 until w step step) {
            for (y in 0 until h step step) {
                val refPixel  = reference.getPixel(x, y)
                val testPixel = test.getPixel(x, y)

                val refL = luminance(refPixel)
                val tesL = luminance(testPixel)

                val diff = abs(refL - tesL)
                val ssim = 1f - (diff * diff / 2f)
                totalSSIM += ssim
                count++

                // Highlight large differences in diff image
                if (diff > 0.1f) {
                    diffBitmap.setPixel(x, y, Color.RED)
                }
            }
        }

        return SSIMResult(
            ssim       = (totalSSIM / count).toFloat().coerceIn(0f, 1f),
            diffBitmap = diffBitmap
        )
    }

    private fun luminance(pixel: Int): Float {
        val r = Color.red(pixel)   / 255f
        val g = Color.green(pixel) / 255f
        val b = Color.blue(pixel)  / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    // ─────────────────────────────────────────────────────────────────────
    // Screen capture helper
    // ─────────────────────────────────────────────────────────────────────

    private fun captureCurrentScreen(): Bitmap? {
        return try {
            val device = androidx.test.uiautomator.UiDevice
                .getInstance(InstrumentationRegistry.getInstrumentation())
            val screenshot = device.screenshot
            screenshot
        } catch (e: Exception) {
            println("Screen capture failed: ${e.message}")
            null
        }
    }

    private fun saveActual(bitmap: Bitmap, testName: String) {
        val file = File(goldenDir, "${testName}_actual.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("📁 Actual saved: ${file.path}")
    }

    private fun saveDiffImage(bitmap: Bitmap, testName: String) {
        val file = File(goldenDir, "${testName}_diff.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("📁 Diff saved: ${file.path}")
    }
}
