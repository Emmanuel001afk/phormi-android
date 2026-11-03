#!/usr/bin/env python3
"""Generate Phormi's original vector app-icon sources and Android resources.

The checked-in PNG exports are rendered from design/icon/phormi-icon.svg.
This script intentionally uses only the Python standard library so resource
regeneration and CI audits do not require a design toolchain.
"""
from __future__ import annotations

from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
DESIGN = ROOT / "design/icon"
ANDROID_NS = "http://schemas.android.com/apk/res/android"

COBALT = "#3659E3"
PAPER = "#FFF4D8"
CORAL = "#FF9678"
INK = "#192653"

# One continuous, slightly off-balance P. The bowl is an eye, the top-right
# corner is a folded page/ear, and the stem ends in a small forward step.
BODY = (
    "M36 26C43 23.8 54.5 24.2 63 25.5L77 38C80 43 80 50 76.5 55.5"
    "C72.5 62 65.5 65.5 58 65C53.5 64.8 49.5 63.5 46 61.5L46.2 73.5"
    "C46.3 77.8 47.8 79.6 51.2 80.4C54.2 81.1 54 84.2 50.8 84.7"
    "C46.2 85.4 41.8 84.1 37.5 83C32 82.5 29.5 79 29.7 73.5L30.2 37"
    "C30.3 31.5 32 27.4 36 26Z"
)
FOLD = "M63 25.5L63.3 34.2C63.4 37.2 64.8 38.5 67.8 38.4L77 38Z"
EYE = (
    "M47 36.5C51.5 33 59 33.5 63.5 37.5C68 41.5 66.5 48.5 62 52.5"
    "C57.5 56.5 50 55 46 50.5C42 46 42.5 40 47 36.5Z"
)
PUPIL = (
    "M58.5 37.5C61.8 37.2 64 39.6 64 43C64 46.5 62 49 58.8 49"
    "C55.5 49 53.5 46.7 53.5 43.5C53.5 40.2 55.3 37.8 58.5 37.5Z"
)
GLINT = (
    "M60.5 39.2C61.6 39.2 62.4 40 62.4 41.1C62.4 42.2 61.6 43 60.5 43"
    "C59.4 43 58.6 42.2 58.6 41.1C58.6 40 59.4 39.2 60.5 39.2Z"
)
COLOR_PATHS = ((BODY, PAPER), (FOLD, CORAL), (EYE, COBALT), (PUPIL, INK), (GLINT, PAPER))
MONO_PATH = "".join((BODY, EYE, PUPIL, GLINT))
ROTATION = "-3"
ADAPTIVE_SCALE = "0.9"  # Keeps the complete character inside the 66dp safe circle.
FULL_SCALE = "1.12"     # Full-bleed exports are not parallax-cropped by a launcher.


def svg_transform(scale: str) -> str:
    return f"translate(54 54) scale({scale}) rotate({ROTATION}) translate(-54 -54)"


def color_svg_paths() -> str:
    return "\n".join(f'    <path fill="{color}" d="{data}"/>' for data, color in COLOR_PATHS)


def write_svg(path: Path, body: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        '<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 108 108">\n'
        + body
        + "\n</svg>\n",
        encoding="utf-8",
    )


def vector_start(width: str = "108dp") -> str:
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f'<vector xmlns:android="{ANDROID_NS}"\n'
        f'    android:width="{width}" android:height="{width}"\n'
        '    android:viewportWidth="108" android:viewportHeight="108">\n'
    )


def android_group(scale: str) -> str:
    return (
        f'    <group android:scaleX="{scale}" android:scaleY="{scale}"\n'
        f'        android:pivotX="54" android:pivotY="54" android:rotation="{ROTATION}">\n'
    )


def android_color_paths(indent: str = "        ") -> str:
    return "\n".join(
        f'{indent}<path android:fillColor="{color}" android:pathData="{data}"/>'
        for data, color in COLOR_PATHS
    )


def write_vector(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    ET.parse(path)


def adaptive_xml(monochrome: bool = False) -> str:
    extra = '\n    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />' if monochrome else ""
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f'<adaptive-icon xmlns:android="{ANDROID_NS}">\n'
        '    <background android:drawable="@drawable/ic_launcher_background" />\n'
        '    <foreground android:drawable="@drawable/ic_launcher_foreground" />'
        f'{extra}\n'
        '</adaptive-icon>\n'
    )


def main() -> None:
    DESIGN.mkdir(parents=True, exist_ok=True)

    write_svg(
        DESIGN / "phormi-icon.svg",
        f'  <rect width="108" height="108" fill="{COBALT}"/>\n'
        f'  <g transform="{svg_transform(FULL_SCALE)}">\n'
        f'{color_svg_paths()}\n'
        '  </g>',
    )
    write_svg(
        DESIGN / "phormi-foreground.svg",
        f'  <g transform="{svg_transform(ADAPTIVE_SCALE)}">\n'
        f'{color_svg_paths()}\n'
        '  </g>',
    )
    write_svg(
        DESIGN / "phormi-monochrome.svg",
        f'  <g transform="{svg_transform(ADAPTIVE_SCALE)}">\n'
        f'    <path fill="#FFFFFF" fill-rule="evenodd" d="{MONO_PATH}"/>\n'
        '  </g>',
    )

    write_vector(
        RES / "drawable/ic_launcher_background.xml",
        vector_start()
        + f'    <path android:fillColor="{COBALT}" android:pathData="M0,0h108v108h-108z"/>\n'
        + '</vector>\n',
    )
    write_vector(
        RES / "drawable/ic_launcher_foreground.xml",
        vector_start()
        + '    <!-- Original Phormi page-creature. Scaled to stay inside the adaptive safe zone. -->\n'
        + android_group(ADAPTIVE_SCALE)
        + android_color_paths()
        + '\n    </group>\n</vector>\n',
    )
    write_vector(
        RES / "drawable/ic_launcher_monochrome.xml",
        vector_start()
        + '    <!-- Alpha silhouette for Android 13+ themed icons; the system supplies the tint. -->\n'
        + android_group(ADAPTIVE_SCALE)
        + f'        <path android:fillColor="#FFFFFFFF" android:fillType="evenOdd" android:pathData="{MONO_PATH}"/>\n'
        + '    </group>\n</vector>\n',
    )
    write_vector(
        RES / "drawable/ic_phormi_logo.xml",
        vector_start("48dp")
        + '    <!-- Full-bleed brand tile for in-app surfaces that do not apply a launcher mask. -->\n'
        + '    <path android:fillColor="' + COBALT + '" android:pathData="M24,0H84C97.25,0 108,10.75 108,24V84C108,97.25 97.25,108 84,108H24C10.75,108 0,97.25 0,84V24C0,10.75 10.75,0 24,0Z"/>\n'
        + android_group(FULL_SCALE)
        + android_color_paths()
        + '\n    </group>\n</vector>\n',
    )
    write_vector(
        RES / "drawable/ic_phormi_notification.xml",
        vector_start("24dp")
        + '    <!-- Notification small icons must be alpha-only; never use the full-color launcher tile. -->\n'
        + android_group(FULL_SCALE)
        + f'        <path android:fillColor="#FFFFFFFF" android:fillType="evenOdd" android:pathData="{MONO_PATH}"/>\n'
        + '    </group>\n</vector>\n',
    )

    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        write_vector(RES / "mipmap-anydpi-v26" / name, adaptive_xml(False))
        write_vector(RES / "mipmap-anydpi-v33" / name, adaptive_xml(True))

    print("Generated Phormi icon SVG masters and Android vector/adaptive resources.")
    print("PNG exports live in design/icon and app/src/main/res/mipmap-*; see docs/APP_ICON.md.")


if __name__ == "__main__":
    main()
