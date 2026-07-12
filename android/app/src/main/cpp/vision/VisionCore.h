/**
 * VisionCore.h — Header for Vision Engine
 */
#pragma once
#include <opencv2/core.hpp>   // cv::Mat — required for compute_ssim declaration
#include <vector>
#include <cstdint>

namespace visionagent {

// Element types
enum ElementType {
    ELEMENT_BUTTON       = 0,
    ELEMENT_TEXT_REGION  = 1,
    ELEMENT_ICON         = 2,
    ELEMENT_DIALOG       = 3,
    ELEMENT_POPUP        = 4,
    ELEMENT_CHECKBOX     = 5,
    ELEMENT_TOGGLE       = 6,
    ELEMENT_PROGRESS     = 7,
    ELEMENT_LIST_ITEM    = 8,
    ELEMENT_NAV_ITEM     = 9,
    ELEMENT_IMAGE        = 10,
    ELEMENT_UNKNOWN      = 99
};

// Screen types
enum ScreenType {
    SCREEN_HOME       = 0,
    SCREEN_LOADING    = 1,
    SCREEN_DIALOG     = 2,
    SCREEN_ERROR      = 3,
    SCREEN_FORM       = 4,
    SCREEN_LIST       = 5,
    SCREEN_NAVIGATION = 6,
    SCREEN_DETAIL     = 7,
    SCREEN_UNKNOWN    = 99
};

struct Bounds {
    int left, top, right, bottom;
};

struct DetectedElement {
    ElementType type;
    Bounds      bounds;
    float       confidence;
    int         area;
    char        text[128];   // OCR result if available
};

struct LayoutInfo {
    bool has_top_bar;
    bool has_bottom_nav;
    bool has_content_scroll;
    bool has_fab;             // Floating Action Button
    int  grid_columns;
};

struct ColorFeatures {
    float dominant_hue;
    float mean_brightness;
    float low_saturation_ratio;
    float high_saturation_ratio;
    int   dominant_color_rgb;
};

struct VisionResult {
    std::vector<DetectedElement> elements;
    LayoutInfo   layout;
    ColorFeatures color_features;
    int          screen_type;
    float        overall_confidence;
    int          processing_ms;
    int          error_code;
};

// ── Public API ────────────────────────────────────────────────────────────
bool        vision_initialize(int width, int height, float confidence_threshold);
VisionResult vision_process_frame(const uint8_t* rgba_data, int width, int height);
int         classify_screen_type(const VisionResult& r);
void        vision_load_template(const uint8_t* data, int size, int tmpl_type);
float       compute_ssim(const cv::Mat& img1, const cv::Mat& img2);
void        vision_release();

} // namespace visionagent
