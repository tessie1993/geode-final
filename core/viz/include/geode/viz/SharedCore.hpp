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
    FieldOfView, Roll, SpeedRamp,
    Palette, Palette2, PaletteMix, Duotone, Lut, Morph, HueShift, HueRange, ColourCycle, CycleSpeed,
    Speed, AudioDrive, Turbulence,
    Detail, Glow,
    Kaleidoscope, Symmetry, Mirror,
    Trails, TrailZoom, TrailWarp, EchoCopy, EchoCopyZoom, EchoCopyFlip,
    Grain, Vignette, Chromatic, Scanlines, Fisheye, SoftFocus, MotionBlur,
    Warp, Ripple, Twist, Tile,
    Glitch, Pixelate, Posterize, Solarize,
    Particles, ParticleKind, ParticleSize,
    Saturation, Brightness, Intensity, Contrast, Gamma, Temperature, Invert,
    Anaglyph,
    LayerOpacity, LayerInfluence, LayerSwap,
};
constexpr size_t kSharedCount = 68;

// ExtraKind's size and MACRO_COUNT in :feature:customization:api.
constexpr size_t kExtraCount = 23;

// The Style-unique Extras, in ExtraKind's order (a manifest writes them in lower case: "scale",
// "center_x"). One name is one control with one range on every Style that has it (style-packs.md):
// center_x and center_y are offsets in -1..1, variant a whole number in 0..9, and every other Extra a
// multiplier in 0..2 of the Style's own amount, so 1 draws the Style as its pack tunes it.
enum class Extra : uint8_t {
    Scale, Count, Size, Thickness, Flight, Spin, Orbit, Swirl, Drift, Complexity, Edge, Fog,
    Decay, Viscosity, Growth, Spread, Force, Reach, Turn, Hold, Variant, CenterX, CenterY,
};
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
