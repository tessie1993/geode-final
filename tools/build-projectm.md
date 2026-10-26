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
- `tools/projectm-v4.1.8-stereo-beats.patch` carries upstream master commit `494269e`: bass, mid and
  treble come from both channels rather than the left alone. Geode feeds projectM stereo (#276), so
  music panned right reacts as strongly as music panned left.
- `tools/projectm-v4.1.8-loudness.patch` fixes the loudness followers behind bass, mid and treble
  (#296). Below a silence floor (about -90 dBFS) the followers hold and read a neutral 1.0, the
  averages start from the first real value, and the fast warm-up lasts 50/30 s of audio rather than 50
  frames. Geode recreates the engine on every trip to the background and every Render start or stop,
  which stock replays as a 20x (60 Hz) to 46x (144 Hz) spike on the first frame, and a pause decays
  the long average until resuming spikes (11x after 10 s). It also makes `projectm_set_beat_sensitivity`
  work: stock stores it and reads it nowhere; now 1.0 leaves the values as measured, 0 holds them at
  1.0 and 2.0 doubles every swing away from 1.0.
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
