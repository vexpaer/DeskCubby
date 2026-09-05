#!/usr/bin/env python3
"""Unresolved-icon-import check.

Material icon names are generated at build time, so a typo or a name missing from
material-icons-extended is an unresolved reference that only shows up on CI - where the log is
not reachable from this sandbox. This verifies that every `Icons.<set>.<Style>.<Name>` used in a
file has a matching import, which catches both a wrong name and a forgotten import.

Usage: python3 .guide/icon_import_check.py <file.kt> [...]
"""
import re
import sys

USE = re.compile(r"Icons\.((?:AutoMirrored\.)?(?:Outlined|Filled|Rounded|Sharp|TwoTone))\.([A-Z][A-Za-z0-9]*)")
IMPORT = re.compile(r"^import (androidx\.compose\.material\.icons\.[A-Za-z0-9_.]+)$", re.M)


def check(path):
    source = open(path, encoding="utf-8").read()
    imported = set(IMPORT.findall(source))
    missing = []
    for style, name in set(USE.findall(source)):
        expected = f"androidx.compose.material.icons.{style.lower()}.{name}"
        # androidx.compose.material.icons.automirrored.outlined.X
        if expected not in imported:
            missing.append(f"{style}.{name} (expected import {expected})")
    return missing


def main(argv):
    bad = False
    for path in argv:
        for problem in check(path):
            print(f"FAIL {path}: {problem}")
            bad = True
    if not bad:
        print(f"OK {len(argv)} file(s): every Icons.* reference has a matching import")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
