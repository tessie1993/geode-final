#pragma once
#include <array>
#include <cstddef>
#include <cstdint>

namespace geode::viz::mod {

// The Shared core (CONTEXT.md) as the engine reads it: every property every Style has, in
// SharedProperty's order in :feature:customization:api, with the same range, starting value, kind
// and luminous mark. SharedCoreTableKonsistTest (in :feature:customization:core) reads the table in
// SharedCore.cpp and fails when the two drift apart.
enum class Shared : uint8_t {
    Zoom, PushIn, Rotation, OrbitYaw, OrbitPitch, Travel, Sway, Shake, DriftX, DriftY, EndlessZoom, DiveSpeed,
    Palette, Palette2, PaletteMix, Duotone, Lut, Morph, HueShift, HueRange, ColourCycle, CycleSpeed,
    Speed, AudioDrive, Turbulence,
    Detail, Glow,
    Kaleidoscope, Symmetry, Mirror,
    Trails, TrailZoom, TrailWarp,
    Grain, Vignette, Chromatic, Scanlines, Fisheye, SoftFocus, MotionBlur,
    Warp, Ripple, Twist, Tile,
    Glitch, Pixelate, Posterize, Solarize,
    Particles, ParticleKind, ParticleSize,
    Saturation, Brightness, Intensity, Contrast, Gamma, Temperature, Invert,
};
constexpr size_t kSharedCount = 58;

// ExtraKind's size and MACRO_COUNT in :feature:customization:api.
constexpr size_t kExtraCount = 23;
constexpr size_t kMacroCount = 6;
constexpr float kMacroRest = 0.5f;

enum class ValueKind : uint8_t { Continuous, Stepped, Switch };

struct SharedSpec {
    const char* name;
    float min;
    float max;
    float initial;
    ValueKind kind;
    // Moves overall brightness: a Reaction on it always rises over the No-flash minimum.
    bool luminous;
};

const std::array<SharedSpec, kSharedCount>& sharedSpecs();
const SharedSpec& spec(Shared property);

// A native value as a fraction 0..1 of the property's range (not a number: its starting value).
float toUnit(Shared property, float native);
// A fraction back to a native value: kept in range, rounded when stepped, 0 or 1 when a switch.
float fromUnit(Shared property, float unit);

}  // namespace geode::viz::mod
