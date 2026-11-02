#!/usr/bin/env python3
"""Regression guardrails for invariants also exercised by the Android smoke suite."""
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
src = root / 'app/src/main/java/com/uong/phormi'
res = root / 'app/src/main/res'
ns = '{http://schemas.android.com/apk/res/android}'
service = (src / 'PhormiKeyboardServiceV2.kt').read_text()
assert not (src / 'PhormiKeyboardRenderCompat.kt').exists(), 'Duplicate renderer must not return'
assert 'PhormiKeyboardLayouts.rows(locale.language)' in service
assert 'buildFieldKeypad(root, clazz)' in service
assert 'deleteSurroundingText(1, 0)' not in service, 'UTF-16-unit deletion is unsafe'
assert 'Insert picked' in service, 'Delayed picker content requires user confirmation'
assert 'prefs.getBoolean(KEY_ONLINE_SUGGESTIONS, true)' not in (src / 'PhormiKeyboardPreferences.kt').read_text()
assert 'getBoolean(KEY_ONLINE_SUGGESTIONS, false)' in (src / 'PhormiKeyboardPreferences.kt').read_text()
assert 'request.grant(request.resources)' not in (src / 'MainActivity.kt').read_text()
assert 'request.grant(resources)' in (src / 'PhormiSitePermissions.kt').read_text()
assert '.setPositiveButton("Allow once")' in (src / 'PhormiSitePermissions.kt').read_text()
assert '.put("id", "tab-${index + 1}")' not in (src / 'PhormiSyncStore.kt').read_text()
assert 'createdAt < AUTO_CLEAR_MS' in (src / 'PhormiKeyboardClipboardStore.kt').read_text()
main = ET.parse(res / 'layout/activity_main.xml')
for element in main.iter():
    if element.get(ns + 'id') in {'@+id/btn_menu', '@+id/tab_count', '@+id/btn_refresh'}:
        assert element.get(ns + 'layout_width') == '48dp'
        assert element.get(ns + 'layout_height') == '48dp'
# Every declared subtype must be backed by a real character layout.
layouts = (src / 'PhormiKeyboardLayouts.kt').read_text().split('val supported = setOf(', 1)[1].split(')', 1)[0]
for subtype in ET.parse(res / 'xml/method_phormi_keyboard.xml').iter('subtype'):
    language = subtype.get(ns + 'imeSubtypeLocale').split('_')[0]
    assert f'"{language}"' in layouts, language
for file in ('backup_rules.xml', 'data_extraction_rules.xml'):
    assert 'phormi_keyboard_dict_cache.xml' in (res / 'xml' / file).read_text()
print('REPAIR REGRESSION GUARDRAILS: PASS')
