#!/usr/bin/env python3
"""Expose connected-test results as annotations; --strict fails only on real test/build failures."""
from pathlib import Path
import argparse
import re
import sys
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('--strict', action='store_true')
args = parser.parse_args()
failures = 0
tests = 0
for path in Path('app/build/outputs/androidTest-results').rglob('*.xml'):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    suites = [root] if root.tag == 'testsuite' else list(root.iter('testsuite'))
    for suite in suites:
        tests += int(suite.get('tests', 0) or 0)
        failures += int(suite.get('failures', 0) or 0) + int(suite.get('errors', 0) or 0)
    for case in root.iter('testcase'):
        for tag in ('failure', 'error'):
            failure = case.find(tag)
            if failure is None:
                continue
            name = f"{case.get('classname')}.{case.get('name')}"
            message = ' '.join((failure.get('message') or failure.text or '').split())[:1200]
            print(f"::error title=Failing device test::{name}: {message}")
log = Path('/tmp/phormi-device-tests.log')
text = log.read_text(errors='replace') if log.is_file() else ''
if text:
    for line in text.splitlines():
        if re.search(r'^e: |^> Task .*FAILED|^FAILURE:|^\* What went wrong:|^Caused by:', line):
            print('::error title=Device test build::' + line[:1000])
    for line in [line for line in text.splitlines() if re.search(r'GRADLE_RESULT|SCRIPT_DONE|Tests on|BUILD SUCCESSFUL|BUILD FAILED|screenshot pull', line)][-20:]:
        print('::notice title=Device test log::' + line[:1000])
else:
    print('::error title=Device test log::/tmp/phormi-device-tests.log was not created')
print(f"::notice title=Device test summary::{tests} tests, {failures} failures/errors")
build_failed = 'BUILD FAILED' in text or 'GRADLE_RESULT=0' not in text
if args.strict and (failures or tests == 0 or build_failed):
    sys.exit(1)
