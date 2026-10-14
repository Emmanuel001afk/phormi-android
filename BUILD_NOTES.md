# Build and validation

Android minimum: API 26. Compile/target: API 36. JDK 17, Gradle 8.11.1.

```sh
python3 tools/audit_project.py
gradle :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
```

A Gradle wrapper is not checked in; install the stated Gradle version, JDK and Android SDK first. The build also needs access to Google's Android/Maven dependencies. `.github/workflows/validate.yml` provisions this environment without running the publishing workflow.

- All `arena/**` pushes and PRs to `main`: resource checks, DOM/media-source Node tests, debug APK, Robolectric regressions and Android lint.
- Include `[emulator]` in a push commit message (or dispatch validation manually) to additionally run the API 35 real-IME integration smoke test. It exercises editor typing, correction/undo, emoji, writing-panel navigation, phone keys and the browser's own Home search field. Screenshots, test reports and crash logs are retained as an artifact.
- Artifacts: `phormi-validation` (debug APK/unit/lint reports) and `phormi-emulator-smoke` (when run). Retention: 7 days. These are validation builds, not a signed production release.

**Do not uninstall an existing real installation to get around a signing mismatch.** That deletes browser data. Test a differently signed debug APK on an emulator/spare device, or use the existing signing pipeline to make an upgrade signed with the original release key. A build/test pass does not replace physical-device, real-network or configured-provider testing.

See [repair and QA notes](docs/BROWSER_KEYBOARD_REPAIR.md) for behavior, limitations and the remaining manual matrix.
