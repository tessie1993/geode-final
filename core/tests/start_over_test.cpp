#include "geode/viz/StartOver.hpp"

#include <gtest/gtest.h>

#include <cstdint>
#include <limits>

namespace startover = geode::viz::startover;
using startover::Frame;
using startover::Request;
using startover::Step;

namespace {

constexpr float kTol = 1e-6f;

Frame drawing() {
    Frame f;
    f.drawing = true;
    return f;
}

Frame built() {
    Frame f = drawing();
    f.built = true;
    return f;
}

Frame milkdrop() {
    Frame f = drawing();
    f.milkdrop = true;
    return f;
}

Frame changing(Frame f) {
    f.changing = true;
    return f;
}

Request asked() {
    Request r;
    r.ask();
    return r;
}

}  // namespace

TEST(StartOver, NothingAskedDoesNothing) {
    Request r;
    EXPECT_FALSE(r.asked());
    EXPECT_EQ(r.step(built()), Step::None);
    EXPECT_EQ(r.step(milkdrop()), Step::None);
    EXPECT_FALSE(r.asked());
}

TEST(StartOver, AStyleBuildsItsFreshCopyThenFadesToItOnALaterFrame) {
    Request r = asked();
    EXPECT_TRUE(r.asked());
    EXPECT_EQ(r.step(drawing()), Step::Build);
    // The build keeps the request: the fade is the next frame's.
    EXPECT_TRUE(r.asked());
    EXPECT_EQ(r.step(built()), Step::Fade);
    EXPECT_FALSE(r.asked());
    // Carried out once.
    EXPECT_EQ(r.step(built()), Step::None);
    EXPECT_EQ(r.step(drawing()), Step::None);
}

TEST(StartOver, ACopyBuiltAlreadyFadesAtOnce) {
    Request r = asked();
    EXPECT_EQ(r.step(built()), Step::Fade);
    EXPECT_FALSE(r.asked());
}

TEST(StartOver, MilkdropLoadsItsPresetAgainInsteadOfBuildingACopy) {
    Request r = asked();
    EXPECT_EQ(r.step(milkdrop()), Step::ReloadPreset);
    EXPECT_FALSE(r.asked());
    EXPECT_EQ(r.step(milkdrop()), Step::None);
}

TEST(StartOver, MilkdropReloadsEvenWithACopyClaimedBuilt) {
    Frame f = milkdrop();
    f.built = true;
    Request r = asked();
    EXPECT_EQ(r.step(f), Step::ReloadPreset);
}

TEST(StartOver, WaitsForAChangeOfStyleThenCarriesOn) {
    Request r = asked();
    EXPECT_EQ(r.step(changing(drawing())), Step::None);
    EXPECT_TRUE(r.asked());
    EXPECT_EQ(r.step(changing(built())), Step::None);
    EXPECT_EQ(r.step(changing(milkdrop())), Step::None);
    EXPECT_TRUE(r.asked());
    EXPECT_EQ(r.step(drawing()), Step::Build);
    EXPECT_EQ(r.step(changing(built())), Step::None);
    EXPECT_EQ(r.step(built()), Step::Fade);
}

TEST(StartOver, AskedWithNothingDrawnIsForgotten) {
    Request r = asked();
    EXPECT_EQ(r.step(Frame{}), Step::None);
    EXPECT_FALSE(r.asked());
    // Not carried out later, when a Style is drawn.
    EXPECT_EQ(r.step(built()), Step::None);
}

TEST(StartOver, NothingDrawnForgetsEvenWhileAChangeIsClaimed) {
    Frame f;
    f.changing = true;
    f.milkdrop = true;
    Request r = asked();
    EXPECT_EQ(r.step(f), Step::None);
    EXPECT_FALSE(r.asked());
}

TEST(StartOver, ManyTapsAreOneStartOver) {
    Request r;
    r.ask();
    r.ask();
    EXPECT_EQ(r.step(built()), Step::Fade);
    EXPECT_EQ(r.step(built()), Step::None);
}

TEST(StartOver, DropForgetsTheRequest) {
    Request r = asked();
    EXPECT_EQ(r.step(drawing()), Step::Build);
    r.drop();
    EXPECT_FALSE(r.asked());
    EXPECT_EQ(r.step(built()), Step::None);
    r.ask();
    EXPECT_EQ(r.step(built()), Step::Fade);
}

TEST(StartOver, TheFadeTakesThePersonsLengthWithinItsBounds) {
    EXPECT_NEAR(startover::fadeSeconds(1500), 1.5f, kTol);
    EXPECT_NEAR(startover::fadeSeconds(2250), 2.25f, kTol);
    EXPECT_NEAR(startover::fadeSeconds(1000), startover::kMinFadeSeconds, kTol);
    EXPECT_NEAR(startover::fadeSeconds(3000), startover::kMaxFadeSeconds, kTol);
    EXPECT_NEAR(startover::fadeSeconds(999), startover::kMinFadeSeconds, kTol);
    EXPECT_NEAR(startover::fadeSeconds(3001), startover::kMaxFadeSeconds, kTol);
    EXPECT_NEAR(startover::fadeSeconds(0), startover::kMinFadeSeconds, kTol);
    EXPECT_NEAR(startover::fadeSeconds(-5000), startover::kMinFadeSeconds, kTol);
    EXPECT_NEAR(startover::fadeSeconds(std::numeric_limits<int64_t>::max()), startover::kMaxFadeSeconds, kTol);
}

TEST(StartOver, TheBoundsNeitherSnapNorDrag) {
    EXPECT_FLOAT_EQ(startover::kMinFadeSeconds, 1.0f);
    EXPECT_FLOAT_EQ(startover::kMaxFadeSeconds, 3.0f);
}
