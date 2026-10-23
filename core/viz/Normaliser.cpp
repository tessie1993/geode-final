#include "geode/viz/Normaliser.hpp"

#include <algorithm>
#include <cmath>

namespace geode::viz::mod {

namespace {
// ExtraKind's names in :feature:customization:api, in its order, as a manifest writes them.
constexpr std::array<std::string_view, kExtraCount> kExtraNames = {
    "scale", "count",      "size", "thickness", "flight",  "spin",      "orbit",    "swirl",
    "drift", "complexity", "edge", "fog",       "decay",   "viscosity", "growth",   "spread",
    "force", "reach",      "turn", "hold",      "variant", "center_x",  "center_y",
};

bool finite(const NativeRange& range) { return std::isfinite(range.min) && std::isfinite(range.max); }

// The safety clamp on one value: finite, inside its range.
float clamped(const NativeRange& range, float value) {
    const float low = std::min(range.min, range.max);
    const float high = std::max(range.min, range.max);
    return std::isfinite(value) ? std::clamp(value, low, high) : low;
}
}  // namespace

float toNative(const NativeRange& range, float fraction) {
    float x = unit(fraction);
    switch (range.curve) {
        case RangeCurve::Linear:
            break;
        case RangeCurve::Exponential:
            x = x * x;
            break;
        case RangeCurve::Logarithmic:
            x = std::sqrt(x);
            break;
    }
    const float native = range.min + (range.max - range.min) * x;
    return range.stepped ? std::round(native) : native;
}

RangeCurve curveNamed(std::string_view name) {
    if (name == "exponential") return RangeCurve::Exponential;
    if (name == "logarithmic") return RangeCurve::Logarithmic;
    return RangeCurve::Linear;
}

int extraNamed(std::string_view name) {
    for (size_t e = 0; e < kExtraCount; ++e) {
        if (kExtraNames[e] == name) return static_cast<int>(e);
    }
    return -1;
}

FamilyRanges FamilyRanges::sharedCore() {
    FamilyRanges family;
    for (size_t p = 0; p < kSharedCount; ++p) {
        const SharedSpec& s = sharedSpecs()[p];
        family.shared[p] = NativeRange{s.min, s.max, RangeCurve::Linear, s.kind != ValueKind::Continuous};
    }
    return family;
}

bool FamilyRanges::declareExtra(std::string_view name, float min, float max, std::string_view curve, bool stepped) {
    const int at = extraNamed(name);
    const NativeRange range{min, max, curveNamed(curve), stepped};
    if (at < 0 || !finite(range)) return false;
    extras[static_cast<size_t>(at)] = range;
    return true;
}

bool FamilyRanges::declareShared(Shared property, const NativeRange& range) {
    const auto at = static_cast<size_t>(property);
    if (at >= kSharedCount || !finite(range)) return false;
    shared[at] = range;
    return true;
}

NativeFrame toNative(const FamilyRanges& family, const Evaluated& evaluated) {
    NativeFrame frame;
    for (size_t p = 0; p < kSharedCount; ++p) {
        frame.shared[p] = clamped(family.shared[p], toNative(family.shared[p], evaluated.unitShared[p]));
    }
    for (size_t e = 0; e < kExtraCount; ++e) {
        frame.extras[e] = clamped(family.extras[e], toNative(family.extras[e], evaluated.extras[e]));
    }
    return frame;
}

}  // namespace geode::viz::mod
