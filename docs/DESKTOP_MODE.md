# Desktop mode

Phormi's desktop mode requests a desktop user agent, desktop user-agent client hints,
and a wide desktop viewport for ordinary websites. Phormi Search's local results page
stays mobile-first because it is rendered by the app itself.

## Scope

- **Per site:** turn desktop layout on or off for the current host.
- **All sites:** change the global default and clear per-host overrides.
- A host can opt in while the global default is mobile, or opt out while the global
  default is desktop. The explicit mobile override wins if both sets ever contain the
  same host.
- Host matching ignores case and a leading `www.`.

## When the mode is applied

- Before omnibox navigation, new-tab loads, deferred-tab resume, and tab restoration.
- Before ordinary main-frame link navigations and cross-host redirects.
- Before Back/Forward when the target history entry belongs to another host.
- To every live tab when the global default changes; live website tabs are reloaded so
  the change is visible immediately. Dormant tabs apply the resolved mode on resume.

## What changes

- User agent: desktop Chrome on Linux, keeping the mobile WebView's Chrome major version.
- User-agent client hints: mobile/desktop, platform, architecture, bitness, model,
  brand/full-version list, and form factors where the installed WebView supports them.
- Viewport: wide viewport with desktop layout algorithm; overview scaling is disabled
  for desktop pages. A late viewport reconciliation repairs pages that force a mobile
  CSS width after load.
- Text zoom and zoom controls remain governed by the browser preferences.

## Menu state

The Browse menu receives the current page URL, resolved desktop state, and Back/Forward
availability. The desktop item shows `Desktop site · On` or `Desktop site · Off` instead
of a static label.

## Validation

- `python3 tools/audit_project.py`
- `python3 tools/audit_search.py`
- `gradle testDebugUnitTest`
- `gradle lintDebug`
- Device/emulator pass: per-site toggle, global toggle, cross-host link, Back/Forward,
  new tab, deferred tab resume, split view, and Phormi Search remaining mobile-first.

The sandbox used for this change does not include Java/Gradle/Android SDK or an emulator,
so compilation, unit tests, lint, and visual behavior are validated in CI rather than locally.
