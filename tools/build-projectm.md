# projectM

projectM is built in-tree from the `third_party/projectm` git submodule (tag v4.1.8) plus Geode's local
patches, listed and applied at configure time (idempotently) by `tools/projectm-patches.cmake`:

- `tools/projectm-v4.1.8-render-fbo-backport.patch` backports `projectm_opengl_render_frame_fbo` from
  upstream master: stock v4.1.8 can only composite a frame onto the default framebuffer, and
  `core/viz/scenes/MilkdropScene.cpp` renders onto the renderer's scene target through this call.
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
- `tools/projectm-v4.1.8-texture-pool.patch` keeps the six generated noise textures for the life of
  an engine: `MilkdropScene` sets new texture paths for every preset, which used to rebuild them on the
  CPU each time. A seeded engine makes them again from its seed, so Renders stay frame-exact. The
  scanner skips hidden folders (the per-preset `.links` folders), and a random texture (`randNN`)
  draws from each file once, however many search paths or links reach it (#307).
- `tools/projectm-v4.1.8-lean-frame.patch` skips the motion vectors' u/v map (a whole RG16F target
  written during the warp) whenever the preset shows no motion vectors, which is most of them (#303).
  `MilkdropScene` also has projectM draw its frame straight onto the scene target, graded by the
  composite, instead of through a copy and a post pass of its own.
- `tools/projectm-v4.1.8-program-cache.patch` adds `projectm_opengl_set_shader_caches`: per-thread
  hooks asked before every program is compiled and every preset shader is translated from HLSL, and
  handed each result. `MilkdropScene` points them at the renderer's `ProgramBinaryCache` (binaries per
  driver, translated text for all) for the length of each draw, so a preset seen before loads without
  translating or compiling (#304).
- `tools/projectm-v4.1.8-shader-extras.patch` lets preset shaders written for MilkDrop 3 and
  BeatDrop-era players run (#306): `get_fft*`/`get_wave*` sample two small spectrum and waveform
  textures (`fft`, `wave`) filled from the audio each frame, only while a loaded shader uses them;
  `mouse*`, `aspect_sd`, `sysTime*` and `sysDate*` read the pointer (`projectm_set_pointer`) and the
  clock (a fixed day on a host-driven clock, so Renders stay frame-exact); the translator knows
  `tanh`, `sinh`, `cosh`, `radians`, `degrees` and `trunc`; `texture2D`-style aliases and random
  texture slots up to `rand99` are accepted. `projectm_set_shader_fallback_event_callback` reports a
  preset shader that fell back to the plain warp or composite. Written from the documented behaviour
  only; no BeatDrop code.
- `tools/projectm-v4.1.8-milkdrop3-extras.patch` plays the larger presets MilkDrop 3 writes (#389):
  16 custom waves and 16 custom shapes instead of 4 each (`wavecode_4` to `wavecode_15`,
  `shapecode_4` to `shapecode_15` and their code), and `q1` to `q64` instead of `q32`, carried from the
  per-frame equations to the waves, shapes, per-pixel equations and both shaders (`_qi` to `_qp`).
  MilkDrop 2 presets play as before; the extra slots are off unless a preset enables them.
- `tools/projectm-eval-render-clock.patch`, applied inside `vendor/projectm-eval`, lets the seeded
  instance feed the expression `rand()` too. The build therefore always uses the vendored
  projectm-eval, never a system one.

The root `CMakeLists.txt` includes `tools/projectm-patches.cmake` and links `libgeode.so` against the
`projectM` target. LGPL-2.1: the patches are the corresponding source of the modification.
Run `git submodule update --init --recursive` after cloning or pulling; the app build then compiles it per ABI.
`checkNativePageAlignment` in `app/build.gradle.kts` still checks every packaged `.so` for 16 KB alignment.

projectM 4.2 is not tagged yet (upstream master already calls itself 4.2.0). When it is, the
submodule moves to that tag and the patches upstream now carries (the FBO render, the frame-time
clock, `0227b7a`, `494269e`) drop out of the chain.

CI builds the same patched copy on the host (`tools/milkdrop-determinism`) and checks that two
Renders of the same input are byte-identical (`docs/ci.md`).

To change a patch: apply the existing patches to a clean v4.1.8 checkout in order, edit, and
regenerate the patch with `git diff` against the state before it.
