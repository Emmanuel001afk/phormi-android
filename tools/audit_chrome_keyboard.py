#!/usr/bin/env python3
"""Guardrails for browser chrome organisation and keyboard sizing/lettering."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/uong/phormi"
RES = ROOT / "app/src/main/res"
errors = []

def read(path):
    try: return path.read_text()
    except OSError as exc:
        errors.append(f"cannot read {path.relative_to(ROOT)}: {exc}")
        return ""

main = read(SRC / "MainActivity.kt")
chrome = read(SRC / "PhormiBrowserChrome.kt")
metrics = read(SRC / "PhormiKeyboardMetrics.kt")
service = read(SRC / "PhormiKeyboardServiceV2.kt")
resize = read(SRC / "PhormiKeyboardResizeOverlay.kt")
prefs = read(SRC / "PhormiKeyboardPreferences.kt")
settings = read(SRC / "PhormiBrowserSettingsActivity.kt")
kbsettings = read(SRC / "PhormiKeyboardSettingsActivity.kt")

for token in ("PhormiBrowserChrome.resolve", "currentChromeState", "btn_top_back", "btn_top_forward"):
    if token not in main: errors.append(f"MainActivity must use {token}")
manifest = read(ROOT / "app/src/main/AndroidManifest.xml")
if 'android:screenOrientation="portrait"' in manifest or 'android:screenOrientation="sensorPortrait"' in manifest:
    errors.append("Phormi screens must not be locked to portrait")
if '.MainActivity' not in manifest or 'android:screenOrientation="fullUser"' not in manifest:
    errors.append("MainActivity must follow the user's rotation preference")
if "if (::startPageContainer.isInitialized) updateHomeChromeVisibility()" not in main:
    errors.append("MainActivity must recompute chrome ownership after rotation")
for token in ("topBack = navInTop", "bottom = browsing && state.bottomEnabled", "topWithoutBottomSummary"):
    if token not in chrome: errors.append(f"chrome policy missing {token}")
if "Back and Forward move into the top bar" not in settings:
    errors.append("browser settings must explain top-bar navigation when bottom bar is off")

ns = "{http://schemas.android.com/apk/res/android}"
layout = ET.parse(RES / "layout/activity_main.xml")
nodes = {e.get(ns + "id", "").removeprefix("@+id/"): e for e in layout.iter() if e.get(ns + "id", "").startswith("@+id/")}
for name in ("top_toolbar", "bottom_toolbar"):
    value = nodes.get(name, {}).get(ns + "layout_height", "")
    m = re.fullmatch(r"(\d+)dp", value)
    if not m or int(m.group(1)) > 48: errors.append(f"{name} must stay <=48dp; found {value}")
ids = [e.get(ns + "id", "").removeprefix("@+id/") for e in layout.iter() if e.get(ns + "id", "").startswith("@+id/")]
for earlier, later in (("btn_home", "btn_top_back"), ("btn_top_back", "btn_top_forward"), ("btn_top_forward", "url_toolbar"), ("url_toolbar", "tab_count"), ("tab_count", "btn_menu")):
    if earlier in ids and later in ids and ids.index(earlier) > ids.index(later):
        errors.append(f"top bar order must place {earlier} before {later}")

for token in ("PhormiKeyboardMetrics.windowHeightDp", "PhormiKeyboardMetrics.keyTextSp", "keyTextSize(", "keyTextScale"):
    if token not in service: errors.append(f"keyboard service must use {token}")
if "PhormiKeyboardMetrics.baseHeightDp" not in resize:
    errors.append("resize overlay must use the shared keyboard base height")
if "private const val TEXT_SCALE" not in prefs or "fun keyTextScale" not in prefs:
    errors.append("keyboard preferences must persist key lettering scale")
if "Key lettering" not in kbsettings or "Height" not in kbsettings:
    errors.append("keyboard settings must expose height and lettering controls")
if "baseHeight(): Int = 312" in service or "val keyboardBase = dp(312f)" in resize:
    errors.append("keyboard must not return to one fixed 312dp base height")

for name in ("PhormiBrowserChromeTest.kt", "PhormiKeyboardMetricsTest.kt"):
    if not (ROOT / "app/src/test/java/com/uong/phormi" / name).is_file():
        errors.append(f"missing unit test {name}")
if not (ROOT / "app/src/androidTest/java/com/uong/phormi/ChromeKeyboardSmokeTest.kt").is_file():
    errors.append("missing ChromeKeyboardSmokeTest device coverage")
if "ChromeKeyboardSmokeTest" not in read(ROOT / ".github/workflows/code-quality.yml"):
    errors.append("device-smoke must run ChromeKeyboardSmokeTest")
for workflow in (".github/workflows/code-quality.yml", ".github/workflows/build-apk.yml"):
    if "audit_chrome_keyboard.py" not in read(ROOT / workflow):
        errors.append(f"{workflow} must run audit_chrome_keyboard.py")

if errors:
    print("PHORMI CHROME/KEYBOARD AUDIT: FAIL")
    for error in errors: print(" -", error)
    sys.exit(1)
print("PHORMI CHROME/KEYBOARD AUDIT: PASS (top/bottom organisation, adaptive keyboard geometry and lettering)")
