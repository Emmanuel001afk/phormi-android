#!/usr/bin/env python3
"""Expose bounded fixture-only emulator previews for restricted CI artifact reviewers.

Full screenshots/XML remain in the normal artifact. No production device/user data
is collected; these files come only from the instrumentation test's clean fixture.
"""
from pathlib import Path
import base64

root = Path('app/build/reports/smoke')
preferred = ['phormi-typing-failure', 'phormi-typing-smoke', 'phormi-emoji-smoke', 'phormi-writing-smoke', 'phormi-phone-smoke', 'phormi-browser-keyboard-smoke', 'phormi-panel-final']
for name in preferred:
    paths = list(root.glob(f'**/{name}-preview.jpg'))
    if not paths:
        continue
    encoded = base64.b64encode(paths[0].read_bytes()).decode('ascii')
    if len(encoded) < 60000:
        print(f'::notice title=Emulator fixture preview::PHORMI_PREVIEW|{name}|{encoded}')
