#!/usr/bin/env python3
"""Repeatable T.A.L.A. branding fixes for the existing web UI and native source."""
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

# Correct wake-word and status strings in the always-on service.
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

# Correct all primary visible branding and the assistant identity in the web app.
web = root / "www" / "index.html"
if web.exists():
    s = web.read_text(encoding="utf-8")
    original = s
    s = s.replace("<title>J.A.R.V.I.S.</title>", "<title>T.A.L.A.</title>")
    s = s.replace('<header><span>J.A.R.V.I.S</span>', '<header><span>T.A.L.A.</span>')
    s = s.replace('<div id="core">J.A.R.V.I.S<small', '<div id="core">T.A.L.A.<small')
    s = s.replace(
        "You are Jarvis, the calm, loyal, dry-witted AI butler from Iron Man, living in the user's Android phone. You have no face, only a voice.",
        "You are T.A.L.A. (Trusted Assistant for Life and Assistance), the user's personal Android assistant. You have no face, only a voice."
    )
    s = s.replace('Write your name as "Jarvis".', 'Your name is T.A.L.A. Never call yourself Jarvis.')
    s = s.replace("Jarvis online.", "T.A.L.A. online.")
    s = s.replace("Jarvis, ...", "Tala, ...")
    s = s.replace('s=s.replace(/J\\.?A\\.?R\\.?V\\.?I\\.?S\\.?/gi,\\'Jarvis\\');',
                  's=s.replace(/J\\.?A\\.?R\\.?V\\.?I\\.?S\\.?/gi,\\'T.A.L.A.\\');')
    if s != original:
        web.write_text(s, encoding="utf-8")
        print("FIXED T.A.L.A. visible branding and assistant identity")
    else:
        print("OK T.A.L.A. visible branding: no matching old text found or already fixed")
else:
    print("SKIP www/index.html: not present")
