# ============================================================
# sanitizer_flags.cmake — Native Sanitizer Configuration
#
# Debug builds में automatically enable:
# - AddressSanitizer (ASan)     → buffer overflow, use-after-free
# - UndefinedBehaviorSanitizer  → integer overflow, null deref
# - ThreadSanitizer (TSan)      → data races (VisionCore parallel sections)
#
# Usage: include(sanitizers/sanitizer_flags.cmake) in CMakeLists.txt
#
# Performance overhead:
# - ASan:  2-5x slowdown, +50% memory
# - UBSan: <5% slowdown
# - TSan:  5-20x slowdown, +8x memory (enable separately)
#
# Enable via: cmake -DENABLE_ASAN=ON -DENABLE_UBSAN=ON ..
# ============================================================

option(ENABLE_ASAN   "Enable AddressSanitizer"           OFF)
option(ENABLE_UBSAN  "Enable UndefinedBehaviorSanitizer"  OFF)
option(ENABLE_TSAN   "Enable ThreadSanitizer"             OFF)
option(ENABLE_MSAN   "Enable MemorySanitizer"             OFF)

# Auto-enable in Debug + Clang
if(CMAKE_BUILD_TYPE STREQUAL "Debug" AND CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    message(STATUS "Debug build detected — considering sanitizer flags")

    if(ANDROID)
        # Android ASan setup (requires wrapping with asan_device_setup.sh)
        if(ENABLE_ASAN)
            message(STATUS "Enabling ASan for Android")
            add_compile_options(-fsanitize=address -fno-omit-frame-pointer)
            add_link_options(-fsanitize=address)
            # Android ASan requires:
            # 1. adb shell setprop wrap.com.visionagent.app "ASAN_OPTIONS=detect_leaks=0"
            # 2. Copy libclang_rt.asan-aarch64-android.so to APK
        endif()

        if(ENABLE_UBSAN)
            message(STATUS "Enabling UBSan for Android")
            add_compile_options(
                -fsanitize=undefined
                -fsanitize=integer
                -fsanitize=bounds
                -fsanitize=null
                -fsanitize=alignment
                -fno-sanitize-recover=all    # Crash on first violation
            )
            add_link_options(-fsanitize=undefined)
        endif()

        if(ENABLE_TSAN)
            message(STATUS "⚠️ TSan enabled — 5-20x slowdown expected")
            add_compile_options(-fsanitize=thread)
            add_link_options(-fsanitize=thread)
        endif()

    else()
        # Desktop/CI build — full sanitizer support
        if(ENABLE_ASAN)
            add_compile_options(-fsanitize=address -fno-omit-frame-pointer)
            add_link_options(-fsanitize=address)
            set(ENV{ASAN_OPTIONS} "detect_leaks=1:check_initialization_order=true")
        endif()

        if(ENABLE_UBSAN)
            add_compile_options(-fsanitize=undefined -fno-sanitize-recover=all)
            add_link_options(-fsanitize=undefined)
        endif()

        if(ENABLE_TSAN AND NOT ENABLE_ASAN)
            # Note: ASan and TSan cannot be used together
            add_compile_options(-fsanitize=thread)
            add_link_options(-fsanitize=thread)
        endif()

        if(ENABLE_MSAN AND NOT ENABLE_ASAN AND NOT ENABLE_TSAN)
            add_compile_options(-fsanitize=memory -fno-omit-frame-pointer -fPIE)
            add_link_options(-fsanitize=memory -pie)
        endif()
    endif()
endif()

# ── Sanitizer Report Format ────────────────────────────────────
if(ENABLE_ASAN OR ENABLE_UBSAN OR ENABLE_TSAN)
    add_compile_definitions(
        SANITIZERS_ENABLED=1
    )
    message(STATUS "Sanitizers: ASan=${ENABLE_ASAN} UBSan=${ENABLE_UBSAN} TSan=${ENABLE_TSAN}")
endif()

# ── Safe Compiler Hardening (always-on) ───────────────────────
# These are lightweight and go into all builds
add_compile_options(
    -fstack-protector-strong    # Stack smashing protection
    -D_FORTIFY_SOURCE=2         # Runtime bounds checking for stdlib
    -Wformat                    # Format string vulnerabilities
    -Wformat-security           # Format string security checks
)

# ── Additional C++ Safety Checks ──────────────────────────────
if(CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    add_compile_options(
        -Warray-bounds           # Array out of bounds (compile-time)
        -Wuninitialized          # Uninitialized variable use
        -Wnull-dereference       # Null pointer dereference
        -Wdouble-promotion       # Float-to-double promotion (perf issue)
    )
endif()
