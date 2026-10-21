#!/usr/bin/env bash
# Bakes the stone grain of frosted stone glass (design/src/main/res/drawable-nodpi/design_stone_grain_*.webp)
# from Poly Haven's CC0 photographic scans: greyscale, 512 px, the broad shading removed with a
# tile-wrapping high-pass so only the fine mineral grain remains, centred on mid-grey so the
# Overlay blend only lightens and darkens. Needs curl and ImageMagick. Run from the repository root.
set -euo pipefail
out=design/src/main/res/drawable-nodpi
work=$(mktemp -d)
for pair in "rock_01 slate" "rock_2 obsidian" "rock_surface basalt"; do
  set -- $pair
  curl -sSf -o "$work/$1.jpg" "https://dl.polyhaven.org/file/ph-assets/Textures/jpg/1k/$1/$1_diff_1k.jpg"
  convert "$work/$1.jpg" -colorspace Gray -resize 512x512 \
    \( +clone -virtual-pixel tile -blur 0x16 \) \
    -compose Mathematics -define compose:args='0,1,-1,0.5' -composite \
    -contrast-stretch 0.5%x0.5% -quality 88 "$out/design_stone_grain_$2.webp"
done
rm -rf "$work"
