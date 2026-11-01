# Follow-up pass: CI failure and the reported browser / keyboard concerns

This document records the work done **after** PR #12 was merged. It is deliberately
a separate, single commit so the whole pass can be reverted on request.

## 1. The merged PR was not awaited — and CI failed

PR #12 was merged with `gh pr merge 12 --merge` while `CodeQL Java/Kotlin` was
still pending. That check later **failed**:

* `gh pr checks 12` → `CodeQL Java/Kotlin  fail`
* Run `41362740998` (`Phormi code quality`) → `failure` at step **Run unit tests**
* `Build committed source for CodeQL extraction`, `Android lint`,
  `Publish debug APK` and `Analyze` were all skipped.

The job log download is blocked for this sandbox, but the check run
(`125016391051`) exposes the compiler errors as annotations:

```
e: MainActivity.kt:3126:46 Argument type mismatch: actual type is 'WebView?', but 'WebView' was expected.
> Task :app:compileDebugKotlin FAILED
FAILURE: Build failed with an exception.
Caused by: org.jetbrains.kotlin.gradle.tasks.CompilationErrorException
```

This was **introduced by the previous fix pass**: `onReceivedError` receives a
nullable `WebView?`, but the new `showNetworkErrorPage()` helper declared a
non-null `WebView`. Kotlin cannot smart-cast `view` from
`view == activeWebView()`.

**Fix:** `showNetworkErrorPage(view: WebView?, ...)` and
`view?.loadDataWithBaseURL(...)`.

> Note: the static checks used earlier (tree-sitter parse, XML parse,
> `tools/audit_project.py`, Python mirrors of pure-Kotlin logic) cannot catch a
> Kotlin type error. Only a real Gradle compile can. That is why this pass is
> validated with an actual CI run instead of being declared "done" locally.

## 2. Keyboard autocorrect and suggestions

**Answer: they are active and enabled by default**, and they are *not* limited to
websites or to any particular site.

* `PhormiKeyboardPreferences.suggestions()` and `.autocorrect()` both default to
  `true`; both are toggleable in the keyboard's Tools → Settings panel and in the
  standalone `PhormiKeyboardSettingsActivity`.
* `refreshPredictionStrip()` builds the candidate row after every keystroke,
  cursor move and selection change (`schedulePredictionRefresh`, `onUpdateSelection`).
* `autoCorrectCurrentWord()` runs on space, on `. , ! ? ; :`, and on the Enter/
  action key. It uses the on-device dictionaries plus the learned-word store;
  online lookups are opt-in and **off by default**.

They are intentionally **off** in these cases (matching Android/Gboard
behaviour, not a website restriction):

* password / visible-password / `TYPE_TEXT_FLAG_NO_SUGGESTIONS` fields
* URI-like and address fields (`textUri`, `TYPE_TEXT_VARIATION_URI`)
* number, phone and date-time input types
* the symbols and numbers keyboard pages (the candidate strip is only part of
  the letters page)

Three real defects were fixed:

1. **A tapped suggestion was silently re-autocorrected.** The handler called
   `commitSpace()`, which runs `autoCorrectCurrentWord()` on the word just
   inserted — so choosing `receive` could immediately be changed to a different
   dictionary word. A `suppressAutoCorrectOnce` flag now protects the user's
   explicit choice.
2. **Setting changes made while the keyboard was hidden could be ignored.**
   The prediction strip was cached by `(word, previousWord)`.
   `onWindowShown()` now clears that cache and re-reads the preferences.
3. **No visible state.** When the strip has nothing to show it now reads
   `Suggestions on · autocorrect on/off · type a word`, `Suggestions off`, or
   `Suggestions off for this field` instead of a generic placeholder.

## 3. Desktop mode — no longer per-site only

Previously the toggle only added/removed the current host from
`KEY_DESKTOP_SITES`; there was no way to make desktop mode the default, and no
way to opt a single site *out* of a global setting.

Now there is:

* a global default (`KEY_DESKTOP_ALL`),
* an explicit per-host **on** set (`KEY_DESKTOP_SITES`),
* an explicit per-host **off** set (`KEY_DESKTOP_SITES_OFF`),

resolved in that order. The menu action opens a chooser with
"Desktop/Mobile layout on `<host>` only" and "Desktop/Mobile layout on every
site", and it states the current scope. Changing the global value pushes the
UA/viewport settings to **all open tabs**; only the active tab is reloaded so
other tabs do not lose form state.

Viewport handling was also corrected. `configureWebView()` used
`useWideViewPort = desktop` (i.e. off for mobile) and
`loadWithOverviewMode = desktop`, while `applyDesktopMode()` used the opposite
convention. A mobile browser should honour the viewport meta tag
(`useWideViewPort = true`) and use overview scaling; a desktop layout should
keep its CSS width and use `LayoutAlgorithm.NORMAL` instead of text autosizing.
`setInitialScale(if (enabled) 0 else 0)` (a no-op) was removed.

## 4. Opening links in tabs / "website expansion"

The long-press link and image menu previously offered only `Open`, `Copy`, and
`Download` — and `Open` always created a new tab with no way to open in place.
It now offers:

| Action | Behaviour |
| --- | --- |
| Open here | loads in the current tab |
| Open in new tab | new tab, brought to front |
| Open in background tab | new tab, `activate = false` |
| Open in Ghost tab | launches the separate private Ghost window |
| Copy link / image URL | clipboard |
| Download link / image | existing download path |

`onCreateWindow()` already turns `target="_blank"` / `window.open()` requests
into real tabs, and the main tab strip is already a `HorizontalScrollView`.

> "Website expansion" was read as expanding a link/site into a tab or a wider
> layout, rather than a separate feature. If it means something else (for
> example a full-screen reading mode), say so and it can be added or reverted.

## 5. Selecting more than one tab to delete

`TabsOverviewActivity` was single-action only: one tap selected a tab, the `×`
closed exactly one tab. It now has an explicit **Select** mode:

* a `Select` / `Cancel` toggle, `All` / `None`, and `Close (n)` in the bottom bar,
* a checkbox on every card, selected cards get a blue border and tinted surface,
* long-press on a card now also offers "Select multiple tabs",
* `Close (n)` asks for confirmation, then returns `action = close_many` with an
  `IntArray` of ids.

`MainActivity.handleTabsOverviewResult()` handles `close_many`, resolving each
id at the moment it is closed because `closeTab()` mutates the tab list and can
spawn a replacement tab when the list empties.

Also fixed while here: the card's "Group" button was guarded by
`body is FrameLayout`, but the card body is a `LinearLayout` — so the button
never appeared. It is now added to the outer `FrameLayout`.

## 6. Ghost mode

### Contrast / unreadable text

`GhostActivity` set a dark root background and white text on a few controls but
left the toolbar, tab strip, chips and URL field without their own backgrounds.
Depending on the WebView/theme combination, that produced text the same colour
as its background. It also never set a WebView background, so a transparent
WebView over a dark window rendered dark page text on dark.

Now every surface is explicit: dark toolbar (`#111C2E`), darker strip
(`#0F1A2B`), rounded slate URL field (`#1E293B`) with `#F8FAFC` text and
`#94A3B8` hint, active tab blue (`#1D4ED8`) with white text, inactive tabs
slate with `#CBD5E1` text, selected tabs amber with near-black text. The WebView
host and each WebView are pinned to white as a content base; pages with their
own background still paint over it.

The same transparent-WebView-over-dark-window problem existed in the main
browser (`webView.setBackgroundColor(Color.TRANSPARENT)` against the
`#0F172A` window background), so that was changed to `Color.WHITE` too.

### "Tab one shows the same as tab two / unified system"

This was a stale-state bug, not shared page content:

* `switchTo()` only updated the omnibox when `active()?.url` was non-null, so a
  new blank tab inherited the previous tab's address.
* Every chip was a static `Ghost 1`, `Ghost 2`, … with no title or URL.
* The URL bar was a single shared value with no per-tab state.

`GhostTab` now stores its own `address` and `title`, updated from
`onPageStarted` / `onPageFinished` / `onReceivedTitle`. `switchTo()` **always**
overwrites the omnibox (clearing it for blank tabs), and each chip shows its
page title or host. All Ghost tabs still share one isolated `PhormiGhost_*`
WebView profile — this is intentional and matches Chrome Incognito, where all
private tabs share one private session. If truly per-tab isolation is wanted,
that is a separate change.

### Limited tab arrangement

The strip was a bare `LinearLayout` with `layout_weight = 1` per chip, so tabs
collapsed to nothing as more were added, with no close button, no titles and no
way to manage several tabs. It is now:

* a `HorizontalScrollView` with fixed-width (150 dp) chips that stay readable
  and auto-scroll to the active tab,
* each chip has its own `×` close button,
* the counter opens a **Ghost tabs** list dialog with titles and addresses,
* long-press a chip (or use "Select tabs" in that dialog) enters a multi-select
  mode with `All` / `Close (n)` / `Cancel` and a confirmation dialog,
* Back exits selection mode before navigating or closing Ghost mode.

## 7. Validation

* tree-sitter parse of all Kotlin files: no new errors versus the baseline
  (pre-existing grammar false positives in `TabsOverviewActivity.kt` decreased
  from 8 to 7).
* all `app/src/main` XML parses; `python3 tools/audit_project.py` →
  `PASS (25 components, 22 layouts, 189 resource IDs, 8 dictionaries)`.
* **A real Gradle `testDebugUnitTest` / `assembleDebug` / `lintDebug` run is
  required for Kotlin type checking** — this pass is validated by an awaited CI
  run, see the report accompanying it.
* No emulator/device UX test was possible in this sandbox, so the visual
  changes are verified by source inspection only.

## 8. Reverting

Everything in this pass is one commit on
`arena/017ec980-phormi-android`. To undo just this pass:

```
git revert <this-commit-sha>
```

To undo the earlier merged fix pass as well:

```
git revert 766b156
```

To return to the exact pre-audit baseline:

```
git reset --hard d2436ad
```
