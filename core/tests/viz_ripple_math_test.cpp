#include "geode/viz/fluid/RippleMath.hpp"

#include <gtest/gtest.h>

#include <vector>

namespace ripple = geode::viz::fluid::ripple;

namespace {

constexpr float kTol = 1e-4f;
constexpr float kFrame = 1.0f / 60.0f;

struct Queued {
    float x;
    float y;
    float radius;
    float amp;
};

class Recorder {
public:
    ripple::OverlayDrops::Queue queue() {
        return [this](float x, float y, float radius, float amp) { drops.push_back({x, y, radius, amp}); };
    }
    std::vector<Queued> drops;
};

GeodeFeatureFrame frame(float transient, float bass, float treble) {
    GeodeFeatureFrame f{};
    f.transient = transient;
    f.bass = bass;
    f.treble = treble;
    return f;
}

}  // namespace

TEST(RippleMath, HeightDecayPerSubstep) {
    EXPECT_NEAR(ripple::heightDecayPerSubstep(0.9f, kFrame), 0.965f, kTol);
    EXPECT_NEAR(ripple::heightDecayPerSubstep(0.9f, 2.0f * kFrame), 0.931225f, kTol);
    EXPECT_NEAR(ripple::heightDecayPerSubstep(2.0f, kFrame), 1.0f, kTol);
    EXPECT_NEAR(ripple::heightDecayPerSubstep(-1.0f, kFrame), 0.65f, kTol);
}

TEST(RippleMath, DropProfileIsAGaussian) {
    EXPECT_NEAR(ripple::dropProfile(0.0f, 0.1f, 2.0f), 2.0f, kTol);
    EXPECT_NEAR(ripple::dropProfile(0.1f, 0.1f, 2.0f), 0.73575888f, kTol);
    EXPECT_NEAR(ripple::dropProfile(-0.1f, 0.1f, 2.0f), 0.73575888f, kTol);
    // A zero radius is floored, not divided by.
    EXPECT_NEAR(ripple::dropProfile(0.0f, 0.0f, 3.0f), 3.0f, kTol);
    EXPECT_NEAR(ripple::dropProfile(1e-4f, 0.0f, 1.0f), 0.36787944f, kTol);
}

TEST(RippleMath, CflClampedDt) {
    EXPECT_NEAR(ripple::cflClampedDt(0.0f, 0.1f, 1.0f), 0.1f, kTol);
    EXPECT_NEAR(ripple::cflClampedDt(1e-7f, 0.1f, 1.0f), 0.1f, kTol);
    EXPECT_NEAR(ripple::cflClampedDt(10.0f, 0.1f, 1.0f), 0.07f, kTol);
    EXPECT_NEAR(ripple::cflClampedDt(1.0f, 0.1f, 1.0f), 0.1f, kTol);
}

TEST(RippleMath, RefractionOffsetIsSoftCapped) {
    const auto [x, y] = ripple::refractionOffset(0.0f, 0.06f, 0.0f, 0.08f, 1.0f);
    EXPECT_NEAR(x, 0.026666667f, kTol);
    EXPECT_NEAR(y, -0.035555556f, kTol);
    const auto [sx, sy] = ripple::refractionOffset(0.0f, 0.06f, 0.0f, 0.08f, 2.0f);
    EXPECT_NEAR(sx, 0.034285714f, kTol);
    EXPECT_NEAR(sy, -0.045714286f, kTol);
    const auto [fx, fy] = ripple::refractionOffset(0.5f, 0.5f, 0.5f, 0.5f, 1.0f);
    EXPECT_EQ(fx, 0.0f);
    EXPECT_EQ(fy, 0.0f);
}

TEST(RippleMath, InkDissipation) {
    EXPECT_NEAR(ripple::inkDissipation(1.0f, 0.1f), 0.9f, kTol);
    EXPECT_NEAR(ripple::inkDissipation(10.0f, 0.1f), 0.2f, kTol);
    EXPECT_NEAR(ripple::inkDissipation(-1.0f, 0.1f), 1.0f, kTol);
    EXPECT_NEAR(ripple::inkDissipation(8.0f, 0.2f), 0.0f, kTol);
}

TEST(RippleMath, StrokeDropsAtRestIsOneDrop) {
    const auto drops = ripple::strokeDrops(0.2f, 0.3f, 0.0f, 0.0f, 0.0f, 0.1f, 1.0f);
    ASSERT_EQ(drops.size(), 1u);
    EXPECT_NEAR(drops[0].x, 0.2f, kTol);
    EXPECT_NEAR(drops[0].y, 0.3f, kTol);
    EXPECT_NEAR(drops[0].radius, 0.1f, kTol);
    EXPECT_NEAR(drops[0].amplitude, 0.25f, kTol);
    EXPECT_TRUE(ripple::strokeDrops(0.2f, 0.3f, 0.0f, 0.0f, 0.0f, 0.1f, 0.0f).empty());
}

TEST(RippleMath, StrokeDropsLeadAndTrailTheStroke) {
    const auto drops = ripple::strokeDrops(0.0f, 0.0f, 0.03f, 0.04f, 0.1f, 0.1f, 1.0f);
    ASSERT_EQ(drops.size(), 2u);
    EXPECT_NEAR(drops[0].x, 0.036f, kTol);
    EXPECT_NEAR(drops[0].y, 0.048f, kTol);
    EXPECT_NEAR(drops[0].amplitude, 0.5625f, kTol);
    EXPECT_NEAR(drops[1].x, -0.036f, kTol);
    EXPECT_NEAR(drops[1].y, -0.048f, kTol);
    EXPECT_NEAR(drops[1].radius, 0.1f, kTol);
    EXPECT_NEAR(drops[1].amplitude, -0.45f, kTol);
}

TEST(RippleMath, StrokeDropsClampSpeedAndStrength) {
    const auto drops = ripple::strokeDrops(0.0f, 0.0f, 1.0f, 0.0f, 0.1f, 0.1f, 3.0f);
    ASSERT_EQ(drops.size(), 2u);
    EXPECT_NEAR(drops[0].x, 0.06f, kTol);
    EXPECT_NEAR(drops[0].y, 0.0f, kTol);
    EXPECT_NEAR(drops[0].amplitude, 3.5f, kTol);
    EXPECT_NEAR(drops[1].amplitude, -2.8f, kTol);
}

TEST(RippleMath, OverlayDropPositionsFollowTheGoldenAngle) {
    const auto [x0, y0] = ripple::overlayDropPosition(0, 1.0f);
    EXPECT_NEAR(x0, 0.0f, kTol);
    EXPECT_NEAR(y0, 0.0f, kTol);
    const auto [x1, y1] = ripple::overlayDropPosition(1, 1.0f);
    EXPECT_NEAR(x1, -0.49273097f, kTol);
    EXPECT_NEAR(y1, 0.45138205f, kTol);
    const auto [w1, h1] = ripple::overlayDropPosition(1, 2.0f);
    EXPECT_NEAR(w1, -0.98546193f, kTol);
    EXPECT_NEAR(h1, 0.45138205f, kTol);
    const auto [x3, y3] = ripple::overlayDropPosition(3, 1.0f);
    EXPECT_NEAR(x3, 0.47795934f, kTol);
    EXPECT_NEAR(y3, 0.62341282f, kTol);
    const auto [xn, yn] = ripple::overlayDropPosition(-3, 1.0f);
    EXPECT_NEAR(xn, 0.0f, kTol);
    EXPECT_NEAR(yn, 0.0f, kTol);
}

TEST(RippleOverlayDrops, AHitQueuesTwoRingsOnItsRisingEdge) {
    ripple::OverlayDrops drops;
    Recorder rec;
    drops.tick(frame(0.5f, 0.5f, 0.0f), 1.0f, rec.queue());
    ASSERT_EQ(rec.drops.size(), 2u);
    EXPECT_NEAR(rec.drops[0].x, 0.0f, kTol);
    EXPECT_NEAR(rec.drops[0].radius, 0.055f, kTol);
    EXPECT_NEAR(rec.drops[0].amp, 0.21f, kTol);
    EXPECT_NEAR(rec.drops[1].x, -0.49273097f, kTol);
    EXPECT_NEAR(rec.drops[1].y, 0.45138205f, kTol);
    EXPECT_NEAR(rec.drops[1].amp, 0.21f, kTol);

    // Held: no new edge.
    drops.tick(frame(0.5f, 0.5f, 0.0f), 1.0f, rec.queue());
    EXPECT_EQ(rec.drops.size(), 2u);
    // Released, then hit again: the next two positions.
    drops.tick(frame(0.0f, 0.5f, 0.0f), 1.0f, rec.queue());
    EXPECT_EQ(rec.drops.size(), 2u);
    drops.tick(frame(0.5f, 2.0f, 0.0f), 1.0f, rec.queue());
    ASSERT_EQ(rec.drops.size(), 4u);
    EXPECT_NEAR(rec.drops[3].x, 0.47795934f, kTol);
    // Bass clamps at 1.5: (0.22 + 0.6) * 0.5.
    EXPECT_NEAR(rec.drops[3].amp, 0.41f, kTol);
}

TEST(RippleOverlayDrops, ATransientUnderTheFloorIsNotAHit) {
    ripple::OverlayDrops drops;
    Recorder rec;
    drops.tick(frame(0.05f, 0.5f, 0.0f), 1.0f, rec.queue());
    EXPECT_TRUE(rec.drops.empty());
}

TEST(RippleOverlayDrops, BrightTrebleSparklesEverySixthFrame) {
    ripple::OverlayDrops drops;
    Recorder rec;
    for (int i = 1; i < ripple::OverlayDrops::kSparkleInterval; ++i) {
        drops.tick(frame(0.0f, 0.0f, 0.6f), 1.0f, rec.queue());
    }
    EXPECT_TRUE(rec.drops.empty());
    drops.tick(frame(0.0f, 0.0f, 0.6f), 1.0f, rec.queue());
    ASSERT_EQ(rec.drops.size(), 1u);
    EXPECT_NEAR(rec.drops[0].radius, 0.03f, kTol);
    EXPECT_NEAR(rec.drops[0].amp, 0.06f, kTol);

    for (int i = 0; i < ripple::OverlayDrops::kSparkleInterval; ++i) {
        drops.tick(frame(0.0f, 0.0f, 3.0f), 1.0f, rec.queue());
    }
    ASSERT_EQ(rec.drops.size(), 2u);
    EXPECT_NEAR(rec.drops[1].amp, 0.2f, kTol);
    EXPECT_NEAR(rec.drops[1].x, -0.49273097f, kTol);

    // At the threshold itself: no sparkle.
    for (int i = 0; i < ripple::OverlayDrops::kSparkleInterval; ++i) {
        drops.tick(frame(0.0f, 0.0f, ripple::OverlayDrops::kSparkleThreshold), 1.0f, rec.queue());
    }
    EXPECT_EQ(rec.drops.size(), 2u);
}

TEST(RippleOverlayDrops, ResetRestartsTheFrameCountAndPositions) {
    ripple::OverlayDrops drops;
    Recorder rec;
    for (int i = 0; i < 3; ++i) drops.tick(frame(0.0f, 0.0f, 0.6f), 1.0f, rec.queue());
    drops.tick(frame(0.5f, 0.5f, 0.0f), 1.0f, rec.queue());
    ASSERT_EQ(rec.drops.size(), 2u);
    drops.reset();
    rec.drops.clear();
    // Without the reset this frame would be the hit's continuation (no edge) and frame 5.
    drops.tick(frame(0.5f, 0.5f, 0.0f), 1.0f, rec.queue());
    ASSERT_EQ(rec.drops.size(), 2u);
    EXPECT_NEAR(rec.drops[0].x, 0.0f, kTol);
    EXPECT_NEAR(rec.drops[0].y, 0.0f, kTol);
    rec.drops.clear();
    for (int i = 1; i < ripple::OverlayDrops::kSparkleInterval - 1; ++i) {
        drops.tick(frame(0.0f, 0.0f, 0.6f), 1.0f, rec.queue());
    }
    EXPECT_TRUE(rec.drops.empty());
    drops.tick(frame(0.0f, 0.0f, 0.6f), 1.0f, rec.queue());
    EXPECT_EQ(rec.drops.size(), 1u);
}
