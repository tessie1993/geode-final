#include "geode/viz/StyleExtras.hpp"

#include <algorithm>
#include <cmath>

namespace geode::viz {

namespace {
using mod::Extra;

// Life: Lenia's centre and Gray-Scott's feed live in a narrow band, and their width and kill rate in a
// narrower one; a full multiplier would kill the colony at once, so the Extras move them part way.
constexpr float kLifeGrowthDepth = 0.25f;
constexpr float kLifeSpreadDepth = 0.15f;
// The looks life_show_frag draws (families.json: life "look" 0..5).
constexpr int kLifeLooks = 6;

// Mycelium: the tops of the family's ranges (families.json), which the agents are tuned within.
constexpr float kMycoMaxSensor = 32.0f;
constexpr float kMycoMaxAngle = 1.5708f;
constexpr float kMycoMaxStep = 2.0f;
constexpr float kMycoMaxDeposit = 0.5f;
constexpr float kMycoMaxDecay = 0.99f;

// Acid: the modes acid_step_frag draws, how far a frame may zoom either way, the feedback the
// family allows and how far VISCOSITY moves the liquid warp.
constexpr int kAcidModes = 10;
constexpr float kAcidZoomSwing = 0.05f;
constexpr float kAcidMinFeedback = 0.8f;
constexpr float kAcidMaxFeedback = 0.99f;
constexpr float kAcidLiquidSpan = 0.5f;

// A persistence Extra this small would make the loss unbounded.
constexpr float kPersistFloor = 0.05f;
}  // namespace

ExtraValues ExtraValues::neutral() {
    ExtraValues values;
    for (size_t e = 0; e < mod::kExtraCount; ++e) values.value[e] = neutralOf(static_cast<Extra>(e));
    return values;
}

float ExtraValues::neutralOf(Extra extra) {
    switch (extra) {
        case Extra::Variant:
        case Extra::CenterX:
        case Extra::CenterY:
            return 0.0f;
        default:
            return 1.0f;
    }
}

float ExtraValues::multiplier(Extra extra, float floor) const {
    const float v = (*this)[extra];
    return std::isfinite(v) ? std::max(v, floor) : 1.0f;
}

namespace extras {

void declare(mod::FamilyRanges& ranges, const packs::StylePack& pack) {
    for (const packs::ExtraSpec& extra : pack.extras) {
        const bool stepped = mod::extraNamed(extra.name) == static_cast<int>(Extra::Variant);
        ranges.declareExtra(extra.name, extra.min, extra.max, "linear", stepped);
    }
}

ExtraValues of(const packs::StylePack* pack, const mod::NativeFrame* frame) {
    ExtraValues values = ExtraValues::neutral();
    if (pack == nullptr) return values;
    for (const packs::ExtraSpec& extra : pack->extras) {
        const int at = mod::extraNamed(extra.name);
        if (at < 0) continue;
        const auto e = static_cast<size_t>(at);
        values.declared[e] = true;
        values.value[e] = frame != nullptr ? frame->extras[e] : extra.defaultValue;
    }
    return values;
}

float scaled(float base, float extra, float depth) { return base * std::max(1.0f + depth * (extra - 1.0f), 0.0f); }

float nudged(float base, float extra, float span, float lo, float hi) {
    return std::clamp(base + span * (extra - 1.0f), lo, hi);
}

float persisting(float retention, float extra) {
    return std::clamp(1.0f - (1.0f - retention) / std::max(extra, kPersistFloor), 0.0f, 1.0f);
}

float damped(float retention, float extra) {
    return std::clamp(1.0f - (1.0f - retention) * std::max(extra, 0.0f), 0.0f, 1.0f);
}

float dissipating(float rate, float extra) { return std::max(rate, 0.0f) / std::max(extra, kPersistFloor); }

int variant(int base, float extra, int choices) {
    const int n = std::max(choices, 1);
    const int shifted = (base + static_cast<int>(std::lround(extra))) % n;
    return shifted < 0 ? shifted + n : shifted;
}

int count(int base, float extra, int most) {
    return std::clamp(static_cast<int>(std::lround(static_cast<float>(base) * std::max(extra, 0.0f))), 1, std::max(most, 1));
}

styles::LifeStyle apply(styles::LifeStyle style, const ExtraValues& x) {
    style.look = variant(style.look, x[Extra::Variant], kLifeLooks);
    if (style.rule == 0) {
        style.mu = scaled(style.mu, x[Extra::Growth], kLifeGrowthDepth);
        style.sigma = scaled(style.sigma, x[Extra::Spread], kLifeSpreadDepth);
    } else {
        style.feed = scaled(style.feed, x[Extra::Growth], kLifeGrowthDepth);
        style.kill = scaled(style.kill, x[Extra::Spread], kLifeSpreadDepth);
    }
    return style;
}

styles::MycoStyle apply(styles::MycoStyle style, const ExtraValues& x) {
    style.sensorDist = std::min(scaled(style.sensorDist, x[Extra::Reach]), kMycoMaxSensor);
    style.turnAngle = std::min(scaled(style.turnAngle, x[Extra::Turn]), kMycoMaxAngle);
    style.moveStep = std::min(scaled(style.moveStep, x[Extra::Drift]), kMycoMaxStep);
    style.decay = std::min(persisting(style.decay, x[Extra::Decay]), kMycoMaxDecay);
    style.deposit = std::min(scaled(style.deposit, x[Extra::Force]), kMycoMaxDeposit);
    return style;
}

styles::AcidStyle apply(styles::AcidStyle style, const ExtraValues& x) {
    style.mode = variant(style.mode, x[Extra::Variant], kAcidModes);
    style.zoom = 1.0f + std::clamp(scaled(style.zoom - 1.0f, x[Extra::Flight]), -kAcidZoomSwing, kAcidZoomSwing);
    style.rotate = scaled(style.rotate, x[Extra::Spin]);
    style.feedback = std::clamp(persisting(style.feedback, x[Extra::Decay]), kAcidMinFeedback, kAcidMaxFeedback);
    style.hueRate = scaled(style.hueRate, x[Extra::Drift]);
    style.liquid = nudged(style.liquid, x[Extra::Viscosity], kAcidLiquidSpan, 0.0f, 1.0f);
    style.modulate = std::min(scaled(style.modulate, x[Extra::Swirl]), 1.0f);
    return style;
}

styles::FluidStyle apply(styles::FluidStyle style, const ExtraValues& x) {
    style.curl = scaled(style.curl, x[Extra::Swirl]);
    style.velocityDissipation = scaled(style.velocityDissipation, x[Extra::Viscosity]);
    style.densityDissipation = dissipating(style.densityDissipation, x[Extra::Decay]);
    style.splatRadius = scaled(style.splatRadius, x[Extra::Size]);
    style.splatForce = scaled(style.splatForce, x[Extra::Force]);
    return style;
}

styles::CymaticsStyle apply(styles::CymaticsStyle style, const ExtraValues& x) {
    style.scale = scaled(style.scale, x[Extra::Scale]);
    style.line = scaled(style.line, x[Extra::Thickness]);
    style.fill = scaled(style.fill, x[Extra::Size]);
    style.swirl = scaled(style.swirl, x[Extra::Swirl]);
    style.flow = scaled(style.flow, x[Extra::Drift]);
    return style;
}

}  // namespace extras

}  // namespace geode::viz
