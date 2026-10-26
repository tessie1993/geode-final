#pragma once
#include <array>
#include <cstdint>
#include <optional>
#include <string_view>

namespace geode::viz::quality {

// What each thermal tier (ThermalGovernor.hpp) lets a Style spend, beyond the render scale (#286).
// A hot phone used to lose only resolution, while a raymarched Style still spent 64 to 128 steps on
// every pixel it kept. Now each tier also scales the march step budget and switches off the Style's
// costly effects, as its pack manifest declares ("tiers", docs/style-packs.md).

// The tiers, coolest first, in ThermalTier's order; their names are the manifest's keys.
inline constexpr int kTierCount = 4;
inline constexpr std::array<std::string_view, kTierCount> kTierNames = {"full", "eased", "reduced", "minimal"};
// A Style names at most this many effects; effect i is bit i of Budget::effectsOff (uFxOff).
inline constexpr int kMaxEffects = 8;

// The share of the Detail step budget each tier keeps when the Style declares none of its own.
inline constexpr std::array<float, kTierCount> kDefaultStepScale = {1.0f, 0.85f, 0.7f, 0.55f};
// A declared share is held between these: no tier marches more than Detail asks, nor so few steps
// that the surface turns to noise.
inline constexpr float kMinStepScale = 0.25f;
inline constexpr float kMaxStepScale = 1.0f;
// The fewest march steps any tier draws with.
inline constexpr int kMinMarchSteps = 24;

struct Budget {
    float stepScale = 1.0f;
    // Bit i set: the Style's effect i is off at this tier.
    std::uint32_t effectsOff = 0;

    bool effectOn(int index) const { return index < 0 || index >= kMaxEffects || (effectsOff & (1u << index)) == 0; }
    bool operator==(const Budget&) const = default;
};

// What a manifest declares for one tier; nothing declared means the default (or the cooler tier's).
struct Declared {
    std::optional<float> stepScale;
    std::uint32_t effectsOff = 0;
};

using Budgets = std::array<Budget, kTierCount>;

// Every tier's budget, from what the manifest declares: a declared share is clamped, an undeclared
// one is the default, and no tier is ever richer than the one cooler than it (a smaller or equal
// share, and every effect the cooler tier switched off still off). "full" is always the whole budget.
Budgets resolve(const std::array<Declared, kTierCount>& declared);

// The budgets of a Style that declares nothing.
const Budgets& defaults();

// The index of a tier name ("eased"), or nullopt for a name that is not a tier.
std::optional<int> tierNamed(std::string_view name);

// The march steps to draw with: the Detail budget scaled by the tier's share, rounded, never below
// kMinMarchSteps nor above the Detail budget itself.
int marchSteps(int detailSteps, float stepScale);

// The 30 fps step at the hottest tiers, with hysteresis: it engages once the tier has stayed at
// kEngageTier or hotter for kEngageSeconds, and lets go once it has stayed cooler for
// kReleaseSeconds. Halving the frame rate halves the work a second, which resolution alone cannot
// do; the dwell on both sides keeps a tier hovering at the edge from toggling the rate.
class FrameRateStep {
public:
    static constexpr float kCappedFps = 30.0f;
    static constexpr int kEngageTier = 2;  // ThermalTier::Reduced
    static constexpr float kEngageSeconds = 5.0f;
    static constexpr float kReleaseSeconds = 30.0f;

    // Advances by dt with the settled tier's index.
    void step(float dt, int tier);
    // The frame rate to cap at: kCappedFps while engaged, 0 (no cap) otherwise.
    float capFps() const { return engaged_ ? kCappedFps : 0.0f; }
    bool engaged() const { return engaged_; }
    void reset();

private:
    bool engaged_ = false;
    float dwellSeconds_ = 0.0f;
};

}  // namespace geode::viz::quality
