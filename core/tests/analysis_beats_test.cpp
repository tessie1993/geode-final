// Beats at every tempo, drums on the right frequencies, drops that fire (#279).

#include <gtest/gtest.h>

#include <cmath>
#include <initializer_list>
#include <utility>
#include <vector>

#include "analysis_signals.hpp"
#include "geode/analysis/DrumChannels.hpp"
#include "geode/analysis/OnsetPeakPicker.hpp"
#include "geode/analysis/StructureTracker.hpp"

using geode::analysis::DrumChannels;
using geode::analysis::OnsetPeakPicker;
using geode::analysis::StructureTracker;
namespace sig = analysis_signals;

namespace {

constexpr float kHopRate = 60.0f;

// Impulses of 1 every `gap` hops after 20 quiet ones; which of the first eight fire (1) and which do not (0).
std::vector<int> impulsesEvery(int gap, float refractorySeconds) {
    OnsetPeakPicker picker(kHopRate, 1.5f, 3.0f, refractorySeconds);
    for (int i = 0; i < 20; i++) picker.accept(0.0f);
    std::vector<int> fired;
    for (int k = 0; k < 8; k++) {
        fired.push_back(picker.accept(1.0f) ? 1 : 0);
        for (int i = 1; i < gap; i++) picker.accept(0.0f);
    }
    return fired;
}

}  // namespace

TEST(OnsetPeakPicker, OnsetsMayComeExactlyTheRefractoryTimeApart) {
    // 0.1 s at 60 hops a second is 6 hops: impulses 6 hops apart all fire, 5 apart every other one.
    const std::vector<int> every = {1, 1, 1, 1, 1, 1, 1, 1};
    const std::vector<int> everyOther = {1, 0, 1, 0, 1, 0, 1, 0};
    EXPECT_EQ(impulsesEvery(6, 0.1f), every);
    EXPECT_EQ(impulsesEvery(5, 0.1f), everyOther);
    // 333 ms (the default gap between beats) is 20 hops: a beat every 20.7 hops, 174 BPM, always fires.
    EXPECT_EQ(impulsesEvery(20, 1.0f / 3.0f), every);
    EXPECT_EQ(impulsesEvery(19, 1.0f / 3.0f), everyOther);
}

TEST(OnsetPeakPicker, SteadyNoiseUnderTwoAndAHalfTimesItsMedianNeverFires) {
    OnsetPeakPicker picker(kHopRate, 1.5f, 3.0f, 0.1f);
    for (int i = 0; i < 90; i++) EXPECT_FALSE(picker.accept(i % 2 == 0 ? 1.0f : 1.2f));
    // The median is 1.2: med + 3 spreads is about 1.5, but the floor is 2.5 x 1.2.
    EXPECT_FALSE(picker.accept(2.7f));
    EXPECT_NEAR(picker.threshold(), 3.0f, 1e-4f);
    for (int i = 0; i < 8; i++) picker.accept(i % 2 == 0 ? 1.0f : 1.2f);
    EXPECT_TRUE(picker.accept(3.2f));
    EXPECT_EQ(picker.strength(), 1.0f);
}

TEST(OnsetPeakPicker, AnAllButSilentCurveDoesNotFire) {
    OnsetPeakPicker picker(kHopRate);
    for (int i = 0; i < 30; i++) picker.accept(0.0f);
    EXPECT_FALSE(picker.accept(0.009f));
    EXPECT_LT(picker.threshold(), 0.009f);
    for (int i = 0; i < 10; i++) picker.accept(0.0f);
    EXPECT_TRUE(picker.accept(0.011f));
}

TEST(OnsetPeakPicker, AVetoedHopNeitherFiresNorStartsARefractoryTime) {
    OnsetPeakPicker picker(kHopRate);
    for (int i = 0; i < 30; i++) picker.accept(0.0f);
    EXPECT_FALSE(picker.accept(1.0f, false));
    EXPECT_EQ(picker.strength(), 0.0f);
    EXPECT_TRUE(picker.accept(1.5f, true));
}

TEST(OnsetPeakPicker, AnOnsetWeakerThanTheMinimumStrengthIsNotOne) {
    OnsetPeakPicker picker(kHopRate, 1.5f, 3.0f, 0.06f, 0.03f, 0.5f);
    for (int i = 0; i < 30; i++) picker.accept(0.0f);
    EXPECT_TRUE(picker.accept(1.0f));
    EXPECT_EQ(picker.strength(), 1.0f);
    for (int i = 0; i < 9; i++) picker.accept(0.0f);
    // 0.3 of the last onset (decayed for ten hops) clears the threshold but not the strength.
    EXPECT_FALSE(picker.accept(0.3f));
    EXPECT_GT(0.3f, picker.threshold());
    EXPECT_TRUE(picker.accept(0.8f));
    EXPECT_NEAR(picker.strength(), 0.8f / std::exp(-11.0f / (kHopRate * OnsetPeakPicker::kPeakMemorySeconds)), 1e-5f);
}

TEST(DrumChannels, EachChannelReadsTheBandsWhoseRealCentreIsInItsRange) {
    DrumChannels drums(64, kHopRate, 48000);
    // 48 kHz: kick 46.9-93.8 Hz, body 164-281 Hz, rattle 2.0-5.9 kHz, hat 7.0-15.2 kHz.
    EXPECT_EQ(drums.rangeFrom(0), 0);
    EXPECT_EQ(drums.rangeTo(0), 3);
    EXPECT_EQ(drums.rangeFrom(1), 5);
    EXPECT_EQ(drums.rangeTo(1), 11);
    EXPECT_EQ(drums.rangeFrom(2), 43);
    EXPECT_EQ(drums.rangeTo(2), 54);
    EXPECT_EQ(drums.rangeFrom(3), 56);
    EXPECT_EQ(drums.rangeTo(3), 64);
    drums.setSampleRateHz(44100);
    EXPECT_EQ(drums.rangeTo(1), 12);
    EXPECT_EQ(drums.bandCount(), 64);
    drums.setSensitivity(5.0f);
    EXPECT_EQ(drums.sensitivity(), 5.0f);
}

namespace {

std::vector<float> frame(std::initializer_list<std::pair<int, int>> ranges, float level) {
    std::vector<float> bands(64, 0.0f);
    for (const auto& [from, to] : ranges) {
        for (int b = from; b < to; b++) bands[static_cast<size_t>(b)] = level;
    }
    return bands;
}

struct Hits {
    float kick;
    float snare;
    float hat;
};

Hits step(DrumChannels& drums, const std::vector<float>& bands) {
    drums.step(bands.data());
    return {drums.kick(), drums.snare(), drums.hat()};
}

void quiet(DrumChannels& drums, int hops) {
    const std::vector<float> silence(64, 0.0f);
    for (int i = 0; i < hops; i++) drums.step(silence.data());
}

}  // namespace

TEST(DrumChannels, AKickASnareAndAHatEachFireTheirOwnChannel) {
    DrumChannels drums(64, kHopRate, 48000);
    quiet(drums, 30);
    Hits h = step(drums, frame({{0, 3}}, 1.0f));
    EXPECT_EQ(h.kick, 1.0f);
    EXPECT_EQ(h.snare, 0.0f);
    EXPECT_EQ(h.hat, 0.0f);
    quiet(drums, 9);
    h = step(drums, frame({{5, 11}, {43, 54}}, 1.0f));
    EXPECT_EQ(h.kick, 0.0f);
    EXPECT_EQ(h.snare, 1.0f);
    EXPECT_EQ(h.hat, 0.0f);
    quiet(drums, 9);
    h = step(drums, frame({{56, 64}}, 1.0f));
    EXPECT_EQ(h.kick, 0.0f);
    EXPECT_EQ(h.snare, 0.0f);
    EXPECT_EQ(h.hat, 1.0f);
}

TEST(DrumChannels, ASnareRisingInTheKickRangeIsStillASnare) {
    DrumChannels drums(64, kHopRate, 48000);
    quiet(drums, 30);
    // The rattle rises with it, which a kick's does not.
    std::vector<float> bands = frame({{5, 11}, {43, 54}}, 1.0f);
    for (int b = 0; b < 3; b++) bands[static_cast<size_t>(b)] = 0.6f;
    const Hits h = step(drums, bands);
    EXPECT_EQ(h.kick, 0.0f);
    EXPECT_GT(h.snare, 0.0f);
}

TEST(DrumChannels, ARattleWithoutABodyIsNotASnare) {
    DrumChannels drums(64, kHopRate, 48000);
    quiet(drums, 30);
    const Hits h = step(drums, frame({{43, 54}}, 1.0f));
    EXPECT_EQ(h.snare, 0.0f);
    EXPECT_EQ(h.kick, 0.0f);
}

TEST(DrumChannels, AKicksTailSwellingAgainIsNotAnotherKick) {
    DrumChannels drums(64, kHopRate, 48000);
    quiet(drums, 30);
    EXPECT_EQ(step(drums, frame({{0, 3}}, 1.0f)).kick, 1.0f);
    // The hold falls 0.05 a hop: six hops of tail at 0.3 leave it at 0.7, so a swell to 0.9 rises only 0.2,
    // a fifth of the kick: too weak to be one.
    for (int i = 0; i < 6; i++) EXPECT_EQ(step(drums, frame({{0, 3}}, 0.3f)).kick, 0.0f);
    EXPECT_EQ(step(drums, frame({{0, 3}}, 0.9f)).kick, 0.0f);
    quiet(drums, 23);
    EXPECT_EQ(step(drums, frame({{0, 3}}, 1.0f)).kick, 1.0f);
}

TEST(DrumChannels, ARangeTheSampleRateCannotReachNeverFires) {
    DrumChannels drums(64, kHopRate, 11025);  // Nyquist 5.5 kHz: no band reaches 7 kHz
    EXPECT_EQ(drums.rangeFrom(3), 64);
    EXPECT_EQ(drums.rangeTo(3), 64);
    quiet(drums, 20);
    for (int i = 0; i < 10; i++) {
        EXPECT_EQ(step(drums, std::vector<float>(64, static_cast<float>(i % 2))).hat, 0.0f);
    }
}

TEST(DrumChannels, ResetForgetsTheLastLevels) {
    DrumChannels drums(64, kHopRate, 48000);
    quiet(drums, 30);
    step(drums, frame({{0, 3}}, 1.0f));
    drums.reset();
    EXPECT_EQ(drums.kick(), 0.0f);
    // The first hop after a reset has nothing to rise from.
    EXPECT_EQ(step(drums, frame({{0, 3}}, 1.0f)).kick, 0.0f);
    quiet(drums, 30);
    EXPECT_EQ(step(drums, frame({{0, 3}}, 1.0f)).kick, 1.0f);
}

TEST(StructureTrackerLevels, TheTracksLevelIgnoresQuietPassages) {
    StructureTracker structure(64, kHopRate);
    const std::vector<float> bands(64, 0.0f);
    for (int i = 0; i < 600; i++) structure.step(bands.data(), std::pow(10.0f, -8.0f / 20.0f), 0.2f);
    EXPECT_NEAR(structure.trackDb(), -8.0f, 1e-3f);
    // A second 30 dB down moves it over 30 seconds, not 6...
    for (int i = 0; i < 60; i++) structure.step(bands.data(), std::pow(10.0f, -38.0f / 20.0f), 0.0f);
    EXPECT_NEAR(structure.trackDb(), -8.98352f, 2e-3f);
    // ...and louder music moves it over 6.
    for (int i = 0; i < 60; i++) structure.step(bands.data(), std::pow(10.0f, -2.0f / 20.0f), 0.0f);
    EXPECT_NEAR(structure.trackDb(), -7.91142f, 2e-3f);
}

namespace {

struct Moment {
    float db;
    float onset;
    bool silent;
};

// Six seconds of music at `db`, a six-second build from 12 dB under it, a half-second dip of `dipDb`, then four
// seconds `slamDb` over it: the drop lands on hop 750.
std::vector<Moment> buildAndDrop(float db, float slamDb = 5.0f, float dipDb = -30.0f, bool dip = true, bool silentDip = false) {
    std::vector<Moment> moments;
    for (int i = 0; i < 360; i++) moments.push_back({db, 0.2f, false});
    for (int i = 0; i < 360; i++) moments.push_back({db - 12.0f + 12.0f * i / 359.0f, 0.2f + 0.6f * i / 359.0f, false});
    if (dip) {
        for (int i = 0; i < 30; i++) moments.push_back({db + dipDb, 0.0f, silentDip});
    }
    for (int i = 0; i < 240; i++) moments.push_back({db + slamDb, 0.5f, false});
    return moments;
}

// Eight seconds at `db`, `quietSeconds` at `quietDb` under it (or silence), then two seconds at `backDb` from it.
std::vector<Moment> quietThenBack(float db, float quietSeconds = 2.5f, float quietDb = -20.0f, float backDb = 0.0f, bool silent = false) {
    std::vector<Moment> moments;
    for (int i = 0; i < 480; i++) moments.push_back({db, 0.3f, false});
    for (int i = 0; i < static_cast<int>(quietSeconds * kHopRate); i++) moments.push_back({db + quietDb, 0.0f, silent});
    for (int i = 0; i < 120; i++) moments.push_back({db + backDb, 0.3f, false});
    return moments;
}

struct Fired {
    std::vector<int> drops;
    std::vector<int> arrivals;
};

Fired play(const std::vector<Moment>& moments) {
    StructureTracker structure(64, kHopRate);
    const std::vector<float> bands(64, 0.0f);
    Fired fired;
    for (size_t i = 0; i < moments.size(); i++) {
        if (moments[i].silent) {
            structure.stepSilent();
        } else {
            structure.step(bands.data(), std::pow(10.0f, moments[i].db / 20.0f), moments[i].onset);
        }
        if (structure.drop()) fired.drops.push_back(static_cast<int>(i));
        if (structure.arrival()) fired.arrivals.push_back(static_cast<int>(i));
    }
    return fired;
}

}  // namespace

TEST(StructureTrackerLevels, ABuildAndDropFiresTheDropAtAnyMasteringLevel) {
    for (const float db : {-8.0f, -28.0f}) {
        const Fired fired = play(buildAndDrop(db));
        EXPECT_EQ(fired.drops, std::vector<int>{750}) << db << " dB";
        EXPECT_TRUE(fired.arrivals.empty()) << db << " dB";
    }
    EXPECT_EQ(play(buildAndDrop(-8.0f, 5.0f, -30.0f, true, true)).drops, std::vector<int>{750}) << "a silent dip";
}

TEST(StructureTrackerLevels, NoDropWithoutABuildADipOrASlam) {
    EXPECT_TRUE(play(buildAndDrop(-8.0f, 5.0f, -30.0f, false)).drops.empty()) << "no dip";
    EXPECT_TRUE(play(buildAndDrop(-8.0f, 5.0f, -8.0f)).drops.empty()) << "a dip of only 8 dB";
    EXPECT_TRUE(play(buildAndDrop(-8.0f, -3.0f)).drops.empty()) << "a slam under the track's level";
    std::vector<Moment> steady(720, Moment{-8.0f, 0.2f, false});
    for (int i = 0; i < 30; i++) steady.push_back({-38.0f, 0.0f, false});
    for (int i = 0; i < 240; i++) steady.push_back({-3.0f, 0.5f, false});
    EXPECT_TRUE(play(steady).drops.empty()) << "no build";
}

TEST(StructureTrackerLevels, MusicReturningFromAQuietPassageArrives) {
    for (const float db : {-8.0f, -28.0f}) {
        EXPECT_EQ(play(quietThenBack(db)).arrivals, std::vector<int>{630}) << db << " dB";
    }
    EXPECT_EQ(play(quietThenBack(-8.0f, 2.5f, -20.0f, 0.0f, true)).arrivals, std::vector<int>{630}) << "a silent pause";
    EXPECT_TRUE(play(quietThenBack(-8.0f, 1.5f)).arrivals.empty()) << "too short a pause";
    EXPECT_TRUE(play(quietThenBack(-8.0f, 2.5f, -10.0f)).arrivals.empty()) << "not quiet enough";
    EXPECT_TRUE(play(quietThenBack(-8.0f, 2.5f, -20.0f, -10.0f)).arrivals.empty()) << "not back up";
}

// The whole analysis, from sound.
TEST(ReactiveAnalyzerBeats, EveryBeatOfA174BpmKickPatternIsABeat) {
    for (const auto& [bpm, gapMs] : {std::pair{174.0, 1000.0f / 3.0f}, std::pair{220.0, 200.0f}}) {
        std::vector<float> pcm(sig::samplesOf(8.0), 0.0f);
        const std::vector<double> kicks = sig::every(0.25, 60.0 / bpm, 7.7);
        sig::place(pcm, sig::kick(), kicks);
        pcm = sig::sum(sig::scaledTo(pcm, -14.0f), sig::white(8.0, -60.0f, 4));
        std::vector<double> beats;
        for (const auto& r : sig::analyse(pcm, gapMs)) {
            if (r.beat) beats.push_back(r.seconds);
        }
        EXPECT_EQ(beats.size(), kicks.size()) << bpm << " BPM";
        for (const double kick : kicks) EXPECT_TRUE(sig::within(kick, beats, 0.0, 0.1)) << bpm << " BPM, kick at " << kick;
    }
}

TEST(ReactiveAnalyzerBeats, TheKickFiresOnKicksAndNotOnTheBassLineOrTheSnare) {
    // Kick on one and three, snare on two and four, a held bass line changing note on the offbeats.
    const double seconds = 12.0;
    const std::vector<double> kicks = sig::every(0.5, 1.0, seconds - 0.3);
    const std::vector<double> snares = sig::every(1.0, 1.0, seconds - 0.3);
    const std::vector<double> notes = sig::every(0.75, 1.0, seconds);
    std::vector<float> pcm(sig::samplesOf(seconds), 0.0f);
    sig::place(pcm, sig::kick(), kicks);
    sig::place(pcm, sig::snare(), snares, 0.6f);
    pcm = sig::sum(pcm, sig::legatoBass(seconds, {55.0, 65.4, 73.4, 49.0}, 1.0, 0.75, 0.35));
    pcm = sig::sum(sig::scaledTo(pcm, -14.0f), sig::white(seconds, -60.0f, 6));

    std::vector<double> kickHits;
    std::vector<double> snareHits;
    for (const auto& r : sig::analyse(pcm)) {
        if (r.kick > 0.0f) kickHits.push_back(r.seconds);
        if (r.snare > 0.0f) snareHits.push_back(r.seconds);
    }
    for (const double kick : kicks) EXPECT_TRUE(sig::within(kick, kickHits, 0.0, 0.1)) << "kick at " << kick;
    for (const double hit : kickHits) {
        EXPECT_TRUE(sig::within(hit, kicks, 0.1, 0.01)) << "a kick event at " << hit << " with no kick";
        EXPECT_FALSE(sig::within(hit, snares, 0.1, 0.01)) << "a kick event on the snare at " << hit;
        EXPECT_FALSE(sig::within(hit, notes, 0.1, 0.01)) << "a kick event on the bass at " << hit;
    }
    for (const double snare : snares) EXPECT_TRUE(sig::within(snare, snareHits, 0.0, 0.1)) << "snare at " << snare;
    for (const double hit : snareHits) EXPECT_TRUE(sig::within(hit, snares, 0.1, 0.01)) << "a snare event at " << hit;
}
