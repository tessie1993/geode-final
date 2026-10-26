#!/usr/bin/env python3
"""Validates every Style pack against the pack format (docs/style-packs.md).

A Style is a folder engine/scenes/src/main/stylepacks/styles/<id>/ holding manifest.json and, for a
shader Style, its fragment shader. styles/index.json lists the Styles in picker order and
styles/families.json declares each family's parameters with their types and ranges. Both the Kotlin
side (StylePacks.kt) and the native side (geode/viz/StylePacks.hpp) read these files; this check holds
them to the format, plus the rules that span packs (every folder listed exactly once, an Extra name
meaning the same range on every Style, every Extra a shader Style declares read by its shader).

The packs are the one source of a Style's Extras. The Kotlin side lists them in a generated file
(PackExtras.kt in :feature:customization:core), which this check holds to the packs.

    python3 tools/ci/check_style_packs.py                 # check the repository
    python3 tools/ci/check_style_packs.py --self-test     # prove the check catches violations
    python3 tools/ci/check_style_packs.py --write-kotlin  # regenerate PackExtras.kt from the packs
"""

from __future__ import annotations

import copy
import json
import math
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
STYLES = ROOT / "engine" / "scenes" / "src" / "main" / "stylepacks" / "styles"
PACK_EXTRAS_KT = (
    ROOT / "feature" / "customization" / "core" / "src" / "main" / "kotlin" / "dev" / "geode" / "feature" / "customization" / "core"
    / "PackExtras.kt"
)

FORMAT_VERSION = 1
ID_PATTERN = re.compile(r"^[a-z][a-z0-9_]*$")
NAME_PATTERN = re.compile(r"^[a-z][a-z0-9_]*$")
# A family's "ignores" names Shared core properties as presets do: SPEED, AUDIO_DRIVE. The Kotlin
# Customization tests check each one is a real property.
SHARED_NAME_PATTERN = re.compile(r"^[A-Z][A-Z0-9_]*$")
MANIFEST_KEYS = {
    "version", "id", "label", "family", "shader", "renderScale", "detail", "parameters", "extras", "macros", "reactions",
    "effects", "tiers",
}
SHADER_FAMILY = "shader"
PARAMETER_TYPES = {"int", "float"}
DETAIL_USES = {"none", "march"}
MIN_RENDER_SCALE = 0.5
MAX_RENDER_SCALE = 1.0
MAX_MACROS = 6
MIN_AMOUNT = -1.0
MAX_AMOUNT = 1.0
REACTION_CURVES = {"linear", "ease_in", "ease_out", "smooth"}
# The Extras (ExtraKind in :feature:customization:api, Extra in geode/viz/SharedCore.hpp), in order. One
# name is one control with one range on every Style (docs/style-packs.md): a multiplier 0..2 of the
# Style's own amount, except the centres (offsets -1..1) and the variant (a whole number 0..9). Its
# default is its neutral value, so a Style draws exactly as its pack tunes it until it is Customized.
EXTRA_NAMES = [
    "scale", "count", "size", "thickness", "flight", "spin", "orbit", "swirl", "drift", "complexity", "edge", "fog",
    "decay", "viscosity", "growth", "spread", "force", "reach", "turn", "hold", "variant", "center_x", "center_y",
]
OFFSET_EXTRAS = {"center_x": (-1, 1, 0), "center_y": (-1, 1, 0), "variant": (0, 9, 0)}
MULTIPLIER_EXTRA = (0, 2, 1)
STYLE_HELPERS = {"count": "xcount", "variant": "xvariant"}
# lib_dmt's flight path clock, which ShaderScene runs at the FLIGHT Extra's rate as it runs uXFlightTime.
ENGINE_CLOCKS = {"flight": "uFlight"}
STYLE_INCLUDE = re.compile(r"^[ \t]*//#include[ \t]+lib_style[ \t]*$", re.MULTILINE)
# The thermal tiers a Style may budget (geode/viz/QualityTiers.hpp); "full" is always the whole budget.
TIER_NAMES = ("eased", "reduced", "minimal")
TIER_KEYS = {"steps", "off"}
MAX_EFFECTS = 8
MIN_TIER_STEPS = 0.25
MAX_TIER_STEPS = 1.0
INDEX_FILE = "index.json"
FAMILIES_FILE = "families.json"
MANIFEST_FILE = "manifest.json"


def is_number(value: object) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def is_text(value: object) -> bool:
    return isinstance(value, str) and value.strip() != ""


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
        report.error(str(path), f"not JSON: {e}")
        return None
    if not isinstance(doc, dict):
        report.error(str(path), "not a JSON object")
        return None
    if doc.get("version") != FORMAT_VERSION or isinstance(doc.get("version"), bool):
        report.error(str(path), f'"version" must be {FORMAT_VERSION}')
        return None
    return doc


def check_ignores(entry: dict, name: str, report: Report) -> None:
    """A family's optional "ignores": the Shared core properties its renderer never reads."""
    if "ignores" not in entry:
        return
    where = f"{FAMILIES_FILE} {name}"
    ignores = entry["ignores"]
    if not isinstance(ignores, list):
        report.error(where, '"ignores" must be an array')
        return
    seen: set[str] = set()
    for ignored in ignores:
        if not isinstance(ignored, str) or not SHARED_NAME_PATTERN.match(ignored):
            report.error(where, f'"ignores" entry {ignored!r} is not a Shared core property name')
        elif ignored in seen:
            report.error(where, f'"ignores" names "{ignored}" twice')
        else:
            seen.add(ignored)


def check_families(doc: dict, report: Report) -> dict[str, dict[str, dict]]:
    families: dict[str, dict[str, dict]] = {}
    entries = doc.get("families")
    if not isinstance(entries, list):
        report.error(FAMILIES_FILE, '"families" must be an array')
        return families
    for entry in entries:
        if not isinstance(entry, dict) or not is_text(entry.get("name")) or not isinstance(entry.get("parameters"), list):
            report.error(FAMILIES_FILE, 'a family needs "name" and "parameters"')
            continue
        name = entry["name"]
        if name in families:
            report.error(FAMILIES_FILE, f'family "{name}" is declared twice')
            continue
        check_ignores(entry, name, report)
        specs: dict[str, dict] = {}
        for spec in entry["parameters"]:
            where = f"{FAMILIES_FILE} {name}"
            if not isinstance(spec, dict) or not is_text(spec.get("key")):
                report.error(where, 'a parameter needs "key"')
                continue
            key = spec["key"]
            if key in specs:
                report.error(where, f'parameter "{key}" is declared twice')
            if spec.get("type") not in PARAMETER_TYPES:
                report.error(where, f'parameter "{key}" needs "type" of {sorted(PARAMETER_TYPES)}')
                continue
            if not is_number(spec.get("min")) or not is_number(spec.get("max")) or spec["min"] > spec["max"]:
                report.error(where, f'parameter "{key}" needs numbers "min" <= "max"')
                continue
            if "default" in spec and not in_range(spec, spec["default"]):
                report.error(where, f'parameter "{key}" has a default outside its range or type')
            specs[key] = spec
        families[name] = specs
    return families


def in_range(spec: dict, value: object) -> bool:
    if not is_number(value) or not spec["min"] <= value <= spec["max"]:
        return False
    return spec["type"] != "int" or float(value).is_integer()


def style_hash(style_id: str) -> int:
    """The 24-bit name the plan gives a Style (core/viz/Layers.cpp styleHash, Kotlin StyleHash)."""
    value = 2166136261
    for byte in style_id.encode():
        value = ((value ^ byte) * 16777619) & 0xFFFFFFFF
    folded = (value >> 24) ^ (value & 0xFFFFFF)
    return 1 if folded == 0 else folded


def check_index(doc: dict, report: Report) -> list[str]:
    styles = doc.get("styles")
    if not isinstance(styles, list):
        report.error(INDEX_FILE, '"styles" must be an array')
        return []
    ids: list[str] = []
    for entry in styles:
        if not isinstance(entry, str) or not ID_PATTERN.match(entry):
            report.error(INDEX_FILE, f"{entry!r} is not a Style id (lowercase letters, digits and _)")
        elif entry in ids:
            report.error(INDEX_FILE, f'"{entry}" is listed twice')
        else:
            ids.append(entry)
    hashed: dict[int, str] = {}
    for style_id in ids:
        other = hashed.setdefault(style_hash(style_id), style_id)
        if other != style_id:
            report.error(INDEX_FILE, f'"{style_id}" and "{other}" share a Style name in the plan; rename one')
    return ids


def check_parameters(manifest: dict, specs: dict[str, dict], where: str, report: Report) -> None:
    declared = manifest.get("parameters", {})
    if not isinstance(declared, dict):
        report.error(where, '"parameters" must be an object')
        return
    for key, value in declared.items():
        if key not in specs:
            report.error(where, f'family "{manifest["family"]}" has no parameter "{key}"')
        elif not in_range(specs[key], value):
            spec = specs[key]
            report.error(where, f'parameter "{key}" = {value!r} is not a {spec["type"]} in [{spec["min"]}, {spec["max"]}]')
    for key, spec in specs.items():
        if "default" not in spec and key not in declared:
            report.error(where, f'parameter "{key}" has no default and must be set')


def check_list(manifest: dict, key: str, where: str, report: Report) -> list[dict]:
    value = manifest.get(key, [])
    if not isinstance(value, list) or not all(isinstance(entry, dict) for entry in value):
        report.error(where, f'"{key}" must be an array of objects')
        return []
    return value


def check_amount(entry: dict, where: str, report: Report) -> None:
    if not is_number(entry.get("amount")) or not MIN_AMOUNT <= entry["amount"] <= MAX_AMOUNT:
        report.error(where, f'"amount" must be a number in [{MIN_AMOUNT}, {MAX_AMOUNT}]')


def extra_convention(name: str) -> tuple[int, int, int]:
    return OFFSET_EXTRAS.get(name, MULTIPLIER_EXTRA)


def extra_uniform(name: str) -> str:
    return "uX" + "".join(part.capitalize() for part in name.split("_"))


def strip_comments(source: str) -> str:
    return re.sub(r"//[^\n]*", "", re.sub(r"/\*.*?\*/", "", source, flags=re.DOTALL))


def check_shader_reads(manifest: dict, shader: Path, where: str, report: Report) -> None:
    names = [e["name"] for e in manifest.get("extras", []) if isinstance(e, dict) and isinstance(e.get("name"), str)]
    if not names:
        return
    source = shader.read_text(encoding="utf-8")
    if not STYLE_INCLUDE.search(source):
        report.error(where, "a shader Style with Extras includes lib_style (//#include lib_style)")
    code = strip_comments(source)
    for name in names:
        # A rate Extra (flight, spin, drift) may be read through its clock, uX<Name>Time (flight also through
        # lib_dmt's uFlight), and the count and the variant through lib_style's xcount() and xvariant().
        helper = STYLE_HELPERS.get(name)
        clock = ENGINE_CLOCKS.get(name)
        if (
            not re.search(rf"\b{extra_uniform(name)}(Time)?\b", code)
            and not (helper and re.search(rf"\b{helper}\s*\(", code))
            and not (clock and re.search(rf"\b{clock}\b", code))
        ):
            report.error(where, f'extra "{name}" is declared but the shader never reads {extra_uniform(name)}')


def check_extras(manifest: dict, where: str, report: Report, extra_ranges: dict[str, tuple[float, float, str]]) -> None:
    names: set[str] = set()
    for extra in check_list(manifest, "extras", where, report):
        name = extra.get("name")
        if not isinstance(name, str) or not NAME_PATTERN.match(name):
            report.error(where, 'an extra needs a "name" of lowercase letters, digits and _')
            continue
        if name in names:
            report.error(where, f'extra "{name}" is declared twice')
        names.add(name)
        if name not in EXTRA_NAMES:
            report.error(where, f'extra "{name}" is not one of the Extras {EXTRA_NAMES}')
            continue
        lo, hi, default = extra.get("min"), extra.get("max"), extra.get("default")
        if not all(is_number(v) for v in (lo, hi, default)) or not lo <= default <= hi:
            report.error(where, f'extra "{name}" needs numbers "min" <= "default" <= "max"')
            continue
        want_lo, want_hi, neutral = extra_convention(name)
        if (lo, hi) != (want_lo, want_hi):
            report.error(where, f'extra "{name}" has range [{lo}, {hi}]; every Style gives it [{want_lo}, {want_hi}]')
        if default != neutral:
            report.error(where, f'extra "{name}" defaults to {default}, not its neutral {neutral}: the Style would not draw as tuned')
        # Extras are generic: one name is one control, with one range, on every Style that has it.
        seen = extra_ranges.setdefault(name, (lo, hi, where))
        if (seen[0], seen[1]) != (lo, hi):
            report.error(where, f'extra "{name}" has range [{lo}, {hi}] but {seen[2]} gives it [{seen[0]}, {seen[1]}]')


def check_macros(manifest: dict, where: str, report: Report) -> None:
    macros = check_list(manifest, "macros", where, report)
    if len(macros) > MAX_MACROS:
        report.error(where, f"at most {MAX_MACROS} Macros")
    names: set[str] = set()
    for macro in macros:
        name = macro.get("name")
        if not isinstance(name, str) or not NAME_PATTERN.match(name):
            report.error(where, 'a Macro needs a "name" of lowercase letters, digits and _')
            continue
        if name in names:
            report.error(where, f'Macro "{name}" is declared twice')
        names.add(name)
        links = macro.get("links")
        if not isinstance(links, list):
            report.error(where, f'Macro "{name}" needs "links"')
            continue
        for link in links:
            if not isinstance(link, dict) or not is_text(link.get("target")):
                report.error(where, f'every link of Macro "{name}" needs a "target"')
                continue
            check_amount(link, where, report)


def check_reactions(manifest: dict, where: str, report: Report) -> None:
    for reaction in check_list(manifest, "reactions", where, report):
        if not is_text(reaction.get("signal")) or not is_text(reaction.get("target")):
            report.error(where, 'a Reaction needs "signal" and "target"')
            continue
        check_amount(reaction, where, report)
        for key in ("riseMs", "fallMs"):
            if not is_number(reaction.get(key)) or reaction[key] < 0:
                report.error(where, f'a Reaction\'s "{key}" must be a number >= 0')
        if reaction.get("curve") not in REACTION_CURVES:
            report.error(where, f'a Reaction\'s "curve" must be one of {sorted(REACTION_CURVES)}')


def check_manifest(
    folder: Path,
    families: dict[str, dict[str, dict]],
    report: Report,
    extra_ranges: dict[str, tuple[float, float, str]],
) -> None:
    where = folder.name
    manifest = load_json(folder / MANIFEST_FILE, report)
    if manifest is None:
        return
    unknown = set(manifest) - MANIFEST_KEYS
    if unknown:
        report.error(where, f"unknown keys {sorted(unknown)}")
    if manifest.get("id") != folder.name:
        report.error(where, f'"id" must be the folder name, "{folder.name}"')
    if not is_text(manifest.get("label")):
        report.error(where, '"label" must be a non-empty string')
    family = manifest.get("family")
    if family not in families:
        report.error(where, f'"family" {family!r} is not declared in {FAMILIES_FILE}')
        return
    files = {p.name for p in folder.iterdir()}
    expected = {MANIFEST_FILE}
    if family == SHADER_FAMILY:
        shader = manifest.get("shader")
        if not is_text(shader) or "/" in shader or not (folder / shader).is_file():
            report.error(where, 'a shader Style needs "shader": a file in its own folder')
        else:
            expected.add(shader)
            check_shader_reads(manifest, folder / shader, where, report)
    elif "shader" in manifest:
        report.error(where, f'family "{family}" draws with its own shaders; only a shader Style names one')
    if files != expected:
        report.error(where, f"the folder holds {sorted(files)}, expected {sorted(expected)}")
    if "renderScale" in manifest:
        scale = manifest["renderScale"]
        if not is_number(scale) or not MIN_RENDER_SCALE <= scale <= MAX_RENDER_SCALE:
            report.error(where, f'"renderScale" must be a number in [{MIN_RENDER_SCALE}, {MAX_RENDER_SCALE}]')
    if manifest.get("detail", "none") not in DETAIL_USES:
        report.error(where, f'"detail" must be one of {sorted(DETAIL_USES)}')
    check_parameters(manifest, families[family], where, report)
    check_extras(manifest, where, report, extra_ranges)
    check_macros(manifest, where, report)
    check_reactions(manifest, where, report)
    check_tiers(manifest, where, report)


def check_tiers(manifest: dict, where: str, report: Report) -> None:
    effects = manifest.get("effects", [])
    if not isinstance(effects, list) or len(effects) > MAX_EFFECTS or not all(isinstance(e, str) and NAME_PATTERN.match(e) for e in effects):
        report.error(where, f'"effects" must be a list of at most {MAX_EFFECTS} lower-case names')
        return
    if len(set(effects)) != len(effects):
        report.error(where, '"effects" names an effect twice')
    tiers = manifest.get("tiers", {})
    if not isinstance(tiers, dict):
        report.error(where, '"tiers" must be an object')
        return
    for name, tier in tiers.items():
        if name not in TIER_NAMES:
            report.error(where, f'"tiers" may declare only {list(TIER_NAMES)}, not {name!r}')
            continue
        if not isinstance(tier, dict) or set(tier) - TIER_KEYS:
            report.error(where, f'tier "{name}" may hold only {sorted(TIER_KEYS)}')
            continue
        if "steps" in tier and (not is_number(tier["steps"]) or not MIN_TIER_STEPS <= tier["steps"] <= MAX_TIER_STEPS):
            report.error(where, f'tier "{name}" "steps" must be a number in [{MIN_TIER_STEPS}, {MAX_TIER_STEPS}]')
        off = tier.get("off", [])
        if not isinstance(off, list) or any(e not in effects for e in off):
            report.error(where, f'tier "{name}" "off" must list effects named in "effects"')
    # A hotter tier is never richer than a cooler one: the engine enforces it, the pack should say it.
    steps = [tiers.get(n, {}).get("steps") if isinstance(tiers.get(n), dict) else None for n in TIER_NAMES]
    declared = [s for s in steps if is_number(s)]
    if declared != sorted(declared, reverse=True):
        report.error(where, '"tiers" "steps" must not rise from "eased" to "minimal"')


def check(styles: Path) -> list[str]:
    report = Report()
    families_doc = load_json(styles / FAMILIES_FILE, report)
    index_doc = load_json(styles / INDEX_FILE, report)
    if families_doc is None or index_doc is None:
        return report.errors
    families = check_families(families_doc, report)
    ids = check_index(index_doc, report)
    folders = sorted(p.name for p in styles.iterdir() if p.is_dir())
    for missing in sorted(set(ids) - set(folders)):
        report.error(INDEX_FILE, f'lists "{missing}", which has no folder')
    for unlisted in sorted(set(folders) - set(ids)):
        report.error(unlisted, f"is not listed in {INDEX_FILE}")
    stray = sorted(p.name for p in styles.iterdir() if p.is_file() and p.name not in (INDEX_FILE, FAMILIES_FILE))
    if stray:
        report.error(str(styles), f"stray files {stray}")
    extra_ranges: dict[str, tuple[float, float, str]] = {}
    for style_id in ids:
        if (styles / style_id).is_dir():
            check_manifest(styles / style_id, families, report, extra_ranges)
    return report.errors


# ---- the Kotlin listing ---------------------------------------------------------------------------

KOTLIN_SINGLE_LINE = 120


def kotlin_number(value: float) -> str:
    number = float(value)
    return f"{int(number)}f" if number.is_integer() else f"{number!r}f"


def kotlin_extras(styles: Path) -> str:
    """PackExtras.kt as the packs under `styles` declare their Extras."""
    ids = json.loads((styles / INDEX_FILE).read_text(encoding="utf-8"))["styles"]
    kinds: set[str] = set()
    families: set[str] = set()
    entries: list[str] = []
    for style_id in ids:
        manifest = json.loads((styles / style_id / MANIFEST_FILE).read_text(encoding="utf-8"))
        extras = manifest.get("extras", [])
        if not extras:
            continue
        family = manifest["family"].upper()
        families.add(family)
        args = [f'"{style_id}"', family]
        for extra in extras:
            kind = extra["name"].upper()
            kinds.add(kind)
            args.append(f'extra({kind}, {kotlin_number(extra["min"])}, {kotlin_number(extra["max"])}, {kotlin_number(extra["default"])})')
        line = f"            pack({', '.join(args)}),"
        if len(line) <= KOTLIN_SINGLE_LINE:
            entries.append(line)
        else:
            entries.append("            pack(\n" + "".join(f"                {arg},\n" for arg in args) + "            ),")
    imports = sorted(
        [f"import dev.geode.feature.customization.api.ExtraKind.{kind}" for kind in kinds]
        + [f"import dev.geode.feature.customization.api.StyleFamily.{family}" for family in families]
    )
    return (
        "// Generated by tools/ci/check_style_packs.py --write-kotlin from the Style packs' \"extras\"\n"
        "// (engine/scenes/src/main/stylepacks/styles/<id>/manifest.json). Do not edit it: change the packs and\n"
        "// run the script again. CI fails when this file and the packs disagree.\n"
        "package dev.geode.feature.customization.core\n\n"
        + "\n".join(imports)
        + "\n\n"
        "/**\n"
        " * Every Style pack that declares Extras, in the packs' order, with its family and its Extras: the one\n"
        " * source of a Style's own controls, which the engine reads from the same files.\n"
        " */\n"
        "internal object PackExtras {\n"
        "    val styles: List<PackStyle> =\n"
        "        listOf(\n"
        + "\n".join(entries)
        + "\n        )\n}\n"
    )


# ---- self-test ------------------------------------------------------------------------------------

GOOD_FAMILIES = {
    "version": 1,
    "families": [
        {"name": "shader", "parameters": []},
        {"name": "plate", "ignores": ["SPEED"], "parameters": [{"key": "mode", "type": "int", "min": 0, "max": 4}, {"key": "gain", "type": "float", "min": 0, "max": 2, "default": 1}]},
    ],
}
GOOD_MANIFESTS = {
    "orb": {
        "version": 1, "id": "orb", "label": "Orb", "family": "shader", "shader": "shader.glsl", "renderScale": 0.75,
        "detail": "march", "extras": [{"name": "scale", "min": 0, "max": 2, "default": 1}],
        "effects": ["halo"], "tiers": {"reduced": {"steps": 0.7, "off": ["halo"]}, "minimal": {"steps": 0.5}},
        "macros": [{"name": "energy", "links": [{"target": "scale", "amount": 0.5}]}],
        "reactions": [{"signal": "bass", "target": "scale", "amount": 1, "riseMs": 20, "fallMs": 300, "curve": "smooth"}],
    },
    "drum": {"version": 1, "id": "drum", "label": "Drum", "family": "plate", "parameters": {"mode": 2}},
}


GOOD_SHADER = "//#include lib_style\nvoid main() { float s = uXScale; }\n"


def write_tree(root: Path, families: dict, index: list[str], manifests: dict[str, dict], shaders: dict[str, object]) -> Path:
    styles = root / "styles"
    styles.mkdir()
    (styles / FAMILIES_FILE).write_text(json.dumps(families))
    (styles / INDEX_FILE).write_text(json.dumps({"version": 1, "styles": index}))
    for style_id, manifest in manifests.items():
        (styles / style_id).mkdir()
        (styles / style_id / MANIFEST_FILE).write_text(json.dumps(manifest))
    for style_id, shader in shaders.items():
        # A file name, holding a shader that reads the good tree's Extra, or (name, its source).
        name, source = shader if isinstance(shader, tuple) else (shader, GOOD_SHADER)
        (styles / style_id / name).write_text(source)
    return styles


def run_case(mutate) -> list[str]:
    families = copy.deepcopy(GOOD_FAMILIES)
    manifests = copy.deepcopy(GOOD_MANIFESTS)
    index = list(manifests)
    shaders = {"orb": "shader.glsl"}
    mutate(families, index, manifests, shaders)
    with tempfile.TemporaryDirectory() as tmp:
        return check(write_tree(Path(tmp), families, index, manifests, shaders))


def self_test() -> int:
    good = run_case(lambda f, i, m, s: None)
    if good:
        print("self-test: the good tree was rejected:\n  " + "\n  ".join(good))
        return 1

    def setter(path: list, value):
        def mutate(f, i, m, s):
            target = m
            for key in path[:-1]:
                target = target[key]
            if value is DELETE:
                del target[path[-1]]
            else:
                target[path[-1]] = value
        return mutate

    cases = {
        "unlisted folder": lambda f, i, m, s: i.remove("drum"),
        "listed twice": lambda f, i, m, s: i.append("orb"),
        "listed without folder": lambda f, i, m, s: i.append("ghost"),
        "bad id": lambda f, i, m, s: i.append("Bad-Id"),
        "id mismatch": setter(["orb", "id"], "other"),
        "missing label": setter(["orb", "label"], DELETE),
        "unknown family": setter(["orb", "family"], "hologram"),
        "unknown key": setter(["orb", "colour"], 1),
        "wrong version": setter(["orb", "version"], 2),
        "missing shader file": lambda f, i, m, s: s.clear(),
        "stray file": lambda f, i, m, s: s.update({"drum": "notes.txt"}),
        "shader on a non-shader family": setter(["drum", "shader"], "shader.glsl"),
        "render scale out of range": setter(["orb", "renderScale"], 0.2),
        "bad detail": setter(["orb", "detail"], "max"),
        "tier that is not a tier": setter(["orb", "tiers", "full"], {"steps": 1}),
        "tier steps out of range": setter(["orb", "tiers", "reduced", "steps"], 0.1),
        "tier switching off an undeclared effect": setter(["orb", "tiers", "reduced", "off"], ["glow"]),
        "tier with an unknown key": setter(["orb", "tiers", "minimal", "fps"], 30),
        "tier steps rising with heat": setter(["orb", "tiers", "minimal", "steps"], 0.9),
        "effect named twice": setter(["orb", "effects"], ["halo", "halo"]),
        "too many effects": setter(["orb", "effects"], [f"fx{n}" for n in range(MAX_EFFECTS + 1)]),
        "unknown parameter": setter(["drum", "parameters", "hue"], 1),
        "parameter out of range": setter(["drum", "parameters", "mode"], 9),
        "int parameter with a fraction": setter(["drum", "parameters", "mode"], 1.5),
        "required parameter missing": setter(["drum", "parameters"], {}),
        "extra default outside range": setter(["orb", "extras"], [{"name": "scale", "min": 0, "max": 2, "default": 3}]),
        "extra range differs between Styles": setter(["drum", "extras"], [{"name": "scale", "min": 0, "max": 5, "default": 1}]),
        "extra that is no Extra": setter(["orb", "extras"], [{"name": "density", "min": 0, "max": 2, "default": 1}]),
        "extra off its one range": setter(["orb", "extras"], [{"name": "scale", "min": 0, "max": 3, "default": 1}]),
        "extra not starting neutral": setter(["orb", "extras"], [{"name": "scale", "min": 0, "max": 2, "default": 1.5}]),
        "extra the shader never reads": setter(["orb", "extras"], [{"name": "fog", "min": 0, "max": 2, "default": 1}]),
        "shader Extras without lib_style": lambda f, i, m, s: s.update({"orb": ("shader.glsl", "void main() { float s = uXScale; }\n")}),
        "too many Macros": setter(["orb", "macros"], [{"name": f"m{n}", "links": []} for n in range(MAX_MACROS + 1)]),
        "Macro amount out of range": setter(["orb", "macros"], [{"name": "energy", "links": [{"target": "x", "amount": 2}]}]),
        "Reaction with negative rise": setter(["orb", "reactions"], [{"signal": "s", "target": "t", "amount": 1, "riseMs": -1, "fallMs": 0, "curve": "linear"}]),
        "Reaction with unknown curve": setter(["orb", "reactions"], [{"signal": "s", "target": "t", "amount": 1, "riseMs": 0, "fallMs": 0, "curve": "zigzag"}]),
        "family default outside range": lambda f, i, m, s: f["families"][1]["parameters"][1].update({"default": 7}),
        "family parameter with unknown type": lambda f, i, m, s: f["families"][1]["parameters"][0].update({"type": "bool"}),
        "ignores not an array": lambda f, i, m, s: f["families"][1].update({"ignores": "SPEED"}),
        "ignores with a bad name": lambda f, i, m, s: f["families"][1].update({"ignores": ["speed"]}),
        "ignores naming a property twice": lambda f, i, m, s: f["families"][1].update({"ignores": ["SPEED", "SPEED"]}),
    }
    failed = [name for name, mutate in cases.items() if not run_case(mutate)]
    if failed:
        print("self-test: these violations were not caught: " + ", ".join(failed))
        return 1
    print(f"self-test: the good tree passes and all {len(cases)} violations are caught")
    return 0


DELETE = object()


def main() -> int:
    if "--self-test" in sys.argv[1:]:
        return self_test()
    errors = check(STYLES)
    if not errors:
        listing = kotlin_extras(STYLES)
        if "--write-kotlin" in sys.argv[1:]:
            PACK_EXTRAS_KT.write_text(listing, encoding="utf-8")
            print(f"wrote {PACK_EXTRAS_KT.relative_to(ROOT)}")
        elif not PACK_EXTRAS_KT.is_file() or PACK_EXTRAS_KT.read_text(encoding="utf-8") != listing:
            errors.append(
                f"{PACK_EXTRAS_KT.relative_to(ROOT)}: not what the packs declare; run "
                "python3 tools/ci/check_style_packs.py --write-kotlin and commit it"
            )
    if errors:
        print(f"style pack check failed ({len(errors)}):")
        for error in errors:
            print(f"  {error}")
        return 1
    count = len(json.loads((STYLES / INDEX_FILE).read_text(encoding="utf-8"))["styles"])
    print(f"style packs ok: {count} Styles")
    return 0


if __name__ == "__main__":
    sys.exit(main())
