#!/usr/bin/env python3
"""Patch an existing JarvisListenService.java for T.A.L.A.; makes a .bak backup."""
from pathlib import Path
import sys

if len(sys.argv) != 2:
    print("Usage: python3 apply_tala_patch.py path/to/JarvisListenService.java")
    raise SystemExit(2)

path = Path(sys.argv[1])
if not path.is_file():
    print(f"ERROR: file not found: {path}")
    raise SystemExit(2)

src = path.read_text(encoding="utf-8")
original = src
replacements = [
    ('b.setContentTitle("J.A.R.V.I.S.");', 'b.setContentTitle("T.A.L.A.");'),
    ('"Jarvis nakikinig. Sabihin: Jarvis"', '"T.A.L.A. nakikinig. Sabihin: Tala"'),
    ('"Online na ako, boss. Sabihin mo lang ang Jarvis."', '"Online na ako, boss. Sabihin mo lang ang Tala."'),
    ('"Nakikinig... sabihin: Jarvis"', '"Nakikinig... sabihin: Tala"'),
    ('"[\\\"jarvis\\\", \\\"[unk]\\\"]"', '"[\\\"tala\\\", \\\"[unk]\\\"]"'),
    ('txt.contains("jarvis")', 'txt.contains("tala")'),
    ('JSONArray tools = new JSONArray(prefs().getString("tools", "[]"));',
     'JSONArray toolSchemas = new JSONArray(prefs().getString("tools", "[]"));'),
    ('payload.put("tools", tools);', 'payload.put("tools", toolSchemas);'),
    ('String res = tools.run(b.optString("name", ""), in);',
     'String res = this.tools.run(b.optString("name", ""), in);'),
]

missing = []
for old, new in replacements:
    if old in src:
        src = src.replace(old, new)
    elif new in src:
        pass  # already patched
    else:
        missing.append(old)

if missing:
    print("ERROR: these expected source lines were not found; no changes written:")
    for line in missing:
        print(" -", line)
    print("This may be a different version of the Java file. Do not force-apply.")
    raise SystemExit(1)

if src == original:
    print("No changes needed; the file already appears patched.")
else:
    backup = path.with_suffix(path.suffix + ".bak")
    backup.write_text(original, encoding="utf-8")
    path.write_text(src, encoding="utf-8")
    print(f"Patched: {path}")
    print(f"Backup:  {backup}")
    print("Internal class and package names were preserved.")
