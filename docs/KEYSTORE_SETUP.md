# 🔑 Android Release Keystore Setup Guide

## Problem jo fix hua

```
Failed to read key from store "/tmp/release.keystore"
Tag number over 30 is not supported
```

### Root Cause
Yeh error tab aata hai jab keystore **PKCS#12 v2 format** mein hoti hai.  
JDK 9+ mein `keytool` default `PKCS12` use karta hai.  
Android build tools (AGP, apksigner) ke kuch versions isko parse nahi kar paate → **"Tag number over 30"** error.

### Fix Applied
CI pipeline ab automatically detect karta hai PKCS12 vs JKS, aur PKCS12 ko JKS mein convert karta hai before building.

---

## 📋 Step-by-Step: Pehli baar keystore banana

### Option A: Script se (Recommended)

Apne kisi bhi machine pe jahan Java installed ho:

```bash
# Script clone ke baad chalao:
bash scripts/generate_keystore.sh
```

Yeh script:
- **JKS format** mein keystore banata hai (PKCS12 nahi)
- 4096-bit RSA key generate karta hai
- 27 saal ki validity
- `release.keystore.b64` file banata hai (GitHub Secret ke liye)

---

### Option B: Manual (keytool se)

```bash
keytool -genkeypair \
  -storetype JKS \
  -keystore release.keystore \
  -storepass "AapkaPasswordYahan" \
  -alias visionagent \
  -keypass "AapkaPasswordYahan" \
  -keyalg RSA \
  -keysize 4096 \
  -sigalg SHA256withRSA \
  -validity 10000 \
  -dname "CN=VisionAgent,OU=Eng,O=VisionAgent,L=Kalkaji,ST=HR,C=IN"
```

> ⚠️ **`-storetype JKS` ZAROOR likho** — warna default PKCS12 banega aur "Tag number over 30" error aayega

Base64 encode karo:
```bash
base64 -w 0 release.keystore > release.keystore.b64
```

---

## 🔐 GitHub Secrets Setup

**Repo → Settings → Secrets and variables → Actions → New repository secret**

| Secret Name | Value |
|-------------|-------|
| `KEYSTORE_BASE64` | `cat release.keystore.b64` ka output (poora string) |
| `KEYSTORE_PASSWORD` | Wahi password jo `-storepass` mein diya |
| `KEY_ALIAS` | `visionagent` (ya jo bhi alias rakkha) |
| `KEY_PASSWORD` | Wahi password jo `-keypass` mein diya |

---

## ✅ Verify karo (local)

```bash
# Keystore theek hai?
keytool -list -v \
  -keystore release.keystore \
  -storepass "AapkaPassword" \
  -storetype JKS

# Output mein yeh dikhna chahiye:
# Keystore type: JKS
# Alias name: visionagent
# Entry type: PrivateKeyEntry
```

---

## 🔄 Agar purana PKCS12 keystore hai

**Option 1**: Naya JKS keystore banao (recommended — fresh start)

**Option 2**: Convert karo existing PKCS12 → JKS:
```bash
keytool -importkeystore \
  -srckeystore release.keystore \
  -srcstoretype PKCS12 \
  -srcstorepass "AapkaPassword" \
  -srckeypass "AapkaPassword" \
  -srcalias visionagent \
  -destkeystore release_jks.keystore \
  -deststoretype JKS \
  -deststorepass "AapkaPassword" \
  -destkeypass "AapkaPassword" \
  -destalias visionagent \
  -noprompt

# Phir naya base64 banao:
base64 -w 0 release_jks.keystore > release.keystore.b64
```

Phir `KEYSTORE_BASE64` secret update karo GitHub pe.

---

## ⚠️ Security Rules

- ❌ `release.keystore` ya `*.b64` ko **git mein push mat karo**
- ✅ `.gitignore` mein `*.keystore`, `*.jks`, `*.b64` add karo
- ✅ Keystore ka backup rakho (encrypted storage mein)
- ⚠️ Agar yeh key lose ho gayi → Play Store pe app update karna IMPOSSIBLE ho jaata hai

---

## CI/CD Flow (What happens automatically)

```
GitHub Push (main/tag) →
  CI: Decode KEYSTORE_BASE64 from secret →
  CI: Detect keystore type (JKS or PKCS12) →
  CI: [If PKCS12] Convert to JKS automatically →
  CI: Verify keystore readable →
  Gradle: assembleRelease (signed APK) →
  apksigner: verify signature →
  Upload: signed APK as artifact
```
