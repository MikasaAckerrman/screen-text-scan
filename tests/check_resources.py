#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Сверка ссылок на ресурсы.

ЗАЧЕМ. Опечатка в имени ресурса — ошибка сборки, но aapt2 сообщает о ней
невнятно, а причина почти всегда одна: ресурс переименовали в одном месте и
забыли в другом. Проверка занимает секунду и экономит прогон CI.

ЛОВУШКА, НА КОТОРОЙ Я УЖЕ ОШИБСЯ ОДИН РАЗ. В манифесте стиль пишется как
`@style/Theme.Sts`, и точка здесь — ЧАСТЬ ИМЕНИ. А в Java тот же стиль
доступен как `R.style.Theme_Sts`: генератор заменяет точку подчёркиванием.
Первая версия этого скрипта приводила Java-имена к манифестным, заменяя все
подчёркивания на точки, и выдала две ложные ошибки. Правильно — сравнивать в
обе стороны, что и делает name_variants().
"""
import glob
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
RES = os.path.join(ROOT, 'app/src/main/res')
MANIFEST = os.path.join(ROOT, 'app/src/main/AndroidManifest.xml')
JAVA = os.path.join(ROOT, 'app/src/main/java/com/screentextscan')

# Каталоги ресурсов, где имя файла и есть имя ресурса.
FILE_KINDS = {
    'drawable': 'drawable',
    'xml': 'xml',
    'mipmap-anydpi-v26': 'mipmap',
    'layout': 'layout',
}


def name_variants(name):
    """Все написания одного имени: с точками и с подчёркиваниями."""
    return {name, name.replace('.', '_'), name.replace('_', '.')}


def collect_defined():
    defined = set()
    for f in glob.glob(os.path.join(RES, 'values', '*.xml')):
        s = open(f, encoding='utf-8').read()
        for m in re.finditer(r'<(string|color|style|dimen|bool|integer)\s+name="([^"]+)"', s):
            defined.add((m.group(1), m.group(2)))
    for sub, kind in FILE_KINDS.items():
        for f in glob.glob(os.path.join(RES, sub, '*.xml')):
            defined.add((kind, os.path.basename(f)[:-4]))
        for ext in ('png', 'jpg', 'webp'):
            for f in glob.glob(os.path.join(RES, sub, '*.' + ext)):
                defined.add((kind, os.path.splitext(os.path.basename(f))[0]))
    return defined


def collect_used():
    used = set()
    files = [MANIFEST] + glob.glob(os.path.join(RES, '**', '*.xml'), recursive=True)
    for f in files:
        s = open(f, encoding='utf-8').read()
        for m in re.finditer(r'"@(string|style|drawable|mipmap|xml|color|dimen|layout)/([A-Za-z0-9_.]+)"', s):
            used.add((m.group(1), m.group(2), os.path.basename(f)))
    for f in glob.glob(os.path.join(JAVA, '*.java')):
        s = open(f, encoding='utf-8').read()
        # (?<!android\.) — платформенные ресурсы android.R.* лежат в самой
        # системе, а не у нас, и проверять их по нашему res/ бессмысленно.
        # Без этого исключения android.R.style.Theme_DeviceDefault выглядел
        # как отсутствующий наш стиль.
        for m in re.finditer(
                r'(?<!android\.)\bR\.(string|drawable|style|color|xml|mipmap|dimen|layout)\.([A-Za-z0-9_]+)', s):
            used.add((m.group(1), m.group(2), os.path.basename(f)))
    return used


def main():
    defined = collect_defined()
    used = collect_used()

    # Индекс: тип → множество всех написаний определённых имён
    index = {}
    for kind, name in defined:
        index.setdefault(kind, set()).update(name_variants(name))

    missing = []
    for kind, name, where in sorted(used):
        pool = index.get(kind, set())
        if not (name_variants(name) & pool):
            missing.append((kind, name, where))

    print('определено ресурсов: %d' % len(defined))
    print('ссылок проверено:    %d' % len(used))

    if missing:
        print()
        print('НЕ НАЙДЕНО — сборка упадёт:')
        for kind, name, where in missing:
            print('   @%s/%s  (из %s)' % (kind, name, where))
        return 1

    print('все ссылки разрешаются')

    # Неиспользуемое — не ошибка, но полезно видеть: обычно это забытый
    # ресурс после переделки интерфейса.
    used_names = {}
    for kind, name, _ in used:
        used_names.setdefault(kind, set()).update(name_variants(name))
    unused = [(k, n) for (k, n) in sorted(defined)
              if not (name_variants(n) & used_names.get(k, set()))]
    if unused:
        print('не используются (не ошибка): '
              + ', '.join('@%s/%s' % kn for kn in unused))
    return 0


if __name__ == '__main__':
    sys.exit(main())
