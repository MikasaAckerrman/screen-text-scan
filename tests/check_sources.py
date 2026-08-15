#!/usr/bin/env python3
"""Fail early when a Java source was replaced by tool output.

The previous CI failure came from a file containing an offload marker instead
of Java source. The Java compiler reported only a generic error at line 1,
which hid the actual boundary failure. Check the boundary explicitly before
all other tests and before Gradle.
"""
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "java"
errors = []
count = 0
markers = ("[CONTEXT OFFLOADED]", "Use file_read tool", "Use file_write tool")

for path in sorted(root.rglob("*.java")):
    count += 1
    text = path.read_text(encoding="utf-8")
    first = text.splitlines()[0] if text.splitlines() else ""
    if first != "package com.screentextscan;":
        errors.append(f"{path}: first line is {first!r}")
    for marker in markers:
        if marker in text:
            errors.append(f"{path}: contains tool marker {marker!r}")

required = {
    "MainActivity.java": "class MainActivity",
    "ResultActivity.java": "class ResultActivity",
    "TextTranslator.java": "class TextTranslator",
    "ZoneSelectorView.java": "class ZoneSelectorView",
    "ReadingOrder.java": "class ReadingOrder",
}
for name, needle in required.items():
    path = root / "com" / "screentextscan" / name
    if not path.exists():
        errors.append(f"missing required source: {path}")
    elif needle not in path.read_text(encoding="utf-8"):
        errors.append(f"{path}: missing {needle!r}")

print(f"проверено Java-файлов: {count}")
if errors:
    print("ошибки целостности исходников:")
    for error in errors:
        print("  " + error)
    sys.exit(1)
print("целостность исходников: OK")
