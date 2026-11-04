# Desktop mode

Phormi's desktop mode requests a desktop user agent, desktop user-agent client hints,
and a wide desktop viewport for ordinary websites. Phormi Search's local results page
stays mobile-first because it is rendered by the app itself.

## Using it

- **Current website:** tap **Desktop site** in the Browse menu. This is a direct
  one-tap toggle for the current host; it does not open a scope chooser. The row shows
  `Desktop site · On` or `Desktop site · Off`, and the page reloads immediately.
- **All websites:** open **Browser settings → Website layout → Use desktop sites by
  default**. Changing this default clears per-site choices and reloads open website
  tabs when you return to the browser.
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
- Toggling the current host also updates other live tabs on that same host.

## What changes

- User agent: desktop Chrome on Linux, keeping the mobile WebView's Chrome major version.
- User-agent client hints: mobile/desktop, platform, architecture, bitness, model,
  brand/full-version list, and form factors where the installed WebView supports them.
- Viewport: a **1280 CSS-pixel** desktop layout viewport. This is intentionally above
  common responsive desktop breakpoints; for example, GitHub switches to its desktop
  header at 1012px, so the older 980px override could still leave a mobile layout.
- Overview scaling fits the wide desktop page to the screen; pinch zoom remains
  available. Mobile pages keep their normal device-width viewport.
- Text zoom and zoom controls remain governed by the browser preferences.

## Validation

- `python3 tools/audit_desktop.py`
- `python3 tools/audit_project.py`
- `python3 tools/audit_search.py`
- `gradle testDebugUnitTest`
- `gradle lintDebug`
- Device/emulator pass: one-tap per-site toggle on GitHub, global toggle, cross-host
  link, Back/Forward, new tab, deferred tab resume, split view, and Phormi Search
  remaining mobile-first.

The sandbox used for this change does not include Java/Gradle/Android SDK or an emulator,
so compilation, unit tests, lint, and visual behavior are validated in CI rather than locally.
