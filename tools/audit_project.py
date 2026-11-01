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
manifest = None

sources = [
    "MainActivity.kt", "MenuActivity.kt", "AccountsActivity.kt", "AiActivity.kt",
    "AiController.kt", "PhormiAiPendingTask.kt", "PhormiAiReminderReceiver.kt", "PhormiAiBootReceiver.kt",
    "PhormiBrowserAiSafety.kt", "PhormiBrowserSettingsActivity.kt",
    "PhormiBrowserPreferences.kt", "PhormiRepairApplication.kt",
    "DownloadsActivity.kt", "PhormiDownloadEngine.kt",
    "PhormiKeyboardServiceV2.kt", "PhormiKeyboardTextEngine.kt",
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
    for name in (".MainActivity", ".MenuActivity", ".AiActivity", ".PhormiAiReminderReceiver", ".PhormiAiBootReceiver",
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

for name in ("MainActivity.kt", "MenuActivity.kt", "AiActivity.kt", "DownloadsActivity.kt",
             "AccountsActivity.kt", "PhormiBrowserSettingsActivity.kt"):
    path = SRC / name
    if not path.exists():
        continue
    references = re.findall(r"(?<!android\.)R\.id\.(\w+)", path.read_text())
    for missing in sorted(set(references) - ids):
        errors.append(f"{name} references unknown R.id.{missing}")

menu = SRC / "MenuActivity.kt"
menu_xml = RES / "layout/activity_menu.xml"
if menu.is_file() and menu_xml.is_file():
    wired = set(re.findall(r"wire\(R\.id\.(menu_\w+)\)", menu.read_text()))
    menu_ids = {
        e.get(android_ns + "id", "").removeprefix("@+id/")
        for e in ET.parse(menu_xml).iter()
        if e.get(android_ns + "id", "").startswith("@+id/menu_")
    }
    page_ids = {"menu_browse_page", "menu_features_page", "menu_settings_page",
                "menu_tab_browse", "menu_tab_features", "menu_tab_settings"}
    for missing in sorted(menu_ids - wired - page_ids):
        errors.append(f"menu item has no click handler: {missing}")

for language in ("en", "fr", "es", "pt", "de", "it", "id", "tr"):
    asset = ROOT / f"app/src/main/assets/keyboard/words_{language}.txt"
    if not asset.is_file() or asset.stat().st_size < 1000:
        errors.append(f"missing/empty keyboard dictionary: {language}")

# --- Regression guards (added with the Chrome/Gboard fix pass) -----------------
kotlin_paths = sorted(SRC.rglob("*.kt"))
kotlin_text = "\n".join(p.read_text(errors="replace") for p in kotlin_paths)
manifest_root = manifest.getroot() if manifest is not None else None

# Every declared component must map to an actual Kotlin/Java class declaration.
for kind in ("activity", "service", "receiver"):
    for element in (manifest_root.iter(kind) if manifest_root is not None else []):
        name = element.get(android_ns + "name", "")
        if not name.startswith("."):
            continue
        simple = name[1:].split(".")[-1]
        if not re.search(r"\b(?:class|object)\s+" + re.escape(simple) + r"\b", kotlin_text):
            errors.append(f"manifest {kind} has no class declaration: {name}")

# Every R.id reference, in every Kotlin file, must resolve to a real resource ID.
for path in kotlin_paths:
    for ref in sorted(set(re.findall(r"(?<!android\.)R\.id\.(\w+)", path.read_text(errors="replace")))):
        if ref not in ids:
            errors.append(f"{path.name} references unknown R.id.{ref}")

# IME subtypes must be Latin-script (the only layouts that exist) and ASCII capable.
ime_path = RES / "xml/method_phormi_keyboard.xml"
locales_path = SRC / "PhormiKeyboardLocales.kt"
if ime_path.is_file() and locales_path.is_file():
    block = re.search(r"latinLanguages[^=]*=\s*setOf\((.*?)\)", locales_path.read_text(), re.S)
    latin = set(re.findall(r'"(\w+)"', block.group(1))) if block else set()
    for subtype in ET.parse(ime_path).iter("subtype"):
        locale = subtype.get(android_ns + "imeSubtypeLocale", "")
        language = locale.split("_")[0].lower()
        if language not in latin:
            errors.append(f"IME subtype advertises an unsupported script: {locale}")
        if subtype.get(android_ns + "isAsciiCapable") != "true":
            errors.append(f"IME subtype is not ASCII capable: {locale}")

# App-owned network calls must use TLS (loopback development endpoints excepted).
for path in kotlin_paths:
    for line_no, line in enumerate(path.read_text(errors="replace").splitlines(), 1):
        for candidate in re.findall(r'https?://[^\s"\')]+', line):
            if candidate.startswith("http://") and not (
                "localhost" in candidate or "127.0.0.1" in candidate
            ):
                errors.append(f"{path.name}:{line_no} plain HTTP endpoint: {candidate[:64]}")

if 'getDeclaredField("Companion")' in kotlin_text or 'getDeclaredField("instance")' in kotlin_text:
    errors.append("reflection into private IME Companion.instance is still present")
if (SRC / "PhormiKeyboardRenderCompat.kt").exists():
    errors.append("dead shadowed render() compat layer is still present")
application = manifest_root.find("application") if manifest_root is not None else None
if application is not None and application.get(android_ns + "usesCleartextTraffic") == "true":
    errors.append("cleartext traffic is globally enabled")

if errors:
    print("PHORMI SOURCE AUDIT: FAIL")
    for error in errors:
        print(" -", error)
    sys.exit(1)
print(f"PHORMI SOURCE AUDIT: PASS ({len(sources)} components, {len(layouts)} layouts, {len(ids)} resource IDs, 8 dictionaries)")
