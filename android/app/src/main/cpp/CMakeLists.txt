# ============================================================
# CMakeLists.txt — Vision Agent Native Build
# C++17 | OpenCV | Tesseract | Android NDK
#
# Architecture: single shared library "vision_agent_native"
# All .cpp sources compiled into one .so — avoids inter-SO linker
# symbol resolution issues (undefined symbol errors with ld.lld
# when multiple shared libs depend on each other's hidden symbols).
# This is the standard Android NDK pattern: one JNI .so per module.
# ============================================================

# cmake_minimum_required: 3.18.0 is NDK r27's actual minimum.
# AGP without version pin accepts any cmake >= this value.
# System cmake on GitHub runners (3.28+ on ubuntu-24.04) satisfies this.
cmake_minimum_required(VERSION 3.18.0)
project(VisionAgentNative VERSION 1.0.0 LANGUAGES CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# ── Compiler flags ─────────────────────────────────────────
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG -ffast-math -fomit-frame-pointer -funroll-loops")
endif()

# -fvisibility=hidden excluded: it hides symbols that JNI exports need.
# JNI JNIEXPORT already uses __attribute__((visibility("default"))).
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} \
    -Wall -Wextra \
    -fstack-protector-strong")

# ── Android ABI-specific flags ─────────────────────────────
if(ANDROID)
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} \
            -march=armv8-a+simd \
            -mtune=cortex-a76")
    elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} \
            -marm -mfpu=neon -mfloat-abi=softfp")
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -msse4.2")
    endif()

    find_library(log-lib     log)
    find_library(android-lib android)
    find_library(jnigraphics jnigraphics)
endif()

# ── OpenCV ─────────────────────────────────────────────────
set(OpenCV_DIR "${CMAKE_SOURCE_DIR}/opencv/sdk/native/jni")
find_package(OpenCV REQUIRED
    COMPONENTS core imgproc imgcodecs features2d objdetect
    OPTIONAL_COMPONENTS ximgproc
)
message(STATUS "OpenCV ${OpenCV_VERSION} — libs: ${OpenCV_LIBS}")

# ── Tesseract (pre-built static, built by CI) ──────────────
add_library(tesseract STATIC IMPORTED)
set_target_properties(tesseract PROPERTIES
    IMPORTED_LOCATION
        "${CMAKE_SOURCE_DIR}/tesseract/lib/${ANDROID_ABI}/libtesseract.a"
)

# ── Leptonica (Tesseract dependency, pre-built static) ─────
add_library(leptonica STATIC IMPORTED)
set_target_properties(leptonica PROPERTIES
    IMPORTED_LOCATION
        "${CMAKE_SOURCE_DIR}/leptonica/lib/${ANDROID_ABI}/libleptonica.a"
)

# ── Include directories ────────────────────────────────────
include_directories(
    "${CMAKE_SOURCE_DIR}/tesseract/include"
    "${CMAKE_SOURCE_DIR}/leptonica/include"
    ${OpenCV_INCLUDE_DIRS}
    ${CMAKE_SOURCE_DIR}/vision
    ${CMAKE_SOURCE_DIR}/ocr
    ${CMAKE_SOURCE_DIR}/frame_processor
    ${CMAKE_SOURCE_DIR}/perf
    ${CMAKE_SOURCE_DIR}/jni_bridge
)

# ============================================================
# Single shared library — vision_agent_native
#
# All sources compiled together into one .so.
# Kotlin loads via: System.loadLibrary("vision_agent_native")
#
# Why one library?
#   - Avoids undefined-symbol linker errors between multiple .so files
#   - ld.lld on Android NDK does not resolve inter-SO symbols at build time
#   - Standard NDK pattern: one .so per feature module
# ============================================================
add_library(vision_agent_native SHARED
    perf/MemoryPool.cpp
    frame_processor/FrameProcessor.cpp
    vision/VisionCore.cpp
    ocr/OCRCore.cpp
    jni_bridge/JNIBridge.cpp
)

target_link_libraries(vision_agent_native
    ${OpenCV_LIBS}
    tesseract
    leptonica
    ${log-lib}
    ${android-lib}
    ${jnigraphics}
)

# ── Release: strip debug symbols ───────────────────────────
if(CMAKE_BUILD_TYPE STREQUAL "Release" AND CMAKE_STRIP)
    add_custom_command(TARGET vision_agent_native POST_BUILD
        COMMAND ${CMAKE_STRIP} --strip-unneeded
                $<TARGET_FILE:vision_agent_native>
        COMMENT "Stripping vision_agent_native")
endif()

message(STATUS "Native build: ABI=${ANDROID_ABI} BUILD=${CMAKE_BUILD_TYPE}")
