#!/usr/bin/env python3
"""Static guardrails for the Phormi launcher icon and notification silhouette.

Run after tools/build_app_icon.py. This does not replace an Android build; it
catches broken resource wiring, missing density exports, and safe-zone drift.
"""
from __future__ import annotations

import math
from pathlib import Path
import re
import struct
import sys
import xml.etree.ElementTree as ET

import build_app_icon as icon

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
SRC = ROOT / "app/src/main/java/com/uong/phormi"
NS = "{http://schemas.android.com/apk/res/android}"
errors: list[str] = []


def check(condition: bool, message: str) -> None:
    if not condition:
        errors.append(message)


def parse(path: Path) -> ET.Element | None:
    try:
        return ET.parse(path).getroot()
    except (OSError, ET.ParseError) as exc:
        errors.append(f"invalid XML: {path.relative_to(ROOT)}: {exc}")
        return None


def png_size(path: Path) -> tuple[int, int] | None:
    try:
        with path.open("rb") as handle:
            header = handle.read(24)
        if header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR":
            errors.append(f"not a PNG: {path.relative_to(ROOT)}")
            return None
        return struct.unpack(">II", header[16:24])
    except OSError as exc:
        errors.append(f"missing PNG: {path.relative_to(ROOT)}: {exc}")
        return None


def sample_path(data: str) -> list[tuple[float, float]]:
    """Sample the absolute M/L/C/Z paths used by the mark."""
    tokens = re.findall(r"[MLCZ]|-?\d+(?:\.\d+)?", data)
    points: list[tuple[float, float]] = []
    i = 0
    cur = (0.0, 0.0)
    start = (0.0, 0.0)
    while i < len(tokens):
        command = tokens[i]
        i += 1
        if command == "M":
            cur = (float(tokens[i]), float(tokens[i + 1]))
            i += 2
            start = cur
            points.append(cur)
        elif command == "L":
            cur = (float(tokens[i]), float(tokens[i + 1]))
            i += 2
            points.append(cur)
        elif command == "C":
            p1 = (float(tokens[i]), float(tokens[i + 1]))
            p2 = (float(tokens[i + 2]), float(tokens[i + 3]))
            p3 = (float(tokens[i + 4]), float(tokens[i + 5]))
            i += 6
            for step in range(64):
                t = step / 63
                u = 1 - t
                points.append((
                    u**3 * cur[0] + 3 * u*u*t * p1[0] + 3 * u*t*t * p2[0] + t**3 * p3[0],
                    u**3 * cur[1] + 3 * u*u*t * p1[1] + 3 * u*t*t * p2[1] + t**3 * p3[1],
                ))
            cur = p3
        elif command == "Z":
            cur = start
        else:
            raise ValueError(f"unsupported path command: {command}")
    return points


def transformed(points: list[tuple[float, float]], scale: float, rotation: float) -> list[tuple[float, float]]:
    angle = math.radians(rotation)
    out = []
    for x, y in points:
        dx, dy = x - 54, y - 54
        rx = dx * math.cos(angle) - dy * math.sin(angle)
        ry = dx * math.sin(angle) + dy * math.cos(angle)
        out.append((54 + rx * scale, 54 + ry * scale))
    return out


manifest = parse(ROOT / "app/src/main/AndroidManifest.xml")
if manifest is not None:
    app = manifest.find("application")
    check(app is not None and app.get(NS + "icon") == "@mipmap/ic_launcher", "manifest must use @mipmap/ic_launcher")
    check(app is not None and app.get(NS + "roundIcon") == "@mipmap/ic_launcher_round", "manifest must use @mipmap/ic_launcher_round")

for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
    v26 = parse(RES / "mipmap-anydpi-v26" / name)
    v33 = parse(RES / "mipmap-anydpi-v33" / name)
    check(v26 is not None and v26.tag == "adaptive-icon", f"{name}: missing v26 adaptive icon")
    check(v33 is not None and v33.tag == "adaptive-icon", f"{name}: missing v33 adaptive icon")
    if v26 is not None:
        check(v26.find("background") is not None and v26.find("foreground") is not None, f"{name}: v26 layers incomplete")
        check(v26.find("monochrome") is None, f"{name}: monochrome must not ship in v26")
    if v33 is not None:
        mono = v33.find("monochrome")
        check(mono is not None and mono.get(NS + "drawable") == "@drawable/ic_launcher_monochrome", f"{name}: v33 monochrome layer missing")

for name in ("ic_launcher_background.xml", "ic_launcher_foreground.xml", "ic_launcher_monochrome.xml", "ic_phormi_notification.xml", "ic_phormi_logo.xml"):
    root = parse(RES / "drawable" / name)
    check(root is not None and root.tag == "vector", f"{name}: expected vector drawable")
    if root is not None:
        check(root.get(NS + "viewportWidth") == "108" and root.get(NS + "viewportHeight") == "108", f"{name}: expected 108dp viewport")

foreground = parse(RES / "drawable/ic_launcher_foreground.xml")
if foreground is not None:
    paths = foreground.findall(".//path")
    check(len(paths) == len(icon.COLOR_PATHS), "foreground path count drifted from the master mark")
    for element, (data, color) in zip(paths, icon.COLOR_PATHS):
        check(element.get(NS + "pathData") == data, "foreground path data drifted from tools/build_app_icon.py")
        check(element.get(NS + "fillColor") == color, "foreground palette drifted from tools/build_app_icon.py")
    group = foreground.find("group")
    check(group is not None and group.get(NS + "scaleX") == icon.ADAPTIVE_SCALE and group.get(NS + "scaleY") == icon.ADAPTIVE_SCALE, "foreground safe-zone scale changed")

mono = parse(RES / "drawable/ic_launcher_monochrome.xml")
if mono is not None:
    path = mono.find(".//path")
    check(path is not None and path.get(NS + "fillType") == "evenOdd", "monochrome must use evenOdd cutouts")
    check(path is not None and path.get(NS + "fillColor") == "#FFFFFFFF", "monochrome must be white alpha only")
    check(path is not None and path.get(NS + "pathData") == icon.MONO_PATH, "monochrome silhouette drifted from the master mark")

points = transformed(sample_path(icon.BODY), float(icon.ADAPTIVE_SCALE), float(icon.ROTATION))
radius = max(math.hypot(x - 54, y - 54) for x, y in points)
check(radius <= 33.0, f"adaptive foreground exceeds the 66dp safe circle: radius {radius:.2f}")

full_points = transformed(sample_path(icon.BODY), float(icon.FULL_SCALE), float(icon.ROTATION))
check(all(0 <= x <= 108 and 0 <= y <= 108 for x, y in full_points), "full-bleed mark exceeds the 108dp canvas")

for dpi, size in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
    for name in ("ic_launcher.png", "ic_launcher_round.png"):
        path = RES / f"mipmap-{dpi}" / name
        check(png_size(path) == (size, size), f"{path.relative_to(ROOT)} must be {size}x{size}")

check(png_size(ROOT / "design/icon/phormi-play-store-512.png") == (512, 512), "Play Store icon must be 512x512")
check(png_size(ROOT / "design/icon/phormi-icon-1024.png") == (1024, 1024), "master PNG must be 1024x1024")

for name in ("PhormiNotificationCenter.kt", "PhormiNotificationManager.kt", "PhormiDownloadEngine.kt"):
    text = (SRC / name).read_text(encoding="utf-8")
    check("R.drawable.ic_phormi_notification" in text, f"{name} must use the alpha notification silhouette")
    check("setSmallIcon(applicationInfo.icon)" not in text, f"{name} must not use the launcher tile as a small icon")

for path in RES.rglob("*.xml"):
    check("app_logo" not in path.read_text(encoding="utf-8"), f"stale app_logo reference in {path.relative_to(ROOT)}")
check(not (RES / "drawable-nodpi/app_logo.png").exists(), "old raster app_logo.png should be removed")

if errors:
    print("PHORMI APP ICON AUDIT: FAIL")
    for error in errors:
        print(" -", error)
    sys.exit(1)
print(f"PHORMI APP ICON AUDIT: PASS (adaptive safe radius {radius:.2f}dp, 5 density exports, themed + notification layers)")
