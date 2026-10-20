#include "analysis/AnalysisSession.hpp"

#include <gtest/gtest.h>

#include <algorithm>
#include <cstdint>
#include <vector>

using geode::analysis::AnalysisSession;

namespace {

constexpr int kSampleRate = 44100;
constexpr int kFftSize = 2048;
// 44100 / 62.5 is 705.6 samples, so a hop of this rate is not a whole number of samples: it is 705, and
// 705 samples last 15.986 ms, not the 16 ms that 1 / 62.5 would claim.
constexpr float kHopRateHz = 62.5f;
constexpr size_t kHopSamples = 705;
// AdaptiveRange reports warmup 1 after this many seconds of analysed (non-silent) audio.
constexpr float kWarmupSeconds = 1.5f;

// Quiet deterministic noise, stereo interleaved, with a short loud click every clickEvery frames
// (none when clickEvery is 0).
std::vector<float> noiseWithClicks(size_t frames, size_t clickEvery) {
    std::vector<float> out(frames * 2);
    uint32_t state = 12345u;
    for (size_t i = 0; i < frames; i++) {
        state = state * 1664525u + 1013904223u;
        const float noise = (static_cast<float>(state >> 8) / 16777216.0f - 0.5f) * 0.02f;
        const float click = clickEvery > 0 && i % clickEvery < 48 ? 0.9f : 0.0f;
        out[i * 2] = noise + click;
        out[i * 2 + 1] = noise + click * 0.5f;
    }
    return out;
}

// Pushes the audio in chunks of `chunk` frames and pulls every hop it makes available.
std::vector<GeodeFeatureFrame> analyse(AnalysisSession& session, const std::vector<float>& pcm, size_t chunk) {
    std::vector<GeodeFeatureFrame> hops;
    const size_t frames = pcm.size() / 2;
    for (size_t at = 0; at < frames; at += chunk) {
        session.push(pcm.data() + at * 2, std::min(chunk, frames - at), 2);
        GeodeFeatureFrame frame{};
        while (session.pull(frame)) hops.push_back(frame);
    }
    return hops;
}

}  // namespace

TEST(AnalysisSession, TheAnalysisClockAdvancesByTheAudioItConsumed) {
    AnalysisSession session(kSampleRate, kFftSize, kHopRateHz);
    const size_t hops = 50;
    const auto pcm = noiseWithClicks(kFftSize + (hops - 1) * kHopSamples, 0);

    const auto frames = analyse(session, pcm, 1024);

    ASSERT_EQ(frames.size(), hops);
    // 50 hops of 705 samples are 0.79932 s of audio; a clock of 1 / 62.5 per hop would say 0.8 s.
    const float heardSeconds = static_cast<float>(hops * kHopSamples) / static_cast<float>(kSampleRate);
    EXPECT_NEAR(frames.back().warmup * kWarmupSeconds, heardSeconds, 1e-5f);
}

TEST(AnalysisSession, PullingIsTheSameWhateverSizeTheAudioArrivesIn) {
    const auto pcm = noiseWithClicks(static_cast<size_t>(kSampleRate) * 3, static_cast<size_t>(kSampleRate) / 4);
    AnalysisSession small(kSampleRate, kFftSize, kHopRateHz);
    AnalysisSession large(kSampleRate, kFftSize, kHopRateHz);

    const auto a = analyse(small, pcm, 256);
    const auto b = analyse(large, pcm, 3001);

    ASSERT_EQ(a.size(), (pcm.size() / 2 - kFftSize) / kHopSamples + 1);
    ASSERT_EQ(a.size(), b.size());
    for (size_t i = 0; i < a.size(); i++) {
        EXPECT_EQ(a[i].rms, b[i].rms) << "hop " << i;
        EXPECT_EQ(a[i].transient, b[i].transient) << "hop " << i;
        EXPECT_EQ(a[i].kick, b[i].kick) << "hop " << i;
    }
}
