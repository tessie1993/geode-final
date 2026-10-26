#include "geode/audio/player/OutputRetry.hpp"

#include <gtest/gtest.h>

#include <cstdint>

using geode::audio::player::OutputRetry;

TEST(OutputRetryTest, NothingIsDueUntilTheOutputNeedsReopening) {
    OutputRetry retry;
    EXPECT_FALSE(retry.pending());
    EXPECT_FALSE(retry.due(0));
    EXPECT_FALSE(retry.due(1'000'000));
    EXPECT_EQ(retry.attempts(), 0);
}

TEST(OutputRetryTest, TheFirstAttemptIsDueAtOnce) {
    OutputRetry retry;
    retry.begin(1000);
    EXPECT_TRUE(retry.pending());
    EXPECT_FALSE(retry.due(999));
    EXPECT_TRUE(retry.due(1000));
    EXPECT_EQ(retry.attempts(), 0);
}

TEST(OutputRetryTest, AttemptsBackOffThenGiveUp) {
    OutputRetry retry;
    int64_t now = 0;
    retry.begin(now);
    const int64_t delays[] = {100, 200, 400, 800, OutputRetry::kMaxDelayMs};
    for (int64_t delay : delays) {
        EXPECT_FALSE(retry.failed(now));
        EXPECT_TRUE(retry.pending());
        EXPECT_FALSE(retry.due(now + delay - 1));
        EXPECT_TRUE(retry.due(now + delay));
        now += delay;
    }
    EXPECT_EQ(retry.attempts(), OutputRetry::kMaxAttempts - 1);
    EXPECT_TRUE(retry.failed(now));
    EXPECT_FALSE(retry.pending());
    EXPECT_FALSE(retry.due(now + 100'000));
    EXPECT_EQ(retry.attempts(), OutputRetry::kMaxAttempts);
}

TEST(OutputRetryTest, ADisconnectWhileRetryingDoesNotStartOver) {
    OutputRetry retry;
    retry.begin(0);
    retry.failed(0);
    retry.begin(50);
    EXPECT_EQ(retry.attempts(), 1);
    EXPECT_FALSE(retry.due(99));
    EXPECT_TRUE(retry.due(100));
}

TEST(OutputRetryTest, AFailureWithNoRetryUnderWayStartsOne) {
    OutputRetry retry;
    EXPECT_FALSE(retry.failed(500));
    EXPECT_TRUE(retry.pending());
    EXPECT_EQ(retry.attempts(), 1);
    EXPECT_FALSE(retry.due(599));
    EXPECT_TRUE(retry.due(600));
}

TEST(OutputRetryTest, AReopenedOutputStartsAfresh) {
    OutputRetry retry;
    retry.begin(0);
    retry.failed(0);
    retry.failed(100);
    retry.reset();
    EXPECT_FALSE(retry.pending());
    EXPECT_EQ(retry.attempts(), 0);
    EXPECT_FALSE(retry.due(1'000'000));
    retry.begin(2000);
    EXPECT_TRUE(retry.due(2000));
    EXPECT_FALSE(retry.failed(2000));
    EXPECT_TRUE(retry.due(2100));
}
