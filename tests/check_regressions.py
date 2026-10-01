#!/usr/bin/env python3
"""
Регрессионные проверки классов багов, реально случавшихся в проекте.

1. Touch-слушатели не должны трогать ПОЛЯ bubble/bubbleTouch напрямую:
   слушатель живёт дольше окна — обращение к полю во время жеста
   падало NPE и убивало процесс вместе со службой доступности
   (краш 24.09, OverlayService$3.onTouch:437).
2. updateViewLayout/removeView обязаны быть в try/catch: жест может
   долететь к уже снятому окну (IllegalArgumentException).
3. readScreen зовётся только с явным targetPkg (пакет-привязка).
4. startForeground с типом mediaProjection — только в onVisionGranted
   (после согласия); до согласия Vivo валит сервис SecurityException'ом.
"""
import re
import sys

BASE = "app/src/main/java/com/screentextscan/"
FAIL = []

def read(name):
    with open(BASE + name, encoding="utf-8") as f:
        return f.read()

def check(cond, msg):
    if not cond:
        FAIL.append(msg)
        print("FAIL:", msg)
    else:
        print("ok:", msg)

src = read("OverlayService.java")

# --- 1. слушатели не трогают поля окна напрямую ----------------------
m = re.search(r"setOnTouchListener\(new View\.OnTouchListener\(\) \{.*?\n        \}\);", src, re.S)
body = m.group(0) if m else ""
check(bool(m), "touch-слушатель найден")
check("bubble.getWidth()" not in body and "bubble.getHeight()" not in body,
      "слушатель не читает bubble.getWidth()/getHeight() (класс краша 24.09)")
check("wm.updateViewLayout(bubble," not in body and "wm.updateViewLayout(bubbleTouch," not in body
      or "try" in body,
      "updateViewLayout в слушателе под try (окно могло сняться)")

# --- 2. любые removeView/updateViewLayout — в try/catch --------------
raw = re.sub(r"//[^\n]*", "", src)
raw = re.sub(r"/\*.*?\*/", "", raw, flags=re.S)

def guarded(raw, idx):
    """Вызов окружён try: try { в пределах 6 строк выше или в той же строке."""
    lines = raw[:idx].split("\n")
    return any("try" in l and "{" in l for l in lines[-6:]) or "try" in raw[max(0, idx - 400):idx].rstrip().split("\n")[-1]

danger = []
for m in re.finditer(r"wm\.(?:removeView|updateViewLayout)\(", raw):
    if not guarded(raw, m.start()):
        danger.append(raw[max(0, m.start() - 60):m.start() + 60].replace("\n", " "))
check(len(danger) == 0,
      f"wm.removeView/updateViewLayout без try: {len(danger)} — {danger[:2]}")

# --- 3. readScreen только с targetPkg --------------------------------
check("readScreen(zone" not in src and "readScreen(null, screenW" not in src,
      "нет вызовов readScreen без targetPkg (привязка обязательна)")

# --- 4. mediaProjection-тип — только после согласия ------------------
grant = re.search(r"private void onVisionGranted.*?\n    \}", src, re.S)
start = re.search(r"public void onCreate\(\).*?\n    \}", src, re.S)
check(bool(grant) and "FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION" in grant.group(0),
      "тип mediaProjection поднимается в onVisionGranted (после согласия)")
check(bool(start) and "MEDIA_PROJECTION" not in start.group(0),
      "onCreate НЕ стартует с mediaProjection (краш на Vivo 01.10)")

# --- 5. IME не переключает скан --------------------------------------
check("isImePackage" in src, "клавиатура детектится по типу окна (isImePackage)")

print()
if FAIL:
    print("ПРОВАЛЕНО:", len(FAIL))
    sys.exit(1)
print("РЕГРЕССИОННЫЕ ПРОВЕРКИ: ВСЁ ЧИСТО")
