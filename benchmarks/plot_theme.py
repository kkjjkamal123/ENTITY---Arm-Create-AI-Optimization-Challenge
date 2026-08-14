"""Shared plot palette, so every figure can be emitted for both GitHub themes.

A single white-background PNG is a bright slab in a dark-mode README, and a
transparent one is worse: the dark axis labels vanish against a dark canvas.
matplotlib cannot ask the reader which theme they are on, so each figure is
rendered twice and the README chooses with `<picture>` and `prefers-color-scheme`.

The palette was duplicated verbatim across five plot scripts before this file
existed, which is why the dark variants could not simply be added one at a time.

    python3 benchmarks/plot_four_arm.py                  # light
    PLOT_THEME=dark python3 benchmarks/plot_four_arm.py  # dark

The light values are the originals, unchanged, so the existing PNGs regenerate
byte-comparable. The dark values are GitHub's own dark canvas and foreground, so
the figure sits flush against the README rather than floating in its own shade.
"""

from __future__ import annotations

import os

DARK = os.environ.get("PLOT_THEME", "light").strip().lower() == "dark"

if DARK:
    SURFACE = "#0d1117"      # GitHub dark canvas
    INK = "#e6edf3"          # GitHub dark default foreground
    INK_MUTED = "#9198a1"
    GRID = "#30363d"
else:
    SURFACE = "#fcfcfb"
    INK = "#0b0b0b"
    INK_MUTED = "#52514e"
    GRID = "#e3e2df"

# Series colours are chosen to clear 4.5:1 against both canvases, so the two
# variants stay recognisably the same chart rather than becoming two designs.
BLUE = "#5aa2f0" if DARK else "#2a78d6"
ORANGE = "#ff8a5c" if DARK else "#eb6834"

MUTED = INK_MUTED  # plot_contributed.py's name for the same role


def suffixed(name: str) -> str:
    """`foo.png` -> `foo-dark.png` when rendering the dark variant."""
    if not DARK:
        return name
    stem, dot, ext = name.rpartition(".")
    return f"{stem}-dark{dot}{ext}" if dot else f"{name}-dark"
