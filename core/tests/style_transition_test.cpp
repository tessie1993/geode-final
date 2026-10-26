#include "geode/viz/StyleTransition.hpp"

#include <gtest/gtest.h>

#include <algorithm>
#include <array>
#include <limits>
#include <set>
#include <string>

namespace transition = geode::viz::transition;
using transition::DownbeatGate;
using transition::Progress;

namespace {

constexpr float kTol = 1e-5f;
// A sixteenth of a second: exact in binary, so a clock stepped by it lands exactly on its marks.
constexpr float kStep = 0.0625f;

GeodeFeatureFrame heard(float bpm = 120.0f, float confidence = 0.8f) {
    GeodeFeatureFrame f{};
    f.bpm = bpm;
    f.pulseConfidence = confidence;
    return f;
}

GeodeFeatureFrame downbeat() {
    GeodeFeatureFrame f = heard();
    f.downbeat = 1.0f;
    return f;
}

}  // namespace

TEST(StyleTransition, TheLooksAreTheCompositesOwnFirstThenTheLibrarys) {
    EXPECT_EQ(transition::kLookCount, 17);
    EXPECT_EQ(std::string(transition::kSafeLooks[0]), "fade");
    EXPECT_EQ(std::string(transition::kSafeLooks[1]), "melt");
    EXPECT_EQ(std::string(transition::kSafeLooks[2]), "slide");
    EXPECT_EQ(std::string(transition::kSafeLooks[3]), "zoom");
    EXPECT_EQ(std::string(transition::kSafeLooks[transition::kFadeSlot]), "fade");
    std::set<std::string> distinct(transition::kSafeLooks.begin(), transition::kSafeLooks.end());
    EXPECT_EQ(distinct.size(), transition::kSafeLooks.size());
    EXPECT_EQ(distinct.count(transition::kRandom), 0U);
    EXPECT_EQ(distinct.count("cut"), 0U) << "Geode never hard-cuts";
}

TEST(StyleTransition, SlotsNameTheSafeLooksOnly) {
    EXPECT_EQ(transition::slotOf("fade"), 0);
    EXPECT_EQ(transition::slotOf("zoom"), 3);
    EXPECT_EQ(transition::slotOf("Dreamy"), 4);
    EXPECT_EQ(transition::slotOf("angular"), 16);
    EXPECT_EQ(transition::slotOf("Fade"), -1);
    EXPECT_EQ(transition::slotOf("cut"), -1);
    EXPECT_EQ(transition::slotOf("random"), -1);
    EXPECT_EQ(transition::slotOf(""), -1);

    EXPECT_TRUE(transition::isSafe("random"));
    EXPECT_TRUE(transition::isSafe("fade"));
    EXPECT_TRUE(transition::isSafe("angular"));
    EXPECT_FALSE(transition::isSafe("cut"));
    EXPECT_FALSE(transition::isSafe("DreamyZoom"));

    EXPECT_EQ(transition::safeLook("Swirl"), "Swirl");
    EXPECT_EQ(transition::safeLook("random"), "random");
    EXPECT_EQ(transition::safeLook("cut"), "fade");
    EXPECT_EQ(transition::safeLook("TVStatic"), "fade");
}

TEST(StyleTransition, AChosenLookIsAlwaysThatLook) {
    for (uint32_t index = 0; index < 5; ++index) {
        EXPECT_EQ(transition::lookSlot("melt", 7, index, 1), 1);
        EXPECT_EQ(transition::lookSlot("angular", 7, index, -1), 16);
        EXPECT_EQ(transition::lookSlot("cut", 7, index, 3), transition::kFadeSlot);
        EXPECT_EQ(transition::lookSlot("DreamyZoom", 7, index, 3), transition::kFadeSlot);
    }
}

TEST(StyleTransition, RandomIsSeededAndNeverTheSameLookTwiceInARow) {
    std::array<int, transition::kLookCount> counts{};
    int previous = -1;
    constexpr int kDraws = transition::kLookCount * 1000;
    for (int i = 0; i < kDraws; ++i) {
        const int slot = transition::lookSlot("random", 42, static_cast<uint32_t>(i), previous);
        ASSERT_GE(slot, 0);
        ASSERT_LT(slot, transition::kLookCount);
        ASSERT_NE(slot, previous) << "draw " << i;
        EXPECT_EQ(slot, transition::lookSlot("random", 42, static_cast<uint32_t>(i), previous)) << "the same draw again";
        counts[static_cast<size_t>(slot)]++;
        previous = slot;
    }
    for (int slot = 0; slot < transition::kLookCount; ++slot) {
        EXPECT_GT(counts[static_cast<size_t>(slot)], 700) << transition::kSafeLooks[static_cast<size_t>(slot)];
        EXPECT_LT(counts[static_cast<size_t>(slot)], 1300) << transition::kSafeLooks[static_cast<size_t>(slot)];
    }
}

TEST(StyleTransition, RandomFollowsTheSeedAndTheIndex) {
    int seedDiffers = 0;
    std::set<int> firstDraws;
    for (uint32_t index = 0; index < 20; ++index) {
        if (transition::lookSlot("random", 1, index, -1) != transition::lookSlot("random", 2, index, -1)) seedDiffers++;
        firstDraws.insert(transition::lookSlot("random", 1, index, -1));
    }
    EXPECT_GT(seedDiffers, 10);
    EXPECT_GT(firstDraws.size(), 5U);
    // A previous slot that names no look is no previous look.
    for (uint32_t index = 0; index < 20; ++index) {
        EXPECT_EQ(transition::lookSlot("random", 9, index, transition::kLookCount), transition::lookSlot("random", 9, index, -1));
    }
}

TEST(StyleTransition, WithNoTempoAChangeStartsAtOnce) {
    DownbeatGate gate;
    EXPECT_FALSE(gate.step(heard(), kStep)) << "nothing was asked for";
    gate.arm();
    EXPECT_TRUE(gate.waiting());
    EXPECT_TRUE(gate.step(heard(0.0f), kStep));
    EXPECT_FALSE(gate.waiting());
    EXPECT_FALSE(gate.step(heard(0.0f), kStep)) << "it started once";

    gate.arm();
    EXPECT_TRUE(gate.step(heard(120.0f, 0.39f), kStep)) << "a tempo the analysis is unsure of is none";
    gate.arm();
    EXPECT_TRUE(gate.step(heard(std::numeric_limits<float>::quiet_NaN()), kStep));
    gate.arm();
    EXPECT_TRUE(gate.step(heard(-120.0f), kStep));
}

TEST(StyleTransition, WithATempoAChangeWaitsForTheDownbeat) {
    DownbeatGate gate;
    gate.arm();
    EXPECT_FALSE(gate.step(heard(120.0f, 0.4f), kStep));
    for (int i = 0; i < 10; ++i) EXPECT_FALSE(gate.step(heard(), kStep));
    EXPECT_TRUE(gate.waiting());
    EXPECT_TRUE(gate.step(downbeat(), kStep));
    EXPECT_FALSE(gate.waiting());
}

TEST(StyleTransition, AMissedDownbeatIsWaitedForABarAndABeatAtMost) {
    EXPECT_FLOAT_EQ(DownbeatGate::maxWait(120.0f), 2.5f);
    EXPECT_FLOAT_EQ(DownbeatGate::maxWait(240.0f), 1.25f);
    EXPECT_FLOAT_EQ(DownbeatGate::maxWait(60.0f), DownbeatGate::kMaxWaitSeconds);
    EXPECT_FLOAT_EQ(DownbeatGate::maxWait(0.0f), 0.0f);
    EXPECT_FLOAT_EQ(DownbeatGate::maxWait(-90.0f), 0.0f);
    EXPECT_FLOAT_EQ(DownbeatGate::maxWait(std::numeric_limits<float>::infinity()), 0.0f);

    DownbeatGate gate;
    gate.arm();
    for (int i = 0; i < 4; ++i) EXPECT_FALSE(gate.step(heard(), 0.5f)) << i;
    EXPECT_TRUE(gate.step(heard(), 0.5f)) << "2.5 s at 120 BPM";
}

TEST(StyleTransition, AskingAgainKeepsTheWaitAndCancellingEndsIt) {
    DownbeatGate gate;
    gate.arm();
    EXPECT_FALSE(gate.step(heard(), 2.0f));
    gate.arm();
    EXPECT_TRUE(gate.step(heard(), 0.5f)) << "the wait counts from the first ask";

    gate.arm();
    gate.cancel();
    EXPECT_FALSE(gate.waiting());
    EXPECT_FALSE(gate.step(downbeat(), kStep));

    gate.arm();
    EXPECT_FALSE(gate.step(heard(), -10.0f)) << "time never runs backwards";
    EXPECT_TRUE(gate.step(heard(), 2.5f));
}

TEST(StyleTransition, WithoutKicksAChangeIsASteadyCrossfadeThatEndsOnTime) {
    Progress progress;
    EXPECT_TRUE(progress.done()) << "nothing is running";
    EXPECT_FLOAT_EQ(progress.progress(), 1.0f);
    progress.start(2.0f);
    EXPECT_FALSE(progress.done());
    EXPECT_FLOAT_EQ(progress.progress(), 0.0f);
    for (int i = 1; i < 32; ++i) {
        EXPECT_FLOAT_EQ(progress.step(kStep, 0.0f), static_cast<float>(i) / 32.0f) << i;
        EXPECT_FALSE(progress.done()) << i;
    }
    EXPECT_FLOAT_EQ(progress.step(kStep, 0.0f), 1.0f);
    EXPECT_TRUE(progress.done());
    EXPECT_FLOAT_EQ(progress.step(kStep, 1.0f), 1.0f) << "never past the end";
}

TEST(StyleTransition, AKickMovesTheChangeOnAStepThatGlides) {
    Progress progress;
    progress.start(2.0f);
    EXPECT_FLOAT_EQ(progress.step(kStep, 0.0f), 0.03125f);
    // The kick asks for the clock (0.0625) plus a step; it glides there at 1 per second.
    EXPECT_NEAR(progress.step(kStep, 0.8f), 0.09375f, kTol);
    EXPECT_NEAR(progress.step(kStep, 0.0f), 0.15625f, kTol);
    EXPECT_NEAR(progress.step(kStep, 0.0f), 0.1625f, kTol) << "there: the clock plus one step";
    EXPECT_NEAR(progress.step(kStep, 0.0f), 0.1625f, kTol) << "it waits for the clock";
    EXPECT_NEAR(progress.step(kStep, 0.0f), 0.1875f, kTol) << "the clock again";
}

TEST(StyleTransition, AStepGlidesNoFasterThanItsGlide) {
    Progress progress;
    progress.start(10.0f);
    const float glide = Progress::kKickStep / Progress::kGlideSeconds;
    EXPECT_NEAR(progress.step(kStep, 1.0f), glide * kStep, kTol);
    EXPECT_NEAR(progress.step(kStep, 0.0f), 2.0f * glide * kStep, kTol);
    EXPECT_NEAR(progress.step(kStep, 0.0f), Progress::kKickStep + kStep / 10.0f, kTol) << "the clock at the kick plus a step";
}

TEST(StyleTransition, KicksRunAheadOfTheClockByAtMostTheLead) {
    Progress progress;
    progress.start(10.0f);
    for (int i = 0; i < 40; ++i) progress.step(kStep, 1.0f);
    EXPECT_NEAR(progress.progress(), 40.0f * kStep / 10.0f + Progress::kMaxLead, kTol);
    // Kicking on, it lands early, and exactly on 1.
    int frames = 40;
    while (!progress.done() && frames < 1000) {
        EXPECT_LE(progress.step(kStep, 1.0f), 1.0f);
        frames++;
    }
    EXPECT_TRUE(progress.done());
    EXPECT_FLOAT_EQ(progress.progress(), 1.0f);
    EXPECT_LT(static_cast<float>(frames) * kStep, 10.0f) << "the kicks brought it in early";
    EXPECT_GE(static_cast<float>(frames) * kStep, 10.0f * (1.0f - Progress::kMaxLead) - kStep);
}

TEST(StyleTransition, ProgressNeverGoesBackOrJumps) {
    Progress progress;
    progress.start(1.0f);
    float last = 0.0f;
    uint32_t noise = 12345U;
    const float rate = std::max(Progress::kKickStep / Progress::kGlideSeconds, 2.0f);
    for (int i = 0; i < 200 && !progress.done(); ++i) {
        noise = noise * 1664525U + 1013904223U;
        const float dt = 0.008f + static_cast<float>(noise >> 24U) / 255.0f * 0.03f;
        const float kick = (noise & 3U) == 0U ? 1.0f : 0.0f;
        const float shown = progress.step(dt, kick);
        EXPECT_GE(shown, last) << i;
        EXPECT_LE(shown - last, rate * dt + kTol) << i;
        last = shown;
    }
    EXPECT_TRUE(progress.done());
}

TEST(StyleTransition, AChangeLastsHalfASecondAtLeast) {
    Progress progress;
    progress.start(3.0f);
    EXPECT_FLOAT_EQ(progress.seconds(), 3.0f);
    progress.start(0.0f);
    EXPECT_FLOAT_EQ(progress.seconds(), Progress::kMinSeconds);
    progress.start(-1.0f);
    EXPECT_FLOAT_EQ(progress.seconds(), Progress::kMinSeconds);
    progress.start(std::numeric_limits<float>::quiet_NaN());
    EXPECT_FLOAT_EQ(progress.seconds(), Progress::kMinSeconds);
    progress.start(std::numeric_limits<float>::infinity());
    EXPECT_FLOAT_EQ(progress.seconds(), Progress::kMinSeconds);
    EXPECT_FLOAT_EQ(progress.step(-1.0f, 0.0f), 0.0f) << "time never runs backwards";
    EXPECT_FLOAT_EQ(progress.step(0.25f, 0.0f), 0.5f);
}

TEST(StyleTransition, TheEaseStartsAndLandsGently) {
    EXPECT_FLOAT_EQ(transition::ease(0.0f), 0.0f);
    EXPECT_FLOAT_EQ(transition::ease(1.0f), 1.0f);
    EXPECT_FLOAT_EQ(transition::ease(0.5f), 0.5f);
    EXPECT_FLOAT_EQ(transition::ease(0.25f), 0.103515625f);
    EXPECT_FLOAT_EQ(transition::ease(0.75f), 1.0f - 0.103515625f);
    EXPECT_FLOAT_EQ(transition::ease(-1.0f), 0.0f);
    EXPECT_FLOAT_EQ(transition::ease(2.0f), 1.0f);
    float last = 0.0f;
    for (int i = 1; i <= 100; ++i) {
        const float eased = transition::ease(static_cast<float>(i) / 100.0f);
        EXPECT_GE(eased, last);
        last = eased;
    }
}
