#!/usr/bin/env python3
"""Expose actionable CI diagnostics as check annotations (also readable through the GitHub API)."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

lines = Path(sys.argv[1]).read_text(errors="replace").splitlines()
messages = []
for index, line in enumerate(lines):
    if re.search(r"^e: |error:|What went wrong:|Could not resolve|Could not find|Execution failed|FAILED$", line):
        messages.extend(lines[index:index + (10 if "What went wrong:" in line else 1)])
for path in Path("app/build/test-results").glob("**/TEST-*.xml"):
    root = ET.parse(path).getroot()
    for case in root.findall("testcase"):
        for failure in case.findall("failure"):
            messages.append(f"{case.get('classname')}.{case.get('name')}: {failure.get('message')}\n{(failure.text or '')[:2500]}")
if not messages:
    messages = lines[-45:]
message = "\n".join(dict.fromkeys(messages))[-16000:]
message = message.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
print(f"::error title=Android build/test diagnostics::{message}")
