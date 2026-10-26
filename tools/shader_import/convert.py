#!/usr/bin/env python3
"""Turns permissively licensed multi-buffer shader art into a Geode Style pack, offline (#387).

Shader art is often written to one common convention: a `mainImage(out vec4, in vec2)` per pass, an
image pass and up to four buffer passes A to D, and the uniforms iTime, iResolution, iFrame, iTimeDelta,
iMouse and iChannel0..3. This maps that convention onto a Style with its own buffers
(docs/style-packs.md "Buffers", lib_buffers.glsl) and writes the pack's folder:

    iTime, iGlobalTime     the Style's clock, uTime, paced a little faster by the music's energy
                           (uEnergyPhase), unless --plain-time and the art reads an audio channel
    iResolution            vec3(uResolution, 1.0): the pass's own size (a buffer's, or the image's)
    iFrame                 the buffers' frame counter (uBufFrame), or the visit's (uFrame, `clocks`)
    iTimeDelta, iFrameRate the frame's seconds (uFrameDt, the `clocks` input)
    iMouse                 the primary finger (lib_touch): xy where it is, zw where it went down,
                           negative once it lifts, all 0 while nothing is touched
    iChannel0..3           a buffer (A..D, lib_buffers' uBufA..uBufD) or the music ("audio": Geode's
                           spectrum texture, uAudioTex, laid out as that convention lays out its own
                           sound input), by default channel n reads buffer n; --channel changes it
    iChannelResolution[n]  that channel's size

Anything the convention offers that a Style cannot read (iDate, iSampleRate, iChannelTime, cube maps,
sound or VR entry points, includes) is refused, and so is every source without a permissive licence
header: MIT, BSD, Apache-2.0, CC0 or the Unlicense, by SPDX identifier or by the licence's own words.
GPL, AGPL, LGPL, any Creative Commons NonCommercial or ShareAlike licence, and a file with no licence at
all are never imported. The header is kept, verbatim, at the top of each converted pass.

The image pass ends on the shared colour grade (grade(), so the palette and colour controls still mean
what they mean on every Style). The pack still has to pass the pack check and the render check, and a
Style made from it is listed by hand (index.json, BuiltInStyles); the No-flash rule is for the person
importing to keep: art that strobes is not a Style. Nothing is downloaded: the sources are files you
already have, and you must have the right to ship them.

    python3 tools/shader_import/convert.py --id tide --label Tide --image image.glsl \\
        --buffer buffer_a.glsl --buffer buffer_b.glsl --scale 0.5 --scale 0.5 \\
        --channel image:1=audio --out engine/scenes/src/main/stylepacks/styles
    python3 tools/shader_import/convert.py ... --check    # validate and convert, write nothing
    python3 tools/shader_import/convert.py --self-test    # prove the converter maps and refuses
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import re
import shutil
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CHECKER = ROOT / "tools" / "ci" / "check_style_packs.py"

MAX_BUFFERS = 4
LETTERS = "ABCD"
BUFFER_SCALE = (0.25, 1.0)
# How much of the energy-paced phase the imported clock gains: at most half again its pace in a loud
# passage, never stopping in a quiet one, and never a jump (a phase is added, a clock is not multiplied).
ENERGY_PACE = 0.5

# ---- licences ---------------------------------------------------------------------------------------

ALLOWED_SPDX = {
    "MIT", "MIT-0", "BSD-2-Clause", "BSD-3-Clause", "0BSD", "Apache-2.0", "CC0-1.0", "Unlicense", "Zlib",
}
# The licence's own words, for a header without an SPDX line.
ALLOWED_WORDS = {
    "MIT": (r"\bMIT License\b", r"Permission is hereby granted, free of charge"),
    "BSD": (r"\bBSD[- ][23][- ]Clause\b", r"Redistribution and use in source and binary forms"),
    "Apache-2.0": (r"Apache License,? Version 2\.0",),
    "CC0-1.0": (r"\bCC0\b", r"Creative Commons Zero"),
    "Unlicense": (r"\bThe Unlicense\b", r"free and unencumbered software released into the public domain"),
}
# Never imported, whatever else the header says (outside an SPDX line, which is read exactly).
REFUSED_WORDS = (
    r"\bA?GPL", r"\bLGPL", r"General Public License", r"\bNon-?Commercial\b", r"\bNC\b", r"Share-?Alike", r"\bBY-NC",
    r"\bBY-SA\b", r"All rights reserved",
)
SPDX_LINE = re.compile(r"SPDX-License-Identifier:\s*([^\n*]+)")


class Refused(Exception):
    """A source the converter will not import, with the reason."""


def licence_header(source: str) -> str:
    """The comments that open `source`, before its first line of code."""
    lines: list[str] = []
    in_block = False
    for line in source.splitlines():
        stripped = line.strip()
        if in_block:
            lines.append(line)
            in_block = "*/" not in stripped
            continue
        if not stripped or stripped.startswith("//"):
            lines.append(line)
        elif stripped.startswith("/*"):
            lines.append(line)
            in_block = "*/" not in stripped
        else:
            break
    return "\n".join(lines).strip()


def spdx_verdict(expression: str) -> str:
    """The licence an SPDX expression lets us ship under, or Refused."""
    expression = expression.strip().strip("()")
    if re.search(r"\bAND\b|\bWITH\b", expression):
        ids = re.split(r"\s+(?:AND|WITH)\s+", expression)
        if all(i.strip("() ") in ALLOWED_SPDX for i in ids):
            return expression
        raise Refused(f"SPDX {expression!r} needs every part permissive")
    for option in re.split(r"\s+OR\s+", expression):
        if option.strip("() ") in ALLOWED_SPDX:
            return option.strip("() ")
    raise Refused(f"SPDX {expression!r} is not a permissive licence (MIT, BSD, Apache-2.0, CC0, Unlicense)")


def licence_of(source: str, name: str) -> str:
    """The permissive licence `source`'s header grants; Refused for anything else."""
    header = licence_header(source)
    if not header:
        raise Refused(f"{name}: no licence header; only permissively licensed art is imported")
    spdx = SPDX_LINE.search(header)
    if spdx:
        try:
            return spdx_verdict(spdx.group(1))
        except Refused as why:
            raise Refused(f"{name}: {why}") from None
    for words in REFUSED_WORDS:
        found = re.search(words, header, re.IGNORECASE)
        if found:
            raise Refused(f"{name}: the header names a licence that is not permissive ({found.group(0)!r})")
    for licence, phrases in ALLOWED_WORDS.items():
        if any(re.search(p, header, re.IGNORECASE) for p in phrases):
            return licence
    raise Refused(f"{name}: no permissive licence in its header (MIT, BSD, Apache-2.0, CC0, Unlicense)")


# ---- the uniform convention -------------------------------------------------------------------------

UNSUPPORTED = {
    "iDate": "the wall-clock date (a Render must give the same frames every time)",
    "iSampleRate": "the sound's sample rate",
    "iChannelTime": "per-channel playback clocks",
    "samplerCube": "cube maps",
    "mainSound": "a sound pass",
    "mainVR": "a VR entry point",
    "mainCubemap": "a cube map pass",
}
MAIN_IMAGE = re.compile(r"\bvoid\s+mainImage\s*\(\s*out\s+vec4\s+\w+\s*,\s*(?:in\s+)?vec2\s+\w+\s*\)")
# The convention's own names; anything else starting with i is the art's.
CONVENTION = re.compile(r"\bi(?:Time|GlobalTime|Resolution|Frame|TimeDelta|FrameRate|Mouse|Channel\w*)\b")
CHANNEL = re.compile(r"\biChannel([0-3])\b")
CHANNEL_RESOLUTION = re.compile(r"\biChannelResolution\s*\[\s*([0-3])\s*\]")
MOUSE_HELPER = """// iMouse, from the primary finger (lib_touch): xy where it is and zw where it went down, in pixels of
// this pass, zw negative once it lifts; all 0 while nothing is touched.
vec4 importMouse() {
    if (touchIdle()) return vec4(0.0);
    vec2 at = (uTouchAnchor.xy * 0.5 + 0.5) * uResolution;
    return vec4(at, uTouchGesture != 0 ? at : -at);
}
"""


@dataclass
class Pass:
    """One pass of the art: the image pass (letter None) or a buffer, A to D."""

    name: str
    source: str
    letter: str | None = None
    scale: float = 1.0
    channels: dict[int, str] = field(default_factory=dict)


def file_name(p: Pass) -> str:
    return "shader.glsl" if p.letter is None else f"buffer_{p.letter.lower()}.glsl"


def sampler_for(channel: str) -> str:
    return "uAudioTex" if channel == "audio" else f"uBuf{channel}"


def convert_pass(p: Pass, buffers: int, plain_time: bool) -> tuple[str, set[str]]:
    """The pass in Geode's terms, and the shared inputs it reads."""
    licence = licence_of(p.source, p.name)
    header = licence_header(p.source)
    body = p.source[len(p.source) - len(p.source.lstrip()):] if not header else p.source[p.source.index(header) + len(header):]
    code = re.sub(r"//[^\n]*", "", re.sub(r"/\*.*?\*/", "", body, flags=re.DOTALL))
    for word, what in UNSUPPORTED.items():
        if re.search(rf"\b{word}\b", code):
            raise Refused(f"{p.name}: reads {word}, {what}, which a Style cannot")
    if re.search(r"^\s*#\s*(?:version|include)\b", code, re.MULTILINE):
        raise Refused(f"{p.name}: has its own #version or #include; the converter writes those")
    if not MAIN_IMAGE.search(code):
        raise Refused(f"{p.name}: no void mainImage(out vec4, in vec2)")
    inputs: set[str] = set()
    uses_buffers = False

    def channel(match: re.Match) -> str:
        nonlocal uses_buffers
        n = int(match.group(1))
        source = p.channels.get(n, LETTERS[n] if n < buffers else None)
        if source is None:
            raise Refused(f"{p.name}: reads iChannel{n}, which is no buffer the art declares; map it with --channel")
        if source != "audio" and LETTERS.index(source) >= buffers:
            raise Refused(f"{p.name}: iChannel{n} maps to buffer {source}, which the art does not declare")
        uses_buffers = uses_buffers or source != "audio"
        return sampler_for(source)

    body = CHANNEL_RESOLUTION.sub(lambda m: f"vec3(vec2(textureSize(iChannel{m.group(1)}, 0)), 1.0)", body)
    body = CHANNEL.sub(channel, body)
    body = re.sub(r"\biResolution\b", "vec3(uResolution, 1.0)", body)
    clock = "uTime" if plain_time else f"(uTime + {ENERGY_PACE} * uEnergyPhase)"
    body = re.sub(r"\bi(?:Global)?Time\b", clock, body)
    if re.search(r"\biFrame\b", body):
        if buffers:
            body = re.sub(r"\biFrame\b", "int(uBufFrame)", body)
            uses_buffers = True
        else:
            body = re.sub(r"\biFrame\b", "int(uFrame)", body)
            inputs.add("clocks")
    if re.search(r"\biTimeDelta\b|\biFrameRate\b", body):
        body = re.sub(r"\biTimeDelta\b", "uFrameDt", body)
        body = re.sub(r"\biFrameRate\b", "(1.0 / max(uFrameDt, 0.001))", body)
        inputs.add("clocks")
    mouse = bool(re.search(r"\biMouse\b", body))
    body = re.sub(r"\biMouse\b", "importMouse()", body)
    leftover = sorted(set(CONVENTION.findall(re.sub(r"//[^\n]*", "", body))))
    if leftover:
        raise Refused(f"{p.name}: reads {', '.join(leftover)}, which the converter does not map")
    image = p.letter is None
    if image and not uses_buffers and buffers:
        raise Refused(f"{p.name}: the image pass reads none of the buffers")
    includes = ["lib_scene_uniforms"]
    if not plain_time and "uEnergyPhase" in body:
        includes.append("lib_scene_motion")
    if inputs:
        includes.append("lib_inputs")
    if uses_buffers:
        includes.append("lib_buffers")
    if mouse:
        includes.append("lib_touch")
    if image:
        includes += ["lib_palette", "lib_scene_grade"]
    out = ["#version 300 es", "precision highp float;", "precision highp sampler2D;", ""]
    out += [header, ""]
    what = "image pass" if image else f"buffer {p.letter}"
    out.append(f"// The {what} of shader art under {licence}, imported by tools/shader_import/convert.py (#387).")
    out += ["", "in vec2 vUv;", "out vec4 fragColor;", ""]
    out += [f"//#include {name}" for name in includes]
    out.append("")
    if mouse:
        out.append(MOUSE_HELPER)
    out.append(body.strip("\n"))
    out.append("")
    out.append("void main() {")
    out.append("    vec4 color = vec4(0.0);")
    out.append("    mainImage(color, gl_FragCoord.xy);")
    if image:
        out.append("    // The shared colour grade, so the palette and colour controls mean what they mean everywhere.")
        out.append("    fragColor = vec4(grade(clamp(color.rgb, 0.0, 1.0)), 1.0);")
    else:
        out.append("    fragColor = color;")
    out.append("}")
    return "\n".join(out) + "\n", inputs


def load_checker():
    spec = importlib.util.spec_from_file_location("check_style_packs", CHECKER)
    module = importlib.util.module_from_spec(spec)
    sys.modules.setdefault("check_style_packs", module)
    spec.loader.exec_module(module)
    return module


def convert(style_id: str, label: str, passes: list[Pass], plain_time: bool = False) -> dict[str, str]:
    """The pack's files, by name: the manifest and each pass. Refused when the art cannot be a Style."""
    if not re.fullmatch(r"[a-z][a-z0-9_]*", style_id):
        raise Refused(f"the id {style_id!r} must be lower-case letters, digits and _, starting with a letter")
    buffers = [p for p in passes if p.letter is not None]
    if len(buffers) > MAX_BUFFERS:
        raise Refused(f"{len(buffers)} buffers; a Style runs at most {MAX_BUFFERS}")
    for p in buffers:
        if not BUFFER_SCALE[0] <= p.scale <= BUFFER_SCALE[1]:
            raise Refused(f"buffer {p.letter}: scale {p.scale} is outside {list(BUFFER_SCALE)}")
    files: dict[str, str] = {}
    inputs: set[str] = set()
    for p in passes:
        text, read = convert_pass(p, len(buffers), plain_time)
        files[file_name(p)] = text
        inputs |= read
    checker = load_checker()
    profile: dict[str, list[str]] = {}
    for level, feature in sorted(set().union(*(checker.shader_reactivity(t) for t in files.values()))):
        profile.setdefault(level, []).append(feature)
    if not profile:
        raise Refused("the art reads nothing of the music: map a channel to audio, or drop --plain-time")
    manifest: dict[str, object] = {"version": 1, "id": style_id, "label": label, "family": "shader", "shader": "shader.glsl"}
    if buffers:
        manifest["buffers"] = [{"shader": file_name(p), "scale": p.scale} for p in buffers]
    if inputs:
        manifest["inputs"] = sorted(inputs)
    manifest["reactivity"] = profile
    files["manifest.json"] = json.dumps(manifest, indent=2) + "\n"
    return files


def pack_problems(style_id: str, files: dict[str, str]) -> list[str]:
    """What the pack check says of the pack on its own, beside the shipped families."""
    checker = load_checker()
    with tempfile.TemporaryDirectory() as tmp:
        styles = Path(tmp) / "styles"
        (styles / style_id).mkdir(parents=True)
        shutil.copy(checker.STYLES / checker.FAMILIES_FILE, styles / checker.FAMILIES_FILE)
        (styles / checker.INDEX_FILE).write_text(json.dumps({"version": 1, "styles": [style_id]}), encoding="utf-8")
        for name, text in files.items():
            (styles / style_id / name).write_text(text, encoding="utf-8")
        return checker.check(styles)


# ---- the command line -------------------------------------------------------------------------------

def parse_channels(specs: list[str], passes: dict[str, Pass]) -> None:
    for spec in specs:
        m = re.fullmatch(r"(image|[a-d]):([0-3])=(audio|[A-D])", spec)
        if not m:
            raise Refused(f"--channel {spec!r}: use <image|a|b|c|d>:<0-3>=<A-D|audio>")
        target = passes.get(m.group(1))
        if target is None:
            raise Refused(f"--channel {spec!r}: the art has no pass {m.group(1)}")
        target.channels[int(m.group(2))] = m.group(3)


def main(argv: list[str]) -> int:
    if "--self-test" in argv:
        return self_test()
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--id", required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--image", required=True, type=Path)
    parser.add_argument("--buffer", action="append", default=[], type=Path, help="buffer A, then B, C, D")
    parser.add_argument("--scale", action="append", default=[], type=float, help="each buffer's share of the target")
    parser.add_argument("--channel", action="append", default=[], help="<pass>:<n>=<A-D|audio>, e.g. image:1=audio")
    parser.add_argument("--plain-time", action="store_true", help="iTime is the Style's clock, not paced by the energy")
    parser.add_argument("--out", type=Path, help="the styles folder to write <id>/ into")
    parser.add_argument("--check", action="store_true", help="convert and validate, write nothing")
    args = parser.parse_args(argv)
    try:
        if len(args.scale) > len(args.buffer):
            raise Refused("more --scale than --buffer")
        named: dict[str, Pass] = {"image": Pass(args.image.name, args.image.read_text(encoding="utf-8"))}
        for i, path in enumerate(args.buffer[:MAX_BUFFERS + 1]):
            scale = args.scale[i] if i < len(args.scale) else 1.0
            letter = LETTERS[i] if i < MAX_BUFFERS else "E"
            named[letter.lower()] = Pass(path.name, path.read_text(encoding="utf-8"), letter, scale)
        parse_channels(args.channel, named)
        buffers = [p for key, p in named.items() if key != "image"]
        files = convert(args.id, args.label, [*buffers, named["image"]], args.plain_time)
    except Refused as why:
        print(f"refused: {why}")
        return 1
    problems = pack_problems(args.id, files)
    for problem in problems:
        print(f"pack check: {problem}")
    if problems:
        return 1
    if args.check or args.out is None:
        print(f"ok: {args.id} converts to a valid pack ({', '.join(sorted(files))}); nothing written")
        return 0
    folder = args.out / args.id
    if folder.exists():
        print(f"refused: {folder} exists")
        return 1
    folder.mkdir(parents=True)
    for name, text in files.items():
        (folder / name).write_text(text, encoding="utf-8")
    print(f"wrote {folder}; list it in index.json and BuiltInStyles, give it Extras if it wants them, and run the checks")
    return 0


# ---- self-test --------------------------------------------------------------------------------------
#
# Every source below is written here for the test, in the common convention; none is anyone's art.

MIT = "// Copyright (c) 2026 Test Author\n// SPDX-License-Identifier: MIT\n"
SIM = MIT + """
void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = fragCoord / iResolution.xy;
    vec4 last = texture(iChannel0, uv);
    float seed = iFrame == 0 ? step(0.5, fract(sin(dot(fragCoord, vec2(12.9, 78.2))) * 43758.5)) : 0.0;
    float m = iMouse.z > 0.0 ? 1.0 - smoothstep(0.0, 20.0, length(fragCoord - iMouse.xy)) : 0.0;
    fragColor = vec4(max(last.r * (1.0 - 0.1 * iTimeDelta), max(seed, m)), 0.0, 0.0, 1.0);
}
"""
TRAIL = MIT + """
void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = fragCoord / iResolution.xy;
    fragColor = mix(texture(iChannel1, uv), texture(iChannel0, uv), 0.1);
}
"""
IMAGE = MIT + """
/* The image: the trail, coloured by time. */
void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = fragCoord / iResolution.xy;
    vec3 size = iChannelResolution[1];
    float v = texture(iChannel1, uv + 0.5 / size.xy).r;
    fragColor = vec4(v * (0.5 + 0.5 * cos(iTime + vec3(0.0, 2.0, 4.0))), 1.0);
}
"""
# The image reading buffer A alone.
IMAGE_A = IMAGE.replace("iChannel1", "iChannel0").replace("iChannelResolution[1]", "iChannelResolution[0]")
SPECTRUM = """/*
 * MIT License
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software.
 */
void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = fragCoord / iResolution.xy;
    float level = texture(iChannel0, vec2(uv.x, 0.25)).r;
    fragColor = vec4(vec3(step(uv.y, level) * 0.8), 1.0);
}
"""


def with_header(header: str, source: str = IMAGE) -> str:
    return header + source[len(MIT):]


def self_test() -> int:
    failures: list[str] = []

    def accepted(name: str, passes: list[Pass], plain_time: bool = False, style_id: str = "imported") -> dict[str, str]:
        try:
            files = convert(style_id, "Imported", passes, plain_time)
        except Refused as why:
            failures.append(f"{name}: refused ({why})")
            return {}
        problems = pack_problems(style_id, files)
        if problems:
            failures.append(f"{name}: the pack check fails: {problems}")
        return files

    def refused(name: str, passes: list[Pass], expect: str, plain_time: bool = False) -> None:
        try:
            convert("imported", "Imported", passes, plain_time)
        except Refused as why:
            if expect.lower() not in str(why).lower():
                failures.append(f"{name}: refused for the wrong reason: {why}")
            return
        failures.append(f"{name}: converted, but must be refused ({expect})")

    def chain() -> list[Pass]:
        return [Pass("a.glsl", SIM, "A", 0.5), Pass("b.glsl", TRAIL, "B"), Pass("image.glsl", IMAGE)]

    files = accepted("two buffers and an image", chain())
    if files:
        manifest = json.loads(files["manifest.json"])
        expect = [{"shader": "buffer_a.glsl", "scale": 0.5}, {"shader": "buffer_b.glsl", "scale": 1.0}]
        if manifest.get("buffers") != expect:
            failures.append(f"buffers: {manifest.get('buffers')}")
        if manifest.get("inputs") != ["clocks"] or manifest.get("reactivity") != {"macro": ["energy"]}:
            failures.append(f"inputs/reactivity: {manifest.get('inputs')} {manifest.get('reactivity')}")
        a, b, image = files["buffer_a.glsl"], files["buffer_b.glsl"], files["shader.glsl"]
        for text in (a, b, image):
            if re.search(r"\bi(?:Time|Resolution|Frame|TimeDelta|Mouse|Channel\w*)\b", re.sub(r"//[^\n]*", "", text)):
                failures.append("a convention uniform is left after conversion")
            if not text.startswith("#version 300 es\n") or "SPDX-License-Identifier: MIT" not in text:
                failures.append("each pass starts with #version and keeps its licence header")
        checks = {
            "buffer A reads itself as uBufA": "texture(uBufA, uv)" in a,
            "iFrame is the buffers' counter": "int(uBufFrame) == 0" in a,
            "iTimeDelta is the frame's seconds": "uFrameDt" in a and "//#include lib_inputs" in a,
            "iMouse is the finger": "importMouse()" in a and "//#include lib_touch" in a,
            "buffer B reads A and itself": "texture(uBufB, uv)" in b and "texture(uBufA, uv)" in b,
            "iResolution is the pass's size": "vec3(uResolution, 1.0).xy" in image,
            "iChannelResolution is the channel's size": "textureSize(uBufB, 0)" in image,
            "iTime is paced by the energy": f"(uTime + {ENERGY_PACE} * uEnergyPhase)" in image,
            "the image pass ends on the grade": "grade(clamp(color.rgb" in image and "//#include lib_scene_grade" in image,
            "a buffer pass keeps its value": "fragColor = color;" in a,
        }
        failures += [name for name, ok in checks.items() if not ok]
    spectrum = accepted("one pass reading the music", [Pass("s.glsl", SPECTRUM, channels={0: "audio"})], plain_time=True)
    if spectrum:
        manifest = json.loads(spectrum["manifest.json"])
        if "buffers" in manifest or manifest.get("reactivity") != {"micro": ["spectrum"]} or "uAudioTex" not in spectrum["shader.glsl"]:
            failures.append(f"the audio channel: {manifest}")
    for name, header in {
        "Apache words": "// Licensed under the Apache License, Version 2.0\n",
        "BSD words": "// Redistribution and use in source and binary forms, with or without modification, are permitted.\n",
        "CC0": "// To the extent possible under law, released under CC0.\n",
        "Unlicense": "// This is free and unencumbered software released into the public domain.\n",
        "SPDX choice": "// SPDX-License-Identifier: GPL-3.0-only OR MIT\n",
        "SPDX both permissive": "// SPDX-License-Identifier: MIT AND Apache-2.0\n",
    }.items():
        accepted(name, [Pass("a.glsl", with_header(header, SIM), "A"), Pass("b.glsl", with_header(header, TRAIL), "B"),
                        Pass("i.glsl", with_header(header))])
    for name, header, expect in [
        ("GPL words", "// This program is free software under the GNU General Public License v3.\n", "not permissive"),
        ("AGPL SPDX", "// SPDX-License-Identifier: AGPL-3.0-or-later\n", "not a permissive"),
        ("LGPL SPDX", "// SPDX-License-Identifier: LGPL-2.1-only\n", "not a permissive"),
        ("NonCommercial", "// Creative Commons Attribution-NonCommercial-ShareAlike 3.0 Unported License.\n", "not permissive"),
        ("CC BY-NC-SA", "// License: CC BY-NC-SA 4.0\n", "not permissive"),
        ("MIT words beside GPL", "// MIT License, or at your option the GPL.\n", "not permissive"),
        ("SPDX with a copyleft part", "// SPDX-License-Identifier: MIT AND GPL-2.0-only\n", "every part"),
        ("no licence named", "// Made by someone, all rights reserved.\n", "not permissive"),
        ("an unknown licence", "// Copyright 2026 Someone. Ask before use.\n", "no permissive licence"),
    ]:
        refused(name, [Pass("i.glsl", with_header(header))], expect)
    refused("no header", [Pass("s.glsl", SPECTRUM.split("*/\n")[1], channels={0: "audio"})], "no licence header")
    refused("one buffer unlicensed", [Pass("a.glsl", with_header("", SIM), "A"), Pass("i.glsl", IMAGE)], "no licence header")
    body = IMAGE.replace("iTime", "iDate.w")
    refused("iDate", [Pass("a.glsl", SIM, "A"), Pass("b.glsl", TRAIL, "B"), Pass("i.glsl", body)], "iDate")
    refused("a cube map", [Pass("i.glsl", MIT + "uniform samplerCube c;\n" + SPECTRUM.split("*/\n")[1])], "samplerCube")
    refused("no mainImage", [Pass("i.glsl", MIT + "void main() {}\n")], "mainImage")
    refused("its own include", [Pass("i.glsl", MIT + "#include \"common.glsl\"\n" + IMAGE[len(MIT):])], "#include")
    refused("a channel nothing feeds", [Pass("i.glsl", IMAGE)], "iChannel1")
    refused("a channel mapped past the buffers",
            [Pass("a.glsl", SIM, "A", channels={0: "C"}), Pass("i.glsl", IMAGE_A)], "buffer C")
    refused("an image pass that reads no buffer",
            [Pass("a.glsl", SIM, "A"), Pass("i.glsl", SPECTRUM, channels={0: "audio"})], "none of the buffers")
    refused("a channel resolution nothing feeds",
            [Pass("i.glsl", SPECTRUM.replace("iResolution.xy;", "iResolution.xy + iChannelResolution[2].xy;"), channels={0: "audio"})],
            "iChannel2")
    own = accepted("the art's own names starting with i", [Pass("s.glsl", SPECTRUM.replace("level", "iLevel"), channels={0: "audio"})],
                   plain_time=True, style_id="own_names")
    if own and "iLevel" not in own["shader.glsl"]:
        failures.append("the art's own iLevel was renamed")
    refused("too many buffers", [Pass(f"{c}.glsl", SIM, c) for c in "ABCDE"] + [Pass("i.glsl", IMAGE)], "at most 4")
    refused("a buffer scale off its range", [Pass("a.glsl", SIM, "A", 0.1), Pass("i.glsl", IMAGE_A)],
            "scale")
    refused("nothing of the music", [Pass("a.glsl", SIM, "A"), Pass("i.glsl", IMAGE_A)], "music",
            plain_time=True)
    try:
        convert("Bad-Id", "Bad", [Pass("i.glsl", SPECTRUM, channels={0: "audio"})])
        failures.append("a bad id converts")
    except Refused:
        pass
    if failures:
        print(f"shader import self-test failed ({len(failures)}):")
        for failure in failures:
            print(f"  {failure}")
        return 1
    print("shader import self-test: the art converts to valid packs and every refusal holds")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
