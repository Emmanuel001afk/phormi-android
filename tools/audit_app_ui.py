#!/usr/bin/env python3
"""Static guardrails for the Phormi icon-derived UI theme.

This is intentionally cheap and source-only. It does not replace Gradle,
Android lint, or a device/emulator visual pass.
"""
from __future__ import annotations

from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
SRC = ROOT / "app/src/main/java/com/uong/phormi"
errors: list[str] = []

BRAND = {
    "phormi_cobalt": "#3659E3",
    "phormi_paper": "#FFF4D8",
    "phormi_coral": "#FF9678",
    "phormi_ink": "#192653",
}
SEMANTIC = [
    "phormi_background", "phormi_surface", "phormi_surface_soft",
    "phormi_text", "phormi_secondary", "phormi_accent", "phormi_accent_soft",
    "phormi_on_accent", "phormi_coral", "phormi_on_coral", "phormi_coral_text",
    "phormi_border", "phormi_success", "phormi_warning", "phormi_danger",
    "phormi_scrim", "phormi_media_background", "phormi_media_scrim",
    "phormi_media_scrim_strong", "phormi_on_media", "phormi_navigation_bar",
]


def parse_colors(path: Path) -> dict[str, str]:
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as exc:
        errors.append(f"cannot parse {path.relative_to(ROOT)}: {exc}")
        return {}
    return {e.attrib["name"]: (e.text or "").strip() for e in root.findall("color") if "name" in e.attrib}


day = parse_colors(RES / "values/colors.xml")
night = parse_colors(RES / "values-night/colors.xml")
for name, value in BRAND.items():
    if day.get(name) != value:
        errors.append(f"brand color {name} must stay {value}; found {day.get(name)!r}")
for name in SEMANTIC:
    if name not in day:
        errors.append(f"missing light semantic color: {name}")
    if name not in night and name != "phormi_coral":
        errors.append(f"missing dark semantic color: {name}")
# phormi_coral is a brand constant inherited from values/colors.xml in night.
if "phormi_coral" in night and night["phormi_coral"] != BRAND["phormi_coral"]:
    errors.append("night phormi_coral must not drift from the icon palette")


def luminance(hex_color: str) -> float:
    hex_color = hex_color.lstrip("#")
    if len(hex_color) == 8:
        hex_color = hex_color[2:]
    channels = [int(hex_color[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    linear = [c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4 for c in channels]
    return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]


def contrast(a: str, b: str) -> float:
    la, lb = luminance(a), luminance(b)
    lighter, darker = max(la, lb), min(la, lb)
    return (lighter + 0.05) / (darker + 0.05)


for label, palette in (("light", day), ("dark", night)):
    for fg, bg, minimum in (
        ("phormi_text", "phormi_background", 4.5),
        ("phormi_secondary", "phormi_background", 4.5),
        ("phormi_on_accent", "phormi_accent", 4.5),
        ("phormi_on_coral", "phormi_coral", 4.5),
        ("phormi_on_media", "phormi_media_background", 4.5),
    ):
        if fg in palette and bg in palette:
            ratio = contrast(palette[fg], palette[bg])
            if ratio < minimum:
                errors.append(f"{label} contrast {fg}/{bg} is {ratio:.2f}:1, expected >= {minimum}:1")

# Theme must be token-driven and API-safe.
themes = (RES / "values/themes.xml").read_text()
if "Base.Theme.Phormi" not in themes or "Theme.MaterialComponents.DayNight.NoActionBar" not in themes:
    errors.append("Base.Theme.Phormi must inherit the DayNight MaterialComponents theme")
for item in ("colorPrimary", "colorOnPrimary", "colorSurface", "colorOnSurface", "android:windowBackground", "android:statusBarColor"):
    if item not in themes:
        errors.append(f"theme missing {item}")
if "android:windowLightNavigationBar" in themes:
    errors.append("windowLightNavigationBar must live only in values-v27 (minSdk is 26)")
v27 = RES / "values-v27/themes.xml"
if not v27.is_file() or "android:windowLightNavigationBar" not in v27.read_text():
    errors.append("values-v27/themes.xml must provide the light navigation bar attribute")
for path in (RES / "values/bools.xml", RES / "values-night/bools.xml"):
    if not path.is_file() or "phormi_light_system_bars" not in path.read_text():
        errors.append(f"missing system-bar bool resource: {path.relative_to(ROOT)}")

# Kotlin helper surface.
ui_colors = (SRC / "PhormiUiColors.kt").read_text()
for accessor in ("background", "surface", "surfaceSoft", "text", "secondary", "accent", "accentSoft", "onAccent", "coral", "onCoral", "coralText", "border", "success", "warning", "danger", "scrim", "mediaBackground", "mediaScrim", "mediaScrimStrong", "onMedia", "applyTheme"):
    if f"fun {accessor}(" not in ui_colors:
        errors.append(f"PhormiUiColors missing {accessor}()")

# Shared surfaces must exist and use semantic tokens.
for name in ("bg_primary_button", "bg_secondary_button", "bg_card", "bg_input", "bg_start_search", "bg_start_action", "bg_menu_item", "bg_shortcut"):
    path = RES / f"drawable/{name}.xml"
    if not path.is_file():
        errors.append(f"missing shared drawable: {name}")
        continue
    text = path.read_text()
    if re.search(r"#[0-9A-Fa-f]{6,8}", text):
        errors.append(f"{name} hardcodes a color instead of using @color/phormi_*")
    if "@color/phormi_" not in text and "@android:color/transparent" not in text:
        errors.append(f"{name} does not reference the Phormi palette")

# Layouts should not carry one-off hex colors.
for path in (RES / "layout").glob("*.xml"):
    if re.search(r"#[0-9A-Fa-f]{6,8}", path.read_text()):
        errors.append(f"layout hardcodes a hex color: {path.name}")

# Guard against the old slate/cyan browser palette returning to app chrome.
legacy = re.compile(r"#0[Bb]1220|#0[Ff]172[Aa]|#1[18]2338|#38[Bb][Dd][Ff]8|Color\.rgb\(56,\s*189,\s*248\)|Color\.rgb\(148,\s*163,\s*184\)|0xFF42A5F5|0xff60a5fa")
allow = {
    "PhormiKeyboardServiceV2.kt",  # user-selectable keyboard presets
    "PhormiAiEmojiEngine.kt",      # generated illustration content
    "PhormiPollinations.kt",       # remote AI image prompt/content helpers
}
for path in SRC.glob("*.kt"):
    if path.name in allow:
        continue
    if legacy.search(path.read_text()):
        errors.append(f"legacy slate/cyan UI color found in {path.name}")

# Keyboard default preset must be branded without changing stored indices.
settings = (SRC / "PhormiKeyboardSettingsActivity.kt").read_text()
service = (SRC / "PhormiKeyboardServiceV2.kt").read_text()
if '"Phormi","Graphite","Ocean","Light","Slate","Forest","Berry","Sand"' not in settings:
    errors.append("keyboard settings preset 0 must be named Phormi")
if '"Phormi","Graphite","Ocean","Light","Slate","Forest","Berry","Sand"' not in service:
    errors.append("keyboard service preset 0 must be named Phormi")
if "Midnight" in settings or "Midnight" in service:
    errors.append("old Midnight keyboard label is still present")
prefs = (SRC / "PhormiKeyboardPreferences.kt").read_text()
if "coerceIn(0, 7)" not in prefs:
    errors.append("keyboard theme indices must remain 0..7 for preference compatibility")

if errors:
    print("PHORMI APP UI AUDIT: FAIL")
    for error in errors:
        print(f" - {error}")
    sys.exit(1)
print("PHORMI APP UI AUDIT: PASS (icon palette, light/dark tokens, API-safe system bars, shared surfaces, keyboard preset)")
