# Phormi Android

Phormi is an Android browser and keyboard project. The Android app is in `app/`; project
structure and platform limitations are summarized in `docs/PLATFORM_ARCHITECTURE.md` and
`docs/FEATURE_COMPLETION_STATUS.md`.

## Requirements

- JDK 17
- Android SDK platform 36 and Android Build Tools compatible with AGP 8.9.1
- An Android emulator or device for connected instrumentation tests
- Network access for the Gradle wrapper and Maven/Google Android dependencies

The repository includes the Gradle wrapper pinned to **Gradle 8.11.1**, matching the
version used by the Android CI workflows. Set `ANDROID_HOME`/`ANDROID_SDK_ROOT`, or create
a local `local.properties` containing `sdk.dir=/path/to/Android/Sdk` (this file is ignored
by Git).

## Quick start

From the repository root:

```bash
./gradlew --version
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
To run the Android instrumentation suite, start an emulator or connect a device with USB
debugging enabled and run:

```bash
./gradlew connectedDebugAndroidTest
```

The source/resource audit scripts and the APK-signature verifier tests can be run without an emulator:

```bash
for audit in tools/audit_*.py; do python3 "$audit" || exit 1; done
python3 tools/test_verify_apk_signature.py
```

## Release and signing expectations

`./gradlew assembleRelease` currently produces
`app/build/outputs/apk/release/app-release-unsigned.apk`: `app/build.gradle.kts` has no
release signing configuration. Do not treat that file as a signed store release or use
it to replace an existing installation whose local browser data matters.

`BUILD_NOTES.md` refers to central AEM signing, but this checkout does not contain a
release keystore, a reproducible local signing configuration, or a verification step that
prints and checks the certificate on the final centrally signed APK. Confirm the signing
identity and verify the delivered APK before distribution. The repository helper runs
`apksigner verify --print-certs` and can pin an approved certificate fingerprint:

```bash
python3 tools/verify_apk_signature.py path/to/delivered.apk --expected-sha256 <approved-64-hex-fingerprint>
```

Without `--expected-sha256`, it confirms the APK has a valid signature and prints each
signer's certificate digest. Never commit signing keys or passwords to this repository;
use the organization's approved secret/signing process.

## Project notes

- `docs/NETWORKS_DATA_VPN.md` — HTTP/LAN navigation policy, data saver and VPN scope.
- `docs/DOWNLOADS_SECURITY.md` — download cookies, redirect handling and resume recovery.
- `docs/FEATURE_COMPLETION_STATUS.md` — completed features and known limitations.
- `tools/audit_*.py` — source-level audits; these complement but do not replace Gradle
  tests, lint, emulator tests or release-signature verification.
