#!/usr/bin/env python3
"""Turns generated floating-asset art into App UI drawables and an owner review sheet (issue #72).

Floating assets (leaves, pebbles, spores, droplets, light motes) are generated with a top-tier image
model on a plain white or black backdrop and finished by hand. This script does the mechanical rest:

1. Removes the backdrop by known-background matting: the backdrop colour is read from the image's
   corners, each pixel's opacity is how far it stands from that colour, and its colour is unmixed
   from the backdrop so edges carry no white or black fringe.
2. Bakes the depth layer's focus blur (far and near are defocused, mid stays sharp), on premultiplied
   alpha so blurred edges do not darken. The App UI never blurs these at runtime.
3. Exports lossy WebP with alpha at mdpi, hdpi, xhdpi, xxhdpi and xxxhdpi.
4. Writes `floating_assets_manifest.json` (ids, layers, sizes, bytes, and each asset's model, prompt and
   licence terms) and `contact_sheet.html`, the asset sheet the owner reviews per theme.

Nothing ships from here directly: after the owner approves a theme's sheet (a review comment on
issue #72), its WebPs are copied into `design/src/main/res/drawable-<density>/` and its entries are
added to `FloatingAssetManifest` in `:design` with `approved = true`.

Input layout (one PNG per asset, drawn at xxxhdpi, i.e. 4x the size it should appear at in dp)::

    INPUT/<theme>/<layer>/<asset_id>.png
    INPUT/<theme>/<layer>/<asset_id>.json   optional: {"model": ..., "prompt": ..., "licence": ...}

where <theme> is deep_forest, night_forest or earth_lichen, <layer> is far, mid or near, and
<asset_id> is a lower-case Android resource name such as `maple_leaf_01`. Use the backdrop that
contrasts with the subject: dark or saturated subjects on white, glowing motes on black.

Usage::

    python3 -m pip install -r tools/floating_assets/requirements.txt
    python3 tools/floating_assets/build_floating_assets.py INPUT OUTPUT [--backdrop auto|white|black]

Dependencies are Pillow and NumPy only, both free and open source.
"""

from __future__ import annotations

import argparse
import html
import json
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

# The density the source PNGs are drawn at, and each exported density's scale relative to mdpi.
SOURCE_DENSITY = "xxxhdpi"
DENSITY_SCALES: dict[str, float] = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}
# The density the contact sheet shows: what most review phones display.
SHEET_DENSITY = "xxhdpi"

# The themes and layers, with the names `:design` uses for them (ForestThemeId, DepthLayer).
THEMES: dict[str, str] = {
    "deep_forest": "DeepForest",
    "night_forest": "NightForest",
    "earth_lichen": "EarthLichen",
}
LAYERS: dict[str, str] = {"far": "Far", "mid": "Mid", "near": "Near"}

# Focus blur baked per layer, as a Gaussian radius in source (xxxhdpi) pixels. Mid stays sharp.
LAYER_BLUR_PX: dict[str, float] = {"far": 6.0, "mid": 0.0, "near": 12.0}
# Blurred edges spread this many radii, so trimming leaves that much room around the subject.
BLUR_SPREAD_RADII = 3.0
# Room left around every trimmed subject, in source pixels, so antialiased edges are never cut.
TRIM_PADDING_PX = 4

# Lossy WebP with alpha: quality and effort (0-6). The research's range is 85-90.
WEBP_QUALITY = 88
WEBP_METHOD = 6

# Matting: differences below this fraction of full range are backdrop noise (JPEG-ish grain).
ALPHA_NOISE_FLOOR = 0.03
# Opacity below this counts as empty when trimming, and is too thin to unmix a colour from.
ALPHA_EMPTY = 1.0 / 255.0
# The corners sampled to find the backdrop, as a fraction of the shorter side.
CORNER_FRACTION = 0.04
# The most the corners may differ from each other and still be one plain backdrop (0-1 per channel).
BACKDROP_TOLERANCE = 0.08
# Corners brighter than this are a white backdrop, darker a black one.
BACKDROP_LUMINANCE_SPLIT = 0.5
WHITE = np.array([1.0, 1.0, 1.0])
BLACK = np.array([0.0, 0.0, 0.0])
CHANNEL_MAX = 255.0

ASSET_ID = re.compile(r"^[a-z][a-z0-9_]*$")
MANIFEST_NAME = "floating_assets_manifest.json"
SHEET_NAME = "contact_sheet.html"
SIDECAR_FIELDS = ("model", "prompt", "licence")


class PipelineError(Exception):
    """A named failure: the run stops and says which asset and why."""


class UnknownTheme(PipelineError):
    pass


class UnknownLayer(PipelineError):
    pass


class InvalidAssetId(PipelineError):
    pass


class NonUniformBackdrop(PipelineError):
    pass


class EmptyAsset(PipelineError):
    pass


class InvalidSidecar(PipelineError):
    pass


class NoInputs(PipelineError):
    pass


@dataclass
class Asset:
    asset_id: str
    theme: str
    layer: str
    source: Path
    backdrop: str = ""
    sizes: dict[str, tuple[int, int]] = field(default_factory=dict)
    bytes: dict[str, int] = field(default_factory=dict)
    provenance: dict[str, str] = field(default_factory=dict)

    @property
    def drawable(self) -> str:
        return f"ambient_{self.theme}_{self.asset_id}"


def find_assets(input_dir: Path) -> list[Asset]:
    assets = []
    for png in sorted(input_dir.glob("*/*/*.png")):
        layer_dir = png.parent
        theme, layer, asset_id = layer_dir.parent.name, layer_dir.name, png.stem
        if theme not in THEMES:
            raise UnknownTheme(f"{png}: theme {theme!r} is not one of {sorted(THEMES)}")
        if layer not in LAYERS:
            raise UnknownLayer(f"{png}: layer {layer!r} is not one of {sorted(LAYERS)}")
        if not ASSET_ID.match(asset_id):
            raise InvalidAssetId(f"{png}: {asset_id!r} is not a lower-case Android resource name")
        assets.append(Asset(asset_id, theme, layer, png, provenance=read_sidecar(png.with_suffix(".json"))))
    if not assets:
        raise NoInputs(f"no <theme>/<layer>/<asset_id>.png found under {input_dir}")
    drawables = [a.drawable for a in assets]
    duplicates = sorted({d for d in drawables if drawables.count(d) > 1})
    if duplicates:
        raise InvalidAssetId(f"asset ids used in more than one layer of a theme: {duplicates}")
    return assets


def read_sidecar(path: Path) -> dict[str, str]:
    if not path.is_file():
        return {name: "" for name in SIDECAR_FIELDS}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise InvalidSidecar(f"{path}: {exc}") from exc
    if not isinstance(data, dict):
        raise InvalidSidecar(f"{path}: expected a JSON object")
    return {name: str(data.get(name, "")) for name in SIDECAR_FIELDS}


def detect_backdrop(rgb: np.ndarray, choice: str, name: str) -> np.ndarray:
    """The backdrop colour: the one asked for, or the corners' common colour when `auto`."""
    if choice == "white":
        return WHITE
    if choice == "black":
        return BLACK
    height, width, _ = rgb.shape
    patch = max(1, int(min(height, width) * CORNER_FRACTION))
    corners = [
        rgb[:patch, :patch],
        rgb[:patch, -patch:],
        rgb[-patch:, :patch],
        rgb[-patch:, -patch:],
    ]
    means = np.array([corner.reshape(-1, 3).mean(axis=0) for corner in corners])
    if float(np.ptp(means, axis=0).max()) > BACKDROP_TOLERANCE:
        raise NonUniformBackdrop(f"{name}: the corners differ; generate on a plain white or black backdrop")
    return WHITE if float(means.mean()) > BACKDROP_LUMINANCE_SPLIT else BLACK


def remove_backdrop(rgb: np.ndarray, backdrop: np.ndarray) -> np.ndarray:
    """Known-background matting: RGBA in 0-1 with the backdrop unmixed from every pixel's colour."""
    reach = np.maximum(backdrop, 1.0 - backdrop)
    alpha = (np.abs(rgb - backdrop) / reach).max(axis=2)
    alpha = np.clip((alpha - ALPHA_NOISE_FLOOR) / (1.0 - ALPHA_NOISE_FLOOR), 0.0, 1.0)
    safe = np.maximum(alpha, ALPHA_EMPTY)[..., None]
    colour = np.clip((rgb - (1.0 - alpha[..., None]) * backdrop) / safe, 0.0, 1.0)
    colour[alpha < ALPHA_EMPTY] = 0.0
    return np.dstack([colour, alpha])


def trim(rgba: np.ndarray, blur_px: float, name: str) -> np.ndarray:
    """Crops to the subject, leaving room for its baked blur to spread."""
    rows, cols = np.nonzero(rgba[..., 3] >= ALPHA_EMPTY)
    if rows.size == 0:
        raise EmptyAsset(f"{name}: nothing left once the backdrop is removed")
    pad = TRIM_PADDING_PX + int(np.ceil(blur_px * BLUR_SPREAD_RADII))
    top, bottom = rows.min(), rows.max() + 1
    left, right = cols.min(), cols.max() + 1
    cropped = rgba[top:bottom, left:right]
    return np.pad(cropped, ((pad, pad), (pad, pad), (0, 0)))


def to_premultiplied_image(rgba: np.ndarray) -> Image.Image:
    pixels = np.round(rgba * CHANNEL_MAX).astype(np.uint8)
    # An H x W x 4 uint8 array is RGBA; Pillow 13 no longer takes a mode here.
    return Image.fromarray(pixels).convert("RGBa")


def bake_blur(image: Image.Image, blur_px: float) -> Image.Image:
    """Blurs premultiplied colour so the edges fade to clear, not to black."""
    return image.filter(ImageFilter.GaussianBlur(blur_px)) if blur_px > 0 else image


def export(asset: Asset, image: Image.Image, output_dir: Path) -> None:
    source_scale = DENSITY_SCALES[SOURCE_DENSITY]
    for density, scale in DENSITY_SCALES.items():
        size = (
            max(1, round(image.width * scale / source_scale)),
            max(1, round(image.height * scale / source_scale)),
        )
        scaled = image.resize(size, Image.Resampling.LANCZOS).convert("RGBA")
        target = output_dir / "res" / f"drawable-{density}" / f"{asset.drawable}.webp"
        target.parent.mkdir(parents=True, exist_ok=True)
        scaled.save(target, "WEBP", quality=WEBP_QUALITY, method=WEBP_METHOD)
        asset.sizes[density] = size
        asset.bytes[density] = target.stat().st_size


def process(asset: Asset, output_dir: Path, backdrop_choice: str) -> None:
    with Image.open(asset.source) as opened:
        rgb = np.asarray(opened.convert("RGB"), dtype=np.float64) / CHANNEL_MAX
    backdrop = detect_backdrop(rgb, backdrop_choice, str(asset.source))
    asset.backdrop = "white" if backdrop is WHITE else "black"
    blur_px = LAYER_BLUR_PX[asset.layer]
    rgba = trim(remove_backdrop(rgb, backdrop), blur_px, str(asset.source))
    export(asset, bake_blur(to_premultiplied_image(rgba), blur_px), output_dir)


def write_manifest(assets: list[Asset], output_dir: Path) -> dict[str, int]:
    totals = {theme: sum(sum(a.bytes.values()) for a in assets if a.theme == theme) for theme in THEMES}
    manifest = {
        "generated_by": "tools/floating_assets/build_floating_assets.py",
        "source_density": SOURCE_DENSITY,
        "webp_quality": WEBP_QUALITY,
        "layer_blur_px": LAYER_BLUR_PX,
        "assets": [
            {
                "id": a.asset_id,
                "drawable": a.drawable,
                "theme": a.theme,
                "kotlin_theme": f"ForestThemeId.{THEMES[a.theme]}",
                "layer": a.layer,
                "kotlin_layer": f"DepthLayer.{LAYERS[a.layer]}",
                "backdrop": a.backdrop,
                "sizes_px": {d: list(s) for d, s in a.sizes.items()},
                "bytes": a.bytes,
                **a.provenance,
                # Only the owner's review comment on issue #72 approves an asset, and only in
                # FloatingAssetManifest; the pipeline never does.
                "approved": False,
            }
            for a in assets
        ],
        # Every density counts: a universal APK carries all of them. An app bundle delivers one.
        "apk_bytes_by_theme": totals,
        "apk_bytes_total": sum(totals.values()),
    }
    (output_dir / MANIFEST_NAME).write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return totals


SHEET_STYLE = """
body { margin: 0; padding: 24px 16px; font: 15px/1.5 system-ui, sans-serif; background: #f4f4f2; color: #1c1c1a; }
h1, h2, h3 { font-weight: 600; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 16px; }
.card { background: #ffffff; border-radius: 12px; padding: 12px; box-shadow: 0 1px 3px rgba(0, 0, 0, .12); }
.swatches { display: grid; grid-template-columns: repeat(3, 1fr); gap: 6px; }
.swatches div { display: flex; align-items: center; justify-content: center; min-height: 120px; border-radius: 8px; }
.swatches img { max-width: 100%; height: auto; }
.checker { background: repeating-conic-gradient(#cfcfcf 0 25%, #ffffff 0 50%) 0 0 / 16px 16px; }
.dark { background: #101410; }
.light { background: #f6f3e8; }
.meta { font-size: 13px; color: #55554f; overflow-wrap: anywhere; }
"""


def sheet_card(asset: Asset) -> str:
    image = f"res/drawable-{SHEET_DENSITY}/{asset.drawable}.webp"
    width, height = asset.sizes[SHEET_DENSITY]
    img = f'<img src="{html.escape(image)}" width="{width}" height="{height}" alt="{html.escape(asset.asset_id)}">'
    meta = "".join(
        f"<div><b>{html.escape(name)}:</b> {html.escape(asset.provenance.get(name) or 'not recorded')}</div>"
        for name in SIDECAR_FIELDS
    )
    total_kib = sum(asset.bytes.values()) / 1024
    return (
        '<div class="card">'
        f'<div class="swatches"><div class="checker">{img}</div><div class="dark">{img}</div><div class="light">{img}</div></div>'
        f"<h3>{html.escape(asset.asset_id)}</h3>"
        f'<div class="meta"><div><b>layer:</b> {asset.layer} &middot; <b>backdrop:</b> {asset.backdrop}</div>'
        f"<div><b>{SHEET_DENSITY}:</b> {width}&times;{height} px &middot; <b>all densities:</b> {total_kib:.1f} KiB</div>"
        f"{meta}</div></div>"
    )


def write_contact_sheet(assets: list[Asset], totals: dict[str, int], output_dir: Path) -> None:
    sections = []
    for theme in THEMES:
        themed = [a for a in assets if a.theme == theme]
        if not themed:
            continue
        rows = []
        for layer in LAYERS:
            cards = "".join(sheet_card(a) for a in themed if a.layer == layer)
            if cards:
                rows.append(f'<h3>{LAYERS[layer]} layer</h3><div class="grid">{cards}</div>')
        sections.append(
            f"<section><h2>{THEMES[theme]}</h2>"
            f"<p>{len(themed)} assets, {totals[theme] / 1024:.1f} KiB across all densities.</p>"
            f"{''.join(rows)}</section>"
        )
    page = (
        '<!doctype html><html lang="en"><head><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width, initial-scale=1">'
        f"<title>Floating asset sheet</title><style>{SHEET_STYLE}</style></head><body>"
        "<h1>Floating asset sheet</h1>"
        "<p>Each asset over transparency, a dark base and a light base. Approve a theme's sheet with a "
        "review comment on issue #72; only then are its assets added to the app.</p>"
        f"{''.join(sections)}</body></html>\n"
    )
    (output_dir / SHEET_NAME).write_text(page, encoding="utf-8")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("input", type=Path, help="folder of <theme>/<layer>/<asset_id>.png")
    parser.add_argument("output", type=Path, help="folder for res/, the manifest and the contact sheet")
    parser.add_argument("--backdrop", choices=("auto", "white", "black"), default="auto")
    args = parser.parse_args(argv)
    try:
        assets = find_assets(args.input)
        args.output.mkdir(parents=True, exist_ok=True)
        for asset in assets:
            process(asset, args.output, args.backdrop)
        totals = write_manifest(assets, args.output)
        write_contact_sheet(assets, totals, args.output)
    except PipelineError as failure:
        print(f"floating assets: {type(failure).__name__}: {failure}", file=sys.stderr)
        return 1
    print(f"{len(assets)} assets, {sum(totals.values()) / 1024:.1f} KiB in all; review {args.output / SHEET_NAME}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
