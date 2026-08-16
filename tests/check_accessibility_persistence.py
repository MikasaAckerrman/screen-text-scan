#!/usr/bin/env python3
"""Guard the Vivo/OriginOS accessibility-service persistence path."""
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
java = root / "app/src/main/java/com/screentextscan"
manifest_path = root / "app/src/main/AndroidManifest.xml"
manifest = manifest_path.read_text(encoding="utf-8")
android = "{http://schemas.android.com/apk/res/android}"
manifest_xml = ET.parse(manifest_path).getroot()
main = (java / "MainActivity.java").read_text(encoding="utf-8")
a11y = (java / "ScanAccessibilityService.java").read_text(encoding="utf-8")
keep = (java / "AccessibilityKeepAliveService.java").read_text(encoding="utf-8")
boot = (java / "KeepAliveReceiver.java").read_text(encoding="utf-8")

assert "android.permission.RECEIVE_BOOT_COMPLETED" in manifest
assert "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" in manifest
main_activity = next(
    node for node in manifest_xml.findall("./application/activity")
    if node.get(android + "name") == ".MainActivity"
)
assert main_activity.get(android + "excludeFromRecents") == "true"
assert 'android:name=".AccessibilityKeepAliveService"' in manifest
assert 'android:stopWithTask="false"' in manifest
assert 'android:name=".KeepAliveReceiver"' in manifest
for action in ("BOOT_COMPLETED", "MY_PACKAGE_REPLACED"):
    assert action in manifest
assert "LOCKED_BOOT_COMPLETED" not in manifest
assert 'android:directBootAware="true"' not in manifest

assert "START_STICKY" in keep
assert "if (!accessibilityEnabled())" in keep
assert keep.index("if (!accessibilityEnabled())") < keep.index("startForeground("), (
    "sticky restart must not promote when accessibility was disabled"
)
assert "Permissions.isAccessibilityEnabled(this)" in keep
assert "AccessibilityKeepAliveService.start(this);" in a11y
assert "disableSelf()" not in a11y
assert "AccessibilityKeepAliveService.start(this);" in main
assert "com.vivo.permissionmanager.activity.BgStartUpManagerActivity" in main
assert "Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" in main
assert "AccessibilityKeepAliveService.start(context);" in boot

print("accessibility persistence: OK")
