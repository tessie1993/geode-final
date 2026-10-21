#include "geode/viz/FormDrive.hpp"

#include <gtest/gtest.h>

#include <cmath>
#include <limits>
#include <vector>

using geode::viz::FormDrive;
using geode::viz::SceneParams;

namespace {

constexpr float kTol = 1e-4f;
constexpr float kFrame = 1.0f / 60.0f;
constexpr float kHalfPi = 1.5707963f;
constexpr float kReducedMotionScale = 0.4f;
constexpr int kMinLobes = 3;
constexpr int kMaxLobes = 12;
constexpr int kDefaultLobes = 6;

GeodeFeatureFrame loud() {
    GeodeFeatureFrame f{};
    f.rms = 0.5f;
    f.bass = 0.8f;
    f.treble = 0.2f;
    f.centroid = 0.3f;
    f.harmonicity = 0.2f;
    f.stereoPan = 0.4f;
    f.stereoWidth = 0.7f;
    return f;
}

void run(FormDrive& drive, const GeodeFeatureFrame& f, float seconds) {
    const int frames = static_cast<int>(std::lround(seconds / kFrame));
    for (int i = 0; i < frames; ++i) drive.step(f, kFrame);
}

std::vector<float> sineBlock(float amplitude) {
    constexpr int kSamples = 1024;
    constexpr float kCyclesPerSample = 0.01f;
    std::vector<float> out(kSamples);
    for (int i = 0; i < kSamples; ++i) out[static_cast<size_t>(i)] = amplitude * std::sin(6.2831853f * kCyclesPerSample * i);
    return out;
}

}  // namespace

TEST(FormDrive, RadiusOfTheCircleIsOne) {
    const FormDrive::Curve circle{4, 2.0f, 2.0f, 2.0f, 1.0f, 1.0f};
    for (const float theta : {0.0f, 0.3f, 1.0f, 2.5f}) EXPECT_NEAR(FormDrive::radius(theta, circle), 1.0f, kTol) << theta;
}

TEST(FormDrive, RadiusFollowsTheSuperformula) {
    // theta 0: |cos 0 / a|^n2 = 0.25, r = 0.25^(-1/2).
    const FormDrive::Curve wide{4, 2.0f, 2.0f, 1.0f, 2.0f, 0.5f};
    EXPECT_NEAR(FormDrive::radius(0.0f, wide), 2.0f, kTol);
    // m * theta / 4 = pi / 2: |sin / b|^n3 = 2, r = 2^(-1/n1).
    const FormDrive::Curve tall{4, 1.0f, 2.0f, 1.0f, 2.0f, 0.5f};
    EXPECT_NEAR(FormDrive::radius(kHalfPi, tall), 0.5f, kTol);
}

TEST(FormDrive, DegenerateCurvesFallBackToTheCircle) {
    // Both terms underflow to zero.
    const FormDrive::Curve vanishing{4, 2.0f, 200.0f, 200.0f, 2.0f, 2.0f};
    EXPECT_EQ(FormDrive::radius(0.0f, vanishing), 1.0f);
    // A zero weight divides by zero.
    const FormDrive::Curve infinite{4, 2.0f, 2.0f, 2.0f, 0.0f, 1.0f};
    EXPECT_EQ(FormDrive::radius(0.0f, infinite), 1.0f);
}

TEST(FormDrive, StartsAtRestAndLeavesParametersAlone) {
    FormDrive drive;
    EXPECT_EQ(drive.curve().m, kDefaultLobes);
    EXPECT_EQ(drive.spin(), 0.0f);
    EXPECT_EQ(drive.presence(), 0.0f);
    for (const float t : drive.taps()) EXPECT_EQ(t, 0.0f);
    SceneParams p;
    p.zoom = 1.3f;
    const SceneParams out = drive.apply(p, false);
    EXPECT_EQ(out.zoom, 1.3f);
    EXPECT_EQ(out.rotation, p.rotation);
}

TEST(FormDrive, SilenceKeepsItAtRest) {
    FormDrive drive;
    run(drive, GeodeFeatureFrame{}, 2.0f);
    EXPECT_NEAR(drive.presence(), 0.0f, kTol);
    EXPECT_EQ(drive.curve().m, kDefaultLobes);
    SceneParams p;
    const SceneParams out = drive.apply(p, false);
    EXPECT_EQ(out.zoom, p.zoom);
    EXPECT_EQ(out.twist, p.twist);
}

TEST(FormDrive, SpinTurnsSlowlyInSilenceAndDtIsClamped) {
    FormDrive drive;
    drive.step(GeodeFeatureFrame{}, 0.1f);
    EXPECT_NEAR(drive.spin(), 0.005f, kTol);
    // A long frame counts as 0.1 s.
    drive.step(GeodeFeatureFrame{}, 5.0f);
    EXPECT_NEAR(drive.spin(), 0.01f, kTol);
    drive.step(GeodeFeatureFrame{}, -1.0f);
    EXPECT_NEAR(drive.spin(), 0.01f, kTol);
}

TEST(FormDrive, ASectionBoundaryReversesTheSpinAndRerollsTheCount) {
    FormDrive drive;
    drive.step(GeodeFeatureFrame{}, 0.1f);
    GeodeFeatureFrame section{};
    section.sectionBoundary = 1.0f;
    // Half as long a frame, the other way: 0.005 - 0.0025.
    drive.step(section, 0.05f);
    EXPECT_NEAR(drive.spin(), 0.0025f, kTol);
    EXPECT_NE(drive.curve().m, kDefaultLobes);
    EXPECT_GE(drive.curve().m, kMinLobes);
    EXPECT_LE(drive.curve().m, kMaxLobes);
}

TEST(FormDrive, ADropPicksABoldLowCount) {
    FormDrive drive;
    GeodeFeatureFrame drop{};
    drop.drop = 1.0f;
    drive.step(drop, kFrame);
    EXPECT_GE(drive.curve().m, 3);
    EXPECT_LE(drive.curve().m, 5);
}

TEST(FormDrive, SnaresClimbTheCountThroughABuildup) {
    FormDrive drive;
    GeodeFeatureFrame build{};
    build.buildup = 1.0f;
    run(drive, build, 2.0f);
    GeodeFeatureFrame snare = build;
    snare.snare = 1.0f;
    drive.step(snare, kFrame);
    EXPECT_EQ(drive.curve().m, kDefaultLobes + 1);
    // Locked out for 1.4 s, even with the snare re-armed.
    drive.step(build, kFrame);
    drive.step(snare, kFrame);
    EXPECT_EQ(drive.curve().m, kDefaultLobes + 1);
    run(drive, build, 1.5f);
    drive.step(snare, kFrame);
    EXPECT_EQ(drive.curve().m, kDefaultLobes + 2);
}

TEST(FormDrive, AHeldSnareChangesTheCountOnce) {
    FormDrive drive;
    GeodeFeatureFrame snare{};
    snare.buildup = 1.0f;
    run(drive, snare, 2.0f);
    snare.snare = 1.0f;
    run(drive, snare, 3.0f);
    EXPECT_EQ(drive.curve().m, kDefaultLobes + 1);
}

TEST(FormDrive, ASnareOutsideABuildupStepsOneLobe) {
    FormDrive drive;
    GeodeFeatureFrame snare{};
    snare.snare = 1.0f;
    drive.step(snare, kFrame);
    const int m = drive.curve().m;
    EXPECT_TRUE(m == kDefaultLobes - 1 || m == kDefaultLobes + 1) << m;
    // A quiet snare is under the gate.
    FormDrive quiet;
    snare.snare = 0.2f;
    quiet.step(snare, kFrame);
    EXPECT_EQ(quiet.curve().m, kDefaultLobes);
}

TEST(FormDrive, LoudMusicBringsTheReactionIn) {
    FormDrive drive;
    run(drive, loud(), 3.0f);
    EXPECT_GT(drive.presence(), 0.99f);
    bool moved = false;
    for (const float t : drive.taps()) {
        EXPECT_GE(t, -1.0f);
        EXPECT_LE(t, 1.0f);
        if (std::fabs(t) > 0.1f) moved = true;
    }
    EXPECT_TRUE(moved);
    EXPECT_GT(drive.spin(), 0.0f);
}

TEST(FormDrive, ApplyMovesGeometryButNeverLuminance) {
    FormDrive drive;
    run(drive, loud(), 3.0f);
    SceneParams p;
    p.formDrive = 1.0f;
    const SceneParams out = drive.apply(p, false);
    EXPECT_NE(out.zoom, p.zoom);
    EXPECT_NE(out.twist, p.twist);
    EXPECT_NE(out.rotation, p.rotation);
    EXPECT_GE(out.zoom, 0.3f);
    EXPECT_LE(out.zoom, 3.0f);
    EXPECT_EQ(out.brightness, p.brightness);
    EXPECT_EQ(out.intensity, p.intensity);
    EXPECT_EQ(out.bloom, p.bloom);
    EXPECT_EQ(out.flash, p.flash);
    EXPECT_EQ(out.strobe, p.strobe);
    EXPECT_EQ(out.glitch, p.glitch);
    EXPECT_EQ(out.symmetry, p.symmetry);

    p.formDrive = 0.0f;
    const SceneParams off = drive.apply(p, false);
    EXPECT_EQ(off.zoom, p.zoom);
    EXPECT_EQ(off.twist, p.twist);
}

TEST(FormDrive, ReducedMotionScalesTheGeometry) {
    FormDrive drive;
    run(drive, loud(), 3.0f);
    SceneParams p;
    p.formDrive = 1.0f;
    const SceneParams full = drive.apply(p, false);
    const SceneParams reduced = drive.apply(p, true);
    // Twist and rotation are linear in the amount while they stay inside their clamps.
    EXPECT_NEAR(reduced.twist - p.twist, kReducedMotionScale * (full.twist - p.twist), kTol);
    EXPECT_NEAR(reduced.rotation - p.rotation, kReducedMotionScale * (full.rotation - p.rotation), kTol);
    EXPECT_NEAR(reduced.colorShift - p.colorShift, kReducedMotionScale * (full.colorShift - p.colorShift), kTol);

    // Half the amount moves them half as far.
    SceneParams half = p;
    half.formDrive = 0.5f;
    const SceneParams halfOut = drive.apply(half, false);
    EXPECT_NEAR(halfOut.twist - p.twist, 0.5f * (full.twist - p.twist), kTol);
    // The amount is clamped to 1.
    SceneParams over = p;
    over.formDrive = 3.0f;
    EXPECT_NEAR(drive.apply(over, false).twist, full.twist, kTol);
}

TEST(FormDrive, TheKaleidoscopeFoldsByTheLobeCount) {
    FormDrive drive;
    run(drive, loud(), 3.0f);
    SceneParams p;
    p.formDrive = 1.0f;
    p.kaleidoscope = true;
    p.symmetry = 2;
    EXPECT_EQ(drive.apply(p, false).symmetry, drive.curve().m);
}

TEST(FormDrive, PcmAloneDrivesThePresenceAndDecaysWithoutIt) {
    FormDrive drive;
    const std::vector<float> block = sineBlock(0.5f);
    for (int i = 0; i < 120; ++i) {
        drive.acceptPcm(block.data(), static_cast<int>(block.size()));
        drive.step(GeodeFeatureFrame{}, kFrame);
    }
    EXPECT_GT(drive.presence(), 0.9f);
    run(drive, GeodeFeatureFrame{}, 20.0f);
    EXPECT_LT(drive.presence(), 0.01f);
}

TEST(FormDrive, EmptyPcmBlocksAreIgnored) {
    FormDrive drive;
    const std::vector<float> block = sineBlock(0.5f);
    for (int i = 0; i < 60; ++i) {
        drive.acceptPcm(nullptr, static_cast<int>(block.size()));
        drive.acceptPcm(block.data(), 0);
        drive.step(GeodeFeatureFrame{}, kFrame);
    }
    EXPECT_NEAR(drive.presence(), 0.0f, kTol);
}

TEST(FormDrive, ResetReturnsToRest) {
    FormDrive drive;
    GeodeFeatureFrame f = loud();
    f.drop = 1.0f;
    drive.step(f, kFrame);
    run(drive, loud(), 2.0f);
    drive.reset();
    EXPECT_EQ(drive.curve().m, kDefaultLobes);
    EXPECT_EQ(drive.spin(), 0.0f);
    EXPECT_EQ(drive.presence(), 0.0f);
    for (const float t : drive.taps()) EXPECT_EQ(t, 0.0f);
}

TEST(FormDrive, IsDeterministic) {
    FormDrive a;
    FormDrive b;
    GeodeFeatureFrame f = loud();
    for (int i = 0; i < 240; ++i) {
        f.snare = (i % 30 == 0) ? 1.0f : 0.0f;
        f.kick = (i % 15 == 0) ? 0.8f : 0.0f;
        f.hat = (i % 5 == 0) ? 0.5f : 0.0f;
        f.sectionBoundary = (i == 100) ? 1.0f : 0.0f;
        a.step(f, kFrame);
        b.step(f, kFrame);
    }
    EXPECT_EQ(a.curve().m, b.curve().m);
    EXPECT_EQ(a.spin(), b.spin());
    EXPECT_EQ(a.taps(), b.taps());
    a.reset();
    b.reset();
    a.step(f, kFrame);
    b.step(f, kFrame);
    EXPECT_EQ(a.taps(), b.taps());
}
