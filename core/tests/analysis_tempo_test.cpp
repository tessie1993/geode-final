// The tempo reads the real tempo, with no preferred BPM (#277): the comb bank's octave choice, its confidence
// and the beat phase it hands the grid.

#include <gtest/gtest.h>

#include <algorithm>
#include <cmath>
#include <vector>

#include "analysis_signals.hpp"
#include "geode/analysis/BeatGrid.hpp"
#include "geode/analysis/PulseReplay.hpp"
#include "geode/analysis/TempoTracker.hpp"

using geode::analysis::BeatGrid;
using geode::analysis::TempoTracker;
namespace sig = analysis_signals;

namespace {

constexpr float kHopRate = 60.0f;

// An onset-strength curve with a bump one hop wide on every beat from 0.5 s: accents[k] is beat k's height
// (cycled), and offbeat (when not 0) adds a bump of that height `offbeatAt` of a beat after each beat.
std::vector<float> beatCurve(double bpm, double seconds, std::vector<float> accents = {1.0f}, float offbeat = 0.0f,
                             double offbeatAt = 0.5) {
    const int hops = static_cast<int>(seconds * kHopRate);
    std::vector<float> curve(static_cast<size_t>(hops), 0.0f);
    const double beat = 60.0 / bpm;
    auto bump = [&](double t, float height) {
        const double centre = t * kHopRate;
        for (int i = std::max(0, static_cast<int>(centre) - 4); i < std::min(hops, static_cast<int>(centre) + 6); i++) {
            curve[static_cast<size_t>(i)] += height * static_cast<float>(std::exp(-0.5 * (i - centre) * (i - centre)));
        }
    };
    for (int k = 0; 0.5 + k * beat < seconds + 0.1; k++) {
        const double t = 0.5 + k * beat;
        bump(t, accents[static_cast<size_t>(k) % accents.size()]);
        if (offbeat > 0.0f) bump(t + offbeatAt * beat, offbeat);
    }
    return curve;
}

TempoTracker track(const std::vector<float>& curve) {
    TempoTracker tempo(kHopRate);
    for (const float v : curve) tempo.step(v);
    return tempo;
}

}  // namespace

TEST(TempoTracker, ReadsEveryTempoWithinTwoPercentAndConfidently) {
    for (const double bpm : {60.0, 90.0, 120.0, 128.0, 140.0, 174.0}) {
        const TempoTracker tempo = track(beatCurve(bpm, 12.0));
        EXPECT_NEAR(tempo.bpm(), bpm, bpm * 0.02) << bpm << " BPM";
        EXPECT_NEAR(tempo.periodFrames(), 60.0 * kHopRate / bpm, 60.0 * kHopRate / bpm * 0.02) << bpm << " BPM";
        EXPECT_GE(tempo.confidence(), 0.6f) << bpm << " BPM";
        EXPECT_LE(tempo.confidence(), 1.0f) << bpm << " BPM";
    }
}

TEST(TempoTracker, NoTempoIsPreferredFromOneEndOfTheRangeToTheOther) {
    // Slow, fast, and ratios of 5 and 3 to the range's ends: every one reads as itself, not a multiple.
    for (const double bpm : {40.0, 75.0, 100.0, 160.0, 200.0, 250.0}) {
        const TempoTracker tempo = track(beatCurve(bpm, 12.0));
        EXPECT_NEAR(tempo.bpm(), bpm, bpm * 0.02) << bpm << " BPM";
        EXPECT_GE(tempo.confidence(), 0.5f) << bpm << " BPM";
    }
}

TEST(TempoTracker, ABackbeatWithEighthHatsReadsTheBeatNotTheHatsOrHalfTime) {
    // Kick and snare on every beat, hats at a third of their height on every offbeat.
    const TempoTracker tempo = track(beatCurve(120.0, 12.0, {1.0f, 0.9f}, 0.35f));
    EXPECT_NEAR(tempo.bpm(), 120.0, 2.4);
    EXPECT_GE(tempo.confidence(), 0.4f);
}

TEST(TempoTracker, ASwungPatternReadsTheBeat) {
    const TempoTracker tempo = track(beatCurve(100.0, 12.0, {1.0f}, 0.4f, 2.0 / 3.0));
    EXPECT_NEAR(tempo.bpm(), 100.0, 2.0);
    EXPECT_GE(tempo.confidence(), 0.5f);
}

TEST(TempoTracker, AnOctaveThatIsACloseCallLowersTheConfidence) {
    const TempoTracker even = track(beatCurve(90.0, 12.0));
    // Beats two and four at 60% of the downbeat: 90 or 45 BPM is a close call.
    const TempoTracker accented = track(beatCurve(90.0, 12.0, {1.0f, 0.6f, 0.8f, 0.6f}));
    EXPECT_NEAR(accented.bpm(), 90.0, 1.8);
    EXPECT_LT(accented.confidence(), even.confidence() - 0.1f);
    EXPECT_GT(accented.confidence(), 0.4f);
}

TEST(TempoTracker, NoiseHasNoConfidentTempo) {
    std::vector<float> curve = sig::uniform(static_cast<size_t>(12 * kHopRate), 77);
    for (auto& v : curve) v += 0.5f;
    EXPECT_LT(track(curve).confidence(), 0.2f);
}

TEST(TempoTracker, ASteadyOrSilentInputHasNoTempo) {
    for (const float level : {0.0f, 0.3f}) {
        const TempoTracker tempo = track(std::vector<float>(720, level));
        EXPECT_EQ(tempo.bpm(), 0.0f) << level;
        EXPECT_EQ(tempo.periodFrames(), 0.0f) << level;
        EXPECT_EQ(tempo.confidence(), 0.0f) << level;
        EXPECT_EQ(tempo.phase(), 0.0f) << level;
    }
}

TEST(TempoTracker, ThePhaseIsZeroOnEveryBeat) {
    const double bpm = 128.0;
    const std::vector<float> curve = beatCurve(bpm, 12.0);
    TempoTracker tempo(kHopRate);
    int checked = 0;
    for (size_t i = 0; i < curve.size(); i++) {
        tempo.step(curve[i]);
        if (i < 8 * kHopRate) continue;
        // The hop nearest each beat: the phase sits within a few hundredths of a beat of 0 (or 1).
        for (int k = 0; k < 30; k++) {
            if (std::fabs(static_cast<double>(i) - (0.5 + k * 60.0 / bpm) * kHopRate) < 0.5) {
                const float phase = tempo.phase();
                EXPECT_LT(std::min(phase, 1.0f - phase), 0.06f) << "hop " << i;
                checked++;
            }
        }
        EXPECT_GE(tempo.phase(), 0.0f);
        EXPECT_LT(tempo.phase(), 1.0f);
    }
    EXPECT_GE(checked, 6);
}

TEST(TempoTracker, ThePhaseRisesThroughTheBeat) {
    const std::vector<float> curve = beatCurve(120.0, 12.0);
    TempoTracker tempo(kHopRate);
    for (const float v : curve) tempo.step(v);
    // 12 s of beats every 30 hops from hop 30: the last beat was at hop 690, and the curve's last hop is 719.
    // The phase is the hops since that beat over the beat, through the one-hop smoothing.
    EXPECT_NEAR(tempo.phase(), 29.0f / 30.0f, 0.04f);
}

TEST(TempoTracker, ResetForgetsEverything) {
    TempoTracker tempo(kHopRate);
    for (const float v : beatCurve(128.0, 10.0)) tempo.step(v);
    ASSERT_GT(tempo.bpm(), 0.0f);
    tempo.reset();
    EXPECT_EQ(tempo.bpm(), 0.0f);
    EXPECT_EQ(tempo.periodFrames(), 0.0f);
    EXPECT_EQ(tempo.confidence(), 0.0f);
    EXPECT_EQ(tempo.phase(), 0.0f);
    // And it reads a new song as if it had never heard the old one.
    const TempoTracker fresh = track(beatCurve(90.0, 12.0));
    for (const float v : beatCurve(90.0, 12.0)) tempo.step(v);
    EXPECT_EQ(tempo.bpm(), fresh.bpm());
    EXPECT_EQ(tempo.confidence(), fresh.confidence());
    EXPECT_EQ(tempo.phase(), fresh.phase());
}

TEST(BeatGrid, WhileLockedThePhaseIsTheTempos) {
    BeatGrid grid;
    EXPECT_FALSE(grid.step(30.0f, 0.3f, 0.4f, false));
    EXPECT_TRUE(grid.locked());
    EXPECT_EQ(grid.phase(), 0.3f);
    // A hit within the tolerance of the beat, either side, is on the grid; one just outside is not.
    EXPECT_TRUE(grid.step(30.0f, BeatGrid::kOnGridTolerance, 0.9f, true));
    EXPECT_EQ(grid.phase(), BeatGrid::kOnGridTolerance);
    EXPECT_TRUE(grid.step(30.0f, 0.9f, 0.9f, true));
    EXPECT_FALSE(grid.step(30.0f, 0.13f, 0.9f, true));
    EXPECT_FALSE(grid.step(30.0f, 0.86f, 0.9f, true));
    EXPECT_FALSE(grid.step(30.0f, 0.5f, 0.9f, true));
    // An offbeat hit leaves the phase where the tempo puts it.
    EXPECT_EQ(grid.phase(), 0.5f);
}

TEST(BeatGrid, UnlockedItRunsOnThePeriodFromTheLastHit) {
    BeatGrid grid;
    // Confidence just under the lock: the tempo's phase is ignored.
    EXPECT_TRUE(grid.step(20.0f, 0.7f, 0.39f, true));
    EXPECT_FALSE(grid.locked());
    EXPECT_EQ(grid.phase(), 0.0f);
    EXPECT_FALSE(grid.step(20.0f, 0.7f, 0.39f, false));
    EXPECT_FLOAT_EQ(grid.phase(), 0.05f);
    for (int i = 0; i < 9; i++) grid.step(20.0f, 0.7f, 0.0f, false);
    EXPECT_NEAR(grid.phase(), 0.5f, 1e-5f);
    // A whole period on, it wraps back round to the start of the beat.
    for (int i = 0; i < 10; i++) grid.step(20.0f, 0.7f, 0.0f, false);
    EXPECT_LT(std::min(grid.phase(), 1.0f - grid.phase()), 1e-4f);
    EXPECT_LT(grid.phase(), 1.0f);
    // With no tempo at all the phase holds, and nothing is locked.
    const float held = grid.phase();
    grid.step(0.0f, 0.7f, 1.0f, false);
    EXPECT_FALSE(grid.locked());
    EXPECT_EQ(grid.phase(), held);
    grid.reset();
    EXPECT_EQ(grid.phase(), 0.0f);
    EXPECT_FALSE(grid.beat());
    EXPECT_FALSE(grid.locked());
}

TEST(PulseReplay, ARenderKeepsEveryBeatOfAClickTrack) {
    // The replayed beats are the ones on the grid: with the tempo read right, that is every beat after the lock.
    const double bpm = 174.0;
    const std::vector<float> curve = beatCurve(bpm, 14.0);
    std::vector<float> rms(curve.size(), 0.1f);
    std::vector<geode::analysis::pulse::Frame> frames(curve.size());
    geode::analysis::pulse::decide(curve.data(), curve.size(), rms.data(), rms.size(), kHopRate, 3.0f, 200.0f, frames.data());
    int beats = 0;
    int expected = 0;
    for (int k = 0; 0.5 + k * 60.0 / bpm < 14.0; k++) {
        const double hop = (0.5 + k * 60.0 / bpm) * kHopRate;
        if (hop < 9.0 * kHopRate || hop > 13.5 * kHopRate) continue;
        expected++;
        for (int i = static_cast<int>(hop) - 1; i <= static_cast<int>(hop) + 2; i++) {
            if (frames[static_cast<size_t>(i)].beat > 0.0f) {
                beats++;
                break;
            }
        }
    }
    EXPECT_GE(expected, 12);
    EXPECT_EQ(beats, expected);
    EXPECT_GE(frames.back().confidence, 0.4f);
}

// The whole analysis, from sound: click tracks read their tempo, and sound with no beat reads no tempo.
TEST(ReactiveAnalyzerTempo, ClickTracksReadWithinTwoPercent) {
    for (const double bpm : {60.0, 90.0, 120.0, 128.0, 140.0, 174.0}) {
        std::vector<float> pcm = sig::white(10.0, -60.0f, 11);
        sig::addClicks(pcm, bpm);
        const auto readings = sig::analyse(pcm);
        ASSERT_FALSE(readings.empty());
        // The last second: the reading holds steady, within 2% and confident enough for the grid.
        for (size_t i = readings.size() - static_cast<size_t>(kHopRate); i < readings.size(); i++) {
            EXPECT_NEAR(readings[i].bpm, bpm, bpm * 0.02) << bpm << " BPM at " << readings[i].seconds << " s";
            EXPECT_GE(readings[i].confidence, BeatGrid::kLockConfidence) << bpm << " BPM at " << readings[i].seconds << " s";
        }
    }
}

TEST(ReactiveAnalyzerTempo, SteadyNoiseHasNoConfidentTempo) {
    for (const bool pinkNoise : {false, true}) {
        const auto pcm = pinkNoise ? sig::pink(4.0, -20.0f, 5) : sig::white(4.0, -20.0f, 3);
        for (const auto& r : sig::analyse(pcm)) {
            EXPECT_LT(r.confidence, BeatGrid::kLockConfidence) << (pinkNoise ? "pink" : "white") << " at " << r.seconds;
        }
    }
}
