#!/usr/bin/env python3
"""Prevent CI from silently returning to per-run debug signing keys."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
gradle = (root / "app" / "build.gradle").read_text(encoding="utf-8")
workflow = (root / ".github" / "workflows" / "build.yml").read_text(
    encoding="utf-8"
)
gitignore = (root / ".gitignore").read_text(encoding="utf-8")

assert "signingConfigs.debug" not in gradle
for name in (
    "STS_KEYSTORE_FILE",
    "STS_KEYSTORE_PASSWORD",
    "STS_KEY_ALIAS",
    "STS_KEY_PASSWORD",
):
    assert name in gradle, f"Gradle does not consume {name}"

assert "STS_KEYSTORE_BASE64" in workflow
assert "c491c6b0772c25b94e13ddd956d1b2e7bccef28983e443d4e49161a1dd4c586a" in workflow
assert "app/sts-release.jks" in gitignore

print("stable APK signing config: OK")
