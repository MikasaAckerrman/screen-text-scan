#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Разрешение графа зависимостей по POM-файлам — без Gradle.

ЗАЧЕМ. Сборка упала на конфликте kotlin-stdlib: ML Kit тянет
kotlin-stdlib-jdk7/jdk8 версии 1.6.x, а androidx.core — kotlin-stdlib 1.8.22,
и с Kotlin 1.8 классы jdk7/jdk8 переехали в основной stdlib. Получились
дубликаты классов, и checkReleaseDuplicateClasses остановил сборку.

Такие конфликты видны в графе зависимостей ЗАДОЛГО до сборки, но в песочнице
нет Gradle, чтобы вызвать `dependencies`. Поэтому обходим POM-ы сами: это
десяток HTTP-запросов и несколько секунд против трёх минут прогона CI.

ЧЕГО СКРИПТ НЕ ДЕЛАЕТ. Он не повторяет алгоритм Gradle целиком: нет
constraints, platform, capabilities, вытеснения по ближайшему определению.
Он отвечает на один вопрос — какие версии одного артефакта приходят из
разных мест и какую из них Gradle выберет (старшую). Для конфликтов
дублирующихся классов этого достаточно.

СЛЕДСТВИЕ, ВАЖНОЕ ДЛЯ CI: скрипт ЧИТАЕТ блок constraints из
app/build.gradle и учитывает его как принудительную версию. Иначе после
починки он продолжал бы сообщать о конфликте, и проверку пришлось бы
«сверять глазами» — то есть она перестала бы быть проверкой.
"""
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET
from collections import defaultdict

REPOS = [
    'https://dl.google.com/dl/android/maven2/',
    'https://repo1.maven.org/maven2/',
]

NS = '{http://maven.apache.org/POM/4.0.0}'

# Что просим напрямую — то же, что в app/build.gradle.
ROOTS = [
    ('androidx.core', 'core', '1.13.1'),
    ('com.google.mlkit', 'translate', '17.0.3'),
]

cache = {}
seen = set()
versions = defaultdict(set)
origin = defaultdict(set)


def read_constraints():
    """Прочитать блок constraints из app/build.gradle.

    Это принудительные версии: Gradle поднимет артефакт до указанной, даже
    если сам граф просит меньшую. Без их учёта проверка сообщала бы о уже
    исправленном конфликте — и её пришлось бы игнорировать, что хуже, чем
    не иметь проверки вовсе.
    """
    import os
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                        '..', 'app', 'build.gradle')
    forced = {}
    try:
        src = open(path, encoding='utf-8').read()
    except OSError:
        return forced
    m = re.search(r'constraints\s*\{(.*?)\n\s*\}', src, re.S)
    if not m:
        return forced
    for line in m.group(1).splitlines():
        dep = re.search(r"['\"]([\w.\-]+):([\w.\-]+):([\w.\-]+)['\"]", line)
        if dep:
            forced[(dep.group(1), dep.group(2))] = dep.group(3)
    return forced


def fetch_pom(group, artifact, version):
    key = (group, artifact, version)
    if key in cache:
        return cache[key]
    path = '%s/%s/%s/%s-%s.pom' % (group.replace('.', '/'), artifact, version, artifact, version)
    for base in REPOS:
        try:
            with urllib.request.urlopen(base + path, timeout=25) as r:
                data = r.read()
                cache[key] = data
                return data
        except Exception:
            continue
    cache[key] = None
    return None


def text(node, tag):
    el = node.find(NS + tag)
    return None if el is None else (el.text or '').strip()


def walk(group, artifact, version, parent, depth=0):
    """Обход в глубину. Глубина ограничена: у POM бывают циклы через
    родительские описания, и без предела обход не закончится."""
    if depth > 6:
        return
    key = (group, artifact, version)
    versions[(group, artifact)].add(version)
    origin[(group, artifact, version)].add(parent)
    if key in seen:
        return
    seen.add(key)

    data = fetch_pom(group, artifact, version)
    if not data:
        return
    try:
        root = ET.fromstring(data)
    except ET.ParseError:
        return

    props = {}
    pnode = root.find(NS + 'properties')
    if pnode is not None:
        for child in pnode:
            props[child.tag.replace(NS, '')] = (child.text or '').strip()

    deps = root.find(NS + 'dependencies')
    if deps is None:
        return
    for d in deps.findall(NS + 'dependency'):
        scope = text(d, 'scope') or 'compile'
        optional = (text(d, 'optional') or 'false').lower() == 'true'
        if scope not in ('compile', 'runtime') or optional:
            continue
        g, aid, v = text(d, 'groupId'), text(d, 'artifactId'), text(d, 'version')
        if not g or not aid or not v:
            continue
        # подстановка ${...} из properties
        m = re.fullmatch(r'\$\{([^}]+)\}', v)
        if m:
            v = props.get(m.group(1))
            if not v:
                continue
        walk(g, aid, v, '%s:%s:%s' % (group, artifact, version), depth + 1)


def main():
    for g, a, v in ROOTS:
        walk(g, a, v, 'app/build.gradle')

    forced = read_constraints()
    print('артефактов в графе: %d' % len(versions))
    if forced:
        print('принудительные версии из constraints: '
              + ', '.join('%s:%s=%s' % (g, a, v) for (g, a), v in sorted(forced.items())))

    conflicts = {k: v for k, v in versions.items() if len(v) > 1}
    if conflicts:
        print()
        print('РАЗНЫЕ ВЕРСИИ ОДНОГО АРТЕФАКТА (Gradle выберет старшую):')
        for (g, a), vs in sorted(conflicts.items()):
            note = ' → принудительно %s' % forced[(g, a)] if (g, a) in forced else ''
            print('  %s:%s → %s%s' % (g, a, ', '.join(sorted(vs)), note))
            for v in sorted(vs):
                for src in sorted(origin[(g, a, v)]):
                    print('      %s ← %s' % (v, src))

    def effective(group, artifact):
        """Версия, которая реально попадёт в сборку."""
        if (group, artifact) in forced:
            return forced[(group, artifact)]
        pool = versions.get((group, artifact), set())
        return max(pool, key=vkey) if pool else None

    # Именно этот случай уронил сборку, поэтому проверяем его отдельно.
    # ВАЖНО про то, что скрипт видит, а Gradle делает: Gradle выбирает
    # СТАРШУЮ версию каждого артефакта в графе (либо указанную в
    # constraints). Значит опасно не любое сочетание версий, а именно
    # «выбранный stdlib >= 1.8» вместе с «выбранным stdlib-jdkN < 1.8»:
    # классы jdk7/jdk8 переехали в основной stdlib, а старые артефакты их
    # всё ещё содержат.
    #
    # Сравнивать надо ВЫБРАННЫЕ версии, а не все пары: первая версия
    # скрипта перебирала комбинации и выдавала три «конфликта» там, где
    # реальный один.
    main_v = effective('org.jetbrains.kotlin', 'kotlin-stdlib')
    problem = False
    if main_v:
        for suffix in ('jdk7', 'jdk8'):
            extra_v = effective('org.jetbrains.kotlin', 'kotlin-stdlib-' + suffix)
            if not extra_v:
                continue
            if minor(main_v) >= (1, 8) and minor(extra_v) < (1, 8):
                print()
                print('КОНФЛИКТ KOTLIN: в сборку пойдут stdlib %s (>=1.8) '
                      'и stdlib-%s %s (<1.8)' % (main_v, suffix, extra_v))
                print('  Классы jdk7/jdk8 переехали в основной stdlib → дубликаты '
                      'в checkReleaseDuplicateClasses.')
                print('  Лечится блоком constraints на все три артефакта '
                      'одной версии — см. app/build.gradle.')
                problem = True
    if not problem:
        print()
        print('конфликта kotlin-stdlib нет')
    return 1 if problem else 0


def vkey(v):
    """Ключ сортировки версий: 1.8.22 старше 1.8.9, хотя строкой наоборот."""
    return [int(x) for x in re.findall(r'\d+', v)]


def minor(v):
    parts = re.findall(r'\d+', v)
    return (int(parts[0]), int(parts[1])) if len(parts) >= 2 else (0, 0)


if __name__ == '__main__':
    sys.exit(main())
