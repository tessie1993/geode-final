// The shared audio inputs every shader Style reads (AudioInputs, lib_scene_uniforms.glsl): the spectrum
// on one scale with its peak hold, and the steady stereo scope (#300).
#include "geode/viz/AudioInputs.hpp"

#include <gtest/gtest.h>

#include <algorithm>
#include <cmath>
#include <limits>
#include <numbers>
#include <vector>

using geode::viz::AudioInputs;

namespace {

constexpr int kWindow = 576;
constexpr float kFrame = 1.0f / 60.0f;
constexpr double kRate = 48000.0;

struct Tone {
    double hz;
    double amplitude;
};

// A stereo window of `frames` frames from sample `start` of a sum of sines; the right channel is the left
// times `rightGain`.
std::vector<float> window(const std::vector<Tone>& tones, long start = 0, double rightGain = 1.0, int frames = kWindow) {
    std::vector<float> out(static_cast<size_t>(frames) * 2);
    for (int i = 0; i < frames; ++i) {
        double v = 0.0;
        for (const Tone& t : tones) v += t.amplitude * std::sin(2.0 * std::numbers::pi * t.hz * static_cast<double>(start + i) / kRate);
        out[static_cast<size_t>(2 * i)] = static_cast<float>(v);
        out[static_cast<size_t>(2 * i + 1)] = static_cast<float>(v * rightGain);
    }
    return out;
}

std::vector<float> step(AudioInputs& inputs, const std::vector<float>& pcm, float dt = kFrame, float drive = 1.0f) {
    std::vector<float> rows(AudioInputs::kWidth * AudioInputs::kRows);
    inputs.acceptStereo(pcm.data(), static_cast<int>(pcm.size() / 2));
    inputs.update(dt, drive, GeodeFeatureFrame{}, rows.data());
    return rows;
}

// The texel whose centre is nearest `hz`.
int texelOf(double hz) {
    const double at = (AudioInputs::kWidth - 1) * std::log(hz / AudioInputs::kLowHz) / std::log(static_cast<double>(AudioInputs::kHighHz) / AudioInputs::kLowHz);
    return static_cast<int>(std::lround(at));
}

int loudestTexel(const std::array<float, AudioInputs::kWidth>& row) {
    return static_cast<int>(std::max_element(row.begin(), row.end()) - row.begin());
}

}  // namespace

TEST(AudioInputs, TexelsSpan30HzTo16kHzOnALogScale) {
    EXPECT_NEAR(AudioInputs::texelHz(0), 30.0f, 1e-3f);
    EXPECT_NEAR(AudioInputs::texelHz(AudioInputs::kWidth - 1), 16000.0f, 0.1f);
    const float middle = AudioInputs::texelHz((AudioInputs::kWidth - 1) / 2);
    EXPECT_NEAR(middle, std::sqrt(30.0f * 16000.0f), 10.0f);
    for (int i = 1; i < AudioInputs::kWidth; ++i) EXPECT_GT(AudioInputs::texelHz(i), AudioInputs::texelHz(i - 1));
}

TEST(AudioInputs, AToneLandsOnItsTexelAtTheTopOfTheScale) {
    for (const double hz : {1000.0, 5000.0}) {
        AudioInputs inputs;
        step(inputs, window({{hz, 0.9}}));
        const int loudest = loudestTexel(inputs.instant());
        EXPECT_NEAR(loudest, texelOf(hz), 3) << hz << " Hz";
        EXPECT_FLOAT_EQ(inputs.instant()[static_cast<size_t>(loudest)], 1.0f) << hz << " Hz";
    }
    // A window of 576 frames resolves the bass only coarsely: a 100 Hz tone lands in the low bass.
    AudioInputs inputs;
    step(inputs, window({{100.0, 0.9}}));
    const int loudest = loudestTexel(inputs.instant());
    EXPECT_GT(loudest, texelOf(60.0));
    EXPECT_LT(loudest, texelOf(200.0));
}

TEST(AudioInputs, AFullScaleSineReadsZeroDbfs) {
    AudioInputs inputs;
    step(inputs, window({{1000.0, 1.0}}));
    // The ceiling is the loudest texel; at the tilt's pivot that is the sine's own level.
    EXPECT_NEAR(inputs.ceilingDb(), 0.0f, 1.0f);
}

TEST(AudioInputs, BassHeavyMusicLooksBassHeavy) {
    AudioInputs inputs;
    step(inputs, window({{100.0, 0.5}, {4000.0, 0.02}}));
    const float bass = inputs.instant()[static_cast<size_t>(texelOf(100.0))];
    const float treble = inputs.instant()[static_cast<size_t>(texelOf(4000.0))];
    EXPECT_GT(bass, 0.9f);
    EXPECT_GT(treble, 0.0f);
    EXPECT_LT(treble, bass - 0.05f);
}

TEST(AudioInputs, TheWholeSpectrumSharesOneScale) {
    AudioInputs loud;
    AudioInputs soft;
    step(loud, window({{200.0, 0.8}, {2500.0, 0.1}}));
    step(soft, window({{200.0, 0.4}, {2500.0, 0.05}}));
    EXPECT_NEAR(soft.ceilingDb(), loud.ceilingDb() - 6.0206f, 0.01f);
    for (size_t i = 0; i < AudioInputs::kWidth; ++i) EXPECT_NEAR(soft.instant()[i], loud.instant()[i], 1e-3f) << "texel " << i;
}

TEST(AudioInputs, AQuietRoomIsNotStretchedToFillTheScale) {
    AudioInputs inputs;
    // About -70 dBFS: heard, but far under the lowest ceiling.
    step(inputs, window({{1000.0, 3e-4}}));
    EXPECT_FLOAT_EQ(inputs.ceilingDb(), AudioInputs::kMinCeilingDb);
    const float top = *std::max_element(inputs.instant().begin(), inputs.instant().end());
    EXPECT_GT(top, 0.0f);
    EXPECT_LT(top, 0.35f);
}

TEST(AudioInputs, TheCeilingFallsSlowlyAfterALoudPassage) {
    AudioInputs inputs;
    step(inputs, window({{1000.0, 1.0}}));
    const float loud = inputs.ceilingDb();
    step(inputs, window({{1000.0, 0.01}}), 0.5f);
    EXPECT_NEAR(inputs.ceilingDb(), loud - AudioInputs::kCeilingFallDbPerSecond * 0.5f, 1e-3f);
    // Silence lowers it at the same rate, never under the floor.
    step(inputs, window({}), 100.0f);
    EXPECT_FLOAT_EQ(inputs.ceilingDb(), AudioInputs::kMinCeilingDb);
}

TEST(AudioInputs, SilenceReadsNothing) {
    AudioInputs inputs;
    step(inputs, window({}));
    for (size_t i = 0; i < AudioInputs::kWidth; ++i) {
        EXPECT_EQ(inputs.instant()[i], 0.0f);
        EXPECT_EQ(inputs.spectrum()[i], 0.0f);
        EXPECT_EQ(inputs.scopeMid()[i], 0.0f);
    }
}

TEST(AudioInputs, TheSpectrumRisesFastAndFallsSlowly) {
    AudioInputs inputs;
    const auto t = static_cast<size_t>(texelOf(1000.0));
    step(inputs, window({{1000.0, 0.9}}));
    const float risen = inputs.spectrum()[t];
    EXPECT_NEAR(risen, inputs.instant()[t] * (1.0f - std::exp(-AudioInputs::kAttackHz * kFrame)), 1e-5f);
    step(inputs, window({}));
    EXPECT_NEAR(inputs.spectrum()[t], risen * std::exp(-AudioInputs::kDecayHz * kFrame), 1e-4f);
    // No time, no change.
    const float held = inputs.spectrum()[t];
    step(inputs, window({}), 0.0f);
    EXPECT_FLOAT_EQ(inputs.spectrum()[t], held);
}

TEST(AudioInputs, PeaksHoldThenFall) {
    AudioInputs inputs;
    const auto t = static_cast<size_t>(texelOf(1000.0));
    for (int i = 0; i < 60; ++i) step(inputs, window({{1000.0, 0.9}}));
    const float top = inputs.peaks()[t];
    EXPECT_NEAR(top, inputs.spectrum()[t], 1e-6f);
    EXPECT_GT(top, 0.99f);
    // The music moves elsewhere: the 1 kHz texel's peak holds, then falls at its own pace.
    const auto elsewhere = window({{100.0, 0.9}});
    for (int i = 0; i < 18; ++i) step(inputs, elsewhere);
    EXPECT_FLOAT_EQ(inputs.peaks()[t], top);
    EXPECT_LT(inputs.spectrum()[t], top - 0.3f);
    for (int i = 0; i < 18 + 30; ++i) step(inputs, elsewhere);
    // 0.6 s of hold have passed, then about half a second of falling.
    EXPECT_NEAR(inputs.peaks()[t], top - AudioInputs::kPeakFallPerSecond * 0.5f, 0.05f);
    for (int i = 0; i < 240; ++i) step(inputs, elsewhere);
    EXPECT_NEAR(inputs.peaks()[t], inputs.spectrum()[t], 1e-6f);
}

TEST(AudioInputs, PeaksFreezeThroughSilence) {
    AudioInputs inputs;
    const auto t = static_cast<size_t>(texelOf(1000.0));
    for (int i = 0; i < 30; ++i) step(inputs, window({{1000.0, 0.9}}));
    const std::array<float, AudioInputs::kWidth> before = inputs.peaks();
    for (int i = 0; i < 180; ++i) step(inputs, window({}));
    EXPECT_EQ(inputs.peaks(), before);
    EXPECT_LT(inputs.spectrum()[t], 0.01f);
}

TEST(AudioInputs, ASteadyToneStandsStillOnTheScope) {
    AudioInputs inputs;
    std::array<float, AudioInputs::kWidth> previous{};
    bool moved = false;
    int lastOffset = -1;
    for (int frame = 0; frame < 30; ++frame) {
        // 60 frames a second at 48 kHz: each window starts 800 samples on, a different phase of 440 Hz.
        step(inputs, window({{440.0, 0.8}}, frame * 800L));
        if (frame > 0) {
            float worst = 0.0f;
            for (size_t i = 0; i < AudioInputs::kWidth; ++i) worst = std::max(worst, std::fabs(inputs.scopeMid()[i] - previous[i]));
            EXPECT_LT(worst, 0.05f) << "frame " << frame;
            moved = moved || inputs.scopeOffset() != lastOffset;
        }
        previous = inputs.scopeMid();
        lastOffset = inputs.scopeOffset();
    }
    EXPECT_TRUE(moved) << "the span should move through the windows to stay on the same phase";
}

TEST(AudioInputs, LeftAndRightShareTheMidsOffset) {
    AudioInputs inputs;
    for (int frame = 0; frame < 5; ++frame) step(inputs, window({{440.0, 0.8}}, frame * 800L, -0.5));
    for (size_t i = 0; i < AudioInputs::kWidth; ++i) {
        EXPECT_NEAR(inputs.scopeRight()[i], -0.5f * inputs.scopeLeft()[i], 1e-5f) << "texel " << i;
        EXPECT_NEAR(inputs.scopeMid()[i], 0.5f * (inputs.scopeLeft()[i] + inputs.scopeRight()[i]), 1e-5f) << "texel " << i;
    }
}

TEST(AudioInputs, TheFirstWindowStartsAtARisingZeroCrossing) {
    AudioInputs inputs;
    const auto pcm = window({{440.0, 0.8}}, 37);
    step(inputs, pcm);
    const int at = inputs.scopeOffset();
    ASSERT_GT(at, 0);
    const float before = pcm[static_cast<size_t>(2 * (at - 1))];
    const float after = pcm[static_cast<size_t>(2 * at)];
    EXPECT_LT(before, 0.0f);
    EXPECT_GE(after, 0.0f);
    // No earlier rising crossing was passed over.
    for (int o = 1; o < at; ++o) EXPECT_FALSE(pcm[static_cast<size_t>(2 * (o - 1))] < 0.0f && pcm[static_cast<size_t>(2 * o)] >= 0.0f);
    EXPECT_NEAR(inputs.scopeMid()[0], after, 1e-6f);
}

TEST(AudioInputs, TheScopeSpanIsStretchedOverTheRow) {
    AudioInputs inputs;
    // A ramp: the row must run from the span's first sample to its last, evenly.
    std::vector<float> ramp(static_cast<size_t>(kWindow) * 2);
    for (int i = 0; i < kWindow; ++i) {
        ramp[static_cast<size_t>(2 * i)] = static_cast<float>(i - 100) / 1000.0f;
        ramp[static_cast<size_t>(2 * i + 1)] = static_cast<float>(i - 100) / 1000.0f;
    }
    step(inputs, ramp);
    const int at = inputs.scopeOffset();
    EXPECT_EQ(at, 100);  // the ramp crosses zero rising at frame 100
    EXPECT_NEAR(inputs.scopeMid()[0], 0.0f, 1e-6f);
    EXPECT_NEAR(inputs.scopeMid()[AudioInputs::kWidth - 1], static_cast<float>(AudioInputs::kScopeFrames - 1) / 1000.0f, 1e-5f);
    EXPECT_NEAR(inputs.scopeMid()[AudioInputs::kWidth / 2], (AudioInputs::kScopeFrames - 1) * 256.0f / 511.0f / 1000.0f, 1e-5f);
}

TEST(AudioInputs, TheRowsAreLaidOutForTheOldReadsAndTheNew) {
    AudioInputs inputs;
    const auto rows = step(inputs, window({{1000.0, 0.9}}, 0, 0.5), kFrame, 2.0f);
    const auto at = [&](int row, size_t i) { return rows[static_cast<size_t>(row) * AudioInputs::kWidth + i]; };
    for (size_t i = 0; i < AudioInputs::kWidth; ++i) {
        EXPECT_FLOAT_EQ(at(AudioInputs::kSpectrumRow, i), std::min(inputs.spectrum()[i] * 2.0f, 1.5f));
        EXPECT_FLOAT_EQ(at(AudioInputs::kSpectrumRow + 1, i), at(AudioInputs::kSpectrumRow, i));
        EXPECT_FLOAT_EQ(at(AudioInputs::kPeakRow, i), std::min(inputs.peaks()[i] * 2.0f, 1.5f));
        EXPECT_FLOAT_EQ(at(AudioInputs::kInstantRow, i), std::min(inputs.instant()[i] * 2.0f, 1.5f));
        EXPECT_FLOAT_EQ(at(AudioInputs::kScopeLeftRow, i), std::clamp(inputs.scopeLeft()[i] * 2.0f * 0.5f + 0.5f, 0.0f, 1.0f));
        EXPECT_FLOAT_EQ(at(AudioInputs::kScopeRightRow, i), std::clamp(inputs.scopeRight()[i] * 2.0f * 0.5f + 0.5f, 0.0f, 1.0f));
        EXPECT_FLOAT_EQ(at(AudioInputs::kScopeRow, i), std::clamp(inputs.scopeMid()[i] * 2.0f * 0.5f + 0.5f, 0.0f, 1.0f));
        EXPECT_FLOAT_EQ(at(AudioInputs::kScopeRow + 1, i), at(AudioInputs::kScopeRow, i));
    }
    // Drive raises a level, and the clamp holds it at 1.5; the scope stays within 0..1.
    EXPECT_FLOAT_EQ(at(AudioInputs::kInstantRow, static_cast<size_t>(texelOf(1000.0))), 1.5f);
    EXPECT_EQ(*std::max_element(rows.begin() + AudioInputs::kScopeLeftRow * AudioInputs::kWidth, rows.begin() + AudioInputs::kInstantRow * AudioInputs::kWidth), 1.0f);
    EXPECT_EQ(*std::min_element(rows.begin() + AudioInputs::kScopeLeftRow * AudioInputs::kWidth, rows.begin() + AudioInputs::kInstantRow * AudioInputs::kWidth), 0.0f);
}

TEST(AudioInputs, WithoutAWindowTheAnalysisStandsIn) {
    AudioInputs inputs;
    GeodeFeatureFrame features{};
    features.rms = 0.3f;
    for (int b = 0; b < GEODE_BAND_COUNT; ++b) features.bands[b] = static_cast<float>(b) / (GEODE_BAND_COUNT - 1);
    for (int w = 0; w < GEODE_WAVEFORM_POINTS; ++w) features.waveform[w] = static_cast<float>(w) / (GEODE_WAVEFORM_POINTS - 1) - 0.5f;
    features.bands[GEODE_BAND_COUNT - 1] = std::numeric_limits<float>::quiet_NaN();
    std::vector<float> rows(AudioInputs::kWidth * AudioInputs::kRows);
    inputs.update(kFrame, 1.0f, features, rows.data());
    EXPECT_FLOAT_EQ(inputs.instant()[0], 0.0f);
    EXPECT_NEAR(inputs.instant()[AudioInputs::kWidth / 2], 0.5f, 0.01f);
    // A band that is not a number reads 0.
    EXPECT_FLOAT_EQ(inputs.instant()[AudioInputs::kWidth - 1], 0.0f);
    EXPECT_FLOAT_EQ(inputs.scopeMid()[0], -0.5f);
    EXPECT_FLOAT_EQ(inputs.scopeMid()[AudioInputs::kWidth - 1], 0.5f);
    EXPECT_EQ(inputs.scopeLeft(), inputs.scopeMid());
    EXPECT_EQ(inputs.scopeRight(), inputs.scopeMid());
    EXPECT_GT(inputs.peaks()[AudioInputs::kWidth / 2], 0.0f);
}

TEST(AudioInputs, SamplesThatAreNotNumbersAreSilence) {
    AudioInputs inputs;
    std::vector<float> pcm(static_cast<size_t>(kWindow) * 2, std::numeric_limits<float>::quiet_NaN());
    step(inputs, pcm);
    for (size_t i = 0; i < AudioInputs::kWidth; ++i) {
        EXPECT_EQ(inputs.instant()[i], 0.0f);
        EXPECT_EQ(inputs.scopeMid()[i], 0.0f);
    }
}

TEST(AudioInputs, ALongWindowKeepsItsNewestFrames) {
    AudioInputs inputs;
    // Silence, then a tone in the newest kFftSize frames: only the tone is measured.
    std::vector<float> pcm(static_cast<size_t>(AudioInputs::kFftSize + 500) * 2, 0.0f);
    const auto tone = window({{1000.0, 0.9}}, 0, 1.0, AudioInputs::kFftSize);
    std::copy(tone.begin(), tone.end(), pcm.begin() + 1000);
    step(inputs, pcm);
    EXPECT_NEAR(loudestTexel(inputs.instant()), texelOf(1000.0), 3);
    // The tone is at 0.9, so its level is 20 log10(0.9) = -0.92 dBFS, within the same 1 dB as a full-scale sine.
    EXPECT_NEAR(inputs.ceilingDb(), -0.915f, 1.0f);
}

TEST(AudioInputs, AResetForgetsEverything) {
    AudioInputs inputs;
    for (int i = 0; i < 10; ++i) step(inputs, window({{1000.0, 0.9}}));
    inputs.reset();
    EXPECT_FLOAT_EQ(inputs.ceilingDb(), AudioInputs::kMinCeilingDb);
    EXPECT_EQ(inputs.scopeOffset(), 0);
    for (size_t i = 0; i < AudioInputs::kWidth; ++i) {
        EXPECT_EQ(inputs.spectrum()[i], 0.0f);
        EXPECT_EQ(inputs.peaks()[i], 0.0f);
        EXPECT_EQ(inputs.instant()[i], 0.0f);
        EXPECT_EQ(inputs.scopeMid()[i], 0.0f);
        EXPECT_EQ(inputs.scopeLeft()[i], 0.0f);
        EXPECT_EQ(inputs.scopeRight()[i], 0.0f);
    }
    // And with nothing new, a frame falls back to the analysis' (empty) frame.
    std::vector<float> rows(AudioInputs::kWidth * AudioInputs::kRows, 9.0f);
    inputs.update(kFrame, 1.0f, GeodeFeatureFrame{}, rows.data());
    EXPECT_EQ(rows[AudioInputs::kScopeRow * AudioInputs::kWidth], 0.5f);
}

TEST(AudioInputs, MonoSamplesAreHeardOnBothChannels) {
    const std::vector<float> stereo = window({{1000.0, 0.5}});
    std::vector<float> mono(kWindow);
    for (int i = 0; i < kWindow; ++i) mono[static_cast<size_t>(i)] = stereo[static_cast<size_t>(2 * i)];
    AudioInputs fromStereo;
    AudioInputs fromMono;
    std::vector<float> rows(AudioInputs::kWidth * AudioInputs::kRows);
    fromStereo.acceptStereo(stereo.data(), kWindow);
    fromStereo.update(kFrame, 1.0f, GeodeFeatureFrame{}, rows.data());
    fromMono.acceptMono(mono.data(), kWindow);
    fromMono.update(kFrame, 1.0f, GeodeFeatureFrame{}, rows.data());
    EXPECT_EQ(fromMono.spectrum(), fromStereo.spectrum());
    EXPECT_EQ(fromMono.scopeMid(), fromStereo.scopeMid());
    EXPECT_EQ(fromMono.scopeLeft(), fromMono.scopeMid());
    EXPECT_EQ(fromMono.scopeRight(), fromMono.scopeMid());
}

TEST(AudioInputs, ALongMonoChunkKeepsItsNewestSamples) {
    // A chunk longer than the FFT: the oldest samples are dropped, as a long stereo window's are.
    const std::vector<float> stereo = window({{1000.0, 0.5}}, 0, 1.0, AudioInputs::kFftSize + 300);
    std::vector<float> mono(stereo.size() / 2);
    for (size_t i = 0; i < mono.size(); ++i) mono[i] = stereo[2 * i];
    AudioInputs fromStereo;
    AudioInputs fromMono;
    std::vector<float> rows(AudioInputs::kWidth * AudioInputs::kRows);
    fromStereo.acceptStereo(stereo.data(), static_cast<int>(mono.size()));
    fromStereo.update(kFrame, 1.0f, GeodeFeatureFrame{}, rows.data());
    fromMono.acceptMono(mono.data(), static_cast<int>(mono.size()));
    fromMono.update(kFrame, 1.0f, GeodeFeatureFrame{}, rows.data());
    EXPECT_EQ(fromMono.spectrum(), fromStereo.spectrum());
    EXPECT_EQ(fromMono.scopeMid(), fromStereo.scopeMid());
    // Nothing, or a chunk with nothing in it, changes nothing.
    fromMono.acceptMono(nullptr, 10);
    fromMono.acceptMono(mono.data(), 0);
}
