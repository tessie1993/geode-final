#include "geode/audio/feed/FeedRing.hpp"

#include <gtest/gtest.h>

#include <cmath>
#include <cstdint>
#include <limits>
#include <vector>

using geode::audio::feed::FeedRing;
using geode::audio::feed::FeedSource;
using geode::audio::feed::ReadPoint;

namespace {

constexpr int kRate = 48000;
constexpr int64_t kMs = 1'000'000;
constexpr size_t kSmall = 4 * FeedRing::kMaxWriteFrames;

// Stereo frames whose left sample is first, first + 1, ... and right sample the negation.
std::vector<float> ramp(int first, size_t frames) {
    std::vector<float> pcm(frames * 2);
    for (size_t i = 0; i < frames; ++i) {
        pcm[i * 2] = static_cast<float>(first + static_cast<int>(i));
        pcm[i * 2 + 1] = -static_cast<float>(first + static_cast<int>(i));
    }
    return pcm;
}

size_t writeRamp(FeedRing& ring, int first, size_t frames, int64_t nowNs, FeedSource source = FeedSource::Player,
                 int rate = kRate) {
    const std::vector<float> pcm = ramp(first, frames);
    return ring.write(source, pcm.data(), frames, 2, rate, nowNs);
}

}  // namespace

TEST(FeedRingTest, CapacityIsAPowerOfTwoOfAtLeastFourWrites) {
    EXPECT_EQ(FeedRing(10).capacity(), kSmall);
    EXPECT_EQ(FeedRing(kSmall).capacity(), kSmall);
    EXPECT_EQ(FeedRing(kSmall + 1).capacity(), 2 * kSmall);
}

TEST(FeedRingTest, NothingIsHeardBeforeASourceIsSelected) {
    FeedRing ring(kSmall);
    EXPECT_EQ(ring.selected(), FeedSource::None);
    EXPECT_EQ(writeRamp(ring, 0, 10, 0), 0u);
    EXPECT_EQ(ring.written(), 0u);
    EXPECT_FALSE(ring.locate(0).live);
}

TEST(FeedRingTest, OnlyTheSelectedSourceIsTaken) {
    FeedRing ring(kSmall);
    ring.select(FeedSource::Player);
    EXPECT_EQ(writeRamp(ring, 0, 10, 0, FeedSource::Live), 0u);
    EXPECT_EQ(writeRamp(ring, 0, 10, 0, FeedSource::None), 0u);
    EXPECT_EQ(writeRamp(ring, 0, 10, 0, FeedSource::Player), 10u);
    EXPECT_EQ(ring.written(), 10u);
}

TEST(FeedRingTest, UnusableWritesAreRefused) {
    FeedRing ring(kSmall);
    ring.select(FeedSource::Live);
    const std::vector<float> pcm = ramp(0, 4);
    EXPECT_EQ(ring.write(FeedSource::Live, nullptr, 4, 2, kRate, 0), 0u);
    EXPECT_EQ(ring.write(FeedSource::Live, pcm.data(), 0, 2, kRate, 0), 0u);
    EXPECT_EQ(ring.write(FeedSource::Live, pcm.data(), 4, 0, kRate, 0), 0u);
    EXPECT_EQ(ring.write(FeedSource::Live, pcm.data(), 4, 2, 0, 0), 0u);
    EXPECT_EQ(ring.written(), 0u);
    EXPECT_EQ(ring.rateHz(), 0);
}

TEST(FeedRingTest, SelectingStartsAnEpochAtTheNewestFrame) {
    FeedRing ring(kSmall);
    ring.select(FeedSource::Player);
    writeRamp(ring, 0, 100, 0);
    const uint32_t first = ring.epoch();
    ring.select(FeedSource::Player);
    EXPECT_EQ(ring.epoch(), first);
    EXPECT_EQ(ring.begin(), 0u);
    ring.select(FeedSource::Live);
    EXPECT_EQ(ring.epoch(), first + 1);
    EXPECT_EQ(ring.begin(), 100u);
    EXPECT_EQ(ring.selected(), FeedSource::Live);
}

TEST(FeedRingTest, StereoIsKeptMonoDoubledAndExtraChannelsDropped) {
    FeedRing ring(kSmall);
    ring.select(FeedSource::Player);
    const float stereo[] = {0.25f, -0.5f};
    const float mono[] = {0.75f};
    const float surround[] = {0.1f, 0.2f, 0.3f};
    ring.write(FeedSource::Player, stereo, 1, 2, kRate, 0);
    ring.write(FeedSource::Player, mono, 1, 1, kRate, 0);
    ring.write(FeedSource::Player, surround, 1, 3, kRate, 0);
    float out[6] = {};
    EXPECT_EQ(ring.copyRange(0, 3, out), 3u);
    EXPECT_FLOAT_EQ(out[0], 0.25f);
    EXPECT_FLOAT_EQ(out[1], -0.5f);
    EXPECT_FLOAT_EQ(out[2], 0.75f);
    EXPECT_FLOAT_EQ(out[3], 0.75f);
    EXPECT_FLOAT_EQ(out[4], 0.1f);
    EXPECT_FLOAT_EQ(out[5], 0.2f);
}

TEST(FeedRingTest, LongWritesAreStoredWhole) {
    FeedRing ring(kSmall);
    ring.select(FeedSource::Player);
    const size_t frames = FeedRing::kMaxWriteFrames * 2 + 7;
    EXPECT_EQ(writeRamp(ring, 0, frames, 0), frames);
    EXPECT_EQ(ring.written(), frames);
    float out[2] = {};
    ring.copyRange(frames - 1, 1, out);
    EXPECT_FLOAT_EQ(out[0], static_cast<float>(frames - 1));
}

TEST(FeedRingTest, ANewSampleRateStartsAnEpoch) {
    FeedRing ring(kSmall);
    ring.select(FeedSource::Player);
    writeRamp(ring, 0, 50, 0);
    const uint32_t epoch = ring.epoch();
    EXPECT_EQ(ring.rateHz(), kRate);
    writeRamp(ring, 50, 50, 0, FeedSource::Player, kRate);
    EXPECT_EQ(ring.epoch(), epoch);
    writeRamp(ring, 100, 50, 0, FeedSource::Player, 44100);
    EXPECT_EQ(ring.epoch(), epoch + 1);
    EXPECT_EQ(ring.begin(), 100u);
    EXPECT_EQ(ring.rateHz(), 44100);
}

TEST(FeedRingTest, ThePacketIntervalRisesAtOnceAndFallsSlowly) {
    FeedRing ring(kSmall);
    ring.select(FeedSource::Player);
    writeRamp(ring, 0, 10, 0);
    EXPECT_EQ(ring.packetNs(), FeedRing::kInitialPacketNs);
    writeRamp(ring, 0, 10, 10 * kMs);
    EXPECT_EQ(ring.packetNs(), FeedRing::kInitialPacketNs - (FeedRing::kInitialPacketNs - 10 * kMs) / 8);
    writeRamp(ring, 0, 10, 50 * kMs);
    EXPECT_EQ(ring.packetNs(), 40 * kMs);
    writeRamp(ring, 0, 10, 50 * kMs + 5000 * kMs);
    EXPECT_EQ(ring.packetNs(), FeedRing::kMaxPacketNs);
}

TEST(FeedRingTest, AResumeIsNotMeasuredAsAPacket) {
    FeedRing ring(kSmall);
    ring.select(FeedSource::Player);
    writeRamp(ring, 0, 10, 0);
    writeRamp(ring, 0, 10, 40 * kMs);
    EXPECT_EQ(ring.packetNs(), 40 * kMs);
    ring.setRunning(FeedSource::Player, false);
    ring.setRunning(FeedSource::Player, true);
    writeRamp(ring, 0, 10, 3000 * kMs);
    EXPECT_EQ(ring.packetNs(), FeedRing::kInitialPacketNs);
    // Running again while running is no resume.
    writeRamp(ring, 0, 10, 3040 * kMs);
    ring.setRunning(FeedSource::Player, true);
    writeRamp(ring, 0, 10, 3080 * kMs);
    EXPECT_EQ(ring.packetNs(), 40 * kMs);
}

TEST(FeedRingTest, LiveAudioIsReadAtTheNewestFrameUntilItGoesStale) {
    FeedRing ring(kSmall);
    ring.select(FeedSource::Player);
    writeRamp(ring, 0, 1000, 0);
    const ReadPoint point = ring.locate(kMs);
    EXPECT_TRUE(point.live);
    EXPECT_EQ(point.end, 1000u);
    EXPECT_EQ(point.begin, 0u);
    EXPECT_EQ(point.rateHz, kRate);
    EXPECT_EQ(point.epoch, ring.epoch());
    EXPECT_TRUE(ring.locate(FeedRing::kInitialPacketNs).live);
    EXPECT_FALSE(ring.locate(FeedRing::kInitialPacketNs + 1).live);
    // A clock read before the write counts as no time at all.
    EXPECT_TRUE(ring.locate(-5 * kMs).live);
}

TEST(FeedRingTest, APausedSourceIsSilentAtOnce) {
    FeedRing ring(kSmall);
    ring.select(FeedSource::Player);
    writeRamp(ring, 0, 1000, 0);
    ring.setRunning(FeedSource::Player, false);
    const ReadPoint paused = ring.locate(0);
    EXPECT_FALSE(paused.live);
    EXPECT_EQ(paused.end, 1000u);
    std::vector<float> out(8, 1.0f);
    EXPECT_EQ(ring.copy(paused, out.data(), 4), 0u);
    for (float s : out) EXPECT_EQ(s, 0.0f);
    ring.setRunning(FeedSource::Player, true);
    EXPECT_TRUE(ring.locate(0).live);
}

TEST(FeedRingTest, ControlForNoSourceChangesNothing) {
    FeedRing ring(kSmall);
    ring.select(FeedSource::Player);
    writeRamp(ring, 0, 1000, 0);
    ring.setRunning(FeedSource::None, false);
    ring.setPresentation(FeedSource::None, 10 * kMs, 1.0f);
    ring.flush(FeedSource::None);
    const ReadPoint point = ring.locate(0);
    EXPECT_TRUE(point.live);
    EXPECT_EQ(point.end, 1000u);
    EXPECT_EQ(point.begin, 0u);
}

TEST(FeedRingTest, AnEmptyEpochIsNotLive) {
    FeedRing ring(kSmall);
    ring.select(FeedSource::Player);
    writeRamp(ring, 0, 100, 0);
    ring.flush(FeedSource::Player);
    const ReadPoint point = ring.locate(0);
    EXPECT_FALSE(point.live);
    EXPECT_EQ(point.begin, 100u);
    EXPECT_EQ(point.end, 100u);
}

TEST(FeedRingTest, OnlyTheSelectedSourceCanFlush) {
    FeedRing ring(kSmall);
    ring.select(FeedSource::Player);
    writeRamp(ring, 0, 100, 0);
    const uint32_t epoch = ring.epoch();
    ring.flush(FeedSource::Live);
    EXPECT_EQ(ring.epoch(), epoch);
    ring.flush(FeedSource::Player);
    EXPECT_EQ(ring.epoch(), epoch + 1);
    EXPECT_EQ(ring.begin(), 100u);
}

TEST(FeedRingTest, ASourceAheadOfTheSpeakerIsReadThatFarBehind) {
    FeedRing ring(2 * kSmall);
    ring.select(FeedSource::Player);
    writeRamp(ring, 0, 30000, 0);
    ring.setPresentation(FeedSource::Player, 500 * kMs, 1.0f);
    EXPECT_EQ(ring.locate(0).end, 30000u - 24000u);
    // Between writes the heard frame moves on with the clock.
    EXPECT_EQ(ring.locate(100 * kMs).end, 30000u - 24000u + 4800u);
    // At twice the speed, twice as many frames sit ahead, and they are heard twice as fast.
    ring.setPresentation(FeedSource::Player, 100 * kMs, 2.0f);
    EXPECT_EQ(ring.locate(0).end, 30000u - 9600u);
    EXPECT_EQ(ring.locate(10 * kMs).end, 30000u - 9600u + 960u);
}

TEST(FeedRingTest, TheHeardFrameStaysWithinTheEpoch) {
    FeedRing ring(kSmall);
    ring.select(FeedSource::Player);
    writeRamp(ring, 0, 1000, 0);
    ring.setPresentation(FeedSource::Player, 100 * kMs, 1.0f);
    // 4800 frames ahead of the speaker, but only 1000 written: the listener has heard none of them.
    EXPECT_EQ(ring.locate(0).end, 0u);
    EXPECT_TRUE(ring.locate(0).live);
    // Never past the newest frame.
    EXPECT_EQ(ring.locate(100 * kMs).end, 1000u);
    EXPECT_EQ(ring.locate(110 * kMs).end, 1000u);
    // A buffered source expires once its buffer has played out and a packet more has not come.
    EXPECT_TRUE(ring.locate(125 * kMs).live);
    EXPECT_FALSE(ring.locate(125 * kMs + 1).live);
}

TEST(FeedRingTest, TheReaderNeverBacksOffFurtherThanTheRingHolds) {
    FeedRing ring(kSmall);
    ring.select(FeedSource::Player);
    writeRamp(ring, 0, 16000, 0);
    ring.setPresentation(FeedSource::Player, 2000 * kMs, 1.0f);
    EXPECT_EQ(ring.locate(0).end, 16000u - (kSmall - 2 * FeedRing::kMaxWriteFrames));
}

TEST(FeedRingTest, PresentationIsClamped) {
    FeedRing ring(kSmall);
    ring.select(FeedSource::Player);
    writeRamp(ring, 0, 10000, 0);
    ring.setPresentation(FeedSource::Player, -10 * kMs, 1.0f);
    EXPECT_EQ(ring.locate(0).end, 10000u);
    ring.setPresentation(FeedSource::Player, 10 * kMs, 0.0f);
    EXPECT_EQ(ring.locate(0).end, 10000u - 480u);
    ring.setPresentation(FeedSource::Player, 10 * kMs, -2.0f);
    EXPECT_EQ(ring.locate(0).end, 10000u - 480u);
    ring.setPresentation(FeedSource::Player, 10 * kMs, std::numeric_limits<float>::quiet_NaN());
    EXPECT_EQ(ring.locate(0).end, 10000u - 480u);
    ring.setPresentation(FeedSource::Player, 5000 * kMs, 1.0f);
    EXPECT_TRUE(ring.locate(FeedRing::kMaxDelayNs + FeedRing::kInitialPacketNs).live);
    EXPECT_FALSE(ring.locate(FeedRing::kMaxDelayNs + FeedRing::kInitialPacketNs + 1).live);
}

TEST(FeedRingTest, PresentationIsPerSource) {
    FeedRing ring(kSmall);
    ring.select(FeedSource::Live);
    writeRamp(ring, 0, 10000, 0, FeedSource::Live);
    ring.setPresentation(FeedSource::Player, 100 * kMs, 1.0f);
    EXPECT_EQ(ring.locate(0).end, 10000u);
    ring.setRunning(FeedSource::Player, false);
    EXPECT_TRUE(ring.locate(0).live);
}

TEST(FeedRingTest, TheWindowEndsAtTheHeardFrame) {
    FeedRing ring(kSmall);
    ring.select(FeedSource::Player);
    writeRamp(ring, 0, 100, 0);
    std::vector<float> out(8, 9.0f);
    const ReadPoint point = ring.locate(0);
    EXPECT_EQ(ring.copy(point, out.data(), 4), 4u);
    EXPECT_FLOAT_EQ(out[0], 96.0f);
    EXPECT_FLOAT_EQ(out[1], -96.0f);
    EXPECT_FLOAT_EQ(out[6], 99.0f);
    EXPECT_FLOAT_EQ(out[7], -99.0f);
}

TEST(FeedRingTest, TheWindowIsSilentBeforeTheEpochBegan) {
    FeedRing ring(kSmall);
    ring.select(FeedSource::Player);
    writeRamp(ring, 0, 100, 0);
    ring.select(FeedSource::Live);
    writeRamp(ring, 500, 3, 0, FeedSource::Live);
    std::vector<float> out(10, 9.0f);
    EXPECT_EQ(ring.copy(ring.locate(0), out.data(), 5), 3u);
    EXPECT_EQ(out[0], 0.0f);
    EXPECT_EQ(out[3], 0.0f);
    EXPECT_FLOAT_EQ(out[4], 500.0f);
    EXPECT_FLOAT_EQ(out[9], -502.0f);
}

TEST(FeedRingTest, CopyingNothingCopiesNothing) {
    FeedRing ring(kSmall);
    ring.select(FeedSource::Player);
    writeRamp(ring, 0, 100, 0);
    float out[2] = {9.0f, 9.0f};
    EXPECT_EQ(ring.copy(ring.locate(0), nullptr, 4), 0u);
    EXPECT_EQ(ring.copy(ring.locate(0), out, 0), 0u);
    EXPECT_EQ(ring.copyRange(0, 0, out), 0u);
    EXPECT_EQ(ring.copyRange(0, 1, nullptr), 0u);
    EXPECT_EQ(out[0], 9.0f);
}

TEST(FeedRingTest, FramesNotYetWrittenReadAsSilence) {
    FeedRing ring(kSmall);
    ring.select(FeedSource::Player);
    writeRamp(ring, 1, 2, 0);
    float out[8] = {9, 9, 9, 9, 9, 9, 9, 9};
    EXPECT_EQ(ring.copyRange(0, 4, out), 2u);
    EXPECT_FLOAT_EQ(out[0], 1.0f);
    EXPECT_FLOAT_EQ(out[2], 2.0f);
    EXPECT_EQ(out[4], 0.0f);
    EXPECT_EQ(out[7], 0.0f);
}

TEST(FeedRingTest, FramesTheWriterMayHaveOverwrittenReadAsSilence) {
    FeedRing ring(kSmall);
    ring.select(FeedSource::Player);
    const size_t written = kSmall + 100;
    writeRamp(ring, 0, written, 0);
    // A write under way may reach kMaxWriteFrames past the published count.
    const uint64_t safe = written + FeedRing::kMaxWriteFrames - kSmall;
    EXPECT_EQ(ring.oldestReadable(), safe);
    std::vector<float> out(4, 9.0f);
    EXPECT_EQ(ring.copyRange(safe - 1, 2, out.data()), 1u);
    EXPECT_EQ(out[0], 0.0f);
    EXPECT_EQ(out[1], 0.0f);
    EXPECT_FLOAT_EQ(out[2], static_cast<float>(safe));
}

TEST(FeedRingTest, TheOldestReadableFrameIsNeverBeforeTheEpoch) {
    FeedRing ring(kSmall);
    ring.select(FeedSource::Player);
    EXPECT_EQ(ring.oldestReadable(), 0u);
    writeRamp(ring, 0, 100, 0);
    ring.flush(FeedSource::Player);
    EXPECT_EQ(ring.oldestReadable(), 100u);
}

TEST(FeedRingTest, AReaderResumesALookbackBeforeTheFrameHeardNow) {
    FeedRing ring(kSmall);
    ring.select(FeedSource::Player);
    writeRamp(ring, 0, 10000, 0);
    EXPECT_EQ(ring.resumeFrom(0, 1000), 9000u);
    EXPECT_EQ(ring.resumeFrom(0, 0), 10000u);
    EXPECT_EQ(ring.resumeFrom(0, 9999), 1u);
}

TEST(FeedRingTest, AReaderResumesBehindTheSpeakerForASourceThatRunsAhead) {
    FeedRing ring(kSmall);
    ring.select(FeedSource::Player);
    ring.setPresentation(FeedSource::Player, 100 * kMs, 1.0f);
    writeRamp(ring, 0, 10000, 0);
    // 100 ms at 48 kHz is 4,800 frames between the newest frame and the speaker.
    EXPECT_EQ(ring.resumeFrom(0, 1000), 10000u - 4800u - 1000u);
}

TEST(FeedRingTest, AReaderNeverResumesBeforeTheOldestReadableFrame) {
    FeedRing ring(kSmall);
    ring.select(FeedSource::Player);
    writeRamp(ring, 0, 100, 0);
    EXPECT_EQ(ring.resumeFrom(0, 1000), 0u);
    ring.flush(FeedSource::Player);
    writeRamp(ring, 0, 50, 0);
    EXPECT_EQ(ring.resumeFrom(0, 1000), 100u);
    writeRamp(ring, 0, 3 * kSmall, 0);
    EXPECT_EQ(ring.resumeFrom(0, 1000), 150u + 3 * kSmall - 1000u);
    EXPECT_EQ(ring.resumeFrom(0, 2 * kSmall), ring.oldestReadable());
    EXPECT_GT(ring.oldestReadable(), 100u);
}

TEST(FeedRingTest, AReaderResumesFromTheStartWhenNothingIsSelected) {
    FeedRing ring(kSmall);
    EXPECT_EQ(ring.resumeFrom(0, 1000), 0u);
}

TEST(FeedRingTest, AWindowGainRaisesTheWindowOnlyAndIsLimited) {
    FeedRing ring(kSmall);
    ring.select(FeedSource::Live);
    EXPECT_FLOAT_EQ(ring.windowGain(FeedSource::Live), 1.0f);
    const float pcm[4] = {0.1f, -0.2f, 0.6f, -0.6f};
    ring.write(FeedSource::Live, pcm, 2, 2, kRate, 0);
    ring.setWindowGain(FeedSource::Live, 2.0f);
    float out[6] = {9, 9, 9, 9, 9, 9};
    EXPECT_EQ(ring.copy(ring.locate(0), out, 3), 2u);
    // The frame before the epoch stays silent; the rest are raised and held within [-1, 1].
    EXPECT_EQ(out[0], 0.0f);
    EXPECT_EQ(out[1], 0.0f);
    EXPECT_FLOAT_EQ(out[2], 0.2f);
    EXPECT_FLOAT_EQ(out[3], -0.4f);
    EXPECT_FLOAT_EQ(out[4], 1.0f);
    EXPECT_FLOAT_EQ(out[5], -1.0f);
    // What the analysis reads is as written.
    float raw[4] = {};
    EXPECT_EQ(ring.copyRange(0, 2, raw), 2u);
    EXPECT_FLOAT_EQ(raw[2], 0.6f);
    EXPECT_FLOAT_EQ(raw[3], -0.6f);
}

TEST(FeedRingTest, AWindowGainBelongsToItsSourceAndIsClamped) {
    FeedRing ring(kSmall);
    ring.setWindowGain(FeedSource::Live, 100.0f);
    EXPECT_FLOAT_EQ(ring.windowGain(FeedSource::Live), FeedRing::kMaxWindowGain);
    EXPECT_FLOAT_EQ(ring.windowGain(FeedSource::Player), 1.0f);
    ring.setWindowGain(FeedSource::Live, -1.0f);
    EXPECT_FLOAT_EQ(ring.windowGain(FeedSource::Live), 0.0f);
    ring.setWindowGain(FeedSource::Live, std::numeric_limits<float>::quiet_NaN());
    EXPECT_FLOAT_EQ(ring.windowGain(FeedSource::Live), 1.0f);
    ring.setWindowGain(FeedSource::None, 4.0f);
    EXPECT_FLOAT_EQ(ring.windowGain(FeedSource::None), 1.0f);
    // The player's window is untouched by the microphone's gain.
    ring.setWindowGain(FeedSource::Live, 4.0f);
    ring.select(FeedSource::Player);
    writeRamp(ring, 3, 1, 0);
    float out[2] = {};
    EXPECT_EQ(ring.copy(ring.locate(0), out, 1), 1u);
    EXPECT_FLOAT_EQ(out[0], 3.0f);
}

TEST(FeedRingTest, TheWindowGainComesAfterTheSensitivityAndOnlyOnTheWindow) {
    FeedRing ring(kSmall);
    ring.select(FeedSource::Live);
    const float pcm[2] = {0.1f, -0.05f};
    ring.write(FeedSource::Live, pcm, 1, 2, kRate, 0);
    ring.setGainDb(20.0f * std::log10(2.0f));
    ring.setWindowGain(FeedSource::Live, 3.0f);
    float out[2] = {};
    EXPECT_EQ(ring.copy(ring.locate(0), out, 1), 1u);
    // Both gains multiply on the window: 0.1 x 2 x 3.
    EXPECT_NEAR(out[0], 0.6f, 1e-5f);
    EXPECT_NEAR(out[1], -0.3f, 1e-5f);
    // The analysis gets the Sensitivity alone.
    float raw[2] = {};
    EXPECT_EQ(ring.copyRange(0, 1, raw), 1u);
    EXPECT_NEAR(raw[0], 0.2f, 1e-5f);
    EXPECT_NEAR(raw[1], -0.1f, 1e-5f);
}

TEST(FeedRingTest, AnUnmeasuredDelayIsToppedUpWithTheRoute) {
    FeedRing ring(2 * kSmall);
    ring.select(FeedSource::Player);
    writeRamp(ring, 0, 30000, 0);
    EXPECT_EQ(ring.route(), geode::audio::feed::OutputRoute::Unknown);
    ring.setRoute(geode::audio::feed::OutputRoute::Bluetooth);
    EXPECT_EQ(ring.route(), geode::audio::feed::OutputRoute::Bluetooth);
    ring.setPresentation(FeedSource::Player, 100 * kMs, 1.0f);
    EXPECT_EQ(ring.locate(0).end, 30000u - 4800u);
    // 100 ms of buffer plus Bluetooth's 200 ms.
    ring.setPresentation(FeedSource::Player, 100 * kMs, 1.0f, false);
    EXPECT_EQ(ring.locate(0).end, 30000u - 14400u);
    ring.setRoute(geode::audio::feed::OutputRoute::Wired);
    EXPECT_EQ(ring.locate(0).end, 30000u - 5280u);
}

TEST(FeedRingTest, AnUnmeasuredDelayKeepsTheSourceLiveForItsWholeLatency) {
    FeedRing ring(2 * kSmall);
    ring.select(FeedSource::Player);
    writeRamp(ring, 0, 30000, 0);
    ring.setRoute(geode::audio::feed::OutputRoute::Bluetooth);
    ring.setPresentation(FeedSource::Player, 100 * kMs, 1.0f, false);
    EXPECT_TRUE(ring.locate(FeedRing::kInitialPacketNs + 300 * kMs).live);
    EXPECT_FALSE(ring.locate(FeedRing::kInitialPacketNs + 300 * kMs + 1).live);
}

TEST(FeedRingTest, TheUserOffsetMovesThePlayersPicture) {
    FeedRing ring(2 * kSmall);
    ring.select(FeedSource::Player);
    writeRamp(ring, 0, 30000, 0);
    ring.setPresentation(FeedSource::Player, 100 * kMs, 1.0f);
    ring.setUserOffset(50 * kMs);
    EXPECT_EQ(ring.userOffsetNs(), 50 * kMs);
    EXPECT_EQ(ring.locate(0).end, 30000u - 7200u);
    // An earlier picture, but never one ahead of what was written.
    ring.setUserOffset(-50 * kMs);
    EXPECT_EQ(ring.locate(0).end, 30000u - 2400u);
    ring.setUserOffset(-100 * kMs);
    EXPECT_EQ(ring.locate(0).end, 30000u);
    ring.setUserOffset(-150 * kMs);
    EXPECT_EQ(ring.locate(0).end, 30000u);
    EXPECT_TRUE(ring.locate(0).live);
}

TEST(FeedRingTest, TheUserOffsetIsClampedAndLeavesLivenessAlone) {
    FeedRing ring(2 * kSmall);
    ring.select(FeedSource::Player);
    writeRamp(ring, 0, 30000, 0);
    ring.setUserOffset(10'000 * kMs);
    EXPECT_EQ(ring.userOffsetNs(), geode::audio::feed::heard::kMaxUserOffsetNs);
    ring.setUserOffset(-10'000 * kMs);
    EXPECT_EQ(ring.userOffsetNs(), -geode::audio::feed::heard::kMaxUserOffsetNs);
    ring.setUserOffset(400 * kMs);
    ring.setPresentation(FeedSource::Player, 100 * kMs, 1.0f);
    EXPECT_TRUE(ring.locate(FeedRing::kInitialPacketNs + 100 * kMs).live);
    EXPECT_FALSE(ring.locate(FeedRing::kInitialPacketNs + 100 * kMs + 1).live);
}

TEST(FeedRingTest, LiveInputIsNeverMovedByTheUserOffset) {
    FeedRing ring(2 * kSmall);
    ring.select(FeedSource::Live);
    writeRamp(ring, 0, 30000, 0, FeedSource::Live);
    ring.setUserOffset(200 * kMs);
    EXPECT_EQ(ring.locate(0).end, 30000u);
    ring.setPresentation(FeedSource::Live, 100 * kMs, 1.0f);
    EXPECT_EQ(ring.locate(0).end, 30000u - 4800u);
}

TEST(FeedRingTest, AFrameIsReadAtTheAudioHeardWhenItReachesTheScreen) {
    FeedRing ring(2 * kSmall);
    ring.select(FeedSource::Player);
    writeRamp(ring, 0, 30000, 0);
    ring.setPresentation(FeedSource::Player, 100 * kMs, 1.0f);
    EXPECT_EQ(ring.locate(0, 10 * kMs).end, 30000u - 4800u + 480u);
    EXPECT_EQ(ring.locate(5 * kMs, 10 * kMs).end, 30000u - 4800u + 720u);
    // A present time already past reads now.
    EXPECT_EQ(ring.locate(0, -10 * kMs).end, 30000u - 4800u);
}

TEST(FeedRingTest, TheScreenNeverReachesPastTheNewestFrame) {
    FeedRing ring(2 * kSmall);
    ring.select(FeedSource::Player);
    writeRamp(ring, 0, 30000, 0);
    ring.setPresentation(FeedSource::Player, 10 * kMs, 1.0f);
    EXPECT_EQ(ring.locate(0, 20 * kMs).end, 30000u);
    // A source heard as it is written has nothing ahead to show.
    ring.setPresentation(FeedSource::Player, 0, 1.0f);
    EXPECT_EQ(ring.locate(0, 20 * kMs).end, 30000u);
    // Liveness is judged now, not at the present time.
    EXPECT_TRUE(ring.locate(FeedRing::kInitialPacketNs, 50 * kMs).live);
}

TEST(FeedRingTest, TheSensitivityIsAGainOnEverythingRead) {
    FeedRing ring(kSmall);
    ring.select(FeedSource::Player);
    const float pcm[] = {0.25f, -0.125f, 0.5f, 0.0625f};
    ring.write(FeedSource::Player, pcm, 2, 2, kRate, 0);
    EXPECT_FLOAT_EQ(ring.gain(), 1.0f);
    ring.setGainDb(20.0f);
    EXPECT_FLOAT_EQ(ring.gain(), 10.0f);
    float out[4] = {};
    EXPECT_EQ(ring.copyRange(0, 2, out), 2u);
    EXPECT_FLOAT_EQ(out[0], 2.5f);
    EXPECT_FLOAT_EQ(out[1], -1.25f);
    EXPECT_FLOAT_EQ(out[2], 5.0f);
    EXPECT_FLOAT_EQ(out[3], 0.625f);
    float window[4] = {};
    EXPECT_EQ(ring.copy(ring.locate(0), window, 2), 2u);
    EXPECT_FLOAT_EQ(window[0], 2.5f);
    EXPECT_FLOAT_EQ(window[3], 0.625f);
    // A lower Sensitivity turns it down; what is stored is untouched.
    ring.setGainDb(-6.0f);
    ring.copyRange(0, 2, out);
    EXPECT_NEAR(out[0], 0.25f * 0.501187f, 1e-5f);
    ring.setGainDb(0.0f);
    ring.copyRange(0, 2, out);
    EXPECT_FLOAT_EQ(out[0], 0.25f);
}

TEST(FeedRingTest, TheSensitivityIsHeldToItsRange) {
    FeedRing ring(kSmall);
    ring.setGainDb(40.0f);
    EXPECT_NEAR(ring.gain(), std::pow(10.0f, FeedRing::kMaxGainDb / 20.0f), 1e-3f);
    ring.setGainDb(-40.0f);
    EXPECT_NEAR(ring.gain(), std::pow(10.0f, FeedRing::kMinGainDb / 20.0f), 1e-5f);
    ring.setGainDb(std::numeric_limits<float>::quiet_NaN());
    EXPECT_FLOAT_EQ(ring.gain(), 1.0f);
    ring.setGainDb(-std::numeric_limits<float>::infinity());
    EXPECT_FLOAT_EQ(ring.gain(), 1.0f);
    EXPECT_FLOAT_EQ(FeedRing::kMinGainDb, -12.0f);
    EXPECT_FLOAT_EQ(FeedRing::kMaxGainDb, 24.0f);
}
