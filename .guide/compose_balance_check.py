#!/usr/bin/env python3
"""Brace/paren depth check for Compose sources.

Compose files are edited mechanically during a redesign, and there is no local Kotlin
toolchain in this sandbox, so a structural check is run over every touched file before the
real compile happens on CI. Line based with block-comment tracking, which - unlike a
whole-file regex - is not confused by apostrophes inside comments.

Usage: python3 .guide/compose_balance_check.py <file.kt> [...]
Exits non-zero on the first unbalanced file.
"""
import re
import sys


def check(path):
    depth = 0
    parens = 0
    in_block = False
    for number, line in enumerate(open(path, encoding="utf-8").read().split("\n"), 1):
        if in_block:
            if "*/" in line:
                in_block = False
            continue
        if "/*" in line and "*/" not in line:
            in_block = True
            continue
        stripped = re.sub(r'"(\\.|[^"\\])*"', '""', line)
        stripped = re.sub(r"//.*", "", stripped)
        depth += stripped.count("{") - stripped.count("}")
        parens += stripped.count("(") - stripped.count(")")
        if depth < 0:
            return f"{path}:{number} brace depth went negative"
    if depth != 0:
        return f"{path}: unbalanced braces (net {depth})"
    if parens != 0:
        return f"{path}: unbalanced parentheses (net {parens})"
    return None


def main(argv):
    problems = [r for r in (check(p) for p in argv) if r]
    for problem in problems:
        print("FAIL", problem)
    if not problems:
        print(f"OK {len(argv)} file(s) balanced")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
