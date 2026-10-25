# glslcheck

Compiles and renders the app's shader assets headlessly, and writes the
frame to a PNG so the result can be looked at rather than trusted.

`tools/shaderpreview` predates the C++ port of the renderer: it reads Kotlin
scene files that no longer exist and cannot run. This is the small
replacement. It answers two questions per shader - does the GLSL the native
core would assemble compile under an ES 3.00 grammar, and does one frame of
it come out non-black - and nothing more.

```sh
cd tools/glslcheck
node check.mjs --list                          # the shader Style ids, from the Style packs
node check.mjs --shader kifs --out out/kifs.png
node check.mjs --all --size 96                 # every fragment style, compile + frame stats
node check.mjs --fluid-display --out out/fluid # every SHADING/BLOOM/SUNRAYS x LOOK variant
node check.mjs --shader curl_bloom --time 61   # a different point on the clocks
node check.mjs --all --resize --size 96        # each style's picture across a scene-target resize
node check.mjs --effects --size 96 --out out/effects  # one frame per Shared core effect
```

`--resize` draws one frame into a scene target, resizes the target the way
`Framebuffer::resizePreserving` does (a linear blit into the new target,
never a clear) and writes what the resized target holds: the picture the
renderer's next frame starts from after the load scale moves. It fails when
that picture comes out darker than half the one before the resize.

## How it stays honest

- Includes are resolved the way `core/render/ShaderSource.cpp` resolves them:
  the registered names are parsed out of that file's `kIncludes`, the line
  pattern is the same anchored `//#include <name>`, and an unknown name is a
  hard error.
- Every uniform the resolved GLSL declares must have a value in the table in
  `check.mjs` (which mirrors `ShaderScene::uploadParams` / `uploadMotion` /
  `uploadTouch` with `SceneParams` defaults and a mid-loudness frame) or the
  run refuses to render. A uniform the harness forgot defaults to zero in GL,
  and a zeroed feature is indistinguishable from a broken one.
- `dropped[...]` in a report line lists uniforms the linker removed because
  the shader never reads them. That is a finding about the shader, not an
  error.
- Textures are named stand-ins (`audio`, `palette`, `dye`, ...), RGBA8 where
  the app uses float formats.

## What it cannot tell you

Everything `tools/shaderpreview/README.md` says under the same heading still
holds: rendering is ANGLE over SwiftShader, which ignores precision
qualifiers, tolerates uniform counts real ES 3.0 drivers reject, and is not a
Mali or an Adreno. This is a lower bound on portability, not a device check.
`docs/DEVICE_CHECKS.md` is still the authority.

The Chromium binary comes from `/opt/pw-browsers/chromium`; override with
`MUSICVIZ_CHROMIUM`. Rendered output goes under `out/`, which is ignored.
