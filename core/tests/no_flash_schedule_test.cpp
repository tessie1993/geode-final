// DelayedPresent: the No-flash pass reads each frame's measurement a frame late, and shows each
// frame one frame late, so the limiter still judges every frame before any of it is seen.
#include <gtest/gtest.h>

#include <algorithm>
#include <cmath>
#include <vector>

#include "geode/viz/FlashLimiter.hpp"

namespace geode::viz {
namespace {

using Action = DelayedPresent::Action;

constexpr float kFrameDt = 1.0f / 60.0f;
constexpr int kFramesPerSecond = 60;

TEST(DelayedPresent, FirstFrameIsMeasuredAndShownAtOnce) {
    DelayedPresent schedule;
    EXPECT_FALSE(schedule.hasShown());
    const DelayedPresent::Step step = schedule.onCandidate(0.02f, false);
    EXPECT_EQ(step.action, Action::MeasureAndShow);
    EXPECT_FLOAT_EQ(step.dt, 0.02f);
    EXPECT_FALSE(step.droppedPending);
    EXPECT_TRUE(schedule.hasShown());
    EXPECT_FALSE(schedule.hasPending());
}

TEST(DelayedPresent, SecondFrameHoldsTheFirstWhileItsOwnMeasurementTravels) {
    DelayedPresent schedule;
    schedule.onCandidate(0.02f, false);
    const DelayedPresent::Step step = schedule.onCandidate(0.03f, false);
    EXPECT_EQ(step.action, Action::HoldShown);
    EXPECT_FLOAT_EQ(step.dt, 0.0f);
    EXPECT_FALSE(step.droppedPending);
    EXPECT_TRUE(schedule.hasPending());
}

TEST(DelayedPresent, FromThenOnEachFrameShowsTheOneBeforeWithItsOwnTime) {
    DelayedPresent schedule;
    schedule.onCandidate(0.02f, false);
    schedule.onCandidate(0.03f, false);
    const float dts[] = {0.016f, 0.017f, 0.05f, 0.016f};
    float previous = 0.03f;
    for (const float dt : dts) {
        const DelayedPresent::Step step = schedule.onCandidate(dt, false);
        EXPECT_EQ(step.action, Action::ShowPending);
        EXPECT_FLOAT_EQ(step.dt, previous);
        EXPECT_FALSE(step.droppedPending);
        EXPECT_TRUE(schedule.hasPending());
        previous = dt;
    }
}

TEST(DelayedPresent, SynchronousDropsThePendingFrameAndCountsItsTime) {
    DelayedPresent schedule;
    schedule.onCandidate(0.02f, false);
    schedule.onCandidate(0.03f, false);
    const DelayedPresent::Step step = schedule.onCandidate(0.04f, true);
    EXPECT_EQ(step.action, Action::MeasureAndShow);
    EXPECT_FLOAT_EQ(step.dt, 0.07f);
    EXPECT_TRUE(step.droppedPending);
    EXPECT_FALSE(schedule.hasPending());
    const DelayedPresent::Step next = schedule.onCandidate(0.05f, true);
    EXPECT_EQ(next.action, Action::MeasureAndShow);
    EXPECT_FLOAT_EQ(next.dt, 0.05f);
    EXPECT_FALSE(next.droppedPending);
}

TEST(DelayedPresent, BackFromSynchronousItHoldsOnceThenRunsLate) {
    DelayedPresent schedule;
    schedule.onCandidate(0.02f, true);
    EXPECT_EQ(schedule.onCandidate(0.02f, false).action, Action::HoldShown);
    EXPECT_EQ(schedule.onCandidate(0.02f, false).action, Action::ShowPending);
}

TEST(DelayedPresent, ResetStartsAgainFromNothing) {
    DelayedPresent schedule;
    schedule.onCandidate(0.02f, false);
    schedule.onCandidate(0.03f, false);
    schedule.reset();
    EXPECT_FALSE(schedule.hasShown());
    EXPECT_FALSE(schedule.hasPending());
    const DelayedPresent::Step step = schedule.onCandidate(0.04f, false);
    EXPECT_EQ(step.action, Action::MeasureAndShow);
    EXPECT_FLOAT_EQ(step.dt, 0.04f);
    EXPECT_FALSE(step.droppedPending);
}

// ---- the cap still holds with the measurement a frame late ---------------------------------

// The whole-frame luminance NoFlashPass puts on screen each frame, replayed the way it draws it.
std::vector<float> screen(int frames, bool synchronous, float (*candidateAt)(int)) {
    DelayedPresent schedule;
    FlashLimiter limiter;
    std::vector<float> out;
    float shown = 0.0f;
    float pending = 0.0f;
    for (int i = 0; i < frames; ++i) {
        const float candidate = candidateAt(i);
        const DelayedPresent::Step step = schedule.onCandidate(kFrameDt, synchronous);
        float judged = shown;
        bool judges = true;
        switch (step.action) {
            case Action::MeasureAndShow: judged = candidate; break;
            case Action::ShowPending: judged = pending; break;
            case Action::HoldShown: judges = false; break;
        }
        if (judges) {
            const float amount = limiter.step(step.dt, LumaGrid::uniform(judged));
            shown = i == 0 ? judged : shown + (judged - shown) * amount;
        }
        if (step.action != Action::MeasureAndShow) pending = candidate;
        out.push_back(shown);
    }
    return out;
}

int mostTransitionsInAnySecond(const std::vector<float>& series) {
    std::vector<int> at;
    float low = series.front();
    float high = series.front();
    int direction = 0;
    for (size_t i = 1; i < series.size(); ++i) {
        const float v = series[i];
        const bool rises = direction != 1 && v - low >= noflash::kGeneralFlashDelta && low < noflash::kGeneralFlashDarkLimit;
        const bool falls = direction != -1 && high - v >= noflash::kGeneralFlashDelta && v < noflash::kGeneralFlashDarkLimit;
        if (rises || falls) {
            direction = rises ? 1 : -1;
            at.push_back(static_cast<int>(i));
            low = high = v;
            continue;
        }
        low = std::min(low, v);
        high = std::max(high, v);
    }
    // A second of frames at 60 Hz is a hair over the limiter's one-second window, so the window
    // spans one frame fewer.
    int most = 0;
    for (size_t i = 0; i < at.size(); ++i) {
        int n = 0;
        for (size_t j = i; j < at.size() && at[j] - at[i] < kFramesPerSecond; ++j) n++;
        most = std::max(most, n);
    }
    return most;
}

float strobe(int frame) { return (frame / 3) % 2 == 1 ? 1.0f : 0.0f; }
float swell(int frame) { return 0.5f + 0.4f * std::sin(static_cast<float>(frame) * 0.05f); }

TEST(DelayedPresent, StrobeIsStillHeldToThreeFlashesPerSecond) {
    for (const bool synchronous : {false, true}) {
        const std::vector<float> shown = screen(4 * kFramesPerSecond, synchronous, strobe);
        EXPECT_LE(mostTransitionsInAnySecond(shown), noflash::kMaxTransitionsPerWindow) << "synchronous " << synchronous;
        EXPECT_GE(mostTransitionsInAnySecond(shown), noflash::kMaxTransitionsPerWindow) << "synchronous " << synchronous;
    }
}

TEST(DelayedPresent, LateFramesAreTheCandidatesOneFrameOn) {
    const std::vector<float> late = screen(kFramesPerSecond, false, swell);
    const std::vector<float> now = screen(kFramesPerSecond, true, swell);
    // Frame 1 holds frame 0; after that each frame shows what the synchronous path showed a frame before.
    EXPECT_FLOAT_EQ(late[0], now[0]);
    EXPECT_FLOAT_EQ(late[1], now[0]);
    for (int i = 2; i < kFramesPerSecond; ++i) EXPECT_FLOAT_EQ(late[static_cast<size_t>(i)], now[static_cast<size_t>(i - 1)]) << "frame " << i;
}

}  // namespace
}  // namespace geode::viz
