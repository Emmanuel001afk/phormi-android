# Fixes applied after the Chrome / Gboard audit

**Branch:** `arena/fabc59f4-phormi-android` · **Base:** `d2436ad` · **Date:** 2026-11-02  
**Companion document:** `docs/PHORMI_CHROME_GBOARD_AUDIT.md` is the *before* snapshot. This file is the *after* record.

You asked me to fix the issues I found and noted that you can tell me to revert if anything feels wrong. Everything below is in one follow-up commit, so reverting is a single command (see §7). I did **not** change any product decisions that need your input; those are listed explicitly in §6.

---

## 1. Verification — read this first

There is still **no Android toolchain in this workspace**: no `gradle`/`gradlew`, no `kotlinc`, no Android SDK, no emulator and no device. Therefore:

- **Nothing was compiled, installed, or run on a device.** All conclusions here are static.
- I validated with:
  - `python3 tools/audit_project.py` → `PHORMI SOURCE AUDIT: PASS (25 components, 22 layouts, 186 resource IDs, 8 dictionaries)`;
  - a tree-sitter Kotlin parse of all 100 Kotlin files (no *new* syntax errors versus the pre-existing baseline);
  - XML parsing of every file under `app/src/main`;
  - a cross-check that every `R.id.*` reference, every manifest component, and every new helper symbol exists;
  - a Python mirror of the three new pure-Kotlin algorithms to confirm the new unit-test expectations.
- `MainActivity.kt` still has one pre-existing parse quirk (`8.dp()` integer-literal method calls in `TabsOverviewActivity.kt`) that tree-sitter cannot handle. It is valid Kotlin and is unchanged.

**Please build and smoke-test on a device before shipping.** Gesture/IME behaviour (glide, accent popups, candidates positioning) is the part most likely to need device tuning.

---

## 2. Things that didn't work — fixed

### 2.1 The keyboard "compatibility layer" was dead code → now real features
- Deleted `PhormiKeyboardRenderCompat.kt`. Its top-level `PhormiKeyboardServiceV2.render()` extension was permanently shadowed by the service's private `render()` member, so **none** of it ran. It also used reflection into private fields and would have broken under R8.
- Added `PhormiKeyboardLocales.kt` — plain, unit-testable Kotlin with no Android/reflection dependency:
  - real **QWERTY / AZERTY (fr) / QWERTZ (de, cs)** letter rows, now selected by the active subtype;
  - real **long-press accents** for 14 Latin-script locales, shown in a non-focusable `PopupWindow` anchored to the key (works from an IME without stealing the editor's focus);
  - **glide typing** that resolves a finger trace to the best offline dictionary word via first/last-letter anchoring and longest-common-subsequence scoring, instead of concatenating every crossed key into gibberish. Candidates come from the bundled dictionaries and offline word packs.
  - `latinLanguages` is the single source of truth for which scripts the IME can actually render.
- `PhormiKeyboardServiceV2` now overrides `onCurrentInputMethodSubtypeChanged`, `onConfigurationChanged` and `onComputeInsets`, and exposes a public `currentInputSubtype()` / `onDictionaryResult()` instead of being reached by reflection.
- `PhormiKeyboardDictionary.wordsWithFirst()` was added to feed the glide resolver.

### 2.2 Reflection and no-op paths in prediction
- `PhormiKeyboardDictionaryApi.refreshKeyboard()` and `PhormiKeyboardTextEngine` no longer reflect into the private `Companion.instance` field and look up methods by name. They use `PhormiKeyboardServiceV2.activeInstance()`.
- Removed the `loadDiskInner()` stub that always returned `emptyList()`; the working `loadDisk(context, ...)` path in `PhormiKeyboardTextEngine` is now the only disk path.
- `PhormiKeyboardClipboardStore.shouldCaptureForActiveEditor()` used `getDeclaredField("instance")` on the wrong class (the field lives in `Companion`), so the lookup always failed and clipboard capture **always** ran — including while a password field was focused. It now uses `activeInstance()` and also skips URI-like fields.

### 2.3 41 advertised locales, one usable layout
- `method_phormi_keyboard.xml` now declares **24 Latin-script subtypes** (down from 41), all `isAsciiCapable="true"`. Arabic, Devanagari, Bengali, Gurmukhi, Gujarati, Tamil, Telugu, Malayalam, Thai, CJK, Cyrillic, Ukrainian, Greek and Hebrew were removed because there is no layout or composition path for them.
- `PhormiKeyboardSettingsActivity` no longer claims ~40 "built-in language layers" or that every language has character long-presses. It describes exactly what exists.
- `PhormiKeyboardLocalesTest` asserts the layouts, accents and script allow-list.

### 2.4 IME metadata / theming mismatches
- Theme 6 is now **"Berry"** in both settings surfaces (was "Rose" in one, "Berry" in the other).
- The eight theme buttons are laid out as **two rows of four** instead of one clipped row.
- `PhormiKeyboardSettingsActivity`, `PhormiKeyboardVoiceActivity`, `PhormiKeyboardMediaActivity`, `PhormiAiEmojiActivity` and `PhormiKeyboardStickerActivity` now use the app's dark `Theme.Phormi` instead of a light platform theme over hardcoded dark backgrounds.

### 2.5 Unreachable activities and never-started services
- `NewsActivity` and `PhormiKeyboardStickerActivity` are now **registered in `AndroidManifest.xml`** and reachable from new **News** and **Sticker studio** menu entries. `NewsActivity`'s stale "missing item_news.xml" comment was corrected.
- Deleted two service shells that were declared but never started:
  - `CentralHubBridgeService.kt` (a localhost HTTP server whose `bindActivity()` had no callers);
  - `PhormiVpnService.kt` (a `VpnService` whose `isRunning` flag was always false). `VpnActivity` now reads its own `vpn_requested` state via `vpnRequested()`.

### 2.6 VPN external-app mismatch and plain HTTP
- `OpenVpnController` now tries **every installed supported client** (`de.blinkt.openvpn`, `net.openvpn.openvpn`, `com.openvpn.client`) when binding, instead of hardcoding one. The error message now distinguishes "not installed" from "installed but does not expose the remote API".
- `VpnActivity` no longer keeps a duplicate, unused package list; `OpenVpnController.API_PACKAGES` is the single list.
- Removed the plain-HTTP VPN Gate fetch. All profile sources are HTTPS.

### 2.7 The Go button was invisible
- `activity_main.xml`: `btn_go` was `layout_width="0dp"` with empty text and no weight. It is now a 40 dp **"Go"** control with a content description; the existing click listener finally has something to click.

### 2.8 Permission over-granting and auto-granted location
- `onRequestPermissionsResult()` now calls `request.grant(pendingPermissionResources)` — only the audio/video resources that were filtered in `onPermissionRequest()` — instead of `request.grant(request.resources)`, which could over-grant.
- Added `PhormiSitePermissions.kt`: normalises origins, stores **per-origin** decisions, and provides a three-way **Allow once / Always allow on this site / Block** dialog. Holding the OS `ACCESS_FINE_LOCATION` permission is no longer treated as consent for every website. Retain is always `false`, so the app's store is the single source of truth and "Reset site permissions" really revokes a decision.
- The **site-info panel** (`btn_site_info`) shows URL, connection security and the site's location decision, with a reset action.

### 2.9 Error and warning surfaces
- `onReceivedSslError()` now shows a real **"This connection is not private"** dialog with the specific certificate problem, a "Back to safety" action, and a deliberately two-step "Advanced → Continue (unsafe)" path. No exception is remembered.
- `onSafeBrowsingHit()` now names the threat type and shows a blocking explanation dialog instead of a transient toast.
- Main-frame network errors render a **full-page error document** with a "Try again" link instead of a toast over a blank screen.
- `onProgressChanged()` drives a new horizontal **progress bar** under the omnibox.
- `shouldOverrideUrlLoading()` now **blocks `javascript:` and `file:`** navigation (file access is disabled in settings anyway) while keeping `data:`/`blob:` for legitimate web-app downloads.
- The generated Phormi Search document's `phormi.local/refresh` link is intercepted and handled instead of being passed to WebView as a non-existent URL.
- `onShowCustomView()` no longer forces landscape orientation for every HTML fullscreen view.

### 2.10 Ghost mode silently degraded
- `GhostActivity` now shows a clear **"Ghost mode is NOT private on this device"** warning when `WebViewFeature.MULTI_PROFILE` is unavailable, instead of quietly using the ordinary default profile.
- The second in-activity ghost path (`createNewTab(forceGhost = true)`) now warns similarly when profiles are unsupported.

### 2.11 Fragile / confusing product logic
- `BookmarksActivity` no longer inspects `Throwable().stackTrace` to decide whether "remove" means delete or hide. Behaviour is an explicit `hideOnly` / `hideQuickAccessRemovals` parameter, set by the Quick Access caller.
- `MenuActivity`'s "Accounts & sign-in" is now **"Accounts & services"** (it opens provider shortcuts, not an account). `ACTION_SETTINGS` is now actually emitted and opens `PhormiBrowserSettingsActivity` instead of an appearance chooser.
- The two duplicated navigation heuristics are now one `PhormiNavigation.resolve()` used by both the omnibox and the new-tab search box. It is stricter (e.g. `version 1.5` is a search, not a hostname) and guarantees typed non-web schemes are never handed to WebView.
- `searchUrlForProvider()` no longer returns a fake `phormi-unified://` URL.
- **Phormi Search honesty:** the footer now says it aggregates public result pages (not "independent search indexes"), hardcoded brand weighting (Google 100, Bing 92 …) was replaced by equal provider weighting, the document is rendered at most twice instead of up to 11 times, Refresh reloads the cached result document, and an in-page "Refresh results" link explicitly requests fresh data.
- `loadFaviconInto()` no longer sends every shortcut host to Google's `s2/favicons` service; icons come only from the site itself.
- **Find in page** now has a persistent dialog with a live match counter and a Next button, instead of one-shot Find/Close.
- **Omnibox suggestions** from local history and favorites are added (no network requests).

---

## 3. Incomplete things — improved

| Gap | What changed |
|---|---|
| No omnibox | Added local autocomplete, a working Go button, a tappable site-info/connection control and a load progress bar. |
| No site permissions manager | Per-origin geolocation decisions with reset, visible in site info. |
| No security interstitials | SSL, Safe Browsing and network error surfaces. |
| No clipboard pin UI | Pin / Unpin per item, plus "Delete unpinned items only". |
| Offline-first keyboard leaked prefixes | **Online word lookups are now opt-in and OFF by default**, with an explicit toggle in both keyboard settings surfaces. Nothing typed leaves the device unless enabled. |
| Dead startup permission list | Removed `STARTUP_PERMISSIONS`, `REQ_STARTUP_PERMISSIONS` and `requestStartupPermissions()`. Sensitive permissions remain requested at point of use. |
| No recent-history accessor | `HistoryActivity.recent()` added for omnibox autocomplete. |
| Only 6 unit tests | Added 14 tests across 3 new pure-Kotlin test classes (20 total). |
| Static audit was shallow | `tools/audit_project.py` now checks every manifest component resolves to a class, all `R.id` references in all Kotlin files, IME subtype script/ASCII validity, plain-HTTP endpoints, IME reflection, the dead compat file, and global cleartext. |

---

## 4. Aesthetic / structural displacement — improved

- **One chrome layer by default:** `PhormiBrowserPreferences.showBottomToolbar` now defaults to `false`. The duplicate bottom bar is still available as an opt-in setting.
- **Taller touch targets:** top toolbar 26 dp → **44 dp**, its controls 24 dp → **40 dp** tall, URL field **12 sp → 14 sp**, bottom bar (when enabled) 26 dp → **44 dp**.
- Removed the hidden 0 dp legacy shortcut DOM from `activity_start_page.xml` (all its IDs were unreferenced).
- **Daily rotating accent is now opt-in** (`false` default) instead of silently changing the browser's colours each day.
- Dark themes applied consistently to keyboard-related activities; theme naming unified; theme row no longer clipped.
- `AndroidManifest.xml`: removed unused `SYSTEM_ALERT_WINDOW`, `REQUEST_INSTALL_PACKAGES`, `MODIFY_AUDIO_SETTINGS`, `WAKE_LOCK`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` permissions (and the unused Bluetooth `uses-feature`); set `android:allowBackup="false"` (a browser stores history and clipboard); `usesCleartextTraffic="false"` with a new `res/xml/network_security_config.xml` that permits cleartext **only to `localhost`** for the optional disposable-email lab. `PhormiDisposableEmailStore` now defaults to `http://localhost:8080` so that exception matches. WebView does not honour `usesCleartextTraffic`, so HTTP sites remain loadable and are visibly flagged by the new site-info indicator (🔒 / ⚠), like Chrome.
- Registered the two missing activities and removed two never-started services.

---

## 5. Files changed

**New (8, including this changelog):** `PhormiKeyboardLocales.kt`, `PhormiNavigation.kt`, `PhormiSitePermissions.kt`, `res/xml/network_security_config.xml`, 3 unit-test files, and `docs/FIXES_APPLIED.md`.  
**Deleted (3):** `PhormiKeyboardRenderCompat.kt`, `CentralHubBridgeService.kt`, `PhormiVpnService.kt`.  
**Modified (23):** `AndroidManifest.xml`, `MainActivity.kt`, `MenuActivity.kt`, `BookmarksActivity.kt`, `HistoryActivity.kt`, `GhostActivity.kt`, `NewsActivity.kt`, `VpnActivity.kt`, `OpenVpnController.kt`, `PhormiBrowserPreferences.kt`, `PhormiDisposableEmailStore.kt`, `PhormiKeyboardServiceV2.kt`, `PhormiKeyboardTextEngine.kt`, `PhormiKeyboardDictionary.kt`, `PhormiKeyboardDictionaryApi.kt`, `PhormiKeyboardPreferences.kt`, `PhormiKeyboardClipboardStore.kt`, `PhormiKeyboardSettingsActivity.kt`, `res/layout/activity_main.xml`, `res/layout/activity_menu.xml`, `res/layout/activity_start_page.xml`, `res/xml/method_phormi_keyboard.xml`, `tools/audit_project.py`.

`git diff --stat`: 26 modified/deleted tracked files plus 8 new files.

---

## 6. Deliberately NOT changed — needs your decision or a device

These are real gaps from the audit, but I did not touch them because they are product decisions, require external credentials/services, or need runtime/visual validation that is impossible here. Tell me which to tackle next.

1. **Replacing the 11-provider HTML scraper with a licensed search API.** Needs a provider contract/API key and a product decision. The scraper remains, but is now honest, cheaper and less fragile.
2. **Replacing `android.webkit.WebView` with Chromium / a real Chrome stack.** Not feasible or desirable inside this app.
3. **Accounts, sync, Google Password Manager, passkeys, payment/address autofill, Safe Browsing levels, installed WebAPKs.** These require Google account/services integration. `PhormiSyncStore` and the shared sync contracts were left intact as planned work.
4. **Splitting `MainActivity.kt` (4,121 lines) into modules.** A large refactor with no compiler here is too risky. Same for unifying the 130 hardcoded `Color.rgb(...)` call sites into one theme source.
5. **Full Android IME framework restructure:** a separate `onCreateCandidatesView()` (currently the suggestion strip is inside the input view), composing text / `setComposingText`, hardware-keyboard handling, `requestCursorUpdates`/`CursorAnchorInfo`. These change IME window/inset geometry and cannot be validated without a device.
6. **Real non-Latin keyboard layouts, CJK/Indic composition, one-handed/split keyboard, emoji/GIF search, voice waveform UI.** Feature work, not bug fixes.
7. **Full 48 dp × 48 dp touch-target redesign and removing the second start-page search field.** I improved sizes and made one toolbar the default, but a complete chrome redesign needs screenshots to validate.
8. **Localisation.** `strings.xml` is still sparse and most UI text is hardcoded.
9. **Unreferenced helper classes kept intentionally** (they are inert but may be planned work): `PhormiSyncStore`, `PhormiQuickAccessRenderer`, `PhormiKeyboardDraftRescue`, `PhormiKeyboardStableInputConnection`, `PhormiKeyboardLexicon`, `PhormiKeyboardController`, `PhormiKeyboardCompat`. Also the two competing ghost paths and the duplicated Quick Access renderer were left in place rather than removed without runtime evidence.
10. **Instrumentation / UI tests.** Only local JVM unit tests were added; device tests need an emulator.

---

## 7. Reverting

Everything in this fix pass is in **one commit** on `arena/fabc59f4-phormi-android`, on top of a separate commit containing only the original audit report. The pre-fix state is `d2436ad`.

- Undo just the fixes, keeping the audit report: `git revert HEAD` (the fix commit is the current `HEAD`, titled `fix: address issues found in the Chrome/Gboard audit`; confirm with `git log --oneline -1`)
- Undo everything back to the original position: `git reset --hard d2436ad`
- Restore a single deleted file: `git checkout d2436ad -- app/src/main/java/com/uong/phormi/<file>.kt`

Or simply tell me what feels wrong — I can revert selectively (e.g. keep the security fixes but drop the glide typing, or restore the 41 subtypes) without undoing the rest.
