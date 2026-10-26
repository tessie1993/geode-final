#pragma once
#include <array>

namespace geode::viz {

// The maths of the HDR pipeline (HdrPass, tonemap_frag.glsl, bloom_down_frag.glsl,
// bloom_up_frag.glsl), mirrored here so the host tests can hold the shaders to it.
//
// The scene, trail, Shared core effect and composite targets are RGBA16F where the device renders
// them, so a Style's light keeps its headroom above 1 all the way to the end of the frame. One final
// pass then adds a real bloom (a dual-filter mip chain of the light above a soft threshold, in
// linear light), applies a filmic curve, encodes for the display and dithers. The No-flash limiter
// measures and limits what that pass wrote, so the cap holds on the tonemapped HDR picture. A device
// that cannot render RGBA16F keeps the 8-bit frame graph, with no bloom pass and no curve.
//
// Every Style was authored by eye on an unmanaged display, so a Style's colour is a display-encoded
// value and 1 is the display's white. The filmic curve (the fitted ACES RRT+ODT curve) therefore runs
// on the encoded value, where its toe and middle stay close to what each Style was tuned to and its
// shoulder rolls the headroom above 1 off to white instead of clipping it. Bloom is light, so it is
// gathered and added in linear light before that.
namespace tone {

// The fitted ACES curve on an exposed value: x (2.51x + 0.03) / (x (2.43x + 0.59) + 0.14).
constexpr float kAcesA = 2.51f;
constexpr float kAcesB = 0.03f;
constexpr float kAcesC = 2.43f;
constexpr float kAcesD = 0.59f;
constexpr float kAcesE = 0.14f;
// The exposure the curve runs at, and the encoded value it maps to the display's white: anything at
// or above kWhite is white, the middle of the range lands within a few percent of where it was, and
// a Style's own white (1) comes down to about 0.78 so the headroom above it has somewhere to go.
constexpr float kExposure = 0.7f;
constexpr float kWhite = 3.0f;
// No HDR value the pipeline carries is above this (encoded); anything not a number counts as black.
// A half float holds far more, but a feedback loop must never be able to grow without bound.
constexpr float kHdrCeiling = 64.0f;

// The sRGB transfer, extended above 1 by the same power so the headroom survives the round trip.
float srgbDecode(float encoded);
float srgbEncode(float linear);
// The fitted ACES curve, unclamped.
float acesFit(float x);
// The display value of one encoded HDR channel: 0 at 0, 1 at kWhite and above, monotonic between.
float toneMap(float encoded);
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

// The mip chain: level 0 at half the output size, each next level half the one before, rounded up,
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
