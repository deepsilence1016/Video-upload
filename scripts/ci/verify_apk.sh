#!/usr/bin/env bash
# ============================================================
# verify_apk.sh — Complete APK Verification Script
# Run after every release build as part of CI/CD
# ============================================================
set -euo pipefail

APK_PATH="${1:?Usage: verify_apk.sh <path/to/app.apk>}"
ANDROID_SDK="${ANDROID_HOME:-/usr/local/lib/android/sdk}"
BUILD_TOOLS="$ANDROID_SDK/build-tools/34.0.0"

pass() { echo "OK: $1"; }
warn() { echo "WARN: $1"; }
fail() { echo "FAIL: $1"; exit 1; }

echo "============================================"
echo " Vision Agent APK Verification"
echo " APK: $APK_PATH"
echo "============================================"

# 1. File exists
[ -f "$APK_PATH" ] || fail "APK file not found"
pass "APK file exists"

# 2. Size check (5MB - 150MB)
SIZE_BYTES=$(stat -c%s "$APK_PATH")
SIZE_MB=$((SIZE_BYTES / 1024 / 1024))
echo "Size: ${SIZE_MB}MB"
[ "$SIZE_MB" -gt 5   ] || fail "APK too small: ${SIZE_MB}MB"
[ "$SIZE_MB" -lt 150 ] || warn "APK large: ${SIZE_MB}MB"
pass "APK size OK: ${SIZE_MB}MB"

# 3. Signature verification
"$BUILD_TOOLS/apksigner" verify --verbose --print-certs "$APK_PATH" 2>&1 | tee /tmp/sig_verify.txt
grep -q "Verifies" /tmp/sig_verify.txt && pass "Signature valid" || fail "Signature INVALID"

# 4. SHA256 hash
SHA256=$(sha256sum "$APK_PATH" | awk '{print $1}')
echo "SHA256: $SHA256"
echo "$SHA256" > "$(dirname "$APK_PATH")/$(basename "$APK_PATH" .apk).sha256"
pass "SHA256 saved"

# 5. Native libraries check
NATIVE_LIBS=$(unzip -l "$APK_PATH" 2>/dev/null | grep "\.so" | awk '{print $4}')
if [ -n "$NATIVE_LIBS" ]; then
    for lib in libVisionCore.so libOCRCore.so libFrameProcessor.so libvision_agent_core.so; do
        echo "$NATIVE_LIBS" | grep -q "$lib" && pass "$lib present" || warn "$lib missing"
    done
fi

echo ""
echo "APK VERIFICATION COMPLETE"
echo "APK:    $APK_PATH"
echo "Size:   ${SIZE_MB}MB"
echo "SHA256: $SHA256"
pass "All checks done"
