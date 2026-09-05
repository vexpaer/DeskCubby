# DeskCubby design system

DeskCubby is a personal cubby: a private desk with drawers. The visual language follows from
that — quiet surfaces, ink-forward type, one accent used sparingly, and structure carried by
contrast and hairlines instead of shadow.

**Quiet, intelligent, tactile, personal, refined.**

Everything below lives in `android/app/src/main/java/com/deskcubby/app/ui/theme/` and
`.../ui/components/`. Screens consume tokens and shared components; they do not declare their
own numbers.

## Colour — `theme/Theme.kt`

Four complete palettes (Material light/dark, Liquid Glass light/dark) plus the authored Organic
Future scheme and the user's Custom palette. Every role is declared explicitly. Before this,
Material and Liquid Glass only overrode `primary`/`surface`, so `surfaceContainer`, `onSurface`
and `outlineVariant` fell back to Material's cool purple-grey defaults (`#F3EDF7`, `#1C1B1F`,
`#CAC4D0`) and read as a visible hue mismatch on the green paper canvas.

The surface ladder is the core hierarchy, not decoration:

| Role | Light | Dark | Used for |
| --- | --- | --- | --- |
| `background` | `#F4F5F1` | `#0E110E` | the canvas a page scrolls on |
| `surface` | `#FAFBF8` | `#141814` | cards, bottom bar, navigation rail |
| `surfaceContainer` | `#EEF0EB` | `#171B17` | insets inside a card (code, image wells) |
| `outlineVariant` | `#DCE0D8` | `#2B312B` | hairlines |
| `outline` | `#78827A` | `#7E887C` | meaningful borders and icons (≥3:1) |

`primary` is reserved for **selection, values and at most one action per view** — never for card
titles. When the user picks a wallpaper, `background` becomes transparent and cards step up to
`surfaceContainerLowest` so they stay legible over the image.

`DeskCubbyColors` (in `theme/Tokens.kt`) names the four roles Material does not have:
`hairline`, `selectedContainer`, `insetSurface`, `accentWash`.

Palette changes must keep `.guide/contrast_check.py` green: every text pair ≥ 4.5:1, every
meaningful UI pair ≥ 3:1. Hairlines and container steps are decorative and are reported against
a lower floor (WCAG 1.4.11 exempts purely decorative separation).

```
python3 .guide/contrast_check.py
```

## Typography — `theme/Type.kt`

`AppTypography` replaced a bare `Typography()`. The Material defaults were tuned for Latin
marketing surfaces: a 57sp display nobody renders, and a 12sp `bodySmall` that was carrying most
of the app's supporting text (241 call sites) so everything looked the same size.

| Role | Size / line height | Weight | Tracking |
| --- | --- | --- | --- |
| `displayLarge` | 40 / 44 | SemiBold | −1.0 |
| `headlineSmall` | 19 / 25 | SemiBold | −0.2 |
| `titleMedium` | 16 / 22 | SemiBold | 0 |
| `titleSmall` | 14 / 19 | SemiBold | +0.05 |
| `bodyLarge` | 16 / 26 | Normal | 0 |
| `bodyMedium` | 15 / 23 | Normal | 0 |
| `bodySmall` | 13 / 19 | Normal | +0.05 |
| `labelLarge` | 13 / 18 | SemiBold | +0.15 |

Line height is ~1.6 and tracking is ~0 for body copy: positive tracking pulls Chinese glyphs
apart, and Simplified/Traditional Chinese, Korean and Japanese need the leading. Wide tracking
is reserved for the uppercase Latin eyebrow/label roles. `fontScale` (0.8–1.3) still multiplies
the whole scale.

`DeskCubbyType` adds the roles Material does not name: `eyebrow` (section labels), `metric` and
`metricCompact` (numeric readouts, with `tnum` so digits do not jitter as counts change),
`metricLabel`, `listTitle`, `listSubtitle`, `mono`, `reading`.

## Spacing, corner, motion — `theme/Tokens.kt`

`DeskCubbySpacing` is a 4dp scale (2/4/6/8/12/16/20/24/32/48) plus structural names: gutters
16/20/24 resolved from the window tier by `currentGutter()`, `cardPadding` 16, `cardGap` 12,
`sectionGap` 24, `listRowMinHeight` 52, `touchTarget` 48.

`DeskCubbyRadius` exposes Dp steps (8/10/14/20/26) and Shape roles (`control`, `field`, `card`,
`panel`, `sheet`). **These are different types** — `DeskCubbyRadius.md` is `14.dp`, while
`DeskCubbyRadius.card` is `RoundedCornerShape(14.dp)`. Passing one where the other is expected is
a compile error, not a silent bug.

`DeskCubbyMotion` keeps durations inside 120–280ms. Navigation previously cross-faded for 700ms,
which made every tab switch feel laggy; it is now a 280ms fade + 1/40-width slide + 0.994 scale.
`rememberAppMotion()` resolves the budget once and honours both the system animator switch and a
custom theme's `transitionMillis`, so `transitionMillis == 0` is a real reduce-motion opt-out.

## Components — `ui/components/`

`Primitives.kt` is the shared layer that replaced per-screen hand-rolling:

- `DcCard` — the standard card: one step above the canvas, hairline separated, clipped ripple,
  press scale 0.985, `selected` state.
- `DcListRow` — 52dp minimum touch target, fixed leading slot so titles align down a column,
  one secondary line.
- `DcSectionHeader` — uppercase eyebrow in the secondary colour. Section names are quiet;
  content carries the hierarchy.
- `DcStat` / `DcTag` / `DcInset` / `DcDivider` / `currentGutter()`.

`AppStates.kt` gives loading, empty and inline-hint states one shared geometry.

Every component resolves the visual style through `GlassPanel`, so Material, Liquid Glass and
Organic Future keep working from a single implementation. Do not fork a component per theme.

## Responsive

`ui/components/AdaptiveLayout.kt` derives `LayoutMode` from real window geometry, never from a
device name: `COMPACT` < 600dp, `MEDIUM` < 840dp, `EXPANDED` ≥ 840dp in landscape. Orientation
decides navigation placement (portrait → bottom bar, landscape → rail); width independently
decides pane count, so a portrait tablet gets a bottom bar with two-pane content.

Wide surfaces must cap their reading column rather than stretch: Home's workspace is capped at
1180dp and centred, and each pane is a lazy column so both sides recycle.

## Checks

There is no Android toolchain in every environment, so these run locally and CI is authoritative:

```
python3 .guide/contrast_check.py                                   # palette accessibility
python3 .guide/compose_balance_check.py <files...>                 # structural balance
python3 .guide/icon_import_check.py <files...>                     # Icons.* have imports
./gradlew testDebugUnitTest lintDebug assembleDebug                # CI
```

Both Kotlin checks pass all 412 files in the module and fail an injected defect, so a clean run
is a real signal rather than a formality.
