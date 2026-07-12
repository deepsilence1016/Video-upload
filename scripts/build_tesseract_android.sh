#!/usr/bin/env bash
# ============================================================
# build_tesseract_android.sh
# Cross-compiles Leptonica + cpu_features + Tesseract for Android ARM64
#
# Why cpu_features?
#   Tesseract 5.x requires CpuFeaturesNdkCompat (REQUIRED in its CMakeLists).
#   NDK bundles cpu_features source at $NDK/sources/third_party/cpu_features/
#   We build and install it first so Tesseract's find_package() succeeds.
# ============================================================
set -euo pipefail

TESSERACT_VERSION="5.3.4"
LEPTONICA_VERSION="1.84.1"
ANDROID_NDK="${ANDROID_NDK_ROOT:-${ANDROID_HOME:-}/ndk-bundle}"
ABI="arm64-v8a"
API_LEVEL=26
BUILD_DIR="$(pwd)/build_native_deps"
INSTALL_DIR="$BUILD_DIR/install"
OUT_DIR="$(pwd)/android/app/src/main/cpp"

echo "NDK:     $ANDROID_NDK"
echo "ABI:     $ABI  API: $API_LEVEL"
echo "Install: $INSTALL_DIR"

mkdir -p "$BUILD_DIR" "$INSTALL_DIR" \
         "$OUT_DIR/tesseract/lib/$ABI" \
         "$OUT_DIR/leptonica/lib/$ABI"

TOOLCHAIN="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64"
CC="$TOOLCHAIN/bin/aarch64-linux-android${API_LEVEL}-clang"
CXX="$TOOLCHAIN/bin/aarch64-linux-android${API_LEVEL}-clang++"
AR="$TOOLCHAIN/bin/llvm-ar"

ls "$CC"  || { echo "❌ clang not found: $CC";  exit 1; }
ls "$CXX" || { echo "❌ clang++ not found: $CXX"; exit 1; }

TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake"
CMAKE_ANDROID_ARGS=(
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE"
    -DANDROID_ABI="$ABI"
    -DANDROID_NATIVE_API_LEVEL="$API_LEVEL"
    -DCMAKE_BUILD_TYPE=Release
    -DCMAKE_C_COMPILER="$CC"
    -DCMAKE_CXX_COMPILER="$CXX"
    -DCMAKE_AR="$AR"
)

# ─────────────────────────────────────────────────────────────
# Step 1: cpu_features (required by Tesseract on Android)
# NDK bundles the source; we build & install to get the cmake config.
# ─────────────────────────────────────────────────────────────
echo ""
echo "=== Step 1: cpu_features (Tesseract dependency) ==="

CPU_FEAT_SRC="$ANDROID_NDK/sources/third_party/cpu_features"
CPU_FEAT_INSTALL="$INSTALL_DIR/cpu_features"

if [ ! -d "$CPU_FEAT_SRC" ]; then
    echo "⚠️  NDK cpu_features source not at $CPU_FEAT_SRC"
    echo "   Searching NDK for cpu_features..."
    CPU_FEAT_SRC=$(find "$ANDROID_NDK" -maxdepth 6 -type d -name "cpu_features" 2>/dev/null | head -1)
    if [ -z "$CPU_FEAT_SRC" ]; then
        echo "   Not found in NDK — cloning from GitHub..."
        CPU_FEAT_SRC="$BUILD_DIR/cpu_features"
        [ -d "$CPU_FEAT_SRC" ] || git clone --depth 1 \
            "https://github.com/google/cpu_features.git" "$CPU_FEAT_SRC"
    fi
fi
echo "cpu_features source: $CPU_FEAT_SRC"

CPU_FEAT_BUILD="$BUILD_DIR/cpu_features_build"
mkdir -p "$CPU_FEAT_BUILD"

cmake -S "$CPU_FEAT_SRC" -B "$CPU_FEAT_BUILD" \
    "${CMAKE_ANDROID_ARGS[@]}" \
    -DCMAKE_INSTALL_PREFIX="$CPU_FEAT_INSTALL" \
    -DBUILD_TESTING=OFF

cmake --build  "$CPU_FEAT_BUILD" -j"$(nproc)"
cmake --install "$CPU_FEAT_BUILD"

# Verify CpuFeaturesNdkCompatConfig.cmake was created
CPU_CMAKE=$(find "$CPU_FEAT_INSTALL" -name "CpuFeaturesNdkCompatConfig.cmake" 2>/dev/null | head -1)
if [ -z "$CPU_CMAKE" ]; then
    echo "⚠️  CpuFeaturesNdkCompatConfig.cmake not found in install."
    echo "   Trying alternative cmake config name..."
    CPU_CMAKE=$(find "$CPU_FEAT_INSTALL" -name "*.cmake" 2>/dev/null | head -3)
    echo "$CPU_CMAKE"
fi
CPU_FEAT_CMAKE_DIR=$(dirname "${CPU_CMAKE:-$CPU_FEAT_INSTALL/lib/cmake/CpuFeaturesNdkCompat}")
echo "✅ cpu_features cmake dir: $CPU_FEAT_CMAKE_DIR"

# ─────────────────────────────────────────────────────────────
# Step 2: Leptonica
# ─────────────────────────────────────────────────────────────
echo ""
echo "=== Step 2: Leptonica $LEPTONICA_VERSION ==="
cd "$BUILD_DIR"

LEPT_TAR="leptonica-${LEPTONICA_VERSION}.tar.gz"
if [ ! -d "leptonica-$LEPTONICA_VERSION" ]; then
    wget -q -O "$LEPT_TAR" \
        "https://github.com/DanBloomberg/leptonica/releases/download/$LEPTONICA_VERSION/$LEPT_TAR"
    tar xzf "$LEPT_TAR"
fi

LEPT_SRC="$BUILD_DIR/leptonica-$LEPTONICA_VERSION"
LEPT_BUILD="$BUILD_DIR/lept_build"
LEPT_INSTALL="$INSTALL_DIR/leptonica"
mkdir -p "$LEPT_BUILD"

cmake -S "$LEPT_SRC" -B "$LEPT_BUILD" \
    "${CMAKE_ANDROID_ARGS[@]}" \
    -DCMAKE_INSTALL_PREFIX="$LEPT_INSTALL" \
    -DBUILD_SHARED_LIBS=OFF \
    -DSW_BUILD=OFF \
    -DENABLE_GIF=OFF -DENABLE_JPEG=OFF -DENABLE_PNG=OFF \
    -DENABLE_TIFF=OFF -DENABLE_WEBP=OFF -DENABLE_OPENJPEG=OFF

cmake --build  "$LEPT_BUILD" -j"$(nproc)"
cmake --install "$LEPT_BUILD"

# cmake --install gives guaranteed path
LEPT_A=$(find "$LEPT_INSTALL" -name "libleptonica.a" | head -1)
[ -n "$LEPT_A" ] || LEPT_A=$(find "$LEPT_BUILD" -name "libleptonica.a" | head -1)
[ -n "$LEPT_A" ] || { echo "❌ libleptonica.a not found"; exit 1; }

cp "$LEPT_A" "$OUT_DIR/leptonica/lib/$ABI/libleptonica.a"
mkdir -p "$OUT_DIR/leptonica/include"
[ -d "$LEPT_INSTALL/include" ] && cp -r "$LEPT_INSTALL/include/." "$OUT_DIR/leptonica/include/" \
    || find "$LEPT_SRC/src" -name "*.h" -exec cp {} "$OUT_DIR/leptonica/include/" \;

echo "✅ libleptonica.a: $(du -sh "$OUT_DIR/leptonica/lib/$ABI/libleptonica.a" | cut -f1)"

# ─────────────────────────────────────────────────────────────
# Step 3: Tesseract
# ─────────────────────────────────────────────────────────────
echo ""
echo "=== Step 3: Tesseract $TESSERACT_VERSION ==="
cd "$BUILD_DIR"

TESS_TAR="${TESSERACT_VERSION}.tar.gz"
if [ ! -d "tesseract-$TESSERACT_VERSION" ]; then
    wget -q -O "$TESS_TAR" \
        "https://github.com/tesseract-ocr/tesseract/archive/refs/tags/$TESS_TAR"
    tar xzf "$TESS_TAR"
fi

TESS_SRC="$BUILD_DIR/tesseract-$TESSERACT_VERSION"
TESS_BUILD="$BUILD_DIR/tess_build"
TESS_INSTALL="$INSTALL_DIR/tesseract"
mkdir -p "$TESS_BUILD"

# Locate Leptonica cmake config for Tesseract
LEPT_CMAKE=$(find "$LEPT_INSTALL" -name "LeptonicaConfig.cmake" -o \
                                   -name "leptonica-config.cmake" 2>/dev/null | head -1)
LEPT_CMAKE_DIR=$(dirname "${LEPT_CMAKE:-$LEPT_INSTALL/lib/cmake/leptonica}")

cmake -S "$TESS_SRC" -B "$TESS_BUILD" \
    "${CMAKE_ANDROID_ARGS[@]}" \
    -DCMAKE_INSTALL_PREFIX="$TESS_INSTALL" \
    -DBUILD_SHARED_LIBS=OFF \
    -DBUILD_TRAINING_TOOLS=OFF \
    -DDISABLED_LEGACY_ENGINE=OFF \
    -DLeptonica_DIR="$LEPT_CMAKE_DIR" \
    -DLEPTONICA_INCLUDE_DIRS="$OUT_DIR/leptonica/include" \
    -DLEPTONICA_LIBRARIES="$OUT_DIR/leptonica/lib/$ABI/libleptonica.a" \
    -DCpuFeaturesNdkCompat_DIR="$CPU_FEAT_CMAKE_DIR" \
    -DCMAKE_PREFIX_PATH="$CPU_FEAT_INSTALL;$LEPT_INSTALL" \
    -DLEPT_TIFF_RESULT=1 \
    -DLEPT_TIFF_RESULT__TRYRUN_OUTPUT="" \
    -DLEPT_TIFF_COMPILE_SUCCESS=TRUE

cmake --build  "$TESS_BUILD" -j"$(nproc)"
cmake --install "$TESS_BUILD"

TESS_A=$(find "$TESS_INSTALL" -name "libtesseract.a" | head -1)
[ -n "$TESS_A" ] || TESS_A=$(find "$TESS_BUILD" -name "libtesseract.a" | head -1)
[ -n "$TESS_A" ] || { echo "❌ libtesseract.a not found"; exit 1; }

cp "$TESS_A" "$OUT_DIR/tesseract/lib/$ABI/libtesseract.a"
mkdir -p "$OUT_DIR/tesseract/include/tesseract"
[ -d "$TESS_INSTALL/include" ] && cp -r "$TESS_INSTALL/include/." "$OUT_DIR/tesseract/include/" \
    || find "$TESS_SRC/include" -name "*.h" -exec cp {} "$OUT_DIR/tesseract/include/tesseract/" \;

echo "✅ libtesseract.a: $(du -sh "$OUT_DIR/tesseract/lib/$ABI/libtesseract.a" | cut -f1)"

# ─────────────────────────────────────────────────────────────
# Step 4: English tessdata
# ─────────────────────────────────────────────────────────────
echo ""
echo "=== Step 4: Tessdata ==="
TESSDATA_DIR="android/app/src/main/assets/tessdata"
mkdir -p "$TESSDATA_DIR"
if [ ! -f "$TESSDATA_DIR/eng.traineddata" ]; then
    wget -q -O "$TESSDATA_DIR/eng.traineddata" \
        "https://github.com/tesseract-ocr/tessdata_fast/raw/main/eng.traineddata"
fi
echo "✅ eng.traineddata: $(du -sh "$TESSDATA_DIR/eng.traineddata" | cut -f1)"

echo ""
echo "🎉 All native dependencies built!"
ls -lh "$OUT_DIR/leptonica/lib/$ABI/" "$OUT_DIR/tesseract/lib/$ABI/"
