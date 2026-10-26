#include "geode/viz/MultiPass.hpp"

#include <algorithm>
#include <cmath>

namespace geode::viz::multipass {

Spec sanitized(Spec spec) {
    spec.scale = std::isfinite(spec.scale) ? std::clamp(spec.scale, kMinScale, kMaxScale) : kDefaultScale;
    return spec;
}

Size marchSize(int width, int height, float scale) {
    const float s = sanitized(Spec{true, scale, true, true}).scale;
    const auto side = [s](int n) { return std::max(1, static_cast<int>(std::ceil(static_cast<float>(std::max(n, 1)) * s))); };
    return Size{side(width), side(height)};
}

Size coarseSize(Size march) {
    const auto side = [](int n) { return std::max(1, (std::max(n, 1) + kCoarseDivisor - 1) / kCoarseDivisor); };
    return Size{side(march.width), side(march.height)};
}

Plan plan(const Spec& spec, int width, int height, bool halfFloat) {
    Plan out;
    if (!spec.on || !halfFloat || width <= 0 || height <= 0) return out;
    out.passes = true;
    out.coarse = spec.coarse;
    out.temporal = spec.temporal;
    out.march = marchSize(width, height, spec.scale);
    out.coarseSize = coarseSize(out.march);
    return out;
}

float halton(std::uint32_t index, std::uint32_t base) {
    if (base < 2) return 0.0f;
    float f = 1.0f;
    float r = 0.0f;
    while (index > 0) {
        f /= static_cast<float>(base);
        r += f * static_cast<float>(index % base);
        index /= base;
    }
    return r;
}

std::array<float, 2> jitter(std::uint32_t frame) {
    const std::uint32_t index = frame % kJitterCycle + 1;
    return {halton(index, 2) - 0.5f, halton(index, 3) - 0.5f};
}

float marchShare(const Spec& spec) {
    if (!spec.on) return 1.0f;
    const float s = sanitized(spec).scale;
    const float coarse = spec.coarse ? s / static_cast<float>(kCoarseDivisor) : 0.0f;
    return s * s + coarse * coarse;
}

bool History::begin(int width, int height) {
    const bool readable = valid_ && width == width_ && height == height_;
    width_ = width;
    height_ = height;
    return readable;
}

void History::kept() {
    valid_ = true;
    frame_ = (frame_ + 1u) % kFrameWrap;
}

void History::lost() { valid_ = false; }

void History::reset() {
    valid_ = false;
    frame_ = 0;
}

}  // namespace geode::viz::multipass
