#!/usr/bin/env python3
"""Make successful test counts visible in API-readable check annotations."""
from pathlib import Path
import xml.etree.ElementTree as ET

reports = list(Path('app/build/test-results').glob('**/TEST-*.xml')) + list(Path('app/build/outputs/androidTest-results').glob('**/TEST-*.xml'))
tests = failures = errors = skipped = 0
for report in reports:
    root = ET.parse(report).getroot()
    tests += int(root.get('tests', 0))
    failures += int(root.get('failures', 0))
    errors += int(root.get('errors', 0))
    skipped += int(root.get('skipped', 0))
if tests:
    print(f'::notice title=Android regression results::{tests} tests; {failures} failures; {errors} errors; {skipped} skipped.')
