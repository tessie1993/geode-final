#include "geode/viz/scenes/CymaticsMath.hpp"

#include <gtest/gtest.h>

#include <array>
#include <cmath>
#include <limits>
#include <vector>

namespace cym = geode::viz::cymatics;

namespace {

constexpr float kTol = 1e-4f;
constexpr float kFundamental = 110.0f;
// A step long enough for the attack (35 ms) to land on its target.
constexpr float kSettle = 1.0f;
constexpr int kQuad = 4;
constexpr int kModeCount = cym::kMaxOrder * (cym::kMaxOrder + 1) / 2;

std::array<float, cym::kMaxRenderedModes * kQuad> snapshotBuffer() {
    std::array<float, cym::kMaxRenderedModes * kQuad> out{};
    out.fill(-1.0f);
    return out;
}

}  // namespace

TEST(CymaticsMath, ModesAreEveryOrderSortedByWavenumber) {
    const auto& modes = cym::modes();
    ASSERT_EQ(static_cast<int>(modes.size()), kModeCount);
    EXPECT_EQ(modes[0].n, 1);
    EXPECT_EQ(modes[0].m, 0);
    EXPECT_NEAR(modes[0].wavenumber, 1.0f, kTol);
    EXPECT_EQ(modes[1].n, 2);
    EXPECT_EQ(modes[1].m, 0);
    EXPECT_EQ(modes[2].n, 2);
    EXPECT_EQ(modes[2].m, 1);
    EXPECT_NEAR(modes[2].wavenumber, std::sqrt(5.0f), kTol);
    for (size_t i = 1; i < modes.size(); ++i) {
        EXPECT_LE(modes[i - 1].wavenumber, modes[i].wavenumber) << i;
        EXPECT_LT(modes[i].m, modes[i].n) << i;
    }
    EXPECT_EQ(modes.back().n, cym::kMaxOrder);
}

TEST(CymaticsMath, BandCentersAreGeometric) {
    EXPECT_NEAR(cym::bandCenterHz(0, 0), cym::kMinBandHz, kTol);
    EXPECT_NEAR(cym::bandCenterHz(0, 1), 692.82032f, 0.01f);
    EXPECT_NEAR(cym::bandCenterHz(0, 2), 144.16868f, 0.01f);
    EXPECT_NEAR(cym::bandCenterHz(1, 2), 3329.4332f, 0.1f);
}

TEST(CymaticsMath, WavenumberForClampsTheFundamental) {
    EXPECT_NEAR(cym::wavenumberFor(440.0f, kFundamental), 2.0f, kTol);
    EXPECT_NEAR(cym::wavenumberFor(440.0f, 10.0f), 3.3166248f, kTol);
    EXPECT_NEAR(cym::wavenumberFor(440.0f, 1000.0f), 1.0f, kTol);
    EXPECT_NEAR(cym::wavenumberFor(-10.0f, kFundamental), 0.0f, kTol);
}

TEST(CymaticsMath, ModeIndexForPicksTheNearestAndTheLastOfATie) {
    const auto& modes = cym::modes();
    EXPECT_EQ(cym::modeIndexFor(1.0f), 0);
    EXPECT_EQ(cym::modeIndexFor(0.0f), 0);
    EXPECT_EQ(cym::modeIndexFor(2.0f), 1);
    EXPECT_EQ(cym::modeIndexFor(2.2f), 2);
    EXPECT_EQ(cym::modeIndexFor(1000.0f), kModeCount - 1);
    // (4,3) and (5,0) both have wavenumber 5; the later one wins.
    const auto& five = modes[static_cast<size_t>(cym::modeIndexFor(5.0f))];
    EXPECT_EQ(five.n, 5);
    EXPECT_EQ(five.m, 0);
}

TEST(CymaticsMath, BandModeMap) {
    EXPECT_TRUE(cym::bandModeMap(0, kFundamental).empty());
    EXPECT_TRUE(cym::bandModeMap(-1, kFundamental).empty());
    const auto map = cym::bandModeMap(2, kFundamental);
    ASSERT_EQ(map.size(), 2u);
    EXPECT_EQ(map[0], 0);
    const auto& high = cym::modes()[static_cast<size_t>(map[1])];
    EXPECT_EQ(high.n, 5);
    EXPECT_EQ(high.m, 2);
    const auto wide = cym::bandModeMap(16, kFundamental);
    for (size_t i = 1; i < wide.size(); ++i) EXPECT_LE(wide[i - 1], wide[i]) << i;
}

TEST(CymaticsMath, ScalarHelpers) {
    EXPECT_NEAR(cym::ringSeconds(0.0f), cym::kMinRingSeconds, kTol);
    EXPECT_NEAR(cym::ringSeconds(1.0f), cym::kMaxRingSeconds, kTol);
    EXPECT_NEAR(cym::ringSeconds(0.5f), 1.28f, kTol);
    EXPECT_NEAR(cym::ringSeconds(2.0f), cym::kMaxRingSeconds, kTol);
    EXPECT_NEAR(cym::ringSeconds(-1.0f), cym::kMinRingSeconds, kTol);

    EXPECT_NEAR(cym::smoothing(0.1f, 0.0f), 1.0f, kTol);
    EXPECT_NEAR(cym::smoothing(0.1f, 0.1f), 0.63212056f, kTol);
    EXPECT_NEAR(cym::smoothing(0.0f, 0.1f), 0.0f, kTol);
    EXPECT_NEAR(cym::smoothing(-1.0f, 0.1f), 0.0f, kTol);

    EXPECT_NEAR(cym::vibrationHz(1.0f), 0.14f, kTol);
    EXPECT_NEAR(cym::vibrationHz(5.0f), 0.7f, kTol);
    EXPECT_NEAR(cym::vibrationHz(0.5f), cym::kMinVibrationHz, kTol);
    EXPECT_NEAR(cym::vibrationHz(20.0f), cym::kMaxVibrationHz, kTol);

    EXPECT_EQ(cym::safeDrive(std::numeric_limits<float>::quiet_NaN()), 0.0f);
    EXPECT_EQ(cym::safeDrive(std::numeric_limits<float>::infinity()), 0.0f);
    EXPECT_NEAR(cym::safeDrive(5.0f), cym::kMaxDrive, kTol);
    EXPECT_NEAR(cym::safeDrive(-1.0f), 0.0f, kTol);
    EXPECT_NEAR(cym::safeDrive(2.0f), 2.0f, kTol);

    EXPECT_NEAR(cym::fieldLiveness(0.01f), 0.5f, kTol);
    EXPECT_NEAR(cym::fieldLiveness(1.0f), 1.0f, kTol);
    EXPECT_NEAR(cym::fieldLiveness(-1.0f), 0.0f, kTol);
    EXPECT_EQ(cym::fieldLiveness(std::numeric_limits<float>::quiet_NaN()), 0.0f);
}

TEST(CymaticsMath, WrapPhase) {
    EXPECT_NEAR(cym::wrapPhase(7.0f, 5.0f), 2.0f, kTol);
    EXPECT_NEAR(cym::wrapPhase(-1.0f, 5.0f), 4.0f, kTol);
    EXPECT_NEAR(cym::wrapPhase(5.0f, 5.0f), 0.0f, kTol);
    EXPECT_NEAR(cym::wrapPhase(3.0f, 0.0f), 0.0f, kTol);
    EXPECT_NEAR(cym::wrapPhase(3.0f, -1.0f), 0.0f, kTol);
    EXPECT_EQ(cym::wrapPhase(std::numeric_limits<float>::quiet_NaN(), 5.0f), 0.0f);
}

TEST(CymaticsMath, ApproachHueTakesTheShortWayRound) {
    EXPECT_NEAR(cym::approachHue(0.2f, 0.4f, 0.5f), 0.3f, kTol);
    EXPECT_NEAR(cym::approachHue(0.7f, 0.1f, 0.5f), 0.9f, kTol);
    EXPECT_NEAR(cym::approachHue(0.1f, 0.7f, 0.5f), 0.9f, kTol);
    EXPECT_NEAR(cym::approachHue(0.2f, 0.4f, 2.0f), 0.4f, kTol);
    EXPECT_NEAR(cym::approachHue(0.2f, 0.4f, -1.0f), 0.2f, kTol);
}

TEST(CymaticsPlate, SilenceRendersNothing) {
    cym::Plate plate;
    const std::array<float, 4> bands{};
    plate.excite(bands.data(), static_cast<int>(bands.size()), kSettle, kFundamental, 1.0f, 0.5f, 0.0f);
    auto out = snapshotBuffer();
    EXPECT_EQ(plate.snapshot(cym::kMaxRenderedModes, out.data(), static_cast<int>(out.size())), 0);
    EXPECT_EQ(plate.dominantWavenumber(), 0.0f);
}

TEST(CymaticsPlate, OneBandExcitesItsMode) {
    cym::Plate plate;
    const float bands[] = {1.0f};
    plate.excite(bands, 1, kSettle, kFundamental, 1.0f, 0.5f, 0.0f);
    EXPECT_NEAR(plate.dominantWavenumber(), std::sqrt(5.0f), kTol);
    auto out = snapshotBuffer();
    ASSERT_EQ(plate.snapshot(cym::kMaxRenderedModes, out.data(), static_cast<int>(out.size())), 1);
    EXPECT_EQ(out[0], 2.0f);
    EXPECT_EQ(out[1], 1.0f);
    EXPECT_NEAR(out[2], 1.0f, kTol);
    EXPECT_NEAR(out[3], 0.0f, kTol);
    EXPECT_EQ(out[4], -1.0f);

    plate.advancePhases(1.0f, 1.0f);
    ASSERT_EQ(plate.snapshot(1, out.data(), static_cast<int>(out.size())), 1);
    EXPECT_NEAR(out[3], 1.9669481f, kTol);

    // Released over one ring time: e^-1 of the level is left.
    const float silent[] = {0.0f};
    plate.excite(silent, 1, 0.5f, kFundamental, 1.0f, 0.5f, 0.0f);
    ASSERT_EQ(plate.snapshot(1, out.data(), static_cast<int>(out.size())), 1);
    EXPECT_NEAR(out[2], 0.36787944f, kTol);
}

TEST(CymaticsPlate, DriveScalesTheExcitation) {
    cym::Plate plate;
    const float bands[] = {1.0f};
    plate.excite(bands, 1, kSettle, kFundamental, 0.5f, 0.5f, 0.0f);
    auto out = snapshotBuffer();
    ASSERT_EQ(plate.snapshot(1, out.data(), static_cast<int>(out.size())), 1);
    // A total under 1 is not normalised up.
    EXPECT_NEAR(out[2], 0.5f, kTol);
}

TEST(CymaticsPlate, FullFocusHearsOnlyPeaksAboveTheLocalMean) {
    cym::Plate plate;
    const float flat[] = {1.0f};
    plate.excite(flat, 1, kSettle, kFundamental, 1.0f, 0.5f, 1.0f);
    auto out = snapshotBuffer();
    EXPECT_EQ(plate.snapshot(cym::kMaxRenderedModes, out.data(), static_cast<int>(out.size())), 0);

    // Two bands: the louder one stands 0.25 above their mean, whitened by 2.6.
    cym::Plate peaked;
    const float bands[] = {0.5f, 1.0f};
    peaked.excite(bands, 2, kSettle, kFundamental, 1.0f, 0.5f, 1.0f);
    ASSERT_EQ(peaked.snapshot(cym::kMaxRenderedModes, out.data(), static_cast<int>(out.size())), 1);
    EXPECT_EQ(out[0], 5.0f);
    EXPECT_EQ(out[1], 2.0f);
    EXPECT_NEAR(out[2], 0.65f, kTol);
    EXPECT_NEAR(peaked.dominantWavenumber(), std::sqrt(29.0f), kTol);
}

TEST(CymaticsPlate, SnapshotOrdersByAmplitudeAndNormalises) {
    cym::Plate plate;
    const float bands[] = {0.5f, 1.0f};
    plate.excite(bands, 2, kSettle, kFundamental, 1.0f, 0.5f, 0.0f);
    EXPECT_NEAR(plate.dominantWavenumber(), std::sqrt(29.0f), kTol);
    auto out = snapshotBuffer();
    ASSERT_EQ(plate.snapshot(cym::kMaxRenderedModes, out.data(), static_cast<int>(out.size())), 2);
    EXPECT_EQ(out[0], 5.0f);
    EXPECT_EQ(out[1], 2.0f);
    EXPECT_NEAR(out[2], 1.0f / 1.5f, kTol);
    EXPECT_EQ(out[4], 1.0f);
    EXPECT_EQ(out[5], 0.0f);
    EXPECT_NEAR(out[6], 0.5f / 1.5f, kTol);
    EXPECT_EQ(out[8], -1.0f);

    // The limit, and the room in the buffer, cap what is written.
    auto one = snapshotBuffer();
    ASSERT_EQ(plate.snapshot(1, one.data(), static_cast<int>(one.size())), 1);
    EXPECT_EQ(one[0], 5.0f);
    EXPECT_NEAR(one[2], 1.0f, kTol);
    EXPECT_EQ(one[4], -1.0f);
    ASSERT_EQ(plate.snapshot(0, one.data(), static_cast<int>(one.size())), 1);
    ASSERT_EQ(plate.snapshot(cym::kMaxRenderedModes, one.data(), kQuad), 1);
    EXPECT_EQ(plate.snapshot(cym::kMaxRenderedModes, one.data(), kQuad - 1), 0);
}

TEST(CymaticsPlate, AmplitudesUnderTheSilenceFloorAreDropped) {
    cym::Plate plate;
    const float bands[] = {cym::kSilence * 0.5f};
    plate.excite(bands, 1, kSettle, kFundamental, 1.0f, 0.5f, 0.0f);
    auto out = snapshotBuffer();
    EXPECT_EQ(plate.snapshot(cym::kMaxRenderedModes, out.data(), static_cast<int>(out.size())), 0);
    EXPECT_EQ(plate.dominantWavenumber(), 0.0f);
}

TEST(CymaticsPlate, NoTimeOrNoBandsChangesNothing) {
    cym::Plate plate;
    const float bands[] = {1.0f};
    plate.excite(bands, 1, 0.0f, kFundamental, 1.0f, 0.5f, 0.0f);
    plate.excite(bands, 0, kSettle, kFundamental, 1.0f, 0.5f, 0.0f);
    auto out = snapshotBuffer();
    EXPECT_EQ(plate.snapshot(cym::kMaxRenderedModes, out.data(), static_cast<int>(out.size())), 0);

    plate.excite(bands, 1, kSettle, kFundamental, 1.0f, 0.5f, 0.0f);
    plate.advancePhases(0.0f, 1.0f);
    ASSERT_EQ(plate.snapshot(1, out.data(), static_cast<int>(out.size())), 1);
    EXPECT_EQ(out[3], 0.0f);
}

TEST(CymaticsPlate, PhaseSpeedIsClamped) {
    cym::Plate plate;
    const float bands[] = {1.0f};
    plate.excite(bands, 1, kSettle, kFundamental, 1.0f, 0.5f, 0.0f);
    // (2,1) vibrates at 0.14 * sqrt(5) Hz; speed clamps to 4: 4 * 2pi * 0.313 * 0.25 s.
    plate.advancePhases(0.25f, 10.0f);
    auto out = snapshotBuffer();
    ASSERT_EQ(plate.snapshot(1, out.data(), static_cast<int>(out.size())), 1);
    EXPECT_NEAR(out[3], 1.9669481f, kTol);
}

TEST(CymaticsPlate, ResetSilencesThePlate) {
    cym::Plate plate;
    const float bands[] = {1.0f};
    plate.excite(bands, 1, kSettle, kFundamental, 1.0f, 0.5f, 0.0f);
    plate.reset();
    auto out = snapshotBuffer();
    EXPECT_EQ(plate.snapshot(cym::kMaxRenderedModes, out.data(), static_cast<int>(out.size())), 0);
    EXPECT_EQ(plate.dominantWavenumber(), 0.0f);
}

TEST(CymaticsDrops, AHitSpawnsADropThatRingsAndFades) {
    cym::Drops drops;
    drops.update(0.1f, 0.5f);
    const auto& p = drops.packed();
    EXPECT_NEAR(p[3], 0.385f, kTol);
    EXPECT_EQ(p[2], 0.0f);
    EXPECT_LE(std::fabs(p[0]), cym::Drops::kSpread);
    EXPECT_LE(std::fabs(p[1]), cym::Drops::kSpread);
    EXPECT_NE(p[0], p[1]);
    EXPECT_EQ(p[7], 0.0f);

    // Inside the cooldown: no new drop; the first one turns and fades.
    drops.update(0.1f, 0.5f);
    EXPECT_NEAR(p[2], 0.24f, kTol);
    EXPECT_NEAR(p[3], 0.35845917f, kTol);
    EXPECT_EQ(p[7], 0.0f);

    // The cooldown is over: the next slot.
    drops.update(0.05f, 0.5f);
    EXPECT_GT(p[7], 0.0f);
    EXPECT_NE(p[4], p[0]);
}

TEST(CymaticsDrops, WeakHitsDoNotSpawnAndStrongOnesAreClamped) {
    cym::Drops drops;
    drops.update(0.1f, cym::Drops::kSpawnThreshold);
    EXPECT_EQ(drops.packed()[3], 0.0f);
    drops.update(0.1f, 5.0f);
    EXPECT_NEAR(drops.packed()[3], 0.715f, kTol);
}

TEST(CymaticsDrops, FadedDropsAreSilencedAndSlotsWrap) {
    cym::Drops drops;
    for (int i = 0; i < cym::Drops::kSlots; ++i) drops.update(cym::Drops::kCooldownSeconds, 1.0f);
    for (int i = 0; i < cym::Drops::kSlots; ++i) EXPECT_GT(drops.packed()[static_cast<size_t>(i) * 4 + 3], 0.0f) << i;
    const float firstX = drops.packed()[0];
    // A seventh drop reuses slot 0 at full strength.
    drops.update(cym::Drops::kCooldownSeconds, 1.0f);
    EXPECT_NEAR(drops.packed()[3], 0.55f, kTol);
    EXPECT_NE(drops.packed()[0], firstX);

    // Ten seconds without hits: every drop is under the floor and set to zero.
    drops.update(10.0f, 0.0f);
    for (int i = 0; i < cym::Drops::kSlots; ++i) EXPECT_EQ(drops.packed()[static_cast<size_t>(i) * 4 + 3], 0.0f) << i;
}

TEST(CymaticsDrops, ResetAndZeroTime) {
    cym::Drops drops;
    drops.update(0.0f, 1.0f);
    EXPECT_EQ(drops.packed()[3], 0.0f);
    drops.update(0.1f, 1.0f);
    ASSERT_GT(drops.packed()[3], 0.0f);
    drops.reset();
    for (const float v : drops.packed()) EXPECT_EQ(v, 0.0f);
    // The cooldown is cleared too: a hit right after the reset spawns.
    drops.update(0.01f, 1.0f);
    EXPECT_GT(drops.packed()[7], 0.0f);
}
