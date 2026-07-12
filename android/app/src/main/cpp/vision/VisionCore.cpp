/**
 * VisionCore.cpp — Production Grade Computer Vision Engine
 *
 * FIX C-1: Removed OpenMP parallel sections that shared g_state buffers.
 * Root cause: detect_buttons(), detect_text_regions(), detect_popups() all
 * read/write g_state.hsv_buffer, g_state.thresh_buffer, g_state.morph_buffer
 * concurrently — classic data race, caught by ThreadSanitizer.
 *
 * Fix strategy: Each detection function now receives LOCAL Mat buffers
 * allocated on the stack / passed by the caller. g_state holds only
 * read-only resources (detectors, kernels, templates, immutable config).
 * The three heavy preprocessing steps run on the same thread sequentially;
 * at 15 fps this is fast enough and avoids all shared-buffer races.
 *
 * Real OpenCV Implementation:
 * 1. MSER  → Text region detection
 * 2. Canny → UI boundary detection
 * 3. Contour Analysis → Shape / button detection
 * 4. ORB + Template matching → Icon detection
 * 5. Color Histogram → Screen classification
 * 6. HoughLines / HoughCircles → Layout analysis
 * 7. SSIM → Frame change detection
 */

#include "VisionCore.h"
#include <android/log.h>
#include <opencv2/opencv.hpp>
#include <opencv2/features2d.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/objdetect.hpp>
#include <arm_neon.h>
#include <mutex>
#include <vector>
#include <memory>
#include <algorithm>
#include <chrono>
#include <cmath>

#define LOG_TAG "VisionCore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace visionagent {

// ─────────────────────────────────────────────────────────────────────────────
// VisionState — holds ONLY read-only / initialisation-time resources.
// NO mutable working buffers here (that was the C-1 bug source).
// Working buffers are local to vision_process_frame() to be thread-safe.
// ─────────────────────────────────────────────────────────────────────────────

struct VisionState {
    // Read-only detectors — initialised once, used concurrently (cv::Ptr is ref-counted)
    cv::Ptr<cv::ORB>                 orb_detector;
    cv::Ptr<cv::MSER>                mser_detector;
    cv::Ptr<cv::BFMatcher>           bf_matcher;
    cv::Ptr<cv::FastFeatureDetector> fast_detector;

    // Morphological kernels — read-only after init
    cv::Mat kernel_3x3;
    cv::Mat kernel_5x5;
    cv::Mat kernel_h;
    cv::Mat kernel_v;

    // Template library — protected by template_mutex for load-time writes
    std::mutex               template_mutex;
    std::vector<cv::Mat>     button_templates;
    std::vector<cv::Mat>     icon_templates;

    float confidence_threshold = 0.75f;
    bool  initialized = false;
    int   frame_width  = 0;
    int   frame_height = 0;
};

// Global state — mutable working buffers removed (see fix note above)
static VisionState g_state;
// Mutex protecting vision_initialize / vision_release vs vision_process_frame
static std::mutex  g_init_mutex;

// ─────────────────────────────────────────────────────────────────────────────
// Per-frame local buffers — allocated in vision_process_frame on the stack
// Each call to vision_process_frame is independent; no sharing between frames.
// ─────────────────────────────────────────────────────────────────────────────
struct FrameBuffers {
    cv::Mat gray;
    cv::Mat blur;
    cv::Mat edge;
    cv::Mat hsv;
    cv::Mat thresh;
    cv::Mat morph;
};

// ─────────────────────────────────────────────────────────────────────────────
// SIMD-accelerated grayscale conversion (ARM NEON)
// ─────────────────────────────────────────────────────────────────────────────
static void rgba_to_gray_neon(const uint8_t* __restrict__ src,
                               uint8_t* __restrict__       dst,
                               int width, int height) {
    // BT.601: R*77 + G*150 + B*29 >> 8
    const uint8x8_t w_r = vdup_n_u8(77);
    const uint8x8_t w_g = vdup_n_u8(150);
    const uint8x8_t w_b = vdup_n_u8(29);

    int total = width * height;
    int i = 0;
    for (; i <= total - 8; i += 8) {
        uint8x8x4_t rgba = vld4_u8(src + i * 4);
        uint16x8_t sum = vaddq_u16(
            vaddq_u16(vmull_u8(rgba.val[0], w_r),
                      vmull_u8(rgba.val[1], w_g)),
            vmull_u8(rgba.val[2], w_b));
        vst1_u8(dst + i, vshrn_n_u16(sum, 8));
    }
    for (; i < total; ++i) {
        dst[i] = (uint8_t)((src[i*4]*77 + src[i*4+1]*150 + src[i*4+2]*29) >> 8);
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SSIM — Structural Similarity for Frame Change Detection
// ─────────────────────────────────────────────────────────────────────────────
float compute_ssim(const cv::Mat& img1, const cv::Mat& img2) {
    static const double C1 = 6.5025, C2 = 58.5225;
    cv::Mat I1, I2;
    img1.convertTo(I1, CV_32F);
    img2.convertTo(I2, CV_32F);
    cv::Mat I1_2 = I1.mul(I1), I2_2 = I2.mul(I2), I1_I2 = I1.mul(I2);
    cv::Mat mu1, mu2;
    cv::GaussianBlur(I1, mu1, {11,11}, 1.5);
    cv::GaussianBlur(I2, mu2, {11,11}, 1.5);
    cv::Mat mu1_2 = mu1.mul(mu1), mu2_2 = mu2.mul(mu2), mu1_mu2 = mu1.mul(mu2);
    cv::Mat s1, s2, s12;
    cv::GaussianBlur(I1_2, s1, {11,11}, 1.5);  s1 -= mu1_2;
    cv::GaussianBlur(I2_2, s2, {11,11}, 1.5);  s2 -= mu2_2;
    cv::GaussianBlur(I1_I2, s12, {11,11}, 1.5); s12 -= mu1_mu2;
    cv::Mat t3 = (2*mu1_mu2+C1).mul(2*s12+C2);
    cv::Mat t4 = (mu1_2+mu2_2+C1).mul(s1+s2+C2);
    cv::Mat ssim_map; cv::divide(t3, t4, ssim_map);
    return (float)cv::mean(ssim_map)[0];
}

// ─────────────────────────────────────────────────────────────────────────────
// Detection functions — now accept LOCAL buffers, never touch g_state mutable data
// ─────────────────────────────────────────────────────────────────────────────

static std::vector<DetectedElement>
detect_buttons(const cv::Mat& frame_color,
               const cv::Mat& blur,
               FrameBuffers&  local,   // local per-frame buffers
               float conf_threshold) {

    std::vector<DetectedElement> results;

    // Use local.hsv — NOT g_state.hsv_buffer
    cv::cvtColor(frame_color, local.hsv, cv::COLOR_BGR2HSV);

    // Use local.thresh — NOT g_state.thresh_buffer
    cv::adaptiveThreshold(blur, local.thresh, 255,
                          cv::ADAPTIVE_THRESH_GAUSSIAN_C, cv::THRESH_BINARY_INV, 11, 2);

    // Use local.morph — NOT g_state.morph_buffer
    cv::morphologyEx(local.thresh, local.morph, cv::MORPH_CLOSE, g_state.kernel_5x5);

    std::vector<std::vector<cv::Point>> contours;
    cv::findContours(local.morph, contours, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);

    int frame_area = frame_color.cols * frame_color.rows;
    for (const auto& contour : contours) {
        double area = cv::contourArea(contour);
        double ratio = area / frame_area;
        if (ratio < 0.001 || ratio > 0.20) continue;

        cv::Rect bbox = cv::boundingRect(contour);
        float aspect = (float)bbox.width / std::max(1, bbox.height);
        if (aspect < 1.5f || aspect > 10.0f) continue;

        std::vector<cv::Point> hull;
        cv::convexHull(contour, hull);
        double hull_area = cv::contourArea(hull);
        double rect_area = (double)bbox.width * bbox.height;
        float rectangularity = (rect_area > 0) ? (float)(hull_area / rect_area) : 0.f;
        if (rectangularity < 0.70f) continue;

        // roi_hsv reads from local.hsv — safe, no shared write
        cv::Mat roi_hsv = local.hsv(bbox);
        cv::Scalar mean_hsv, std_hsv;
        cv::meanStdDev(roi_hsv, mean_hsv, std_hsv);
        float color_uniformity = 1.0f - std::min(1.0f, (float)std_hsv[1] / 50.0f);
        float pos_bonus = (bbox.y > frame_color.rows * 0.6f) ? 0.1f : 0.0f;
        float confidence = std::min(1.0f, rectangularity*0.5f + color_uniformity*0.3f + 0.2f + pos_bonus);
        if (confidence < conf_threshold) continue;

        DetectedElement el;
        el.type = ELEMENT_BUTTON;
        el.bounds = {bbox.x, bbox.y, bbox.x+bbox.width, bbox.y+bbox.height};
        el.confidence = confidence;
        el.area = (int)area;
        results.push_back(el);
    }
    return results;
}

static std::vector<DetectedElement>
detect_text_regions(const cv::Mat& blur, float conf_threshold) {
    std::vector<DetectedElement> results;
    std::vector<std::vector<cv::Point>> msers;
    std::vector<cv::Rect> bboxes;
    // g_state.mser_detector is cv::Ptr — thread-safe for concurrent detectRegions
    // calls only if the detector itself is not written to concurrently.
    // Here we run sequentially so this is safe.
    g_state.mser_detector->detectRegions(blur, msers, bboxes);
    for (const auto& bbox : bboxes) {
        if (bbox.height < 8 || bbox.height > 80 || bbox.width < 5) continue;
        cv::Mat roi = blur(bbox);
        double mn, mx;
        cv::minMaxLoc(roi, &mn, &mx);
        float contrast = (float)(mx - mn) / 255.0f;
        if (contrast < 0.3f) continue;
        DetectedElement el;
        el.type = ELEMENT_TEXT_REGION;
        el.bounds = {bbox.x, bbox.y, bbox.x+bbox.width, bbox.y+bbox.height};
        el.confidence = std::min(1.0f, 0.6f + contrast*0.4f);
        el.area = bbox.width * bbox.height;
        results.push_back(el);
    }
    return results;
}

static std::vector<DetectedElement>
detect_icons(const cv::Mat& blur, float conf_threshold) {
    std::vector<DetectedElement> results;

    // Snapshot icon templates under lock to avoid race with vision_load_template
    std::vector<cv::Mat> templates_snapshot;
    {
        std::lock_guard<std::mutex> lk(g_state.template_mutex);
        templates_snapshot = g_state.icon_templates;
    }
    if (templates_snapshot.empty()) return results;

    for (const auto& tmpl : templates_snapshot) {
        if (tmpl.empty()) continue;
        for (float scale : {0.5f, 0.75f, 1.0f, 1.25f, 1.5f}) {
            cv::Mat scaled;
            cv::resize(tmpl, scaled, {}, scale, scale);
            if (scaled.rows < 20 || scaled.cols < 20 ||
                scaled.cols > blur.cols || scaled.rows > blur.rows) continue;
            cv::Mat match;
            cv::matchTemplate(blur, scaled, match, cv::TM_CCOEFF_NORMED);
            double max_val; cv::Point max_loc;
            cv::minMaxLoc(match, nullptr, &max_val, nullptr, &max_loc);
            if (max_val >= conf_threshold) {
                DetectedElement el;
                el.type = ELEMENT_ICON;
                el.bounds = {max_loc.x, max_loc.y,
                             max_loc.x+scaled.cols, max_loc.y+scaled.rows};
                el.confidence = (float)max_val;
                el.area = scaled.rows * scaled.cols;
                results.push_back(el);
            }
        }
    }
    return results;
}

static std::vector<DetectedElement>
detect_popups(const cv::Mat& frame_color, const cv::Mat& gray, float conf_threshold) {
    std::vector<DetectedElement> results;
    int W = frame_color.cols, H = frame_color.rows;
    if (W < 12 || H < 12) return results;

    cv::Rect corner_tl(0, 0, W/6, H/6), corner_tr(W*5/6, 0, W/6, H/6);
    cv::Rect corner_bl(0, H*5/6, W/6, H/6), corner_br(W*5/6, H*5/6, W/6, H/6);
    cv::Rect center(W/4, H/4, W/2, H/2);

    double mean_corner = (cv::mean(gray(corner_tl))[0] + cv::mean(gray(corner_tr))[0] +
                          cv::mean(gray(corner_bl))[0] + cv::mean(gray(corner_br))[0]) / 4.0;
    double mean_center = cv::mean(gray(center))[0];
    if (mean_center <= mean_corner + 20.0) return results;

    cv::Mat center_gray = gray(center).clone();
    cv::Mat center_edge;
    cv::Canny(center_gray, center_edge, 50, 150);

    std::vector<std::vector<cv::Point>> contours;
    cv::findContours(center_edge, contours, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);

    double best_area = 0; cv::Rect best_rect;
    for (const auto& c : contours) {
        double a = cv::contourArea(c);
        if (a < 5000) continue;
        cv::Rect r = cv::boundingRect(c);
        double fill = a / std::max(1, r.width * r.height);
        if (fill > 0.5 && a > best_area) { best_area = a; best_rect = r; }
    }
    if (best_area > 0) {
        best_rect.x += W/4; best_rect.y += H/4;
        DetectedElement el;
        el.type = ELEMENT_DIALOG;
        el.bounds = {best_rect.x, best_rect.y,
                     best_rect.x+best_rect.width, best_rect.y+best_rect.height};
        el.confidence = std::min(1.0f, 0.7f+(float)(mean_center-mean_corner)/100.0f);
        el.area = (int)best_area;
        results.push_back(el);
    }
    return results;
}

static LayoutInfo analyze_layout(const cv::Mat& gray, int width, int height) {
    LayoutInfo info = {};
    cv::Mat edges; cv::Canny(gray, edges, 30, 90);
    std::vector<cv::Vec4i> lines;
    cv::HoughLinesP(edges, lines, 1, CV_PI/180, 50, width*0.4, 10);
    int top=0, bot=0, sep=0;
    for (const auto& l : lines) {
        int yc = (l[1]+l[3])/2;
        if (std::abs(l[3]-l[1]) < 15 && std::abs(l[2]-l[0]) > width*0.6f) {
            if (yc < height*0.15f) top++;
            else if (yc > height*0.85f) bot++;
            else sep++;
        }
    }
    info.has_top_bar = (top >= 1);
    info.has_bottom_nav = (bot >= 1);
    info.has_content_scroll = (sep >= 2);
    std::vector<cv::Vec3f> circles;
    cv::HoughCircles(gray, circles, cv::HOUGH_GRADIENT, 1, gray.rows/8.0, 100, 30, 20, 80);
    for (const auto& c : circles) {
        if ((int)c[0] > width*0.6f && (int)c[1] > height*0.6f) { info.has_fab = true; break; }
    }
    return info;
}

static ColorFeatures compute_color_features(const cv::Mat& frame) {
    ColorFeatures feat = {};
    cv::Mat hsv; cv::cvtColor(frame, hsv, cv::COLOR_BGR2HSV);
    std::vector<cv::Mat> ch; cv::split(hsv, ch);
    int hist_size = 32; float range[] = {0, 256};
    const float* hr = range; cv::Mat s_hist;
    cv::calcHist(&ch[1], 1, 0, {}, s_hist, 1, &hist_size, &hr);
    cv::normalize(s_hist, s_hist, 0, 1, cv::NORM_MINMAX);
    float lo = 0, hi = 0;
    for (int i = 0; i < hist_size; ++i) {
        if (i < hist_size/3) lo += s_hist.at<float>(i);
        else if (i > hist_size*2/3) hi += s_hist.at<float>(i);
    }
    feat.low_saturation_ratio = lo;
    feat.high_saturation_ratio = hi;
    feat.dominant_hue = (float)cv::mean(ch[0])[0];
    feat.mean_brightness = (float)cv::mean(ch[2])[0] / 255.0f;
    return feat;
}

// ─────────────────────────────────────────────────────────────────────────────
// Public API
// ─────────────────────────────────────────────────────────────────────────────

bool vision_initialize(int width, int height, float confidence_threshold) {
    std::lock_guard<std::mutex> lk(g_init_mutex);
    try {
        g_state.frame_width  = width;
        g_state.frame_height = height;
        g_state.confidence_threshold = confidence_threshold;

        // Pre-compute kernels (read-only after here)
        g_state.kernel_3x3 = cv::getStructuringElement(cv::MORPH_RECT, {3,3});
        g_state.kernel_5x5 = cv::getStructuringElement(cv::MORPH_RECT, {5,5});
        g_state.kernel_h   = cv::getStructuringElement(cv::MORPH_RECT, {25,1});
        g_state.kernel_v   = cv::getStructuringElement(cv::MORPH_RECT, {1,25});

        g_state.orb_detector  = cv::ORB::create(500, 1.2f, 8, 31, 0, 2,
                                                 cv::ORB::HARRIS_SCORE, 31, 20);
        g_state.mser_detector = cv::MSER::create(5, 60, 14400, 0.25, 0.2,
                                                  200, 1.01, 0.003, 5);
        g_state.bf_matcher    = cv::BFMatcher::create(cv::NORM_HAMMING, true);
        g_state.fast_detector = cv::FastFeatureDetector::create(20, true);

        g_state.initialized = true;
        LOGI("VisionCore initialized: %dx%d threshold=%.2f", width, height, confidence_threshold);
        return true;
    } catch (const cv::Exception& e) {
        LOGE("VisionCore init failed: %s", e.what());
        return false;
    }
}

VisionResult vision_process_frame(const uint8_t* rgba_data, int width, int height) {
    VisionResult result = {};
    {
        std::lock_guard<std::mutex> lk(g_init_mutex);
        if (!g_state.initialized) return result;
    }

    auto t_start = std::chrono::steady_clock::now();

    try {
        // Allocate per-frame LOCAL buffers — no sharing with other calls
        FrameBuffers local;
        local.gray  = cv::Mat(height, width, CV_8UC1);
        local.blur  = cv::Mat(height, width, CV_8UC1);
        local.edge  = cv::Mat(height, width, CV_8UC1);
        local.hsv   = cv::Mat(height, width, CV_8UC3);
        local.thresh= cv::Mat(height, width, CV_8UC1);
        local.morph = cv::Mat(height, width, CV_8UC1);

        // Step 1: SIMD Grayscale
        rgba_to_gray_neon(rgba_data, local.gray.data, width, height);

        // Step 2: Gaussian Blur
        cv::GaussianBlur(local.gray, local.blur, {3,3}, 0);

        // Step 3: BGR for color ops (zero-copy wrap)
        cv::Mat frame_bgr(height, width, CV_8UC4, const_cast<uint8_t*>(rgba_data));
        cv::Mat frame_color;
        cv::cvtColor(frame_bgr, frame_color, cv::COLOR_RGBA2BGR);

        // Step 4: Canny edges
        cv::Canny(local.blur, local.edge, 50, 150, 3, true);

        // Step 5: Sequential detection — each function uses its own local buffers
        // No OpenMP: removed to eliminate shared-buffer data race (fix C-1)
        auto buttons     = detect_buttons(frame_color, local.blur, local, g_state.confidence_threshold);
        auto text_regions= detect_text_regions(local.blur, g_state.confidence_threshold - 0.1f);
        auto popups      = detect_popups(frame_color, local.blur, g_state.confidence_threshold);
        auto icons       = detect_icons(local.blur, g_state.confidence_threshold);

        result.layout       = analyze_layout(local.blur, width, height);
        result.color_features = compute_color_features(frame_color);

        for (auto& v : {buttons, text_regions, icons, popups})
            result.elements.insert(result.elements.end(), v.begin(), v.end());

        if (!result.elements.empty()) {
            float sum = 0;
            for (const auto& el : result.elements) sum += el.confidence;
            result.overall_confidence = sum / result.elements.size();
        }
        result.screen_type = classify_screen_type(result);

        auto t_end = std::chrono::steady_clock::now();
        result.processing_ms = (int)std::chrono::duration_cast<std::chrono::milliseconds>(
            t_end - t_start).count();

        LOGD("VisionCore: %d elements screen=%d %dms",
             (int)result.elements.size(), result.screen_type, result.processing_ms);

    } catch (const cv::Exception& e) {
        LOGE("Vision processing error: %s", e.what());
        result.error_code = -1;
    }
    return result;
}

int classify_screen_type(const VisionResult& r) {
    for (const auto& el : r.elements)
        if (el.type == ELEMENT_DIALOG || el.type == ELEMENT_POPUP) return SCREEN_DIALOG;
    if (r.elements.size() < 3 && r.layout.has_top_bar) return SCREEN_LOADING;
    int tc = 0;
    for (const auto& el : r.elements) if (el.type == ELEMENT_TEXT_REGION) tc++;
    if (tc >= 3) return SCREEN_FORM;
    if (r.layout.has_bottom_nav) return SCREEN_NAVIGATION;
    if (r.elements.size() > 5) return SCREEN_LIST;
    return SCREEN_UNKNOWN;
}

void vision_load_template(const uint8_t* data, int size, int tmpl_type) {
    std::vector<uint8_t> buf(data, data + size);
    cv::Mat tmpl = cv::imdecode(buf, cv::IMREAD_GRAYSCALE);
    if (tmpl.empty()) { LOGE("Failed to load template"); return; }
    std::lock_guard<std::mutex> lk(g_state.template_mutex);
    if (tmpl_type == 0) g_state.button_templates.push_back(tmpl);
    else                g_state.icon_templates.push_back(tmpl);
    LOGD("Template loaded: type=%d %dx%d", tmpl_type, tmpl.cols, tmpl.rows);
}

void vision_release() {
    std::lock_guard<std::mutex> lk(g_init_mutex);
    g_state.button_templates.clear();
    g_state.icon_templates.clear();
    g_state.initialized = false;
    LOGI("VisionCore released");
}

} // namespace visionagent
