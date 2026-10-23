#pragma once
#include "geode/viz/Normaliser.hpp"
#include "geode/viz/Params.hpp"

namespace geode::viz {

// The evaluated Shared core, in its native units, onto the scene parameters every Style and the
// composite pass draw from: once a Customization plan is live it, not the scene parameters a snapshot
// carries, sets the Camera's zoom, rotation, sway, drift and endless zoom, the colour (palettes, LUT,
// morph, hue, cycle), speed, audio drive and turbulence, Detail and Glow, kaleidoscope and mirror,
// the lens effects, warp and ripple, glitch and pixelate, and the grade. The parts of the Camera the
// scene parameters have no field for (push-in, orbit, travel, shake) are drawn from the CameraRig;
// the shake field is cleared here so no Style also draws the old per-beat jitter. Trails, the blurs
// and the particle layer are SharedEffects'. Runs before the visual-safety clamp, which still bounds
// every value.
void applySharedCore(SceneParams& p, const mod::NativeFrame& frame);

}  // namespace geode::viz
