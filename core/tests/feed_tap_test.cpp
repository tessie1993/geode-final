#include "geode/audio/feed/FeedTap.hpp"

#include <gtest/gtest.h>

#include <cstdint>
#include <vector>

#include "geode/audio/feed/FeedRing.hpp"

using geode::audio::feed::FeedRing;
using geode::audio::feed::FeedSource;
using geode::audio::feed::FeedTap;

namespace {

constexpr int kRate = 48000;
constexpr int64_t kMs = 1'000'000;
constexpr size_t kSmall = 4 * FeedRing::kMaxWriteFrames;

// Stereo frames whose left sample is first, first + 1, ... and right sample the negation.
size_t writeRamp(FeedRing& ring, int first, size_t frames, int64_t nowNs, int rate = kRate) {
    std::vector<float> pcm(frames * 2);
    for (size_t i = 0; i < frames; ++i) {
        pcm[i * 2] = static_cast<float>(first + static_cast<int>(i));
        pcm[i * 2 + 1] = -static_cast<float>(first + static_cast<int>(i));
    }
    return ring.write(FeedSource::Player, pcm.data(), frames, 2, rate, nowNs);
}

struct Listening {
    FeedRing ring{kSmall};
    FeedTap tap;
    std::vector<float> out = std::vector<float>(2 * 20000, 7.0f);

    Listening() { ring.select(FeedSource::Player); }

    FeedTap::Take take(int64_t nowNs, size_t maxFrames = 20000) { return tap.take(ring, nowNs, out.data(), maxFrames); }
};

}  // namespace

TEST(FeedTapTest, TheFirstTakeMarksTheStartAndGivesNothing) {
    Listening l;
    EXPECT_FALSE(l.tap.started());
    EXPECT_EQ(l.tap.rateHz(), FeedTap::kFallbackRateHz);
    writeRamp(l.ring, 0, 100, 0, 44100);
    const FeedTap::Take first = l.take(0);
    EXPECT_TRUE(l.tap.started());
    EXPECT_EQ(first.frames, 0u);
    EXPECT_EQ(first.rateHz, 44100);
    EXPECT_EQ(l.tap.rateHz(), 44100);
    EXPECT_EQ(l.out[0], 7.0f);
}

TEST(FeedTapTest, EachTakeGivesWhatWasHeardSinceTheLast) {
    Listening l;
    writeRamp(l.ring, 0, 100, 0);
    l.take(0);
    writeRamp(l.ring, 100, 50, 1 * kMs);
    const FeedTap::Take next = l.take(1 * kMs);
    EXPECT_EQ(next.frames, 50u);
    EXPECT_EQ(next.rateHz, kRate);
    EXPECT_EQ(l.out[0], 100.0f);
    EXPECT_EQ(l.out[1], -100.0f);
    EXPECT_EQ(l.out[98], 149.0f);
    EXPECT_EQ(l.take(1 * kMs).frames, 0u);
}

TEST(FeedTapTest, ATakeGivesNoMoreThanAskedAndTheRestNextTime) {
    Listening l;
    writeRamp(l.ring, 0, 100, 0);
    l.take(0);
    writeRamp(l.ring, 100, 100, 1 * kMs);
    EXPECT_EQ(l.take(1 * kMs, 30).frames, 30u);
    EXPECT_EQ(l.out[0], 100.0f);
    EXPECT_EQ(l.take(1 * kMs, 200).frames, 70u);
    EXPECT_EQ(l.out[0], 130.0f);
    EXPECT_EQ(l.take(1 * kMs, 200).frames, 0u);
}

TEST(FeedTapTest, NoWhereToWriteKeepsThePlace) {
    Listening l;
    writeRamp(l.ring, 0, 100, 0);
    l.take(0);
    writeRamp(l.ring, 100, 20, 1 * kMs);
    const FeedTap::Take nowhere = l.tap.take(l.ring, 1 * kMs, nullptr, 10);
    EXPECT_EQ(nowhere.frames, 0u);
    EXPECT_EQ(nowhere.rateHz, kRate);
    EXPECT_EQ(l.take(1 * kMs, 0).frames, 0u);
    EXPECT_EQ(l.take(1 * kMs).frames, 20u);
    EXPECT_EQ(l.out[0], 100.0f);
}

TEST(FeedTapTest, SilenceIsGivenForTheTimeNothingIsHeard) {
    FeedRing ring(kSmall);
    FeedTap tap;
    std::vector<float> out(2 * 1000, 7.0f);
    EXPECT_EQ(tap.take(ring, 0, out.data(), 1000).rateHz, FeedTap::kFallbackRateHz);
    const FeedTap::Take part = tap.take(ring, 10 * kMs, out.data(), 100);
    EXPECT_EQ(part.frames, 100u);
    EXPECT_EQ(part.rateHz, FeedTap::kFallbackRateHz);
    EXPECT_EQ(out[0], 0.0f);
    EXPECT_EQ(out[199], 0.0f);
    EXPECT_EQ(out[200], 7.0f);
    EXPECT_EQ(tap.take(ring, 10 * kMs, out.data(), 1000).frames, 380u);
    EXPECT_EQ(out[759], 0.0f);
    EXPECT_EQ(out[760], 7.0f);
    EXPECT_EQ(tap.take(ring, 10 * kMs, out.data(), 1000).frames, 0u);
}

TEST(FeedTapTest, APauseGivesSilenceAndTheResumeGoesOnWhereItStopped) {
    Listening l;
    writeRamp(l.ring, 0, 100, 0);
    l.take(0);
    l.ring.setRunning(FeedSource::Player, false);
    EXPECT_EQ(l.take(1 * kMs).frames, 0u);
    const FeedTap::Take quiet = l.take(11 * kMs);
    EXPECT_EQ(quiet.frames, 480u);
    EXPECT_EQ(l.out[0], 0.0f);
    EXPECT_EQ(l.out[959], 0.0f);
    EXPECT_EQ(l.out[960], 7.0f);
    l.ring.setRunning(FeedSource::Player, true);
    writeRamp(l.ring, 500, 10, 12 * kMs);
    EXPECT_EQ(l.take(12 * kMs).frames, 10u);
    EXPECT_EQ(l.out[0], 500.0f);
}

TEST(FeedTapTest, ASeekStartsOverAtTheNewestFrame) {
    Listening l;
    writeRamp(l.ring, 0, 100, 0);
    l.take(0);
    writeRamp(l.ring, 100, 50, 1 * kMs);
    l.ring.flush(FeedSource::Player);
    writeRamp(l.ring, 1000, 20, 2 * kMs);
    EXPECT_EQ(l.take(2 * kMs).frames, 0u);
    writeRamp(l.ring, 2000, 10, 3 * kMs);
    EXPECT_EQ(l.take(3 * kMs).frames, 10u);
    EXPECT_EQ(l.out[0], 2000.0f);
}

TEST(FeedTapTest, ANewRateStartsOverAndCountsSilenceAtIt) {
    Listening l;
    writeRamp(l.ring, 0, 100, 0);
    l.take(0);
    writeRamp(l.ring, 100, 50, 1 * kMs, 44100);
    const FeedTap::Take moved = l.take(1 * kMs);
    EXPECT_EQ(moved.frames, 0u);
    EXPECT_EQ(moved.rateHz, 44100);
    l.ring.setRunning(FeedSource::Player, false);
    EXPECT_EQ(l.take(2 * kMs).frames, 0u);
    EXPECT_EQ(l.take(12 * kMs).frames, 441u);
}

TEST(FeedTapTest, WhatTheWriterOverwroteIsSkipped) {
    Listening l;
    writeRamp(l.ring, 0, 100, 0);
    l.take(0);
    writeRamp(l.ring, 100, 40000, 0);
    const FeedTap::Take caught = l.take(0);
    // The oldest frame still safe: 40100 written, a write's reach ahead, the ring's size behind.
    EXPECT_EQ(caught.frames, kSmall - FeedRing::kMaxWriteFrames);
    EXPECT_EQ(l.out[0], 27812.0f);
    EXPECT_EQ(l.out[1], -27812.0f);
}
