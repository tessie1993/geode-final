#!/usr/bin/env python3
"""Fail when the App UI blurs live or depends on a glassmorphism library.

The owner's rule (22 Oct 2026, #102): the App UI has no glass anywhere. This is the static half of
the check; the visual half, tools/ci/review_glass_screens.py, looks at the App UI screenshots. This
half scans:

- the App UI's production Kotlin (design/src/main, feature/*/ui/src/main and app/src/main, but not
  app/src/main/assets or cpp, which hold the Visual) for live blur APIs: Modifier.blur,
  RenderEffect.createBlurEffect, Compose's BlurEffect, BlurMaskFilter, and the imports of the Haze,
  Cloudy, BlurView and Blurry glass libraries;
- every Gradle build file and version catalog for those libraries as dependencies.

Comments and string literals are skipped. Names are not checked: what counts is what is drawn.
Artwork blurred in advance and shipped as a bitmap is allowed. The Visual (engine/, core/,
app/src/main/assets) may blur: it is not scanned. There is no allowlist.

    python3 tools/ci/check_no_glass.py              # check the repository
    python3 tools/ci/check_no_glass.py --self-test  # prove the check catches live blur
"""

from __future__ import annotations

import re
import sys
import tempfile
from pathlib import Path

UI_ROOTS = ("design/src/main", "app/src/main", "feature/*/ui/src/main")
SKIPPED_DIRS = {"assets", "cpp"}  # the Visual's shaders and the JNI layer, under app/src/main
CODE_SUFFIXES = {".kt", ".kts", ".java"}

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


def blank(match: re.Match[str]) -> str:
    return re.sub(r"[^\n]", " ", match.group(0))


def line_of(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def ui_sources(root: Path) -> list[Path]:
    files: set[Path] = set()
    for pattern in UI_ROOTS:
        for base in root.glob(pattern):
            for path in base.rglob("*"):
                rel = path.relative_to(base).parts
                if path.is_file() and path.suffix in CODE_SUFFIXES and not (rel and rel[0] in SKIPPED_DIRS):
                    files.add(path)
    return sorted(files)


def build_files(root: Path) -> list[Path]:
    found = [p for p in root.rglob("*") if p.is_file() and (p.name.endswith(".gradle.kts") or p.suffix == ".toml")]
    return sorted(p for p in found if not any(
        part in {"build", "third_party"} or part.startswith(".") for part in p.relative_to(root).parts[:-1]))


def check(root: Path) -> list[str]:
    root = root.resolve()
    errors: list[str] = []
    for path in ui_sources(root):
        rel = path.relative_to(root).as_posix()
        code = KOTLIN_NOISE.sub(blank, path.read_text(encoding="utf-8", errors="replace"))
        for pattern, api in LIVE_BLUR:
            for match in pattern.finditer(code):
                errors.append(f"{rel}:{line_of(code, match.start())}: {api}: live blur or a glass library. "
                              "The App UI has no glass (#102).")
    for path in build_files(root):
        rel = path.relative_to(root).as_posix()
        text = re.sub(r"(?m)(//|#).*$", blank, path.read_text(encoding="utf-8", errors="replace"))
        for line in sorted({line_of(text, m.start()) for m in GLASS_DEPENDENCY.finditer(text)}):
            errors.append(f"{rel}:{line}: dependency: a glassmorphism library. The App UI has no glass (#102).")
    return errors


def self_test() -> int:
    """A tree with every kind of live blur and glass dependency, plus look-alikes that must pass."""
    files = {
        # Must fail.
        "design/src/main/java/a/Panel.kt": "fun Modifier.p() = this.blur(20.dp)\n",
        "design/src/main/java/a/Effect.kt": "val e = RenderEffect.createBlurEffect(1f, 1f, T)\n",
        "feature/library/ui/src/main/java/a/Card.kt": "val e = BlurEffect(8f, 8f)\nval f = BlurMaskFilter(2f, N)\n",
        "feature/library/ui/src/main/java/a/Haze.kt": "import dev.chrisbanes.haze.hazeSource\n",
        "app/src/main/java/a/Bar.kt": "import com.skydoves.cloudy.cloudy\nval x = 1\n",
        "gradle/libs.versions.toml": 'haze = { module = "dev.chrisbanes.haze:haze", version = "1" }\n',
        # Must pass: names, comments, strings, text shadows, pre-blurred art, the Visual, tests.
        "design/src/main/java/a/Fine.kt": (
            "// this.blur(4.dp)\n/* BlurMaskFilter */\nval s = \"Modifier.blur(2.dp)\"\nval glassTint = 1\n"
            "val sh = Shadow(blurRadius = 10f)\nval art = R.drawable.library_art_preblurred\n"),
        "app/src/main/assets/shaders/blur_frag.glsl": "vec3 blur(vec2 uv);\n",
        "app/src/main/cpp/blur.cpp": "int x = blur(1);\n",
        "engine/scenes/src/main/java/a/Glass.kt": "fun Modifier.g() = blur(4.dp)\n",
        "design/src/test/java/a/BlurTest.kt": "val m = Modifier.blur(1.dp)\n",
        "app/build.gradle.kts": '// haze was here\nimplementation(libs.compose.ui)\n',
    }
    expected = [
        "design/src/main/java/a/Panel.kt:1: Modifier.blur",
        "design/src/main/java/a/Effect.kt:1: RenderEffect.createBlurEffect",
        "feature/library/ui/src/main/java/a/Card.kt:1: BlurEffect",
        "feature/library/ui/src/main/java/a/Card.kt:2: BlurMaskFilter",
        "feature/library/ui/src/main/java/a/Haze.kt:1: Haze",
        "app/src/main/java/a/Bar.kt:1: Cloudy",
        "gradle/libs.versions.toml:1: dependency",
    ]
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        for name, body in files.items():
            (root / name).parent.mkdir(parents=True, exist_ok=True)
            (root / name).write_text(body, encoding="utf-8")
        errors = check(root)
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
    errors = check(Path(__file__).resolve().parents[2])
    for error in errors:
        print(f"::error::{error}")
    if errors:
        print(f"{len(errors)} glass violation(s); see docs/ci.md (No glass in the App UI)", file=sys.stderr)
        return 1
    print("no glass: the App UI has no live blur and no glassmorphism library")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
