#pragma once
#include <array>

namespace geode::viz::tile {

// A frame that suits a tiled mobile GPU (#311). Such a GPU draws each pass tile by tile in on-chip
// memory, and what costs it is moving whole targets through main memory: reloading a target's old
// contents before a pass, storing it after, reading it as a texture. So the frame graph:
//   - starts every pass that redraws its whole target by invalidating it, so nothing is reloaded;
//   - draws the composite, the tonemap and the No-flash limiter at the frame size below, and lets
//     the limiter's last pass upscale onto the output, instead of upscaling before the composite;
//   - skips the composite when it would hand the picture on unchanged (compositeIsIdentity): the
//     tonemap then reads the scene target itself;
//   - draws the outgoing Style of a transition at outgoingSize, a quarter of the pixels.

struct Size {
    int width = 1;
    int height = 1;
    bool operator==(const Size&) const = default;
};

// The size the composite, the tonemap and the No-flash limiter draw at: the scene targets' size on
// each side where it is below the output (upscaling it before the composite would only move more
// pixels through every pass after it), and the output's where the scene is supersampled (the
// composite then downsamples, as before). Never below 1x1.
Size frameSize(Size render, Size output);

// The outgoing Style's target during a transition: half the scene target on each side, rounded up,
// and never below 1x1. It is on screen only while it fades, warps or slides away.
Size outgoingSize(Size render);

// Everything composite_frag.glsl reads that can change the active Style's picture when no Layer and
// no transition is running, as the composite uploads it (CompositePass::lookOf). The names follow its
// uniforms; the gates are uGateA and the Camera's x and z of uCamGate.
struct CompositeLook {
    bool hasLayer = false;
    bool hasOutgoing = false;
    // uGateA: x geometry and stylize, y mirror and invert, z grade, zoom and rotation, w beat pulse.
    std::array<float, 4> gate{};
    // uCamGate.x (the Camera's flat moves) and uCamGate.z (its flat endless zoom) for this picture.
    float camMoves = 0.0f;
    float camEndless = 0.0f;
    float camPush = 0.0f;
    float camTiltYaw = 0.0f;
    float camTiltPitch = 0.0f;
    float camShakeX = 0.0f;
    float camShakeY = 0.0f;
    float camShakeRoll = 0.0f;
    float camTravelDive = 0.0f;
    float camEndlessDive = 0.0f;
    // Always applied.
    float flowStrength = 0.0f;
    float rippleStrength = 0.0f;
    float fisheye = 0.0f;
    float glitch = 0.0f;
    float chroma = 0.0f;
    float scanline = 0.0f;
    float grain = 0.0f;
    float vignette = 0.0f;
    float anaglyph = 0.0f;
    float underlayAmount = 0.0f;
    // The geometry and stylize group (gate x).
    float sway = 0.0f;
    float shake = 0.0f;
    bool kaleido = false;
    float symmetry = 0.0f;
    float twist = 0.0f;
    float warp = 0.0f;
    float ripple = 0.0f;
    float driftX = 0.0f;
    float driftY = 0.0f;
    float tile = 1.0f;
    float pixelate = 0.0f;
    float bloom = 0.0f;
    float posterize = 0.0f;
    float temperature = 0.0f;
    bool solarize = false;
    // Mirror and invert (gate y).
    bool mirror = false;
    bool invert = false;
    // The grade, zoom and rotation (gate z): the integrated rotation angle and the hue with its cycle.
    float rotation = 0.0f;
    float zoom = 1.0f;
    float hue = 0.0f;
    float saturation = 1.0f;
    float contrast = 1.0f;
    float gamma = 1.0f;
    float brightness = 1.0f;
    // The beat pulse amount (gate w).
    float pulse = 0.0f;
};

// True when the composite would draw the active Style's picture exactly as it is: every test below
// is the one composite_frag.glsl makes before it touches the picture, with the same thresholds.
bool compositeIsIdentity(const CompositeLook& look);

}  // namespace geode::viz::tile
