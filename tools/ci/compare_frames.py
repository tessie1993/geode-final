#!/usr/bin/env python3
"""Compares rendered Visual frames with the committed goldens (docs/ci.md).

Each PNG in <actual> must have a golden of the same name in <goldens>, and the mean absolute
difference over all RGBA channels must stay within <tolerance> (0-255 scale). SwiftShader on a
pinned Chrome is deterministic, so the tolerance only absorbs rounding noise; a real change to
a Style moves far more than that. Pillow is required.

  compare_frames.py <goldens dir> <actual dir> [tolerance]
"""
import os
import sys

from PIL import Image, ImageChops, ImageStat


def main(goldens, actual, tolerance=2.0):
    tolerance = float(tolerance)
    names = sorted(n for n in os.listdir(actual) if n.endswith(".png"))
    if not names:
        sys.exit(f"::error::no frames were rendered into {actual}")
    missing, changed = [], []
    for name in names:
        golden = os.path.join(goldens, name)
        if not os.path.exists(golden):
            missing.append(name)
            continue
        a = Image.open(golden).convert("RGBA")
        b = Image.open(os.path.join(actual, name)).convert("RGBA")
        if a.size != b.size:
            changed.append(f"{name}: size {a.size} -> {b.size}")
            continue
        diff = sum(ImageStat.Stat(ImageChops.difference(a, b)).mean) / 4.0
        if diff > tolerance:
            changed.append(f"{name}: mean difference {diff:.2f} > {tolerance}")
    stale = sorted(set(n for n in os.listdir(goldens) if n.endswith(".png")) - set(names)) if os.path.isdir(goldens) else []
    for name in missing:
        print(f"::error::no golden for {name}; record goldens (docs/ci.md)")
    for line in changed:
        print(f"::error::{line}")
    for name in stale:
        print(f"::error::golden {name} has no rendered frame; a Style was removed or renamed, record goldens")
    print(f"{len(names)} frames compared, {len(missing)} missing, {len(changed)} changed, {len(stale)} stale")
    return 1 if missing or changed or stale else 0


if __name__ == "__main__":
    if len(sys.argv) not in (3, 4):
        sys.exit(__doc__)
    sys.exit(main(*sys.argv[1:]))
