#!/usr/bin/env python3
"""Brace/paren/bracket depth check for Kotlin sources.

There is no Kotlin toolchain in this sandbox, so every touched file gets a structural check
before the real compile happens on CI. This is a single-pass scanner that tracks line
comments, block comments, single-line strings, raw triple-quoted strings, string templates and
char literals, because naive regexes get them wrong: `"image/*"` is a MIME type, not the start
of a KDoc block, and `'}'` is a char literal, not a brace.

Usage: python3 .guide/compose_balance_check.py <file.kt> [...]
Exits non-zero on the first unbalanced file.
"""
import sys

PAIRS = {"{": "}", "(": ")", "[": "]"}
CLOSERS = set(PAIRS.values())


def scan(source):
    stack = []
    line = 1
    i = 0
    n = len(source)
    while i < n:
        ch = source[i]
        nxt = source[i + 1] if i + 1 < n else ""
        if ch == "\n":
            line += 1
            i += 1
            continue
        # Line comment.
        if ch == "/" and nxt == "/":
            while i < n and source[i] != "\n":
                i += 1
            continue
        # Block comment (nesting is not a Kotlin thing, but a stray /* inside one is harmless).
        if ch == "/" and nxt == "*":
            depth = 1
            i += 2
            while i < n and depth:
                if source[i] == "/" and source[i + 1:i + 2] == "*":
                    depth += 1
                    i += 2
                    continue
                if source[i] == "*" and source[i + 1:i + 2] == "/":
                    depth -= 1
                    i += 2
                    continue
                if source[i] == "\n":
                    line += 1
                i += 1
            continue
        # Raw string.
        if source.startswith('"""', i):
            i += 3
            while i < n and not source.startswith('"""', i):
                if source[i] == "\n":
                    line += 1
                i += 1
            i += 3
            continue
        # Single-line string, including ${...} templates whose braces must be ignored.
        if ch == '"':
            i += 1
            while i < n and source[i] != '"':
                if source[i] == "\\":
                    i += 2
                    continue
                if source[i] == "$" and source[i + 1:i + 2] == "{":
                    depth = 1
                    i += 2
                    while i < n and depth:
                        if source[i] == "{":
                            depth += 1
                        elif source[i] == "}":
                            depth -= 1
                        elif source[i] == "\n":
                            line += 1
                        i += 1
                    continue
                i += 1
            i += 1
            continue
        # Char literal.
        if ch == "'":
            i += 1
            while i < n and source[i] != "'":
                i += 2 if source[i] == "\\" else 1
            i += 1
            continue
        if ch in PAIRS:
            stack.append((ch, line))
            i += 1
            continue
        if ch in CLOSERS:
            if not stack:
                return f"line {line}: unexpected '{ch}' with an empty stack"
            opener, opened_at = stack.pop()
            if PAIRS[opener] != ch:
                return f"line {line}: '{ch}' closes '{opener}' opened on line {opened_at}"
            i += 1
            continue
        i += 1
    if stack:
        opener, opened_at = stack[-1]
        return f"unclosed '{opener}' opened on line {opened_at} ({len(stack)} still open)"
    return None


def main(argv):
    problems = []
    for path in argv:
        result = scan(open(path, encoding="utf-8").read())
        if result:
            problems.append(f"{path}: {result}")
    for problem in problems:
        print("FAIL", problem)
    if not problems:
        print(f"OK {len(argv)} file(s) balanced")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
