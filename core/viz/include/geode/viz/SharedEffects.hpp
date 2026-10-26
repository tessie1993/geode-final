#pragma once

#include "geode/viz/Normaliser.hpp"
#include "geode/viz/ReactionGraph.hpp"

namespace geode::viz {

// The Shared core effects that work on every Style's finished picture before the composite pass
// (#11): Trails and echo, the two blurs (soft focus and motion blur) and the one particle layer.
// The rest of the Shared core (kaleidoscope and mirror, the lens effects, warp and ripple, glitch
// and pixelate, colour grading) is drawn by composite_frag.glsl. Every value starts at 0, so a
// Style looks as it did until someone turns an effect up. SharedFxPass draws them.
struct SharedEffects {
    float trails = 0.0f;
    float trailZoom = 0.0f;
    float trailWarp = 0.0f;
    float softFocus = 0.0f;
    float motionBlur = 0.0f;
    float particles = 0.0f;
    int particleKind = 0;
    float particleSize = 1.0f;
    // The red/blue anaglyph switch (#388), 0 or 1. composite_frag.glsl draws it last, so it is not
    // part of active(): SharedFxPass has nothing to do for it.
    float anaglyph = 0.0f;

    bool active() const { return trails > 0.0f || softFocus > 0.0f || motionBlur > 0.0f || particles > 0.0f; }
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

// How long the anaglyph takes to fade fully in or out: switching it never cuts the picture over.
constexpr float kAnaglyphFadeSeconds = 0.5f;
// The anaglyph's mix this frame, moved from `current` toward `target` (each 0..1) at most a frame's
// share of kAnaglyphFadeSeconds; it holds where it is on a stalled frame.
float anaglyphMix(float current, float target, float dt);

}  // namespace sharedfx

}  // namespace geode::viz
