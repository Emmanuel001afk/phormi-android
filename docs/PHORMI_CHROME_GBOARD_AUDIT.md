# Phormi Browser vs Chrome Android · Phormi Keyboard vs Gboard

**Snapshot:** 2026-11-01 · branch `arena/fabc59f4-phormi-android` · commit `d2436ad`  
**This is the "before" snapshot.** Fixes applied on 2026-11-02 are documented in `docs/FIXES_APPLIED.md`.  
**Interpretation:** “Chrome” means Chrome for Android; “Google keyboard” means Gboard / Google Keyboard.  
**Method:** static reading of the checked-out source, layouts, resources, manifest, build files and tests. I also ran `python3 tools/audit_project.py`, which returned `PHORMI SOURCE AUDIT: PASS (25 components, 22 layouts, 191 resource IDs, 8 dictionaries)`. That script checks source/resource wiring; it does **not** prove runtime behavior. This workspace has no Gradle wrapper, `gradle`, `kotlinc`, Android SDK, emulator or device, so I did not compile or run the APK.

---

## 1. Executive verdict

Phormi is not a Chrome clone and Phormi Keyboard is not a Gboard clone.

- **Phormi Browser** is an Android `WebView` shell with a very large feature surface: tabs, split view, local history/favorites/bookmarks, downloads, desktop-mode UA switching, RSS news, search aggregation, AI browser automation, browser/site locks, a separate Ghost process, VPN integration, a disposable-email screen, object anchors and a local automation bridge. It is not a Chromium browser with Chrome's account, password, autofill, site-permission, sync and Safe Browsing product stack.
- **Phormi Keyboard** is a custom `InputMethodService` with programmatic `LinearLayout`/`Button` keyboard rendering, local prediction/correction, emoji/clipboard/media/tools/settings panels, voice input and a floating/resize overlay. It is not an IME with Gboard's mature language/layout model, gesture recognition, candidates-view integration, long-press accents, hardware-keyboard handling or system-wide polish.

The project is genuinely feature-rich, but the comparison exposes four recurring problems:

1. **Doesn't work / brittle:** advertised capabilities are unreachable, environment-dependent, or based on scraping/reflection/dead classes.
2. **Doesn't make sense:** browser, IME, VPN, AI, news, account shortcuts, sticker generation and automation are mixed into one app with overlapping concepts and misleading labels.
3. **Incomplete:** core Chrome/Gboard product layers are absent or represented by simplified local equivalents.
4. **Aesthetically and structurally displaced:** the UI is much denser, more duplicated, more hardcoded and more monolithic than either reference product.

---

## 2. Reference baseline used for the comparison

These are high-level product capabilities, not version-by-version parity claims.

- Chrome documents syncing of bookmarks, reading list, passwords, payment info, addresses, tabs and history [1](https://support.google.com/chrome/answer/165139?co=GENIE.Platform%3DAndroid&hl=en), a Google Password Manager/passkey surface [2](https://support.google.com/chrome/answer/95606/manage-passwords-android?co=GENIE.Platform%3DAndroid&hl=en-GB), payment/address management [4](https://support.google.com/chrome/answer/16580822?hl=en-ukanswer&co=GENIE.Platform%3DAndroid), per-site permissions with “allow this time / allow while visiting / never allow” [2](https://support.google.com/chrome/answer/114662?hl=en&co=GENIE.Platform%3DAndroid), Safe Browsing/protection levels [1](https://www.google.com/chrome/mobile/), and Incognito tabs that switch within the normal tab UI [2](https://support.google.com/chrome/answer/95464?co=GENIE.Platform%3DAndroid&hl=en).
- Gboard Help lists Glide typing, text correction, voice typing and per-language/layout selection [1](https://support.google.com/gboard/answer/6380730?hl=en&co=GENIE.Platform%3DAndroid), plus long-press accents, caps lock, space-bar cursor control, emoji/GIF and handwriting [2](https://support.google.com/gboard/answer/2842292?hl=en-GB&visit_id=637606872425395917-1069921008&rd=1). Its clipboard supports pinning and short-lived history [1](https://support.google.com/gboard/answer/10742542?hl=en). Gboard's commonly available one-handed and floating modes are also a useful ergonomic baseline [1](https://mobilesyrup.com/2020/03/08/one-handed-mode-iphone-pixel-samsung-lg-huawei/).
- Android's IME framework provides a dedicated candidates view and expects subtypes to represent locales, input modes or layouts [2](https://developer.android.com/guide/topics/text/creating-input-method); `InputMethodService` documents the soft-input/candidates-view lifecycle [1](https://developer.android.com/reference/kotlin/android/inputmethodservice/InputMethodService).
- Android accessibility guidance recommends at least 48 dp × 48 dp touch targets [4](https://developer.android.com/codelabs/starting-android-accessibility).
- Android WebView documentation warns that custom URL schemes are restricted and that `shouldOverrideUrlLoading` should not be used to load such schemes unconditionally [1](https://developer.android.com/develop/ui/views/layout/webapps/webview).

### High-level coverage matrix

| Area | Chrome Android / Gboard expectation | Phormi source reality | Practical consequence |
|---|---|---|---|
| Browser engine | Full Chrome browser stack | `android.webkit.WebView` instances (`MainActivity.kt:2980-3248`) | WebView can render pages, but Phormi does not own Chrome's account/security/feature stack. |
| Accounts, sync, passwords, payments | Sync, Password Manager, payment/address management | No first-party account/sync/password/payment implementation; state is in `SharedPreferences` JSON (`MainActivity.kt:2207-2248`) | No cross-device continuity or Chrome-like credential/autofill management. |
| Site permissions | Per-site, per-feature, one-time/remembered permissions | App-level camera/mic/geolocation requests; geolocation auto-grants if app permission exists (`MainActivity.kt:3347-3394`) | A site can inherit device-level permission without Chrome's per-origin UI. |
| Incognito | Incognito tabs inside normal tab switcher | Separate `GhostActivity` in `:ghost` process, isolated profile only when `MULTI_PROFILE` exists (`GhostActivity.kt:35-50`, `130-165`) | Private mode has a different UI and degrades to the default WebView profile on unsupported systems. |
| Omnibox | Larger address/search field with suggestions and site info | Plain 24 dp-high `EditText`, no `TextWatcher`/adapter, no security icon or page title (`activity_main.xml:51-64`) | No Chrome-style suggestions, site-information panel or visible security state. |
| IME architecture | Gboard uses mature IME lifecycle, layouts and candidates | No `onCreateCandidatesView`, no `onCurrentInputMethodSubtypeChanged`, no `onComputeInsets`, no hardware-key handling; suggestion strip is inside the input view (`PhormiKeyboardServiceV2.kt:370-450`) | Suggestions cannot be managed by the framework and the keyboard does not react properly to subtype/configuration changes. |
| Languages/layouts | Gboard lets users choose languages and layouts | 41 declared subtypes but one active Latin QWERTY letter layout (`method_phormi_keyboard.xml:5-45`; `PhormiKeyboardServiceV2.kt:453`, `531-554`) | Arabic, Hindi, Bengali, Urdu, Chinese, Japanese, Korean, Greek, Hebrew, etc. are labels, not usable native layouts. |
| Glide/long-press | Gboard glide typing and long-press accents | Code exists only in an unreachable extension (`PhormiKeyboardRenderCompat.kt:18-34`, `87-226`) | Neither gesture typing nor locale long-press accents are active. |
| Theming | System-integrated appearance/material behavior | 130 hardcoded `Color.rgb(...)` call sites, 8 keyboard themes, separate browser theme preferences | Light/dark/system choices apply inconsistently across screens. |

---

## 3. What genuinely works

To be fair, this is not an empty shell.

**Browser primitives present in source:** HTTP/HTTPS `ACTION_VIEW` handling, multiple WebView-backed tabs, back/forward, refresh/pull-to-refresh, `WebViewClient`/`WebChromeClient`, downloads and foreground download service, file chooser, fullscreen video, find-in-page via `findAllAsync`, desktop UA and metadata, print-to-PDF through Android PrintManager, local history/bookmarks/favorites, tab groups, a separate tab-overview activity, split view, renderer-crash recovery, SSL error cancellation, WebView Safe Browsing callback, and a default-browser role request. See `MainActivity.kt:555-720`, `1691-1707`, `2003-2083`, `2980-3426`, `3452-3559`, `MainActivity.kt:3823-3835`, `MainActivity.kt:3919-3968`.

**Keyboard primitives present in source:** text commit, backspace with repeat, shift/caps lock/auto-capitalization, number/symbol pages, email/URI convenience rows, space-bar cursor swipe, local dictionary completions, simple autocorrect, learned words and bigrams, emoji categories/recent emoji, clipboard history, voice recognition through Android `SpeechRecognizer`, `commitContent` for images/GIFs, eight hardcoded themes, wallpaper, haptics/sounds and a floating/resize overlay. See `PhormiKeyboardServiceV2.kt:370-450`, `452-639`, `640-796`, `840-995`.

The problem is not a total absence of features; it is that many features are **prototype-grade, unreachable, or placed in the wrong product layer**.

---

## 4. Things that do not work or are likely to fail

### 4.1 Highest-impact finding: the keyboard “compatibility layer” is dead code

`PhormiKeyboardRenderCompat.kt` defines a top-level extension named `render()`:

```kotlin
// PhormiKeyboardRenderCompat.kt:18
internal fun PhormiKeyboardServiceV2.render(): View { ... }
```

But the service itself has a **private member** with the same name:

```kotlin
// PhormiKeyboardServiceV2.kt:798
private fun render(): View { ... }
```

Every `render()` call inside the service resolves to the member. There is no call to the extension elsewhere. Therefore all of the following are inactive:

- `normalizeViewport()` and its 360 dp viewport logic (`PhormiKeyboardRenderCompat.kt:47-68`);
- AZERTY/QWERTZ locale layouts (`PhormiKeyboardRenderCompat.kt:87-119`);
- multilingual long-press accent dialogs (`PhormiKeyboardRenderCompat.kt:147-180`);
- the crude glide-typing gesture listener (`PhormiKeyboardRenderCompat.kt:186-226`);
- shift-state decoration (`PhormiKeyboardRenderCompat.kt:133-145`).

This also makes the settings screen inaccurate. `PhormiKeyboardSettingsActivity.kt:48-52` claims “built-in language layers” for ~40 languages and says “character long-presses follow the selected locale.” In the active service, letter keys have no long-press listener and the rows are always `qwertyuiop / asdfghjkl / zxcvbnm`. The dead extension additionally calls a nonexistent `refreshPredictions` method and uses reflection into private fields; even if wired, it would be brittle and would rebuild the whole IME on each letter.

**Compared with Gboard:** Gboard documents glide typing, text correction, voice typing and per-language layout selection [1](https://support.google.com/gboard/answer/6380730?hl=en&co=GENIE.Platform%3DAndroid) and long-press accents [2](https://support.google.com/gboard/answer/2842292?hl=en-GB&visit_id=637606872425395917-1069921008&rd=1). Phormi has the metadata and the dead code, not the behavior.

### 4.2 41 IME subtypes, one usable Latin layout

`method_phormi_keyboard.xml:5-45` declares 41 locale subtypes, including `en_NG`, Yoruba, Igbo, Hausa, Arabic, Hindi, Bengali, Urdu, Punjabi, Gujarati, Tamil, Telugu, Malayalam, Thai, Vietnamese, Chinese, Japanese, Korean, Russian, Greek and Hebrew. Only four are marked ASCII-capable. There are eight full bundled dictionaries (`app/src/main/assets/keyboard/words_*.txt`) and ~40 small ~50-word starter lexicons in `PhormiKeyboardLanguageData.kt:8-47`.

The active keyboard nevertheless renders a Latin QWERTY layout, with a few `@`, `.com`, `/` and `https://` convenience keys. There is no Arabic, Devanagari, Bengali, Gurmukhi, Gujarati, Tamil, Telugu, Malayalam, Thai, CJK, Cyrillic, Greek or Hebrew layout, no IME composition/candidate flow for those scripts, and no `onCurrentInputMethodSubtypeChanged` to switch layouts. Android's IME guidance explicitly describes subtypes as representing locales, input modes or layouts [2](https://developer.android.com/guide/topics/text/creating-input-method). Here they are mostly labels used to choose a word list.

**Likely user impact:** selecting “Hindi” or “Arabic” changes prediction data, not the keys. This is particularly visible for `en_NG`/Yoruba/Igbo/Hausa: the app advertises Nigerian locales but does not provide a Nigerian-language typing surface.

### 4.3 “Phormi Search” is a fragile HTML scraper, not a search engine API

`runUnifiedSearch()` fires 11 simultaneous HTTP requests to Google, Bing, DuckDuckGo, Brave, Yahoo, Ecosia, Mojeek, Startpage, Qwant, Yandex and Swisscows (`MainActivity.kt:1159-1249`). `fetchProviderResults()` sends a Chrome 126 user-agent and parses up to 2 MB of each result page with provider-specific regexes (`MainActivity.kt:1253-1390`). `mergeUnifiedResults()` then applies hardcoded brand weights such as Google=100, Bing=92, Brave=88 (`MainActivity.kt:1458-1531`). The result page is injected with `loadDataWithBaseURL("https://phormi.local/", ...)` and is fully reloaded after every provider finishes (`MainActivity.kt:1204-1247`, `MainActivity.kt:1533-1597`).

Things that can and commonly do go wrong with this design:

- anti-bot/CAPTCHA pages, consent walls, A/B markup or class-name changes silently produce zero results for one or more engines;
- 11 network requests per query increase latency, battery and data use;
- full-document reloads reset scroll position and the in-page `toggleSources()` state up to 11 times;
- the address bar shows the raw query, not a real URL;
- “cross-engine agreement” is not truly independent when several providers syndicate or mirror upstream indexes;
- the fake `phormi.local` origin has no meaningful site identity or security UI;
- refresh reruns all 11 fetches rather than reloading a cached document (`MainActivity.kt:1677-1685`).

The app does provide direct engine links as a fallback, but that is an admission that the aggregation layer can produce “No results could be collected.” Chrome's omnibox/search integration is a maintained product surface; this is a client-side scraping heuristic.

### 4.4 Ghost mode is not reliably private

`GhostActivity` creates a separate `:ghost` process and a random `PhormiGhost_*` WebView profile only when `WebViewFeature.MULTI_PROFILE` is supported (`GhostActivity.kt:35-50`, `130-165`). If that feature is unsupported, `ghostProfileName` remains null and the WebView uses the ordinary default profile. Cookies and DOM storage can therefore persist on those devices despite the “Ghost” name. `finishAndClear()` and `onDestroy()` clear cache/history and delete the profile when one exists, but this is not equivalent to Chrome's Incognito session model, which starts a separate browsing session and removes site data when all Incognito tabs close [1](https://support.google.com/chrome/answer/95464?hl=en-in&co=GENIE.Platform%3DAndroid).

There are also two competing ghost implementations in `MainActivity`: `createGhostTab()` launches `GhostActivity`, while `createNewTab(forceGhost = true)` tries to use the shared `GHOST_PROFILE_NAME` inside the normal activity (`MainActivity.kt:2003-2018`, `2084-2089`). That is conceptually confusing.

**Aesthetic displacement:** Ghost mode has its own 56 dp toolbar plus 44 dp tab strip with plain text glyph buttons, rather than Chrome's same-browser Incognito color/icon/switcher treatment (`GhostActivity.kt:67-125`).

### 4.5 Named tab environments silently degrade

`isMultiProfileSupported()` gates `ProfileStore`/`WebViewCompat.setProfile` (`MainActivity.kt:2249-2301`; `PhormiEnvironmentManager.kt:12-40`). On WebViews without `MULTI_PROFILE`, `createNewTab()` simply ignores the requested profile and uses `Default`. Existing code shows a toast: “Named environments are unavailable on this WebView.” Chrome's signed-in profiles/accounts are a first-class product concept; here “Work”, “Personal”, etc. are just WebView profile names, with no account, password, bookmarks or settings isolation.

`restoreTabs()` also catches every exception and falls back to a single blank tab (`MainActivity.kt:2303-2355`), with no diagnostic or recovery UI. WebView state is serialized via `Parcel` + Base64 and limited to 512 KB (`MainActivity.kt:2356-2387`); if state capture fails, the app restores only the URL.

### 4.6 Dead components, dead helpers and dead permissions

Static references show a substantial amount of code that is declared but unreachable:

- `NewsActivity` and `PhormiKeyboardStickerActivity` are implemented but **not registered** in `AndroidManifest.xml`. Their layouts/`item_news.xml` exist, but `NewsActivity.kt:20-24` still says it avoids a “missing item_news.xml resource.”
- `CentralHubBridgeService` is declared (`AndroidManifest.xml:67`) and implements a localhost HTTP server, but no code starts or binds it. Its `bindActivity()` is never called.
- `PhormiVpnService` is declared (`:69`) and has `ACTION_CONNECT`/`ACTION_STATUS`, but `VpnActivity` never starts it; `PhormiVpnService.isRunning` therefore remains false.
- `PhormiSyncStore` implements the shared sync contract (`shared/phormi_sync_contract.json`) but is never instantiated.
- `PhormiQuickAccessRenderer` is a second quick-access renderer with different grid geometry, but `MainActivity.loadQuickAccessRows()` uses its own renderer.
- Keyboard helpers `PhormiKeyboardDraftRescue`, `PhormiKeyboardStableInputConnection`, `PhormiKeyboardLexicon`, `PhormiKeyboardController` and `PhormiKeyboardCompat` are unreferenced.
- `requestStartupPermissions()` exists but is never called (`MainActivity.kt:2894`). Consequently `POST_NOTIFICATIONS`, `READ_MEDIA_*` and `BLUETOOTH_*` appear only in a dead startup list (`MainActivity.kt:175-195`). `SYSTEM_ALERT_WINDOW` and `REQUEST_INSTALL_PACKAGES` are declared but never used.

`AndroidManifest.xml:3-24` declares 22 `uses-permission` entries — camera, microphone, location, Bluetooth, media, overlay, package-install, foreground-service and related capabilities — and the manifest separately declares an accessibility service and a VPN service (`:67-69`). That is a very large trust surface for a WebView browser + IME, especially when several permissions have no active request path. `usesCleartextTraffic="true"` and `requestLegacyExternalStorage="true"` are also enabled at `:35`; the VPN screen fetches one source over plain HTTP (`VpnActivity.kt:273`).

### 4.7 VPN feature has an external-app mismatch

`VpnActivity` declares three possible OpenVPN packages (`VpnActivity.kt:59-63`), but `OpenVpnController` hardcodes only `de.blinkt.openvpn` (`OpenVpnController.kt:17`, `OpenVpnController.kt:104-108`). Users with the other two clients get “OpenVPN for Android is not installed.” The app downloads public/free OpenVPN profiles from VPN Gate and a third-party GitHub CSV (`VpnActivity.kt:265-331`), then delegates the actual tunnel to an external app. This is not a self-contained browser VPN, and it sits inside a browser menu alongside bookmarks and history.

### 4.8 The “Go” button is effectively invisible

`activity_main.xml:67-72` defines:

```xml
android:id="@+id/btn_go"
android:layout_width="0dp"
android:layout_height="24dp"
android:text=""
```

There is no `layout_weight` and no later code that assigns text/width; `MainActivity.kt:676` wires a click listener and `:908` only changes its text color. It is a zero-width control. The IME `actionGo` and the separate 36 dp start-page Go button cover the real navigation path, but the toolbar control itself is dead.

### 4.9 Permission and error handling is below Chrome's UX and, in one case, over-grants

- `onPermissionRequest()` computes a filtered `allowed` array of audio/video resources, but after the Android permission result `onRequestPermissionsResult()` calls `request.grant(request.resources)` — the **entire** requested resource array, not `allowed` (`MainActivity.kt:3347-3370`, `MainActivity.kt:3427-3444`). If a request mixes audio/video with another WebView resource, the extra resource can be granted inadvertently.
- `onGeolocationPermissionsShowPrompt()` grants location automatically when the app-level `ACCESS_FINE_LOCATION` permission is already present, with no per-origin allow/deny/one-time UI (`MainActivity.kt:3372-3394`). Chrome documents per-site and one-time permission choices [2](https://support.google.com/chrome/answer/114662?hl=en&co=GENIE.Platform%3DAndroid).
- `shouldOverrideUrlLoading()` returns `false` for `javascript`, `data`, `blob` and `file` schemes while separately setting `setAllowFileAccess(false)` (`MainActivity.kt:3032-3052`). Android's WebView docs warn that custom/non-HTTP schemes are restricted and should not be loaded unconditionally [1](https://developer.android.com/develop/ui/views/layout/webapps/webview).
- SSL errors produce `handler.cancel()` plus a toast; HTTP/loading errors produce toasts; Safe Browsing hits call `backToSafety(true)` plus a toast (`MainActivity.kt:3132-3162`). There are no full-page interstitials, “learn more,” proceed controls or saved site exceptions.
- `buildChromeClient()` has no `onProgressChanged`, and the layout has no page progress bar.

### 4.10 Keyboard rendering and prediction refresh are fragile

The service calls `setInputView(render())` at 39 sites. Page changes, emoji categories, clipboard edits, settings toggles, panel switches and some locale/helper code all rebuild and replace the entire input view. `onCreateInputView()` already returns `render()`, then `onStartInputView()` calls `setInputView(render())` again (`PhormiKeyboardServiceV2.kt:128`, `170`). This is expensive, can flicker, and discards transient view state. Shift is specially patched with `updateShiftKeyAppearance()` to avoid a full rebuild, which confirms the problem was known.

Remote dictionary results are refreshed by reflecting into the private `Companion.instance` and looking up `refreshPredictionStrip()` by name (`PhormiKeyboardDictionaryApi.kt:134-145`). Release minification is currently disabled (`app/build.gradle.kts:18-21`), so this may work today, but enabling R8 would silently break it. `loadDiskInner()` is a stub that always returns `emptyList()` (`PhormiKeyboardDictionaryApi.kt:116-121`). The working `loadDisk(context, ...)` is called separately by `PhormiKeyboardTextEngine`, so the disk cache is not wholly lost, but one prediction path is still a no-op.

---

## 5. Things that do not make sense

1. **Product scope.** One APK declares 26 activities and 5 services: browser, IME, downloads, VPN, AI automation, accessibility automation, disposable email, capsules, news, object anchors, sticker/emoji generation, Central Hub bridge and file viewers (`AndroidManifest.xml:36-74`). Chrome separates browser, account and site services; Gboard is an IME. Phormi mixes them into one full-screen menu with 46 `menu_*` controls. This makes every feature compete for the same navigation hierarchy and permission set.

2. **“Accounts & sign-in” is not account/sign-in.** `MenuActivity.kt:106` maps `menu_settings` to `AccountsActivity`, which is a list of provider shortcuts/custom tabs, not a Chrome account, password manager or sync identity. `MainActivity.kt:3546` maps `ACTION_SETTINGS` to `showAppearanceChooser()`, while `MenuActivity` never sends that action. Names and behavior diverge.

3. **Bookmarks, Favorites and Quick Access overlap.** `BookmarksActivity.getAll()` and `FavoritesActivity.render()` both read `PhormiFavorites`. `BookmarksActivity.calledFromQuickAccessRenderer()` inspects `Throwable().stackTrace` to decide whether “remove” means delete a favorite or merely hide a Quick Access tile (`BookmarksActivity.kt:30-56`). Behavior should be chosen by an explicit parameter, not by the caller's stack trace. A hidden legacy shortcut strip also remains in `activity_start_page.xml:46-57` with `0dp` height and `visibility="gone"`.

4. **Two search/address fields and one dead Go button.** On the start page there is a 54 dp search/address box with its own engine selector and Go button, while the top 26 dp omnibox is hidden. Chrome normally uses one omnibox/new-tab search surface. Phormi also hides `btn_go` in the top bar by giving it zero width. The same navigation heuristic is duplicated in `navigateFromStartPage()` and `navigateFromUrlBar()` (`MainActivity.kt:1603-1617`, `1769-1798`).

5. **“Independent indexes” are consumer HTML pages.** The generated search page says “Phormi queries all available independent search indexes together” (`MainActivity.kt:1581-1588`), but the implementation fetches HTML search-result pages with a spoofed Chrome UA and regexes. It is an aggregator of presentation markup, not an index or API client. Ranking by hardcoded brand weights further weakens the “independent evidence” claim.

6. **VPN, free public server lists and browser identity.** A browser menu item launches a screen that downloads free OpenVPN profiles from VPN Gate and a third-party GitHub CSV, then requires a specific external app. This is a different trust and product model from Chrome's browsing/security services. It also exposes plain HTTP traffic because `usesCleartextTraffic` is globally enabled.

7. **“Offline-first” keyboard still sends typed prefixes to third parties.** `PhormiKeyboardTextEngine.suggestions()` calls `PhormiKeyboardDictionaryApi.request()` for eligible English prefixes of length ≥ 2 during suggestion refresh (`PhormiKeyboardTextEngine.kt:110-123`; `PhormiKeyboardDictionaryApi.kt:61-101`). That sends the current word prefix to `api.datamuse.com` or `api.dictionaryapi.dev`. There is no separate cloud-suggestions consent screen; the only related switch is the general Suggestions toggle. The AI-sticker path can also send sanitized typed context to Pollinations when an API key is configured (`PhormiPollinationsAiEmojiEngine.kt:29-76`).

8. **Clipboard privacy semantics are inconsistent with the reference.** `PhormiKeyboardClipboardStore` auto-captures the system clipboard while the IME is active, stores up to 50 items in plaintext `SharedPreferences` for 24 hours, and filters “sensitive” values with a handful of regexes (`PhormiKeyboardClipboardStore.kt:10-110`). The store has a `pinned` field but the clipboard UI exposes only delete/clear, not pin. Gboard documents pinning and short-lived clipboard history [1](https://support.google.com/gboard/answer/10742542?hl=en).

9. **Non-Latin “language layers” with Latin keys.** The settings text lists Arabic, Hindi, Bengali, Urdu, Chinese, Japanese, Korean, Greek and Hebrew as built-in layers, but there is no active layout/composition path. A word list of 50 common words is not a keyboard layout.

10. **Theme naming and ownership are inconsistent.** The in-keyboard settings call theme 6 “Berry” (`PhormiKeyboardServiceV2.kt:796`), while the full settings screen calls it “Rose” (`PhormiKeyboardSettingsActivity.kt:68`). The browser has System/Light/Dark plus a daily rotating accent (`MainActivity.kt:766-912`), while the keyboard has its own eight themes and several keyboard activities use `@android:style/Theme.Material.Light.NoActionBar` in the manifest despite hardcoded dark backgrounds. There is no single visual system.

---

## 6. Incomplete areas

### 6.1 Browser vs Chrome Android

| Missing / simplified | Evidence | Chrome reference |
|---|---|---|
| Account sync of bookmarks, passwords, payment info, addresses, tabs/history | `MainActivity.kt:2207-2248`; `PhormiSyncStore.kt` unused; `shared/phormi_sync_contract.json` is only a contract | Chrome documents sync of these categories [1](https://support.google.com/chrome/answer/165139?co=GENIE.Platform%3DAndroid&hl=en). |
| Google Password Manager / passkeys / payment management | No first-party implementation found | Chrome Help documents password manager/passkeys [2](https://support.google.com/chrome/answer/95606/manage-passwords-android?co=GENIE.Platform%3DAndroid&hl=en-GB) and payment/address management [4](https://support.google.com/chrome/answer/16580822?hl=en-ukanswer&co=GENIE.Platform%3DAndroid). |
| Per-site permission manager | Only global JavaScript/third-party-cookie/mixed-content switches in `PhormiSecurityCenterActivity.kt:29-60`; site locks are navigation locks, not permission storage | Chrome provides per-site/one-time permissions [2](https://support.google.com/chrome/answer/114662?hl=en&co=GENIE.Platform%3DAndroid). |
| Real omnibox | Plain `EditText`; no `TextWatcher`, adapter, autocomplete, site-info icon, page title or progress bar | Chrome's site-information/permission flow starts from a site-info control [2](https://support.google.com/chrome/answer/114662?hl=en&co=GENIE.Platform%3DAndroid); Safe Browsing is a product-level protection [1](https://www.google.com/chrome/mobile/). |
| Incognito switching in tab UI | Separate `GhostActivity` only | Chrome supports switching regular/Incognito tabs within the tab UI [2](https://support.google.com/chrome/answer/95464?co=GENIE.Platform%3DAndroid&hl=en). |
| Full security/error interstitials | Toast-only SSL/HTTP/Safe Browsing handling (`MainActivity.kt:3069-3162`) | Chrome's Safe Browsing levels and warning surfaces are documented [1](https://www.google.com/chrome/mobile/). |
| Installed web apps | `addCurrentSiteToHomeScreen()` explicitly creates a pinned shortcut and says it is “deliberately not presented as a WebAPK” (`MainActivity.kt:3919-3968`) | Chrome documents installing web apps as launcher/home-screen apps with offline storage, notifications and other app features [1](https://support.google.com/chrome/answer/9658361?co=GENIE.Platform%3DAndroid&hl=en&oco=1). |
| Find-in-page polish | A dialog with one query and `findAllAsync`, no next/previous/count/close-on-scroll (`MainActivity.kt:3823-3835`) | Chrome's find bar provides match navigation/count. |
| Desktop site parity | Changes UA, viewport metadata and injects `width=980` (`MainActivity.kt:3860-3918`, `MainActivity.kt:3205-3230`) | Many modern sites use responsive/client-hints logic; UA spoofing alone is incomplete. |
| Landscape/fullscreen behavior | `onShowCustomView()` forces `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` for every custom view (`MainActivity.kt:3305-3336`) | Fullscreen web content is not always video; forced rotation is disruptive. |
| Localization | `res/values/strings.xml` has 26 strings; most UI text is hardcoded Kotlin/XML literals, and there are no locale-qualified resource sets | Chrome/Gboard are localized products. |

Additional browser gaps visible from source: no omnibox suggestions from history/bookmarks, no visible tab titles on phones, no progress indicator, no first-party translate/reader/offline-page layer, no direct “Save as PDF” file output (it opens Android's print UI), no Chrome-style site-data viewer, no password-breach checks [3](https://support.google.com/chrome/answer/95606?hl=en-PS&co=GENIE.Platform%3DAndroid&visit_id=639280665538635264-614654034&rd=1), no enhanced Safe Browsing controls [1](https://www.google.com/chrome/mobile/), no profile/account switcher, no proper installed-web-app flow [1](https://support.google.com/chrome/answer/9658361?co=GENIE.Platform%3DAndroid&hl=en&oco=1), and no integration of the cross-platform sync contract. The Windows prototype is a separate 4-file WebView2 project under `desktop/windows/` and is not part of the Android build.

### 6.2 Keyboard vs Gboard

| Missing / simplified | Evidence | Gboard/Android reference |
|---|---|---|
| Real per-language layouts | One QWERTY letter layout; dead AZERTY/QWERTZ extension | Gboard lets users choose languages and layouts [1](https://support.google.com/gboard/answer/6380730?hl=en&co=GENIE.Platform%3DAndroid). Android subtypes represent locales/modes/layouts [2](https://developer.android.com/guide/topics/text/creating-input-method). |
| Glide typing | Dead `installGlideCompat`, which would just concatenate letters under the finger | Gboard documents slide-to-type [3](https://support.google.com/gboard/answer/2811346?hl=en&co=GENIE.Platform%3DAndroid). |
| Long-press accents / punctuation popups | No active `OnLongClickListener` on letter keys; dead `installMultilingualLongPress` | Gboard documents “touch and hold a key” for accents/more options [2](https://support.google.com/gboard/answer/2842292?hl=en-GB&visit_id=637606872425395917-1069921008&rd=1). |
| Candidates view | No `onCreateCandidatesView`; strip is inside the input view and always reserves 30 dp | Android IME framework provides `onCreateCandidatesView()` for suggestions [1](https://developer.android.com/reference/kotlin/android/inputmethodservice/InputMethodService). |
| Hardware keyboard / extracted text / cursor anchors | No `onKeyDown`, `onStartCandidatesView`, `onComputeInsets`, `requestCursorUpdates`, or `CursorAnchorInfo` usage | `InputMethodService` exposes these lifecycle/inset/candidate surfaces [1](https://developer.android.com/reference/kotlin/android/inputmethodservice/InputMethodService). |
| Voice typing UI | `SpeechRecognizer` with no waveform, partial-result display, language picker, pause/resume or error UI (`PhormiKeyboardServiceV2.kt:846-899`) | Gboard documents a voice toolbar and voice-input features [2](https://support.google.com/gboard/answer/2781851?hl=en-GB&co=GENIE.Platform%3DAndroid). |
| Emoji/GIF search, skin tones, sticker catalog | Static emoji categories + local file imports; no search field (`PhormiKeyboardServiceV2.kt:640-683`, `PhormiKeyboardEmoji.kt:134-158`) | Gboard exposes emoji/GIF and handwriting features [2](https://support.google.com/gboard/answer/2842292?hl=en-GB&visit_id=637606872425395917-1069921008&rd=1). |
| Clipboard pin/expiry UI | Store supports pinning for 24 h, but panel only deletes/clears | Gboard supports pinning and short-lived history [1](https://support.google.com/gboard/answer/10742542?hl=en). |
| One-handed / split keyboard | Floating/resize overlay only; no left/right one-handed mode, no split layout for large screens | Gboard's one-handed and floating modes are a common ergonomic baseline [1](https://mobilesyrup.com/2020/03/08/one-handed-mode-iphone-pixel-samsung-lg-huawei/). |
| Compose/IME correctness | Suggestions use `deleteSurroundingText`/`commitText`; no `setComposingText`, composing region, or `finishComposingText` except clearing it | Modern IMEs use composition for transliteration, CJK, accents and autocorrect. |
| Dynamic color / system theme | Eight hardcoded RGB themes; browser theme is separate; settings activity uses a platform light theme with dark UI | Gboard follows system/material theming. |
| Predictions quality | Prefix lookup, one-edit correction, ~50-word lexicons for most languages, learned bigrams, optional system spell checker and remote Datamuse results (`PhormiKeyboardTextEngine.kt:1-190`; `PhormiKeyboardDictionary.kt:1-80`) | Gboard's text correction is a mature language-model feature [1](https://support.google.com/gboard/answer/6380730?hl=en&co=GENIE.Platform%3DAndroid). |
| Test coverage | 3 unit-test files / 6 tests: AI safety, dictionary correction, AI emoji context. No IME lifecycle, layout, permissions, search, tabs or UI tests (`app/src/test/java/com/uong/phormi/`) | A product claiming 41 locales needs locale/layout/IME instrumentation tests. |

---

## 7. Aesthetic and structural displacement

### 7.1 Browser chrome anatomy

```
Chrome-like expectation
┌───────────────────────────────────────────┐
│  one larger omnibox: site info · URL · tabs · menu │  ~48–56 dp
├───────────────────────────────────────────┤
│                                               │
│                  web content                  │
│                                               │
└───────────────────────────────────────────┘

Phormi main screen
┌───────────────────────────────────────────┐
│ 26 dp: home · URL(24 dp) · tab strip · refresh · favorite · count · + │
│ hidden: back forward downloads AI menu back forward                 │
├───────────────────────────────────────────┤
│                 web content                  │
├───────────────────────────────────────────┤
│ 26 dp: back · forward · AI · downloads       │
└───────────────────────────────────────────┘
```

Evidence: `activity_main.xml:11-214` and `activity_main.xml:293-364`; visibility rules in `MainActivity.kt:1638-1656`.

Problems:

- Two persistent 26 dp toolbars consume vertical space and duplicate controls.
- Most icon/text controls are 24 dp × 24 dp with 10–24 sp labels. Android accessibility guidance recommends at least 48 dp × 48 dp [4](https://developer.android.com/codelabs/starting-android-accessibility).
- The address field is a 12 sp `EditText` with no favicon, lock/site-info icon, page title, progress bar or suggestion dropdown.
- Controls are Unicode glyphs (`‹ › ↻ + ⋮ ◈ ✦ ↓`) rather than consistent vector icons; glyph rendering varies by font/device.
- The tab strip is only shown on screens ≥600 dp (`MainActivity.kt:1619-1624`), so phones have just a numeric tab count.
- Hidden top back/forward/AI/downloads buttons exist as a responsive fallback to the bottom toolbar, creating three overlapping control maps.

### 7.2 New-tab/start page

`activity_start_page.xml` stacks, in one scrolling surface:

- a 30 dp logo + title/subtitle;
- a second 54 dp search/address box with engine selector and Go button;
- four 46 dp quick actions;
- a 32 dp “Quick access / News · On / AI” row;
- dynamic 56–60 dp quick-access rows with 8.5 sp labels;
- a horizontal news row;
- a 0 dp hidden legacy shortcut strip;
- 10 sp explanatory text.

Chrome's New Tab Page normally presents one search/address field, shortcuts, and optional content discovery. Phormi mixes navigation, search, shortcuts, news, AI, legacy compatibility DOM and engine selection into one screen. The top omnibox is hidden on this page, so the same task has two different visual grammars.

Quick-access favicons are fetched per tile on raw `Thread`s, including Google's `s2/favicons` service (`MainActivity.kt:1089-1115`), which leaks shortcut hosts to a third party and has no persistent cache.

### 7.3 Menu structure

`activity_menu.xml` is a full-screen translucent three-page menu: Browse / Features / Settings, 46 `menu_*` IDs, dozens of 48 dp text rows, a fixed “Close menu” footer and hardcoded section labels/icons. Chrome Android uses an anchored overflow menu and a hierarchical Settings screen. Phormi's menu duplicates back/forward/refresh/new tab/tabs/downloads/bookmarks/history/AI and then adds VPN, keyboard, disposable email, capsule, default browser, site lock, object anchors, navigation lens and same-page split. The result reads like an app launcher rather than browser chrome.

### 7.4 Keyboard anatomy

```
Phormi active letter view, fixed 312 dp × heightScale
┌───────────────────────────────────────────┐
│ 30 dp suggestion strip                      │
│ 36 dp horizontally scrolling toolbar: 😀 📋 ▦ Cabinet 🌐 │
│ 3 × 44 dp QWERTY rows                      │
│ 44 dp bottom row: ?123 · Symbols · ⇧ · , · Space · . · ⌫ · action │
└───────────────────────────────────────────┘
```

Evidence: `PhormiKeyboardServiceV2.kt:177-253`, `PhormiKeyboardServiceV2.kt:339-368`, `PhormiKeyboardServiceV2.kt:370-450`, `PhormiKeyboardServiceV2.kt:531-639`.

Displacements:

- Android provides a candidates view above the input view; Phormi embeds predictions inside the input view and keeps a 30 dp strip even when it says “Suggestions are off for this field” (`PhormiKeyboardServiceV2.kt:370-450`).
- The toolbar is a 34–36 dp horizontal pill row; important features are hidden behind “Cabinet,” which replaces the entire keyboard with a scrolling settings/media/tools page. Gboard generally keeps typing available while exposing a feature menu.
- Keys are static `Button`s with `GradientDrawable` backgrounds, `stateListAnimator=null`, no pressed/popup preview, and manual haptic/sound callbacks (`PhormiKeyboardServiceV2.kt:267-300`).
- The base window height is hardcoded to 312 dp and scaled by a preference; there is no `onComputeInsets`, edge-to-edge navigation-bar handling, split layout for tablets/foldables, or one-handed mode (`PhormiKeyboardServiceV2.kt:177-229`).
- The floating/resize state draws a blue frame and circular handles over the whole keyboard (`PhormiKeyboardResizeOverlay.kt:47-180`). It is functional-looking but visually unlike Gboard's one-handed/floating treatment, and it covers the typing surface while resizing.
- Eight theme buttons are squeezed into one horizontal row in the settings activity (`PhormiKeyboardSettingsActivity.kt:68-71`), while the in-keyboard settings cycle themes one at a time (`PhormiKeyboardServiceV2.kt:787-795`).
- Theme 6 is “Rose” in one settings screen and “Berry” in the other. Browser theme, keyboard theme, keyboard settings activity theme and hardcoded activity backgrounds are independent systems.

### 7.5 Architecture

`MainActivity.kt` is 4,121 lines and mixes WebView configuration, tab persistence, search scraping, news RSS, AI automation, split view, downloads, permissions, browser lock, appearance, desktop mode, home-screen shortcuts and a Central Hub command API. `PhormiKeyboardServiceV2.kt` is 997 lines and mixes rendering, input connection, predictions, voice, clipboard, emoji, media, settings, resize and external bridges. Some methods are compressed into single very long lines, making review difficult.

There are 18 `tools/repair_round*.py` scripts, multiple unused compatibility/renderer classes, two quick-access renderers, two ghost paths, two settings surfaces for the keyboard, and a separate four-file Windows prototype. That is a patch-accumulation architecture, not a modular browser/IME architecture. It explains why dead code, misleading labels and duplicate UI controls coexist.

---

## 8. Confidence and verification limits

**High confidence — directly observable from source:**

- dead `PhormiKeyboardRenderCompat` extension;
- 41 subtypes vs one Latin layout and eight dictionaries;
- unregistered activities and unstarted services;
- zero-width `btn_go`;
- 22 manifest permissions and dead startup permission request;
- duplicated toolbars, 24 dp controls, hardcoded colors/themes;
- no candidates view, no subtype-change handler, no hardware-key handling;
- HTML scraping search implementation;
- `usesCleartextTraffic=true`;
- only 3 unit-test files / 6 unit tests.

**Medium confidence — source strongly implies runtime behavior but needs device validation:**

- exact search-engine failures caused by anti-bot pages/markup changes;
- Ghost mode privacy degradation on a specific WebView build;
- keyboard flicker/jank from 39 `setInputView(render())` call sites;
- notification/media-permission failures on Android 13+;
- visual clipping of the 8-theme row on narrow phones;
- external OpenVPN package mismatch on devices with only one of the other clients.

**Low / not verified:**

- compile result, APK behavior, frame rate, memory use, crash rate, touch accuracy and exact rendered pixels. No Android toolchain or device was available here.
- Chrome/Gboard version-specific feature parity. The public references above describe high-level capabilities only.

---

## 9. If you want to close the gap, fix in this order

No source changes were made as part of this audit; these are recommendations.

1. **Wire or delete `PhormiKeyboardRenderCompat`.** If you keep it, rename the extension, replace reflection with internal APIs, add real locale layouts, long-press accents and a proper gesture recognizer; then correct the settings text and add locale tests.
2. **Reduce the IME subtype list to what is actually supported**, or implement per-script layouts/composition. Do not advertise 41 locales with one QWERTY layout.
3. **Replace unified HTML scraping** with one licensed search API or a single normal search-provider page. Preserve direct-engine links as an explicit fallback.
4. **Create one modular browser architecture.** Split `MainActivity` into tab/session, WebView configuration, search, permissions, downloads, AI and appearance owners. Remove dead activities/services/helpers and unused permissions.
5. **Rebuild browser chrome around one larger omnibox** with ≥48 dp touch targets, site info, suggestions, progress, tab switcher and anchored menu. Remove the second start-page field or make it visually the same omnibox.
6. **Implement a real permission model:** per-origin camera/mic/location, one-time grants, and full-page SSL/Safe Browsing/error interstitials.
7. **Use the Android IME framework properly:** candidates view, subtype-change handling, insets/config changes, hardware keyboard, composing text, and leave typing visible while tools/settings are open.
8. **Unify visual language:** one theme source, resource strings, vector icons, no daily accent surprise unless clearly opt-in, and no light platform themes on dark hardcoded screens.
9. **Add instrumentation/UI tests** for IME subtype switching, keyboard rendering, WebView navigation, search failure, permissions and Ghost-mode isolation. Six pure unit tests cannot cover a 41-locale IME or a 4,121-line browser activity.

If you provide screenshots, an APK or a device trace, the aesthetic conclusions above can be refined from code-inferred dimensions to pixel-level observations.
