#!/usr/bin/env python3
"""Static guardrails for the one-tap desktop-site toggle.

This is intentionally source-only. It does not replace Gradle unit tests,
Android lint, or a device/emulator pass.
"""
from __future__ import annotations

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/uong/phormi"
TESTS = ROOT / "app/src/test/java/com/uong/phormi"
errors: list[str] = []


def read(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError as exc:
        errors.append(f"cannot read {path.relative_to(ROOT)}: {exc}")
        return ""


main = read(SRC / "MainActivity.kt")
policy = read(SRC / "PhormiDesktopMode.kt")
menu = read(SRC / "MenuActivity.kt")
settings = read(SRC / "PhormiBrowserSettingsActivity.kt")

match = re.search(r"private fun toggleDesktopMode\(\) \{.*?\n    \}\n\n    fun handleCentralHubCommand", main, re.S)
if not match:
    errors.append("cannot locate toggleDesktopMode()")
else:
    toggle = match.group(0)
    if "AlertDialog.Builder" in toggle or ".setItems(" in toggle:
        errors.append("Desktop site must be a direct toggle, not a scope chooser dialog")
    for token in ("setDesktopModeForHost(host, next)", "target.reload()", "Desktop site $state for $host"):
        if token not in toggle:
            errors.append(f"desktop toggle missing {token}")

# The viewport must be the device's real CSS width. Any hard-coded desktop slab
# (1280px and friends) scales text down until it is unreadable and pushes fixed
# or bottom-aligned page elements off-screen - the reported desktop-mode bug.
if "window.screen.width" not in policy:
    errors.append("viewport script must derive the layout width from the real screen")
if re.search(r"width\s*=\s*\d{3,5}\b", policy) or "DESKTOP_VIEWPORT_WIDTH" in policy:
    errors.append("a fixed desktop-width viewport scales text down until it is unreadable")
if "initial-scale=1" not in policy:
    errors.append("desktop viewport must render at scale 1")
if "loadWithOverviewMode = true" not in main:
    errors.append("desktop pages must use overview scaling so the wide layout fits the screen")
if "loadWithOverviewMode = !enabled" in main or "loadWithOverviewMode = !desktop" in main:
    errors.append("do not disable overview scaling for desktop pages")

for token in ("KEY_GLOBAL", "KEY_POLICY_DIRTY", "Use desktop sites by default"):
    if token not in settings:
        errors.append(f"Browser settings missing global desktop control: {token}")
if "PhormiDesktopMode.KEY_POLICY_DIRTY" not in main or "applyDesktopModeToAllTabs(reloadLiveSites = true)" not in main:
    errors.append("MainActivity must reload live tabs after a global desktop-policy change")
if "PhormiDesktopMode.menuLabel(desktopEnabled)" not in menu:
    errors.append("menu must show the current desktop-site state")
if not (TESTS / "PhormiDesktopModeTest.kt").is_file():
    errors.append("missing PhormiDesktopModeTest.kt")

for workflow in (".github/workflows/code-quality.yml", ".github/workflows/build-apk.yml"):
    if "audit_desktop.py" not in read(ROOT / workflow):
        errors.append(f"{workflow} must run audit_desktop.py")

if errors:
    print("PHORMI DESKTOP AUDIT: FAIL")
    for error in errors:
        print(" -", error)
    sys.exit(1)
print("PHORMI DESKTOP AUDIT: PASS (direct toggle, desktop viewport, global setting, reload contract)")
