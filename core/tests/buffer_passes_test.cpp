#include "geode/viz/BufferPasses.hpp"

#include <gtest/gtest.h>

#include <cmath>
#include <limits>
#include <string>

namespace bp = geode::viz::bufferpass;

TEST(BufferPassSpec, SanitizedHoldsTheCountAndEveryScaleToTheirRanges) {
    bp::Spec spec;
    spec.count = 7;
    spec.scale = {0.1f, 2.0f, 0.5f, std::numeric_limits<float>::quiet_NaN()};
    const bp::Spec out = bp::sanitized(spec);
    EXPECT_EQ(out.count, bp::kMaxBuffers);
    EXPECT_FLOAT_EQ(out.scale[0], bp::kMinScale);
    EXPECT_FLOAT_EQ(out.scale[1], bp::kMaxScale);
    EXPECT_FLOAT_EQ(out.scale[2], 0.5f);
    EXPECT_FLOAT_EQ(out.scale[3], bp::kDefaultScale);
    spec.count = -2;
    spec.scale = {std::numeric_limits<float>::infinity(), 0.25f, 1.0f, 0.75f};
    const bp::Spec low = bp::sanitized(spec);
    EXPECT_EQ(low.count, 0);
    EXPECT_FLOAT_EQ(low.scale[0], bp::kDefaultScale);
    EXPECT_FLOAT_EQ(low.scale[1], 0.25f);
    EXPECT_FLOAT_EQ(low.scale[2], 1.0f);
    EXPECT_FLOAT_EQ(low.scale[3], 0.75f);
    spec.count = 2;
    EXPECT_EQ(bp::sanitized(spec).count, 2);
}

TEST(BufferPassSpec, ADefaultSpecHasNoBuffersAtFullSize) {
    const bp::Spec spec;
    EXPECT_EQ(spec.count, 0);
    for (const float scale : spec.scale) EXPECT_FLOAT_EQ(scale, 1.0f);
    EXPECT_EQ(bp::sanitized(spec), spec);
    bp::Spec other;
    other.scale[2] = 0.5f;
    EXPECT_FALSE(other == spec);
}

TEST(BufferPassSize, IsTheScaledTargetRoundedUp) {
    EXPECT_EQ(bp::bufferSize(1080, 1920, 0.5f), (bp::Size{540, 960}));
    EXPECT_EQ(bp::bufferSize(101, 33, 0.5f), (bp::Size{51, 17}));
    EXPECT_EQ(bp::bufferSize(640, 480, 1.0f), (bp::Size{640, 480}));
    EXPECT_EQ(bp::bufferSize(10, 9, 0.25f), (bp::Size{3, 3}));
}

TEST(BufferPassSize, HoldsTheScaleToItsRangeAndNeverGoesBelowOnePixel) {
    EXPECT_EQ(bp::bufferSize(400, 200, 0.05f), (bp::Size{100, 50}));
    EXPECT_EQ(bp::bufferSize(400, 200, 3.0f), (bp::Size{400, 200}));
    EXPECT_EQ(bp::bufferSize(400, 200, std::numeric_limits<float>::quiet_NaN()), (bp::Size{400, 200}));
    EXPECT_EQ(bp::bufferSize(0, -5, 0.5f), (bp::Size{1, 1}));
    EXPECT_EQ(bp::bufferSize(1, 1, 0.25f), (bp::Size{1, 1}));
    EXPECT_EQ(bp::bufferSize(2, 3, 0.25f), (bp::Size{1, 1}));
}

TEST(BufferPassUnits, TakeConsecutiveUnitsAndFitBelowTheLimit) {
    EXPECT_EQ(bp::unitOf(0, 12), 12);
    EXPECT_EQ(bp::unitOf(3, 12), 15);
    EXPECT_EQ(bp::unitOf(1, 14), 15);
    // Four buffers from unit 12 fill 12..15, the last of the 16 every GLES 3 device has.
    EXPECT_TRUE(bp::fits(4, 12, 16));
    EXPECT_FALSE(bp::fits(4, 13, 16));
    EXPECT_TRUE(bp::fits(2, 14, 16));
    EXPECT_FALSE(bp::fits(3, 14, 16));
    EXPECT_TRUE(bp::fits(0, 16, 16));
    EXPECT_FALSE(bp::fits(1, 16, 16));
}

TEST(BufferPassOrder, APassSeesTheBuffersDrawnBeforeItAsThisFrame) {
    // Buffer B (1) reads A as this frame drew it, and itself, C and D as last frame left them.
    EXPECT_TRUE(bp::readsThisFrame(1, 0));
    EXPECT_FALSE(bp::readsThisFrame(1, 1));
    EXPECT_FALSE(bp::readsThisFrame(1, 2));
    EXPECT_FALSE(bp::readsThisFrame(0, 0));
    // The image pass (after every buffer) sees them all as this frame left them.
    for (int buffer = 0; buffer < bp::kMaxBuffers; ++buffer) EXPECT_TRUE(bp::readsThisFrame(bp::kMaxBuffers, buffer));
}

TEST(BufferPassChain, PingPongsEachBufferBetweenItsTwoTargets) {
    bp::Chain chain(2);
    EXPECT_EQ(chain.count(), 2);
    EXPECT_EQ(chain.latest(0), 0);
    EXPECT_EQ(chain.target(0), 1);
    EXPECT_EQ(chain.latest(1), 0);
    EXPECT_EQ(chain.target(1), 1);
    chain.drawn(0);
    // A's newest picture is the one it just drew; B, not drawn yet, still reads last frame's.
    EXPECT_EQ(chain.latest(0), 1);
    EXPECT_EQ(chain.target(0), 0);
    EXPECT_EQ(chain.latest(1), 0);
    chain.drawn(1);
    EXPECT_EQ(chain.latest(1), 1);
    chain.endFrame();
    chain.drawn(0);
    chain.drawn(1);
    EXPECT_EQ(chain.latest(0), 0);
    EXPECT_EQ(chain.latest(1), 0);
    EXPECT_EQ(chain.target(1), 1);
}

TEST(BufferPassChain, IgnoresABufferItDoesNotHave) {
    bp::Chain chain(1);
    chain.drawn(1);
    chain.drawn(-1);
    EXPECT_EQ(chain.latest(1), 0);
    EXPECT_EQ(chain.latest(-1), 0);
    EXPECT_EQ(chain.target(1), 1);
    EXPECT_EQ(chain.latest(0), 0);
    chain.drawn(0);
    EXPECT_EQ(chain.latest(0), 1);
    const bp::Chain none;
    EXPECT_EQ(none.count(), 0);
    EXPECT_EQ(none.latest(0), 0);
}

TEST(BufferPassChain, HoldsItsCountToTheBuffersThereAre) {
    EXPECT_EQ(bp::Chain(9).count(), bp::kMaxBuffers);
    EXPECT_EQ(bp::Chain(-3).count(), 0);
    bp::Chain full(bp::kMaxBuffers);
    full.drawn(bp::kMaxBuffers - 1);
    EXPECT_EQ(full.latest(bp::kMaxBuffers - 1), 1);
    full.drawn(bp::kMaxBuffers);
    EXPECT_EQ(full.latest(bp::kMaxBuffers), 0);
}

TEST(BufferPassChain, CountsFramesAndStartsAgainEachVisit) {
    bp::Chain chain(2);
    EXPECT_EQ(chain.frame(), 0u);
    chain.endFrame();
    chain.endFrame();
    EXPECT_EQ(chain.frame(), 2u);
    chain.drawn(1);
    chain.reset();
    EXPECT_EQ(chain.frame(), 0u);
    EXPECT_EQ(chain.latest(1), 0);
    EXPECT_EQ(chain.target(1), 1);
    EXPECT_EQ(chain.count(), 2);
}

TEST(BufferPassChain, TheFrameCounterWrapsInsideAFloat) {
    bp::Chain chain(1);
    for (std::uint32_t i = 0; i + 1 < bp::kFrameWrap; ++i) chain.endFrame();
    EXPECT_EQ(chain.frame(), bp::kFrameWrap - 1);
    chain.endFrame();
    EXPECT_EQ(chain.frame(), 0u);
    EXPECT_EQ(bp::kFrameWrap, 1u << 24);
}

TEST(BufferPassConstants, MatchTheShaderLibrary) {
    // lib_buffers.glsl declares these samplers and reads uBufPass 4 as the image pass.
    EXPECT_EQ(bp::kMaxBuffers, 4);
    EXPECT_EQ(std::string(bp::kSamplers[0]), "uBufA");
    EXPECT_EQ(std::string(bp::kSamplers[1]), "uBufB");
    EXPECT_EQ(std::string(bp::kSamplers[2]), "uBufC");
    EXPECT_EQ(std::string(bp::kSamplers[3]), "uBufD");
    EXPECT_FLOAT_EQ(bp::kImagePass, 4.0f);
    EXPECT_FLOAT_EQ(bp::kMinScale, 0.25f);
    EXPECT_FLOAT_EQ(bp::kMaxScale, 1.0f);
    EXPECT_FLOAT_EQ(bp::kDefaultScale, 1.0f);
}
