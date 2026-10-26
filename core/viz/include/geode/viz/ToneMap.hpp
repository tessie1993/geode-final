#pragma once
#include <array>

namespace geode::viz {

// The maths of the HDR pipeline (HdrPass, tonemap_frag.glsl, bloom_down_frag.glsl,
// bloom_up_frag.glsl), mirrored here so the host tests can hold the shaders to it.
//
// The scene, trail, Shared core effect and composite targets are RGBA16F where the device renders
// them, so a Style's light keeps its headroom above 1 all the way to the end of the frame. One final
// pass then adds a real bloom (a dual-filter mip chain of the light above a soft threshold, in
// linear light), rolls the highlights off, encodes for the display and dithers. The No-flash limiter
// measures and limits what that pass wrote, so the cap holds on the tonemapped HDR picture. A device
// that cannot render RGBA16F keeps the 8-bit frame graph, with no bloom pass and no curve.
//
// Every Style was authored by eye on an unmanaged display, so a Style's colour is a display-encoded
// value and 1 is the display's white. The curve leaves everything up to that white exactly as each
// Style made it (identity on [0, 1]); only the HDR highlights above it, from the Styles' own headroom
// and from bloom, roll off. Above 1 a smooth shoulder, with slope 1 where it leaves the identity,
// compresses the brightest channel toward kShoulderCeiling; how far it has gone toward the ceiling is
// how far the colour bleaches toward white, so an overexposed colour turns white instead of clipping
// to a flat, hue-shifted patch. Bloom is light, so it is gathered and added in linear light first.
namespace tone {

// The shoulder's ceiling (encoded): every value above 1 is compressed to below it.
constexpr float kShoulderCeiling = 2.0f;
// No HDR value the pipeline carries is above this (encoded); anything not a number counts as black.
// A half float holds far more, but a feedback loop must never be able to grow without bound.
constexpr float kHdrCeiling = 64.0f;

// The sRGB transfer, extended above 1 by the same power so the headroom survives the round trip.
float srgbDecode(float encoded);
float srgbEncode(float linear);
// The curve on one encoded value: 0 at 0 (negatives too), the identity up to 1, and above 1
// 1 + (C - 1)(1 - exp(-(x - 1) / (C - 1))) with C = kShoulderCeiling: increasing, below the ceiling.
float toneMap(float encoded);
// How far a compressed value has gone from 1 toward the ceiling, 0..1: its bleach toward white.
float whiteness(float compressed);
struct Rgb {
    float r = 0.0f;
    float g = 0.0f;
    float b = 0.0f;
};
// The display colour of one encoded HDR colour: unchanged while every channel is at most 1;
// otherwise its brightest channel goes through toneMap(), its hue kept, and the colour bleaches
// toward white by whiteness() of that channel. Every channel of the result is within [0, 1].
Rgb toDisplay(Rgb encoded);
// NaN to 0, then clamped to [0, kHdrCeiling].
float sanitize(float value);

// Bloom: how much of a pixel's linear light is gathered, by the brightest channel `brightness`. Below
// threshold - knee nothing, above threshold + knee everything above the threshold, and a quadratic
// knee between so the edge of the glow never bands.
float bloomContribution(float brightness, float threshold, float knee);

// Bloom gathers the light above the display's white. The Bloom parameter (0..kMaxFlashDepth after
// the safety clamp) lowers the threshold toward the bright mid-tones and strengthens the glow.
constexpr float kBloomThreshold = 1.0f;
constexpr float kBloomLowestThreshold = 0.6f;
constexpr float kBloomKnee = 0.5f;
constexpr float kBloomStrength = 0.12f;
constexpr float kBloomParamStrength = 1.6f;

struct BloomSettings {
    bool on = false;
    float threshold = kBloomThreshold;
    float knee = kBloomKnee;
    float strength = 0.0f;
};
// The bloom for a frame: off when the thermal tier has dropped the optional passes.
BloomSettings bloomFor(float bloomParam, bool optionalPasses);

// The mip chain: level 0 at half the frame size, each next level half the one before, rounded up,
// until a level would be smaller than kMinLevelSize on either side or kMaxLevels are made.
constexpr int kMaxLevels = 6;
constexpr int kMinLevelSize = 8;
struct LevelSize {
    int width = 0;
    int height = 0;
};
// Fills `out` and returns how many levels the chain has (0 when the output is too small for one).
int bloomLevels(int width, int height, std::array<LevelSize, kMaxLevels>& out);

}  // namespace tone
}  // namespace geode::viz
