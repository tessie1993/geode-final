#include "geode/viz/Layers.hpp"

#include <algorithm>
#include <cmath>

#include "geode/viz/VisualSafety.hpp"

namespace geode::viz::layer {

namespace {
constexpr uint32_t kFnvOffset = 2166136261u;
constexpr uint32_t kFnvPrime = 16777619u;
constexpr uint32_t kLow24 = 0xFFFFFFu;
constexpr int kFoldShift = 24;
// A fade this close to its end is at its end, so it finishes on the frame its length says.
constexpr float kFadeEnd = 1e-4f;

// `ordinal` as a whole index into `count`, or -1 when it is not one.
int indexOf(float ordinal, int count) {
    if (!std::isfinite(ordinal)) return -1;
    const long rounded = std::lround(ordinal);
    return rounded >= 0 && rounded < count ? static_cast<int>(rounded) : -1;
}

float unitOf(float value) { return std::isfinite(value) ? std::clamp(value, 0.0f, 1.0f) : 0.0f; }

// `value` moved towards `target` by at most `step`.
float towards(float value, float target, float step) {
    if (target > value) return std::min(value + step, target);
    return std::max(value - step, target);
}

// The request as the mixer takes it: values in range, and nothing but "off" for a Layer 2 with no Style.
Request sanitized(const Request& request) {
    Request out;
    if (request.spec.style != kNoStyle) out.spec = request.spec;
    out.opacity = std::min(unitOf(request.opacity), opacityCap(out.spec.blend));
    out.amount = unitOf(request.amount);
    out.swap = unitOf(request.swap);
    return out;
}
}  // namespace

Blend blendAt(float ordinal) {
    const int at = indexOf(ordinal, kBlendCount);
    return at < 0 ? Blend::Normal : static_cast<Blend>(at);
}

Influence influenceAt(float ordinal) {
    const int at = indexOf(ordinal, kInfluenceCount);
    return at < 0 ? Influence::None : static_cast<Influence>(at);
}

float opacityCap(Blend mode) {
    switch (mode) {
        case Blend::Add:
        case Blend::Difference:
        case Blend::Exclusion:
            return safety::kMaxFlashDepth;
        case Blend::Normal:
        case Blend::Screen:
        case Blend::Multiply:
        case Blend::Overlay:
        case Blend::SoftLight:
        case Blend::HardLight:
        case Blend::Luminosity:
            break;
    }
    return 1.0f;
}

uint32_t styleHash(std::string_view id) {
    uint32_t hash = kFnvOffset;
    for (const char c : id) {
        hash ^= static_cast<uint8_t>(c);
        hash *= kFnvPrime;
    }
    const uint32_t folded = (hash >> kFoldShift) ^ (hash & kLow24);
    return folded == kNoStyle ? 1u : folded;
}

void Mixer::reset() {
    out_ = Mix{};
    primed_ = false;
}

const Mix& Mixer::step(const Request& request, float dt) {
    const Request want = sanitized(request);
    if (!primed_) {
        primed_ = true;
        out_.spec = want.spec;
        out_.presence = want.spec.style == kNoStyle ? 0.0f : 1.0f;
        out_.opacity = want.opacity;
        out_.amount = want.amount;
        out_.swap = want.swap;
        return out_;
    }
    const float t = std::isfinite(dt) && dt > 0.0f ? std::min(dt, kMaxStepSeconds) : 0.0f;
    const float fade = t / kFadeSeconds;
    if (!(out_.spec == want.spec)) {
        // What is shown fades out first; only once it is gone does the new Style or mode come in.
        const float left = out_.presence - fade;
        out_.presence = out_.spec.style == kNoStyle || left <= kFadeEnd ? 0.0f : left;
        if (out_.presence <= 0.0f) out_.spec = want.spec;
    } else {
        const float shown = out_.presence + fade;
        out_.presence = want.spec.style == kNoStyle ? 0.0f : shown >= 1.0f - kFadeEnd ? 1.0f : shown;
    }
    if (out_.presence <= 0.0f) {
        // Nothing of Layer 2 is on screen: it comes back in at what is asked, not from where it was.
        out_.opacity = want.opacity;
        out_.amount = want.amount;
        out_.swap = want.swap;
    } else {
        const float slew = t / kSlewSeconds;
        out_.opacity = towards(out_.opacity, std::min(want.opacity, opacityCap(out_.spec.blend)), slew);
        out_.amount = towards(out_.amount, want.amount, slew);
        out_.swap = towards(out_.swap, want.swap, slew);
    }
    return out_;
}

}  // namespace geode::viz::layer
