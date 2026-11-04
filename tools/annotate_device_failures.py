#!/usr/bin/env python3
"""Expose connected-test XML/compiler failures as check annotations (logs may be unreachable)."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

for path in Path('app/build/outputs/androidTest-results').rglob('*.xml'):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    for case in root.iter('testcase'):
        for tag in ('failure', 'error'):
            failure = case.find(tag)
            if failure is None:
                continue
            name = f"{case.get('classname')}.{case.get('name')}"
            message = ' '.join((failure.get('message') or failure.text or '').split())[:1200]
            print(f"::error title=Failing device test::{name}: {message}")
log = Path('/tmp/phormi-device-tests.log')
if log.is_file():
    for line in log.read_text(errors='replace').splitlines():
        if re.search(r'^e: |^> Task .*FAILED|^FAILURE:|^\* What went wrong:|^Caused by:', line):
            print('::error title=Device test build::' + line[:1000])
