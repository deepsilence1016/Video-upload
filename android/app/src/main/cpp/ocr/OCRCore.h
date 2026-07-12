/**
 * OCRCore.h — OCR Engine Header
 */
#pragma once
#include <string>
#include <vector>
#include <cstdint>
#include <chrono>

namespace visionagent {

struct TextBounds { int left, top, right, bottom; };

struct TextBlock {
    std::string text;
    TextBounds  bounds;
    float       confidence;
    std::string language;
};

struct OCRResult {
    std::string            full_text;
    std::vector<TextBlock> blocks;
    float                  overall_confidence;
    int                    processing_ms;
    int                    error_code;
};

bool      ocr_initialize(const char* tess_data_path,
                          const char* languages,
                          int         page_seg_mode,
                          int         ocr_engine_mode,
                          float       conf_threshold);

OCRResult ocr_extract_text(const uint8_t* rgba_data,
                             int width, int height,
                             int preprocessing_level);

void      ocr_set_language(const char* lang);
void      ocr_release();

} // namespace visionagent
