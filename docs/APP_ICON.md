# Phormi app icon

The launcher mark is an original, hand-built vector character: a lowercase **P** whose bowl is a curious eye, whose top-right corner folds like a page/ear, and whose stem ends in a small forward step. It is deliberately asymmetric and flat-colored so it reads as a drawn brand character rather than a glossy generic badge.

## Palette

| Role | Hex |
| --- | --- |
| Cobalt field | `#3659E3` |
| Paper body | `#FFF4D8` |
| Coral fold | `#FF9678` |
| Ink pupil | `#192653` |

## Android resources

- `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml` — adaptive icons for Android 8+.
- `app/src/main/res/mipmap-anydpi-v33/ic_launcher.xml` and `ic_launcher_round.xml` — adaptive icons with a monochrome layer for Android 13+ themed icons.
- `app/src/main/res/drawable/ic_launcher_background.xml` — solid cobalt background layer.
- `app/src/main/res/drawable/ic_launcher_foreground.xml` — color character, scaled to stay inside the 66dp adaptive safe circle.
- `app/src/main/res/drawable/ic_launcher_monochrome.xml` — alpha-only silhouette for themed icons.
- `app/src/main/res/drawable/ic_phormi_notification.xml` — alpha-only small-icon silhouette for notifications.
- `app/src/main/res/drawable/ic_phormi_logo.xml` — full-bleed rounded brand tile for in-app surfaces.
- `app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher*.png` — legacy/store density exports.

The old `drawable-nodpi/app_logo.png` raster is removed. Notification builders now use `ic_phormi_notification` instead of the full-color launcher tile.

## Design masters

- `design/icon/phormi-icon.svg` — full-bleed square master.
- `design/icon/phormi-foreground.svg` — transparent adaptive foreground.
- `design/icon/phormi-monochrome.svg` — transparent themed-icon silhouette.
- `design/icon/phormi-icon-1024.png` — 1024px master export.
- `design/icon/phormi-play-store-512.png` — 512px Play Store export.
- `design/icon/phormi-icon-preview.png` — mask, small-size, and themed-layer preview sheet.

## Regeneration

The vector/Android resource generator uses only the Python standard library:

```bash
python3 tools/build_app_icon.py
python3 tools/audit_app_icon.py
```

The checked-in PNGs were rendered from the SVG masters with `@resvg/resvg-js`. If the mark changes, re-export the PNGs at the sizes listed above and rerun the audit. The audit checks resource wiring, palette/path drift, adaptive safe-zone bounds, density sizes, and notification icon usage; it does not replace a Gradle/Android build.
