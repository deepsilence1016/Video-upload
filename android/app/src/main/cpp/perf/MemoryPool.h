#pragma once
#include <cstddef>

namespace visionagent {
struct PoolStats {
    int total_capacity;
    int total_allocated;
    int num_pools;
};
bool      memory_pool_init();
void*     pool_alloc(size_t size);
void      pool_free(void* ptr, size_t size);
PoolStats memory_pool_stats();
void      memory_pool_release();
} // namespace visionagent
