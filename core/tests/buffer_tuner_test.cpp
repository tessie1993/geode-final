#include "geode/audio/player/BufferTuner.hpp"

#include <gtest/gtest.h>

#include <cstdint>
#include <optional>

using geode::audio::player::BufferTuner;
using geode::audio::player::TunableStream;

namespace {

constexpr int32_t kBurst = 192;
constexpr int32_t kSampleRate = 48000;
constexpr int32_t kRoomyCapacity = kBurst * 32;
constexpr int64_t kMsPerSecond = 1000;

constexpr int32_t kMinFrames = kBurst * BufferTuner::kMinBursts;
constexpr int32_t kMaxFrames = kBurst * BufferTuner::kMaxBursts;
constexpr int32_t kStep = kBurst * BufferTuner::kStepBursts;

constexpr int64_t framesFor(int32_t ms) { return static_cast<int64_t>(ms) * kSampleRate / kMsPerSecond; }

// An output stream that applies whatever size it is asked for and reports underruns the test injects.
class FakeStream : public TunableStream {
public:
    std::optional<int32_t> xrunCount() override {
        if (!reportsXruns) return std::nullopt;
        return xruns;
    }

    std::optional<int32_t> setBufferSizeInFrames(int32_t frames) override {
        ++setCalls;
        if (rejects) return std::nullopt;
        size = frames;
        return size;
    }

    int32_t xruns = 0;
    int32_t size = 0;
    int setCalls = 0;
    bool reportsXruns = true;
    bool rejects = false;
};

class BufferTunerTest : public ::testing::Test {
protected:
    void SetUp() override {
        ASSERT_EQ(tuner.reset(stream, kBurst, kRoomyCapacity, kSampleRate), BufferTuner::Setup::Ready);
        tick();  // The first callback only records the underrun baseline.
    }

    // One audio callback of one burst.
    void tick() { tuner.onAudioReady(stream, kBurst); }

    // Callbacks covering at least `frames` frames.
    void run(int64_t frames) {
        for (int64_t done = 0; done < frames; done += kBurst) tick();
    }

    void underrun() {
        ++stream.xruns;
        tick();
    }

    // An underrun far enough after the last grow that it is not the same glitch.
    void separateUnderrun() {
        run(framesFor(BufferTuner::kGrowCooldownMs));
        underrun();
    }

    FakeStream stream;
    BufferTuner tuner;
};

}  // namespace

TEST_F(BufferTunerTest, StartsAtTheMinimumBurstMultiple) {
    EXPECT_EQ(tuner.sizeFrames(), kMinFrames);
    EXPECT_EQ(stream.size, kMinFrames);
    EXPECT_EQ(tuner.minFrames(), kMinFrames);
    EXPECT_EQ(tuner.maxFrames(), kMaxFrames);
}

TEST_F(BufferTunerTest, GrowsOneStepAfterAnUnderrun) {
    underrun();
    EXPECT_EQ(tuner.sizeFrames(), kMinFrames + kStep);
    EXPECT_EQ(stream.size, kMinFrames + kStep);
}

TEST_F(BufferTunerTest, UnderrunsWithinTheCooldownCountAsOneGlitch) {
    underrun();
    underrun();
    underrun();
    EXPECT_EQ(tuner.sizeFrames(), kMinFrames + kStep);
    separateUnderrun();
    EXPECT_EQ(tuner.sizeFrames(), kMinFrames + 2 * kStep);
}

TEST_F(BufferTunerTest, QuietStreamDoesNotChange) {
    const int callsBefore = stream.setCalls;
    run(framesFor(BufferTuner::kStableMs) * 3);
    EXPECT_EQ(tuner.sizeFrames(), kMinFrames);
    EXPECT_EQ(stream.setCalls, callsBefore);
}

TEST_F(BufferTunerTest, ShrinksOneStepOnlyAfterTheStablePeriod) {
    underrun();
    separateUnderrun();
    ASSERT_EQ(tuner.sizeFrames(), kMinFrames + 2 * kStep);

    run(framesFor(BufferTuner::kStableMs) - kBurst);
    EXPECT_EQ(tuner.sizeFrames(), kMinFrames + 2 * kStep);
    tick();
    EXPECT_EQ(tuner.sizeFrames(), kMinFrames + kStep);

    run(framesFor(BufferTuner::kStableMs));
    EXPECT_EQ(tuner.sizeFrames(), kMinFrames);
}

TEST_F(BufferTunerTest, AnUnderrunRestartsTheStablePeriod) {
    underrun();
    run(framesFor(BufferTuner::kStableMs) / 2);
    separateUnderrun();
    const int32_t grown = tuner.sizeFrames();
    run(framesFor(BufferTuner::kStableMs) - kBurst);
    EXPECT_EQ(tuner.sizeFrames(), grown);
}

TEST_F(BufferTunerTest, NeverShrinksBelowTheMinimum) {
    underrun();
    run(framesFor(BufferTuner::kStableMs) * 10);
    EXPECT_EQ(tuner.sizeFrames(), kMinFrames);
}

TEST_F(BufferTunerTest, NeverGrowsAboveTheMaximum) {
    for (int i = 0; i < BufferTuner::kMaxBursts * 2; ++i) separateUnderrun();
    EXPECT_EQ(tuner.sizeFrames(), kMaxFrames);
    EXPECT_EQ(stream.size, kMaxFrames);
}

TEST(BufferTunerBounds, MaximumIsCappedByTheStreamCapacity) {
    FakeStream stream;
    BufferTuner tuner;
    constexpr int32_t kSmallCapacity = kBurst * (BufferTuner::kMinBursts + 1);
    ASSERT_EQ(tuner.reset(stream, kBurst, kSmallCapacity, kSampleRate), BufferTuner::Setup::Ready);
    tuner.onAudioReady(stream, kBurst);
    for (int i = 0; i < BufferTuner::kMaxBursts; ++i) {
        ++stream.xruns;
        tuner.onAudioReady(stream, static_cast<int32_t>(framesFor(BufferTuner::kGrowCooldownMs)));
    }
    EXPECT_EQ(tuner.maxFrames(), kSmallCapacity);
    EXPECT_EQ(tuner.sizeFrames(), kSmallCapacity);
}

TEST(BufferTunerBounds, CapacityBelowTheMinimumPinsBothBounds) {
    FakeStream stream;
    BufferTuner tuner;
    ASSERT_EQ(tuner.reset(stream, kBurst, kBurst, kSampleRate), BufferTuner::Setup::Ready);
    EXPECT_EQ(tuner.minFrames(), kBurst);
    EXPECT_EQ(tuner.maxFrames(), kBurst);
    EXPECT_EQ(tuner.sizeFrames(), kBurst);
}

TEST_F(BufferTunerTest, UnderrunAfterAShrinkDoublesTheStablePeriod) {
    const int64_t base = framesFor(BufferTuner::kStableMs);
    underrun();
    run(base);
    ASSERT_EQ(tuner.sizeFrames(), kMinFrames);

    // The shrink caused an underrun: grow back, and wait twice as long before trying again.
    separateUnderrun();
    ASSERT_EQ(tuner.sizeFrames(), kMinFrames + kStep);
    EXPECT_EQ(tuner.stablePeriodFrames(), base * BufferTuner::kStableBackoffFactor);
    run(base);
    EXPECT_EQ(tuner.sizeFrames(), kMinFrames + kStep);
    run(base * BufferTuner::kStableBackoffFactor - base);
    EXPECT_EQ(tuner.sizeFrames(), kMinFrames);
}

TEST_F(BufferTunerTest, UnderrunWithoutAPrecedingShrinkKeepsTheStablePeriod) {
    underrun();
    separateUnderrun();
    EXPECT_EQ(tuner.stablePeriodFrames(), framesFor(BufferTuner::kStableMs));
}

TEST_F(BufferTunerTest, StablePeriodBackoffIsCapped) {
    for (int i = 0; i < 16; ++i) {
        separateUnderrun();
        run(tuner.stablePeriodFrames());
    }
    EXPECT_EQ(tuner.stablePeriodFrames(), framesFor(BufferTuner::kMaxStableMs));
}

TEST_F(BufferTunerTest, CounterGoingBackwardsIsANewBaselineNotAnUnderrun) {
    underrun();
    const int32_t size = tuner.sizeFrames();
    ASSERT_EQ(size, kMinFrames + kStep);
    stream.xruns = 0;
    run(framesFor(BufferTuner::kGrowCooldownMs));
    EXPECT_EQ(tuner.sizeFrames(), size);
    separateUnderrun();
    EXPECT_EQ(tuner.sizeFrames(), size + kStep);
}

TEST(BufferTunerSetup, InvalidGeometryLeavesTheStreamAlone) {
    FakeStream stream;
    BufferTuner tuner;
    EXPECT_EQ(tuner.reset(stream, 0, kRoomyCapacity, kSampleRate), BufferTuner::Setup::InvalidStreamGeometry);
    EXPECT_EQ(tuner.reset(stream, kBurst, 0, kSampleRate), BufferTuner::Setup::InvalidStreamGeometry);
    EXPECT_EQ(tuner.reset(stream, kBurst, kRoomyCapacity, 0), BufferTuner::Setup::InvalidStreamGeometry);
    EXPECT_EQ(stream.setCalls, 0);
    EXPECT_FALSE(tuner.active());
    EXPECT_EQ(tuner.sizeFrames(), 0);
    ++stream.xruns;
    tuner.onAudioReady(stream, kBurst);
    EXPECT_EQ(stream.setCalls, 0);
}

TEST(BufferTunerSetup, RejectedStartingSizeLeavesTheTunerInactive) {
    FakeStream stream;
    stream.rejects = true;
    BufferTuner tuner;
    EXPECT_EQ(tuner.reset(stream, kBurst, kRoomyCapacity, kSampleRate), BufferTuner::Setup::Rejected);
    EXPECT_FALSE(tuner.active());
    tuner.onAudioReady(stream, kBurst);
    ++stream.xruns;
    tuner.onAudioReady(stream, kBurst);
    EXPECT_EQ(stream.setCalls, 1);
}

TEST(BufferTunerSetup, StreamWithoutAnUnderrunCounterIsNeverRetuned) {
    FakeStream stream;
    stream.reportsXruns = false;
    BufferTuner tuner;
    ASSERT_EQ(tuner.reset(stream, kBurst, kRoomyCapacity, kSampleRate), BufferTuner::Setup::Ready);
    for (int i = 0; i < 1000; ++i) tuner.onAudioReady(stream, kBurst);
    EXPECT_EQ(stream.setCalls, 1);
    EXPECT_EQ(tuner.sizeFrames(), kMinFrames);
}

TEST_F(BufferTunerTest, ResetReturnsToTheStartingSize) {
    underrun();
    ASSERT_EQ(tuner.reset(stream, kBurst, kRoomyCapacity, kSampleRate), BufferTuner::Setup::Ready);
    EXPECT_EQ(tuner.sizeFrames(), kMinFrames);
    EXPECT_EQ(tuner.stablePeriodFrames(), framesFor(BufferTuner::kStableMs));
}
