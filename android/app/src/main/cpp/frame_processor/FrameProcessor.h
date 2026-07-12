#pragma once
#include <cstdint>

namespace visionagent {

struct FrameEntry;

struct ROIChangeResult {
    bool  has_change;
    int   changed_blocks;        // Bitmask of 16 blocks
    int   changed_block_count;
    float block_diffs[16];
    float overall_diff;
};

bool           frame_processor_init(int width, int height, int pool_size = 5);
uint8_t*       frame_acquire_buffer();
bool           frame_push(uint8_t* data, int width, int height);
bool           frame_pop(FrameEntry& out);
void           frame_release_buffer(uint8_t* buffer);
// FIX NC-9: Accept actual width+height — previously hardcoded to 1080 which caused
// buffer over-read on non-1080p devices (e.g., 1440p, 720p) → segfault.
ROIChangeResult frame_detect_change(const uint8_t* frame,
                                     int width, int height,
                                     float threshold = 0.02f);
int            frame_queue_size();
void           frame_processor_release();

void downscale_frame_bilinear(const uint8_t* src, uint8_t* dst,
                               int src_w, int src_h,
                               int dst_w, int dst_h);
} // namespace visionagent
