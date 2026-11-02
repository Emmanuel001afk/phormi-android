#!/usr/bin/env python3
"""Expose fatal lint findings as check annotations when hosted log downloads are blocked."""
from pathlib import Path
import xml.etree.ElementTree as ET

for path in Path('app/build/reports').glob('lint-results*.xml'):
    for issue in ET.parse(path).iter('issue'):
        if issue.get('severity') not in ('Error', 'Fatal'):
            continue
        location = issue.find('location')
        where = '' if location is None else f"{location.get('file', '')}:{location.get('line', '')}: "
        message = (where + issue.get('message', '')).replace('%', '%25').replace('\n', '%0A').replace('\r', '%0D')
        print(f"::error title=Lint {issue.get('id')}::{message}")
