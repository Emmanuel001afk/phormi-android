#!/usr/bin/env python3
"""Compatibility entry point for real structural + DOM checks, not substring-based feature claims.

Android build, Robolectric regression tests, lint and optional emulator integration
remain in .github/workflows/validate.yml. This script does not claim device coverage.
"""
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
subprocess.run([sys.executable, "tools/validate_resources.py"], cwd=root, check=True)
tests = sorted(str(path.relative_to(root)) for path in (root / "tools/tests").glob("*.test.mjs"))
subprocess.run(["node", "--test", *tests], cwd=root, check=True)
print("Offline checks passed. Run Gradle validation for Android compilation, regressions and lint.")
