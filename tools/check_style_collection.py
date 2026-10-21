#!/usr/bin/env python3
"""Dependency-free source checks for the composite style collection."""

from __future__ import annotations

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
    catalog = read("engine/scenes/src/main/kotlin/dev/geode/render/scene/VisualStyleCatalog.kt")
    cym_shader = read("app/src/main/assets/shaders/cymatics_field_frag.glsl")
    cym_scene = read("core/viz/scenes/CymaticsScene.cpp")
    # The touch-point uniforms are uploaded through the shared helper in
    # SceneCommon.hpp (uploadSceneTouch), not inline in CymaticsScene.cpp itself.
    scene_common = read("core/viz/scenes/SceneCommon.hpp")
    scene_registry = read("core/viz/SceneRegistry.cpp")
    hub = read("app/src/main/java/dev/geode/ui/VisualsHub.kt")
    shell = read("app/src/main/java/dev/geode/ui/AppShell.kt")
    crystal = read("app/src/main/java/dev/geode/ui/Crystal.kt")
    theme_catalog = read("app/src/main/java/dev/geode/ui/theme/ThemePackCatalog.kt")

    # \s* after the paren: ktlint wraps long constructor calls onto their own
    # lines, so the id literal is not necessarily on the same line as the name.
    cym_ids = re.findall(r'CymaticsStyle\(\s*"([^" ]+)"', catalog)
    require(len(cym_ids) == 10, f"expected 10 Cymatics substyles, got {len(cym_ids)}")
    require(len(set(cym_ids)) == 10, "style IDs collide")

    for family, shader in (("Cymatics", cym_shader),):
        require("uniform int uStyle;" in shader, f"{family} shader lacks uStyle")
        for style in range(1, 11):
            require(f"uStyle == {style}" in shader, f"{family} shader lacks branch {style}")

    require(
        declared_uniforms(cym_shader) == uploaded_uniforms(cym_scene + scene_common),
        "Cymatics uniform parity drift",
    )

    # Scene resolution moved out of Kotlin (VisualizerRenderer just calls into
    # the native renderer) and into core/viz/SceneRegistry.cpp, which is what
    # actually lists, resolves and instantiates the Cymatics substyles now.
    require(
        "append(out, styles::cymaticsIds());" in scene_registry,
        "native scene registry does not offer Cymatics variants",
    )
    require(
        "styles::cymatics(id)" in scene_registry,
        "native scene registry does not resolve Cymatics variants",
    )
    require(
        "std::make_unique<CymaticsScene>(*style, loader_, host_)" in scene_registry,
        "native scene registry no longer routes Cymatics creation through the shared style lookup",
    )
    require("SceneList(VisualStyleCatalog.cymaticsIds" in hub, "Cymatics variants are absent from the picker")

    # The App UI keeps one theme pack, Lapis Lazuli, until the App UI redesign.
    require("val lapisLazuli =" in theme_catalog, "missing the Lapis Lazuli theme pack")

    require("CrystalMaterialTheme(" in shell, "shell does not propagate the active theme pack")
    require("LocalThemePack provides pack" in crystal, "crystal panels do not receive the active theme pack")

    print("style collection checks passed")
    print(f"  Cymatics substyles:   {len(cym_ids)}")


if __name__ == "__main__":
    main()
