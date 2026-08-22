#!/usr/bin/env python3
"""Convert Android AppTranslations.kt tables into web/frontend/src/i18n/translations.json."""
import json
import re
import sys
from pathlib import Path

SRC = Path(__file__).resolve().parents[2] / "android/app/src/main/java/com/deskcubby/app/ui/theme/AppTranslations.kt"
OUT = Path(__file__).resolve().parents[1] / "frontend/src/i18n/translations.json"

ENTRY = re.compile(r'^\s*"((?:[^"\\]|\\.)*)"\s+to\s+"((?:[^"\\]|\\.)*)",\s*$')


def parse_map(lines, start):
    out = {}
    i = start
    while i < len(lines):
        line = lines[i]
        if line.strip() == ")":
            return out, i
        m = ENTRY.match(line)
        if m:
            def unescape(s: str) -> str:
                # Kotlin string escapes used in this file: \" \\ \$ \n \t
                s = s.replace('\\"', '"').replace("\\n", "\n").replace("\\t", "\t")
                return s.replace("\\$", "$").replace("\\\\", "\\")
            key = unescape(m.group(1))
            val = unescape(m.group(2))
            out[key] = val
        i += 1
    return out, i


def main():
    text = SRC.read_text(encoding="utf-8")
    lines = text.splitlines()
    tables = {}
    for name in ("TRADITIONAL", "KOREAN", "JAPANESE"):
        for idx, line in enumerate(lines):
            if f"val {name}: Map<String, String> = mapOf(" in line:
                table, _ = parse_map(lines, idx + 1)
                tables[name] = table
                break
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(
        {
            "zh-TW": tables.get("TRADITIONAL", {}),
            "ko": tables.get("KOREAN", {}),
            "ja": tables.get("JAPANESE", {}),
        },
        ensure_ascii=False, indent=0, separators=(",", ":"),
    ), encoding="utf-8")
    print(f"wrote {OUT} sizes:", {k: len(v) for k, v in tables.items()})


if __name__ == "__main__":
    sys.exit(main())
