# Phormi UI theme

The app UI is derived from the launcher icon: **cobalt actions, warm paper surfaces,
ink text, and coral accents**. The goal is a friendly, rounded browser surface,
not a separate visual identity from the icon.

## Palette

Brand constants live in `app/src/main/res/values/colors.xml` and should not drift:

| Token | Value | Use |
| --- | --- | --- |
| `phormi_cobalt` | `#3659E3` | primary action / icon body |
| `phormi_paper` | `#FFF4D8` | warm surface / icon page |
| `phormi_coral` | `#FF9678` | playful accent / folded ear |
| `phormi_ink` | `#192653` | text / icon eye |

Semantic tokens map the brand into light and dark mode:

- Light: paper background (`#FAF5E9`), near-paper surfaces, ink text, cobalt accent.
- Dark: ink background (`#121A3A`), lifted ink surfaces, paper text, lighter cobalt accent (`#9DB1FF`).
- Status colors (`success`, `warning`, `danger`) are tuned separately for each mode.
- Media viewer chrome stays cinematic, but uses `phormi_media_*` and `phormi_on_media` tokens.

## Usage rules

- XML: use `@color/phormi_*`, not one-off hex values.
- Kotlin: use `PhormiUiColors.*` or the matching color resource.
- Shared surfaces: `bg_primary_button`, `bg_secondary_button`, `bg_card`, `bg_input`,
  `bg_start_search`, `bg_start_action`, `bg_menu_item`, `bg_shortcut`.
- Rounded shape language: inputs/menu items ~16dp, cards/shortcuts ~20dp,
  buttons ~24dp, search pills ~28dp.
- WebView content keeps a white page background unless Phormi owns the page
  (search results, network error page). Phormi-owned HTML receives token-derived CSS colors.
- PDF page bitmaps remain white; only the viewer chrome is themed.

## System bars

`Base.Theme.Phormi` sets status/navigation colors from tokens.
`android:windowLightNavigationBar` is API 27+, so it lives only in
`app/src/main/res/values-v27/themes.xml`. On API 26 the navigation bar uses the
dark `phormi_navigation_bar` fallback because light navigation-bar icons are unavailable.

## Keyboard

Keyboard preset `0` is now **Phormi** and uses the icon's ink/paper/cobalt/coral
relationship. Stored theme indices remain `0..7`, so existing preferences keep
working. Shift is coral for one-letter capitalization; caps lock remains red.

## Verification

Run the cheap source guardrails before Gradle:

```bash
python3 tools/audit_project.py
python3 tools/audit_repairs.py
python3 tools/audit_app_icon.py
python3 tools/audit_app_ui.py
```

Then run the Android checks in CI or locally:

```bash
gradle testDebugUnitTest
gradle assembleDebug
gradle lintDebug
```

A visual pass should still check light mode, dark mode, API 26/27+ system bars,
Ghost mode, the keyboard, and the media/PDF viewers on a device or emulator.
