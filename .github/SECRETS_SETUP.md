# GitHub Secrets Setup Guide

APK release sign karne ke liye ye secrets GitHub repo mein add karein:

## Settings → Secrets and Variables → Actions → New Repository Secret

| Secret Name | Description | Example |
|---|---|---|
| `KEYSTORE_BASE64` | Release keystore (Base64 encoded) | `cat release.keystore \| base64` |
| `KEYSTORE_PASSWORD` | Keystore password | `MyStr0ngPass!` |
| `KEY_ALIAS` | Key alias name | `vision-agent-key` |
| `KEY_PASSWORD` | Key password | `MyStr0ngPass!` |

## Keystore Generate karne ka tarika:

```bash
# Terminal mein run karein
keytool -genkey -v \
  -keystore release.keystore \
  -alias vision-agent-key \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000

# Base64 encode karein
base64 -i release.keystore | pbcopy   # macOS
base64 release.keystore               # Linux
```

## GitHub Release Trigger:

```bash
# Tag push se release APK automatically banega
git tag v1.0.0
git push origin v1.0.0
```

## Workflow Files:
- `.github/workflows/android_ci_cd.yml` — Main CI/CD pipeline
- `.github/workflows/nightly_checks.yml` — Nightly benchmarks
