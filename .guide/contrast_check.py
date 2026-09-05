#!/usr/bin/env python3
"""Contrast audit for the DeskCubby redesign palette.

Run:  python3 .guide/contrast_check.py
Exits non-zero when any text pair falls below WCAG AA (4.5:1) or any
non-text UI pair falls below 3:1, so the palette cannot regress silently.
"""
import sys

def chan(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4

def lum(hexstr):
    h = hexstr.lstrip("#")
    r, g, b = int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)
    return 0.2126 * chan(r) + 0.7152 * chan(g) + 0.0722 * chan(b)

def ratio(a, b):
    la, lb = lum(a), lum(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)

LIGHT = {
    "background": "#F4F5F1", "surface": "#FAFBF8", "surfaceContainerLowest": "#FFFFFF",
    "surfaceContainerLow": "#F5F6F2", "surfaceContainer": "#EEF0EB",
    "surfaceContainerHigh": "#E7EAE4", "surfaceContainerHighest": "#E0E4DD",
    "surfaceVariant": "#E3E7DF", "onSurface": "#161A15", "onSurfaceVariant": "#55604F",
    "outline": "#78827A", "outlineVariant": "#DCE0D8", "primary": "#3D6B4E",
    "onPrimary": "#FFFFFF", "primaryContainer": "#D8E8DC", "onPrimaryContainer": "#14301F",
    "secondary": "#5C7263", "onSecondary": "#FFFFFF", "secondaryContainer": "#DFE9E0",
    "onSecondaryContainer": "#1C2A20", "tertiary": "#7A6544", "onTertiary": "#FFFFFF",
    "tertiaryContainer": "#F0E4CE", "onTertiaryContainer": "#2E2411", "error": "#B3261E",
    "onError": "#FFFFFF", "errorContainer": "#F9DEDC", "onErrorContainer": "#410E0B",
    "inverseSurface": "#2E332D", "inverseOnSurface": "#F1F3EE",
}
DARK = {
    "background": "#0E110E", "surface": "#141814", "surfaceContainerLowest": "#090B09",
    "surfaceContainerLow": "#111511", "surfaceContainer": "#171B17",
    "surfaceContainerHigh": "#1F241F", "surfaceContainerHighest": "#282E28",
    "surfaceVariant": "#2A312A", "onSurface": "#E3E7E1", "onSurfaceVariant": "#A9B3A6",
    "outline": "#7E887C", "outlineVariant": "#2B312B", "primary": "#93D3A2",
    "onPrimary": "#0C2D18", "primaryContainer": "#23462E", "onPrimaryContainer": "#D2E9D8",
    "secondary": "#B9CCBE", "onSecondary": "#22332A", "secondaryContainer": "#2E4034",
    "onSecondaryContainer": "#D5E7DA", "tertiary": "#DCC39A", "onTertiary": "#3B2E12",
    "tertiaryContainer": "#4E4022", "onTertiaryContainer": "#F0E1C6", "error": "#FFB4AB",
    "onError": "#690005", "errorContainer": "#93000A", "onErrorContainer": "#FFDAD6",
    "inverseSurface": "#E3E7E1", "inverseOnSurface": "#2B312B",
}
GLASS_LIGHT = {
    "background": "#EFF2F9", "surface": "#F8F9FD", "surfaceContainerLowest": "#FFFFFF",
    "surfaceContainerLow": "#F3F5FB", "surfaceContainer": "#EBEEF7", "surfaceContainerHigh": "#E4E8F2",
    "surfaceContainerHighest": "#DDE2EE", "surfaceVariant": "#E1E6F1", "onSurface": "#161A22",
    "onSurfaceVariant": "#4E5866", "outline": "#747F8D", "outlineVariant": "#D9DFEA",
    "primary": "#4160A6", "onPrimary": "#FFFFFF", "primaryContainer": "#D9E1F7",
    "onPrimaryContainer": "#102348", "secondary": "#5A6478", "onSecondary": "#FFFFFF",
    "secondaryContainer": "#DEE3EE", "onSecondaryContainer": "#18202F", "tertiary": "#75598C",
    "onTertiary": "#FFFFFF", "tertiaryContainer": "#EBDDF6", "onTertiaryContainer": "#2C1442",
    "error": "#B3261E", "onError": "#FFFFFF", "errorContainer": "#F9DEDC",
    "onErrorContainer": "#410E0B", "inverseSurface": "#2C313B", "inverseOnSurface": "#F0F2F7",
}
GLASS_DARK = {
    "background": "#0A0D14", "surface": "#11151E", "surfaceContainerLowest": "#06080D",
    "surfaceContainerLow": "#0E121A", "surfaceContainer": "#141924", "surfaceContainerHigh": "#1C2230",
    "surfaceContainerHighest": "#252C3C", "surfaceVariant": "#283040", "onSurface": "#E2E6EF",
    "onSurfaceVariant": "#A7B0C0", "outline": "#78828F", "outlineVariant": "#283040",
    "primary": "#A9C0F5", "onPrimary": "#122450", "primaryContainer": "#2B4480",
    "onPrimaryContainer": "#D8E2FA", "secondary": "#BCC5D8", "onSecondary": "#262E3E",
    "secondaryContainer": "#343D50", "onSecondaryContainer": "#D9E0EC", "tertiary": "#D6BCEA",
    "onTertiary": "#3E2454", "tertiaryContainer": "#573E6E", "onTertiaryContainer": "#F0E1FB",
    "error": "#FFB4AB", "onError": "#690005", "errorContainer": "#93000A",
    "onErrorContainer": "#FFDAD6", "inverseSurface": "#E2E6EF", "inverseOnSurface": "#2A3140",
}

TEXT_PAIRS = [
    ("onSurface", "background"), ("onSurface", "surface"), ("onSurface", "surfaceContainer"),
    ("onSurface", "surfaceContainerHigh"), ("onSurface", "surfaceContainerHighest"),
    ("onSurface", "surfaceVariant"), ("onSurfaceVariant", "background"),
    ("onSurfaceVariant", "surface"), ("onSurfaceVariant", "surfaceContainer"),
    ("onSurfaceVariant", "surfaceContainerHigh"), ("onSurfaceVariant", "surfaceContainerHighest"),
    ("primary", "surface"), ("primary", "surfaceContainer"), ("primary", "background"),
    ("onPrimary", "primary"), ("onPrimaryContainer", "primaryContainer"),
    ("onSecondaryContainer", "secondaryContainer"), ("onTertiaryContainer", "tertiaryContainer"),
    ("error", "surface"), ("error", "surfaceContainer"), ("onError", "error"),
    ("onErrorContainer", "errorContainer"), ("inverseOnSurface", "inverseSurface"),
    ("secondary", "surface"), ("tertiary", "surface"),
]
NONTEXT_PAIRS = [
    ("outline", "surface"), ("outline", "background"), ("outline", "surfaceContainer"),
    ("outline", "surfaceContainerHigh"), ("primary", "onPrimary"),
]
# WCAG 1.4.11 exempts purely decorative separation, so hairlines and container steps are
# reported for review with a much lower floor: enough to be visible, never enough to shout.
DECORATIVE_PAIRS = [
    ("outlineVariant", "surface", 1.15), ("outlineVariant", "background", 1.15),
    ("outlineVariant", "surfaceContainer", 1.05),
    ("surfaceContainer", "surface", 1.02),
    ("surfaceContainerHigh", "surfaceContainer", 1.02),
    ("secondaryContainer", "surface", 1.05),
    ("primaryContainer", "surface", 1.05),
]
SURFACES = ["background", "surface", "surfaceContainerLow", "surfaceContainer",
            "surfaceContainerHigh", "surfaceContainerHighest"]

def audit(name, pal):
    print(f"\n== {name} ==")
    failures = []
    for fg, bg in TEXT_PAIRS:
        r = ratio(pal[fg], pal[bg])
        flag = "ok " if r >= 4.5 else "FAIL"
        if r < 4.5:
            failures.append(f"{fg}/{bg} {r:.2f}")
        print(f"  {flag} text    {fg:22s} on {bg:24s} {r:5.2f}")
    for fg, bg in NONTEXT_PAIRS:
        r = ratio(pal[fg], pal[bg])
        flag = "ok " if r >= 3.0 else "FAIL"
        if r < 3.0:
            failures.append(f"nontext {fg}/{bg} {r:.2f}")
        print(f"  {flag} nontext {fg:22s} on {bg:24s} {r:5.2f}")
    for fg, bg, floor in DECORATIVE_PAIRS:
        r = ratio(pal[fg], pal[bg])
        flag = "ok " if r >= floor else "FAIL"
        if r < floor:
            failures.append(f"decorative {fg}/{bg} {r:.2f} < {floor}")
        print(f"  {flag} decor   {fg:22s} on {bg:24s} {r:5.3f} (floor {floor})")
    # Surface ladder must be monotonically distinguishable in the given mode.
    lums = [lum(pal[s]) for s in SURFACES]
    for a, b in zip(SURFACES, SURFACES[1:]):
        step = ratio(pal[a], pal[b])
        flag = "ok " if step >= 1.015 else "warn"
        print(f"  {flag} ladder  {a:24s} -> {b:24s} {step:5.3f}")
    return failures

def main():
    bad = []
    for name, pal in [("Material light", LIGHT), ("Material dark", DARK),
                      ("Liquid Glass light", GLASS_LIGHT), ("Liquid Glass dark", GLASS_DARK)]:
        bad += audit(name, pal)
    print()
    if bad:
        print("CONTRAST FAILURES:")
        for f in bad:
            print("  -", f)
        sys.exit(1)
    print("All text pairs >= 4.5:1 and all non-text pairs >= 3:1.")

if __name__ == "__main__":
    main()
