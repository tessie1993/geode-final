#include "geode/audio/feed/FeatureHistory.hpp"

#include <gtest/gtest.h>

#include <cstdint>

using geode::audio::feed::FeatureHistory;

namespace {

GeodeFeatureFrame frameWith(float rms) {
    GeodeFeatureFrame frame{};
    frame.rms = rms;
    return frame;
}

}  // namespace

TEST(FeatureHistoryTest, AnEmptyHistoryHasNothingToShow) {
    FeatureHistory history;
    GeodeFeatureFrame out = frameWith(9.0f);
    EXPECT_FALSE(history.at(0, 1000, out));
    EXPECT_EQ(out.rms, 9.0f);
    EXPECT_EQ(history.size(), 0u);
}

TEST(FeatureHistoryTest, TheNewestFrameTheListenerHasReachedIsShown) {
    FeatureHistory history;
    history.publish(1, 100, frameWith(0.1f));
    history.publish(1, 200, frameWith(0.2f));
    history.publish(1, 300, frameWith(0.3f));
    GeodeFeatureFrame out{};
    ASSERT_TRUE(history.at(1, 250, out));
    EXPECT_FLOAT_EQ(out.rms, 0.2f);
    ASSERT_TRUE(history.at(1, 300, out));
    EXPECT_FLOAT_EQ(out.rms, 0.3f);
    ASSERT_TRUE(history.at(1, 100000, out));
    EXPECT_FLOAT_EQ(out.rms, 0.3f);
    ASSERT_TRUE(history.at(1, 100, out));
    EXPECT_FLOAT_EQ(out.rms, 0.1f);
    EXPECT_FALSE(history.at(1, 99, out));
    EXPECT_EQ(history.size(), 3u);
}

TEST(FeatureHistoryTest, FramesOfAnotherEpochAreNeverShown) {
    FeatureHistory history;
    history.publish(1, 100, frameWith(0.1f));
    history.publish(2, 150, frameWith(0.2f));
    history.publish(1, 160, frameWith(0.3f));
    GeodeFeatureFrame out{};
    ASSERT_TRUE(history.at(2, 1000, out));
    EXPECT_FLOAT_EQ(out.rms, 0.2f);
    ASSERT_TRUE(history.at(1, 155, out));
    EXPECT_FLOAT_EQ(out.rms, 0.1f);
    EXPECT_FALSE(history.at(3, 1000, out));
}

TEST(FeatureHistoryTest, TheOldestFramesMakeWayForNewOnes) {
    FeatureHistory history;
    const uint64_t extra = 5;
    for (uint64_t i = 0; i < FeatureHistory::kSlots + extra; ++i) history.publish(1, i * 10, frameWith(static_cast<float>(i)));
    EXPECT_EQ(history.size(), FeatureHistory::kSlots);
    GeodeFeatureFrame out{};
    EXPECT_FALSE(history.at(1, (extra - 1) * 10, out));
    ASSERT_TRUE(history.at(1, extra * 10, out));
    EXPECT_FLOAT_EQ(out.rms, static_cast<float>(extra));
    ASSERT_TRUE(history.at(1, (FeatureHistory::kSlots + extra) * 10, out));
    EXPECT_FLOAT_EQ(out.rms, static_cast<float>(FeatureHistory::kSlots + extra - 1));
}

TEST(FeatureHistoryTest, ClearingForgetsEverything) {
    FeatureHistory history;
    history.publish(1, 100, frameWith(0.1f));
    history.clear();
    GeodeFeatureFrame out{};
    EXPECT_FALSE(history.at(1, 1000, out));
    EXPECT_EQ(history.size(), 0u);
    history.publish(1, 200, frameWith(0.2f));
    ASSERT_TRUE(history.at(1, 1000, out));
    EXPECT_FLOAT_EQ(out.rms, 0.2f);
    EXPECT_EQ(history.size(), 1u);
}
