#!/usr/bin/env python3
"""Small, repeatable source fixes for the existing T.A.L.A. repository."""
from pathlib import Path

def replace_once(path: Path, old: str, new: str, label: str):
    if not path.exists():
        print(f"SKIP {path}: file not present")
        return
    text = path.read_text(encoding="utf-8")
    if new in text:
        print(f"OK {label}: already fixed")
        return
    if old not in text:
        print(f"WARNING {label}: expected text not found; leaving file unchanged")
        return
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"FIXED {label}")

root = Path(__file__).resolve().parent
listen = root / "JarvisListenService.java"

replace_once(
    listen,
    'new Recognizer(model, 16000.0f, "[\\"jarvis\\", \\"[unk]\\"]")',
    'new Recognizer(model, 16000.0f, "[\\"tala\\", \\"[unk]\\"]")',
    "offline wake-word grammar"
)
replace_once(
    listen,
    'setStatus("Nakikinig... sabihin: Jarvis");',
    'setStatus("Nakikinig... sabihin: Tala");',
    "listening status text"
)
replace_once(
    listen,
    'JSONArray tools = new JSONArray(prefs().getString("tools", "[]"));',
    'JSONArray toolSchemas = new JSONArray(prefs().getString("tools", "[]"));',
    "tool schema variable"
)
replace_once(
    listen,
    'payload.put("tools", tools);',
    'payload.put("tools", toolSchemas);',
    "tool schema payload"
)
