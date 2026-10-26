#include "geode/analysis/Decimator.hpp"

#include <gtest/gtest.h>

#include <cmath>
#include <numbers>
#include <vector>

using geode::analysis::analysisRateFor;
using geode::analysis::decimationFor;
using geode::analysis::Decimator;

namespace {

// The RMS of the mid outputs of `factor` decimating a sine of `hz` at `rateHz`, after the filter has filled.
double toneRms(int factor, int rateHz, double hz, double seconds) {
    Decimator d(factor);
    const auto frames = static_cast<int>(rateHz * seconds);
    const auto settle = static_cast<int>(d.taps()) * 2;
    double sum = 0.0;
    int count = 0;
    for (int i = 0; i < frames; ++i) {
        const auto x = static_cast<float>(0.5 * std::sin(2.0 * std::numbers::pi * hz * i / rateHz));
        float mid = 0.0f;
        float side = 0.0f;
        if (d.push(x, 0.0f, mid, side) && i > settle) {
            sum += static_cast<double>(mid) * mid;
            ++count;
        }
    }
    return count > 0 ? std::sqrt(sum / count) : 0.0;
}

}  // namespace

TEST(Decimator, HiResRatesAreBroughtToAt48kHzOrLess) {
    EXPECT_EQ(decimationFor(22050), 1);
    EXPECT_EQ(decimationFor(44100), 1);
    EXPECT_EQ(decimationFor(48000), 1);
    EXPECT_EQ(decimationFor(48001), 2);
    EXPECT_EQ(decimationFor(88200), 2);
    EXPECT_EQ(decimationFor(96000), 2);
    EXPECT_EQ(decimationFor(96001), 3);
    EXPECT_EQ(decimationFor(176400), 4);
    EXPECT_EQ(decimationFor(192000), 4);
    EXPECT_EQ(decimationFor(0), 1);
    EXPECT_EQ(decimationFor(-1), 1);
    EXPECT_EQ(analysisRateFor(44100), 44100);
    EXPECT_EQ(analysisRateFor(48000), 48000);
    EXPECT_EQ(analysisRateFor(88200), 44100);
    EXPECT_EQ(analysisRateFor(96000), 48000);
    EXPECT_EQ(analysisRateFor(176400), 44100);
    EXPECT_EQ(analysisRateFor(192000), 48000);
}

TEST(Decimator, AFactorOfOnePassesSamplesThrough) {
    Decimator d(0);
    EXPECT_EQ(d.factor(), 1);
    EXPECT_EQ(d.taps(), 0u);
    float mid = 0.0f;
    float side = 0.0f;
    ASSERT_TRUE(d.push(0.25f, -0.5f, mid, side));
    EXPECT_EQ(mid, 0.25f);
    EXPECT_EQ(side, -0.5f);
}

TEST(Decimator, EveryFactorthSampleCompletesAnOutput) {
    Decimator d(4);
    EXPECT_EQ(d.taps(), static_cast<size_t>(Decimator::kTapsPerFactor * 4 + 1));
    float mid = 0.0f;
    float side = 0.0f;
    for (int round = 0; round < 3; ++round) {
        EXPECT_FALSE(d.push(1.0f, 1.0f, mid, side));
        EXPECT_FALSE(d.push(1.0f, 1.0f, mid, side));
        EXPECT_FALSE(d.push(1.0f, 1.0f, mid, side));
        EXPECT_TRUE(d.push(1.0f, 1.0f, mid, side));
    }
}

TEST(Decimator, ASteadyLevelPassesAtUnityGain) {
    Decimator d(2);
    float mid = 0.0f;
    float side = 0.0f;
    for (int i = 0; i < 1000; ++i) d.push(1.0f, -0.5f, mid, side);
    EXPECT_NEAR(mid, 1.0f, 1e-4f);
    EXPECT_NEAR(side, -0.5f, 1e-4f);
}

TEST(Decimator, MusicBelow16kHzKeepsItsLevel) {
    const double expected = 0.5 / std::sqrt(2.0);
    EXPECT_NEAR(toneRms(4, 192000, 1000.0, 0.2), expected, expected * 0.01);
    EXPECT_NEAR(toneRms(4, 192000, 12000.0, 0.2), expected, expected * 0.01);
    EXPECT_NEAR(toneRms(2, 96000, 6000.0, 0.2), expected, expected * 0.01);
}

TEST(Decimator, WhatWouldFoldBackIsRemoved) {
    const double input = 0.5 / std::sqrt(2.0);
    // 30 kHz would fold back to 18 kHz at 48 kHz, 40 kHz to 8 kHz: both at least 60 dB down.
    EXPECT_LT(toneRms(4, 192000, 30000.0, 0.2), input * 1e-3);
    EXPECT_LT(toneRms(4, 192000, 40000.0, 0.2), input * 1e-3);
    EXPECT_LT(toneRms(2, 96000, 30000.0, 0.2), input * 1e-3);
}

TEST(Decimator, AResetStartsOverFromSilence) {
    Decimator d(2);
    float mid = 0.0f;
    float side = 0.0f;
    for (int i = 0; i < 500; ++i) d.push(1.0f, 1.0f, mid, side);
    d.push(1.0f, 1.0f, mid, side);  // half way through an output
    d.reset();
    EXPECT_FALSE(d.push(0.0f, 0.0f, mid, side));
    ASSERT_TRUE(d.push(0.0f, 0.0f, mid, side));
    EXPECT_EQ(mid, 0.0f);
    EXPECT_EQ(side, 0.0f);
}

TEST(Decimator, OnlyANewFactorStartsOver) {
    Decimator d(2);
    float mid = 0.0f;
    float side = 0.0f;
    for (int i = 0; i < 500; ++i) d.push(1.0f, 1.0f, mid, side);
    d.setFactor(2);
    d.push(1.0f, 1.0f, mid, side);
    ASSERT_TRUE(d.push(1.0f, 1.0f, mid, side));
    EXPECT_NEAR(mid, 1.0f, 1e-4f);
    d.setFactor(4);
    EXPECT_EQ(d.factor(), 4);
    for (int i = 0; i < 3; ++i) EXPECT_FALSE(d.push(0.0f, 0.0f, mid, side));
    ASSERT_TRUE(d.push(0.0f, 0.0f, mid, side));
    EXPECT_EQ(mid, 0.0f);
}
