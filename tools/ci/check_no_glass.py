#!/usr/bin/env python3
"""Fail when the App UI uses glass: live blur, glassmorphism libraries, or anything named glass.

The owner's rule (22 Oct 2026, #102): the App UI has no glass anywhere. No frosted-glass panels, no
glassmorphism, no live blur or refraction behind App UI surfaces, no translucent glass orbs. "3D" is
a depth illusion: parallax, tilt and lighting. This check scans the App UI's production source:

- design/src/main, feature/*/ui/src/main and app/src/main (Kotlin and res/; not assets/ or cpp/,
  which hold the Visual's shaders and the JNI layer), for
  - live blur APIs: Modifier.blur, RenderEffect.createBlurEffect, Compose's BlurEffect,
    BlurMaskFilter, and the Haze / Cloudy / BlurView / Blurry glass libraries' imports;
  - identifiers, source files, resource files and resource names containing glass, frosted or
    refraction;
- every Gradle build file and version catalog, for those glassmorphism libraries as dependencies.

Comments and string literals are not code and are skipped. Artwork blurred in advance (a pre-blurred
bitmap shipped as a resource) is not a live blur and is allowed, as long as it is not named glass.
The Visual (engine/, core/, app/src/main/assets) may blur and refract: it is not scanned.

Legacy code on its way out is listed in config/no-glass/allowlist.txt, each entry under the ticket
that deletes it. The allowlist only shrinks: an entry that no longer matches is an error, and in a
pull request any entry the base branch does not have is an error (--base-allowlist).

    python3 tools/ci/check_no_glass.py                               # check the repository
    python3 tools/ci/check_no_glass.py --base-allowlist <file>       # ... and ratchet the allowlist
    python3 tools/ci/check_no_glass.py --self-test                   # prove the check catches glass
"""

from __future__ import annotations

import re
import sys
import tempfile
from pathlib import Path

ALLOWLIST = "config/no-glass/allowlist.txt"
UI_ROOTS = ("design/src/main", "app/src/main", "feature/*/ui/src/main")
SKIPPED_DIRS = {"assets", "cpp"}  # the Visual's shaders and the JNI layer, under app/src/main
CODE_SUFFIXES = {".kt", ".kts", ".java"}

GLASS_WORD = re.compile(r"glass|frosted|refraction", re.I)
IDENTIFIER = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
LIVE_BLUR = [
    (re.compile(r"\.\s*blur\s*\("), "Modifier.blur"),
    (re.compile(r"\bcreateBlurEffect\b"), "RenderEffect.createBlurEffect"),
    (re.compile(r"\bBlurEffect\s*\("), "BlurEffect"),
    (re.compile(r"\bBlurMaskFilter\b"), "BlurMaskFilter"),
    (re.compile(r"\bdev\.chrisbanes\.haze\b|\bhaze(?:Source|Child|Effect)\b"), "Haze"),
    (re.compile(r"\bcom\.skydoves\.cloudy\b|\bcloudy\s*\("), "Cloudy"),
    (re.compile(r"\beightbitlab\.com\.blurview\b"), "BlurView"),
    (re.compile(r"\bjp\.wasabeef\.blurry\b"), "Blurry"),
]
GLASS_DEPENDENCY = re.compile(
    r"chrisbanes[.:]haze|^\s*haze\w*\s*=|skydoves[.:]cloudy|Dimezis|eightbitlab|wasabeef[.:]blurry"
    r"|glassmorph|liquid-?glass|realtimeblur", re.I | re.M)
# Comments and string literals, replaced by blank space of the same shape so line numbers hold.
KOTLIN_NOISE = re.compile(r'//[^\n]*|/\*.*?\*/|"""(?:.|\n)*?"""|"(?:\\.|[^"\\\n])*"|\'(?:\\.|[^\'\\\n])\'',
                          re.S)
XML_COMMENT = re.compile(r"<!--.*?-->", re.S)
XML_NAME = re.compile(r'\bname\s*=\s*"([^"]*)"')


def blank(match: re.Match[str]) -> str:
    return re.sub(r"[^\n]", " ", match.group(0))


def line_of(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def ui_files(root: Path) -> list[Path]:
    files: set[Path] = set()
    for pattern in UI_ROOTS:
        for base in root.glob(pattern):
            for path in base.rglob("*"):
                rel = path.relative_to(base).parts
                if path.is_file() and not (rel and rel[0] in SKIPPED_DIRS):
                    files.add(path)
    return sorted(files)


def build_files(root: Path) -> list[Path]:
    found = [p for p in root.rglob("*") if p.is_file() and (p.name.endswith(".gradle.kts") or p.suffix == ".toml")]
    return sorted(p for p in found if not any(
        part in {"build", "third_party"} or part.startswith(".") for part in p.relative_to(root).parts[:-1]))


def findings(root: Path) -> list[tuple[str, int, str, str]]:
    """(path, line, token, what) for every glass construct; the allowlist matches on (path, token)."""
    out: list[tuple[str, int, str, str]] = []
    for path in ui_files(root):
        rel = path.relative_to(root).as_posix()
        stem = path.name.split(".")[0]
        if GLASS_WORD.search(stem):
            out.append((rel, 1, stem, "file named glass, frosted or refraction"))
        if path.suffix in CODE_SUFFIXES:
            code = KOTLIN_NOISE.sub(blank, path.read_text(encoding="utf-8", errors="replace"))
            for pattern, api in LIVE_BLUR:
                for match in pattern.finditer(code):
                    out.append((rel, line_of(code, match.start()), api, "live blur or glass library"))
            for match in IDENTIFIER.finditer(code):
                if GLASS_WORD.search(match.group(0)):
                    out.append((rel, line_of(code, match.start()), match.group(0),
                                "identifier named glass, frosted or refraction"))
        elif path.suffix == ".xml":
            xml = XML_COMMENT.sub(blank, path.read_text(encoding="utf-8", errors="replace"))
            for match in XML_NAME.finditer(xml):
                if GLASS_WORD.search(match.group(1)):
                    out.append((rel, line_of(xml, match.start()), match.group(1),
                                "resource named glass, frosted or refraction"))
    for path in build_files(root):
        rel = path.relative_to(root).as_posix()
        text = path.read_text(encoding="utf-8", errors="replace")
        text = re.sub(r"(?m)(//|#).*$", blank, text)
        for line in sorted({line_of(text, m.start()) for m in GLASS_DEPENDENCY.finditer(text)}):
            out.append((rel, line, "dependency", "glassmorphism library as a dependency"))
    return out


def read_allowlist(path: Path) -> set[tuple[str, str]]:
    """Entries are "<path> <token>" lines; # starts a comment."""
    entries: set[tuple[str, str]] = set()
    if path.is_file():
        for raw in path.read_text(encoding="utf-8").splitlines():
            parts = raw.split("#", 1)[0].split()
            if parts:
                if len(parts) != 2:
                    raise ValueError(f"{path}: bad allowlist line {raw!r}; want '<path> <token>'")
                entries.add((parts[0], parts[1]))
    return entries


def check(root: Path, base_allowlist: Path | None = None) -> list[str]:
    root = root.resolve()
    allowed = read_allowlist(root / ALLOWLIST)
    errors: list[str] = []
    used: set[tuple[str, str]] = set()
    for rel, line, token, what in findings(root):
        if (rel, token) in allowed:
            used.add((rel, token))
        else:
            errors.append(f"{rel}:{line}: {token}: {what}. The App UI has no glass (#102).")
    for rel, token in sorted(allowed - used):
        errors.append(f"{ALLOWLIST}: '{rel} {token}' no longer matches; delete the entry")
    if base_allowlist is not None and base_allowlist.is_file():
        for rel, token in sorted(allowed - read_allowlist(base_allowlist)):
            errors.append(f"{ALLOWLIST}: '{rel} {token}' is new; the allowlist only shrinks")
    return errors


def self_test() -> int:
    """A tree with every kind of glass, plus look-alikes that must pass, and an allowlist ratchet."""
    files = {
        # Must fail.
        "design/src/main/java/a/Panel.kt": "fun Modifier.p() = this.blur(20.dp)\n",
        "design/src/main/java/a/Effect.kt": "val e = RenderEffect.createBlurEffect(1f, 1f, T)\n",
        "feature/library/ui/src/main/java/a/Card.kt": "val e = BlurEffect(8f, 8f)\nval f = BlurMaskFilter(2f, N)\n",
        "feature/library/ui/src/main/java/a/Haze.kt": "import dev.chrisbanes.haze.hazeSource\n",
        "app/src/main/java/a/Bar.kt": "val frostedTint = 1\n",
        "app/src/main/java/a/GlassOrb.kt": "class Orb\n",
        "app/src/main/res/drawable-nodpi/stone_refraction.webp": "",
        "app/src/main/res/values/colors.xml": '<resources><color name="bar_glass">#1</color></resources>\n',
        "gradle/libs.versions.toml": 'haze = { module = "dev.chrisbanes.haze:haze", version = "1" }\n',
        "design/src/main/java/a/Legacy.kt": "val refractionOverlay = 1\nval glassTint = 2\n",
        # Must pass: comments, strings, text shadows, pre-blurred art, the Visual, tests.
        "design/src/main/java/a/Fine.kt": (
            '// a glass panel\n/* frosted */\nval s = "refraction and glass"\n'
            "val sh = Shadow(blurRadius = 10f)\nval art = R.drawable.library_art_preblurred\n"),
        "design/src/main/res/drawable-nodpi/library_art_preblurred.webp": "",
        "app/src/main/assets/shaders/glass_frag.glsl": "vec3 refraction;\n",
        "app/src/main/cpp/glass.cpp": "int glass;\n",
        "engine/scenes/src/main/java/a/Glass.kt": "fun Modifier.g() = blur(4.dp)\n",
        "design/src/test/java/a/GlassTest.kt": "val glass = 1\n",
        "app/build.gradle.kts": '// haze was here\nimplementation(libs.compose.ui)\n',
        ALLOWLIST: "# Old theme, deleted at #17.\ndesign/src/main/java/a/Legacy.kt refractionOverlay\n"
                   "design/src/main/java/a/Gone.kt glassTint\n",
    }
    expected = [
        "design/src/main/java/a/Panel.kt:1: Modifier.blur",
        "design/src/main/java/a/Effect.kt:1: RenderEffect.createBlurEffect",
        "feature/library/ui/src/main/java/a/Card.kt:1: BlurEffect",
        "feature/library/ui/src/main/java/a/Card.kt:2: BlurMaskFilter",
        "feature/library/ui/src/main/java/a/Haze.kt:1: Haze",
        "app/src/main/java/a/Bar.kt:1: frostedTint",
        "app/src/main/java/a/GlassOrb.kt:1: GlassOrb",
        "app/src/main/res/drawable-nodpi/stone_refraction.webp:1: stone_refraction",
        "app/src/main/res/values/colors.xml:1: bar_glass",
        "gradle/libs.versions.toml:1: dependency",
        "design/src/main/java/a/Legacy.kt:2: glassTint",
        f"{ALLOWLIST}: 'design/src/main/java/a/Gone.kt glassTint' no longer matches",
        f"{ALLOWLIST}: 'design/src/main/java/a/Gone.kt glassTint' is new",
    ]
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        for name, body in files.items():
            (root / name).parent.mkdir(parents=True, exist_ok=True)
            (root / name).write_text(body, encoding="utf-8")
        base = root / "base-allowlist.txt"
        base.write_text("design/src/main/java/a/Legacy.kt refractionOverlay\n", encoding="utf-8")
        errors = check(root, base)
    missing = [x for x in expected if not any(e.startswith(x) for e in errors)]
    unexpected = [e for e in errors if not any(e.startswith(x) for x in expected)]
    print("\n".join(errors))
    for x in missing:
        print(f"MISSED: {x}")
    for e in unexpected:
        print(f"FALSE ALARM: {e}")
    ok = not missing and not unexpected
    print("self-test " + ("passed" if ok else "FAILED"))
    return 0 if ok else 1


def main(argv: list[str]) -> int:
    if "--self-test" in argv:
        return self_test()
    base = Path(argv[argv.index("--base-allowlist") + 1]) if "--base-allowlist" in argv else None
    root = Path(__file__).resolve().parents[2]
    errors = check(root, base)
    for error in errors:
        print(f"::error::{error}")
    if errors:
        print(f"{len(errors)} glass violation(s); see docs/ci.md (No glass in the App UI)", file=sys.stderr)
        return 1
    print("no glass: the App UI has no live blur, no glass libraries and nothing named glass")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
