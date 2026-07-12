#!/usr/bin/env bash
# ============================================================
# generate_keystore.sh — Production-grade Android release keystore generator
#
# PROBLEM THIS SOLVES:
#   "Failed to read key from store" + "Tag number over 30 is not supported"
#
#   Root cause: openssl / newer keytool versions create PKCS#12 v2 keystores
#   by default. Java's older KeyStore API (used by apksigner / jarsigner)
#   throws "Tag number over 30 is not supported" when reading PKCS#12 v2
#   because it uses ASN.1 tags > 30 which the BC/SunPKCS12 provider in
#   older JDK versions cannot parse.
#
# SOLUTION:
#   Use keytool with explicit -storetype JKS (not PKCS12).
#   JKS format is the legacy Java KeyStore — 100% compatible with AGP,
#   apksigner, jarsigner, and all Android build tooling.
#
# USAGE (run once locally or in a setup job):
#   bash scripts/generate_keystore.sh
#
#   Then copy the base64 output to GitHub Secrets:
#     KEYSTORE_BASE64  → contents of release.keystore.b64
#     KEYSTORE_PASSWORD → your chosen storepass
#     KEY_ALIAS        → visionagent
#     KEY_PASSWORD     → your chosen keypass
#
# ============================================================
set -euo pipefail

# ── Config (edit these before running) ───────────────────────
KEYSTORE_FILE="release.keystore"
STORE_PASS="${KEYSTORE_PASSWORD:-VisionAgent@2024!}"   # Change this!
KEY_ALIAS_NAME="${KEY_ALIAS:-visionagent}"
KEY_PASS="${KEY_PASSWORD:-VisionAgent@2024!}"          # Change this!
DNAME="CN=VisionAgent,OU=Engineering,O=VisionAgent,L=Kalkaji,ST=HR,C=IN"
VALIDITY_DAYS=10000   # ~27 years
KEY_SIZE=4096
# ──────────────────────────────────────────────────────────────

echo ""
echo "╔══════════════════════════════════════════════════════════╗"
echo "║   VisionAgent — Android Release Keystore Generator      ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo ""

# Check keytool availability
if ! command -v keytool &>/dev/null; then
    echo "❌ keytool not found. Install Java JDK first."
    echo "   Ubuntu/Debian: sudo apt-get install default-jdk"
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | head -1)
echo "☕ Java: $JAVA_VERSION"
echo ""

# Remove existing keystore if present
if [ -f "$KEYSTORE_FILE" ]; then
    echo "⚠️  Existing $KEYSTORE_FILE found — removing..."
    rm -f "$KEYSTORE_FILE"
fi

echo "🔑 Generating JKS keystore (NOT PKCS#12 — avoids 'Tag number over 30' error)..."
echo "   Store type : JKS"
echo "   Key size   : ${KEY_SIZE} bits RSA"
echo "   Validity   : ${VALIDITY_DAYS} days (~27 years)"
echo "   Alias      : ${KEY_ALIAS_NAME}"
echo "   DN         : ${DNAME}"
echo ""

# ── Generate the keystore ─────────────────────────────────────
# CRITICAL FLAGS:
#   -storetype JKS    → Force JKS (not PKCS12). PKCS12 v2 causes
#                        "Tag number over 30 is not supported" in Java < 17
#   -keyalg RSA       → RSA key (required for Android signing)
#   -keysize 4096     → Strong key
#   -sigalg SHA256withRSA → Modern signature algorithm
#   -validity 10000   → Long validity (Play Store recommends > 25 years)
keytool \
    -genkeypair \
    -storetype JKS \
    -keystore "$KEYSTORE_FILE" \
    -storepass "$STORE_PASS" \
    -alias "$KEY_ALIAS_NAME" \
    -keypass "$KEY_PASS" \
    -keyalg RSA \
    -keysize "$KEY_SIZE" \
    -sigalg SHA256withRSA \
    -validity "$VALIDITY_DAYS" \
    -dname "$DNAME" \
    -v 2>&1

echo ""
echo "✅ Keystore generated: $KEYSTORE_FILE"
echo "   Size: $(du -sh "$KEYSTORE_FILE" | cut -f1)"
echo ""

# ── Verify the keystore ───────────────────────────────────────
echo "🔍 Verifying keystore contents..."
keytool -list -v \
    -keystore "$KEYSTORE_FILE" \
    -storepass "$STORE_PASS" \
    -storetype JKS 2>&1 | grep -E "Keystore type|Alias name|Creation date|Entry type|Certificate fingerprints|SHA256"

echo ""

# ── Export as base64 for GitHub Secrets ──────────────────────
B64_FILE="${KEYSTORE_FILE}.b64"
base64 -w 0 "$KEYSTORE_FILE" > "$B64_FILE"

echo "✅ Base64 exported: $B64_FILE"
echo "   Size: $(du -sh "$B64_FILE" | cut -f1)"
echo ""

# ── Print GitHub Secrets setup instructions ───────────────────
echo "╔══════════════════════════════════════════════════════════╗"
echo "║          GITHUB SECRETS — Copy these values             ║"
echo "╠══════════════════════════════════════════════════════════╣"
echo "║"
echo "║  Go to: GitHub repo → Settings → Secrets → Actions → New secret"
echo "║"
echo "║  1. KEYSTORE_BASE64"
echo "║     Value: (entire contents of ${B64_FILE})"
echo "║     Run:   cat ${B64_FILE}"
echo "║"
echo "║  2. KEYSTORE_PASSWORD"
echo "║     Value: ${STORE_PASS}"
echo "║"
echo "║  3. KEY_ALIAS"
echo "║     Value: ${KEY_ALIAS_NAME}"
echo "║"
echo "║  4. KEY_PASSWORD"
echo "║     Value: ${KEY_PASS}"
echo "║"
echo "╚══════════════════════════════════════════════════════════╝"
echo ""

# ── Security: DO NOT commit keystore to git ───────────────────
# Add to .gitignore if not already there
GITIGNORE_ROOT="$(dirname "$(dirname "$(realpath "$0")")")/.gitignore"
if [ -f "$GITIGNORE_ROOT" ]; then
    if ! grep -q "\.keystore" "$GITIGNORE_ROOT"; then
        echo "# Android keystores — NEVER commit" >> "$GITIGNORE_ROOT"
        echo "*.keystore" >> "$GITIGNORE_ROOT"
        echo "*.jks" >> "$GITIGNORE_ROOT"
        echo "*.keystore.b64" >> "$GITIGNORE_ROOT"
        echo "⚠️  Added *.keystore, *.jks, *.keystore.b64 to .gitignore"
    else
        echo "✅ .gitignore already covers keystore files"
    fi
fi

echo ""
echo "⚠️  SECURITY REMINDER:"
echo "   • DO NOT commit $KEYSTORE_FILE or $B64_FILE to git"
echo "   • Store these files securely (password manager, encrypted backup)"
echo "   • If this key is lost, you CANNOT update your Play Store app"
echo ""
echo "🎉 Done! Follow the GitHub Secrets instructions above."
echo ""
