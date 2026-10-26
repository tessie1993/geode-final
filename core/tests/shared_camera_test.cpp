#include <gtest/gtest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <limits>

#include "geode/viz/SharedCamera.hpp"

// The Camera as every Style gets it (CameraRig): push-in, orbit, travel, shake, the endless dive, and
// Camera v2's lens, roll and speed ramp (#330). Every value glides at the same rate per second, so a
// Reaction moving one never jumps the picture, and reduced motion calms the moving ones.

namespace geode::viz {
namespace {

constexpr float kDt = 1.0f / 60.0f;
constexpr float kTolerance = 2e-4f;
constexpr float kPi = 3.1415927f;
constexpr float kNaN = std::numeric_limits<float>::quiet_NaN();

// `seconds` of 60 Hz frames of `in`.
CameraPose run(CameraRig& rig, const CameraInput& in, float seconds, bool reducedMotion = false, float hit = 0.0f) {
    const int frames = static_cast<int>(std::lround(seconds / kDt));
    for (int i = 0; i < frames; ++i) rig.step(in, hit, kDt, reducedMotion);
    return rig.pose();
}

// Where a one-pole glide from `from` toward `to` at `hz` is after `seconds` of 60 Hz frames.
float glided(float from, float to, float hz, float seconds) {
    const int frames = static_cast<int>(std::lround(seconds / kDt));
    return to + (from - to) * std::pow(std::exp(-kDt * hz), static_cast<float>(frames));
}

// The smallest turn from angle `a` to angle `b`.
float turn(float a, float b) {
    const float d = std::fmod(b - a + 3.0f * kPi, 2.0f * kPi) - kPi;
    return d;
}

CameraInput lens(float fieldOfView) {
    CameraInput in;
    in.fieldOfView = fieldOfView;
    return in;
}

CameraInput rolling(float roll) {
    CameraInput in;
    in.roll = roll;
    return in;
}

CameraInput ramping(float ramp) {
    CameraInput in;
    in.speedRamp = ramp;
    return in;
}

// ---- the starting values -------------------------------------------------------------------------

TEST(CameraRig, AtItsStartingValuesTheCameraIsStillAndThePictureIsTheStylesOwn) {
    CameraRig rig;
    const CameraPose& p = run(rig, CameraInput{}, 2.0f);
    EXPECT_TRUE(p.still());
    EXPECT_EQ(p.push, 0.0f);
    EXPECT_EQ(p.travel, 0.0f);
    EXPECT_EQ(p.lens, 0.0f);
    EXPECT_EQ(p.roll, 0.0f);
    EXPECT_EQ(p.rampSpeed, 0.0f);
    EXPECT_EQ(p.flatPush(), 0.0f);
    EXPECT_EQ(p.shakeX, 0.0f);
    const std::array<float, 9> identity{1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f};
    for (size_t i = 0; i < identity.size(); ++i) EXPECT_NEAR(p.rotation[i], identity[i], 1e-6f) << i;
}

TEST(CameraRig, TheCameraValuesAreReadFromTheFrameKeptInRange) {
    mod::NativeFrame f;
    f.shared[static_cast<size_t>(mod::Shared::PushIn)] = 2.0f;
    f.shared[static_cast<size_t>(mod::Shared::OrbitYaw)] = -3.0f;
    f.shared[static_cast<size_t>(mod::Shared::OrbitPitch)] = 0.4f;
    f.shared[static_cast<size_t>(mod::Shared::Travel)] = kNaN;
    f.shared[static_cast<size_t>(mod::Shared::Shake)] = -1.0f;
    f.shared[static_cast<size_t>(mod::Shared::DiveSpeed)] = -0.5f;
    f.shared[static_cast<size_t>(mod::Shared::EndlessZoom)] = 0.5f;
    f.shared[static_cast<size_t>(mod::Shared::FieldOfView)] = 4.0f;
    f.shared[static_cast<size_t>(mod::Shared::Roll)] = -0.3f;
    f.shared[static_cast<size_t>(mod::Shared::SpeedRamp)] = 1.5f;
    const CameraInput in = CameraInput::from(f);
    EXPECT_EQ(in.pushIn, 1.0f);
    EXPECT_EQ(in.orbitYaw, -1.0f);
    EXPECT_NEAR(in.orbitPitch, 0.4f, 1e-6f);
    EXPECT_EQ(in.travel, 0.0f);
    EXPECT_EQ(in.shake, 0.0f);
    EXPECT_EQ(in.diveSpeed, 0.0f);
    EXPECT_TRUE(in.endlessZoom);
    EXPECT_EQ(in.fieldOfView, 1.0f);
    EXPECT_NEAR(in.roll, -0.3f, 1e-6f);
    EXPECT_EQ(in.speedRamp, 1.0f);

    f.shared[static_cast<size_t>(mod::Shared::EndlessZoom)] = 0.49f;
    f.shared[static_cast<size_t>(mod::Shared::FieldOfView)] = kNaN;
    f.shared[static_cast<size_t>(mod::Shared::Roll)] = -2.0f;
    f.shared[static_cast<size_t>(mod::Shared::SpeedRamp)] = -1.0f;
    f.shared[static_cast<size_t>(mod::Shared::DiveSpeed)] = 0.8f;
    const CameraInput again = CameraInput::from(f);
    EXPECT_FALSE(again.endlessZoom);
    EXPECT_EQ(again.fieldOfView, 0.0f);
    EXPECT_EQ(again.roll, -1.0f);
    EXPECT_EQ(again.speedRamp, 0.0f);
    EXPECT_NEAR(again.diveSpeed, 0.8f, 1e-6f);
}

TEST(CameraRig, AFrameWithNoTimeMovesNothingAndALongOneIsCappedAtATenthOfASecond) {
    CameraInput in;
    in.pushIn = 1.0f;
    CameraRig rig;
    rig.step(in, 0.0f, kNaN, false);
    rig.step(in, 0.0f, -1.0f, false);
    EXPECT_EQ(rig.pose().push, 0.0f);
    rig.step(in, 0.0f, 5.0f, false);
    EXPECT_NEAR(rig.pose().push, 1.0f - std::exp(-0.1f * camera::kGlideHz), 1e-5f);
}

TEST(CameraRig, ResetPutsTheCameraBackAtRest) {
    CameraInput in;
    in.pushIn = 1.0f;
    in.roll = 1.0f;
    in.speedRamp = 1.0f;
    CameraRig rig;
    run(rig, in, 1.0f);
    rig.reset();
    EXPECT_TRUE(rig.pose().still());
    EXPECT_EQ(rig.pose().roll, 0.0f);
    EXPECT_EQ(rig.pose().travel, 0.0f);
    EXPECT_EQ(run(rig, CameraInput{}, 1.0f).roll, 0.0f);
}

// ---- push-in, orbit, travel ---------------------------------------------------------------------

TEST(CameraRig, PushInGlidesToWhereItIsSet) {
    CameraInput in;
    in.pushIn = 1.0f;
    CameraRig rig;
    EXPECT_NEAR(run(rig, in, 0.4f).push, glided(0.0f, 1.0f, camera::kGlideHz, 0.4f), kTolerance);
    EXPECT_FALSE(rig.pose().still());
    EXPECT_NEAR(rig.pose().flatPush(), rig.pose().push, kTolerance);
}

TEST(CameraRig, TheOrbitTurnsTheCameraYawThenPitch) {
    CameraInput in;
    in.orbitYaw = 0.5f;
    CameraRig rig;
    const CameraPose& yawed = run(rig, in, 12.0f);
    EXPECT_NEAR(yawed.yaw, 0.5f * camera::kMaxYaw, kTolerance);
    EXPECT_NEAR(yawed.tiltYaw, 0.5f * camera::kMaxTiltYaw, kTolerance);
    EXPECT_NEAR(yawed.rotation[0], 0.0f, kTolerance);
    EXPECT_NEAR(yawed.rotation[2], -1.0f, kTolerance);
    EXPECT_NEAR(yawed.rotation[6], 1.0f, kTolerance);
    EXPECT_FALSE(yawed.still());

    in.orbitYaw = 0.0f;
    in.orbitPitch = -1.0f;
    const CameraPose& pitched = run(rig, in, 12.0f);
    EXPECT_NEAR(pitched.pitch, -camera::kMaxPitch, kTolerance);
    EXPECT_NEAR(pitched.tiltPitch, -camera::kMaxTiltPitch, kTolerance);
    EXPECT_NEAR(pitched.rotation[4], std::cos(camera::kMaxPitch), kTolerance);
    EXPECT_NEAR(pitched.rotation[7], std::sin(camera::kMaxPitch), kTolerance);
    EXPECT_NEAR(pitched.rotation[5], -std::sin(camera::kMaxPitch), kTolerance);
}

TEST(CameraRig, TravelFliesAtItsSpeedAndSlowerWithReducedMotion) {
    CameraInput in;
    in.travel = 1.0f;
    CameraRig rig;
    const float before = run(rig, in, 12.0f).travel;
    const float after = run(rig, in, 1.0f).travel;
    EXPECT_NEAR(after - before, camera::kTravelSpeed, 1e-2f);
    EXPECT_GT(rig.pose().travelDive, 0.0f);

    CameraRig calm;
    const float calmBefore = run(calm, in, 12.0f, true).travel;
    EXPECT_NEAR(run(calm, in, 1.0f, true).travel - calmBefore, 0.3f * camera::kTravelSpeed, 1e-2f);
}

TEST(CameraRig, TheEndlessDiveRunsWhileOnAndSettlesBackToThePictureWhenOff) {
    CameraInput in;
    in.endlessZoom = true;
    in.diveSpeed = 0.4f;
    CameraRig rig;
    EXPECT_NEAR(run(rig, in, 1.0f).endlessDive, 0.4f * camera::kEndlessDiveHz, 1e-3f);
    in.endlessZoom = false;
    EXPECT_EQ(run(rig, in, 10.0f).endlessDive, 0.0f);
}

// ---- the shake ------------------------------------------------------------------------------------

TEST(CameraRig, TheShakeStaysSmallSwellsWithHitsAndStopsWithReducedMotion) {
    CameraInput in;
    in.shake = 1.0f;
    CameraRig rig;
    float largest = 0.0f;
    for (int i = 0; i < 300; ++i) {
        const CameraPose& p = rig.step(in, 1.0f, kDt, false);
        largest = std::max({largest, std::fabs(p.shakeX), std::fabs(p.shakeY)});
        EXPECT_LE(std::fabs(p.shakeRoll), camera::kMaxShakeRoll + 1e-6f);
    }
    EXPECT_GT(largest, 0.5f * camera::kMaxShake);
    EXPECT_LE(largest, camera::kMaxShake + 1e-6f);

    CameraRig quiet;
    float quietLargest = 0.0f;
    for (int i = 0; i < 300; ++i) {
        const CameraPose& p = quiet.step(in, 0.0f, kDt, false);
        quietLargest = std::max({quietLargest, std::fabs(p.shakeX), std::fabs(p.shakeY)});
    }
    // Between hits it wobbles at its quiet share (0.35) of the same swing.
    EXPECT_LT(quietLargest, 0.4f * largest);
    EXPECT_GT(quietLargest, 0.3f * largest);

    CameraRig calm;
    const CameraPose& p = run(calm, in, 2.0f, true, 1.0f);
    EXPECT_EQ(p.shakeX, 0.0f);
    EXPECT_EQ(p.shakeY, 0.0f);
    EXPECT_EQ(p.shakeRoll, 0.0f);
}

// ---- Camera v2: the lens ---------------------------------------------------------------------------

TEST(CameraRig, TheLensSpringsOpenFastAndSettlesBackSlowlySoAHitPunchesIt) {
    CameraRig rig;
    const float opened = run(rig, lens(1.0f), 0.1f).lens;
    EXPECT_NEAR(opened, camera::kLensOctaves * glided(0.0f, 1.0f, camera::kLensPunchHz, 0.1f), kTolerance);
    const float settling = run(rig, lens(0.0f), 0.1f).lens;
    EXPECT_NEAR(settling, opened * std::exp(-camera::kLensSettleHz * 0.1f), kTolerance);
    EXPECT_GT(settling, 0.8f * opened);
    EXPECT_EQ(run(rig, lens(0.0f), 12.0f).lens, 0.0f);
    EXPECT_TRUE(rig.pose().still());
}

TEST(CameraRig, AFullLensWidensOrNarrowsByItsOctaves) {
    CameraRig wide;
    EXPECT_NEAR(run(wide, lens(1.0f), 5.0f).lens, camera::kLensOctaves, kTolerance);
    CameraRig narrow;
    EXPECT_NEAR(run(narrow, lens(-1.0f), 5.0f).lens, -camera::kLensOctaves, kTolerance);
}

TEST(CameraRig, AFlatPictureOnlyNarrowsMagnifiedLikeAPushIn) {
    CameraRig wide;
    const CameraPose& w = run(wide, lens(1.0f), 5.0f);
    EXPECT_EQ(w.flatPush(), 0.0f);
    EXPECT_TRUE(w.still());

    CameraRig narrow;
    const CameraPose& n = run(narrow, lens(-1.0f), 5.0f);
    const float magnify = std::exp2(camera::kLensOctaves);
    EXPECT_NEAR(n.flatPush(), (magnify - 1.0f) / 0.6f, 1e-3f);
    EXPECT_FALSE(n.still());

    CameraInput both = lens(-1.0f);
    both.pushIn = 1.0f;
    CameraRig pushed;
    EXPECT_NEAR(run(pushed, both, 12.0f).flatPush(), (1.6f * magnify - 1.0f) / 0.6f, 1e-3f);
}

TEST(CameraRig, ReducedMotionTakesThePunchOutOfTheLensAndHalvesIt) {
    CameraRig rig;
    EXPECT_NEAR(run(rig, lens(1.0f), 0.1f, true).lens,
                camera::kLensOctaves * glided(0.0f, 0.5f, camera::kGlideHz, 0.1f), kTolerance);
    EXPECT_NEAR(run(rig, lens(1.0f), 12.0f, true).lens, 0.5f * camera::kLensOctaves, kTolerance);
    const float before = rig.pose().lens;
    EXPECT_NEAR(run(rig, lens(0.0f), 0.1f, true).lens, before * std::exp(-camera::kGlideHz * 0.1f), kTolerance);
}

// ---- Camera v2: the roll -----------------------------------------------------------------------------

TEST(CameraRig, TheRollAcceleratesIntoItsSpinNoFasterThanItsLimit) {
    CameraRig rig;
    // After n frames the rate is n steps of the limit, so the angle is the sum of those.
    const int frames = 30;
    const float step = camera::kRollAccel * kDt;
    const float expected = step * kDt * static_cast<float>(frames * (frames + 1) / 2);
    EXPECT_NEAR(run(rig, rolling(1.0f), frames * kDt).roll, expected, 1e-4f);
    EXPECT_FALSE(rig.pose().still());

    // Up to speed, it turns at the full rate, a quarter turn a second, and stays within half a turn.
    run(rig, rolling(1.0f), 3.0f);
    float last = rig.pose().roll;
    for (int i = 0; i < 240; ++i) {
        const float now = rig.step(rolling(1.0f), 0.0f, kDt, false).roll;
        EXPECT_NEAR(turn(last, now), camera::kMaxRollRate * kDt, 1e-4f);
        EXPECT_LE(std::fabs(now), kPi + 1e-5f);
        last = now;
    }
}

TEST(CameraRig, TheRollTurnsEitherWay) {
    CameraRig rig;
    run(rig, rolling(-1.0f), 3.0f);
    const float before = rig.pose().roll;
    EXPECT_NEAR(turn(before, run(rig, rolling(-1.0f), 0.1f).roll), -0.1f * camera::kMaxRollRate, 1e-3f);
}

TEST(CameraRig, WithTheRollAtZeroTheCameraLevelsOutSmoothlyAndComesToRestUpright) {
    CameraRig rig;
    run(rig, rolling(1.0f), 2.5f);
    ASSERT_GT(std::fabs(rig.pose().roll), 0.5f);
    float last = rig.pose().roll;
    for (int i = 0; i < 60 * 30; ++i) {
        const float now = rig.step(CameraInput{}, 0.0f, kDt, false).roll;
        EXPECT_LE(std::fabs(turn(last, now)), camera::kMaxRollRate * kDt + 1e-5f) << i;
        last = now;
    }
    EXPECT_EQ(rig.pose().roll, 0.0f);
    EXPECT_TRUE(rig.pose().still());
}

TEST(CameraRig, ReducedMotionHoldsTheRollToASlowTurn) {
    CameraRig rig;
    run(rig, rolling(1.0f), 3.0f, true);
    const float before = rig.pose().roll;
    EXPECT_NEAR(turn(before, run(rig, rolling(1.0f), 0.5f, true).roll), 0.25f * camera::kMaxRollRate * 0.5f, 1e-3f);
}

// ---- Camera v2: the speed ramp -----------------------------------------------------------------------

TEST(CameraRig, TheSpeedRampRampsInFasterThanItEasesOff) {
    CameraRig rig;
    const float rising = run(rig, ramping(1.0f), 0.5f).rampSpeed;
    EXPECT_NEAR(rising, glided(0.0f, camera::kRampSpeed, camera::kRampRiseHz, 0.5f), 1e-3f);
    run(rig, ramping(1.0f), 15.0f);
    EXPECT_NEAR(rig.pose().rampSpeed, camera::kRampSpeed, 1e-3f);
    const float easing = run(rig, ramping(0.0f), 0.5f).rampSpeed;
    EXPECT_NEAR(easing, glided(camera::kRampSpeed, 0.0f, camera::kRampFallHz, 0.5f), 1e-3f);
    EXPECT_GT(camera::kRampSpeed - easing, 0.0f);
    EXPECT_LT(camera::kRampSpeed - easing, rising);
    EXPECT_EQ(run(rig, ramping(0.0f), 40.0f).rampSpeed, 0.0f);
}

TEST(CameraRig, TheSpeedRampFliesTheCameraFurtherAndRunsTheFlatDive) {
    CameraRig rig;
    const float before = run(rig, ramping(1.0f), 15.0f).travel;
    EXPECT_NEAR(run(rig, ramping(1.0f), 1.0f).travel - before, camera::kRampSpeed, 2e-2f);
    EXPECT_GT(rig.pose().travelDive, 0.0f);
    EXPECT_FALSE(rig.pose().still());

    // On top of travel: the two speeds add.
    CameraInput both = ramping(0.5f);
    both.travel = 1.0f;
    CameraRig flying;
    const float start = run(flying, both, 20.0f).travel;
    EXPECT_NEAR(run(flying, both, 1.0f).travel - start, camera::kTravelSpeed + 0.5f * camera::kRampSpeed, 2e-2f);
}

TEST(CameraRig, ReducedMotionSlowsTheSpeedRampLikeTravel) {
    CameraRig rig;
    EXPECT_NEAR(run(rig, ramping(1.0f), 20.0f, true).rampSpeed, 0.3f * camera::kRampSpeed, 1e-3f);
}

}  // namespace
}  // namespace geode::viz
