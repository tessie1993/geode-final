#include "geode/viz/BufferPasses.hpp"

#include <algorithm>
#include <cmath>

namespace geode::viz::bufferpass {

Spec sanitized(Spec spec) {
    spec.count = std::clamp(spec.count, 0, kMaxBuffers);
    for (float& scale : spec.scale) scale = std::isfinite(scale) ? std::clamp(scale, kMinScale, kMaxScale) : kDefaultScale;
    return spec;
}

Size bufferSize(int width, int height, float scale) {
    const float s = std::isfinite(scale) ? std::clamp(scale, kMinScale, kMaxScale) : kDefaultScale;
    const auto side = [s](int n) { return std::max(1, static_cast<int>(std::ceil(static_cast<float>(std::max(n, 1)) * s))); };
    return Size{side(width), side(height)};
}

int unitOf(int index, int firstUnit) { return firstUnit + index; }

bool fits(int count, int firstUnit, int units) { return unitOf(count, firstUnit) <= units; }

bool readsThisFrame(int reader, int buffer) { return buffer < reader; }

Chain::Chain(int count) : count_(std::clamp(count, 0, kMaxBuffers)) {}

int Chain::latest(int index) const { return valid(index) ? latest_[static_cast<size_t>(index)] : 0; }

void Chain::drawn(int index) {
    if (!valid(index)) return;
    latest_[static_cast<size_t>(index)] = 1 - latest_[static_cast<size_t>(index)];
}

void Chain::endFrame() { frame_ = (frame_ + 1u) % kFrameWrap; }

void Chain::reset() {
    latest_.fill(0);
    frame_ = 0;
}

}  // namespace geode::viz::bufferpass
