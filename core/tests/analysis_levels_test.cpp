// Quiet rooms stay quiet (#278): bands at least three bins wide, a measured noise floor, one shared scale
// for every band, log-compressed whitening, and no DC.

#include <gtest/gtest.h>

#include <algorithm>
#include <cmath>
#include <vector>

#include "analysis_signals.hpp"
#include "geode/analysis/AdaptiveRange.hpp"
#include "geode/analysis/AdaptiveWhitening.hpp"
#include "geode/analysis/LogBands.hpp"

using geode::analysis::AdaptiveRange;
using geode::analysis::AdaptiveWhitening;
using geode::analysis::LogBands;
namespace sig = analysis_signals;

namespace {

constexpr float kTol = 1e-4f;

std::vector<float> filled(int n, float value) {
    return std::vector<float>(static_cast<size_t>(n), value);
}

float follow(float from, float to, float tau, float dt) {
    return from + (to - from) * (1.0f - std::exp(-dt / tau));
}

}  // namespace

TEST(LogBands, EveryBandAveragesAtLeastThreeBinsAtEveryRate) {
    for (const int rate : {22050, 44100, 48000, 96000}) {
        const LogBands bands(64, 2048, rate);
        for (int b = 0; b < 64; b++) {
            EXPECT_GE(bands.lastBin(b) - bands.firstBin(b) + 1, LogBands::kMinBins) << rate << " Hz, band " << b;
            if (b > 0) {
                EXPECT_GE(bands.firstBin(b), bands.firstBin(b - 1)) << rate << " Hz, band " << b;
                EXPECT_GE(bands.lastBin(b), bands.lastBin(b - 1)) << rate << " Hz, band " << b;
            }
        }
    }
}

TEST(LogBands, LowBandsStepOneBinApartAndShareTheirNeighbours) {
    const LogBands bands(64, 2048, 48000);
    EXPECT_EQ(bands.firstBin(0), 1);
    EXPECT_EQ(bands.lastBin(0), 3);
    EXPECT_EQ(bands.firstBin(1), 2);
    EXPECT_EQ(bands.lastBin(1), 4);
    EXPECT_EQ(bands.firstBin(63), 619);
    EXPECT_EQ(bands.lastBin(63), 682);
}

TEST(LogBands, ABandReportsTheFrequenciesOfTheBinsItAverages) {
    const LogBands bands(64, 2048, 48000);  // bins 23.4375 Hz apart
    EXPECT_NEAR(bands.lowerHz(0), 11.71875f, kTol);
    EXPECT_NEAR(bands.upperHz(0), 82.03125f, kTol);
    EXPECT_NEAR(bands.centreHz(0), 46.875f, kTol);
    EXPECT_NEAR(bands.lowerHz(63), 14496.09375f, 1e-2f);
    EXPECT_NEAR(bands.upperHz(63), 15996.09375f, 1e-2f);
    EXPECT_NEAR(bands.centreHz(63), 15246.09375f, 1e-2f);

    LogBands moved(64, 2048, 48000);
    moved.setSampleRateHz(96000);
    EXPECT_EQ(moved.sampleRateHz(), 96000);
    EXPECT_NEAR(moved.centreHz(0), 93.75f, kTol);
}

TEST(LogBands, EnergyIsTheMeanTiltedPowerOfTheBandsBins) {
    const LogBands bands(64, 2048, 48000);
    // Magnitudes of N/2 are unit amplitude after the 2/N scale; the pink tilt weighs a bin by f / 1 kHz.
    std::vector<float> magnitudes(1025, 1024.0f);
    std::vector<float> power(64);
    bands.energy(magnitudes.data(), power.data());
    EXPECT_NEAR(power[0], 0.046875f, 1e-6f);  // bins 1-3: (1 + 2 + 3) / 3 * 23.4375 / 1000
    EXPECT_NEAR(power[1], 0.0703125f, 1e-6f);
    std::vector<float> db(64);
    bands.energyDb(magnitudes.data(), db.data());
    EXPECT_NEAR(db[0], -13.290587f, 1e-3f);
    std::vector<float> silent(1025, 0.0f);
    bands.energyDb(silent.data(), db.data());
    EXPECT_EQ(db[0], AdaptiveRange::kSilenceDb);
}

TEST(AdaptiveRange, NothingReadsUntilASoundStandsAboveTheFloors) {
    AdaptiveRange range(8);
    std::vector<float> out(8, 1.0f);
    std::vector<float> excess(8, 1.0f);
    range.normalize(filled(8, -90.0f).data(), 1.0f / 60.0f, out.data(), excess.data());
    EXPECT_FALSE(range.primed());
    EXPECT_EQ(range.levelDb(), AdaptiveRange::kSilenceDb);
    for (int b = 0; b < 8; b++) {
        EXPECT_EQ(out[b], 0.0f);
        EXPECT_EQ(excess[b], 0.0f);
        EXPECT_EQ(range.floorDb(b), -90.0f);
    }
    EXPECT_EQ(range.scaled(-10.0f, -90.0f), 0.0f);
}

TEST(AdaptiveRange, EveryBandReadsOnTheSharedLevel) {
    AdaptiveRange range(8);
    std::vector<float> out(8);
    std::vector<float> excess(8);
    range.normalize(filled(8, -40.0f).data(), 1.0f / 60.0f, out.data(), excess.data());
    ASSERT_TRUE(range.primed());
    EXPECT_FLOAT_EQ(range.levelDb(), -40.0f);
    // A band at the shared level reads half the scale: headroom over span.
    for (int b = 0; b < 8; b++) {
        EXPECT_FLOAT_EQ(range.floorDb(b), AdaptiveRange::kNoiseCeilingDb);
        EXPECT_NEAR(out[b], 0.5f, kTol);
        EXPECT_NEAR(excess[b], 0.5f, kTol);
    }
    // The scale runs from a span under the top (the level plus the headroom) to the top.
    EXPECT_NEAR(range.scaled(-22.0f, -80.0f), 1.0f, kTol);
    EXPECT_NEAR(range.scaled(-10.0f, -80.0f), 1.0f, kTol);
    EXPECT_NEAR(range.scaled(-49.0f, -80.0f), 0.25f, kTol);
    EXPECT_NEAR(range.scaled(-58.0f, -80.0f), 0.0f, kTol);
    EXPECT_NEAR(range.scaled(-70.0f, -80.0f), 0.0f, kTol);
    // A band's own noise floor raises the bottom of its scale, never the top.
    EXPECT_NEAR(range.scaled(-40.0f, -60.0f), 8.0f / 26.0f, kTol);
    // A floor so high its margin reaches the top leaves nothing to read.
    EXPECT_EQ(range.scaled(-20.0f, -30.0f), 0.0f);
    EXPECT_EQ(range.scaled(-20.0f, -34.0f), 0.0f);
}

TEST(AdaptiveRange, TheSharedLevelIsTheMeanOfTheBandsHeardAndExcessIsNotClamped) {
    AdaptiveRange range(8);
    std::vector<float> in = filled(8, -40.0f);
    in[4] = -10.0f;
    std::vector<float> out(8);
    std::vector<float> excess(8);
    range.normalize(in.data(), 1.0f / 60.0f, out.data(), excess.data());
    EXPECT_NEAR(range.levelDb(), -36.25f, kTol);
    EXPECT_NEAR(out[0], 0.3958333f, kTol);
    EXPECT_NEAR(out[4], 1.0f, kTol);
    EXPECT_NEAR(excess[0], 0.3958333f, kTol);
    EXPECT_NEAR(excess[4], 1.2291667f, kTol);
    // Without somewhere to put it, the excess is simply not reported.
    range.normalize(in.data(), 1.0f / 60.0f, out.data());
    EXPECT_NEAR(out[0], 0.3958333f, 1e-3f);
}

TEST(AdaptiveRange, FourBandsAboveTheirFloorsMoveTheLevelAndThreeDoNot) {
    std::vector<float> out(8);
    std::vector<float> in = filled(8, -95.0f);
    for (int b = 0; b < 3; b++) in[b] = -40.0f;
    AdaptiveRange three(8);
    three.normalize(in.data(), 1.0f / 60.0f, out.data());
    EXPECT_FALSE(three.primed());
    EXPECT_EQ(out[0], 0.0f);

    in[3] = -40.0f;
    AdaptiveRange four(8);
    four.normalize(in.data(), 1.0f / 60.0f, out.data());
    EXPECT_TRUE(four.primed());
    EXPECT_FLOAT_EQ(four.levelDb(), -40.0f);
    EXPECT_NEAR(out[0], 0.5f, kTol);
    EXPECT_EQ(out[7], 0.0f);
}

TEST(AdaptiveRange, ASteadyToneReadsTheSameForever) {
    AdaptiveRange range(8);
    std::vector<float> out(8);
    for (int hop = 0; hop < 3600; hop++) range.normalize(filled(8, -40.0f).data(), 1.0f / 60.0f, out.data());
    // A minute on, the floor has not crept up to the tone: it never rises past the noise ceiling.
    EXPECT_FLOAT_EQ(range.floorDb(0), AdaptiveRange::kNoiseCeilingDb);
    EXPECT_FLOAT_EQ(range.levelDb(), -40.0f);
    EXPECT_NEAR(out[0], 0.5f, kTol);
}

TEST(AdaptiveRange, TheFloorFallsFastAndRisesSlowly) {
    AdaptiveRange range(4);
    std::vector<float> out(4);
    range.normalize(filled(4, -90.0f).data(), 0.5f, out.data());
    EXPECT_FLOAT_EQ(range.floorDb(0), -90.0f);
    range.normalize(filled(4, -100.0f).data(), 0.5f, out.data());
    const float fell = follow(-90.0f, -100.0f, AdaptiveRange::kFloorFallSeconds, 0.5f);
    EXPECT_NEAR(range.floorDb(0), fell, kTol);
    EXPECT_NEAR(fell, -96.321205f, kTol);
    range.normalize(filled(4, -85.0f).data(), 4.0f, out.data());
    EXPECT_NEAR(range.floorDb(0), follow(fell, -85.0f, AdaptiveRange::kFloorRiseSeconds, 4.0f), kTol);
    EXPECT_NEAR(range.floorDb(0), -89.164839f, kTol);
}

TEST(AdaptiveRange, TheLevelRisesWithinASecondAndFallsOverFour) {
    AdaptiveRange range(8);
    std::vector<float> out(8);
    range.normalize(filled(8, -40.0f).data(), 1.0f, out.data());
    range.normalize(filled(8, -30.0f).data(), 1.0f, out.data());
    EXPECT_NEAR(range.levelDb(), -33.678794f, kTol);
    range.normalize(filled(8, -50.0f).data(), 4.0f, out.data());
    EXPECT_NEAR(range.levelDb(), -43.995764f, kTol);
    // Nothing above the floors: the level holds where the music left it.
    range.normalize(filled(8, -95.0f).data(), 4.0f, out.data());
    EXPECT_NEAR(range.levelDb(), -43.995764f, kTol);
}

TEST(AdaptiveRange, AGroupReadsItsMeanPowerOverItsMeanFloor) {
    AdaptiveRange range(4);
    std::vector<float> out(4);
    range.normalize(filled(4, -40.0f).data(), 1.0f / 60.0f, out.data());
    const std::vector<float> power = {1e-4f, 1e-6f, 1e-4f, 1e-4f};
    EXPECT_NEAR(range.scaledMean(power.data(), 0, 1), 0.5f, kTol);
    EXPECT_NEAR(range.scaledMean(power.data(), 0, 2), 0.41758094f, kTol);
    EXPECT_NEAR(range.scaledMean(power.data(), 2, 4), 0.5f, kTol);
}

TEST(AdaptiveRange, AHopIsHeardTenDecibelsAboveTheFloors) {
    AdaptiveRange range(4);
    std::vector<float> out(4);
    range.normalize(filled(4, -40.0f).data(), 1.0f / 60.0f, out.data());  // every floor at the -80 dB ceiling
    EXPECT_NEAR(range.floorPower(0), 1.5848932e-7f, 1e-12f);
    EXPECT_TRUE(range.heard(filled(4, std::pow(10.0f, -6.99f)).data()));
    EXPECT_FALSE(range.heard(filled(4, std::pow(10.0f, -7.01f)).data()));
    // One loud band is enough when the rest are silent: the hop is heard as a whole.
    std::vector<float> one(4, 0.0f);
    one[2] = std::pow(10.0f, -6.39f);
    EXPECT_TRUE(range.heard(one.data()));
    one[2] = std::pow(10.0f, -6.41f);
    EXPECT_FALSE(range.heard(one.data()));
}

TEST(AdaptiveRange, WarmupCountsTheSecondsOfSoundAndResetForgets) {
    AdaptiveRange range(4, 1.5f);
    std::vector<float> out(4);
    range.normalize(filled(4, AdaptiveRange::kSilenceDb).data(), 0.75f, out.data());
    EXPECT_EQ(range.warmup(), 0.0f);
    range.normalize(filled(4, -40.0f).data(), 0.75f, out.data());
    EXPECT_NEAR(range.warmup(), 0.5f, kTol);
    range.normalize(filled(4, -40.0f).data(), 0.0f, out.data());
    EXPECT_NEAR(range.warmup(), 0.5f, kTol);
    range.normalize(filled(4, -40.0f).data(), 1.5f, out.data());
    EXPECT_EQ(range.warmup(), 1.0f);
    range.reset();
    EXPECT_FALSE(range.primed());
    EXPECT_EQ(range.levelDb(), AdaptiveRange::kSilenceDb);
    EXPECT_EQ(range.warmup(), 0.0f);
    EXPECT_EQ(range.floorDb(0), AdaptiveRange::kSilenceDb);
    range.normalize(filled(4, -90.0f).data(), 1.0f, out.data());
    EXPECT_EQ(range.floorDb(0), -90.0f);
    EXPECT_EQ(out[0], 0.0f);
}

TEST(AdaptiveWhitening, ABandOverItsPeakIsLogCompressed) {
    AdaptiveWhitening whitening(1);
    const std::vector<float> noFloor = {0.0f};
    float out = 0.0f;
    whitening.whiten(std::vector<float>{1.0f}.data(), noFloor.data(), 0.0f, &out);
    EXPECT_NEAR(out, 1.0f, kTol);
    whitening.whiten(std::vector<float>{0.25f}.data(), noFloor.data(), 0.0f, &out);
    EXPECT_NEAR(out, 0.40367746f, kTol);  // log(1 + 3 x 0.25) / log(4)
    // The peak decays over two seconds.
    whitening.whiten(std::vector<float>{0.25f}.data(), noFloor.data(), 2.0f, &out);
    EXPECT_NEAR(out, 0.80172982f, kTol);
    whitening.reset();
    whitening.whiten(std::vector<float>{0.25f}.data(), noFloor.data(), 0.0f, &out);
    EXPECT_NEAR(out, 1.0f, kTol);
}

TEST(AdaptiveWhitening, ABandIsNeverLiftedFromUnderItsFloor) {
    AdaptiveWhitening whitening(1);
    float out = 0.0f;
    whitening.whiten(std::vector<float>{1e-6f}.data(), std::vector<float>{1e-4f}.data(), 0.0f, &out);
    EXPECT_NEAR(out, 0.02132217f, kTol);  // log(1 + 3 x 0.01) / log(4)
}

TEST(AdaptiveWhitening, NorFromFortyDecibelsUnderTheLoudestBand) {
    AdaptiveWhitening whitening(2);
    const std::vector<float> floors = {0.0f, 0.0f};
    const std::vector<float> in = {1.0f, 1e-6f};
    std::vector<float> out(2);
    // On the first hop nothing is loud yet, so the quiet band is its own peak.
    whitening.whiten(in.data(), floors.data(), 0.0f, out.data());
    EXPECT_NEAR(out[1], 1.0f, kTol);
    whitening.whiten(in.data(), floors.data(), 0.0f, out.data());
    EXPECT_NEAR(out[0], 1.0f, kTol);
    EXPECT_NEAR(out[1], 0.02132217f, kTol);
}

// The whole analysis, from sound.
TEST(ReactiveAnalyzerLevels, RoomNoiseAndMicHissReadNothingAndFireNothing) {
    const std::vector<std::vector<float>> rooms = {sig::white(4.0, -60.0f, 3), sig::pink(4.0, -60.0f, 5),
                                                   sig::pink(4.0, -50.0f, 5)};
    for (size_t room = 0; room < rooms.size(); room++) {
        for (const auto& r : sig::analyse(rooms[room])) {
            EXPECT_LT(r.loudestBand, 0.05f) << "room " << room << " at " << r.seconds;
            EXPECT_LT(r.bass, 0.05f) << "room " << room << " at " << r.seconds;
            EXPECT_LT(r.mid, 0.05f) << "room " << room << " at " << r.seconds;
            EXPECT_LT(r.treble, 0.05f) << "room " << room << " at " << r.seconds;
            EXPECT_EQ(r.flux, 0.0f) << "room " << room << " at " << r.seconds;
            EXPECT_FALSE(r.beat) << "room " << room << " at " << r.seconds;
            EXPECT_EQ(r.kick + r.snare + r.hat, 0.0f) << "room " << room << " at " << r.seconds;
        }
    }
}

TEST(ReactiveAnalyzerLevels, ASteadyToneReadsSteadilyWithoutPumping) {
    float lowest = 1.0f;
    float highest = 0.0f;
    // A held bass note in a quiet room, once the first second has settled the floors.
    for (const auto& r : sig::analyse(sig::sum(sig::tone(4.0, 110.0, -20.0f), sig::white(4.0, -60.0f, 9)))) {
        if (r.seconds < 1.0) continue;
        EXPECT_FALSE(r.beat) << r.seconds;
        lowest = std::min(lowest, r.bass);
        highest = std::max(highest, r.bass);
        EXPECT_LT(r.mid, 0.01f) << r.seconds;
    }
    EXPECT_GT(lowest, 0.62f);
    EXPECT_LT(highest, 0.74f);
    EXPECT_LT(highest - lowest, 0.005f);
}

TEST(ReactiveAnalyzerLevels, PinkAndWhiteNoiseKeepTheirShape) {
    for (const auto& r : sig::analyse(sig::white(4.0, -20.0f, 3))) {
        if (r.seconds >= 1.0) EXPECT_GT(r.treble - r.bass, 0.3f) << "white at " << r.seconds;
    }
    for (const auto& r : sig::analyse(sig::pink(4.0, -20.0f, 5))) {
        if (r.seconds >= 1.0) EXPECT_LT(std::fabs(r.treble - r.bass), 0.15f) << "pink at " << r.seconds;
    }
}

TEST(ReactiveAnalyzerLevels, AQuietRoomThenMusicBeatsOnlyOnTheMusic) {
    std::vector<float> pcm = sig::white(12.0, -60.0f, 21);
    const std::vector<double> clicks = sig::addClicks(pcm, 120.0, 4.25);
    const auto readings = sig::analyse(pcm, 1000.0f / 3.0f);
    std::vector<double> beats;
    for (const auto& r : readings) {
        if (r.seconds < 4.25) {
            EXPECT_FALSE(r.beat) << r.seconds;
            EXPECT_LT(r.loudestBand, 0.05f) << r.seconds;
        } else if (r.beat) {
            beats.push_back(r.seconds);
        }
    }
    EXPECT_EQ(beats.size(), clicks.size());
    for (const double click : clicks) {
        EXPECT_TRUE(sig::within(click, beats, 0.0, 0.1)) << "click at " << click;
    }
}

TEST(ReactiveAnalyzerLevels, ADcOffsetIsNotSound) {
    for (const auto& r : sig::analyse(std::vector<float>(static_cast<size_t>(sig::kRate), 0.2f))) {
        EXPECT_EQ(r.rms, 0.0f);
        EXPECT_EQ(r.loudestBand, 0.0f);
    }
    const std::vector<float> clean = sig::tone(2.0, 440.0, -20.0f);
    std::vector<float> offset = clean;
    for (auto& v : offset) v += 0.3f;
    const auto a = sig::analyse(clean);
    const auto b = sig::analyse(offset);
    ASSERT_EQ(a.size(), b.size());
    for (size_t i = 0; i < a.size(); i++) {
        EXPECT_NEAR(a[i].rms, b[i].rms, 1e-5f) << i;
        EXPECT_NEAR(a[i].bass, b[i].bass, 1e-3f) << i;
        EXPECT_NEAR(a[i].mid, b[i].mid, 1e-3f) << i;
        EXPECT_NEAR(a[i].treble, b[i].treble, 1e-3f) << i;
        EXPECT_NEAR(a[i].loudestBand, b[i].loudestBand, 1e-3f) << i;
    }
}
