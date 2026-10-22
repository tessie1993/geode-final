#include "geode/audio/dsp/Limiter.hpp"

#include <gtest/gtest.h>

#include <cmath>
#include <vector>

using geode::audio::Limiter;

namespace {

constexpr float kSampleRate = 48000.0f;
constexpr float kCeiling = 0.98f;
// 5 ms of lookahead at 48 kHz.
constexpr size_t kLookaheadFrames = 240;

std::vector<float> sine(size_t frames, int channels, float amplitude) {
    std::vector<float> out(frames * static_cast<size_t>(channels));
    for (size_t i = 0; i < frames; ++i) {
        const float v = amplitude * std::sin(2.0f * 3.14159265f * 440.0f * static_cast<float>(i) / kSampleRate);
        for (int c = 0; c < channels; ++c) out[i * static_cast<size_t>(channels) + static_cast<size_t>(c)] = v;
    }
    return out;
}

}  // namespace

TEST(Limiter, LoudInputNeverExceedsTheCeiling) {
    Limiter limiter(kSampleRate, 2);
    auto buffer = sine(48000, 2, 2.0f);
    limiter.process(buffer.data(), 48000);
    for (float s : buffer) {
        ASSERT_LE(std::fabs(s), kCeiling);
    }
}

TEST(Limiter, QuietInputPassesThroughAfterTheLookahead) {
    Limiter limiter(kSampleRate, 1);
    std::vector<float> buffer(1000, 0.5f);
    limiter.process(buffer.data(), buffer.size());
    for (size_t i = 0; i < kLookaheadFrames; ++i) EXPECT_FLOAT_EQ(buffer[i], 0.0f) << "frame " << i;
    for (size_t i = kLookaheadFrames; i < buffer.size(); ++i) EXPECT_FLOAT_EQ(buffer[i], 0.5f) << "frame " << i;
}

TEST(Limiter, GainRecoversAfterAPeakPasses) {
    Limiter limiter(kSampleRate, 1);
    std::vector<float> buffer(48000, 0.5f);
    buffer[1000] = 4.0f;
    limiter.process(buffer.data(), buffer.size());
    // Half a second later the release has long finished: the quiet signal is back at full level.
    EXPECT_NEAR(buffer.back(), 0.5f, 1e-3f);
    // While the peak is in the window the gain is pulled down.
    EXPECT_LT(buffer[1000 + kLookaheadFrames - 1], 0.5f);
}

TEST(Limiter, GainRecoversWithTheReleaseTimeConstant) {
    Limiter limiter(kSampleRate, 1);
    constexpr size_t kPeak = 1000;
    std::vector<float> buffer(5000, 0.5f);
    buffer[kPeak] = 4.0f;
    limiter.process(buffer.data(), buffer.size());
    // While the peak is in the window the gain sits at ceiling / peak. From the frame it leaves, the gain
    // closes c of its gap to 1 each frame, c = 1 - exp(-1 / (50 ms * rate)): one time constant
    // (2400 frames) on, the output is 0.5 * (1 - gap * e^-(2401 / 2400)).
    constexpr double kReleaseFrames = 0.05 * 48000.0;
    constexpr size_t kAfter = 2400;
    const double gap = 1.0 - static_cast<double>(kCeiling) / 4.0;
    const double expected = 0.5 * (1.0 - gap * std::exp(-static_cast<double>(kAfter + 1) / kReleaseFrames));
    EXPECT_NEAR(buffer[kPeak + kLookaheadFrames + kAfter], expected, 1e-3);
}

TEST(Limiter, DisabledLeavesTheBufferUntouched) {
    Limiter limiter(kSampleRate, 2);
    limiter.setEnabled(false);
    auto buffer = sine(4800, 2, 2.0f);
    const auto original = buffer;
    limiter.process(buffer.data(), 4800);
    EXPECT_EQ(buffer, original);
}

TEST(Limiter, ResetClearsTheDelayLine) {
    Limiter limiter(kSampleRate, 1);
    std::vector<float> loud(500, 0.9f);
    limiter.process(loud.data(), loud.size());
    limiter.reset();
    std::vector<float> silence(kLookaheadFrames, 0.0f);
    limiter.process(silence.data(), silence.size());
    for (float s : silence) EXPECT_FLOAT_EQ(s, 0.0f);
}
