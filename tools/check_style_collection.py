#!/usr/bin/env python3
"""Dependency-free source checks for the composite style collection."""

from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"style collection check failed: {message}")


def declared_uniforms(shader: str) -> set[str]:
    return set(re.findall(r"uniform\s+(?:highp\s+|mediump\s+|lowp\s+)?\w+\s+(\w+)", shader))


def uploaded_uniforms(scene: str) -> set[str]:
    # Matches both the plain `loc("name")` calls used inside a scene and the
    # `locs.loc("name")` / `uniforms_.loc("name")` forms used through UniformCache.
    return set(re.findall(r'loc\("(\w+)"\)', scene))


def main() -> None:
    styles = ROOT / "engine/scenes/src/main/stylepacks/styles"
    cym_shader = read("app/src/main/assets/shaders/cymatics_field_frag.glsl")
    cym_scene = read("core/viz/scenes/CymaticsScene.cpp")
    # The touch-point uniforms are uploaded through the shared helper in
    # SceneCommon.hpp (uploadSceneTouch), not inline in CymaticsScene.cpp itself.
    scene_common = read("core/viz/scenes/SceneCommon.hpp")
    shell = read("app/src/main/java/dev/geode/ui/AppShell.kt")
    crystal = read("app/src/main/java/dev/geode/ui/Crystal.kt")
    theme_catalog = read("app/src/main/java/dev/geode/ui/theme/ThemePackCatalog.kt")

    # The Cymatics Styles are packs (docs/style-packs.md); each names a branch of the shared shader.
    index = json.loads((styles / "index.json").read_text(encoding="utf-8"))["styles"]
    manifests = [json.loads((styles / i / "manifest.json").read_text(encoding="utf-8")) for i in index]
    cym = [m for m in manifests if m["family"] == "cymatics"]
    cym_ids = [m["id"] for m in cym]
    require(len(set(cym_ids)) == len(cym_ids), "style IDs collide")
    require("uniform int uStyle;" in cym_shader, "Cymatics shader lacks uStyle")
    for m in cym:
        branch = m.get("parameters", {}).get("shaderStyle", 0)
        require(branch == 0 or f"uStyle == {branch}" in cym_shader, f"Cymatics shader lacks branch {branch} for {m['id']}")

    require(
        declared_uniforms(cym_shader) == uploaded_uniforms(cym_scene + scene_common),
        "Cymatics uniform parity drift",
    )

    # The App UI keeps one theme pack, Lapis Lazuli, until the App UI redesign.
    require("val lapisLazuli =" in theme_catalog, "missing the Lapis Lazuli theme pack")

    require("CrystalMaterialTheme(" in shell, "shell does not propagate the active theme pack")
    require("LocalThemePack provides pack" in crystal, "crystal panels do not receive the active theme pack")

    print("style collection checks passed")
    print(f"  Cymatics substyles:   {len(cym_ids)}")


if __name__ == "__main__":
    main()
