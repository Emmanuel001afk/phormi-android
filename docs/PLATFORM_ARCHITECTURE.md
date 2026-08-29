# Phormi platform architecture

Phormi is being built as one browser product with platform-specific shells, not as an Android APK that is later renamed for another OS.

## Targets

- Android: APK/AAB, using the current native Android WebView shell.
- Windows/Linux/macOS: a desktop shell will use the same browser/product logic and web UI, packaged as a desktop executable (for example, Windows MSIX/EXE).
- iOS/iPadOS: a native iOS shell will use WKWebView and produce an IPA/App Store build.

## Important rule

The Android APK is not itself the Windows or iOS artifact. Cross-platform support is a product architecture requirement. Shared browser behavior, settings, search configuration, tab state, and theme data should therefore be kept platform-neutral wherever practical, while WebView/WKWebView/desktop web-container operations stay in each platform shell.

## Theme contract

The same theme state is represented in `shared/phormi_theme.json`. Each platform can consume that state without forcing the Android implementation into the other platforms.

## Current phase

Build 16 implements the appearance layer on Android first: system/light/dark mode, optional daily accent rotation, and a persisted custom start-page wallpaper. The desktop and iOS shells remain separate targets to be built later; this build does not falsely treat APK conversion as cross-platform support.
