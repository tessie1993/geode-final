# projectM

projectM is built in-tree from the `third_party/projectm` git submodule (tag v4.1.8) plus Geode's local
patches, listed and applied at configure time (idempotently) by `tools/projectm-patches.cmake`:

- `tools/projectm-v4.1.8-render-fbo-backport.patch` backports `projectm_opengl_render_frame_fbo` from
  upstream master: stock v4.1.8 can only composite a frame onto the default framebuffer, and
  `core/viz/scenes/MilkdropScene.cpp` renders into its own framebuffer through this call.
- `tools/projectm-v4.1.8-render-clock.patch` adds `projectm_set_frame_time` and
  `projectm_set_random_seed`. A seeded instance draws every random value (preset state, shader
  randoms, noise textures, random textures, transitions) from its own generator, and preset
  transitions run on projectM's clock instead of the wall clock. MilkdropScene uses both during a
  Render, so the same song and settings always give the same video (ADR-0005).
- `tools/projectm-v4.1.8-plasma-mediump.patch` carries upstream master commit `0227b7a`: the plasma
  transition's noise overflowed at mediump precision on mobile GPUs, and the Visual uses blended
  transitions.
- `tools/projectm-eval-render-clock.patch`, applied inside `vendor/projectm-eval`, lets the seeded
  instance feed the expression `rand()` too. The build therefore always uses the vendored
  projectm-eval, never a system one.

The root `CMakeLists.txt` includes `tools/projectm-patches.cmake` and links `libgeode.so` against the
`projectM` target. LGPL-2.1: the patches are the corresponding source of the modification.
Run `git submodule update --init --recursive` after cloning or pulling; the app build then compiles it per ABI.
`checkNativePageAlignment` in `app/build.gradle.kts` still checks every packaged `.so` for 16 KB alignment.

CI builds the same patched copy on the host (`tools/milkdrop-determinism`) and checks that two
Renders of the same input are byte-identical (`docs/ci.md`).

To change a patch: apply the existing patches to a clean v4.1.8 checkout in order, edit, and
regenerate the patch with `git diff` against the state before it.
