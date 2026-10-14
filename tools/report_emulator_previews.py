#!/usr/bin/env python3
"""Bounded fixture-only previews for restricted CI artifact reviewers.

Checks truncate individual messages at 4 KiB, so previews use numbered chunks.
Full PNG/XML evidence remains in the normal artifact; no real user data is read.
"""
from pathlib import Path
import base64
import json
import xml.etree.ElementTree as ET

root = Path('app/build/reports/smoke')
for xml in root.glob('**/*typing-failure.xml'):
    try:
        keys = [{k: node.get(k) for k in ('text', 'bounds', 'clickable', 'enabled', 'focused')}
                for node in ET.parse(xml).iter('node') if node.get('text') in ('⌫', 'Space', '↵', 'Hello the ')]
        print('::notice title=Keyboard hit regions::PHORMI_BOUNDS|' + json.dumps(keys, ensure_ascii=False))
    except (ET.ParseError, OSError):
        pass
preferred = ['phormi-typing-failure', 'phormi-browser-keyboard-smoke', 'phormi-emoji-smoke']
for name in preferred:
    paths = list(root.glob(f'**/{name}-preview.jpg'))
    if not paths:
        continue
    encoded = base64.b64encode(paths[0].read_bytes()).decode('ascii')
    if len(encoded) > 60000:
        continue
    chunks = [encoded[i:i + 3000] for i in range(0, len(encoded), 3000)]
    for index, chunk in enumerate(chunks):
        print(f'::notice title=Emulator fixture preview::PHORMI_PREVIEW|{name}|{index}|{len(chunks)}|{chunk}')
