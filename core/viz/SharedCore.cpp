#include "geode/viz/SharedCore.hpp"

#include <algorithm>
#include <cmath>

namespace geode::viz::mod {

namespace {
constexpr float kSwitchOn = 0.5f;
using K = ValueKind;

// One row per SharedProperty, same order, same numbers. Keep each row on one line in this exact
// shape: SharedCoreTableKonsistTest parses it.
constexpr std::array<SharedSpec, kSharedCount> kTable = {{
    {"ZOOM", 0.3f, 3.0f, 1.0f, K::Continuous, false},
    {"PUSH_IN", 0.0f, 1.0f, 0.0f, K::Continuous, false},
    {"ROTATION", -3.0f, 3.0f, 0.0f, K::Continuous, false},
    {"ORBIT_YAW", -1.0f, 1.0f, 0.0f, K::Continuous, false},
    {"ORBIT_PITCH", -1.0f, 1.0f, 0.0f, K::Continuous, false},
    {"TRAVEL", -1.0f, 1.0f, 0.0f, K::Continuous, false},
    {"SWAY", 0.0f, 1.0f, 0.0f, K::Continuous, false},
    {"SHAKE", 0.0f, 1.0f, 0.0f, K::Continuous, false},
    {"DRIFT_X", -1.0f, 1.0f, 0.0f, K::Continuous, false},
    {"DRIFT_Y", -1.0f, 1.0f, 0.0f, K::Continuous, false},
    {"ENDLESS_ZOOM", 0.0f, 1.0f, 0.0f, K::Switch, false},
    {"DIVE_SPEED", 0.05f, 1.2f, 0.3f, K::Continuous, false},
    {"FIELD_OF_VIEW", -1.0f, 1.0f, 0.0f, K::Continuous, false},
    {"ROLL", -1.0f, 1.0f, 0.0f, K::Continuous, false},
    {"SPEED_RAMP", 0.0f, 1.0f, 0.0f, K::Continuous, false},
    {"PALETTE", 0.0f, 20.0f, 0.0f, K::Stepped, false},
    {"PALETTE_2", 0.0f, 20.0f, 1.0f, K::Stepped, false},
    {"PALETTE_MIX", 0.0f, 1.0f, 0.0f, K::Continuous, false},
    {"DUOTONE", 0.0f, 1.0f, 0.0f, K::Switch, false},
    {"LUT", -1.0f, 4.0f, -1.0f, K::Stepped, false},
    {"MORPH", 0.0f, 1.0f, 0.0f, K::Continuous, false},
    {"HUE_SHIFT", 0.0f, 1.0f, 0.0f, K::Continuous, false},
    {"HUE_RANGE", 0.0f, 1.5f, 1.0f, K::Continuous, false},
    {"COLOUR_CYCLE", 0.0f, 1.0f, 0.0f, K::Switch, false},
    {"CYCLE_SPEED", 0.02f, 0.6f, 0.1f, K::Continuous, false},
    {"SPEED", 0.05f, 4.0f, 1.0f, K::Continuous, false},
    {"AUDIO_DRIVE", 0.2f, 2.5f, 1.0f, K::Continuous, false},
    {"TURBULENCE", 0.0f, 1.5f, 0.0f, K::Continuous, false},
    {"DETAIL", 0.25f, 1.5f, 1.0f, K::Continuous, false},
    {"GLOW", 0.0f, 1.0f, 0.0f, K::Continuous, true},
    {"KALEIDOSCOPE", 0.0f, 1.0f, 0.0f, K::Switch, false},
    {"SYMMETRY", 0.0f, 16.0f, 6.0f, K::Stepped, false},
    {"MIRROR", 0.0f, 1.0f, 0.0f, K::Switch, false},
    {"TRAILS", 0.0f, 0.98f, 0.0f, K::Continuous, false},
    {"TRAIL_ZOOM", -0.5f, 0.5f, 0.0f, K::Continuous, false},
    {"TRAIL_WARP", 0.0f, 1.0f, 0.0f, K::Continuous, false},
    {"ECHO_COPY", 0.0f, 1.0f, 0.0f, K::Continuous, true},
    {"ECHO_COPY_ZOOM", 0.5f, 2.0f, 1.0f, K::Continuous, false},
    {"ECHO_COPY_FLIP", 0.0f, 3.0f, 1.0f, K::Stepped, false},
    {"GRAIN", 0.0f, 1.0f, 0.0f, K::Continuous, false},
    {"VIGNETTE", 0.0f, 1.0f, 0.0f, K::Continuous, false},
    {"CHROMATIC", 0.0f, 1.0f, 0.0f, K::Continuous, false},
    {"SCANLINES", 0.0f, 1.0f, 0.0f, K::Continuous, false},
    {"FISHEYE", -1.0f, 1.0f, 0.0f, K::Continuous, false},
    {"SOFT_FOCUS", 0.0f, 1.0f, 0.0f, K::Continuous, false},
    {"MOTION_BLUR", 0.0f, 1.0f, 0.0f, K::Continuous, false},
    {"WARP", 0.0f, 1.0f, 0.0f, K::Continuous, false},
    {"RIPPLE", 0.0f, 1.0f, 0.0f, K::Continuous, false},
    {"TWIST", -1.0f, 1.0f, 0.0f, K::Continuous, false},
    {"TILE", 1.0f, 6.0f, 1.0f, K::Continuous, false},
    {"GLITCH", 0.0f, 0.25f, 0.0f, K::Continuous, true},
    {"PIXELATE", 0.0f, 1.0f, 0.0f, K::Continuous, false},
    {"POSTERIZE", 0.0f, 1.0f, 0.0f, K::Continuous, false},
    {"SOLARIZE", 0.0f, 1.0f, 0.0f, K::Switch, true},
    {"PARTICLES", 0.0f, 1.0f, 0.0f, K::Continuous, false},
    {"PARTICLE_KIND", 0.0f, 3.0f, 0.0f, K::Stepped, false},
    {"PARTICLE_SIZE", 0.3f, 2.5f, 1.0f, K::Continuous, false},
    {"SATURATION", 0.0f, 1.5f, 1.0f, K::Continuous, false},
    {"BRIGHTNESS", 0.2f, 1.25f, 1.0f, K::Continuous, true},
    {"INTENSITY", 0.2f, 1.25f, 1.0f, K::Continuous, true},
    {"CONTRAST", 0.3f, 1.25f, 1.0f, K::Continuous, true},
    {"GAMMA", 0.3f, 2.5f, 1.0f, K::Continuous, true},
    {"TEMPERATURE", -1.0f, 1.0f, 0.0f, K::Continuous, false},
    {"INVERT", 0.0f, 1.0f, 0.0f, K::Switch, true},
    {"ANAGLYPH", 0.0f, 1.0f, 0.0f, K::Switch, true},
    {"LAYER_OPACITY", 0.0f, 1.0f, 0.5f, K::Continuous, true},
    {"LAYER_INFLUENCE", 0.0f, 1.0f, 0.5f, K::Continuous, true},
    {"LAYER_SWAP", 0.0f, 1.0f, 0.0f, K::Continuous, true},
}};
}  // namespace

const std::array<SharedSpec, kSharedCount>& sharedSpecs() { return kTable; }

const SharedSpec& spec(Shared property) { return kTable[std::min(static_cast<size_t>(property), kSharedCount - 1)]; }

float toUnit(Shared property, float native) {
    const SharedSpec& s = spec(property);
    const float value = std::isnan(native) ? s.initial : native;
    return std::clamp((value - s.min) / (s.max - s.min), 0.0f, 1.0f);
}

float fromUnit(Shared property, float unit) {
    const SharedSpec& s = spec(property);
    const float u = std::isnan(unit) ? toUnit(property, s.initial) : std::clamp(unit, 0.0f, 1.0f);
    const float native = s.min + (s.max - s.min) * u;
    switch (s.kind) {
        case ValueKind::Continuous: return native;
        case ValueKind::Stepped: return std::round(native);
        case ValueKind::Switch: return native >= kSwitchOn ? 1.0f : 0.0f;
    }
    return native;
}

}  // namespace geode::viz::mod
