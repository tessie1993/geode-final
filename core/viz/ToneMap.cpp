#include "geode/viz/ToneMap.hpp"

#include <algorithm>
#include <cmath>

#include "geode/viz/VisualSafety.hpp"

namespace geode::viz::tone {

namespace {
constexpr float kSrgbDecodeKnee = 0.04045f;
constexpr float kSrgbEncodeKnee = 0.0031308f;
constexpr float kSrgbSlope = 12.92f;
constexpr float kSrgbOffset = 0.055f;
constexpr float kSrgbScale = 1.055f;
constexpr float kSrgbPower = 2.4f;
}  // namespace

float srgbDecode(float encoded) {
    if (encoded <= 0.0f) return 0.0f;
    if (encoded <= kSrgbDecodeKnee) return encoded / kSrgbSlope;
    return std::pow((encoded + kSrgbOffset) / kSrgbScale, kSrgbPower);
}

float srgbEncode(float linear) {
    if (linear <= 0.0f) return 0.0f;
    if (linear <= kSrgbEncodeKnee) return linear * kSrgbSlope;
    return kSrgbScale * std::pow(linear, 1.0f / kSrgbPower) - kSrgbOffset;
}

float toneMap(float encoded) {
    const float x = std::max(encoded, 0.0f);
    if (x <= 1.0f) return x;
    const float span = kShoulderCeiling - 1.0f;
    return 1.0f + span * (1.0f - std::exp(-(x - 1.0f) / span));
}

float whiteness(float compressed) { return std::clamp((compressed - 1.0f) / (kShoulderCeiling - 1.0f), 0.0f, 1.0f); }

Rgb toDisplay(Rgb encoded) {
    const Rgb c{std::max(encoded.r, 0.0f), std::max(encoded.g, 0.0f), std::max(encoded.b, 0.0f)};
    const float peak = std::max(c.r, std::max(c.g, c.b));
    if (peak <= 1.0f) return c;
    // The hue at full brightness, then bleached toward white as far as the peak was compressed.
    const float bleach = whiteness(toneMap(peak));
    const auto out = [&](float channel) { return channel / peak + (1.0f - channel / peak) * bleach; };
    return {out(c.r), out(c.g), out(c.b)};
}

float sanitize(float value) {
    if (std::isnan(value)) return 0.0f;
    return std::clamp(value, 0.0f, kHdrCeiling);
}

float bloomContribution(float brightness, float threshold, float knee) {
    const float b = std::max(brightness, 0.0f);
    if (knee <= 0.0f) return std::max(b - threshold, 0.0f);
    // The quadratic knee meets the straight part (b - threshold) with the same value and slope at
    // threshold + knee, and is 0 with slope 0 at threshold - knee.
    const float soft = std::clamp(b - threshold + knee, 0.0f, 2.0f * knee);
    const float curve = soft * soft / (4.0f * knee);
    return std::max(curve, b - threshold);
}

BloomSettings bloomFor(float bloomParam, bool optionalPasses) {
    BloomSettings s;
    if (!optionalPasses) return s;
    const float param = std::clamp(bloomParam, 0.0f, safety::kMaxFlashDepth);
    const float t = param / safety::kMaxFlashDepth;
    s.on = true;
    s.threshold = kBloomThreshold + (kBloomLowestThreshold - kBloomThreshold) * t;
    s.knee = kBloomKnee;
    s.strength = kBloomStrength + kBloomParamStrength * param;
    return s;
}

int bloomLevels(int width, int height, std::array<LevelSize, kMaxLevels>& out) {
    int w = (std::max(width, 0) + 1) / 2;
    int h = (std::max(height, 0) + 1) / 2;
    int count = 0;
    while (count < kMaxLevels && w >= kMinLevelSize && h >= kMinLevelSize) {
        out[static_cast<size_t>(count)] = {w, h};
        ++count;
        w = (w + 1) / 2;
        h = (h + 1) / 2;
    }
    for (int i = count; i < kMaxLevels; ++i) out[static_cast<size_t>(i)] = {};
    return count;
}

}  // namespace geode::viz::tone
