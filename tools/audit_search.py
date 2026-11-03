#!/usr/bin/env python3
"""Static guardrails for the polished Phormi Search results experience.

This is intentionally source-only. It does not replace Gradle unit tests,
Android lint, or a device/emulator pass.
"""
from __future__ import annotations

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/uong/phormi"
ASSETS = ROOT / "app/src/main/assets/search"
TESTS = ROOT / "app/src/test/java/com/uong/phormi"
errors: list[str] = []


def read(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError as exc:
        errors.append(f"cannot read {path.relative_to(ROOT)}: {exc}")
        return ""


main = read(SRC / "MainActivity.kt")
renderer = read(SRC / "PhormiSearchRenderer.kt")
providers = read(SRC / "PhormiSearchProviders.kt")
urls = read(SRC / "PhormiSearchUrls.kt")
ranking = read(SRC / "PhormiSearchRanking.kt")
text = read(SRC / "PhormiSearchText.kt")
template = read(ASSETS / "results.html")
css = read(ASSETS / "results.css")
script = read(ASSETS / "results.js")

# The results document must be asset-driven and use the app palette, not a raw inline dump.
for name in ("results.html", "results.css", "results.js"):
    if not (ASSETS / name).is_file():
        errors.append(f"missing search asset: {name}")
for token in ("{{TITLE}}", "{{QUERY}}", "{{PALETTE}}", "{{CSS}}", "{{SCRIPT}}", "{{CONTENT}}"):
    if token not in template:
        errors.append(f"search template missing {token}")
if "PhormiSearchRenderer.render" not in main or "search/results.html" not in main:
    errors.append("MainActivity must render search through PhormiSearchRenderer and packaged assets")
for raw in ("source-badge", "Collecting $completed", "Unified results ·", "toggleSources"):
    if raw in main:
        errors.append(f"raw search UI marker still present: {raw}")
for token in ("--bg", "--surface", "--text", "--accent", "--border", "prefers-reduced-motion"):
    if token not in css:
        errors.append(f"search CSS missing {token}")
if re.search(r"https?://", template.replace("https://phormi.local", "")):
    errors.append("search template must not load remote assets")
if "eval(" in script or "innerHTML" in script:
    errors.append("search script must not use eval or innerHTML")

# Safety and behavior contracts that the polish pass must preserve.
if "multi_engine_consent_v1" not in main:
    errors.append("multi-engine consent gate is missing")
if "connectTimeout = 6000" not in main or "readTimeout = 8000" not in main:
    errors.append("provider timeout contract changed")
if "2_000_000" not in main:
    errors.append("provider response cap is missing")
if "unifiedSearchGenerations[tab.id] != generation" not in main:
    errors.append("per-tab search generation guard is missing")
if "ai-answer-text" not in main or "ai-answer-text" not in renderer:
    errors.append("AI answer must inject into the text node, not replace the section heading")
if "/search" not in main or "/refresh" not in main:
    errors.append("local search/refresh routes are missing")
if "PhormiSearchUrls.webUrlOrNull" not in renderer:
    errors.append("renderer must filter unsafe result URLs")
if "escape(" not in renderer:
    errors.append("renderer must escape provider-controlled text")
if "PhormiSearchRanking.merge" not in main:
    errors.append("MainActivity must use the testable ranking helper")
if "PhormiSearchProviders.all" not in main:
    errors.append("MainActivity must use the shared provider list")

# JVM coverage for the new pure helpers.
for name in (
    "PhormiSearchRendererTest.kt",
    "PhormiSearchUrlsTest.kt",
    "PhormiSearchTextTest.kt",
    "PhormiSearchRankingTest.kt",
    "PhormiSearchProvidersTest.kt",
):
    if not (TESTS / name).is_file():
        errors.append(f"missing search test: {name}")

if errors:
    print("PHORMI SEARCH AUDIT: FAIL")
    for error in errors:
        print(" -", error)
    sys.exit(1)
print("PHORMI SEARCH AUDIT: PASS (asset-driven results, consent/timeouts/cap, tab guards, safe rendering, JVM tests)")
