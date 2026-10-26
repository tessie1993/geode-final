#include "geode/viz/StyleInputs.hpp"

#include <gtest/gtest.h>

#include <algorithm>
#include <cmath>
#include <set>
#include <string>

namespace inputs = geode::viz::inputs;
using inputs::Input;

namespace {

constexpr float kDt = 1.0f / 60.0f;

// The value at (x, y) of channel c of a size-wide RGBA plane.
int texel(const std::vector<std::uint8_t>& plane, int size, int x, int y, int c) {
    return plane[static_cast<size_t>((y * size + x) * 4 + c)];
}

int latticeByte(std::uint32_t seed, int c, int x, int y) { return static_cast<int>(inputs::hashCell(seed, c, x, y, 0) >> 24); }

// Steps `clock` for `seconds` at 60 fps with a fixed analysis reading.
void run(inputs::BarClock& clock, float seconds, float barPhase, float confidence, float bpm) {
    for (int i = 0; i < static_cast<int>(std::lround(seconds / kDt)); ++i) clock.step(barPhase, confidence, bpm, kDt);
}

// Shortest distance between two phases on the unit circle.
float circleDistance(float a, float b) {
    const float d = std::fabs(a - b);
    return std::min(d, 1.0f - d);
}

}  // namespace

// ---- names ---------------------------------------------------------------------------------------

TEST(StyleInputNames, EveryNameReadsBackAsItsInput) {
    for (int i = 0; i < inputs::kInputCount; ++i) {
        const auto input = inputs::named(inputs::kInputNames[static_cast<size_t>(i)]);
        ASSERT_TRUE(input.has_value()) << inputs::kInputNames[static_cast<size_t>(i)];
        EXPECT_EQ(static_cast<int>(*input), i);
    }
    EXPECT_EQ(inputs::named("noise_lq").value_or(Input::Bar), Input::NoiseLq);
    EXPECT_EQ(inputs::named("noise_vol").value_or(Input::Bar), Input::NoiseVolume);
    EXPECT_EQ(inputs::named("previous_frame").value_or(Input::Bar), Input::PreviousFrame);
    EXPECT_EQ(inputs::named("bar").value_or(Input::Clocks), Input::Bar);
}

TEST(StyleInputNames, AnythingElseIsNoInput) {
    for (const char* name : {"", "noise", "NOISE_LQ", "blur4", "blur", "prev_frame", "clock", "bars"}) {
        EXPECT_FALSE(inputs::named(name).has_value()) << name;
    }
}

TEST(StyleInputSet, EachInputIsItsOwnBit) {
    inputs::Set all = 0;
    for (int i = 0; i < inputs::kInputCount; ++i) {
        const inputs::Set b = inputs::bit(static_cast<Input>(i));
        EXPECT_EQ(b, 1u << i);
        EXPECT_EQ(all & b, 0u);
        all |= b;
    }
    const inputs::Set set = inputs::bit(Input::Blur2) | inputs::bit(Input::Clocks);
    EXPECT_TRUE(inputs::has(set, Input::Blur2));
    EXPECT_TRUE(inputs::has(set, Input::Clocks));
    EXPECT_FALSE(inputs::has(set, Input::Blur1));
    EXPECT_FALSE(inputs::has(0, Input::NoiseLq));
}

// ---- noise ---------------------------------------------------------------------------------------

TEST(StyleInputNoise, HashCellIsPinned) {
    // Pinned values: the noise a Render draws must never change under it.
    EXPECT_EQ(inputs::hashCell(0, 0, 0, 0, 0), 0x0eaa7511u);
    EXPECT_EQ(inputs::hashCell(inputs::kNoiseSeed, 1, 2, 3, 4), 0x2a55fbfbu);
    EXPECT_EQ(inputs::hashCell(inputs::kNoiseSeed, 3, -1, 0, 0), 0x696423c2u);
}

TEST(StyleInputNoise, EveryArgumentChangesTheHash) {
    const std::uint32_t base = inputs::hashCell(7, 1, 2, 3, 4);
    EXPECT_NE(inputs::hashCell(8, 1, 2, 3, 4), base);
    EXPECT_NE(inputs::hashCell(7, 2, 2, 3, 4), base);
    EXPECT_NE(inputs::hashCell(7, 1, 3, 3, 4), base);
    EXPECT_NE(inputs::hashCell(7, 1, 2, 4, 4), base);
    EXPECT_NE(inputs::hashCell(7, 1, 2, 3, 5), base);
    // The axes are not interchangeable.
    EXPECT_NE(inputs::hashCell(7, 1, 3, 2, 4), base);
    EXPECT_EQ(inputs::hashCell(7, 1, 2, 3, 4), base);
}

TEST(StyleInputNoise, LatticeFollowsTheSmoothness) {
    EXPECT_EQ(inputs::lattice(Input::NoiseLq), 1);
    EXPECT_EQ(inputs::lattice(Input::NoiseMq), inputs::kMqLattice);
    EXPECT_EQ(inputs::lattice(Input::NoiseHq), inputs::kHqLattice);
    EXPECT_EQ(inputs::lattice(Input::NoiseVolume), 1);
    EXPECT_EQ(inputs::lattice(Input::BlueNoise), 0);
    EXPECT_EQ(inputs::lattice(Input::Blur1), 0);
    EXPECT_EQ(inputs::lattice(Input::Clocks), 0);
}

TEST(StyleInputNoise, WhiteNoiseIsTheLatticeItself) {
    const std::uint32_t seed = inputs::kNoiseSeed;
    const auto plane = inputs::noisePlane(seed, 16, 1);
    ASSERT_EQ(plane.size(), 16u * 16u * 4u);
    EXPECT_EQ(texel(plane, 16, 0, 0, 0), 89);
    EXPECT_EQ(texel(plane, 16, 0, 0, 1), 176);
    EXPECT_EQ(texel(plane, 16, 0, 0, 2), 67);
    EXPECT_EQ(texel(plane, 16, 0, 0, 3), 115);
    EXPECT_EQ(texel(plane, 16, 5, 7, 0), 131);
    EXPECT_EQ(texel(plane, 16, 5, 7, 3), 130);
    for (int y = 0; y < 16; y += 3) {
        for (int x = 0; x < 16; x += 5) {
            for (int c = 0; c < 4; ++c) EXPECT_EQ(texel(plane, 16, x, y, c), latticeByte(seed, c, x, y));
        }
    }
}

TEST(StyleInputNoise, SmoothNoiseInterpolatesBetweenLatticePoints) {
    const std::uint32_t seed = 42;
    const int size = 16;
    const auto plane = inputs::noisePlane(seed, size, 4);
    ASSERT_EQ(plane.size(), static_cast<size_t>(size * size * 4));
    // On a lattice point: the lattice value.
    EXPECT_EQ(texel(plane, size, 8, 4, 2), latticeByte(seed, 2, 2, 1));
    EXPECT_EQ(texel(plane, size, 0, 0, 0), latticeByte(seed, 0, 0, 0));
    // Half way along x: smoothstep(0.5) = 0.5, the mean of the two neighbours.
    for (int c = 0; c < 4; ++c) {
        const float mean = 0.5f * static_cast<float>(latticeByte(seed, c, 1, 2) + latticeByte(seed, c, 2, 2));
        EXPECT_NEAR(texel(plane, size, 6, 8, c), mean, 0.51f) << c;
    }
    // A quarter of the way the smoothstep weight is 0.15625, not 0.25.
    const float a = static_cast<float>(latticeByte(seed, 0, 1, 0));
    const float b = static_cast<float>(latticeByte(seed, 0, 1, 1));
    EXPECT_NEAR(texel(plane, size, 4, 1, 0), a + (b - a) * 0.15625f, 0.51f);
}

TEST(StyleInputNoise, SmoothNoiseWrapsSoTheTextureTiles) {
    const std::uint32_t seed = 9;
    const int size = 8;
    const auto plane = inputs::noisePlane(seed, size, 4);
    // Texel 6 is half way from lattice point 1 to lattice point 0 again (two cells across).
    for (int c = 0; c < 4; ++c) {
        const float mean = 0.5f * static_cast<float>(latticeByte(seed, c, 1, 0) + latticeByte(seed, c, 0, 0));
        EXPECT_NEAR(texel(plane, size, 6, 0, c), mean, 0.51f);
        const float down = 0.5f * static_cast<float>(latticeByte(seed, c, 0, 1) + latticeByte(seed, c, 0, 0));
        EXPECT_NEAR(texel(plane, size, 0, 6, c), down, 0.51f);
    }
}

TEST(StyleInputNoise, ChannelsAndSeedsDiffer) {
    const auto a = inputs::noisePlane(1, 32, 1);
    const auto b = inputs::noisePlane(2, 32, 1);
    EXPECT_NE(a, b);
    EXPECT_EQ(a, inputs::noisePlane(1, 32, 1));
    int sameChannels = 0;
    for (int i = 0; i < 32 * 32; ++i) sameChannels += a[static_cast<size_t>(i * 4)] == a[static_cast<size_t>(i * 4 + 1)] ? 1 : 0;
    EXPECT_LT(sameChannels, 32);
}

TEST(StyleInputNoise, NoiseCoversTheByteRange) {
    const auto plane = inputs::noisePlane(inputs::kNoiseSeed, inputs::kNoiseSize, 1);
    ASSERT_EQ(plane.size(), static_cast<size_t>(inputs::kNoiseSize * inputs::kNoiseSize * 4));
    double sum = 0.0;
    int low = 0;
    int high = 0;
    for (const std::uint8_t v : plane) {
        sum += v;
        low += v < 16 ? 1 : 0;
        high += v > 239 ? 1 : 0;
    }
    EXPECT_NEAR(sum / static_cast<double>(plane.size()), 127.5, 2.0);
    EXPECT_GT(low, 0);
    EXPECT_GT(high, 0);
}

TEST(StyleInputNoise, RejectsSizesThatCannotTile) {
    EXPECT_TRUE(inputs::noisePlane(1, 0, 1).empty());
    EXPECT_TRUE(inputs::noisePlane(1, -4, 1).empty());
    EXPECT_TRUE(inputs::noisePlane(1, 16, 0).empty());
    EXPECT_TRUE(inputs::noisePlane(1, 10, 4).empty());
    EXPECT_EQ(inputs::noisePlane(1, 1, 1).size(), 4u);
    EXPECT_TRUE(inputs::noiseVolume(1, 0).empty());
    EXPECT_TRUE(inputs::noiseVolume(1, -1).empty());
}

TEST(StyleInputNoise, VolumeIsWhiteNoiseXFastest) {
    const std::uint32_t seed = inputs::kNoiseSeed;
    const int size = 4;
    const auto volume = inputs::noiseVolume(seed, size);
    ASSERT_EQ(volume.size(), static_cast<size_t>(size * size * size * 4));
    const auto at = [&](int x, int y, int z, int c) { return volume[static_cast<size_t>(((z * size + y) * size + x) * 4 + c)]; };
    EXPECT_EQ(at(1, 2, 3, 0), 191);
    EXPECT_EQ(at(1, 2, 3, 1), 67);
    EXPECT_EQ(at(1, 2, 3, 2), 145);
    EXPECT_EQ(at(1, 2, 3, 3), 153);
    for (int z = 0; z < size; ++z) {
        for (int y = 0; y < size; ++y) {
            for (int x = 0; x < size; ++x) {
                EXPECT_EQ(at(x, y, z, 2), static_cast<int>(inputs::hashCell(seed, 2, x, y, z) >> 24));
            }
        }
    }
    EXPECT_EQ(inputs::noiseVolume(seed, inputs::kNoiseVolumeSize).size(), 32u * 32u * 32u * 4u);
}

// ---- history -------------------------------------------------------------------------------------

TEST(StyleInputHistory, OnlyThePreviousFrameOrABlurKeepsHistory) {
    EXPECT_FALSE(inputs::needsHistory(0));
    EXPECT_FALSE(inputs::needsHistory(inputs::bit(Input::NoiseHq) | inputs::bit(Input::Clocks) | inputs::bit(Input::Bar)));
    EXPECT_TRUE(inputs::needsHistory(inputs::bit(Input::PreviousFrame)));
    EXPECT_TRUE(inputs::needsHistory(inputs::bit(Input::Blur1)));
    EXPECT_TRUE(inputs::needsHistory(inputs::bit(Input::Blur2)));
    EXPECT_TRUE(inputs::needsHistory(inputs::bit(Input::Blur3)));
}

TEST(StyleInputHistory, TheChainReachesTheDeepestBlurDeclared) {
    EXPECT_EQ(inputs::blurChainLength(0), 0);
    EXPECT_EQ(inputs::blurChainLength(inputs::bit(Input::PreviousFrame)), 0);
    EXPECT_EQ(inputs::blurChainLength(inputs::bit(Input::Blur1)), 2);
    EXPECT_EQ(inputs::blurChainLength(inputs::bit(Input::Blur2)), 3);
    EXPECT_EQ(inputs::blurChainLength(inputs::bit(Input::Blur3)), 4);
    EXPECT_EQ(inputs::blurChainLength(inputs::bit(Input::Blur1) | inputs::bit(Input::Blur3)), 4);
    EXPECT_EQ(inputs::blurChainLength(inputs::bit(Input::Blur1) | inputs::bit(Input::Blur2)), 3);
}

TEST(StyleInputHistory, ABlurReadsItsLevelOrTheDeepestBuilt) {
    EXPECT_EQ(inputs::blurSource(1, 4), 1);
    EXPECT_EQ(inputs::blurSource(2, 4), 2);
    EXPECT_EQ(inputs::blurSource(3, 4), 3);
    EXPECT_EQ(inputs::blurSource(3, 3), 2);
    EXPECT_EQ(inputs::blurSource(2, 2), 1);
    EXPECT_EQ(inputs::blurSource(1, 1), 0);
    EXPECT_EQ(inputs::blurSource(1, 0), -1);
    EXPECT_EQ(inputs::blurSource(0, 4), -1);
    EXPECT_EQ(inputs::blurSource(4, 4), -1);
    EXPECT_EQ(inputs::blurSource(-1, 4), -1);
}

// ---- clocks --------------------------------------------------------------------------------------

TEST(StyleInputClocks, VisitSeedDependsOnTheBaseAndTheStyle) {
    EXPECT_EQ(inputs::visitSeed(inputs::kNoiseSeed, "nebula"), 0xf7781effu);
    EXPECT_NE(inputs::visitSeed(inputs::kNoiseSeed, "kifs"), inputs::visitSeed(inputs::kNoiseSeed, "nebula"));
    EXPECT_NE(inputs::visitSeed(1, "nebula"), inputs::visitSeed(2, "nebula"));
    EXPECT_NE(inputs::visitSeed(1, "ab"), inputs::visitSeed(1, "ba"));
    EXPECT_NE(inputs::visitSeed(1, ""), inputs::visitSeed(1, "a"));
}

TEST(StyleInputClocks, VisitRandomIsPinnedAndInTheUnitRange) {
    const auto r = inputs::visitRandom(0xf7781effu, 1);
    EXPECT_NEAR(r[0], 0.0658764f, 1e-6f);
    EXPECT_NEAR(r[1], 0.2415146f, 1e-6f);
    EXPECT_NEAR(r[2], 0.6138716f, 1e-6f);
    EXPECT_NEAR(r[3], 0.9410975f, 1e-6f);
    std::set<float> seen;
    for (std::uint32_t visit = 0; visit < 200; ++visit) {
        for (const float v : inputs::visitRandom(5, visit)) {
            EXPECT_GE(v, 0.0f);
            EXPECT_LT(v, 1.0f);
            seen.insert(v);
        }
    }
    EXPECT_GT(seen.size(), 790u);
    EXPECT_EQ(inputs::visitRandom(5, 3), inputs::visitRandom(5, 3));
}

TEST(StyleInputClocks, AVisitStartsFromZeroWithANewVector) {
    inputs::VisitClock clock;
    EXPECT_EQ(clock.visits(), 0u);
    clock.beginVisit(77);
    EXPECT_EQ(clock.visits(), 1u);
    EXPECT_EQ(clock.random(), inputs::visitRandom(77, 1));
    for (int i = 0; i < 30; ++i) clock.step(kDt);
    EXPECT_EQ(clock.frame(), 30u);
    EXPECT_NEAR(clock.age(), 0.5f, 1e-4f);
    EXPECT_FLOAT_EQ(clock.dt(), kDt);
    clock.beginVisit(77);
    EXPECT_EQ(clock.visits(), 2u);
    EXPECT_EQ(clock.frame(), 0u);
    EXPECT_FLOAT_EQ(clock.age(), 0.0f);
    EXPECT_FLOAT_EQ(clock.dt(), 0.0f);
    EXPECT_EQ(clock.random(), inputs::visitRandom(77, 2));
    EXPECT_NE(clock.random(), inputs::visitRandom(77, 1));
}

TEST(StyleInputClocks, ABrokenFrameCountsButDoesNotAge) {
    inputs::VisitClock clock;
    clock.beginVisit(1);
    clock.step(0.25f);
    clock.step(-1.0f);
    EXPECT_FLOAT_EQ(clock.dt(), 0.0f);
    clock.step(NAN);
    EXPECT_FLOAT_EQ(clock.dt(), 0.0f);
    clock.step(INFINITY);
    EXPECT_FLOAT_EQ(clock.dt(), 0.0f);
    EXPECT_EQ(clock.frame(), 4u);
    EXPECT_FLOAT_EQ(clock.age(), 0.25f);
}

TEST(StyleInputClocks, AgeStopsAtItsCeiling) {
    inputs::VisitClock clock;
    clock.beginVisit(1);
    clock.step(inputs::kStyleAgeMax - 1.0f);
    EXPECT_FLOAT_EQ(clock.age(), inputs::kStyleAgeMax - 1.0f);
    clock.step(5.0f);
    EXPECT_FLOAT_EQ(clock.age(), inputs::kStyleAgeMax);
    clock.step(5.0f);
    EXPECT_FLOAT_EQ(clock.age(), inputs::kStyleAgeMax);
}

TEST(StyleInputClocks, TheFrameCountWrapsWhereAFloatStopsCounting) {
    inputs::VisitClock clock;
    clock.beginVisit(1);
    for (std::uint32_t i = 0; i < inputs::kFrameWrap - 1u; ++i) clock.step(0.0f);
    EXPECT_EQ(clock.frame(), inputs::kFrameWrap - 1u);
    clock.step(0.0f);
    EXPECT_EQ(clock.frame(), 0u);
    clock.step(0.0f);
    EXPECT_EQ(clock.frame(), 1u);
}

// ---- the bar clock -------------------------------------------------------------------------------

TEST(StyleInputBar, FreeRunsAtTheDefaultTempoUntilOneIsHeard) {
    inputs::BarClock clock;
    EXPECT_FLOAT_EQ(clock.barsPerSecond(), inputs::BarClock::kDefaultBarsPerSecond);
    run(clock, 1.0f, 0.0f, 0.0f, 0.0f);
    EXPECT_LT(circleDistance(clock.phase(), 0.5f), 1e-3f);
    EXPECT_FLOAT_EQ(clock.confidence(), 0.0f);
}

TEST(StyleInputBar, RunsAtTheHeardTempo) {
    inputs::BarClock clock;
    // 90 bpm, four beats a bar: 0.375 bars a second.
    run(clock, 2.0f, 0.0f, 0.0f, 90.0f);
    EXPECT_FLOAT_EQ(clock.barsPerSecond(), 0.375f);
    EXPECT_NEAR(clock.phase(), 0.75f, 1e-3f);
    // A tempo outside 40..240 bpm is held to it.
    clock.step(0.0f, 0.0f, 600.0f, kDt);
    EXPECT_FLOAT_EQ(clock.barsPerSecond(), inputs::BarClock::kMaxBarsPerSecond);
    clock.step(0.0f, 0.0f, 10.0f, kDt);
    EXPECT_FLOAT_EQ(clock.barsPerSecond(), inputs::BarClock::kMinBarsPerSecond);
    // No tempo (0, negative, not a number) keeps the last one.
    clock.step(0.0f, 0.0f, -5.0f, kDt);
    clock.step(0.0f, 0.0f, NAN, kDt);
    EXPECT_FLOAT_EQ(clock.barsPerSecond(), inputs::BarClock::kMinBarsPerSecond);
}

TEST(StyleInputBar, LocksOntoTheHeardBarWhenSure) {
    inputs::BarClock clock;
    // The analysis hears the bar a third of the way ahead, steadily, at the same tempo.
    float heard = 0.33f;
    for (int i = 0; i < 600; ++i) {
        heard = std::fmod(heard + 0.5f * kDt, 1.0f);
        clock.step(heard, 0.9f, 120.0f, kDt);
    }
    EXPECT_LT(circleDistance(clock.phase(), heard), 0.01f);
    EXPECT_NEAR(clock.confidence(), 0.9f, 0.01f);
}

TEST(StyleInputBar, IgnoresTheBarBelowTheLockConfidence) {
    inputs::BarClock clock;
    run(clock, 2.0f, 0.4f, inputs::BarClock::kLockConfidence - 0.01f, 120.0f);
    EXPECT_LT(circleDistance(clock.phase(), 0.0f), 1e-3f);  // two whole bars: the clock's own count, not 0.4
    EXPECT_FLOAT_EQ(clock.confidence(), 0.0f);
    inputs::BarClock sure;
    run(sure, 2.0f, 0.4f, inputs::BarClock::kLockConfidence, 120.0f);
    EXPECT_GT(sure.confidence(), 0.1f);
    EXPECT_GT(circleDistance(sure.phase(), 0.0f), 0.05f);
}

TEST(StyleInputBar, NeverRunsBackwardsNorJumps) {
    inputs::BarClock clock;
    run(clock, 1.0f, 0.0f, 0.0f, 120.0f);
    // The tracker suddenly hears the bar just behind and then far ahead: the clock only changes pace.
    const float steady = 0.5f * kDt;
    for (const float heard : {0.45f, 0.95f, 0.2f}) {
        for (int i = 0; i < 120; ++i) {
            const float before = clock.phase();
            clock.step(heard, 1.0f, 120.0f, kDt);
            float advance = clock.phase() - before;
            if (advance < 0.0f) advance += 1.0f;
            EXPECT_GE(advance, steady * inputs::BarClock::kMinPace - 1e-6f);
            EXPECT_LE(advance, steady * inputs::BarClock::kMaxPace + 1e-6f);
        }
    }
}

TEST(StyleInputBar, ConfidenceFollowsSlowlyAndAStalledFrameChangesNothing) {
    inputs::BarClock clock;
    clock.step(0.5f, 1.0f, 120.0f, kDt);
    EXPECT_GT(clock.confidence(), 0.0f);
    EXPECT_LT(clock.confidence(), 0.05f);
    const float phase = clock.phase();
    const float confidence = clock.confidence();
    clock.step(0.9f, 1.0f, 200.0f, 0.0f);
    clock.step(0.9f, 1.0f, 200.0f, -1.0f);
    clock.step(0.9f, 1.0f, 200.0f, NAN);
    EXPECT_FLOAT_EQ(clock.phase(), phase);
    EXPECT_FLOAT_EQ(clock.confidence(), confidence);
    EXPECT_FLOAT_EQ(clock.barsPerSecond(), 0.5f);
    // A confidence above 1 counts as 1; a bar phase outside 0..1 is held to it.
    inputs::BarClock over;
    run(over, 10.0f, 1.7f, 3.0f, 120.0f);
    EXPECT_NEAR(over.confidence(), 1.0f, 1e-3f);
}

TEST(StyleInputBar, ResetStartsOver) {
    inputs::BarClock clock;
    run(clock, 1.3f, 0.2f, 1.0f, 150.0f);
    clock.reset();
    EXPECT_FLOAT_EQ(clock.phase(), 0.0f);
    EXPECT_FLOAT_EQ(clock.confidence(), 0.0f);
    EXPECT_FLOAT_EQ(clock.barsPerSecond(), inputs::BarClock::kDefaultBarsPerSecond);
}
