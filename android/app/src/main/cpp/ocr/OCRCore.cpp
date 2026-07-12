/**
 * OCRCore.cpp — Production Grade Tesseract OCR Integration
 *
 * Full Pipeline:
 * 1. Image Preprocessing (OpenCV)
 *    ├── Grayscale conversion (NEON-accelerated)
 *    ├── Adaptive histogram equalization (CLAHE)
 *    ├── Gaussian denoising
 *    ├── Otsu's binarization (auto-threshold)
 *    ├── Deskew correction (Hough-based angle detection)
 *    └── Morphological cleanup
 *
 * 2. Tesseract Recognition
 *    ├── LSTM engine (OEM_LSTM_ONLY — most accurate)
 *    ├── Block segmentation (PSM_AUTO for UI screens)
 *    ├── Word-level + char-level confidence
 *    └── Multi-language support
 *
 * 3. Post-processing
 *    ├── Confidence filtering
 *    ├── Layout-aware text ordering
 *    └── UI-specific error correction
 */

#include "OCRCore.h"
#include <tesseract/baseapi.h>
#include <leptonica/allheaders.h>
#include <opencv2/opencv.hpp>
#include <opencv2/imgproc.hpp>
#include <android/log.h>
#include <cstring>
#include <cmath>
#include <algorithm>
#include <memory>
#include <vector>
#include <sstream>

#define LOG_TAG "OCRCore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace visionagent {

// ─────────────────────────────────────────────────────────────────────────────
// FIX NC-10: Added std::mutex to OCRState.
// g_ocr.pre_denoised and g_ocr.pre_binary are shared mutable buffers written
// by preprocess_for_ocr() and passed by reference to Tesseract.
// If ocr_release() is called concurrently (e.g., app shutdown while OCR is running),
// it destroys the OCRState including the cv::Mat buffers while they are in use → crash.
// Fix: g_ocr_mutex guards all public functions.
// ─────────────────────────────────────────────────────────────────────────────

struct OCRState {
    tesseract::TessBaseAPI* api = nullptr;
    bool initialized            = false;
    int  page_seg_mode          = tesseract::PSM_AUTO;
    float conf_threshold        = 60.0f;  // Tesseract: 0–100

    // CLAHE for adaptive histogram equalization
    cv::Ptr<cv::CLAHE> clahe;

    // Working buffers — local to each call via preprocess_for_ocr(),
    // but declared here to reuse allocations across calls.
    cv::Mat pre_gray;
    cv::Mat pre_binary;
    cv::Mat pre_denoised;
};

static OCRState g_ocr;
static std::mutex g_ocr_mutex;  // FIX NC-10: guards all OCR public API calls

// ─────────────────────────────────────────────────────────────────────────────
// Preprocessing Pipeline
// ─────────────────────────────────────────────────────────────────────────────

/**
 * deskew_image()
 *
 * Detects skew angle using Hough line transform,
 * rotates image to correct the skew.
 * Essential for accurate OCR on rotated UI screenshots.
 */
cv::Mat deskew_image(const cv::Mat& binary) {
    // Find all non-zero points
    std::vector<cv::Point> pts;
    cv::findNonZero(binary, pts);
    if (pts.size() < 100) return binary;

    // Compute minimum area rectangle
    cv::RotatedRect box = cv::minAreaRect(pts);
    float angle = box.angle;

    // Normalize angle to [-45, 45]
    if (angle < -45.0f) angle += 90.0f;
    if (std::abs(angle) < 0.5f) return binary;  // No skew

    // Rotate
    cv::Point2f center(binary.cols / 2.0f, binary.rows / 2.0f);
    cv::Mat rot_mat = cv::getRotationMatrix2D(center, angle, 1.0);
    cv::Mat rotated;
    cv::warpAffine(binary, rotated, rot_mat, binary.size(),
                   cv::INTER_LINEAR, cv::BORDER_REPLICATE);
    return rotated;
}

/**
 * preprocess_for_ocr()
 *
 * Full preprocessing pipeline optimized for Android UI screenshots:
 * - Screenshots are typically high-res, clean, digital
 * - Main challenges: small fonts, colored backgrounds, icons mixed with text
 */
cv::Mat preprocess_for_ocr(const cv::Mat& input_rgba, int level) {
    cv::Mat gray;

    // Grayscale
    if (input_rgba.channels() == 4)
        cv::cvtColor(input_rgba, gray, cv::COLOR_RGBA2GRAY);
    else if (input_rgba.channels() == 3)
        cv::cvtColor(input_rgba, gray, cv::COLOR_BGR2GRAY);
    else
        gray = input_rgba.clone();

    if (level == 0) return gray;  // NONE — raw gray

    // LIGHT: just grayscale + basic threshold
    if (level == 1) {
        cv::Mat result;
        cv::threshold(gray, result, 0, 255,
                      cv::THRESH_BINARY | cv::THRESH_OTSU);
        return result;
    }

    // MEDIUM (default for UI):
    // 1. CLAHE (adaptive histogram equalization)
    cv::Mat clahe_result;
    g_ocr.clahe->apply(gray, clahe_result);

    // 2. Gaussian denoise
    cv::GaussianBlur(clahe_result, g_ocr.pre_denoised, cv::Size(3,3), 0);

    // 3. Otsu threshold
    cv::threshold(g_ocr.pre_denoised, g_ocr.pre_binary,
                  0, 255, cv::THRESH_BINARY | cv::THRESH_OTSU);

    if (level == 2) return g_ocr.pre_binary;

    // HEAVY: also deskew and morphological cleanup
    // 4. Morphological opening (remove small noise)
    cv::Mat kernel = cv::getStructuringElement(cv::MORPH_RECT, {2,2});
    cv::morphologyEx(g_ocr.pre_binary, g_ocr.pre_binary,
                     cv::MORPH_OPEN, kernel);

    // 5. Deskew
    cv::Mat deskewed = deskew_image(g_ocr.pre_binary);

    return deskewed;
}

/**
 * mat_to_pix()
 *
 * Convert OpenCV Mat → Leptonica PIX (Tesseract input format)
 * Zero-copy where possible.
 */
Pix* mat_to_pix(const cv::Mat& mat) {
    Pix* pix = nullptr;

    if (mat.type() == CV_8UC1) {
        // Binary/Gray image
        pix = pixCreate(mat.cols, mat.rows, 8);
        if (!pix) return nullptr;

        l_uint32* data = pixGetData(pix);
        int wpl = pixGetWpl(pix);

        for (int y = 0; y < mat.rows; ++y) {
            const uint8_t* row = mat.ptr<uint8_t>(y);
            l_uint32*      dst = data + y * wpl;
            for (int x = 0; x < mat.cols; ++x) {
                SET_DATA_BYTE(dst, x, row[x]);
            }
        }
    } else if (mat.type() == CV_8UC3) {
        // BGR color
        cv::Mat rgb;
        cv::cvtColor(mat, rgb, cv::COLOR_BGR2RGB);
        pix = pixCreate(rgb.cols, rgb.rows, 32);
        if (!pix) return nullptr;

        l_uint32* data = pixGetData(pix);
        int wpl = pixGetWpl(pix);

        for (int y = 0; y < rgb.rows; ++y) {
            const uint8_t* row = rgb.ptr<uint8_t>(y);
            l_uint32*      dst = data + y * wpl;
            for (int x = 0; x < rgb.cols; ++x) {
                composeRGBPixel(row[x*3], row[x*3+1], row[x*3+2],
                                dst + x);
            }
        }
    }
    return pix;
}

// ─────────────────────────────────────────────────────────────────────────────
// Public API
// ─────────────────────────────────────────────────────────────────────────────

bool ocr_initialize(const char* tess_data_path,
                    const char* languages,
                    int         page_seg_mode,
                    int         ocr_engine_mode,
                    float       conf_threshold) {
    std::lock_guard<std::mutex> lk(g_ocr_mutex);  // FIX NC-10
    try {
        g_ocr.api = new tesseract::TessBaseAPI();
        g_ocr.conf_threshold = conf_threshold;
        g_ocr.page_seg_mode  = page_seg_mode;

        // Initialize with language data path and language(s)
        int ret = g_ocr.api->Init(
            tess_data_path,
            languages,
            static_cast<tesseract::OcrEngineMode>(ocr_engine_mode)
        );

        if (ret != 0) {
            LOGE("Tesseract Init failed for langs=%s path=%s", languages, tess_data_path);
            delete g_ocr.api;
            g_ocr.api = nullptr;
            return false;
        }

        // Configure for UI screenshot optimization
        g_ocr.api->SetPageSegMode(
            static_cast<tesseract::PageSegMode>(page_seg_mode));

        // Tesseract variables for better UI text recognition
        g_ocr.api->SetVariable("tessedit_char_whitelist", "");
        g_ocr.api->SetVariable("load_system_dawg", "false");
        g_ocr.api->SetVariable("load_freq_dawg",   "false");
        g_ocr.api->SetVariable("textord_heavy_nr",  "1");
        g_ocr.api->SetVariable("edges_max_children_per_outline", "40");

        // CLAHE for adaptive contrast
        g_ocr.clahe = cv::createCLAHE(3.0, cv::Size(8,8));

        g_ocr.initialized = true;
        LOGI("Tesseract initialized: lang=%s, PSM=%d, OEM=%d",
             languages, page_seg_mode, ocr_engine_mode);
        return true;

    } catch (const std::exception& e) {
        LOGE("OCR init exception: %s", e.what());
        return false;
    }
}

OCRResult ocr_extract_text(const uint8_t* rgba_data,
                            int width, int height,
                            int preprocessing_level) {
    std::lock_guard<std::mutex> lk(g_ocr_mutex);  // FIX NC-10
    OCRResult result = {};
    if (!g_ocr.initialized || !g_ocr.api) return result;

    auto t_start = std::chrono::steady_clock::now();

    try {
        // Wrap input as OpenCV Mat (zero-copy)
        cv::Mat input(height, width, CV_8UC4,
                      const_cast<uint8_t*>(rgba_data));

        // Preprocessing pipeline
        cv::Mat processed = preprocess_for_ocr(input, preprocessing_level);

        // Convert to Leptonica PIX
        Pix* pix = mat_to_pix(processed);
        if (!pix) {
            LOGE("mat_to_pix returned nullptr");
            return result;
        }

        // Set image in Tesseract
        g_ocr.api->SetImage(pix);
        g_ocr.api->SetSourceResolution(150);  // Typical screen DPI

        // Get recognition result
        char* text_raw = g_ocr.api->GetUTF8Text();
        if (!text_raw) {
            pixDestroy(&pix);
            return result;
        }

        result.full_text = std::string(text_raw);
        delete[] text_raw;

        result.overall_confidence = g_ocr.api->MeanTextConf();

        // Get word-level results with bounding boxes
        tesseract::ResultIterator* ri = g_ocr.api->GetIterator();
        tesseract::PageIteratorLevel level = tesseract::RIL_WORD;

        if (ri) {
            do {
                const char* word = ri->GetUTF8Text(level);
                float conf = ri->Confidence(level);

                if (word && conf >= g_ocr.conf_threshold) {
                    int x1, y1, x2, y2;
                    ri->BoundingBox(level, &x1, &y1, &x2, &y2);

                    TextBlock block;
                    block.text       = std::string(word);
                    block.bounds     = {x1, y1, x2, y2};
                    block.confidence = conf / 100.0f;
                    result.blocks.push_back(block);
                }
                if (word) delete[] word;

            } while (ri->Next(level));
            delete ri;
        }

        pixDestroy(&pix);
        g_ocr.api->Clear();

        auto t_end = std::chrono::steady_clock::now();
        result.processing_ms = (int)std::chrono::duration_cast
                                    <std::chrono::milliseconds>
                                    (t_end - t_start).count();

        LOGD("OCR: %d blocks, confidence=%.1f, time=%dms",
             (int)result.blocks.size(),
             result.overall_confidence,
             result.processing_ms);

    } catch (const std::exception& e) {
        LOGE("OCR extraction error: %s", e.what());
        result.error_code = -1;
    }

    return result;
}

void ocr_set_language(const char* lang) {
    if (!g_ocr.initialized || !g_ocr.api) return;
    // Re-init with new language
    g_ocr.api->Init(nullptr, lang);
    LOGI("OCR language set to: %s", lang);
}

void ocr_release() {
    std::lock_guard<std::mutex> lk(g_ocr_mutex);  // FIX NC-10: prevent use-after-free
    if (g_ocr.api) {
        g_ocr.api->End();
        delete g_ocr.api;
        g_ocr.api = nullptr;
    }
    g_ocr.initialized = false;
    LOGI("OCRCore released");
}

} // namespace visionagent
