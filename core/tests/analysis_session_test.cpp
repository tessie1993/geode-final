#include "geode/analysis/AnalysisSession.hpp"

#include <gtest/gtest.h>

#include <algorithm>
#include <cmath>
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
    AnalysisSession fine(kSampleRate, kFftSize, kHopRateHz);
    AnalysisSession coarse(kSampleRate, kFftSize, kHopRateHz);

    const auto a = analyse(fine, pcm, 256);
    const auto b = analyse(coarse, pcm, 3001);

    ASSERT_EQ(a.size(), (pcm.size() / 2 - kFftSize) / kHopSamples + 1);
    ASSERT_EQ(a.size(), b.size());
    for (size_t i = 0; i < a.size(); i++) {
        EXPECT_EQ(a[i].rms, b[i].rms) << "hop " << i;
        EXPECT_EQ(a[i].transient, b[i].transient) << "hop " << i;
        EXPECT_EQ(a[i].kick, b[i].kick) << "hop " << i;
    }
}

namespace {

// The strength each sequenced event fires with on a hop, as the frame's one-hop fields report it; 0 when it
// does not fire. A beat with no strength of its own counts as a full one.
float firedOn(const GeodeFeatureFrame& f, int event) {
    switch (event) {
        case GEODE_EVENT_BEAT:
            return f.beat > 0.0f ? (f.beatStrength > 0.0f ? f.beatStrength : 1.0f) : 0.0f;
        case GEODE_EVENT_TRANSIENT:
            return f.transient;
        case GEODE_EVENT_KICK:
            return f.kick;
        case GEODE_EVENT_SNARE:
            return f.snare;
        case GEODE_EVENT_HAT:
            return f.hat;
        case GEODE_EVENT_DOWNBEAT:
            return f.downbeat;
        case GEODE_EVENT_SECTION_BOUNDARY:
            return f.sectionBoundary;
        case GEODE_EVENT_DROP:
            return f.drop;
        case GEODE_EVENT_ARRIVAL:
            return f.arrival;
        default:
            return 0.0f;
    }
}

std::vector<GeodeFeatureFrame> clickTrack() {
    AnalysisSession session(kSampleRate, kFftSize, kHopRateHz);
    session.setTuning(3.0f, 60.0f, 0.02f, 0.15f);
    // Six seconds with a click every quarter second, far enough apart for every picker's refractory window.
    const auto pcm = noiseWithClicks(static_cast<size_t>(kSampleRate) * 6, static_cast<size_t>(kSampleRate) / 4);
    return analyse(session, pcm, 512);
}

}  // namespace

TEST(AnalysisSession, EachEventsSequenceStepsOnceOnEveryHopItFiresOn) {
    const auto hops = clickTrack();
    ASSERT_FALSE(hops.empty());

    for (int e = 0; e < GEODE_EVENT_COUNT; e++) {
        float seq = 0.0f;
        float strength = 0.0f;
        for (size_t i = 0; i < hops.size(); i++) {
            const float fired = firedOn(hops[i], e);
            if (fired > 0.0f) {
                seq += 1.0f;
                strength = fired;
            }
            ASSERT_EQ(hops[i].events[e].seq, seq) << "event " << e << ", hop " << i;
            ASSERT_EQ(hops[i].events[e].strength, strength) << "event " << e << ", hop " << i;
        }
    }
    EXPECT_GT(hops.back().events[GEODE_EVENT_TRANSIENT].seq, 0.0f) << "the clicks should be heard as transients";
}

TEST(AnalysisSession, AReaderLookingAt30_60Or120FpsSeesEveryEventExactlyOnce) {
    const auto hops = clickTrack();
    const double seconds = static_cast<double>(kFftSize + (hops.size() - 1) * kHopSamples) / kSampleRate;

    for (const int fps : {30, 60, 120}) {
        for (int e = 0; e < GEODE_EVENT_COUNT; e++) {
            float lastSeen = 0.0f;
            int seen = 0;
            // One display frame past the end, so the last hop is looked at too.
            for (int tick = 0; tick <= static_cast<int>(seconds * fps) + 1; tick++) {
                // The newest hop whose whole window has arrived by this display frame.
                const auto heard = static_cast<size_t>(static_cast<double>(tick) / fps * kSampleRate);
                if (heard < static_cast<size_t>(kFftSize)) continue;
                const size_t newest = std::min((heard - kFftSize) / kHopSamples, hops.size() - 1);
                const GeodeEvent& event = hops[newest].events[e];
                if (event.seq != lastSeen && event.seq != 0.0f) {
                    seen++;
                    EXPECT_GT(event.strength, 0.0f) << "event " << e << " at " << fps << " fps";
                }
                lastSeen = event.seq;
            }
            EXPECT_EQ(static_cast<float>(seen), hops.back().events[e].seq) << "event " << e << " at " << fps << " fps";
        }
    }
}

namespace {

// A band-limited beat at `rateHz`, starting `offsetSamples` samples of 48 kHz late: a decaying 60 Hz kick
// every half second, an 8 kHz tick every quarter, and a steady 440 Hz tone underneath. Stereo interleaved,
// the right channel a little quieter.
std::vector<float> bandLimitedBeat(int rateHz, double seconds, double offsetSamples) {
    const auto frames = static_cast<size_t>(rateHz * seconds);
    std::vector<float> out(frames * 2);
    const double pi = 3.14159265358979323846;
    for (size_t i = 0; i < frames; i++) {
        const double t = static_cast<double>(i) / rateHz - offsetSamples / 48000.0;
        double v = 0.0;
        if (t >= 0.0) {
            const double inKick = std::fmod(t, 0.5);
            const double inTick = std::fmod(t, 0.25);
            v = 0.8 * std::sin(2.0 * pi * 60.0 * inKick) * std::exp(-inKick / 0.08) +
                0.2 * std::sin(2.0 * pi * 8000.0 * inTick) * std::exp(-inTick / 0.01) + 0.1 * std::sin(2.0 * pi * 440.0 * t);
        }
        out[i * 2] = static_cast<float>(v);
        out[i * 2 + 1] = static_cast<float>(v * 0.8);
    }
    return out;
}

}  // namespace

TEST(AnalysisSession, AHiResSourceIsAnalysedAt48kHzOrLess) {
    AnalysisSession session(192000, kFftSize, 60.0f);
    EXPECT_EQ(session.analysisRateHz(), 48000);
    EXPECT_EQ(session.decimation(), 4);
    session.setSampleRateHz(44100);
    EXPECT_EQ(session.analysisRateHz(), 44100);
    EXPECT_EQ(session.decimation(), 1);
    session.setSampleRateHz(88200);
    EXPECT_EQ(session.analysisRateHz(), 44100);
    EXPECT_EQ(session.decimation(), 2);
    session.setSampleRateHz(0);
    EXPECT_EQ(session.analysisRateHz(), 44100);
}

TEST(AnalysisSession, AHiResSourceHopsAsOftenAsAt48kHz) {
    AnalysisSession at48(48000, kFftSize, 60.0f);
    AnalysisSession at192(192000, kFftSize, 60.0f);
    const auto a = analyse(at48, bandLimitedBeat(48000, 2.0, 0.0), 1024);
    const auto b = analyse(at192, bandLimitedBeat(192000, 2.0, 0.0), 4096);
    // 96,000 samples at 48 kHz in 800-sample hops after the first 2048-sample window.
    EXPECT_EQ(a.size(), (96000u - kFftSize) / 800u + 1u);
    EXPECT_EQ(b.size(), a.size());
}

TEST(AnalysisSession, TheSameMusicAt192kHzReadsAsAt48kHz) {
    // The decimator delays its output by 16 samples of 48 kHz and lands a quarter of an input sample
    // early; the 48 kHz reference is shifted to match, so the two analyses see the same audio.
    const double seconds = 8.0;
    AnalysisSession at48(48000, kFftSize, 60.0f);
    AnalysisSession at192(192000, kFftSize, 60.0f);
    at48.setTuning(3.0f, 60.0f, 0.02f, 0.15f);
    at192.setTuning(3.0f, 60.0f, 0.02f, 0.15f);
    const auto a = analyse(at48, bandLimitedBeat(48000, seconds, 15.25), 800);
    const auto b = analyse(at192, bandLimitedBeat(192000, seconds, 0.0), 3200);

    ASSERT_EQ(a.size(), b.size());
    ASSERT_GT(a.size(), 100u);
    for (size_t i = 8; i < a.size(); i++) {
        EXPECT_NEAR(b[i].rms, a[i].rms, 0.01f * a[i].rms + 1e-4f) << "hop " << i;
        EXPECT_NEAR(b[i].bass, a[i].bass, 0.03f) << "hop " << i;
        EXPECT_NEAR(b[i].mid, a[i].mid, 0.03f) << "hop " << i;
        EXPECT_NEAR(b[i].treble, a[i].treble, 0.03f) << "hop " << i;
    }
    EXPECT_NEAR(b.back().bpm, a.back().bpm, 0.01f * a.back().bpm + 0.01f);
    EXPECT_NEAR(b.back().events[GEODE_EVENT_TRANSIENT].seq, a.back().events[GEODE_EVENT_TRANSIENT].seq, 1.0f);
}
