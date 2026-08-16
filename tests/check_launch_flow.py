#!/usr/bin/env python3
"""Keep both scan entry points on the same cold-start-safe path."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
java = root / "app" / "src" / "main" / "java" / "com" / "screentextscan"
main = (java / "MainActivity.java").read_text(encoding="utf-8")
permissions = (java / "Permissions.java").read_text(encoding="utf-8")
launch = (java / "LaunchActivity.java").read_text(encoding="utf-8")

assert "ScanAccessibilityService.get()" not in main, (
    "MainActivity must use persistent system permission state, not a live "
    "service instance that is null during cold start"
)
assert "if (!Permissions.canOverlay(this))" in main
assert "openOverlaySettings();" in main
assert "if (!Permissions.isAccessibilityMasterOn(this)" in main
assert "openAccessibilitySettings();" in main
assert "new Intent(this, LaunchActivity.class)" in main, (
    "MainActivity must use LaunchActivity's service wait path"
)
assert "return canOverlay(c) && isAccessibilityMasterOn(c)" in permissions, (
    "ready() must reject a disabled accessibility master switch"
)
assert "WAIT_TOTAL_MS" in launch and "WAIT_STEP_MS" in launch
assert "h.postDelayed(this::waitForServiceThenStart, WAIT_STEP_MS)" in launch, (
    "LaunchActivity must retry while the enabled accessibility service binds"
)
assert "moveTaskToBack(true);" in launch, (
    "LaunchActivity must reveal the app below before showing the overlay"
)

print("scan launch flow: OK")
