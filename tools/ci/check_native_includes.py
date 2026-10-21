#!/usr/bin/env python3
"""Fail when a native library includes another library's private header.

Each native library lives in core/<lib>/. Its public headers sit in core/<lib>/include/geode/<lib>/
and are included as "geode/<lib>/Header.hpp"; everything else in core/<lib>/ is private to it and
included relative to core/<lib>/. A file may include:

- any header of its own library, and
- the public headers of the libraries it depends on (LIBRARY_DEPS below, which mirrors the
  target_link_libraries lines in core/CMakeLists.txt).

Anything else - another library's private header, reached by a relative path, by "../" or by any
other route, or a public header of a library outside its dependencies - is an error. The dependency
graph itself must stay acyclic. The graph and the layout: docs/native-libraries.md.

    python3 tools/ci/check_native_includes.py               # check the repository
    python3 tools/ci/check_native_includes.py --self-test   # prove the check catches violations
"""

from __future__ import annotations

import re
import sys
import tempfile
from pathlib import Path

# Who may include whose public headers. "api_types" is the C ABI header geode/api/geode_api.h
# (core/api/include); "api" is the rest of core/api, the C ABI implementation over every library.
LIBRARY_DEPS: dict[str, set[str]] = {
    "api_types": set(),
    "util": set(),
    "render": {"util"},
    "analysis": {"api_types"},
    "audio": {"api_types", "util"},
    "library": set(),
    "viz": {"api_types", "render", "analysis", "util"},
    "api": {"api_types", "util", "analysis", "audio", "library", "viz"},
}
# Consumers that are not libraries: the host unit tests (pure parts only) and the app's JNI layer.
CONSUMER_DEPS: dict[str, set[str]] = {
    "tests": {"api_types", "util", "analysis", "audio", "viz"},
    "jni": {"api_types"},
}
LIBRARY_DIRS = ("api", "util", "render", "analysis", "audio", "library", "viz")
SOURCE_SUFFIXES = {".c", ".cc", ".cpp", ".h", ".hpp"}
INCLUDE = re.compile(r'^\s*#\s*include\s*([<"])([^>"]+)[>"]', re.M)


def unit_of(root: Path, path: Path) -> tuple[str | None, bool]:
    """(unit, is_public) for a file under the repository root, or (None, False) if no unit owns it."""
    rel = path.relative_to(root).parts
    if len(rel) >= 2 and rel[0] == "core":
        if rel[1] in LIBRARY_DIRS:
            public = len(rel) >= 5 and rel[2] == "include" and rel[3] == "geode" and rel[4] == rel[1]
            if rel[1] == "api" and len(rel) >= 3 and rel[2] == "include":
                return "api_types", public
            return rel[1], public
        if rel[1] == "tests":
            return "tests", False
    if rel[:4] == ("app", "src", "main", "cpp"):
        return "jni", False
    return None, False


def find_cycle(graph: dict[str, set[str]]) -> list[str] | None:
    state: dict[str, int] = {}
    stack: list[str] = []

    def visit(node: str) -> list[str] | None:
        state[node] = 1
        stack.append(node)
        for dep in sorted(graph.get(node, ())):
            if state.get(dep) == 1:
                return stack[stack.index(dep):] + [dep]
            if dep not in state:
                found = visit(dep)
                if found:
                    return found
        stack.pop()
        state[node] = 2
        return None

    for node in sorted(graph):
        if node not in state:
            found = visit(node)
            if found:
                return found
    return None


def resolve(root: Path, src: Path, unit: str, spec: str) -> Path | None:
    """Where the compiler would find a quoted/angled include, given the include dirs CMake sets."""
    candidates = [src.parent / spec]
    lib_dir = src.relative_to(root).parts[1] if unit in LIBRARY_DEPS else None
    if lib_dir:
        candidates.append(root / "core" / lib_dir / spec)
    candidates += [root / "core" / d / "include" / spec for d in LIBRARY_DIRS]
    for cand in candidates:
        try:
            resolved = cand.resolve()
            resolved.relative_to(root)
        except (OSError, ValueError):
            continue
        if resolved.is_file():
            return resolved
    return None


def check(root: Path) -> list[str]:
    root = root.resolve()
    errors: list[str] = []
    graph = dict(LIBRARY_DEPS)
    cycle = find_cycle(graph)
    if cycle:
        errors.append("native library dependency cycle: " + " -> ".join(cycle))

    sources = [p for d in ("core", "app/src/main/cpp") if (root / d).is_dir()
               for p in sorted((root / d).rglob("*")) if p.suffix in SOURCE_SUFFIXES and p.is_file()]
    for src in sources:
        unit, _ = unit_of(root, src)
        if unit is None:
            continue
        rel_src = src.relative_to(root)
        rel = rel_src.parts
        # A stray header in core/<lib>/include outside geode/<lib>/ would be public but unnamespaced.
        if unit in LIBRARY_DEPS and len(rel) >= 3 and rel[2] == "include" and not (
                len(rel) >= 5 and rel[3] == "geode" and rel[4] == rel[1]):
            errors.append(f"{rel_src}: public headers belong in core/{rel[1]}/include/geode/{rel[1]}/")
        allowed = LIBRARY_DEPS.get(unit, CONSUMER_DEPS.get(unit, set()))
        text = src.read_text(encoding="utf-8", errors="replace")
        for match in INCLUDE.finditer(text):
            spec = match.group(2)
            line = text.count("\n", 0, match.start()) + 1
            target = resolve(root, src, unit, spec)
            if target is None:
                continue  # a system or third-party header
            target_unit, target_public = unit_of(root, target)
            if target_unit is None or target_unit not in LIBRARY_DEPS:
                continue
            where = f"{rel_src}:{line}: #include \"{spec}\""
            same_library = (target_unit == unit
                            or {unit, target_unit} == {"api", "api_types"} and target_public)
            if same_library:
                continue
            if not target_public:
                errors.append(f"{where} reaches {target.relative_to(root)}, a private header of "
                              f"the {target_unit} library")
            elif target_unit not in allowed:
                errors.append(f"{where}: {unit} does not depend on {target_unit} "
                              f"(tools/ci/check_native_includes.py LIBRARY_DEPS, core/CMakeLists.txt)")
    return errors


def self_test() -> int:
    """Builds a small tree with one allowed include and three violations and checks all are seen."""
    files = {
        "core/util/include/geode/util/Log.hpp": "#pragma once\n",
        "core/util/Json.cpp": '#include "geode/util/Log.hpp"\n#include "Private.hpp"\n',
        "core/util/Private.hpp": "#pragma once\n",
        "core/render/Quad.cpp": '#include "geode/util/Log.hpp"\n',
        "core/analysis/Spectrum.cpp": '#include "../util/Private.hpp"\n#include "geode/util/Log.hpp"\n',
        "core/viz/scenes/A.cpp": '#include "../../render/Quad.cpp"\n',
    }
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        for name, body in files.items():
            (root / name).parent.mkdir(parents=True, exist_ok=True)
            (root / name).write_text(body)
        errors = check(root)
    expected = ["core/analysis/Spectrum.cpp:1", "core/analysis/Spectrum.cpp:2", "core/viz/scenes/A.cpp:1"]
    ok = len(errors) == len(expected) and all(any(e.startswith(x) for e in errors) for x in expected)
    print("\n".join(errors))
    print("self-test " + ("passed" if ok else "FAILED"))
    return 0 if ok else 1


def main(argv: list[str]) -> int:
    if "--self-test" in argv:
        return self_test()
    root = Path(__file__).resolve().parents[2]
    errors = check(root)
    for error in errors:
        print(f"::error::{error}")
    if errors:
        print(f"{len(errors)} native include violation(s); see docs/native-libraries.md", file=sys.stderr)
        return 1
    print("native includes: every library uses only its own headers and its dependencies' public headers")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
