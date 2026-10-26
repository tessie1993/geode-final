---
paths:
  - "app/src/main/assets/shaders/**"
  - "engine/scenes/src/main/stylepacks/**"
  - "tools/glslcheck/**"
---

# Shaders and Style packs

GLSL is not in the Graphify index. A shader is named by its file name from C++, so `grep -rn "<name>.glsl" core/viz core/render` finds the pass that owns it, and `docs/visual-frames.md` says what each pass draws and what CI checks of it.

- Every shader must compile under GLSL ES 3.00 and draw a lit frame on the host (`tools/glslcheck`, the "Shaders compile and render" job). There are no golden images (#184): a check is a property of a drawn frame.
- A shader may `#include` only the `lib_*.glsl` files listed in `kIncludes` in `core/render/ShaderSource.cpp`; glslcheck reads that list. Adding a library means adding it there.
- A Style is a data pack in `engine/scenes/src/main/stylepacks/styles/` (`docs/style-packs.md`): its shader and manifest. Adding or removing a Style changes the packs only; no Kotlin or C++ names a Style except the default. `tools/ci/check_style_packs.py` validates every pack.
- The No-flash rule (`CONTEXT.md`) bounds every Style: nothing a shader does may bypass the engine-wide brightness limiter.
