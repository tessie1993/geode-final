# CI: Shaders compile and render

What the `visual-frames` job in `.github/workflows/ci.yml` draws and checks. The job stores and compares no images (#184): every check is a property of a frame drawn on the host, so nothing has to be committed.

## Style packs

Every Style pack is valid ([style-packs.md](style-packs.md); `tools/ci/check_style_packs.py`, after a self-test that proves it still catches violations). Every Style's shader compiles and renders a non-black frame, and its linked program reads no shared Style input its manifest does not declare. Each Style's picture also survives a scene-target resize (`Framebuffer::resizePreserving`, ADR-0005 "Visual buffers follow load and never go black") without going dark.

## Shared core effects and the HDR pipeline

Each Shared core effect (kaleidoscope and mirror, trails and echo and the echo copy, zoomed and flipped, the lens effects and both blurs, warp and ripple, glitch and pixelate, the particle layer, colour grading and MilkDrop's palette tint, the red/blue anaglyph) draws a frame through `composite_frag.glsl` or `shared_fx_frag.glsl` over the same stand-in picture. So do:

- the HDR pipeline's bloom and tonemap passes (`bloom_down_frag.glsl`, `bloom_up_frag.glsl`, `tonemap_frag.glsl`, with and without the lens light);
- each part of the Style lens effects' glare (flare ghosts, god rays, star, zoom and anamorphic streaks: `lens_frag.glsl`);
- depth of field (`dof_frag.glsl`);
- the 3D particle layer's streaks and sparks (`particles3d_vert.glsl` and `particles3d_frag.glsl`, drawn instanced);
- Layer 2 (`lib_layers.glsl`) in every blend mode and influence, swapped, half faded and over a running fade.

## Smaller frames

The tile-friendly frame's paths (#311) draw too: the composite's transitions reading a half-size outgoing picture, the tonemap reading a smaller scene target directly (the skipped composite), and the No-flash limiter's last pass (`noflash_mix_frag.glsl`) upscaling a smaller frame onto the output. A multi-pass Style's resolve (`multipass_resolve_frag.glsl`, #332) draws a frame from a smaller march, on its own and with a history.

## Feedback

A Style with feedback also draws a frame of its warp stage on the motion grid (`feedback_warp_vert.glsl`), the shared stage draws a frame with the borders and one with the streaks painted into the loop, and the feedback loop's store runs for real: a white frame kept through the shared warp (`feedback_warp_frag.glsl`) at the slowest decay on a 120 Hz panel, in RGBA16F, RGB10_A2 and RGBA8, must follow the exact decay and end at exact black, while the same loop undithered must stall (proving the check can tell).

## Style transitions

Every look a change of Style may take (`kSafeLooks` in `core/viz/include/geode/viz/StyleTransition.hpp`, #309) is drawn through the composite at points along its progress, and fails if the frame's brightness leaves the span of its two pictures (a dip to black or a wash to white); two planted looks that do exactly that must fail, which proves the check still catches them.

## glslang

Then glslang (`glslangValidator`, installed in CI only, never shipped) checks every Style's assembled shader (and warp stage) against the GLSL ES 3.00 specification, after a self-test that proves it still fails on planted violations; errors name the Style's file and line.
