#include "geode/viz/FlashLimiter.hpp"

#include <gtest/gtest.h>

#include <algorithm>
#include <cmath>
#include <functional>
#include <vector>

namespace geode::viz {
namespace {

constexpr float kFrameDt = 1.0f / 60.0f;
constexpr int kFramesPerSecond = 60;
constexpr float kTolerance = 1e-5f;
// A 10 Hz square wave: the picture changes every 3 frames, 20 transitions a second.
constexpr int kStrobeHalfPeriodFrames = 3;
// The first 6 transitions of such a strobe land on frames 3, 6, ..., 18; the 7th would be frame 21.
constexpr int kFirstDampedStrobeFrame = 21;
// Area 0 is the whole frame; area 1 the bottom-left quarter (cells 0, 1, 4 and 5); area 5 the centre.
constexpr int kBottomLeftRegion = 1;
constexpr int kCentreRegion = 1 + 4;

bool strobeOn(int frame, int halfPeriodFrames) { return (frame / halfPeriodFrames) % 2 == 1; }

// Counts WCAG transitions in one measured series on its own, without the limiter's tracker: a
// transition is a move of at least `delta` against the last one's direction from the extreme
// reached since, with the darker state below `darkLimit`.
class IndependentCounter {
public:
    IndependentCounter(float delta, float darkLimit) : delta_(delta), darkLimit_(darkLimit) {}

    void feed(double time, float value) {
        if (!started_) {
            started_ = true;
            low_ = high_ = value;
            return;
        }
        const bool rises = direction_ != 1 && value - low_ >= delta_ && low_ < darkLimit_;
        const bool falls = direction_ != -1 && high_ - value >= delta_ && value < darkLimit_;
        if (rises || falls) {
            direction_ = rises ? 1 : -1;
            times_.push_back(time);
            low_ = high_ = value;
            return;
        }
        low_ = std::min(low_, value);
        high_ = std::max(high_, value);
    }

    int total() const { return static_cast<int>(times_.size()); }

    // The most transitions inside any closed window of `seconds`.
    int mostInAnyWindow(double seconds) const {
        int most = 0;
        for (size_t i = 0; i < times_.size(); ++i) {
            int n = 0;
            for (size_t j = i; j < times_.size() && times_[j] - times_[i] <= seconds; ++j) n++;
            most = std::max(most, n);
        }
        return most;
    }

private:
    float delta_;
    float darkLimit_;
    bool started_ = false;
    float low_ = 0.0f;
    float high_ = 0.0f;
    int direction_ = 0;
    std::vector<double> times_;
};

struct Run {
    std::vector<float> amounts;
    std::vector<LumaGrid> shown;
};

// Drives the limiter over `frames` synthetic frames and replays what it shows: the frame before,
// crossfaded toward the candidate by the returned amount, as NoFlashPass draws it.
Run drive(FlashLimiter& limiter, int frames, const std::function<LumaGrid(int)>& source) {
    Run run;
    LumaGrid shown;
    for (int i = 0; i < frames; ++i) {
        const LumaGrid candidate = source(i);
        const float amount = limiter.step(kFrameDt, candidate);
        if (i == 0) {
            shown = candidate;
        } else {
            for (int c = 0; c < noflash::kCellCount; ++c) {
                const size_t k = static_cast<size_t>(c);
                shown.luminance[k] += (candidate.luminance[k] - shown.luminance[k]) * amount;
                shown.red[k] += (candidate.red[k] - shown.red[k]) * amount;
            }
        }
        run.amounts.push_back(amount);
        run.shown.push_back(shown);
    }
    return run;
}

std::array<float, noflash::kAreaCount> means(const std::array<float, noflash::kCellCount>& cells) {
    std::array<float, noflash::kAreaCount> out{};
    FlashLimiter::areaMeans(cells, out);
    return out;
}

// Transitions the shown frames make in one area, counted independently.
IndependentCounter countGeneral(const Run& run, int area) {
    IndependentCounter counter(noflash::kGeneralFlashDelta, noflash::kGeneralFlashDarkLimit);
    for (size_t i = 0; i < run.shown.size(); ++i) {
        counter.feed(static_cast<double>(i) * kFrameDt, means(run.shown[i].luminance)[static_cast<size_t>(area)]);
    }
    return counter;
}

IndependentCounter countRed(const Run& run, int area) {
    IndependentCounter counter(noflash::kRedFlashDelta, noflash::kNoDarkLimit);
    for (size_t i = 0; i < run.shown.size(); ++i) {
        counter.feed(static_cast<double>(i) * kFrameDt, means(run.shown[i].red)[static_cast<size_t>(area)]);
    }
    return counter;
}

bool anyDamped(const Run& run) {
    return std::any_of(run.amounts.begin(), run.amounts.end(), [](float a) { return a < 1.0f; });
}

bool noneDamped(const Run& run) {
    return std::all_of(run.amounts.begin(), run.amounts.end(), [](float a) { return a == 1.0f; });
}

// ---- the pixel measures ---------------------------------------------------------------------

TEST(NoFlashMeasure, SrgbDecodesWithTheStandardCurve) {
    EXPECT_FLOAT_EQ(noflash::srgbToLinear(0.0f), 0.0f);
    EXPECT_FLOAT_EQ(noflash::srgbToLinear(1.0f), 1.0f);
    EXPECT_FLOAT_EQ(noflash::srgbToLinear(0.04045f), 0.04045f / 12.92f);
    EXPECT_NEAR(noflash::srgbToLinear(0.5f), 0.2140411f, kTolerance);
    EXPECT_FLOAT_EQ(noflash::srgbToLinear(-0.5f), 0.0f);
    EXPECT_FLOAT_EQ(noflash::srgbToLinear(2.0f), 1.0f);
}

TEST(NoFlashMeasure, RelativeLuminanceWeighsTheChannels) {
    EXPECT_NEAR(noflash::relativeLuminance(1.0f, 1.0f, 1.0f), 1.0f, kTolerance);
    EXPECT_FLOAT_EQ(noflash::relativeLuminance(1.0f, 0.0f, 0.0f), 0.2126f);
    EXPECT_FLOAT_EQ(noflash::relativeLuminance(0.0f, 1.0f, 0.0f), 0.7152f);
    EXPECT_FLOAT_EQ(noflash::relativeLuminance(0.0f, 0.0f, 1.0f), 0.0722f);
}

TEST(NoFlashMeasure, SaturatedRedOnlyCountsRedThatDominates) {
    EXPECT_FLOAT_EQ(noflash::saturatedRed(1.0f, 0.0f, 0.0f), 1.0f);
    EXPECT_NEAR(noflash::saturatedRed(0.9f, 0.05f, 0.05f), 0.8f, kTolerance);
    EXPECT_FLOAT_EQ(noflash::saturatedRed(0.7f, 0.2f, 0.1f), 0.0f);
    EXPECT_FLOAT_EQ(noflash::saturatedRed(0.0f, 0.0f, 0.0f), 0.0f);
    EXPECT_FLOAT_EQ(noflash::saturatedRed(0.0f, 1.0f, 0.0f), 0.0f);
}

TEST(NoFlashMeasure, ReadbackTakesRedFromRAndLuminanceFromG) {
    std::array<uint8_t, noflash::kReadbackBytes> bytes{};
    bytes[3 * noflash::kReadbackChannels + noflash::kReadbackRedChannel] = 51;
    bytes[3 * noflash::kReadbackChannels + noflash::kReadbackLumaChannel] = 255;
    bytes[3 * noflash::kReadbackChannels + 2] = 255;
    const LumaGrid grid = LumaGrid::fromReadback(bytes);
    EXPECT_FLOAT_EQ(grid.red[3], 0.2f);
    EXPECT_FLOAT_EQ(grid.luminance[3], 1.0f);
    EXPECT_FLOAT_EQ(grid.red[2], 0.0f);
    EXPECT_FLOAT_EQ(grid.luminance[4], 0.0f);
}

TEST(NoFlashMeasure, AreasAreTheWholeFrameThenEveryQuarterRegion) {
    std::array<float, noflash::kCellCount> cells{};
    for (int i = 0; i < noflash::kCellCount; ++i) cells[static_cast<size_t>(i)] = static_cast<float>(i);
    const auto areas = means(cells);
    ASSERT_EQ(noflash::kAreaCount, 10);
    EXPECT_FLOAT_EQ(areas[0], 7.5f);
    EXPECT_FLOAT_EQ(areas[1], 2.5f);   // cells 0, 1, 4, 5
    EXPECT_FLOAT_EQ(areas[2], 3.5f);   // cells 1, 2, 5, 6
    EXPECT_FLOAT_EQ(areas[4], 6.5f);   // cells 4, 5, 8, 9
    EXPECT_FLOAT_EQ(areas[5], 7.5f);   // the centre: cells 5, 6, 9, 10
    EXPECT_FLOAT_EQ(areas[9], 12.5f);  // cells 10, 11, 14, 15
}

// ---- the transition rule --------------------------------------------------------------------

TEST(TransitionTracker, ATenthOfFullLuminanceIsATransition) {
    TransitionTracker t(noflash::kGeneralFlashDelta, noflash::kGeneralFlashDarkLimit);
    t.reset(0.0f);
    EXPECT_FALSE(t.feed(0.0999f));
    EXPECT_TRUE(t.feed(0.1f));
    EXPECT_EQ(t.last(), TransitionTracker::Direction::Rise);
    EXPECT_FALSE(t.feed(0.75f));
    EXPECT_FALSE(t.feed(0.6501f));
    EXPECT_TRUE(t.feed(0.65f));
    EXPECT_EQ(t.last(), TransitionTracker::Direction::Fall);
}

TEST(TransitionTracker, ChangesAmongBrightStatesAreNotFlashes) {
    TransitionTracker t(noflash::kGeneralFlashDelta, noflash::kGeneralFlashDarkLimit);
    t.reset(0.8f);
    EXPECT_FALSE(t.feed(1.0f));
    EXPECT_FALSE(t.feed(0.8f));
    EXPECT_FALSE(t.feed(1.0f));
    EXPECT_TRUE(t.feed(0.79f));
    t.reset(0.79f);
    EXPECT_TRUE(t.feed(0.9f));
}

TEST(TransitionTracker, BandIsWhatTheNextValueMayReachWithoutATransition) {
    TransitionTracker t(noflash::kGeneralFlashDelta, noflash::kGeneralFlashDarkLimit);
    t.reset(0.3f);
    EXPECT_FLOAT_EQ(t.ceiling(), 0.3f + 0.1f);
    EXPECT_FLOAT_EQ(t.floor(), 0.3f - 0.1f);
    EXPECT_TRUE(t.feed(0.5f));
    EXPECT_TRUE(std::isinf(t.ceiling()));
    EXPECT_FLOAT_EQ(t.floor(), 0.5f - 0.1f);
    EXPECT_TRUE(t.feed(0.2f));
    EXPECT_TRUE(std::isinf(t.floor()));
    EXPECT_FLOAT_EQ(t.ceiling(), 0.2f + 0.1f);
    t.reset(0.95f);
    EXPECT_FLOAT_EQ(t.floor(), noflash::kGeneralFlashDarkLimit);
    EXPECT_TRUE(std::isinf(t.ceiling()));
}

TEST(TransitionTracker, RedHasNoDarkLimit) {
    TransitionTracker t(noflash::kRedFlashDelta, noflash::kNoDarkLimit);
    t.reset(0.9f);
    EXPECT_TRUE(t.feed(0.9f + noflash::kRedFlashDelta));
    EXPECT_FLOAT_EQ(noflash::kRedFlashDelta, 0.0625f);
}

// ---- strobing input stays within three flashes a second ---------------------------------------

TEST(FlashLimiter, WholeFrameStrobeIsHeldToThreeFlashesPerSecond) {
    FlashLimiter limiter;
    const Run run = drive(limiter, 4 * kFramesPerSecond, [](int i) {
        return LumaGrid::uniform(strobeOn(i, kStrobeHalfPeriodFrames) ? 1.0f : 0.0f);
    });
    for (int area = 0; area < noflash::kAreaCount; ++area) {
        const IndependentCounter shown = countGeneral(run, area);
        EXPECT_LE(shown.mostInAnyWindow(noflash::kWindowSeconds), noflash::kMaxTransitionsPerWindow) << "area " << area;
        EXPECT_GE(shown.total(), noflash::kMaxTransitionsPerWindow) << "area " << area;
    }
    EXPECT_EQ(countGeneral(run, noflash::kWholeFrame).mostInAnyWindow(noflash::kWindowSeconds), noflash::kMaxTransitionsPerWindow);
    // The first three flashes pass untouched; the seventh transition is held just short of 0.1.
    for (int i = 0; i < kFirstDampedStrobeFrame; ++i) EXPECT_EQ(run.amounts[static_cast<size_t>(i)], 1.0f) << "frame " << i;
    EXPECT_NEAR(run.amounts[kFirstDampedStrobeFrame], noflash::kGeneralFlashDelta - noflash::kBandMargin, kTolerance);
    EXPECT_NEAR(means(run.shown[kFirstDampedStrobeFrame].luminance)[0], 0.098f, kTolerance);
}

TEST(FlashLimiter, StrobeInOneRegionIsCaughtEvenWhenTheWholeFrameBarelyMoves) {
    FlashLimiter limiter;
    // Only cell 0 strobes, full swing, over grey: the whole frame moves by 1/16, under 0.1.
    constexpr float kGrey = 0.3f;
    const Run run = drive(limiter, 4 * kFramesPerSecond, [&](int i) {
        LumaGrid g = LumaGrid::uniform(kGrey);
        g.luminance[0] = strobeOn(i, kStrobeHalfPeriodFrames) ? 1.0f : 0.0f;
        return g;
    });
    EXPECT_EQ(countGeneral(run, noflash::kWholeFrame).total(), 0);
    const IndependentCounter region = countGeneral(run, kBottomLeftRegion);
    EXPECT_LE(region.mostInAnyWindow(noflash::kWindowSeconds), noflash::kMaxTransitionsPerWindow);
    EXPECT_EQ(region.mostInAnyWindow(noflash::kWindowSeconds), noflash::kMaxTransitionsPerWindow);
    EXPECT_TRUE(anyDamped(run));
    for (int i = 0; i < kFirstDampedStrobeFrame; ++i) EXPECT_EQ(run.amounts[static_cast<size_t>(i)], 1.0f) << "frame " << i;
    // Region mean 0.225 (cell 0 dark) toward 0.475: 0.098 of room over a 0.25 change.
    EXPECT_NEAR(run.amounts[kFirstDampedStrobeFrame], (noflash::kGeneralFlashDelta - noflash::kBandMargin) / 0.25f, kTolerance);
}

TEST(FlashLimiter, StrobeInTheCentreQuarterIsHeldInEveryAreaItTouches) {
    FlashLimiter limiter;
    const Run run = drive(limiter, 4 * kFramesPerSecond, [](int i) {
        LumaGrid g = LumaGrid::uniform(0.1f);
        const float v = strobeOn(i, kStrobeHalfPeriodFrames) ? 1.0f : 0.1f;
        for (const int cell : {5, 6, 9, 10}) g.luminance[static_cast<size_t>(cell)] = v;
        return g;
    });
    EXPECT_TRUE(anyDamped(run));
    for (int area = 0; area < noflash::kAreaCount; ++area) {
        EXPECT_LE(countGeneral(run, area).mostInAnyWindow(noflash::kWindowSeconds), noflash::kMaxTransitionsPerWindow) << "area " << area;
    }
    EXPECT_EQ(countGeneral(run, kCentreRegion).mostInAnyWindow(noflash::kWindowSeconds), noflash::kMaxTransitionsPerWindow);
}

TEST(FlashLimiter, RedStrobeIsHeldEvenWhenLuminanceIsSteady) {
    FlashLimiter limiter;
    constexpr float kRed = 0.3f;
    const Run run = drive(limiter, 4 * kFramesPerSecond, [&](int i) {
        return LumaGrid::uniform(0.2f, strobeOn(i, kStrobeHalfPeriodFrames) ? kRed : 0.0f);
    });
    EXPECT_EQ(countGeneral(run, noflash::kWholeFrame).total(), 0);
    for (int area = 0; area < noflash::kAreaCount; ++area) {
        EXPECT_LE(countRed(run, area).mostInAnyWindow(noflash::kWindowSeconds), noflash::kMaxTransitionsPerWindow) << "area " << area;
    }
    EXPECT_EQ(countRed(run, noflash::kWholeFrame).mostInAnyWindow(noflash::kWindowSeconds), noflash::kMaxTransitionsPerWindow);
    EXPECT_NEAR(run.amounts[kFirstDampedStrobeFrame], (noflash::kRedFlashDelta - noflash::kBandMargin) / kRed, kTolerance);
}

TEST(FlashLimiter, FastStrobeAtEveryFrameIsHeldToo) {
    FlashLimiter limiter;
    const Run run = drive(limiter, 3 * kFramesPerSecond, [](int i) { return LumaGrid::uniform(i % 2 == 1 ? 1.0f : 0.0f); });
    EXPECT_LE(countGeneral(run, noflash::kWholeFrame).mostInAnyWindow(noflash::kWindowSeconds), noflash::kMaxTransitionsPerWindow);
    EXPECT_EQ(limiter.transitionsInWindow(), noflash::kMaxTransitionsPerWindow);
}

TEST(FlashLimiter, BudgetComesBackOnceTheOldestTransitionIsASecondOld) {
    FlashLimiter limiter;
    // Six transitions on frames 3..18, then steady dark for well over a second, then one more rise.
    constexpr int kQuietUntil = 3 * kFramesPerSecond;
    const Run run = drive(limiter, kQuietUntil + 1, [&](int i) {
        if (i < kFirstDampedStrobeFrame) return LumaGrid::uniform(strobeOn(i, kStrobeHalfPeriodFrames) ? 1.0f : 0.0f);
        return LumaGrid::uniform(i == kQuietUntil ? 1.0f : 0.0f);
    });
    EXPECT_EQ(run.amounts.back(), 1.0f);
    EXPECT_TRUE(noneDamped(run));
    EXPECT_EQ(limiter.transitionsInWindow(), 1);
}

TEST(FlashLimiter, ResetForgetsTheBudget) {
    FlashLimiter limiter;
    drive(limiter, kFramesPerSecond, [](int i) { return LumaGrid::uniform(strobeOn(i, kStrobeHalfPeriodFrames) ? 1.0f : 0.0f); });
    EXPECT_EQ(limiter.transitionsInWindow(), noflash::kMaxTransitionsPerWindow);
    limiter.reset();
    EXPECT_EQ(limiter.transitionsInWindow(), 0);
    EXPECT_EQ(limiter.step(kFrameDt, LumaGrid::uniform(1.0f)), 1.0f);
    EXPECT_EQ(limiter.step(kFrameDt, LumaGrid::uniform(0.0f)), 1.0f);
    EXPECT_FLOAT_EQ(limiter.shown().luminance[0], 0.0f);
}

// ---- normal motion is never damped ----------------------------------------------------------

TEST(FlashLimiter, TwoFlashesASecondPassUntouched) {
    FlashLimiter limiter;
    // Full swing every 15 frames: four transitions a second, within the six allowed.
    const Run run = drive(limiter, 5 * kFramesPerSecond, [](int i) { return LumaGrid::uniform(strobeOn(i, 15) ? 1.0f : 0.0f); });
    EXPECT_TRUE(noneDamped(run));
    EXPECT_EQ(countGeneral(run, noflash::kWholeFrame).mostInAnyWindow(noflash::kWindowSeconds), 4);
}

TEST(FlashLimiter, SlowSwellPassesUntouched) {
    FlashLimiter limiter;
    constexpr float kSwellHz = 0.5f;
    const Run run = drive(limiter, 6 * kFramesPerSecond, [&](int i) {
        const float t = static_cast<float>(i) * kFrameDt;
        return LumaGrid::uniform(0.45f + 0.4f * std::sin(6.2831853f * kSwellHz * t));
    });
    EXPECT_TRUE(noneDamped(run));
}

TEST(FlashLimiter, BrightObjectSweepingAcrossPassesUntouched) {
    FlashLimiter limiter;
    // One white cell on a dark ground, moving one cell every 8 frames along the bottom row.
    constexpr int kFramesPerCell = 8;
    const Run run = drive(limiter, 6 * kFramesPerSecond, [&](int i) {
        LumaGrid g = LumaGrid::uniform(0.05f);
        g.luminance[static_cast<size_t>((i / kFramesPerCell) % noflash::kGridColumns)] = 1.0f;
        return g;
    });
    EXPECT_TRUE(noneDamped(run));
    EXPECT_GT(countGeneral(run, kBottomLeftRegion).total(), 0);
}

TEST(FlashLimiter, FlickerUnderTheThresholdPassesUntouched) {
    FlashLimiter limiter;
    const Run run = drive(limiter, 3 * kFramesPerSecond, [](int i) { return LumaGrid::uniform(i % 2 == 1 ? 0.38f : 0.3f); });
    EXPECT_TRUE(noneDamped(run));
}

TEST(FlashLimiter, FlickerAmongBrightStatesPassesUntouched) {
    FlashLimiter limiter;
    const Run run = drive(limiter, 3 * kFramesPerSecond, [](int i) { return LumaGrid::uniform(strobeOn(i, kStrobeHalfPeriodFrames) ? 1.0f : 0.85f); });
    EXPECT_TRUE(noneDamped(run));
}

TEST(FlashLimiter, FirstFrameIsShownAsItIs) {
    FlashLimiter limiter;
    EXPECT_EQ(limiter.step(kFrameDt, LumaGrid::uniform(0.7f, 0.2f)), 1.0f);
    EXPECT_FLOAT_EQ(limiter.shown().luminance[5], 0.7f);
    EXPECT_FLOAT_EQ(limiter.shown().red[5], 0.2f);
    EXPECT_EQ(limiter.transitionsInWindow(), 0);
}

}  // namespace
}  // namespace geode::viz
