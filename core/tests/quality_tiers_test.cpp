#include "geode/viz/QualityTiers.hpp"

#include <gtest/gtest.h>

#include <cmath>
#include <fstream>
#include <limits>
#include <sstream>
#include <string>
#include <vector>

#include "geode/viz/StylePacks.hpp"
#include "geode/viz/ThermalGovernor.hpp"

// Quality tiers cut work, not only resolution (#286): what each thermal tier lets a Style spend, how a
// pack declares it, and the 30 fps step at the hottest tiers.

namespace geode::viz {
namespace {

constexpr int kFull = 0;
constexpr int kEased = 1;
constexpr int kReduced = 2;
constexpr int kMinimal = 3;
constexpr float kFrame = 1.0f / 60.0f;

quality::Declared steps(float share, std::uint32_t off = 0) {
    quality::Declared d;
    d.stepScale = share;
    d.effectsOff = off;
    return d;
}

void run(quality::FrameRateStep& step, float seconds, int tier) {
    const int frames = static_cast<int>(std::lround(seconds / kFrame));
    for (int i = 0; i < frames; ++i) step.step(kFrame, tier);
}

std::optional<packs::StylePack> parse(const std::string& extra, std::vector<packs::PackProblem>& problems) {
    std::vector<packs::PackProblem> familyProblems;
    const auto families = packs::parseFamilies(R"({"version": 1, "families": [{"name": "shader", "parameters": []}]})", familyProblems);
    EXPECT_TRUE(families.has_value());
    const std::string text = R"({"version": 1, "id": "orb", "label": "Orb", "family": "shader")" + extra + "}";
    return packs::parseManifest("orb", text, families.value_or(std::vector<packs::FamilySchema>{}), problems);
}

void expectRejected(const std::string& extra) {
    std::vector<packs::PackProblem> problems;
    EXPECT_FALSE(parse(extra, problems).has_value()) << extra;
    ASSERT_EQ(problems.size(), 1u) << extra;
    EXPECT_EQ(problems.front().error, packs::PackError::BadValue) << extra;
}

std::optional<std::string> readShipped(const std::string& path) {
    std::ifstream in(std::string(GEODE_STYLE_PACK_ROOT) + "/" + path, std::ios::binary);
    if (!in) return std::nullopt;
    std::ostringstream text;
    text << in.rdbuf();
    return text.str();
}

}  // namespace

// ---- budgets -------------------------------------------------------------------------------------

TEST(QualityTiers, TheNamesFollowTheThermalTiers) {
    EXPECT_EQ(quality::kTierNames[static_cast<size_t>(ThermalTier::Full)], "full");
    EXPECT_EQ(quality::kTierNames[static_cast<size_t>(ThermalTier::Eased)], "eased");
    EXPECT_EQ(quality::kTierNames[static_cast<size_t>(ThermalTier::Reduced)], "reduced");
    EXPECT_EQ(quality::kTierNames[static_cast<size_t>(ThermalTier::Minimal)], "minimal");
    EXPECT_EQ(quality::tierNamed("reduced").value_or(-1), kReduced);
    EXPECT_EQ(quality::tierNamed("full").value_or(-1), kFull);
    EXPECT_EQ(quality::tierNamed("minimal").value_or(-1), kMinimal);
    EXPECT_FALSE(quality::tierNamed("scorching").has_value());
}

TEST(QualityTiers, ByDefaultEachHotterTierMarchesFewerSteps) {
    const quality::Budgets& b = quality::defaults();
    EXPECT_FLOAT_EQ(b[kFull].stepScale, 1.0f);
    EXPECT_FLOAT_EQ(b[kEased].stepScale, 0.85f);
    EXPECT_FLOAT_EQ(b[kReduced].stepScale, 0.7f);
    EXPECT_FLOAT_EQ(b[kMinimal].stepScale, 0.55f);
    for (const quality::Budget& tier : b) EXPECT_EQ(tier.effectsOff, 0u);
}

TEST(QualityTiers, ADeclaredShareIsClampedAndTheFullTierIsAlwaysWhole) {
    std::array<quality::Declared, quality::kTierCount> declared{};
    declared[kFull] = steps(0.3f, 0b1);
    declared[kEased] = steps(1.5f);
    declared[kReduced] = steps(0.1f);
    const quality::Budgets b = quality::resolve(declared);
    EXPECT_EQ(b[kFull], quality::Budget{});
    EXPECT_FLOAT_EQ(b[kEased].stepScale, quality::kMaxStepScale);
    EXPECT_FLOAT_EQ(b[kReduced].stepScale, quality::kMinStepScale);
    // Minimal declares nothing: its default share, but never more than the cooler tier.
    EXPECT_FLOAT_EQ(b[kMinimal].stepScale, quality::kMinStepScale);
}

TEST(QualityTiers, ABrokenShareFallsBackToTheDefault) {
    std::array<quality::Declared, quality::kTierCount> declared{};
    declared[kEased] = steps(std::numeric_limits<float>::quiet_NaN());
    EXPECT_FLOAT_EQ(quality::resolve(declared)[kEased].stepScale, quality::kDefaultStepScale[kEased]);
}

TEST(QualityTiers, AHotterTierIsNeverRicherThanACoolerOne) {
    std::array<quality::Declared, quality::kTierCount> declared{};
    declared[kEased] = steps(0.6f, 0b01);
    declared[kReduced] = steps(0.9f, 0b10);
    const quality::Budgets b = quality::resolve(declared);
    EXPECT_FLOAT_EQ(b[kEased].stepScale, 0.6f);
    EXPECT_FLOAT_EQ(b[kReduced].stepScale, 0.6f);
    EXPECT_FLOAT_EQ(b[kMinimal].stepScale, 0.55f);
    EXPECT_EQ(b[kEased].effectsOff, 0b01u);
    EXPECT_EQ(b[kReduced].effectsOff, 0b11u);
    EXPECT_EQ(b[kMinimal].effectsOff, 0b11u);
}

TEST(QualityTiers, AnEffectIsOnUnlessItsBitIsSet) {
    quality::Budget b;
    b.effectsOff = 0b100;
    EXPECT_TRUE(b.effectOn(0));
    EXPECT_TRUE(b.effectOn(1));
    EXPECT_FALSE(b.effectOn(2));
    EXPECT_TRUE(b.effectOn(-1));
    EXPECT_TRUE(b.effectOn(quality::kMaxEffects));
}

TEST(QualityTiers, MarchStepsScaleTheDetailBudgetWithinItsBounds) {
    EXPECT_EQ(quality::marchSteps(128, 1.0f), 128);
    EXPECT_EQ(quality::marchSteps(128, 0.5f), 64);
    EXPECT_EQ(quality::marchSteps(96, 0.7f), 67);
    // Never fewer than the floor, never more than Detail asked for.
    EXPECT_EQ(quality::marchSteps(64, 0.25f), quality::kMinMarchSteps);
    EXPECT_EQ(quality::marchSteps(64, 2.0f), 64);
    EXPECT_EQ(quality::marchSteps(16, 0.5f), 16);
    EXPECT_EQ(quality::marchSteps(64, std::numeric_limits<float>::quiet_NaN()), 64);
    EXPECT_EQ(quality::marchSteps(64, -1.0f), quality::kMinMarchSteps);
}

TEST(QualityTiers, AtTheHottestTierARaymarchDoesUnderAFifthOfItsWork) {
    // Per second: pixels (the tier's render scale, squared) x steps x frames (the 30 fps step halves
    // them). The estimate behind "hot tiers hold their frame time" on a mid-range phone.
    const float pixels = std::pow(thermalTierInfo(ThermalTier::Minimal).renderScale, 2.0f);
    const float stepsShare = static_cast<float>(quality::marchSteps(128, quality::defaults()[kMinimal].stepScale)) / 128.0f;
    const float frames = quality::FrameRateStep::kCappedFps / 60.0f;
    EXPECT_LT(pixels * stepsShare * frames, 0.2f);
    // Without the frame step the per-frame cost alone is under a quarter of a cool phone's.
    EXPECT_LT(pixels * stepsShare, 0.25f);
}

// ---- manifests -----------------------------------------------------------------------------------

TEST(QualityTiers, AManifestDeclaresEffectsAndTiers) {
    std::vector<packs::PackProblem> problems;
    const auto pack = parse(R"(, "effects": ["motes", "halo"],
        "tiers": {"eased": {"steps": 0.9}, "reduced": {"steps": 0.6, "off": ["halo"]}, "minimal": {"off": ["motes"]}})", problems);
    ASSERT_TRUE(pack.has_value()) << (problems.empty() ? "" : problems.front().detail);
    EXPECT_TRUE(problems.empty());
    ASSERT_EQ(pack->effects.size(), 2u);
    EXPECT_EQ(pack->effects[0], "motes");
    EXPECT_EQ(pack->effects[1], "halo");
    EXPECT_EQ(pack->tiers[kFull], quality::Budget{});
    EXPECT_FLOAT_EQ(pack->tiers[kEased].stepScale, 0.9f);
    EXPECT_EQ(pack->tiers[kEased].effectsOff, 0u);
    EXPECT_FLOAT_EQ(pack->tiers[kReduced].stepScale, 0.6f);
    EXPECT_EQ(pack->tiers[kReduced].effectsOff, 0b10u);
    EXPECT_FLOAT_EQ(pack->tiers[kMinimal].stepScale, 0.55f);
    EXPECT_EQ(pack->tiers[kMinimal].effectsOff, 0b11u);
}

TEST(QualityTiers, AManifestWithoutTiersGetsTheDefaults) {
    std::vector<packs::PackProblem> problems;
    const auto pack = parse("", problems);
    ASSERT_TRUE(pack.has_value());
    EXPECT_TRUE(pack->effects.empty());
    EXPECT_EQ(pack->tiers, quality::defaults());
}

TEST(QualityTiers, BadEffectsAndTiersAreRejected) {
    expectRejected(R"(, "effects": "motes")");
    expectRejected(R"(, "effects": ["a", "b", "c", "d", "e", "f", "g", "h", "i"])");
    expectRejected(R"(, "effects": ["motes", ""])");
    expectRejected(R"(, "effects": ["motes", 3])");
    expectRejected(R"(, "effects": ["motes", "motes"])");
    expectRejected(R"(, "tiers": [])");
    expectRejected(R"(, "tiers": {"full": {"steps": 1}})");
    expectRejected(R"(, "tiers": {"scorching": {}})");
    expectRejected(R"(, "tiers": {"eased": 0.5})");
    expectRejected(R"(, "tiers": {"eased": {"steps": "half"}})");
    expectRejected(R"(, "tiers": {"eased": {"fps": 30}})");
    expectRejected(R"(, "tiers": {"eased": {"off": "motes"}})");
    expectRejected(R"(, "effects": ["motes"], "tiers": {"eased": {"off": ["halo"]}})");
    expectRejected(R"(, "effects": ["motes"], "tiers": {"eased": {"off": [0]}})");
}

TEST(QualityTiers, EightEffectsAreAllowed) {
    std::vector<packs::PackProblem> problems;
    const auto pack = parse(R"(, "effects": ["a", "b", "c", "d", "e", "f", "g", "h"], "tiers": {"minimal": {"off": ["h"]}})", problems);
    ASSERT_TRUE(pack.has_value());
    EXPECT_EQ(pack->tiers[kMinimal].effectsOff, 0b10000000u);
}

TEST(QualityTiers, RodTunnelShipsItsBudgets) {
    const auto registry = packs::StylePackRegistry::load(readShipped);
    const packs::StylePack* rod = registry.find("rod_tunnel");
    ASSERT_NE(rod, nullptr);
    ASSERT_EQ(rod->effects.size(), 1u);
    EXPECT_EQ(rod->effects[0], "motes");
    EXPECT_TRUE(rod->tiers[kEased].effectOn(0));
    EXPECT_FALSE(rod->tiers[kReduced].effectOn(0));
    EXPECT_FLOAT_EQ(rod->tiers[kMinimal].stepScale, 0.5f);
}

// ---- the 30 fps step -----------------------------------------------------------------------------

TEST(FrameRateStep, StartsUncapped) {
    quality::FrameRateStep step;
    EXPECT_FALSE(step.engaged());
    EXPECT_EQ(step.capFps(), 0.0f);
}

TEST(FrameRateStep, EngagesOnlyOnceAHotTierHasHeld) {
    quality::FrameRateStep step;
    run(step, 4.0f, kReduced);
    EXPECT_FALSE(step.engaged());
    run(step, 1.5f, kReduced);
    EXPECT_TRUE(step.engaged());
    EXPECT_EQ(step.capFps(), 30.0f);
}

TEST(FrameRateStep, TheMildTiersNeverEngageIt) {
    quality::FrameRateStep step;
    run(step, 120.0f, kEased);
    EXPECT_FALSE(step.engaged());
    run(step, 10.0f, kMinimal);
    EXPECT_TRUE(step.engaged());
}

TEST(FrameRateStep, ABriefHotSpellDoesNotEngageIt) {
    quality::FrameRateStep step;
    for (int i = 0; i < 10; ++i) {
        run(step, 3.0f, kMinimal);
        run(step, 1.0f, kEased);
    }
    EXPECT_FALSE(step.engaged());
}

TEST(FrameRateStep, ReleasesOnlyOnceTheCoolerTierHasHeld) {
    quality::FrameRateStep step;
    run(step, 6.0f, kMinimal);
    ASSERT_TRUE(step.engaged());
    run(step, 29.0f, kEased);
    EXPECT_TRUE(step.engaged());
    run(step, 1.5f, kEased);
    EXPECT_FALSE(step.engaged());
    EXPECT_EQ(step.capFps(), 0.0f);
}

TEST(FrameRateStep, AHotMomentWhileCoolingRestartsTheRelease) {
    quality::FrameRateStep step;
    run(step, 6.0f, kReduced);
    ASSERT_TRUE(step.engaged());
    run(step, 20.0f, kFull);
    run(step, 0.5f, kReduced);
    run(step, 20.0f, kFull);
    EXPECT_TRUE(step.engaged());
    run(step, 11.0f, kFull);
    EXPECT_FALSE(step.engaged());
}

TEST(FrameRateStep, ABrokenFrameMovesNothingAndResetLetsGo) {
    quality::FrameRateStep step;
    run(step, 4.9f, kMinimal);
    step.step(std::numeric_limits<float>::quiet_NaN(), kMinimal);
    step.step(-1.0f, kMinimal);
    step.step(0.0f, kMinimal);
    EXPECT_FALSE(step.engaged());
    run(step, 0.2f, kMinimal);
    ASSERT_TRUE(step.engaged());
    step.reset();
    EXPECT_FALSE(step.engaged());
    run(step, 4.0f, kMinimal);
    EXPECT_FALSE(step.engaged());
}

TEST(FrameRateStep, TheGovernorCapsTheFrameRateAtItsHottestTiers) {
    ThermalGovernor governor;
    EXPECT_EQ(governor.frameCapFps(), 0.0f);
    governor.setPlatformStatus(3);
    const int frames = static_cast<int>(std::lround(10.0f / kFrame));
    for (int i = 0; i < frames; ++i) governor.onFrame(kFrame);
    ASSERT_EQ(governor.tier(), ThermalTier::Minimal);
    EXPECT_EQ(governor.frameCapFps(), quality::FrameRateStep::kCappedFps);
}

TEST(FrameRateStep, ARenderIsNeverCapped) {
    ThermalGovernor governor;
    governor.setPlatformStatus(3);
    governor.beginOffscreenRender();
    const int frames = static_cast<int>(std::lround(10.0f / kFrame));
    for (int i = 0; i < frames; ++i) governor.onFrame(kFrame);
    EXPECT_EQ(governor.frameCapFps(), 0.0f);
    governor.endOffscreenRender();
}

}  // namespace geode::viz
