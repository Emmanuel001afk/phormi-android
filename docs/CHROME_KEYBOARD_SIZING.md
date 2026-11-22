# Browser chrome, refresh, rotation and keyboard sizing

## One 48dp top row, ordered like Chrome

Phone, bottom bar off (the default):

```
‹   ›   [ 🔒  address / search ]   ↻   (2)   ⋮
Back Fwd                          Refresh Tabs Menu
```

Widths at 360dp: back 40 + forward 40 + refresh 36 + tabs 40 + menu 48 = 204dp of
controls, leaving 156dp for the omnibox (176dp at 380dp, 207dp at 411dp).

- **Back · Forward · address · Refresh · Menu** — the order people already know from Chrome.
- **Refresh is permanent.** It reads `×` (Stop) while the page loads and `↻` (reload)
  when it is idle. On a blank tab it renders as empty chrome and does nothing, so a
  refresh can never turn into a stray navigation.
- **Tabs (2)** is the tab-count circle. It opens the tab overview, where tabs are
  listed, searched, grouped and closed. **⋮** opens the menu, which starts with
  **New tab · Close current tab · Tab overview · Tab groups**, then THIS PAGE with
  **Back · Forward · Refresh · Find · Share · Desktop site · Home** — the controls
  Chrome keeps one tap from the tab strip. The menu is reachable from any page, so
  no function depends on a tab-strip that a phone does not show.
- **Home** is a menu row (Chrome keeps it in the overflow on phones), so no inline button
  steals address width. Every tab/page action is listed at the top of the menu.
- Wide screens (≥ 900dp) additionally show the tab strip, AI and active downloads inline.
- A visible **↻** never appears/disappears with screen width any more; that was the
  "refresh button appears and disappears" complaint.

### Bottom bar on (opt-in)

Top row keeps the address, Refresh, Tabs and Menu. The bottom bar owns **Back and
Forward only** plus AI and Downloads. No control is duplicated and the page keeps its
height.

## Refresh and the scroll problem

- The browser no longer wraps the WebView in a `SwipeRefreshLayout` by default. An
  up/down scroll (including scrolling back to the top of a long page) can only move
  the page — it cannot reload it.
- **Pull-to-refresh** is opt-in: Browser settings → Controls. When enabled, the layout
  is created on demand with a long pull distance and a child-scroll guard, so a pull
  only refreshes at the very top of a page.
- **Two-finger hold to refresh** is always available and needs no setting: put two
  fingers on the page and hold them still for about a second. It never competes with
  scrolling (the page is not dragged), never competes with pinch-zoom (any real
  movement over 12dp cancels it), and fires once per hold. `PhormiHoldToRefresh`
  owns the timing so a MOVE-less still hold still fires via a scheduled tick.

## Address field, cleared tabs and blank pages

- `PhormiBrowserChrome.addressText()` is the single source for what the omnibox shows:
  the search query while a local search is displayed, otherwise the tab's real URL,
  otherwise nothing.
- Going Home, closing the last tab, or clearing a tab resets the address field *and*
  the tab chip. The stale `https://youtube.com` left in the field after a page was
  cleared is gone.
- A tab that has not committed yet keeps the address the user asked for
  (`PhormiTabOverview.navigationUrl`), so it never falls back to an old site.
- Tab chips show the page title, or the host while the title is still pending.

## Rotation and fullscreen video

- `MainActivity` is `fullUser`: the browser follows the phone's rotation setting.
- On rotation the activity receives `onConfigurationChanged` and **reflows** each page
  (`PhormiDesktopMode.reflowScript()`): a resize event, no reload, so form values,
  scroll state and video playback survive.
- Entering HTML5 fullscreen (a movie player) switches to `full_sensor`, so turning the
  phone gives landscape and turning it back gives portrait — like Chrome. Leaving
  fullscreen returns to `fullUser`; it no longer restores a stale `unspecified` value.

## Tab memory (blank-tab regression)

- `PhormiTabMemoryPolicy` only discards pages that are **outside a 10-minute grace
  window** and not protected (active, split, AI, device access, private, search, media,
  unsaved edits). A tab used recently keeps its page instead of coming back empty.
- Every reuse restores scroll position and the saved navigation snapshot.

## Keyboard sizing

- Base height adapts to landscape, phone and tablet sizes; presets Compact / Comfortable / Large.
- Key lettering follows the actual key box, so narrow keyboards shrink text rather than overflow.
- **Docked keyboards are flush with the bottom edge.** The IME window extends behind
  the system navigation zone and the root padding keeps the lowest row clear of the
  gesture pill. Gesture navigation reports a zero `navigation_bar_height`, so the
  inset falls back to a real 24dp estimate instead of 0 — the "keyboard floats above
  a gap" bug.
- Floating keyboard stays a free Gboard-style panel that never resizes the app.

## Validation

```bash
python3 tools/audit_chrome_keyboard.py
python3 tools/audit_tabs.py
python3 tools/audit_repairs.py
gradle testDebugUnitTest
gradle connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.uong.phormi.TabOverviewSmokeTest,com.uong.phormi.ChromeKeyboardSmokeTest
```

Device checks cover Chrome-order top bar, permanent refresh, no SwipeRefreshLayout by
default, pull-to-refresh only installed after the user opts in, bottom-bar ownership,
compact chrome height, readable letter keys and a real height-preset change.
`PhormiHoldToRefreshTest` covers the two-finger hold on the JVM: one second fires once,
a moving/pinching gesture never fires, and lifting re-arms.
