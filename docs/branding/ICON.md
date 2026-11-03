# Phormi app icon

> A hand-cut monogram **P** in warm bone, holding an **ember sun** in its counter,
> set on a deep pine field traced with contour rings.

The icon replaces the previous glossy blue "AI gradient" mark. It is deliberately
quiet: a muted green field, a warm off-white letter, and a single ember accent —
so it stays readable in a crowded launcher and doesn't glare at night.

---

## The idea

| Element | Meaning |
| --- | --- |
| **Bone "P"** | The app's initial, drawn as one continuous rounded stroke — a hand-cut letterform rather than a geometric monogram. The warm off-white reads as paper, chalk, or enamel: a material, not a gradient. |
| **Ember sun in the counter** | The surviving bright thing inside the letter. It stands for daylight, the open web, and the tab you come back to. Warmth comes from *one* light source rather than neon glow everywhere. |
| **Pine field + contour rings** | A deep, low-saturation green instead of the usual tech blue/black. The concentric rings are topography lines — quiet structure behind the mark. |
| **Warm light spill** | A faint amber wash on the letter where it faces the sun, so the letter and sun read as one lit object rather than two stickers. |

Palette balance: one low-chroma field (pine), one warm neutral (bone), one warm
accent (ember). Nothing is fully saturated — the brightest pixel in the icon is
about `#FFD79E`, well below the neon range.

## Palette

| Token | Hex | Use |
| --- | --- | --- |
| Pine (top) | `#2C5248` | field gradient start |
| Pine (deep) | `#0A1715` | field gradient end |
| Bone highlight | `#FBF3E3` | lit edge of the letter |
| Bone mid | `#F1E5CE` | letter body |
| Bone shadow | `#DFC9A3` | shaded edge of the letter |
| Ember highlight | `#FFD79E` | sun centre |
| Ember | `#E9955A` | sun body |
| Ember deep | `#B24A28` | sun rim |
| Contour | `#4E796D` | rings (30% opacity) |

## What ships

| File | Purpose |
| --- | --- |
| `app/src/main/res/drawable/ic_launcher_foreground.xml` | adaptive foreground (vector, gradient fill + stroke) |
| `app/src/main/res/drawable/ic_launcher_background.xml` | adaptive background (vector, gradient + contour rings) |
| `app/src/main/res/drawable/ic_launcher_monochrome.xml` | Android 13+ themed-icon silhouette |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher{,_round}.xml` | adaptive-icon definitions |
| `app/src/main/res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher{,_round}.png` | legacy launcher icons (API 26 fallback) |
| `docs/branding/phormi_icon.svg` / `.png` | master art (1024px PNG, 512px store size) |
| `docs/branding/phormi_mark.svg` / `app_logo.png` | transparent-background mark for docs, README, store listings |
| `docs/branding/adaptive_preview.png` | composited launcher preview |

## Safe zones

The adaptive foreground is sized so the mark's **bounding-box diagonal** is
54.5% of the 108dp canvas. That keeps it inside the guaranteed-visible circle
(66dp diameter) under every launcher mask — squircle, circle, rounded square,
and teardrop — while still filling the tile.

The legacy PNGs are full-bleed squares; the round variants carry their own
circular clip.

## Themed icons (Android 13+)

`ic_launcher_monochrome.xml` draws the same P and sun as a single solid
silhouette with the sun reduced to 60% of its normal size, so the letterform
still shows a counter when the system tints the whole shape one colour.

## Regenerating

The geometry lives in one place — `tools/app_icon/generate_icon.py`. Both the
Android vector drawables and the SVG masters are emitted from the same
letterform constants, so nothing can drift out of sync.

```bash
# 1. vector drawables + SVG sources (stdlib only)
python3 tools/app_icon/generate_icon.py .

# 2. PNG rasters (needs one dev dependency, not committed)
npm install --no-save @resvg/resvg-js
node tools/app_icon/rasterize.js .
```

Tweak the constants at the top of `generate_icon.py` (`BOWL_R`, `BOWL_BOT`,
`STROKE`, `SUN_R`, the palette) and re-run both steps to explore variations.
