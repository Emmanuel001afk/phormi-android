#!/usr/bin/env python3
"""Source guardrails for sectioned tabs, navigation identity and compact chrome."""
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
overview = read(SRC / "TabsOverviewActivity.kt")
helper = read(SRC / "PhormiTabOverview.kt")
ns = "{http://schemas.android.com/apk/res/android}"
layout = ET.parse(RES / "layout/activity_main.xml")
nodes = {e.get(ns + "id", "").removeprefix("@+id/"): e for e in layout.iter() if e.get(ns + "id", "").startswith("@+id/")}
for name, maximum in (("top_toolbar", 48), ("bottom_toolbar", 48)):
    value = nodes.get(name, {}).get(ns + "layout_height", "")
    match = re.fullmatch(r"(\d+)dp", value)
    if not match or int(match.group(1)) > maximum:
        errors.append(f"{name} must stay <= {maximum}dp; found {value}")
if 'if (onHome || !wide) View.GONE else View.VISIBLE' in main:
    errors.append("Home must not be hidden on phones")
if "PhormiTabOverview.navigationUrl(deferredUrl, lastKnownUrl, webView?.url)" not in main:
    errors.append("tab URL must not let a temporary about:blank erase the requested destination")
if main.count("saveTabsImmediate(true)") < 2 or "recordTabNavigation(tab, url)" not in main:
    errors.append("overview snapshots and omnibox navigation must persist tab identity immediately")
for token in ("Current tab", "Other tabs", "New tabs", "Ghost tabs"):
    if token not in helper:
        errors.append(f"missing overview section: {token}")
for token in ("PhormiTabOverview.sections", "PhormiTabOverview.filter", "item_overview_tab", "item_overview_section"):
    if token not in overview:
        errors.append(f"overview must use {token}")
for name in ("PhormiTabOverviewTest.kt",):
    if not (ROOT / "app/src/test/java/com/uong/phormi" / name).is_file():
        errors.append(f"missing unit test {name}")
if not (ROOT / "app/src/androidTest/java/com/uong/phormi/TabOverviewSmokeTest.kt").is_file():
    errors.append("missing device smoke test")
for workflow in (".github/workflows/code-quality.yml", ".github/workflows/build-apk.yml"):
    if "audit_tabs.py" not in read(ROOT / workflow):
        errors.append(f"{workflow} must run audit_tabs.py")
if errors:
    print("PHORMI TABS AUDIT: FAIL")
    for error in errors: print(" -", error)
    sys.exit(1)
print("PHORMI TABS AUDIT: PASS (sections, durable URL/title identity, compact chrome, phone Home)")
