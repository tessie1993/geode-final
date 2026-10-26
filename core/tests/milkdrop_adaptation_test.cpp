#include "geode/viz/MilkdropAdaptation.hpp"

#include <gtest/gtest.h>

namespace milkdrop = geode::viz::milkdrop;
using milkdrop::LoadAdapter;

namespace {

constexpr float kPaced = 60.0f;
constexpr float kOnPace = 1.0f / kPaced;
// Well under LoadAdapter::kBehindFraction of 60 fps.
constexpr float kSlow = 1.0f / 30.0f;
// Between the behind and the headroom thresholds of 60 fps.
constexpr float kBetween = 1.0f / 55.0f;

// Feeds frames of one interval for about the given time.
void run(LoadAdapter& adapter, float frameSeconds, float seconds, float pacedFps = kPaced) {
    const int frames = static_cast<int>(seconds / frameSeconds) + 1;
    for (int i = 0; i < frames; ++i) adapter.onFrame(frameSeconds, pacedFps);
}

// Settles the averaged rate on a new interval without crossing a step threshold.
void settle(LoadAdapter& adapter, float frameSeconds) {
    run(adapter, frameSeconds, 1.0f);
}

}  // namespace

TEST(MilkdropEngineFps, PacedRateWinsOverTheMeasuredOne) {
    EXPECT_EQ(milkdrop::engineFps(90.0f, 41.0f), 90);
    EXPECT_EQ(milkdrop::engineFps(59.94f, 0.0f), 60);
}

TEST(MilkdropEngineFps, FreeRunningUsesTheMeasuredRate) {
    EXPECT_EQ(milkdrop::engineFps(0.0f, 43.4f), 43);
    EXPECT_EQ(milkdrop::engineFps(0.0f, 43.6f), 44);
}

TEST(MilkdropEngineFps, NothingKnownFallsBackToTheDefault) {
    EXPECT_EQ(milkdrop::engineFps(0.0f, 0.0f), milkdrop::kDefaultEngineFps);
    EXPECT_EQ(milkdrop::engineFps(-1.0f, -1.0f), milkdrop::kDefaultEngineFps);
}

TEST(MilkdropEngineFps, StaysInRange) {
    EXPECT_EQ(milkdrop::engineFps(1000.0f, 0.0f), milkdrop::kMaxEngineFps);
    EXPECT_EQ(milkdrop::engineFps(0.0f, 0.2f), milkdrop::kMinEngineFps);
}

TEST(MilkdropRenderFps, IsTheRenderClocksRate) {
    EXPECT_EQ(milkdrop::renderFps(1.0f / 30.0f), 30);
    EXPECT_EQ(milkdrop::renderFps(1.0f / 24.0f), 24);
    EXPECT_EQ(milkdrop::renderFps(0.0f), milkdrop::kDefaultEngineFps);
}

TEST(MilkdropBufferSize, ScalesTheWindowAndRounds) {
    EXPECT_EQ(milkdrop::bufferSize(1080, 2400, 1.0f), (milkdrop::BufferSize{1080, 2400}));
    EXPECT_EQ(milkdrop::bufferSize(1080, 2400, 0.85f), (milkdrop::BufferSize{918, 2040}));
    EXPECT_EQ(milkdrop::bufferSize(101, 3, 0.5f), (milkdrop::BufferSize{51, 2}));
}

TEST(MilkdropBufferSize, NeverBelowOnePixel) {
    EXPECT_EQ(milkdrop::bufferSize(1, 1, 0.7f), (milkdrop::BufferSize{1, 1}));
    EXPECT_EQ(milkdrop::bufferSize(0, 0, 1.0f), (milkdrop::BufferSize{1, 1}));
}

TEST(MilkdropResizeNow, AnEngineWithNoSizeTakesOneAtOnce) {
    EXPECT_TRUE(milkdrop::resizeNow({0, 0}, {1080, 2400}));
    EXPECT_TRUE(milkdrop::resizeNow({0, 2400}, {1080, 2400}));
    EXPECT_TRUE(milkdrop::resizeNow({1080, 0}, {1080, 2400}));
}

TEST(MilkdropResizeNow, TheSameShapeWaitsForThePresetChange) {
    EXPECT_FALSE(milkdrop::resizeNow({1080, 2400}, {1080, 2400}));
    EXPECT_FALSE(milkdrop::resizeNow({1080, 2400}, {918, 2040}));
    EXPECT_FALSE(milkdrop::resizeNow({1000, 2000}, {1010, 2000}));
}

TEST(MilkdropResizeNow, ANewShapeResizesAtOnce) {
    EXPECT_TRUE(milkdrop::resizeNow({1080, 2400}, {2400, 1080}));
    EXPECT_TRUE(milkdrop::resizeNow({1000, 2000}, {1050, 2000}));
    EXPECT_TRUE(milkdrop::resizeNow({1050, 2000}, {1000, 2000}));
}

TEST(MilkdropPointer, NoFingerIsNoPointer) {
    EXPECT_EQ(milkdrop::pointerFromTouch(0, 0.3f, 0.4f), (milkdrop::Pointer{-1.0f, -1.0f, false}));
    EXPECT_EQ(milkdrop::pointerFromTouch(-1, 0.3f, 0.4f), (milkdrop::Pointer{-1.0f, -1.0f, false}));
}

TEST(MilkdropPointer, TheFirstFingerInUvWithYDown) {
    EXPECT_EQ(milkdrop::pointerFromTouch(1, -1.0f, 1.0f), (milkdrop::Pointer{0.0f, 0.0f, true}));
    EXPECT_EQ(milkdrop::pointerFromTouch(2, 1.0f, -1.0f), (milkdrop::Pointer{1.0f, 1.0f, true}));
    EXPECT_EQ(milkdrop::pointerFromTouch(1, 0.5f, 0.5f), (milkdrop::Pointer{0.75f, 0.25f, true}));
}

TEST(MilkdropPointer, StaysInsideThePicture) {
    EXPECT_EQ(milkdrop::pointerFromTouch(1, -3.0f, 3.0f), (milkdrop::Pointer{0.0f, 0.0f, true}));
    EXPECT_EQ(milkdrop::pointerFromTouch(1, 3.0f, -3.0f), (milkdrop::Pointer{1.0f, 1.0f, true}));
}

TEST(MilkdropLadders, StartAtFullDetailAndOnlyGetCoarser) {
    EXPECT_EQ(milkdrop::kMeshLadder.front(), (milkdrop::MeshSize{48, 32}));
    for (size_t i = 1; i < milkdrop::kMeshLadder.size(); ++i) {
        EXPECT_LT(milkdrop::kMeshLadder[i].columns, milkdrop::kMeshLadder[i - 1].columns);
        EXPECT_LT(milkdrop::kMeshLadder[i].rows, milkdrop::kMeshLadder[i - 1].rows);
    }
    EXPECT_FLOAT_EQ(milkdrop::kBufferScaleLadder.front(), 1.0f);
    for (size_t i = 1; i < milkdrop::kBufferScaleLadder.size(); ++i) {
        EXPECT_LT(milkdrop::kBufferScaleLadder[i], milkdrop::kBufferScaleLadder[i - 1]);
    }
}

TEST(MilkdropLoadAdapter, StartsAtFullDetail) {
    LoadAdapter adapter;
    EXPECT_EQ(adapter.meshStep(), 0);
    EXPECT_EQ(adapter.mesh(), milkdrop::kMeshLadder.front());
    EXPECT_FLOAT_EQ(adapter.bufferScale(), 1.0f);
    EXPECT_FALSE(adapter.bufferChangePending());
    EXPECT_FLOAT_EQ(adapter.averageFps(), 0.0f);
}

TEST(MilkdropLoadAdapter, KeepingUpChangesNothing) {
    LoadAdapter adapter;
    run(adapter, kOnPace, 30.0f);
    EXPECT_EQ(adapter.meshStep(), 0);
    EXPECT_FALSE(adapter.bufferChangePending());
    EXPECT_NEAR(adapter.averageFps(), kPaced, 0.5f);
}

TEST(MilkdropLoadAdapter, ABriefDeficitIsNotLoad) {
    LoadAdapter adapter;
    run(adapter, kSlow, LoadAdapter::kStepDownSeconds * 0.5f);
    run(adapter, kOnPace, 2.0f);
    run(adapter, kSlow, LoadAdapter::kStepDownSeconds * 0.5f);
    EXPECT_EQ(adapter.meshStep(), 0);
}

TEST(MilkdropLoadAdapter, SustainedDeficitLowersTheMeshFirst) {
    LoadAdapter adapter;
    run(adapter, kSlow, LoadAdapter::kStepDownSeconds + 0.5f);
    EXPECT_EQ(adapter.meshStep(), 1);
    EXPECT_EQ(adapter.mesh(), milkdrop::kMeshLadder[1]);
    EXPECT_FALSE(adapter.bufferChangePending());
    EXPECT_FLOAT_EQ(adapter.bufferScale(), 1.0f);
}

TEST(MilkdropLoadAdapter, OneStepPerSustainedDeficit) {
    LoadAdapter adapter;
    run(adapter, kSlow, LoadAdapter::kStepDownSeconds * 2.0f + 0.5f);
    EXPECT_EQ(adapter.meshStep(), 2);
}

TEST(MilkdropLoadAdapter, BufferStepWaitsForAPresetChange) {
    LoadAdapter adapter;
    const float allMeshSteps = LoadAdapter::kStepDownSeconds * static_cast<float>(milkdrop::kMeshLadder.size());
    run(adapter, kSlow, allMeshSteps + 0.5f);
    EXPECT_EQ(adapter.mesh(), milkdrop::kMeshLadder.back());
    EXPECT_TRUE(adapter.bufferChangePending());
    // Pending only: the buffer in use keeps its size until the preset changes.
    EXPECT_FLOAT_EQ(adapter.bufferScale(), 1.0f);
    EXPECT_FLOAT_EQ(adapter.pendingBufferScale(), milkdrop::kBufferScaleLadder[1]);
    EXPECT_TRUE(adapter.onPresetChange());
    EXPECT_FLOAT_EQ(adapter.bufferScale(), milkdrop::kBufferScaleLadder[1]);
    EXPECT_FALSE(adapter.bufferChangePending());
    EXPECT_FALSE(adapter.onPresetChange());
}

TEST(MilkdropLoadAdapter, NeverGoesBelowTheLastSteps) {
    LoadAdapter adapter;
    for (int i = 0; i < 20; ++i) {
        run(adapter, kSlow, LoadAdapter::kStepDownSeconds + 0.1f);
        adapter.onPresetChange();
    }
    EXPECT_EQ(adapter.mesh(), milkdrop::kMeshLadder.back());
    EXPECT_FLOAT_EQ(adapter.bufferScale(), milkdrop::kBufferScaleLadder.back());
}

TEST(MilkdropLoadAdapter, HeadroomRestoresDetailInReverseOrder) {
    LoadAdapter adapter;
    const float allMeshSteps = LoadAdapter::kStepDownSeconds * static_cast<float>(milkdrop::kMeshLadder.size());
    run(adapter, kSlow, allMeshSteps + 0.5f);
    adapter.onPresetChange();
    ASSERT_FLOAT_EQ(adapter.bufferScale(), milkdrop::kBufferScaleLadder[1]);
    const int coarsestMesh = adapter.meshStep();

    settle(adapter, kOnPace);
    run(adapter, kOnPace, LoadAdapter::kStepUpSeconds);
    // The buffer, given up last, comes back first - and again only at a preset change.
    EXPECT_EQ(adapter.meshStep(), coarsestMesh);
    EXPECT_TRUE(adapter.bufferChangePending());
    EXPECT_FLOAT_EQ(adapter.pendingBufferScale(), 1.0f);
    EXPECT_TRUE(adapter.onPresetChange());
    EXPECT_FLOAT_EQ(adapter.bufferScale(), 1.0f);

    run(adapter, kOnPace, LoadAdapter::kStepUpSeconds);
    EXPECT_EQ(adapter.meshStep(), coarsestMesh - 1);
}

TEST(MilkdropLoadAdapter, HoldsBetweenTheThresholds) {
    LoadAdapter adapter;
    run(adapter, kSlow, LoadAdapter::kStepDownSeconds + 0.5f);
    ASSERT_EQ(adapter.meshStep(), 1);
    run(adapter, kBetween, LoadAdapter::kStepUpSeconds * 3.0f);
    EXPECT_EQ(adapter.meshStep(), 1);
}

TEST(MilkdropLoadAdapter, FollowsThePacedRate) {
    // 30 fps is on pace for a display paced at 30, and behind for one paced at 60.
    LoadAdapter pacedAt30;
    run(pacedAt30, kSlow, LoadAdapter::kStepDownSeconds * 2.0f, 30.0f);
    EXPECT_EQ(pacedAt30.meshStep(), 0);
    LoadAdapter pacedAt60;
    run(pacedAt60, kSlow, LoadAdapter::kStepDownSeconds * 2.0f, 60.0f);
    EXPECT_GT(pacedAt60.meshStep(), 0);
}

TEST(MilkdropLoadAdapter, FreeRunningHoldsToItsOwnTarget) {
    const float target = LoadAdapter::kFreeRunningTargetFps;
    LoadAdapter keepingUp;
    run(keepingUp, 1.0f / target, LoadAdapter::kStepDownSeconds * 2.0f, 0.0f);
    EXPECT_EQ(keepingUp.meshStep(), 0);
    LoadAdapter behind;
    run(behind, 1.0f / (target * 0.5f), LoadAdapter::kStepDownSeconds * 2.0f, 0.0f);
    EXPECT_GT(behind.meshStep(), 0);
}

TEST(MilkdropLoadAdapter, StallsAreNotLoad) {
    LoadAdapter adapter;
    for (int i = 0; i < 100; ++i) adapter.onFrame(LoadAdapter::kMaxFrameSeconds * 2.0f, kPaced);
    adapter.onFrame(0.0f, kPaced);
    adapter.onFrame(-1.0f, kPaced);
    EXPECT_EQ(adapter.meshStep(), 0);
    EXPECT_FLOAT_EQ(adapter.averageFps(), 0.0f);
}

TEST(MilkdropLoadAdapter, AveragesTheFrameRate) {
    LoadAdapter adapter;
    adapter.onFrame(1.0f / 40.0f, kPaced);
    EXPECT_NEAR(adapter.averageFps(), 40.0f, 0.01f);
    adapter.onFrame(1.0f / 20.0f, kPaced);
    // One frame moves the average by kSmoothing of the difference, not all the way.
    const float expectedSeconds = 1.0f / 40.0f + LoadAdapter::kSmoothing * (1.0f / 20.0f - 1.0f / 40.0f);
    EXPECT_NEAR(adapter.averageFps(), 1.0f / expectedSeconds, 0.01f);
}

TEST(MilkdropLoadAdapter, ResetReturnsToFullDetail) {
    LoadAdapter adapter;
    run(adapter, kSlow, LoadAdapter::kStepDownSeconds * 6.0f);
    adapter.onPresetChange();
    adapter.reset();
    EXPECT_EQ(adapter.meshStep(), 0);
    EXPECT_FLOAT_EQ(adapter.bufferScale(), 1.0f);
    EXPECT_FALSE(adapter.bufferChangePending());
    EXPECT_FLOAT_EQ(adapter.averageFps(), 0.0f);
}

TEST(MilkdropRenderClock, SumsTheFrameIntervalsFromZero) {
    milkdrop::RenderClock clock;
    EXPECT_DOUBLE_EQ(clock.seconds(), 0.0);
    for (int i = 0; i < 30; ++i) clock.advance(1.0f / 30.0f);
    EXPECT_NEAR(clock.seconds(), 1.0, 1e-5);
    clock.reset();
    EXPECT_DOUBLE_EQ(clock.seconds(), 0.0);
}

TEST(MilkdropRenderClock, SameFramesGiveTheSameTimes) {
    milkdrop::RenderClock a;
    milkdrop::RenderClock b;
    const float intervals[] = {0.0333f, 0.0417f, 0.0333f, 0.0166f};
    for (float dt : intervals) {
        a.advance(dt);
        b.advance(dt);
        EXPECT_EQ(a.seconds(), b.seconds());
    }
}
