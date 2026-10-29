#!/usr/bin/env python3
"""Cheap source/resource sanity checks. Run before Gradle, not instead of an Android build."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/uong/phormi"
RES = ROOT / "app/src/main/res"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
errors: list[str] = []

sources = [
    "MainActivity.kt", "BrowserMenuPopup.kt", "BrowserMenuActions.kt", "AccountsActivity.kt", "AiActivity.kt",
    "AiController.kt", "PhormiAiPendingTask.kt", "PhormiAiReminderReceiver.kt", "PhormiAiBootReceiver.kt",
    "PhormiBrowserAiSafety.kt", "PhormiBrowserSettingsActivity.kt",
    "PhormiBrowserPreferences.kt", "PhormiRepairApplication.kt",
    "DownloadsActivity.kt", "PhormiDownloadEngine.kt",
    "PhormiKeyboardServiceV2.kt", "PhormiKeyboardTextEngine.kt",
    "PhormiKeyboardOnlineDictionary.kt", "PhormiKeyboardCorrectionPolicy.kt",
    "PhormiKeyboardDictionary.kt", "PhormiKeyboardEmoji.kt", "PhormiKeyboardAiContext.kt",
    "PhormiKeyboardSystemSpellChecker.kt", "TabsOverviewActivity.kt",
    "TabGroupsActivity.kt", "GhostActivity.kt", "BookmarksActivity.kt", "HistoryActivity.kt",
]
for name in sources:
    if not (SRC / name).is_file():
        errors.append(f"missing source: {name}")

android_ns = "{http://schemas.android.com/apk/res/android}"
try:
    manifest = ET.parse(MANIFEST)
    registered = {
        element.get(android_ns + "name")
        for kind in ("activity", "service", "receiver")
        for element in manifest.iter(kind)
    }
    for name in (".MainActivity", ".AiActivity", ".PhormiAiReminderReceiver", ".PhormiAiBootReceiver",
                 ".PhormiBrowserSettingsActivity", ".PhormiKeyboardServiceV2",
                 ".PhormiDownloadService", ".DownloadsActivity", ".AccountsActivity"):
        if name not in registered:
            errors.append(f"manifest missing component: {name}")
    ghost = next((e for e in manifest.iter("activity") if e.get(android_ns + "name") == ".GhostActivity"), None)
    if ghost is None or ghost.get(android_ns + "process") != ":ghost":
        errors.append("GhostActivity must be isolated in :ghost")
except (OSError, ET.ParseError) as exc:
    errors.append(f"AndroidManifest.xml invalid: {exc}")

ids: set[str] = set()
layouts = list((RES / "layout").glob("*.xml"))
for path in (*layouts, *(RES / "xml").glob("*.xml"), *(RES / "values").glob("*.xml")):
    try:
        tree = ET.parse(path)
        for element in tree.iter():
            value = element.get(android_ns + "id", "")
            if value.startswith("@+id/"):
                ids.add(value.removeprefix("@+id/"))
    except ET.ParseError as exc:
        errors.append(f"invalid XML: {path.relative_to(ROOT)}: {exc}")

for name in ("MainActivity.kt", "BrowserMenuPopup.kt", "AiActivity.kt", "DownloadsActivity.kt",
             "AccountsActivity.kt", "PhormiBrowserSettingsActivity.kt"):
    path = SRC / name
    if not path.exists():
        continue
    references = re.findall(r"(?<!android\.)R\.id\.(\w+)", path.read_text())
    for missing in sorted(set(references) - ids):
        errors.append(f"{name} references unknown R.id.{missing}")

popup_path = SRC / "BrowserMenuPopup.kt"
router_path = SRC / "BrowserMenuActions.kt"
main_path = SRC / "MainActivity.kt"
if popup_path.is_file() and router_path.is_file() and main_path.is_file():
    popup = popup_path.read_text()
    router = router_path.read_text()
    main = main_path.read_text()
    actions = set(re.findall(r"BrowserMenuActions\.([A-Z_]+)", popup))
    defined = set(re.findall(r"const val ([A-Z_]+) =", router))
    routed = set(re.findall(r"BrowserMenuActions\.([A-Z_]+)\s*->", main))
    for name in sorted(actions - defined):
        errors.append(f"menu action has no constant: {name}")
    for name in sorted(actions - routed):
        errors.append(f"menu action has no browser handler: {name}")
    browsing = popup.split('group(sections, "Browsing"', 1)[-1].split('group(sections, "Library & tabs"', 1)[0]
    if "BrowserMenuActions.GHOST" not in browsing:
        errors.append("Ghost mode must live under Browsing")
    if "dispatch(BrowserMenuActions.SETTINGS)" not in popup or "showBrowserMenu(it)" not in main:
        errors.append("the browser menu must open over the tab with Settings inside it")
    if "MenuActivity" in main or ".MenuActivity" in MANIFEST.read_text():
        errors.append("obsolete three-page menu must not be launched")

for language in ("en", "fr", "es", "pt", "de", "it", "id", "tr"):
    asset = ROOT / f"app/src/main/assets/keyboard/words_{language}.txt"
    if not asset.is_file() or asset.stat().st_size < 1000:
        errors.append(f"missing/empty keyboard dictionary: {language}")

if errors:
    print("PHORMI SOURCE AUDIT: FAIL")
    for error in errors:
        print(" -", error)
    sys.exit(1)
print(f"PHORMI SOURCE AUDIT: PASS ({len(sources)} components, {len(layouts)} layouts, {len(ids)} resource IDs, 8 dictionaries)")
