#include "geode/viz/RenderScale.hpp"

#include <gtest/gtest.h>

#include <cmath>

namespace scale = geode::viz::scale;

namespace {

constexpr float kFrameSeconds = 1.0f / 60.0f;
constexpr float kBudgetMs = 1000.0f / 60.0f;
constexpr int kFullWindow = 240;
// Comfortably over (one frame in twenty or more missing vsync) and comfortably within budget.
constexpr scale::FrameLoad kOver{kFullWindow, 33.3f};
constexpr scale::FrameLoad kWithin{kFullWindow, 16.7f};
constexpr scale::FrameLoad kSteady{kFullWindow, 19.5f};
constexpr scale::FrameLoad kTooFewFrames{10, 33.3f};

float run(scale::LoadScaleController& controller, float seconds, const scale::FrameLoad& load) {
    const int frames = static_cast<int>(std::lround(seconds / kFrameSeconds));
    float s = controller.scale();
    for (int i = 0; i < frames; ++i) s = controller.step(kFrameSeconds, load, kBudgetMs);
    return s;
}

}  // namespace

// ---- judging a frame window ----------------------------------------------------------------------

TEST(LoadVerdict, TooFewFramesIsUnknown) {
    EXPECT_EQ(scale::judge(kTooFewFrames, kBudgetMs, {}), scale::LoadVerdict::Unknown);
}

TEST(LoadVerdict, NoBudgetIsUnknown) {
    EXPECT_EQ(scale::judge(kOver, 0.0f, {}), scale::LoadVerdict::Unknown);
}

TEST(LoadVerdict, TailPastTheBudgetIsOver) {
    EXPECT_EQ(scale::judge(kOver, kBudgetMs, {}), scale::LoadVerdict::Over);
}

TEST(LoadVerdict, TailOnTimeIsWithin) {
    EXPECT_EQ(scale::judge(kWithin, kBudgetMs, {}), scale::LoadVerdict::Within);
}

TEST(LoadVerdict, InBetweenIsSteady) {
    EXPECT_EQ(scale::judge(kSteady, kBudgetMs, {}), scale::LoadVerdict::Steady);
}

TEST(FrameBudget, FollowsThePacedRate) {
    EXPECT_FLOAT_EQ(scale::frameBudgetMs(30.0f), 1000.0f / 30.0f);
}

TEST(FrameBudget, UncappedAssumesTheDefaultRate) {
    EXPECT_FLOAT_EQ(scale::frameBudgetMs(0.0f), 1000.0f / scale::kDefaultFrameRate);
}

// ---- the load scale and its hysteresis -----------------------------------------------------------

TEST(LoadScaleController, StartsAtFullScale) {
    scale::LoadScaleController controller;
    EXPECT_FLOAT_EQ(controller.scale(), 1.0f);
    EXPECT_EQ(controller.level(), 0);
}

TEST(LoadScaleController, StepsDownOnceOverBudgetForTheHold) {
    scale::LoadScaleController controller;
    run(controller, 4.5f, kOver);
    EXPECT_EQ(controller.level(), 0);
    run(controller, 0.7f, kOver);
    EXPECT_EQ(controller.level(), 1);
    EXPECT_NEAR(controller.scale(), 0.95f, 1e-5f);
}

TEST(LoadScaleController, HoldsBetweenTwoSteps) {
    scale::LoadScaleController controller;
    run(controller, 5.2f, kOver);
    ASSERT_EQ(controller.level(), 1);
    run(controller, 4.7f, kOver);
    EXPECT_EQ(controller.level(), 1);
    run(controller, 0.5f, kOver);
    EXPECT_EQ(controller.level(), 2);
}

TEST(LoadScaleController, NeverDropsBelowItsFloor) {
    scale::LoadScaleController controller;
    const float s = run(controller, 300.0f, kOver);
    EXPECT_NEAR(s, scale::LoadScaleConfig{}.minScale, 1e-5f);
}

TEST(LoadScaleController, StepsUpOnlyAfterTheLongerDwell) {
    scale::LoadScaleController controller;
    run(controller, 5.2f, kOver);
    ASSERT_EQ(controller.level(), 1);
    run(controller, 14.5f, kWithin);
    EXPECT_EQ(controller.level(), 1);
    run(controller, 1.0f, kWithin);
    EXPECT_EQ(controller.level(), 0);
    EXPECT_FLOAT_EQ(controller.scale(), 1.0f);
}

TEST(LoadScaleController, AlternatingLoadDoesNotFlip) {
    scale::LoadScaleController controller;
    for (int i = 0; i < 60; ++i) {
        run(controller, 1.0f, kOver);
        run(controller, 1.0f, kWithin);
    }
    EXPECT_EQ(controller.level(), 0);
}

TEST(LoadScaleController, UnknownAndSteadyReadingsHoldTheLevel) {
    scale::LoadScaleController controller;
    run(controller, 5.2f, kOver);
    ASSERT_EQ(controller.level(), 1);
    run(controller, 100.0f, kTooFewFrames);
    EXPECT_EQ(controller.level(), 1);
    run(controller, 100.0f, kSteady);
    EXPECT_EQ(controller.level(), 1);
}

TEST(LoadScaleController, AStepUpThatFailsBacksOffTheNextOne) {
    scale::LoadScaleController controller;
    const float firstUpDwell = controller.upDwellSeconds();
    run(controller, 5.2f, kOver);
    run(controller, 15.2f, kWithin);
    ASSERT_EQ(controller.level(), 0);
    run(controller, 5.2f, kOver);
    ASSERT_EQ(controller.level(), 1);
    EXPECT_FLOAT_EQ(controller.upDwellSeconds(), firstUpDwell * 2.0f);
    // The longer dwell now applies.
    run(controller, 20.0f, kWithin);
    EXPECT_EQ(controller.level(), 1);
    run(controller, 10.5f, kWithin);
    EXPECT_EQ(controller.level(), 0);
}

TEST(LoadScaleController, BackOffIsCapped) {
    scale::LoadScaleController controller;
    for (int i = 0; i < 20; ++i) {
        run(controller, 5.2f, kOver);
        run(controller, controller.upDwellSeconds() + 0.5f, kWithin);
    }
    EXPECT_LE(controller.upDwellSeconds(), scale::LoadScaleConfig{}.maxUpDwellSeconds);
}

TEST(LoadScaleController, ResetReturnsToFullScale) {
    scale::LoadScaleController controller;
    run(controller, 30.0f, kOver);
    ASSERT_GT(controller.level(), 0);
    controller.reset();
    EXPECT_EQ(controller.level(), 0);
    EXPECT_FLOAT_EQ(controller.upDwellSeconds(), scale::LoadScaleConfig{}.upDwellSeconds);
}

// ---- supersampling -------------------------------------------------------------------------------

TEST(LiveSupersample, SmallPanelsAreCappedAtTheMaximum) {
    EXPECT_FLOAT_EQ(scale::liveSupersample(720, 1600), scale::kMaxLiveSupersample);
}

TEST(LiveSupersample, LargePanelsDrawAtTheirOwnSize) {
    EXPECT_FLOAT_EQ(scale::liveSupersample(1440, 3200), scale::kMinLiveSupersample);
}

TEST(LiveSupersample, ASmallerPanelNeverDrawsMorePixels) {
    const auto drawn = [](int w, int h) {
        const float s = scale::liveSupersample(w, h);
        return static_cast<float>(w) * static_cast<float>(h) * s * s;
    };
    EXPECT_LE(drawn(1080, 2160), drawn(1080, 2400) * 1.01f);
    EXPECT_LE(drawn(1080, 2400), drawn(1440, 3200));
}

TEST(LiveSupersample, NoSurfaceIsOne) {
    EXPECT_FLOAT_EQ(scale::liveSupersample(0, 0), scale::kMinLiveSupersample);
}

// ---- the render size -----------------------------------------------------------------------------

TEST(RenderSize, RendersDrawAtExactlyTheExportSize) {
    scale::ScaleInputs in;
    in.surfaceWidth = 1280;
    in.surfaceHeight = 720;
    in.offscreen = true;
    in.styleScale = 0.6f;
    in.loadScale = 0.7f;
    in.thermalCeiling = 0.6f;
    EXPECT_EQ(scale::renderSize(in), (scale::RenderSize{1280, 720}));
}

TEST(RenderSize, ExportSupersampleIsANamedSettingWithinItsRange) {
    scale::ScaleInputs in;
    in.offscreen = true;
    in.exportSupersample = 1.5f;
    EXPECT_FLOAT_EQ(scale::totalScale(in), 1.5f);
    in.exportSupersample = 5.0f;
    EXPECT_FLOAT_EQ(scale::totalScale(in), scale::kMaxExportSupersample);
    in.exportSupersample = 0.25f;
    EXPECT_FLOAT_EQ(scale::totalScale(in), scale::kMinExportSupersample);
}

TEST(RenderSize, LiveTakesTheLowerOfLoadAndThermal) {
    scale::ScaleInputs in;
    in.surfaceWidth = 1440;
    in.surfaceHeight = 3200;
    in.loadScale = 0.9f;
    in.thermalCeiling = 0.7f;
    EXPECT_FLOAT_EQ(scale::totalScale(in), 0.7f);
    in.thermalCeiling = 1.0f;
    EXPECT_FLOAT_EQ(scale::totalScale(in), 0.9f);
}

TEST(RenderSize, StyleScaleMultiplies) {
    scale::ScaleInputs in;
    in.surfaceWidth = 1440;
    in.surfaceHeight = 3200;
    in.styleScale = 0.75f;
    EXPECT_FLOAT_EQ(scale::totalScale(in), 0.75f);
    EXPECT_EQ(scale::renderSize(in), (scale::RenderSize{1080, 2400}));
}

TEST(RenderSize, QualityNeverDropsBelowTheFloor) {
    scale::ScaleInputs in;
    in.surfaceWidth = 1440;
    in.surfaceHeight = 3200;
    in.styleScale = 0.5f;
    in.loadScale = 0.6f;
    EXPECT_FLOAT_EQ(scale::totalScale(in), scale::kMinQualityScale);
}

TEST(RenderSize, LayerTwoDrawsBothPicturesAtThreeQuartersOfTheSofterStyle) {
    scale::ScaleInputs in;
    in.surfaceWidth = 1440;
    in.surfaceHeight = 3200;
    in.styleScale = 1.0f;
    in.layered = true;
    in.layerStyleScale = 1.0f;
    EXPECT_FLOAT_EQ(scale::totalScale(in), 0.75f);
    in.layerStyleScale = 0.8f;
    EXPECT_FLOAT_EQ(scale::totalScale(in), 0.6f);
    in.styleScale = 0.9f;
    in.layerStyleScale = 1.0f;
    EXPECT_FLOAT_EQ(scale::totalScale(in), 0.675f);
    in.layered = false;
    EXPECT_FLOAT_EQ(scale::totalScale(in), 0.9f);
}

TEST(RenderSize, LayerTwoKeepsTheQualityFloorAndLeavesRendersAlone) {
    scale::ScaleInputs in;
    in.surfaceWidth = 1440;
    in.surfaceHeight = 3200;
    in.styleScale = 0.5f;
    in.layered = true;
    in.layerStyleScale = 0.5f;
    EXPECT_FLOAT_EQ(scale::totalScale(in), scale::kMinQualityScale);
    in.offscreen = true;
    EXPECT_EQ(scale::renderSize(in), (scale::RenderSize{1440, 3200}));
}

TEST(LayeredStyleScale, IsTheStyleScaleWithoutLayerTwo) {
    EXPECT_FLOAT_EQ(scale::layeredStyleScale(0.8f, false, 0.5f), 0.8f);
    EXPECT_FLOAT_EQ(scale::layeredStyleScale(0.8f, true, 0.5f), 0.5f * scale::kLayeredRenderScale);
    EXPECT_FLOAT_EQ(scale::layeredStyleScale(0.6f, true, 0.9f), 0.6f * scale::kLayeredRenderScale);
    EXPECT_FLOAT_EQ(scale::kLayeredRenderScale, 0.75f);
}

TEST(RenderSize, NeverZero) {
    scale::ScaleInputs in;
    in.surfaceWidth = 0;
    in.surfaceHeight = 0;
    const scale::RenderSize size = scale::renderSize(in);
    EXPECT_GE(size.width, 1);
    EXPECT_GE(size.height, 1);
}

// ---- the Style's declared render scale -----------------------------------------------------------

TEST(StyleRenderScale, KeepsTheDeclaredScale) {
    EXPECT_FLOAT_EQ(scale::styleRenderScale(0.75), 0.75f);
}

TEST(StyleRenderScale, DefaultsToOneWhenNothingIsDeclared) {
    EXPECT_FLOAT_EQ(scale::styleRenderScale(std::nullopt), scale::kDefaultStyleRenderScale);
    EXPECT_FLOAT_EQ(scale::kDefaultStyleRenderScale, 1.0f);
}

TEST(StyleRenderScale, DefaultsToOneWhenNotFinite) {
    EXPECT_FLOAT_EQ(scale::styleRenderScale(std::nan("")), scale::kDefaultStyleRenderScale);
    EXPECT_FLOAT_EQ(scale::styleRenderScale(HUGE_VAL), scale::kDefaultStyleRenderScale);
}

TEST(StyleRenderScale, ClampsToTheAllowedRange) {
    EXPECT_FLOAT_EQ(scale::styleRenderScale(0.1), scale::kMinStyleRenderScale);
    EXPECT_FLOAT_EQ(scale::styleRenderScale(3.0), scale::kMaxStyleRenderScale);
}
