#!/usr/bin/env python3
"""Turns an Element's prompt manifest into finished App UI assets, and ships only the approved ones (#104).

Every Element has a prompt manifest, tools/element_assets/elements/<element>.json, with an entry for
every slot: H0-H3, N1-N5, I1-I5, L1-L2, B1, S1, T1, F1-F9 (images), A1-A5 (sound FX) and V1-V6
(reference videos). An image prompt is the slot's subject, then the Element's style anchor, then the
suffix for the slot's backdrop (isolated-white, isolated-black, tile or full-bleed). The steps, in order:

    plan      print every prompt and the gflow command that would make it
    generate  run gflow into build/element-assets/<element>/raw/ (a dry run unless --go: it spends credits)
    adopt     record an image made outside this tool, such as the approved anchor after its upscale
    finish    matte, trim and export the picked variants at every density; cut the sounds to OGG
    sheet     write the contact sheet the owner reviews (build/element-assets/<element>/sheet.html)
    approve   mark finished slots approved, with the URL of the owner's comment on the asset ticket
    ship      copy the approved slots into design/src/main/res and design/element-assets.json
    report    print the Element's APK weight as a markdown table for its asset ticket
    check     validate the prompt manifests and the shipped manifest (standard library only)

Sprites are drawn at 4x their dp size, so the finished master is xxxhdpi and the other densities are
scaled down from it. Everything under build/ is the working record and never reaches git: only
`ship` writes into the app, and `check` fails if any element_* or ambient_* resource ships without
an approved entry. Image work needs Pillow and NumPy (requirements.txt); sounds need ffmpeg on PATH.

    python tools/element_assets/element_assets.py plan water
    python tools/element_assets/element_assets.py generate water --slots H1,H2 --go
    python tools/element_assets/element_assets.py finish water --pick H1=2,H2=1
    python tools/element_assets/element_assets.py check --self-test
"""

from __future__ import annotations

import argparse
import copy
import html
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import TYPE_CHECKING, Callable
from urllib.parse import quote

if TYPE_CHECKING:
    import numpy as np
    from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
REPO = "tessie1993/geode-final"
FORMAT_VERSION = 1

IMAGE, SOUND, VIDEO = "image", "sound", "video"
WHITE, BLACK, TILE, FULL_BLEED = "isolated-white", "isolated-black", "tile", "full-bleed"
ISOLATED = (WHITE, BLACK)
SUFFIXES = {
    WHITE: "isolated and centred on a perfectly flat pure white background, no shadow, no vignette, no gradient, no text, no border",
    BLACK: "isolated and centred on a perfectly flat pure black background, no shadow, no vignette, no gradient, no text, no border",
    TILE: "seamless tileable texture, even lighting, no focal point, no text",
    FULL_BLEED: "full-frame scene, no text, no people, no logos",
}
SOUND_TEMPLATE = (
    "A completely black screen, nothing visible. Audio only: {sound}. No music, no voices, "
    "no ambience except what is described, a moment of silence before and after."
)

IMAGE_MODEL = "nano-pro"
IMAGE_VARIANTS = 4
SOUND_SECONDS = (4, 6, 8)
DEFAULT_SOUND_SECONDS = 4
VIDEO_SECONDS = 8
VIDEO_ASPECT = "9:16"
VIDEO_METHODS = ("t2v", "i2v")
LOOP_SLOT = "A4"

# Sprites are drawn at 4x their dp size: the master is xxxhdpi, and each density is a fraction of it.
DENSITIES = {"mdpi": 0.25, "hdpi": 0.375, "xhdpi": 0.5, "xxhdpi": 0.75, "xxxhdpi": 1.0}
MASTER_DENSITY = "xxxhdpi"
WEBP_QUALITY = 88
WEBP_METHOD = 6

# Backdrop check: the corner patches (a fraction of the shorter side) must sit this close to the
# expected white or black, and be this even, on a 0-1 scale.
CORNER_FRACTION = 0.04
BACKDROP_TOLERANCE = 0.06
BACKDROP_SPREAD = 0.05
# White matting: alpha ramps smoothly from transparent to opaque over this distance from white.
WHITE_RAMP = (0.02, 0.30)
# Black matting: brightness below this is backdrop noise.
BLACK_FLOOR = 0.02
ALPHA_FLOOR = 1.0 / 255.0
# A sprite whose mean alpha falls below this after matting is empty.
MIN_COVERAGE = 0.002
TRIM_PADDING_PX = 8

LOUDNESS_LUFS = -20
TRUE_PEAK_DB = -2
SILENCE_DB = -50
FADE_IN_S = 0.005
FADE_OUT_S = 0.04
LOOP_CROSSFADE_S = 1.5

ELEMENT_KEYS = {"version", "element", "ticket", "styleAnchor", "project", "anchor", "slots"}
ANCHOR_KEYS = {"slot", "mediaId", "project", "prompt", "approval"}
SLOT_KEYS = {
    IMAGE: {"subject", "backdrop", "aspect", "extra", "name", "note"},
    SOUND: {"sound", "duration", "note"},
    VIDEO: {"prompt", "method", "from", "note"},
}
SHIPPED_KEYS = {"version", "entries"}
ENTRY_FIELDS = ("id", "element", "slot", "kind", "files", "model", "prompt", "seed", "mediaId", "licence", "approved", "approval")
SHIPPED_PREFIXES = ("element_", "ambient_")
LICENCE = "Generated with Google Flow on the owner's account for Geode; Google's generative AI terms apply."

NAME = re.compile(r"^[a-z][a-z0-9_]*$")
UUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
APPROVAL = re.compile(r"^https://github\.com/" + re.escape(REPO) + r"/issues/(\d+)#issuecomment-\d+$")


@dataclass(frozen=True)
class SlotType:
    kind: str
    purpose: str
    aspect: str = ""
    backdrops: tuple[str, ...] = ()


def _slot_table() -> dict[str, SlotType]:
    table = {"H0": SlotType(IMAGE, "Home hero backdrop, full bleed", "9:16", (FULL_BLEED,))}
    for n, depth in enumerate(("far", "mid", "near"), 1):
        table[f"H{n}"] = SlotType(IMAGE, f"Home parallax, {depth} layer", "9:16", ISOLATED)
    for n in range(1, 6):
        table[f"N{n}"] = SlotType(IMAGE, f"Home navigation object {n}", "1:1", ISOLATED)
    for n in range(1, 6):
        table[f"I{n}"] = SlotType(IMAGE, f"Icon art for the Bar, {n} of 5", "1:1", ISOLATED)
    table["L1"] = SlotType(IMAGE, "Library item object (holds the cover art)", "1:1", ISOLATED)
    table["L2"] = SlotType(IMAGE, "Library scrolling ground, vertical tile", "9:16", (TILE,))
    table["B1"] = SlotType(IMAGE, "Bar surface texture, horizontal tile", "16:9", (TILE,))
    table["S1"] = SlotType(IMAGE, "Calm label surface for text, low contrast", "1:1", (TILE,))
    table["T1"] = SlotType(IMAGE, "Touch effect sprite, the Android 12 fallback", "1:1", (BLACK,))
    for n in range(1, 10):
        depth = ("far", "mid", "near")[(n - 1) // 3]
        table[f"F{n}"] = SlotType(IMAGE, f"Floating asset, {depth}", "1:1", ISOLATED)
    for n, use in enumerate(("Tap", "Navigate", "Library item touch", "Home ambient loop", "Toggle"), 1):
        table[f"A{n}"] = SlotType(SOUND, use)
    uses = ("Home hero motion", "Home hero motion", "Home hero motion", "Touch study", "Navigation transition", "Library scroll")
    for n, use in enumerate(uses, 1):
        table[f"V{n}"] = SlotType(VIDEO, use)
    return table


SLOTS = _slot_table()


class PipelineError(Exception):
    """A named failure: the run stops and says which slot and why."""


class ManifestError(PipelineError):
    pass


class NonUniformBackdrop(PipelineError):
    pass


class EmptySprite(PipelineError):
    pass


class ToolFailed(PipelineError):
    pass


@dataclass(frozen=True)
class Paths:
    root: Path

    @property
    def elements(self) -> Path:
        return self.root / "tools" / "element_assets" / "elements"

    @property
    def shipped(self) -> Path:
        return self.root / "design" / "element-assets.json"

    @property
    def res(self) -> Path:
        return self.root / "design" / "src" / "main" / "res"

    def work(self, element: str) -> Path:
        return self.root / "build" / "element-assets" / element


# ---- check (standard library only) ----------------------------------------------------------------


def is_text(value: object) -> bool:
    return isinstance(value, str) and value.strip() != ""


def is_int(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def is_uuid(value: object) -> bool:
    return isinstance(value, str) and UUID.match(value) is not None


class Report:
    def __init__(self) -> None:
        self.errors: list[str] = []

    def error(self, where: str, message: str) -> None:
        self.errors.append(f"{where}: {message}")


def load_json(path: Path, report: Report) -> dict | None:
    try:
        doc = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        report.error(path.name, "missing")
        return None
    except (json.JSONDecodeError, UnicodeDecodeError) as e:
        report.error(path.name, f"not JSON: {e}")
        return None
    if not isinstance(doc, dict):
        report.error(path.name, "not a JSON object")
        return None
    if doc.get("version") != FORMAT_VERSION or isinstance(doc.get("version"), bool):
        report.error(path.name, f'"version" must be {FORMAT_VERSION}')
        return None
    return doc


def check_approval(url: object, ticket: object, where: str, report: Report) -> None:
    match = APPROVAL.match(url) if isinstance(url, str) else None
    if match is None:
        report.error(where, f'"approval" must be the URL of the owner\'s comment (https://github.com/{REPO}/issues/<n>#issuecomment-<id>)')
    elif is_int(ticket) and int(match.group(1)) != ticket:
        report.error(where, f"the approval must be a comment on the Element's asset ticket, #{ticket}")


def check_anchor(anchor: object, ticket: object, where: str, report: Report) -> None:
    if anchor is None:
        return
    if not isinstance(anchor, dict):
        report.error(where, '"anchor" must be an object or null')
        return
    unknown = set(anchor) - ANCHOR_KEYS
    if unknown:
        report.error(where, f"the anchor has unknown keys {sorted(unknown)}")
    slot = anchor.get("slot")
    if slot not in SLOTS or SLOTS[slot].kind != IMAGE:
        report.error(where, 'the anchor\'s "slot" must be an image slot')
    if not is_uuid(anchor.get("mediaId")):
        report.error(where, 'the anchor needs its gflow "mediaId"')
    if not is_uuid(anchor.get("project")):
        report.error(where, 'the anchor needs the Flow "project" it lives in')
    if not is_text(anchor.get("prompt")):
        report.error(where, 'the anchor needs the "prompt" it was made with')
    check_approval(anchor.get("approval"), ticket, f"{where} anchor", report)


def check_slot(slot: str, spec: object, where: str, report: Report, names: dict[str, str]) -> None:
    if not isinstance(spec, dict):
        report.error(where, "must be an object")
        return
    slot_type = SLOTS[slot]
    unknown = set(spec) - SLOT_KEYS[slot_type.kind]
    if unknown:
        report.error(where, f"unknown keys {sorted(unknown)}")
    for key in ("note", "extra"):
        if key in spec and not is_text(spec[key]):
            report.error(where, f'"{key}" must be a non-empty string')
    if slot_type.kind == IMAGE:
        if not is_text(spec.get("subject")):
            report.error(where, 'needs a "subject"')
        backdrop = spec.get("backdrop")
        if backdrop not in SUFFIXES:
            report.error(where, f'"backdrop" must be one of {sorted(SUFFIXES)}')
        elif backdrop not in slot_type.backdrops:
            report.error(where, f'{slot} takes a backdrop of {list(slot_type.backdrops)}, not "{backdrop}"')
        if spec.get("aspect") != slot_type.aspect:
            report.error(where, f'"aspect" must be "{slot_type.aspect}"')
        name = spec.get("name")
        if slot.startswith("F"):
            if not isinstance(name, str) or not NAME.match(name):
                report.error(where, 'a floating asset needs a "name" of lowercase letters, digits and _')
            elif name in names:
                report.error(where, f'"name" {name} is already used by {names[name]}')
            else:
                names[name] = slot
        elif "name" in spec:
            report.error(where, 'only floating assets (F1-F9) take a "name"')
    elif slot_type.kind == SOUND:
        if not is_text(spec.get("sound")):
            report.error(where, 'needs a "sound"')
        if "duration" in spec and (not is_int(spec["duration"]) or spec["duration"] not in SOUND_SECONDS):
            report.error(where, f'"duration" must be one of {list(SOUND_SECONDS)} seconds')
    else:
        if not is_text(spec.get("prompt")):
            report.error(where, 'needs a "prompt"')
        method = spec.get("method")
        if method not in VIDEO_METHODS:
            report.error(where, f'"method" must be one of {list(VIDEO_METHODS)}')
        source = spec.get("from")
        if method == "i2v" and (source not in SLOTS or SLOTS[source].kind != IMAGE):
            report.error(where, 'an i2v video needs "from": the image slot it starts from')
        if method == "t2v" and "from" in spec:
            report.error(where, 'a t2v video starts from no image, so it takes no "from"')


def check_prompt_manifest(doc: dict, stem: str, report: Report) -> None:
    where = f"{stem}.json"
    unknown = set(doc) - ELEMENT_KEYS
    if unknown:
        report.error(where, f"unknown keys {sorted(unknown)}")
    if doc.get("element") != stem or not NAME.match(stem):
        report.error(where, f'"element" must be the file name, "{stem}" (lowercase letters, digits and _)')
    ticket = doc.get("ticket")
    if not is_int(ticket) or ticket <= 0:
        report.error(where, '"ticket" must be the number of the Element\'s asset ticket')
    if not is_text(doc.get("styleAnchor")):
        report.error(where, 'needs a "styleAnchor"')
    if not is_uuid(doc.get("project")):
        report.error(where, '"project" must be the Element\'s Flow project id')
    check_anchor(doc.get("anchor"), ticket, where, report)
    slots = doc.get("slots")
    if not isinstance(slots, dict):
        report.error(where, '"slots" must be an object')
        return
    for missing in (slot for slot in SLOTS if slot not in slots):
        report.error(where, f"slot {missing} is missing")
    for stray in sorted(set(slots) - set(SLOTS)):
        report.error(where, f'"{stray}" is not a slot')
    names: dict[str, str] = {}
    for slot, spec in slots.items():
        if slot in SLOTS:
            check_slot(slot, spec, f"{where} {slot}", report, names)


def resource_name_for(element: str, slot: str, floating_name: str) -> str:
    if slot.startswith("F"):
        return f"ambient_{element}_{floating_name}"
    return f"element_{element}_{slot.lower()}"


def expected_files(kind: str, resource: str) -> list[str]:
    if kind == SOUND:
        return [f"raw/{resource}.ogg"]
    return [f"drawable-{density}/{resource}.webp" for density in DENSITIES]


def expected_resource(manifest: dict | None, element: str, slot: str) -> str | None:
    if not slot.startswith("F"):
        return resource_name_for(element, slot, "")
    try:
        name = manifest["slots"][slot]["name"] if manifest else None
    except (KeyError, TypeError):
        return None
    return resource_name_for(element, slot, name) if isinstance(name, str) else None


def check_entry(entry: dict, label: str, manifests: dict[str, dict], res: Path, report: Report, listed: set[str]) -> None:
    if entry["approved"] is not True:
        report.error(label, "is not approved; only approved entries ship")
    element, slot, kind = entry["element"], entry["slot"], entry["kind"]
    manifest = manifests.get(element) if isinstance(element, str) else None
    if manifest is None:
        report.error(label, f'"element" {element!r} has no prompt manifest')
    check_approval(entry["approval"], manifest.get("ticket") if manifest else None, label, report)
    if slot not in SLOTS:
        report.error(label, f'"slot" {slot!r} is not a slot')
        return
    if kind == VIDEO or SLOTS[slot].kind == VIDEO:
        report.error(label, "videos are motion references and never ship")
        return
    if kind != SLOTS[slot].kind:
        report.error(label, f'"kind" must be "{SLOTS[slot].kind}" for {slot}')
        return
    resource = expected_resource(manifest, element, slot)
    if resource is not None and entry["id"] != resource:
        report.error(label, f'"id" must be "{resource}"')
    for key in ("model", "prompt", "mediaId", "licence"):
        if not is_text(entry[key]):
            report.error(label, f'"{key}" must be a non-empty string')
    if entry["seed"] is not None and not is_int(entry["seed"]):
        report.error(label, '"seed" must be a whole number, or null when gflow does not give one')
    wanted = expected_files(kind, entry["id"])
    files = entry["files"]
    if not isinstance(files, list) or sorted(files) != sorted(wanted):
        report.error(label, f'"files" must be {wanted}')
    for rel in wanted:
        listed.add(rel)
        if not (res / rel).is_file():
            report.error(label, f"{rel} is missing")


def check_shipped(doc: dict, manifests: dict[str, dict], res: Path, report: Report) -> None:
    where = "element-assets.json"
    unknown = set(doc) - SHIPPED_KEYS
    if unknown:
        report.error(where, f"unknown keys {sorted(unknown)}")
    entries = doc.get("entries")
    if not isinstance(entries, list):
        report.error(where, '"entries" must be an array')
        return
    seen: set[str] = set()
    listed: set[str] = set()
    for index, entry in enumerate(entries):
        label = f"{where} entry {index}"
        if not isinstance(entry, dict):
            report.error(label, "must be an object")
            continue
        missing = [key for key in ENTRY_FIELDS if key not in entry]
        if missing:
            report.error(label, f"missing fields {missing}")
            continue
        stray = set(entry) - set(ENTRY_FIELDS)
        if stray:
            report.error(label, f"unknown keys {sorted(stray)}")
        entry_id = entry["id"]
        if not isinstance(entry_id, str) or not NAME.match(entry_id) or not entry_id.startswith(SHIPPED_PREFIXES):
            report.error(label, '"id" must be an element_* or ambient_* resource name')
            continue
        label = f"{where} {entry_id}"
        if entry_id in seen:
            report.error(label, "is used twice; ids are unique across all Elements")
        seen.add(entry_id)
        check_entry(entry, label, manifests, res, report, listed)
    if res.is_dir():
        for path in sorted(res.rglob("*")):
            if path.is_file() and path.name.startswith(SHIPPED_PREFIXES):
                rel = path.relative_to(res).as_posix()
                if rel not in listed:
                    report.error(rel, "ships without an approved entry in element-assets.json")


def check(paths: Paths) -> list[str]:
    report = Report()
    manifests: dict[str, dict] = {}
    for path in sorted(paths.elements.glob("*.json")):
        doc = load_json(path, report)
        if doc is not None:
            check_prompt_manifest(doc, path.stem, report)
            manifests[path.stem] = doc
    if not manifests:
        report.error(str(paths.elements), "holds no prompt manifest")
    shipped = load_json(paths.shipped, report)
    if shipped is not None:
        check_shipped(shipped, manifests, paths.res, report)
    return report.errors


# ---- self-test ------------------------------------------------------------------------------------

TEST_UUID = "00000000-0000-4000-8000-000000000000"
DELETE = object()


def sample_element(element: str = "water", ticket: int = 105) -> dict:
    """A complete, valid prompt manifest with placeholder prompts, for the self-test and the unit tests."""
    slots: dict[str, dict] = {}
    for slot, slot_type in SLOTS.items():
        if slot_type.kind == IMAGE:
            slots[slot] = {"subject": f"a test subject for {slot}", "backdrop": slot_type.backdrops[0], "aspect": slot_type.aspect}
            if slot.startswith("F"):
                slots[slot]["name"] = f"mote_{slot.lower()}"
        elif slot_type.kind == SOUND:
            slots[slot] = {"sound": "a soft tap", "duration": DEFAULT_SOUND_SECONDS}
        else:
            slots[slot] = {"prompt": "a slow drift", "method": "i2v", "from": "H0"}
    return {"version": FORMAT_VERSION, "element": element, "ticket": ticket, "styleAnchor": "a calm test scene",
            "project": TEST_UUID, "anchor": None, "slots": slots}


def sample_entry(element: str, slot: str, resource: str, ticket: int) -> dict:
    kind = SLOTS[slot].kind
    return {"id": resource, "element": element, "slot": slot, "kind": kind, "files": expected_files(kind, resource),
            "model": IMAGE_MODEL, "prompt": "a test prompt", "seed": None, "mediaId": TEST_UUID, "licence": LICENCE,
            "approved": True, "approval": f"https://github.com/{REPO}/issues/{ticket}#issuecomment-1"}


def write_check_tree(root: Path, elements: dict[str, dict], shipped: dict, files: list[str]) -> Paths:
    paths = Paths(root)
    paths.elements.mkdir(parents=True)
    for name, doc in elements.items():
        (paths.elements / f"{name}.json").write_text(json.dumps(doc), encoding="utf-8")
    paths.shipped.parent.mkdir(parents=True, exist_ok=True)
    paths.shipped.write_text(json.dumps(shipped), encoding="utf-8")
    for rel in files:
        (paths.res / rel).parent.mkdir(parents=True, exist_ok=True)
        (paths.res / rel).write_bytes(b"asset")
    return paths


def run_case(mutate: Callable[[dict, dict, list], None]) -> list[str]:
    elements = {"water": sample_element("water", 105), "wood": sample_element("wood", 106)}
    entries = [
        sample_entry("water", "H1", "element_water_h1", 105),
        sample_entry("water", "A1", "element_water_a1", 105),
        sample_entry("wood", "F1", "ambient_wood_mote_f1", 106),
    ]
    shipped = {"version": FORMAT_VERSION, "entries": entries}
    files = [rel for entry in entries for rel in entry["files"]]
    mutate(elements, shipped, files)
    with tempfile.TemporaryDirectory() as tmp:
        return check(write_check_tree(Path(tmp), elements, shipped, files))


def self_test() -> int:
    good = run_case(lambda e, s, f: None)
    if good:
        print("self-test: the good tree was rejected:\n  " + "\n  ".join(good))
        return 1

    def slot(slot_id: str, key: str, value: object) -> Callable[[dict, dict, list], None]:
        def mutate(e: dict, s: dict, f: list) -> None:
            spec = e["water"]["slots"][slot_id]
            if value is DELETE:
                del spec[key]
            else:
                spec[key] = value
        return mutate

    def entry(index: int, key: str, value: object) -> Callable[[dict, dict, list], None]:
        def mutate(e: dict, s: dict, f: list) -> None:
            if value is DELETE:
                del s["entries"][index][key]
            else:
                s["entries"][index][key] = value
        return mutate

    anchor = {"slot": "H0", "mediaId": TEST_UUID, "project": TEST_UUID, "prompt": "p",
              "approval": f"https://github.com/{REPO}/issues/999#issuecomment-1"}
    video = sample_entry("water", "V1", "element_water_v1", 105) | {"kind": VIDEO, "files": ["raw/element_water_v1.mp4"]}
    cases: dict[str, Callable[[dict, dict, list], None]] = {
        "wrong version": lambda e, s, f: e["water"].update({"version": 2}),
        "element differs from the file name": lambda e, s, f: e["water"].update({"element": "river"}),
        "bad project id": lambda e, s, f: e["water"].update({"project": "water"}),
        "no ticket": lambda e, s, f: e["water"].pop("ticket"),
        "anchor approved on another ticket": lambda e, s, f: e["water"].update({"anchor": anchor}),
        "missing slot": lambda e, s, f: e["water"]["slots"].pop("N3"),
        "unknown slot": lambda e, s, f: e["water"]["slots"].update({"Z9": {"subject": "x"}}),
        "missing subject": slot("H1", "subject", DELETE),
        "unknown backdrop": slot("H1", "backdrop", "grey"),
        "backdrop the slot does not take": slot("N1", "backdrop", TILE),
        "wrong aspect": slot("B1", "aspect", "9:16"),
        "unknown slot key": slot("H1", "colour", "teal"),
        "floating asset without a name": slot("F1", "name", DELETE),
        "floating name used twice": slot("F2", "name", "mote_f1"),
        "name on a non-floating slot": slot("N1", "name", "pebble"),
        "sound without a sound": slot("A1", "sound", DELETE),
        "bad sound duration": slot("A1", "duration", 5),
        "unknown video method": slot("V1", "method", "r2v"),
        "i2v without a source": slot("V1", "from", DELETE),
        "t2v with a source": slot("V1", "method", "t2v"),
        "entry not approved": entry(0, "approved", False),
        "approval is not a comment URL": entry(0, "approval", "approved"),
        "approval on another Element's ticket": entry(0, "approval", f"https://github.com/{REPO}/issues/106#issuecomment-1"),
        "duplicate id": lambda e, s, f: s["entries"].append(copy.deepcopy(s["entries"][0])),
        "missing licence": entry(0, "licence", DELETE),
        "missing seed key": entry(0, "seed", DELETE),
        "seed that is not a number": entry(0, "seed", "random"),
        "empty media id": entry(0, "mediaId", ""),
        "file missing at a density": lambda e, s, f: f.remove("drawable-hdpi/element_water_h1.webp"),
        "files list short a density": entry(0, "files", expected_files(IMAGE, "element_water_h1")[1:]),
        "video shipped": lambda e, s, f: (s["entries"].append(video), f.append("raw/element_water_v1.mp4")),
        "kind does not match the slot": entry(1, "kind", IMAGE),
        "id does not match the slot": entry(0, "id", "element_water_h2"),
        "floating asset id does not match its name": entry(2, "id", "ambient_wood_leaf"),
        "Element without a prompt manifest": lambda e, s, f: e.pop("wood"),
        "unlisted element file ships": lambda e, s, f: f.append("drawable-mdpi/element_water_n1.webp"),
        "unlisted ambient file ships": lambda e, s, f: f.append("raw/ambient_water_drip.ogg"),
    }
    failed = [name for name, mutate in cases.items() if not run_case(mutate)]
    if failed:
        print("self-test: these violations were not caught: " + ", ".join(failed))
        return 1
    print(f"self-test: the good tree passes and all {len(cases)} violations are caught")
    return 0


# ---- prompt manifests -----------------------------------------------------------------------------


@dataclass(frozen=True)
class Anchor:
    slot: str
    media_id: str
    project: str
    prompt: str
    approval: str


@dataclass(frozen=True)
class SlotSpec:
    slot: str
    kind: str
    subject: str = ""
    backdrop: str = ""
    aspect: str = ""
    extra: str = ""
    name: str = ""
    sound: str = ""
    duration: int = DEFAULT_SOUND_SECONDS
    prompt: str = ""
    method: str = ""
    source: str = ""

    @property
    def purpose(self) -> str:
        return SLOTS[self.slot].purpose


@dataclass(frozen=True)
class ElementManifest:
    element: str
    ticket: int
    style_anchor: str
    project: str
    anchor: Anchor | None
    slots: dict[str, SlotSpec]


def parse_element(doc: dict) -> ElementManifest:
    anchor_doc = doc.get("anchor")
    anchor = None
    if anchor_doc:
        anchor = Anchor(anchor_doc["slot"], anchor_doc["mediaId"], anchor_doc["project"], anchor_doc["prompt"], anchor_doc["approval"])
    slots: dict[str, SlotSpec] = {}
    for slot in SLOTS:
        spec = doc["slots"][slot]
        slots[slot] = SlotSpec(
            slot=slot,
            kind=SLOTS[slot].kind,
            subject=spec.get("subject", ""),
            backdrop=spec.get("backdrop", ""),
            aspect=spec.get("aspect", ""),
            extra=spec.get("extra", ""),
            name=spec.get("name", ""),
            sound=spec.get("sound", ""),
            duration=spec.get("duration", DEFAULT_SOUND_SECONDS),
            prompt=spec.get("prompt", ""),
            method=spec.get("method", ""),
            source=spec.get("from", ""),
        )
    return ElementManifest(doc["element"], doc["ticket"], doc["styleAnchor"], doc["project"], anchor, slots)


def read_element(paths: Paths, element: str) -> ElementManifest:
    report = Report()
    doc = load_json(paths.elements / f"{element}.json", report)
    if doc is not None:
        check_prompt_manifest(doc, element, report)
    if report.errors or doc is None:
        raise ManifestError(f"the {element} prompt manifest is not valid:\n  " + "\n  ".join(report.errors))
    return parse_element(doc)


def compose_prompt(manifest: ElementManifest, spec: SlotSpec) -> str:
    if spec.kind == IMAGE:
        parts = [spec.subject, manifest.style_anchor, SUFFIXES[spec.backdrop]]
        if spec.extra:
            parts.append(spec.extra)
        return ", ".join(parts)
    if spec.kind == SOUND:
        return SOUND_TEMPLATE.format(sound=spec.sound)
    return spec.prompt


def resource_name(element: str, spec: SlotSpec) -> str:
    return resource_name_for(element, spec.slot, spec.name)


def parse_slots(text: str | None, manifest: ElementManifest) -> list[SlotSpec]:
    if not text:
        return list(manifest.slots.values())
    wanted = [s.strip().upper() for s in text.split(",") if s.strip()]
    unknown = [s for s in wanted if s not in SLOTS]
    if unknown:
        raise PipelineError(f"not slots: {unknown}")
    return [manifest.slots[s] for s in wanted]


# ---- working record -------------------------------------------------------------------------------


@dataclass
class Variant:
    file: str
    media_id: str | None
    seed: int | None
    model: str
    prompt: str

    def to_json(self) -> dict:
        return {"file": self.file, "mediaId": self.media_id, "seed": self.seed, "model": self.model, "prompt": self.prompt}

    @staticmethod
    def from_json(doc: dict) -> Variant:
        return Variant(doc["file"], doc.get("mediaId"), doc.get("seed"), doc.get("model", ""), doc.get("prompt", ""))


@dataclass
class SlotRecord:
    variants: list[Variant] = field(default_factory=list)
    pick: int | None = None
    finished: dict[str, str] = field(default_factory=dict)
    approval: str = ""

    def picked(self) -> Variant | None:
        if self.pick is None or not 1 <= self.pick <= len(self.variants):
            return None
        return self.variants[self.pick - 1]

    def to_json(self) -> dict:
        return {"variants": [v.to_json() for v in self.variants], "pick": self.pick, "finished": self.finished, "approval": self.approval}

    @staticmethod
    def from_json(doc: dict) -> SlotRecord:
        return SlotRecord([Variant.from_json(v) for v in doc.get("variants", [])], doc.get("pick"),
                          dict(doc.get("finished", {})), doc.get("approval", ""))


@dataclass
class Record:
    element: str
    slots: dict[str, SlotRecord] = field(default_factory=dict)

    def slot(self, slot: str) -> SlotRecord:
        return self.slots.setdefault(slot, SlotRecord())

    def to_json(self) -> dict:
        ordered = {slot: self.slots[slot].to_json() for slot in SLOTS if slot in self.slots}
        return {"version": FORMAT_VERSION, "element": self.element, "slots": ordered}


def load_record(paths: Paths, element: str) -> Record:
    path = paths.work(element) / "record.json"
    if not path.is_file():
        return Record(element)
    doc = json.loads(path.read_text(encoding="utf-8"))
    return Record(element, {slot: SlotRecord.from_json(s) for slot, s in doc.get("slots", {}).items()})


def save_record(paths: Paths, record: Record) -> None:
    path = paths.work(record.element) / "record.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(record.to_json(), indent=2) + "\n", encoding="utf-8")


# ---- generate (gflow) -----------------------------------------------------------------------------

Runner = Callable[[list[str]], "subprocess.CompletedProcess[str]"]


def run_tool(argv: list[str]) -> subprocess.CompletedProcess[str]:
    env = {**os.environ, "GFLOW_CLI_UPDATE_CHECK": "0", "PYTHONUTF8": "1"}
    return subprocess.run(argv, capture_output=True, text=True, encoding="utf-8", errors="replace", env=env, check=False)


def reference_for(manifest: ElementManifest, spec: SlotSpec, record: Record, work: Path, strict: bool = True) -> str | None:
    """What an image is made from (the approved anchor) or a video starts from (its source slot's pick).

    A local file is preferred, since Flow accepts an uploaded file on every host; the anchor's media
    id is the fallback.
    """
    anchor = manifest.anchor
    if spec.kind == IMAGE:
        if anchor is None or spec.slot == anchor.slot:
            return None
        source = anchor.slot
    elif spec.kind == VIDEO and spec.method == "i2v":
        source = spec.source
    else:
        return None
    picked = record.slots.get(source, SlotRecord()).picked()
    if picked is not None:
        return str(work / picked.file)
    if anchor is not None and source == anchor.slot:
        return anchor.media_id
    if strict:
        raise PipelineError(f"{spec.slot} starts from {source}, which has no picked variant yet")
    return f"<the pick of {source}>"


def gflow_command(manifest: ElementManifest, spec: SlotSpec, out_dir: Path, ref: str | None) -> list[str]:
    prompt = compose_prompt(manifest, spec)
    tail = ["--project", manifest.project, "--json"]
    if spec.kind == IMAGE:
        head = ["gflow", "image", "i2i", prompt, "--ref", ref] if ref else ["gflow", "image", "t2i", prompt]
        return head + ["--model", IMAGE_MODEL, "--aspect", spec.aspect, "-n", str(IMAGE_VARIANTS), "--out", str(out_dir)] + tail
    if spec.kind == SOUND:
        seconds = str(spec.duration)
        return ["gflow", "video", "t2v", prompt, "--duration", seconds, "--aspect", VIDEO_ASPECT, "--out-dir", str(out_dir)] + tail
    head = ["gflow", "video", "i2v", "--initial-frame", str(ref), prompt] if spec.method == "i2v" else ["gflow", "video", "t2v", prompt]
    return head + ["--duration", str(VIDEO_SECONDS), "--aspect", VIDEO_ASPECT, "--out-dir", str(out_dir)] + tail


def result_items(payload: object) -> list[dict]:
    if isinstance(payload, list):
        return [item for item in payload if isinstance(item, dict)]
    if isinstance(payload, dict):
        for key in ("images", "results", "videos", "items"):
            if isinstance(payload.get(key), list):
                return [item for item in payload[key] if isinstance(item, dict)]
        return [payload]
    return []


def match_item(path: Path, items: list[dict], index: int, count: int) -> dict:
    for item in items:
        local = item.get("local_path")
        if isinstance(local, str) and Path(local).name == path.name:
            return item
    for item in items:
        ident = item.get("media_name") or item.get("media_id")
        if isinstance(ident, str) and ident and ident in path.name:
            return item
    return items[index] if len(items) == count and index < len(items) else {}


def media_id_of(path: Path, item: dict) -> str | None:
    ident = item.get("media_name") or item.get("media_id")
    if isinstance(ident, str) and ident:
        return ident
    stem = path.stem.rsplit("_", 1)[0]
    return stem if UUID.match(stem) else None


def seed_of(item: dict) -> int | None:
    seed = item.get("seed")
    # gflow reports a synthetic 0 where Flow gives no seed.
    return seed if is_int(seed) and seed > 0 else None


def problem(result: subprocess.CompletedProcess[str]) -> str:
    try:
        payload = json.loads(result.stdout)
        if isinstance(payload, dict):
            return str(payload.get("detail") or payload.get("title") or payload)
    except ValueError:
        pass
    lines = [line for line in (result.stderr or "").splitlines() if line.strip()]
    return lines[-1] if lines else "no detail"


def generate_slot(paths: Paths, manifest: ElementManifest, record: Record, spec: SlotSpec, ref: str | None, run: Runner) -> list[Variant]:
    work = paths.work(manifest.element)
    out_dir = work / "raw" / spec.slot
    out_dir.mkdir(parents=True, exist_ok=True)
    before = set(out_dir.iterdir())
    result = run(gflow_command(manifest, spec, out_dir, ref))
    if result.returncode != 0:
        raise ToolFailed(f"{spec.slot}: gflow exited {result.returncode}: {problem(result)}")
    try:
        items = result_items(json.loads(result.stdout))
    except ValueError as e:
        raise ToolFailed(f"{spec.slot}: gflow did not return JSON") from e
    for item in items:
        local = item.get("local_path")
        if isinstance(local, str) and Path(local).is_file() and Path(local).parent.resolve() != out_dir.resolve():
            shutil.copy2(local, out_dir / Path(local).name)
    files = sorted(p for p in out_dir.iterdir() if p.is_file() and p not in before)
    if not files:
        raise ToolFailed(f"{spec.slot}: gflow reported success but wrote no file")
    model = IMAGE_MODEL if spec.kind == IMAGE else str(next((i["model"] for i in items if i.get("model")), "veo"))
    prompt = compose_prompt(manifest, spec)
    variants = []
    for index, path in enumerate(files):
        item = match_item(path, items, index, len(files))
        variants.append(Variant(path.relative_to(work).as_posix(), media_id_of(path, item), seed_of(item), model, prompt))
    record.slot(spec.slot).variants.extend(variants)
    return variants


# ---- finish: images -------------------------------------------------------------------------------


def load_rgb(path: Path) -> np.ndarray:
    import numpy as np
    from PIL import Image

    with Image.open(path) as opened:
        return np.asarray(opened.convert("RGB"), dtype=np.float32) / np.float32(255.0)


def check_backdrop(rgb: np.ndarray, backdrop: str, label: str) -> None:
    """Fails unless every corner patch is the flat white or black the matting expects."""
    import numpy as np

    expected = 1.0 if backdrop == WHITE else 0.0
    colour = "white" if backdrop == WHITE else "black"
    height, width, _ = rgb.shape
    patch = max(1, int(min(height, width) * CORNER_FRACTION))
    corners = {
        "top left": rgb[:patch, :patch],
        "top right": rgb[:patch, -patch:],
        "bottom left": rgb[-patch:, :patch],
        "bottom right": rgb[-patch:, -patch:],
    }
    for where, corner in corners.items():
        pixels = corner.reshape(-1, 3)
        off = float(np.abs(pixels.mean(axis=0) - expected).max())
        spread = float(pixels.std(axis=0).max())
        if off > BACKDROP_TOLERANCE or spread > BACKDROP_SPREAD:
            raise NonUniformBackdrop(f"{label}: the {where} corner is not flat {colour} "
                                     f"(off by {off:.2f}, spread {spread:.2f}); generate it again on a flat {colour} backdrop")


def _unmix(rgb: np.ndarray, alpha: np.ndarray, backdrop: float) -> np.ndarray:
    import numpy as np

    safe = np.maximum(alpha, ALPHA_FLOOR)[..., None]
    colour = np.clip((rgb - (1.0 - alpha[..., None]) * backdrop) / safe, 0.0, 1.0)
    colour[alpha < ALPHA_FLOOR] = 0.0
    return colour


def matte_white(rgb: np.ndarray) -> np.ndarray:
    """Alpha from each pixel's distance to white, on a soft ramp; the white is unmixed from the colour."""
    import numpy as np

    low, high = WHITE_RAMP
    t = np.clip(((1.0 - rgb).max(axis=2) - low) / (high - low), 0.0, 1.0)
    alpha = t * t * (3.0 - 2.0 * t)
    return np.dstack([_unmix(rgb, alpha, 1.0), alpha])


def matte_black(rgb: np.ndarray) -> np.ndarray:
    """For glows and bubbles on black: alpha keyed on brightness, colour un-premultiplied, so soft light stays soft."""
    import numpy as np

    alpha = np.clip((rgb.max(axis=2) - BLACK_FLOOR) / (1.0 - BLACK_FLOOR), 0.0, 1.0)
    return np.dstack([_unmix(rgb, alpha, 0.0), alpha])


def check_not_empty(rgba: np.ndarray, label: str) -> None:
    coverage = float(rgba[..., 3].mean())
    if coverage < MIN_COVERAGE:
        raise EmptySprite(f"{label}: almost nothing is left once the backdrop is removed (coverage {coverage:.4f})")


def trim(rgba: np.ndarray, label: str, padding: int = TRIM_PADDING_PX) -> np.ndarray:
    import numpy as np

    rows, cols = np.nonzero(rgba[..., 3] >= ALPHA_FLOOR)
    if rows.size == 0:
        raise EmptySprite(f"{label}: nothing is left once the backdrop is removed")
    cropped = rgba[rows.min():rows.max() + 1, cols.min():cols.max() + 1]
    return np.pad(cropped, ((padding, padding), (padding, padding), (0, 0)))


def density_sizes(width: int, height: int) -> dict[str, tuple[int, int]]:
    return {density: (max(1, round(width * scale)), max(1, round(height * scale))) for density, scale in DENSITIES.items()}


def export_densities(image: Image.Image, out_dir: Path, name: str) -> dict[str, Path]:
    """Writes the master (xxxhdpi) and every smaller density as WebP. Pillow resizes RGBA premultiplied,
    so edges do not darken."""
    from PIL import Image as PILImage

    source = image if image.mode == "RGBA" else image.convert("RGB")
    written: dict[str, Path] = {}
    for density, size in density_sizes(*image.size).items():
        scaled = source if size == image.size else source.resize(size, PILImage.Resampling.LANCZOS)
        target = out_dir / f"drawable-{density}" / f"{name}.webp"
        target.parent.mkdir(parents=True, exist_ok=True)
        scaled.save(target, "WEBP", quality=WEBP_QUALITY, method=WEBP_METHOD)
        written[density] = target
    return written


def finish_image(source: Path, backdrop: str, out_dir: Path, name: str, label: str) -> dict[str, Path]:
    import numpy as np
    from PIL import Image as PILImage

    rgb = load_rgb(source)
    if backdrop in ISOLATED:
        check_backdrop(rgb, backdrop, label)
        rgba = matte_white(rgb) if backdrop == WHITE else matte_black(rgb)
        check_not_empty(rgba, label)
        pixels = np.round(trim(rgba, label) * 255.0).astype(np.uint8)
    else:
        pixels = np.round(rgb * 255.0).astype(np.uint8)
    return export_densities(PILImage.fromarray(pixels), out_dir, name)


# ---- finish: sounds -------------------------------------------------------------------------------


def loudnorm() -> str:
    return f"loudnorm=I={LOUDNESS_LUFS}:TP={TRUE_PEAK_DB}:LRA=7"


def one_shot_command(source: Path, target: Path) -> list[str]:
    """Trims silence at both ends, normalises loudness and fades in and out (the fade-out runs reversed)."""
    trim_start = f"silenceremove=start_periods=1:start_threshold={SILENCE_DB}dB:start_silence=0.05"
    chain = ",".join([
        trim_start, "areverse", trim_start, "areverse", loudnorm(),
        f"afade=t=in:d={FADE_IN_S}", "areverse", f"afade=t=in:d={FADE_OUT_S}", "areverse",
    ])
    return ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(source), "-vn", "-ac", "1",
            "-af", chain, "-ar", "48000", "-c:a", "libvorbis", "-q:a", "5", str(target)]


def loop_command(source: Path, target: Path, duration: float, crossfade: float = LOOP_CROSSFADE_S) -> list[str]:
    """Cross-fades the tail into the head, so the loop's end runs into its start without a seam."""
    if duration < 3 * crossfade:
        raise PipelineError(f"{source.name}: {duration:.1f} s is too short for a {crossfade} s loop crossfade")
    end = duration - crossfade
    graph = ";".join([
        f"[0:a]aformat=channel_layouts=mono,{loudnorm()},aresample=48000,asplit=3[head][body][tail]",
        f"[head]atrim=0:{crossfade},asetpts=PTS-STARTPTS,afade=t=in:d={crossfade}:curve=qsin[in]",
        f"[tail]atrim=start={end},asetpts=PTS-STARTPTS,afade=t=out:d={crossfade}:curve=qsin[out]",
        "[in][out]amix=inputs=2:normalize=0[seam]",
        f"[body]atrim={crossfade}:{end},asetpts=PTS-STARTPTS[middle]",
        "[seam][middle]concat=n=2:v=0:a=1[loop]",
    ])
    return ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(source), "-filter_complex", graph,
            "-map", "[loop]", "-ac", "1", "-c:a", "libvorbis", "-q:a", "5", str(target)]


def probe_duration(path: Path, run: Runner) -> float:
    result = run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", str(path)])
    try:
        return float(result.stdout.strip())
    except ValueError as e:
        raise ToolFailed(f"{path.name}: ffprobe could not read its length") from e


def finish_sound(source: Path, target: Path, loop: bool, run: Runner) -> Path:
    target.parent.mkdir(parents=True, exist_ok=True)
    argv = loop_command(source, target, probe_duration(source, run)) if loop else one_shot_command(source, target)
    result = run(argv)
    if result.returncode != 0 or not target.is_file():
        raise ToolFailed(f"{source.name}: ffmpeg failed: {problem(result)}")
    return target


# ---- finish, approve, ship and report -------------------------------------------------------------


def parse_picks(text: str) -> dict[str, int]:
    picks: dict[str, int] = {}
    for part in (p.strip() for p in text.split(",") if p.strip()):
        slot, _, number = part.partition("=")
        slot = slot.strip().upper()
        if slot not in SLOTS or not number.strip().isdigit():
            raise PipelineError(f'"{part}" is not SLOT=NUMBER (for example H0=2)')
        picks[slot] = int(number)
    return picks


def finish(paths: Paths, manifest: ElementManifest, record: Record, picks: dict[str, int], run: Runner = run_tool) -> None:
    work = paths.work(manifest.element)
    for slot, number in picks.items():
        spec = manifest.slots[slot]
        slot_record = record.slot(slot)
        if not 1 <= number <= len(slot_record.variants):
            raise PipelineError(f"{slot} has {len(slot_record.variants)} variants; there is no #{number}")
        source = work / slot_record.variants[number - 1].file
        label = f"{slot} #{number} ({source.name})"
        name = resource_name(manifest.element, spec)
        finished_dir = work / "finished"
        if spec.kind == IMAGE:
            written = finish_image(source, spec.backdrop, finished_dir, name, label)
            finished = {density: path.relative_to(work).as_posix() for density, path in written.items()}
        elif spec.kind == SOUND:
            target = finish_sound(source, finished_dir / "raw" / f"{name}.ogg", slot == LOOP_SLOT, run)
            finished = {"ogg": target.relative_to(work).as_posix()}
        else:
            target = finished_dir / "video" / f"{slot}{source.suffix}"
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
            finished = {"video": target.relative_to(work).as_posix()}
        slot_record.pick, slot_record.finished, slot_record.approval = number, finished, ""
        save_record(paths, record)
        print(f"{slot}: finished #{number} as {name}")


def approve(manifest: ElementManifest, record: Record, url: str, slots: list[SlotSpec]) -> list[str]:
    report = Report()
    check_approval(url, manifest.ticket, "approve", report)
    if report.errors:
        raise PipelineError(report.errors[0])
    approved = []
    for spec in slots:
        slot_record = record.slots.get(spec.slot)
        if spec.kind == VIDEO or slot_record is None or not slot_record.finished:
            continue
        slot_record.approval = url
        approved.append(spec.slot)
    return approved


def load_shipped(paths: Paths) -> dict:
    if not paths.shipped.is_file():
        return {"version": FORMAT_VERSION, "entries": []}
    return json.loads(paths.shipped.read_text(encoding="utf-8"))


def ship(paths: Paths, manifest: ElementManifest, record: Record) -> list[str]:
    """Copies every approved, finished slot into the app and records it in the shipped manifest."""
    work = paths.work(manifest.element)
    shipped = load_shipped(paths)
    entries = {entry["id"]: entry for entry in shipped.get("entries", [])}
    done = []
    for slot, spec in manifest.slots.items():
        slot_record = record.slots.get(slot)
        variant = slot_record.picked() if slot_record else None
        if spec.kind == VIDEO or slot_record is None or variant is None or not slot_record.approval or not slot_record.finished:
            continue
        name = resource_name(manifest.element, spec)
        files = expected_files(spec.kind, name)
        keys = list(DENSITIES) if spec.kind == IMAGE else ["ogg"]
        sources = [work / slot_record.finished.get(key, "") for key in keys]
        missing = [source.name for source in sources if not source.is_file()]
        if missing:
            raise PipelineError(f"{slot}: finished files are missing ({missing}); run finish again")
        for rel, source in zip(files, sources):
            target = paths.res / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
        entries[name] = {
            "id": name, "element": manifest.element, "slot": slot, "kind": spec.kind, "files": files,
            "model": variant.model, "prompt": variant.prompt, "seed": variant.seed, "mediaId": variant.media_id,
            "licence": LICENCE, "approved": True, "approval": slot_record.approval,
        }
        done.append(name)
    shipped = {"version": FORMAT_VERSION, "entries": [entries[key] for key in sorted(entries)]}
    paths.shipped.parent.mkdir(parents=True, exist_ok=True)
    paths.shipped.write_text(json.dumps(shipped, indent=2) + "\n", encoding="utf-8")
    return done


def apk_weight(paths: Paths, element: str) -> list[tuple[str, int]]:
    rows = []
    for entry in load_shipped(paths).get("entries", []):
        if entry.get("element") == element:
            for rel in entry.get("files", []):
                path = paths.res / rel
                rows.append((rel, path.stat().st_size if path.is_file() else 0))
    return sorted(rows)


def report_markdown(element: str, rows: list[tuple[str, int]]) -> str:
    if not rows:
        return f"No approved {element} assets ship yet: the APK weight is 0 bytes."
    total = sum(size for _, size in rows)
    lines = [f"**{element} APK weight:** {total:,} bytes ({total / 1024:.1f} KiB) across {len(rows)} files.", "",
             "| File | Bytes |", "|---|---:|"]
    lines += [f"| `{rel}` | {size:,} |" for rel, size in rows]
    lines.append(f"| **Total** | **{total:,}** |")
    return "\n".join(lines)


# ---- contact sheet --------------------------------------------------------------------------------

SHEET_STYLE = """
body { margin: 0; padding: 24px 16px; font: 15px/1.5 system-ui, sans-serif; background: #f4f4f2; color: #1c1c1a; }
section { background: #fff; border-radius: 12px; padding: 12px 16px; margin: 0 0 16px; box-shadow: 0 1px 3px rgba(0,0,0,.12); }
h2 { margin: 4px 0; font-size: 18px; } h2 span { font-weight: 400; color: #55554f; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 12px; }
figure { margin: 0; padding: 8px; border: 2px solid transparent; border-radius: 10px; }
figure.picked { border-color: #e0a030; background: #fff8ea; }
.swatches { display: grid; grid-template-columns: repeat(3, 1fr); gap: 4px; }
.swatches div { display: flex; align-items: center; justify-content: center; min-height: 110px; border-radius: 6px; }
.swatches img { max-width: 100%; max-height: 220px; }
.checker { background: repeating-conic-gradient(#cfcfcf 0 25%, #fff 0 50%) 0 0 / 16px 16px; }
.dark { background: #0d1414; } .light { background: #f6f3e8; }
.meta, figcaption, .prompt { font-size: 13px; color: #55554f; overflow-wrap: anywhere; }
.badge { background: #e0a030; color: #fff; border-radius: 4px; padding: 0 6px; font-weight: 600; }
video { width: 100%; max-height: 420px; background: #000; } audio { width: 100%; }
"""


def _src(rel: str) -> str:
    return html.escape(quote(rel))


def sheet_media(kind: str, rel: str, alt: str) -> str:
    if kind == IMAGE:
        img = f'<img src="{_src(rel)}" alt="{html.escape(alt)}" loading="lazy">'
        return f'<div class="swatches"><div class="checker">{img}</div><div class="dark">{img}</div><div class="light">{img}</div></div>'
    if kind == SOUND:
        return f'<audio controls preload="none" src="{_src(rel)}"></audio>'
    return f'<video controls loop muted preload="metadata" src="{_src(rel)}"></video>'


def sheet_section(manifest: ElementManifest, spec: SlotSpec, slot_record: SlotRecord | None, work: Path) -> str:
    name = resource_name(manifest.element, spec)
    prompt = compose_prompt(manifest, spec)
    details = f"<b>id</b> {html.escape(name)}"
    if spec.kind == IMAGE:
        details += f" &middot; <b>backdrop</b> {spec.backdrop} &middot; <b>aspect</b> {spec.aspect}"
    if spec.kind == VIDEO:
        details += " &middot; motion reference, never shipped"
    figures = []
    for number, variant in enumerate(slot_record.variants if slot_record else [], 1):
        picked = slot_record is not None and slot_record.pick == number
        badge = ' <span class="badge">picked</span>' if picked else ""
        extra = "" if variant.prompt == prompt else f"<br>prompt: {html.escape(variant.prompt)}"
        figures.append(
            f'<figure class="{"picked" if picked else ""}">{sheet_media(spec.kind, variant.file, f"{spec.slot} #{number}")}'
            f"<figcaption>#{number}{badge} &middot; media id {html.escape(str(variant.media_id))} &middot; "
            f"seed {html.escape(str(variant.seed))}{extra}</figcaption></figure>"
        )
    if slot_record and slot_record.finished:
        rel = slot_record.finished.get(MASTER_DENSITY) or slot_record.finished.get("ogg") or slot_record.finished.get("video", "")
        size = sum((work / r).stat().st_size for r in slot_record.finished.values() if (work / r).is_file())
        approval = f' &middot; approved: <a href="{html.escape(slot_record.approval)}">comment</a>' if slot_record.approval else ""
        figures.append(f'<figure>{sheet_media(spec.kind, rel, f"{spec.slot} finished")}'
                       f"<figcaption>finished #{slot_record.pick} &middot; {size / 1024:.1f} KiB in all{approval}</figcaption></figure>")
    body = "".join(figures) or "<p class=\"meta\">Not generated yet.</p>"
    return (f'<section id="{spec.slot}"><h2>{spec.slot} <span>{html.escape(spec.purpose)}</span></h2>'
            f'<div class="meta">{details}</div><p class="prompt">{html.escape(prompt)}</p><div class="grid">{body}</div></section>')


def write_sheet(paths: Paths, manifest: ElementManifest, record: Record) -> Path:
    work = paths.work(manifest.element)
    work.mkdir(parents=True, exist_ok=True)
    sections = "".join(sheet_section(manifest, spec, record.slots.get(slot), work) for slot, spec in manifest.slots.items())
    ticket = f"https://github.com/{REPO}/issues/{manifest.ticket}"
    page = (
        '<!doctype html><html lang="en"><head><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width, initial-scale=1">'
        f"<title>{manifest.element} asset sheet</title><style>{SHEET_STYLE}</style></head><body>"
        f"<h1>{html.escape(manifest.element.capitalize())} asset sheet</h1>"
        f"<p>Each image over transparency, a dark ground and a light ground; the picked variant is outlined. "
        f'Approve the sheet in a comment on <a href="{ticket}">#{manifest.ticket}</a>; only approved slots ship.</p>'
        f"{sections}</body></html>\n"
    )
    target = work / "sheet.html"
    target.write_text(page, encoding="utf-8")
    return target


# ---- command line ---------------------------------------------------------------------------------


def print_plan(paths: Paths, manifest: ElementManifest, record: Record, slots: list[SlotSpec], override: str | None) -> None:
    work = paths.work(manifest.element)
    for spec in slots:
        target = "a motion reference, never shipped" if spec.kind == VIDEO else resource_name(manifest.element, spec)
        print(f"{spec.slot}  {spec.purpose}  ->  {target}")
        print(f"  prompt: {compose_prompt(manifest, spec)}")
        if manifest.anchor and spec.slot == manifest.anchor.slot:
            print(f"  the approved anchor, media id {manifest.anchor.media_id}: record its file with `adopt`, not generate")
        else:
            ref = override or reference_for(manifest, spec, record, work, strict=False)
            print(f"  $ {shlex.join(gflow_command(manifest, spec, work / 'raw' / spec.slot, ref))}")
        print()


def command_generate(paths: Paths, args: argparse.Namespace, manifest: ElementManifest) -> int:
    record = load_record(paths, manifest.element)
    slots = parse_slots(args.slots, manifest)
    if not args.go:
        print_plan(paths, manifest, record, slots, args.ref)
        print("Dry run: nothing was sent. Add --go to run gflow; every run spends Flow credits.")
        return 0
    work = paths.work(manifest.element)
    for spec in slots:
        if manifest.anchor and spec.slot == manifest.anchor.slot:
            print(f"{spec.slot}: skipped, it is the approved anchor; record its file with `adopt`")
            continue
        ref = args.ref or reference_for(manifest, spec, record, work)
        variants = generate_slot(paths, manifest, record, spec, ref, run_tool)
        save_record(paths, record)
        first = len(record.slot(spec.slot).variants) - len(variants) + 1
        print(f"{spec.slot}: {len(variants)} variants, #{first} to #{first + len(variants) - 1}")
    return 0


def command_adopt(paths: Paths, args: argparse.Namespace, manifest: ElementManifest) -> int:
    slot = args.slot.upper()
    if slot not in SLOTS or SLOTS[slot].kind != IMAGE:
        raise PipelineError(f"{slot} is not an image slot")
    spec = manifest.slots[slot]
    is_anchor = manifest.anchor is not None and slot == manifest.anchor.slot
    media_id = args.media_id or (manifest.anchor.media_id if is_anchor and manifest.anchor else None)
    if not media_id:
        raise PipelineError("give the image's gflow --media-id")
    prompt = args.prompt or (manifest.anchor.prompt if is_anchor and manifest.anchor else compose_prompt(manifest, spec))
    source = Path(args.file)
    if not source.is_file():
        raise PipelineError(f"{source} does not exist")
    work = paths.work(manifest.element)
    target = work / "raw" / slot / source.name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)
    record = load_record(paths, manifest.element)
    record.slot(slot).variants.append(Variant(target.relative_to(work).as_posix(), media_id, args.seed, args.model, prompt))
    save_record(paths, record)
    print(f"{slot}: recorded as variant #{len(record.slot(slot).variants)}")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("plan", "generate", "adopt", "finish", "sheet", "approve", "ship", "report"):
        command = sub.add_parser(name)
        command.add_argument("element")
        if name in ("plan", "generate", "approve"):
            command.add_argument("--slots", help="comma-separated slots, such as H1,N1 (default: all)")
        if name in ("plan", "generate"):
            command.add_argument("--ref", help="the reference image or start frame to use instead of the anchor (path or media id)")
        if name == "generate":
            command.add_argument("--go", action="store_true", help="really run gflow; this spends Flow credits")
        if name == "adopt":
            command.add_argument("slot")
            command.add_argument("file")
            command.add_argument("--media-id")
            command.add_argument("--seed", type=int)
            command.add_argument("--model", default=IMAGE_MODEL)
            command.add_argument("--prompt")
        if name == "finish":
            command.add_argument("--pick", required=True, help="SLOT=VARIANT pairs, such as H0=2,N1=1")
        if name == "approve":
            command.add_argument("--url", required=True, help="the owner's approval comment on the Element's asset ticket")
    check_parser = sub.add_parser("check")
    check_parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    paths = Paths(ROOT)

    if args.command == "check":
        if args.self_test:
            return self_test()
        errors = check(paths)
        if errors:
            print(f"element asset check failed ({len(errors)}):")
            for error in errors:
                print(f"  {error}")
            return 1
        shipped = load_shipped(paths).get("entries", [])
        print(f"element assets ok: {len(list(paths.elements.glob('*.json')))} prompt manifests, {len(shipped)} shipped entries")
        return 0
    try:
        if args.command == "report":
            print(report_markdown(args.element, apk_weight(paths, args.element)))
            return 0
        manifest = read_element(paths, args.element)
        if args.command == "plan":
            print_plan(paths, manifest, load_record(paths, args.element), parse_slots(args.slots, manifest), args.ref)
        elif args.command == "generate":
            return command_generate(paths, args, manifest)
        elif args.command == "adopt":
            return command_adopt(paths, args, manifest)
        elif args.command == "finish":
            finish(paths, manifest, load_record(paths, args.element), parse_picks(args.pick))
        elif args.command == "sheet":
            print(f"contact sheet: {write_sheet(paths, manifest, load_record(paths, args.element))}")
        elif args.command == "approve":
            record = load_record(paths, args.element)
            approved = approve(manifest, record, args.url, parse_slots(args.slots, manifest))
            save_record(paths, record)
            print(f"approved: {', '.join(approved) or 'nothing (no finished slots)'}")
        elif args.command == "ship":
            done = ship(paths, manifest, load_record(paths, args.element))
            print(f"shipped: {', '.join(done) or 'nothing (no approved slots)'}")
    except PipelineError as failure:
        print(f"element assets: {type(failure).__name__}: {failure}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
