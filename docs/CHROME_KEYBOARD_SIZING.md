# Browser chrome and keyboard sizing

## Horizontal top/bottom organisation

The browser keeps one horizontal chrome layer by default. The optional bottom bar is a real navigation bar, not a duplicate strip.

### Bottom bar off (default)

Top bar, left to right:

1. **Home**
2. **Back**
3. **Forward**
4. **Address/search field**
5. **Tabs**
6. **Menu**

On phones, Refresh/AI/Downloads live in the menu so the address field keeps usable width. Wide screens may show the tab strip, Refresh, AI and active Downloads inline.

### Bottom bar on

- **Top bar:** Home, address/search, Refresh, Tabs, Menu.
- **Bottom bar:** Back, Forward, AI, Downloads.

Back/Forward are not duplicated in the top bar while the bottom bar owns them.

Both bars stay at **48dp** high. Secondary top-bar targets are 44dp wide where needed to preserve address width; primary Home/Tabs/Menu/Refresh targets remain 48dp.

## Keyboard size and lettering

The keyboard no longer uses one fixed 312dp base height and fixed key lettering.

- Base height adapts to landscape, phone and tablet sizes.
- Height presets: **Compact / Comfortable / Large**.
- Key lettering presets: **Small / Default / Large**.
- Lettering is computed from the actual key box, so narrow/reduced-width keyboards shrink text instead of overflowing.
- Home-row inset and key margins adapt to row length.
- **Docked keyboards are flush with the bottom screen edge**: the IME window extends behind the system navigation zone by the navigation-bar inset (estimated synchronously, refined from real window insets), and the keyboard's own bottom padding keeps the lowest key row clear of the gesture pill / navigation bar. No visible gap, and the bottom row is never hidden behind the system bar.
- **Floating keyboard is a free Gboard-style panel**: it resizes the app nothing, sits over a transparent window with room to spare. Drag the grab bar on the top edge to move it; drag a corner handle to reshape it. Everything outside the handle zones types normally. Moving/resizing updates the window live and commits (re-renders key geometry) only when the gesture ends.
- Reset restores height, width, position and lettering.

Settings are available both in **Phormi Keyboard settings** and in the keyboard's **Tools → Settings** panel.

## Validation

```bash
python3 tools/audit_chrome_keyboard.py
python3 tools/audit_tabs.py
gradle testDebugUnitTest
gradle connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.uong.phormi.TabOverviewSmokeTest,com.uong.phormi.ChromeKeyboardSmokeTest
```

Device checks cover top-only navigation, bottom-bar ownership, compact chrome height, readable letter keys and a real height-preset change.

## Rotation

Phormi screens now declare `android:screenOrientation="fullUser"` instead of relying on an unspecified/portrait-biased default. The browser also recomputes top/bottom chrome ownership when the device rotates, so the address bar and navigation controls stay usable in landscape.
