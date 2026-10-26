#pragma once

#include <array>

#include "geode/viz/Normaliser.hpp"
#include "geode/viz/ReactionGraph.hpp"

namespace geode::viz {

// The Shared core effects that work on every Style's finished picture before the composite pass
// (#11): Trails and echo (with the echo copy, #385), the two blurs (soft focus and motion blur) and the one particle layer.
// The rest of the Shared core (kaleidoscope and mirror, the lens effects, warp and ripple, glitch
// and pixelate, colour grading) is drawn by composite_frag.glsl. Every value starts at 0, so a
// Style looks as it did until someone turns an effect up. SharedFxPass draws them.
struct SharedEffects {
    float trails = 0.0f;
    float trailZoom = 0.0f;
    float trailWarp = 0.0f;
    // The echo copy (#385): how much of it is mixed in (0 off, 1 an even blend), its zoom and its flip
    // (0 none, 1 left to right, 2 top to bottom, 3 both).
    float echoCopy = 0.0f;
    float echoCopyZoom = 1.0f;
    int echoCopyFlip = 0;
    float softFocus = 0.0f;
    float motionBlur = 0.0f;
    float particles = 0.0f;
    int particleKind = 0;
    float particleSize = 1.0f;
    // The red/blue anaglyph switch (#388), 0 or 1. composite_frag.glsl draws it last, so it is not
    // part of active(): SharedFxPass has nothing to do for it.
    float anaglyph = 0.0f;

    bool active() const { return trails > 0.0f || echoCopy > 0.0f || softFocus > 0.0f || motionBlur > 0.0f || particles > 0.0f; }
    // Read in the Shared core's own ranges.
    static SharedEffects from(const mod::Evaluated& evaluated);
    // Read from a frame the Normaliser mapped into the Style family's ranges (what the renderer gets).
    static SharedEffects from(const mod::NativeFrame& frame);
};

namespace sharedfx {

// The frame rate the Trails and Motion blur amounts are tuned at: an amount is what one 60 fps
// frame keeps of the last, and other frame rates keep the same share per second.
constexpr float kReferenceFps = 60.0f;
constexpr float kMaxTrails = 0.98f;
// Full motion blur keeps this much of the last frame at 60 fps, so the new frame always shows.
constexpr float kMaxMotionKeep = 0.85f;

// How much of the last frame's picture an echo keeps this frame (it never darkens the new frame).
float echoKeep(float trails, float dt);
// How much of the last frame motion blur mixes into this one.
float motionKeep(float motionBlur, float dt);

// The echo's zoom and warp for a frame of dt, in the per-frame units the feedback shaders read
// (trail_warp_frag's uZoom and uWarp, shared_fx_frag's uEchoZoom and uEchoWarp). `zoom` and `warp` are
// what one 60 fps frame moves the picture by, so a 120 Hz frame moves it half as far and the echo
// streams at the same speed on every panel. A stalled frame moves nothing.
//
// The shaders scale the picture about its centre by (1 - zoom x kEchoZoomScale) a frame; over n
// reference frames that is (1 - zoom x kEchoZoomScale)^n, handed back in the same form.
constexpr float kEchoZoomScale = 0.06f;
float echoZoom(float zoom, float dt);
float echoWarp(float warp, float dt);

// The echo copy (#385): a second copy of this frame's picture, zoomed about its centre and flipped,
// mixed over it for display (shared_fx_frag's uEchoCopy, uEchoCopyZoom and uEchoCopyFlip). A flip of
// the picture keeps its overall brightness, and the mix is at most an even blend, so the copy moves no
// more light than it shows. A change of flip never cuts over: the copy fades out over
// kEchoCopyFadeSeconds, turns while it is gone, and fades back in.
constexpr float kEchoCopyFadeSeconds = 0.25f;
constexpr float kEchoCopyMaxMix = 0.5f;
constexpr float kMinEchoCopyZoom = 0.5f;
constexpr float kMaxEchoCopyZoom = 2.0f;
constexpr int kEchoCopyFlips = 4;

// The flip the copy shows and how far it has faded in (0..1).
struct EchoCopyFade {
    int flip = 0;
    float presence = 0.0f;

    bool operator==(const EchoCopyFade&) const = default;
};

// One frame of `dt`: toward full presence while the copy shows `targetFlip` (held to 0..3), else
// toward none, taking `targetFlip` once it is gone. A stalled or broken frame holds the fade.
EchoCopyFade stepEchoCopy(const EchoCopyFade& fade, int targetFlip, float dt);
// The share of the copy mixed over the picture: `amount` (0..1) of the even blend, faded by `presence`.
float echoCopyMix(float amount, float presence);
// The copy's zoom held to its range; 1 when broken.
float echoCopyZoom(float zoom);
// What the copy's offset from the centre is multiplied by on x and y for `flip`: -1 where it is flipped.
std::array<float, 2> echoCopyFlipScale(int flip);

// How long the anaglyph takes to fade fully in or out: switching it never cuts the picture over.
constexpr float kAnaglyphFadeSeconds = 0.5f;
// The anaglyph's mix this frame, moved from `current` toward `target` (each 0..1) at most a frame's
// share of kAnaglyphFadeSeconds; it holds where it is on a stalled frame.
float anaglyphMix(float current, float target, float dt);

}  // namespace sharedfx

}  // namespace geode::viz
