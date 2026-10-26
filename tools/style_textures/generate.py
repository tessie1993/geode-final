#!/usr/bin/env python3
"""The images Geode's own Style packs ship (#391), made from nothing but this file.

A shader Style may ship images in its pack ("textures" in its manifest, docs/style-packs.md). Geode ships
no one else's art, so every such image is drawn here from integer hashes, with the noise of
tools/milk_textures/generate.py: ours by construction, byte-identical on every run, and tiling at its
edges where the Style repeats it.

    python3 tools/style_textures/generate.py

writes each image into its Style's folder under engine/scenes/src/main/stylepacks/styles/.
"""

import importlib.util
import math
import os

HERE = os.path.dirname(os.path.abspath(__file__))
# The MilkDrop texture generator's noise and PNG writer, loaded by path: both scripts are generate.py.
_spec = importlib.util.spec_from_file_location("milk_textures", os.path.join(HERE, "..", "milk_textures", "generate.py"))
_milk = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_milk)
clamp, fbm, hash2, smooth, write_png = _milk.clamp, _milk.fbm, _milk.hash2, _milk.smooth, _milk.write_png

ROOT = os.path.normpath(os.path.join(HERE, "..", ".."))
STYLES = os.path.join(ROOT, "engine", "scenes", "src", "main", "stylepacks", "styles")
PAPER_SIZE = 256


def streaks(x, y, across, along, seed):
    """Value noise stretched along x: `across` cells over the unit square in y, `along` in x, tiling."""
    gx = x * along
    gy = y * across
    x0 = math.floor(gx)
    y0 = math.floor(gy)
    fx = smooth(gx - x0)
    fy = smooth(gy - y0)
    xa, xb = x0 % along, (x0 + 1) % along
    ya, yb = y0 % across, (y0 + 1) % across
    a = hash2(xa, ya, seed)
    b = hash2(xb, ya, seed)
    c = hash2(xa, yb, seed)
    d = hash2(xb, yb, seed)
    top = a + (b - a) * fx
    return top + ((c + (d - c) * fx) - top) * fy


def fibre(value):
    """A thin bright or dark line where stretched noise crosses its middle."""
    return max(0.0, 1.0 - abs(value - 0.5) * 18.0)


def paper(u, v):
    """Washi-like paper for Origami Peaks: a warm white, a soft mottle, and fibres laid both ways. Its mean is
    close to 1, so the facets keep their brightness and only gain a grain."""
    mottle = fbm(u, v, 4, 4, 11) - 0.5
    grain = hash2(int(u * PAPER_SIZE), int(v * PAPER_SIZE), 23) - 0.5
    lines = fibre(streaks(u, v, 48, 6, 31)) + fibre(streaks(v, u, 48, 6, 37)) + 0.6 * fibre(streaks(u, v, 96, 12, 41))
    shade = 0.93 + 0.08 * mottle + 0.025 * grain + 0.05 * lines
    return (clamp(shade * 1.01), clamp(shade * 0.995), clamp(shade * 0.965))


IMAGES = {
    ("origami_peaks", "paper.png"): (PAPER_SIZE, lambda x, y: paper((x + 0.5) / PAPER_SIZE, (y + 0.5) / PAPER_SIZE)),
}


def main():
    for (style, name), (size, pixel) in IMAGES.items():
        path = os.path.join(STYLES, style, name)
        write_png(path, size, size, pixel)
        print(f"{os.path.relpath(path, ROOT)}: {os.path.getsize(path)} bytes")


if __name__ == "__main__":
    main()
