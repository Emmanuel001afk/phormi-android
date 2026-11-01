# Phormi browser and keyboard audit

Date: 2026-11-01 · Baseline: `d2436ad` · Scope: Android application

## Scope and verdict

This is a source-level comparison with the established Android Chrome and Gboard user experience, not a benchmark against a particular installed version. Chrome/Gboard features vary by device, language and release. No screenshots or device recordings were supplied. This workspace has no available Android device tooling, Gradle executable or Gradle wrapper, so the app was not built or run. Visual findings below are derived from actual layout definitions and code; runtime symptoms explicitly marked as risks need device confirmation.

**Verdict:** Phormi has substantial functionality, but core interaction reliability trails its feature breadth. The browser's biggest gaps are site-permission safety, usable navigation/tab geometry, consistent settings and private-mode behavior. The keyboard's biggest gaps are field-specific editing, language/glide integration, text correctness and a stable, familiar layout. More feature buttons will not close those gaps.

Evidence paths below are relative to `app/src/main/`; line numbers refer to the audited baseline. **Confirmed** means the defect or missing implementation is identifiable in source, not that an Android reproduction was performed. **Risk** means the runtime outcome needs verification. **Design** means a usability judgment rather than a functional failure.

## Browser versus Chrome

### B1 — Site permissions bypass origin-level consent
**Critical · Confirmed**

`java/com/uong/phormi/MainActivity.kt:3347–3392,3427–3447`

Camera/microphone requests are granted automatically if Android has already granted the app permission. Location is likewise granted without a site-origin confirmation. Android app permission and permission for an individual website are different trust boundaries. Chrome's site-permission model is the relevant baseline: granting the browser microphone access must not mean approving every requesting website.

The Android permission-result path also grants `request.resources`, rather than the previously filtered audio/video set. There is no corresponding cancellation handler visible for these pending web requests.

**Fix direction:** explicit origin-labelled approval; per-origin decisions and revocation; deny cancelled/stale requests; only grant the intersection of requested, supported and user-approved resources. Test across two different origins and after navigation.

### B2 — Security toggles do not update already-open tabs
**High · Confirmed**

`PhormiSecurityCenterActivity.kt:41–50`; `MainActivity.kt:230–246,2016–2019,2987–3005`

JavaScript and mixed-content settings are read when a WebView is configured; third-party-cookie settings when a tab is created. Returning from settings updates text zoom, but not those security settings. Reloading an existing WebView does not rerun its configuration, despite the JavaScript UI telling users to reload.

**Effect:** the interface says a restriction is saved while existing tabs can retain the old behavior. Apply settings to live tabs immediately and document reload requirements separately.

### B3 — Main navigation is miniature rather than compact
**High · Design; dimensions confirmed**

`res/layout/activity_main.xml:13–249`; `MainActivity.kt:1638–1654`

The top bar is 26dp high; most controls are 24×24dp; the URL text is 12sp in a 24dp field. These are substantially below a conventional 48dp touch-target goal. Hiding the bottom toolbar adds back, forward and AI controls to this already cramped row. A 150dp strip appears on wide screens regardless of actual remaining address-bar space.

**Compared with Chrome:** saving height is useful, but not at the cost of identifying the URL and reliably hitting tabs/menu. No enlarged touch-delegate implementation was found.

**Fix direction:** one readable address row with a few priority controls; secondary actions in overflow. A distinctive Phormi appearance does not require unusually small touch targets.

### B4 — Tab overview has real geometry errors
**High · Confirmed**

`TabsOverviewActivity.kt:70–110,128–151`; `res/layout/activity_tabs_overview.xml`; `res/layout/item_tab_circle.xml`

- Vertical row height is **154 raw pixels**, while its child body is **142dp**. Grid rows are **160 raw pixels** with **148dp** bodies. At density 3, a 51dp-high row contains a 142dp-high body. Clipping/displacement is a predictable consequence; exact rendering needs a device.
- Header controls, margins and padding total **347dp**, excluding the weighted title. At 360dp, only 13dp remains for “Tabs”; at 320dp, fixed content already overflows by 27dp.
- “Horizontal” mode puts cards into a vertical stack inside a horizontal-only scrolling container. Later cards can extend vertically without a corresponding vertical scroll path.
- The intended per-card Group button is added only if `body is FrameLayout`, but XML defines `body` as a `LinearLayout`. That button is never attached. Long-press grouping remains available.
- A 92dp right padding is reserved even though that Group button is absent, wasting scarce title/URL space.

**Fix direction:** a responsive, recycled list/grid using dp consistently; move mode/split/group management out of the title row; keep per-card controls in an intentional layout.

### B5 — Address bar is an input box, not a complete omnibox
**Medium · Confirmed gap / Design**

`MainActivity.kt:1769–1797,3120–3138,3225–3238`; `res/layout/activity_main.xml`

No address-bar history/bookmark suggestion pipeline or origin/site-information control was found. URL detection is a string heuristic: “contains a dot and no spaces” means URL. Inputs such as `localhost:8080` are treated as searches; arbitrary dotted strings can become URLs. The visible address normally updates at page completion rather than navigation start, so redirects can leave stale address text during loading.

No WebChromeClient page-progress callback/stop-loading UI was found. TLS failures are cancelled, which is good, but explained through a transient toast rather than a durable recovery screen.

**Fix direction:** robust URL parsing, suggestions, visible origin/security information, and clear loading/error states.

### B6 — Default search adds fragility and cost
**Medium · Confirmed implementation / runtime reliability risk**

`MainActivity.kt:1159–1297`

Phormi Search sends the query to **11 engines**, downloads HTML, parses results and reloads the entire generated results document as each engine completes. This is not equivalent to one selected search provider.

Consequences to test: HTML/CAPTCHA breakage, partial results, bandwidth and latency, scroll/focus resets as results arrive. Queries are distributed to many providers; that needs an explicit explanation and user choice. The code does expose completion counts, so this is not a claim that every failure is invisible.

**Fix direction:** a dependable single-provider default; opt-in multi-engine search; incremental result updates instead of whole-document reloads; provider-specific failure states.

### B7 — Ghost mode is a reduced browser with an incomplete fallback
**High · Confirmed gaps / device-dependent privacy risk**

`GhostActivity.kt:30–57,67–159,195–225`; `PhormiRepairApplication.kt:9–15`; `AndroidManifest.xml`

Positive: Ghost uses `FLAG_SECURE`, a separate process and, on supported systems, a dedicated WebView profile. However:

- Without MULTI_PROFILE, `ghostProfileName` remains null. Exit clears history/cache but does not explicitly clear fallback cookies or Web Storage; profile deletion then does nothing. A separate data directory is isolation, not automatic ephemerality.
- Directory-suffix isolation is only applied on API 28+, while minSdk is 26. Older-device fallback needs particular attention; do not assume the newer isolation applies there.
- There is no WebChromeClient/file-chooser implementation, download listener or equivalent media-permission handling in Ghost. Normal browser upload/fullscreen/download flows are not carried over.
- Its controls use raw pixel dimensions, and the tab strip has no scrolling container. It looks and behaves like a separate mini-browser rather than a privacy variant of the main browser.

**Fix direction:** share browser behavior with explicit private-session policy; securely handle or disable unsupported isolation; verify cookies/storage are absent after close/reopen and process death.

### B8 — Accounts and sync are not Chrome-style browser identity/sync
**Medium · Confirmed scope gap**

`AccountsActivity.kt:16–40`; `PhormiSyncStore.kt:17–157`

Accounts is a set of website-login shortcuts, not authenticated Phormi browser identity. Its own copy correctly warns that external sessions cannot be imported and the list does not prove sign-in. Some provider WebView logins can remain incompatible; opening externally does not repair the internal cookie session.

`PhormiSyncStore` is a snapshot helper with no application call sites found. It exports at most five tabs, regenerates positional tab IDs and imports only part of the persisted tab schema. There is no active authenticated cross-device sync flow demonstrated by this class.

**Fix direction:** keep “Website accounts” distinct from “Browser account / sync”; do not describe helper code as shipped sync. Implement stable IDs, transport, authentication, merge semantics and complete schema migration before promising parity.

### B9 — Tabs are eagerly restored, and hiding is not memory reclamation
**Medium · Confirmed architecture / performance risk**

`MainActivity.kt:2003–2081,2207–2246,2303–2345,2937–2945`

Each restored tab creates a WebView, and tabs without restorable state load immediately. Under memory pressure, inactive views are merely hidden. There is no lazy-restoration/discard strategy here comparable to a mature browser's large-session management.

State capture is feature-gated, and routine saves write empty state entries when `captureWebViewState` is false. That can overwrite richer snapshots, reducing crash recovery to URLs. This is not a claim that normal background/foreground restore always fails.

**Fix direction:** lightweight tab metadata, lazy WebViews, explicit retention/discard policy and snapshots that are not erased by metadata-only saves. Benchmark 30+ tabs rather than asserting Chrome speed parity.

### B10 — Bookmarks, Favorites and Quick Access lack a clean distinction
**Medium · Confirmed / Design**

`BookmarksActivity.kt:24–51,109–145`; `FavoritesActivity.kt`; `res/layout/activity_menu.xml`

Bookmarks delegates its storage to `PhormiFavorites`; its add/delete prompts say “favorite.” Favorites is separately listed in the menu while its source comment claims separation from bookmarks. Quick Access has separate hiding behavior selected via inspection of the calling stack.

**Effect:** users see several concepts without a reliable answer to “where was this page saved?”

**Fix direction:** one saved-pages model; optionally expose “pinned to home” as a property. Use explicit methods for hiding a shortcut versus deleting a saved page, not stack-trace detection.

### B11 — Visual language and information hierarchy are inconsistent
**Medium · Design; source evidence confirmed**

`res/layout/activity_menu.xml`, `activity_start_page.xml`, `activity_main.xml`; `res/values/themes.xml`; `MainActivity.kt:879–910`; `TabsOverviewActivity.kt:128–139`

The main browser has theme-dependent colors, while menu and many secondary screens hard-code dark backgrounds. Tab cards switch to peach/light colors inside a dark tab overview. Unicode characters and emoji substitute for a consistent icon family. Small 9–12sp labels coexist with much larger headings and many independently styled buttons.

The menu is a full-screen three-category feature directory. “Same-page split” vs “Split screen,” “Browsing environments,” “Capsule,” “Navigation Lens” and “Object Anchors” need explanatory language. New features compete with everyday browsing tasks.

**Fix direction:** consistent color/spacing/type tokens, standard vector icons, progressive disclosure and plain-language labels. Keep the distinctive tools, but subordinate them to browsing.

## Keyboard versus Gboard

### K1 — Multilingual and glide enhancements are disconnected from normal rendering
**High · Confirmed**

`PhormiKeyboardServiceV2.kt:452–455,798–838`; `PhormiKeyboardRenderCompat.kt:17–36,87–119,147–235`; `res/xml/method_phormi_keyboard.xml`

There are two `render` implementations: a private service member and a same-named extension. Calls inside the service resolve to its member, which does **not** call the extension's locale-layout, glide or accented-long-press installers. No external renderer call sites were found.

The app advertises **41 language subtypes**, but the active letter layout is hard-coded QWERTY. Even the dormant locale mapper only changes French/German/Czech; it provides no real Arabic, Indic, Cyrillic, CJK composition or transliteration implementation.

The dormant glide implementation merely concatenates crossed letters; it has no gesture-to-word decoder, ranking, repeated-letter model or automatic spacing. Wiring it in alone would still not produce Gboard-style glide typing.

**Important distinction:** the dormant renderer's 360dp sizing differs from the service's 312dp base, but it is **not evidence that both heights currently run on every input**. The stronger confirmed defect is unreachable enhancements, not a proven 312/360dp fight.

**Fix direction:** one renderer, language-driven key models and a deliberate language-support matrix; advertise only genuinely implemented input systems. Treat glide as a separate recognition feature.

### K2 — Phone/date layouts omit essential controls
**High · Confirmed**

`PhormiKeyboardServiceV2.kt:452,474–479,589–639`

Phone and date/time branches render character rows directly but never add the bottom editing/action row. Backspace and Enter/Next/Done are therefore missing from these primary layouts. Date/time rows repeat digits instead of forming a coherent keypad.

Numeric mode always includes dot and comma without respecting decimal/signed flags, offers ABC that resolves straight back to numbers for a numeric editor, and lacks a visible route to a minus sign in its standard number page.

**Compared with Gboard:** field type must choose a complete usable keyboard, not just substitute characters. Verify phone, integer, negative decimal, date/time and numeric password fields separately.

### K3 — Clipboard image taps paste labels instead of images
**High · Confirmed**

`PhormiKeyboardServiceV2.kt:685–719`; `PhormiKeyboardClipboardStore.kt:45–49,64–88`

Image clipboard records contain both a URI and nonempty label text. The click handler tests `item.text.isNotBlank()` first and commits that label. The URI branch is not reached for those records.

**Effect:** selecting a screenshot can insert “Screenshot / image” instead of the image. Prefer URI content for media records, with MIME support/error handling; use text only for text records.

### K4 — Backspace is not Unicode-safe
**High · Confirmed**

`PhormiKeyboardServiceV2.kt:950–967`

The ordinary delete path calls `deleteSurroundingText(1, 0)`, deleting a UTF-16 code unit rather than a full user-perceived character. Supplementary emoji, combining accents and joined emoji need different handling. Exact resulting display depends on the editor.

**Fix direction:** at least code-point-safe deletion, ideally grapheme-cluster deletion, with selection and composing-region tests. Test skin-tone emoji, family emoji, flags and combining Yoruba marks.

### K5 — Locale and contextual emoji lookups use the wrong reflection target
**High · Confirmed source defect**

`PhormiKeyboardTextEngine.kt:75–92,250–254`; `PhormiKeyboardDictionaryApi.kt:134–144`; `PhormiKeyboardServiceV2.kt:38–57,721–728`

These helpers search for the companion property's `instance` backing field on the companion object class. Kotlin stores that backing field on the enclosing service class. The service already exposes an `activeInstance()` accessor, but the helpers do not use it.

**Effect:** subtype lookup falls back to editor hints/device locale; `contextBeforeCursor` returns empty, so contextual emoji cannot inspect the intended surrounding text through this path; remote suggestion refresh silently fails.

**Fix direction:** use explicit accessors/injected context, not reflection into private compiler implementation details.

### K6 — Dictionary requests are automatic, malformed and only partially integrated
**High · Confirmed implementation; live endpoint behavior untested**

`PhormiKeyboardTextEngine.kt:110–124`; `PhormiKeyboardDictionaryApi.kt:37–144`; `PhormiKeyboardPreferences.kt:26–32`; `PhormiKeyboardSettingsActivity.kt`

For eligible ordinary English text, suggestions request remote lookups once a prefix has two letters. There is no separate network-suggestions opt-in in the inspected settings. Password/no-personalized-learning guards exist, which is good, but that does not make ordinary text prefixes appropriate to send without a clear choice.

The Datamuse URL ends in `* +max=7`, not `*&max=7`. Parsing it yields one malformed `sp` value and no `max` parameter. The dictionary fallback returns only the exact prefix, which suggestion merging then filters out. The synchronous disk-cache helper returns an empty list; another disk reader exists but is not called by the inspected suggestion path. Requests create raw threads without an in-flight deduplication/negative-cache strategy.

**Fix direction:** offline by default; explicit optional online assistance; valid URLs, bounded background work, working cache and direct result callbacks. Do not describe current behavior simply as local-only typing assistance.

### K7 — Basic text behavior remains incomplete
**Medium · Confirmed**

`PhormiKeyboardServiceV2.kt:625–629,970–992`; `PhormiKeyboardTextEngine.kt:94–105,239–246`

- Space dragging moves just one cursor step after crossing a threshold; it does not track distance continuously.
- Multiline input unconditionally inserts a newline, even where the application requests a suitable action. Editor flags/actions need a proper policy.
- Automatic capitalization trims trailing whitespace before checking newline, so a fresh line after a non-punctuated sentence can be missed. CAP_WORDS is treated as always uppercase, not word-boundary-sensitive.
- Word extraction accepts letters/apostrophes but not combining marks, fragmenting accented language input.
- Key labels remain lowercase even when the shift state changes; the shift key is the only visual indication.

**Fix direction:** dedicated input-state/composition logic and tests rather than more conditionals in view handlers.

### K8 — Keyboard layout works against typing muscle memory
**Medium · Design; dimensions confirmed**

`PhormiKeyboardServiceV2.kt:339–374,404–441,453–472,531–552,589–638`

All three letter rows are stretched independently across the width, making seven bottom-row letters wider than ten top-row letters rather than using predictable staggered geometry. Shift and backspace are moved into a crowded nine-control bottom row with two separate number/symbol controls, comma, period, Space and the action key.

Suggestions and toolbar occupy separate rows. A 94dp AI-expression pill plus up to four suggestions crowds the candidate row; its children are 32dp high in a 30dp strip. Letter rows likewise place 44dp-high keys plus margins into 44dp parents. The weighted scroll body can leave excess space in normal typing and scroll core letters in constrained layouts.

**Compared with Gboard:** the issue is not merely different colors. Positions, sizes and reachability of frequent keys are less predictable.

**Fix direction:** stable staggered letter rows, shift/backspace alongside the final letter row, a wide spacebar and one coherent symbols entry. Keep optional AI out of prime candidate space.

### K9 — Resizing changes the window before it updates the key geometry
**Medium · Confirmed implementation / runtime risk**

`PhormiKeyboardServiceV2.kt:177–229,248–255,810–837`; `PhormiKeyboardResizeOverlay.kt:115–156`

Drag callbacks persist new scales and resize the window, but existing key heights were calculated when views were built. They are not rebuilt/rescaled until Done. The overlay computes height deltas against full screen height while the service uses a 312dp keyboard base, producing mismatched drag scaling. The window has screen-dependent height clamps, while root height is separately calculated without those same clamps.

**Effect to test:** clipping/blank space during resizing, inconsistent handle movement, and short-landscape layout overflow. Use one measured geometry model and actual IME insets; do not assume changing the IME window's soft-input mode alone controls the host app's insets.

### K10 — Voice typing has no usable listening/error state
**Medium · Confirmed**

`PhormiKeyboardServiceV2.kt:786,860–898,139–165`

Voice is inside Cabinet rather than a directly discoverable microphone control. Ready/beginning/RMS/partial callbacks are empty, even though partial results are requested. Recognizer errors simply cancel. Input finish/hide handlers do not cancel recognition, while the final result commits to whichever input connection is current at callback time.

**Effect:** users cannot clearly tell whether listening started, failed or finished; switching editors during recognition risks inserting results into the wrong field. Bind results to an input session, show listening/error/cancel states, and cancel on session end.

### K11 — Clipboard retention and settings polish are unfinished
**Medium · Confirmed / Design**

`PhormiKeyboardClipboardStore.kt:18–35,51–60`; `PhormiKeyboardSettingsActivity.kt:66–70`; `PhormiKeyboardServiceV2.kt:796`

Pinned clipboard entries still expire after 24 hours because expiry does not exempt `pinned`. The main clipboard panel exposes delete but no pin/unpin control. Theme settings put eight buttons on one equal-width phone row, making names hard to fit; the same theme is called “Rose” in one settings surface and “Berry” in another.

**Fix direction:** coherent pin semantics and controls; theme previews in a responsive grid; consistent names and selected-state feedback.

## Cross-cutting structure

1. **Oversized ownership:** `MainActivity.kt` is 4,120 lines and the keyboard service 996. Rendering, persistence, permission policy, networking and input behavior are intertwined. Size alone is not a bug, but these mixed responsibilities make fixes collide.
2. **Silent reflection failures:** compatibility code references private fields/methods and nonexistent `refreshPredictions` methods. Broad exception swallowing makes nonfunctional paths appear successful.
3. **Duplicated concepts:** two keyboard renderers, overlapping settings surfaces, multiple saved-page names and browser/ghost implementations drift apart.
4. **No unified layout/theme system:** raw pixels, dp, hard-coded colors, emoji icons and programmatic layouts are mixed. No explicit window-inset handling was found in the main UI; targetSdk 36 makes modern edge-to-edge testing important. Actual overlap is a device-testing risk, not established from screenshots.
5. **Insufficient interaction tests:** only three local unit-test files and no instrumentation tests were found. Existing tests cover useful dictionary/AI helper logic, not permission lifecycles, tab geometry or keyboard input integration.

## What is already worth preserving

- Main browsing cancels invalid TLS connections and blocks mixed content by default.
- The browser has real implementations for tabs, history, downloads, sharing, print/PDF and find-in-page; it is not just a static mock-up.
- WebView state restoration and renderer-crash handling exist, though parity/reliability needs testing.
- The keyboard has offline dictionary assets, conservative correction logic, correction undo, password/no-learning guards and clipboard sensitivity filtering.
- Ghost mode's secure-window and supported-profile isolation direction is sound.
- Accounts already contains honest explanatory copy about WebView login limitations.

## Recommended order

### P0 — Trust and basic correctness

Fix origin-specific permissions, live security-setting propagation, Ghost fallback cleanup, keyboard network consent, phone/date editing controls, clipboard image insertion and Unicode deletion.

### P1 — Primary interaction quality

Repair tab geometry and phone toolbar touch targets. Consolidate the active keyboard renderer and locale handling. Build a conventional stable key layout, session-safe voice input and one resize model. Validate accessibility labels and contrast.

### P2 — Product completeness

Improve omnibox/loading/errors, lazy tab restoration, dependable search, saved-page semantics and language-specific input. Ship sync only with a real end-to-end flow. Relegate specialized browser/AI tools to secondary surfaces until these basics are reliable.

## Device acceptance checklist

- 320/360/411dp portrait, high-density screens, landscape, split-window, gesture navigation, 200% font scale and TalkBack.
- Two origins requesting camera/mic/location; permission cancellation/navigation; security settings changed with existing tabs open.
- Ghost cookies/localStorage across close/reopen, process death and systems with/without MULTI_PROFILE.
- 30+ tabs, all overview modes, grouping/closing/reordering expectations, process restart and memory pressure.
- English/French/German/Yoruba/Arabic input; active subtype versus app hints; combining marks and emoji deletion.
- Phone, signed decimal, date/time, password, URL, email, multiline Send and Next/Done fields.
- Clipboard text/images, MIME rejection, pin expiry, cold restart and pending content returned after an editor switch.
- Voice success/error/cancel/editor switch; floating/resize keyboard across orientation changes.
- Network observation for dictionary-prefix requests, slow/offline search and provider failures.

## Checks performed

Static Python/XML checks confirmed: the 347dp fixed tab-header width; the raw-pixel/dp tab-card mismatch; 41 declared keyboard subtypes; both renderer definitions; malformed Datamuse query parameters; three local test files and zero instrumentation test files. Call sites were traced with repository search. These checks are not Android build, UI or endpoint tests.

Only this audit document was added; application behavior was not changed.
