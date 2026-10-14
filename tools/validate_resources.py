#!/usr/bin/env python3
"""Offline structural checks; Android compilation/lint remain the authoritative checks."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
errors = []
resources = {}
ids = set()
for path in RES.rglob("*.xml"):
    try:
        tree = ET.parse(path)
    except ET.ParseError as error:
        errors.append(f"{path.relative_to(ROOT)}: {error}")
        continue
    resource_type = path.parent.name.split("-")[0]
    if resource_type != "values":
        resources.setdefault(resource_type, set()).add(path.stem)
    else:
        for element in tree.getroot():
            name = element.get("name")
            if name:
                resources.setdefault(element.tag.replace("-array", ""), set()).add(name)
    ids.update(re.findall(r"@\+id/([\w]+)", path.read_text()))
for path in RES.rglob("*"):
    if path.is_file() and path.suffix != ".xml":
        resources.setdefault(path.parent.name.split("-")[0], set()).add(path.stem)
for path in list(RES.rglob("*.xml")) + [ROOT / "app/src/main/AndroidManifest.xml"]:
    for resource_type, name in re.findall(r"@(?!(?:android:|\+))([\w]+)/([\w.]+)", path.read_text()):
        if resource_type == "id":
            if name not in ids:
                errors.append(f"{path.name}: missing id/{name}")
        elif resource_type in {"drawable", "layout", "xml", "mipmap", "string", "style", "color"}:
            if name not in resources.get(resource_type, set()):
                errors.append(f"{path.name}: missing {resource_type}/{name}")
manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml")
android_name = "{http://schemas.android.com/apk/res/android}name"
src = ROOT / "app/src/main/java/com/uong/phormi"
for element in manifest.findall(".//activity") + manifest.findall(".//service"):
    name = element.get(android_name, "")
    if name.startswith(".") and not (src / f"{name[1:]}.kt").exists():
        # Download service intentionally shares the download engine's source file.
        if not any(re.search(rf"\bclass\s+{re.escape(name[1:])}\b", p.read_text()) for p in src.glob("*.kt")):
            errors.append(f"Manifest references missing class {name}")
if errors:
    raise SystemExit("\n".join(errors))
print(f"Resource structure OK: {len(list(RES.rglob('*.xml')))} XML files; manifest classes resolved.")
