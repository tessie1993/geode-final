#include "geode/viz/TileFrame.hpp"

#include <gtest/gtest.h>

#include <functional>
#include <string>
#include <vector>

// The tile-friendly frame (#311): the size the last passes draw at, the outgoing Style's half-size
// target, and when the composite can be skipped because it would hand the picture on unchanged.

namespace geode::viz::tile {
namespace {

// ---- frameSize -----------------------------------------------------------------------------------

TEST(TileFrameSize, AReducedSceneIsCarriedToTheLastPassAtItsOwnSize) {
    EXPECT_EQ(frameSize({648, 1440}, {1080, 2400}), (Size{648, 1440}));
}

TEST(TileFrameSize, ASupersampledSceneIsDownsampledByTheCompositeToTheOutput) {
    EXPECT_EQ(frameSize({1512, 3360}, {1080, 2400}), (Size{1080, 2400}));
}

TEST(TileFrameSize, AnEqualSceneDrawsAtTheOutput) {
    EXPECT_EQ(frameSize({1080, 2400}, {1080, 2400}), (Size{1080, 2400}));
}

TEST(TileFrameSize, EachSideTakesTheSmallerOfTheTwo) {
    EXPECT_EQ(frameSize({2000, 100}, {1000, 200}), (Size{1000, 100}));
    EXPECT_EQ(frameSize({100, 2000}, {200, 1000}), (Size{100, 1000}));
}

TEST(TileFrameSize, NeverBelowOnePixel) {
    EXPECT_EQ(frameSize({0, -4}, {1080, 2400}), (Size{1, 1}));
    EXPECT_EQ(frameSize({1080, 2400}, {0, 0}), (Size{1, 1}));
}

// ---- outgoingSize --------------------------------------------------------------------------------

TEST(TileOutgoingSize, HalvesEachSideForAQuarterOfThePixels) {
    EXPECT_EQ(outgoingSize({1080, 2400}), (Size{540, 1200}));
}

TEST(TileOutgoingSize, RoundsAnOddSideUp) {
    EXPECT_EQ(outgoingSize({649, 1441}), (Size{325, 721}));
    EXPECT_EQ(outgoingSize({3, 2}), (Size{2, 1}));
}

TEST(TileOutgoingSize, NeverBelowOnePixel) {
    EXPECT_EQ(outgoingSize({1, 1}), (Size{1, 1}));
    EXPECT_EQ(outgoingSize({0, 0}), (Size{1, 1}));
    EXPECT_EQ(outgoingSize({-6, -9}), (Size{1, 1}));
}

// ---- compositeIsIdentity -------------------------------------------------------------------------

constexpr std::array<float, 4> kAllGates{1.0f, 1.0f, 1.0f, 1.0f};

CompositeLook gated() {
    CompositeLook look;
    look.gate = kAllGates;
    look.camMoves = 1.0f;
    look.camEndless = 1.0f;
    return look;
}

TEST(TileCompositeIdentity, TheDefaultLookIsAnIdentityWithEveryGateOpen) {
    EXPECT_TRUE(compositeIsIdentity(CompositeLook{}));
    EXPECT_TRUE(compositeIsIdentity(gated()));
}

TEST(TileCompositeIdentity, ALayerOrATransitionAlwaysNeedsTheComposite) {
    CompositeLook layer;
    layer.hasLayer = true;
    EXPECT_FALSE(compositeIsIdentity(layer));
    CompositeLook outgoing;
    outgoing.hasOutgoing = true;
    EXPECT_FALSE(compositeIsIdentity(outgoing));
}

// One composite input: how to set it, the largest value the shader still skips, the smallest it
// draws, and the gate (index into CompositeLook::gate, or -1 for none) that has to be open for it.
struct Case {
    std::string name;
    std::function<void(CompositeLook&, float)> set;
    float still;
    float moves;
    int gate;
};

std::vector<Case> cases() {
    return {
        {"flow", [](CompositeLook& l, float v) { l.flowStrength = v; }, 0.001f, 0.0011f, -1},
        {"ripple overlay", [](CompositeLook& l, float v) { l.rippleStrength = v; }, 0.001f, 0.0011f, -1},
        {"fisheye", [](CompositeLook& l, float v) { l.fisheye = v; }, 0.001f, 0.0011f, -1},
        {"fisheye inward", [](CompositeLook& l, float v) { l.fisheye = -v; }, 0.001f, 0.0011f, -1},
        {"glitch", [](CompositeLook& l, float v) { l.glitch = v; }, 0.001f, 0.0011f, -1},
        {"chroma", [](CompositeLook& l, float v) { l.chroma = v; }, 0.001f, 0.0011f, -1},
        {"scanlines", [](CompositeLook& l, float v) { l.scanline = v; }, 0.001f, 0.0011f, -1},
        {"grain", [](CompositeLook& l, float v) { l.grain = v; }, 0.001f, 0.0011f, -1},
        {"vignette", [](CompositeLook& l, float v) { l.vignette = v; }, 0.001f, 0.0011f, -1},
        {"anaglyph", [](CompositeLook& l, float v) { l.anaglyph = v; }, 0.001f, 0.0011f, -1},
        {"underlay", [](CompositeLook& l, float v) { l.underlayAmount = v; }, 0.0001f, 0.00011f, -1},
        {"shake x", [](CompositeLook& l, float v) { l.camShakeX = v; }, 0.0f, 0.000002f, -1},
        {"shake y", [](CompositeLook& l, float v) { l.camShakeY = -v; }, 0.0f, 0.000002f, -1},
        {"shake roll", [](CompositeLook& l, float v) { l.camShakeRoll = v; }, 0.0f, 0.000002f, -1},
        {"sway", [](CompositeLook& l, float v) { l.sway = -v; }, 0.001f, 0.0011f, 0},
        {"shake", [](CompositeLook& l, float v) { l.shake = v; }, 0.001f, 0.0011f, 0},
        {"twist", [](CompositeLook& l, float v) { l.twist = -v; }, 0.001f, 0.0011f, 0},
        {"warp", [](CompositeLook& l, float v) { l.warp = v; }, 0.001f, 0.0011f, 0},
        {"ripple", [](CompositeLook& l, float v) { l.ripple = v; }, 0.001f, 0.0011f, 0},
        {"drift x", [](CompositeLook& l, float v) { l.driftX = -v; }, 0.001f, 0.0011f, 0},
        {"drift y", [](CompositeLook& l, float v) { l.driftY = v; }, 0.001f, 0.0011f, 0},
        {"tile", [](CompositeLook& l, float v) { l.tile = v; }, 1.01f, 1.011f, 0},
        {"pixelate", [](CompositeLook& l, float v) { l.pixelate = v; }, 0.001f, 0.0011f, 0},
        {"bloom", [](CompositeLook& l, float v) { l.bloom = v; }, 0.001f, 0.0011f, 0},
        {"posterize", [](CompositeLook& l, float v) { l.posterize = v; }, 0.001f, 0.0011f, 0},
        {"temperature", [](CompositeLook& l, float v) { l.temperature = v; }, 0.0f, 0.00001f, 0},
        {"temperature cool", [](CompositeLook& l, float v) { l.temperature = -v; }, 0.0f, 0.00001f, 0},
        {"kaleidoscope", [](CompositeLook& l, float v) { l.kaleido = true; l.symmetry = v; }, 1.99f, 2.0f, 0},
        {"rotation", [](CompositeLook& l, float v) { l.rotation = -v; }, 0.0001f, 0.00011f, 2},
        {"zoom in", [](CompositeLook& l, float v) { l.zoom = 1.0f + v; }, 0.00009f, 0.00012f, 2},
        {"zoom out", [](CompositeLook& l, float v) { l.zoom = 1.0f - v; }, 0.00009f, 0.00012f, 2},
        {"hue", [](CompositeLook& l, float v) { l.hue = v; }, 0.0001f, 0.00011f, 2},
        {"saturation", [](CompositeLook& l, float v) { l.saturation = 1.0f - v; }, 0.00009f, 0.00012f, 2},
        {"contrast", [](CompositeLook& l, float v) { l.contrast = 1.0f + v; }, 0.00009f, 0.00012f, 2},
        {"gamma", [](CompositeLook& l, float v) { l.gamma = 1.0f - v; }, 0.00009f, 0.00012f, 2},
        {"brightness", [](CompositeLook& l, float v) { l.brightness = 1.0f + v; }, 0.0f, 0.0001f, 2},
        {"dimmer", [](CompositeLook& l, float v) { l.brightness = 1.0f - v; }, 0.0f, 0.0001f, 2},
        {"pulse", [](CompositeLook& l, float v) { l.pulse = v; }, 0.0001f, 0.00011f, 3},
    };
}

TEST(TileCompositeIdentity, EachInputIsSkippedUpToTheShadersOwnThresholdAndDrawnAboveIt) {
    for (const Case& c : cases()) {
        CompositeLook still = gated();
        c.set(still, c.still);
        EXPECT_TRUE(compositeIsIdentity(still)) << c.name << " at " << c.still;
        CompositeLook moves = gated();
        c.set(moves, c.moves);
        EXPECT_FALSE(compositeIsIdentity(moves)) << c.name << " at " << c.moves;
    }
}

TEST(TileCompositeIdentity, AGatedInputIsIgnoredWhileTheStyleAppliesItItself) {
    for (const Case& c : cases()) {
        if (c.gate < 0) continue;
        CompositeLook closed = gated();
        closed.gate[static_cast<size_t>(c.gate)] = 0.5f;
        c.set(closed, c.moves);
        EXPECT_TRUE(compositeIsIdentity(closed)) << c.name << " with its gate closed";
        // Only its own gate decides: opening every other one changes nothing.
        CompositeLook other = CompositeLook{};
        other.gate[static_cast<size_t>(c.gate)] = 0.51f;
        c.set(other, c.moves);
        EXPECT_FALSE(compositeIsIdentity(other)) << c.name << " with only its gate open";
    }
}

TEST(TileCompositeIdentity, AnUngatedInputCountsWhateverTheGates) {
    for (const Case& c : cases()) {
        if (c.gate >= 0) continue;
        CompositeLook look;
        c.set(look, c.moves);
        EXPECT_FALSE(compositeIsIdentity(look)) << c.name;
    }
}

TEST(TileCompositeIdentity, AKaleidoscopeNeedsBothItsSwitchAndTwoSegments) {
    CompositeLook off = gated();
    off.kaleido = false;
    off.symmetry = 8.0f;
    EXPECT_TRUE(compositeIsIdentity(off));
    CompositeLook on = gated();
    on.kaleido = true;
    on.symmetry = 8.0f;
    EXPECT_FALSE(compositeIsIdentity(on));
}

TEST(TileCompositeIdentity, SolarizeCountsOnlyWithTheGeometryGate) {
    CompositeLook look = gated();
    look.solarize = true;
    EXPECT_FALSE(compositeIsIdentity(look));
    look.gate[0] = 0.0f;
    EXPECT_TRUE(compositeIsIdentity(look));
}

TEST(TileCompositeIdentity, MirrorAndInvertCountOnlyWithTheirGate) {
    for (const bool mirror : {true, false}) {
        CompositeLook look = gated();
        look.mirror = mirror;
        look.invert = !mirror;
        EXPECT_FALSE(compositeIsIdentity(look)) << (mirror ? "mirror" : "invert");
        look.gate[1] = 0.5f;
        EXPECT_TRUE(compositeIsIdentity(look)) << (mirror ? "mirror" : "invert") << " with its gate closed";
    }
}

TEST(TileCompositeIdentity, PushInAndOrbitCountOnlyWhereTheCompositeMovesThePicture) {
    CompositeLook push = gated();
    push.camPush = 0.00011f;
    EXPECT_FALSE(compositeIsIdentity(push));
    push.camPush = 0.0001f;
    EXPECT_TRUE(compositeIsIdentity(push));
    // A pull-back reads as no push: the shader only magnifies.
    push.camPush = -0.5f;
    EXPECT_TRUE(compositeIsIdentity(push));
    push.camPush = 0.5f;
    push.camMoves = 0.5f;
    EXPECT_TRUE(compositeIsIdentity(push));

    for (const bool yaw : {true, false}) {
        CompositeLook tilt = gated();
        (yaw ? tilt.camTiltYaw : tilt.camTiltPitch) = -0.00011f;
        EXPECT_FALSE(compositeIsIdentity(tilt)) << (yaw ? "yaw" : "pitch");
        (yaw ? tilt.camTiltYaw : tilt.camTiltPitch) = 0.0001f;
        EXPECT_TRUE(compositeIsIdentity(tilt)) << (yaw ? "yaw" : "pitch");
        (yaw ? tilt.camTiltYaw : tilt.camTiltPitch) = 0.3f;
        tilt.camMoves = 0.0f;
        EXPECT_TRUE(compositeIsIdentity(tilt)) << (yaw ? "yaw" : "pitch") << " on a Style with its own camera";
    }
    // The yaw and the pitch add up before the threshold.
    CompositeLook both = gated();
    both.camTiltYaw = 0.00006f;
    both.camTiltPitch = -0.00006f;
    EXPECT_FALSE(compositeIsIdentity(both));
}

TEST(TileCompositeIdentity, TheShakeAddsItsThreeAxesBeforeTheThreshold) {
    CompositeLook look;
    look.camShakeX = 0.0000004f;
    look.camShakeY = -0.0000004f;
    look.camShakeRoll = 0.0000004f;
    EXPECT_FALSE(compositeIsIdentity(look));
}

TEST(TileCompositeIdentity, TheDriftAddsItsTwoAxesBeforeTheThreshold) {
    CompositeLook look = gated();
    look.driftX = 0.0006f;
    look.driftY = -0.0006f;
    EXPECT_FALSE(compositeIsIdentity(look));
}

TEST(TileCompositeIdentity, ADiveCountsOnlyAwayFromTheStartOfItsCycle) {
    CompositeLook travel = gated();
    travel.camTravelDive = 0.3f;
    EXPECT_FALSE(compositeIsIdentity(travel));
    travel.camMoves = 0.0f;
    EXPECT_TRUE(compositeIsIdentity(travel));

    CompositeLook endless = gated();
    endless.camEndlessDive = 0.3f;
    EXPECT_FALSE(compositeIsIdentity(endless));
    endless.camEndless = 0.5f;
    EXPECT_TRUE(compositeIsIdentity(endless));

    // The two phases add, and a whole cycle is the start again.
    CompositeLook whole = gated();
    whole.camTravelDive = 0.25f;
    whole.camEndlessDive = 0.75f;
    EXPECT_TRUE(compositeIsIdentity(whole));
    whole.camEndlessDive = 0.7f;
    EXPECT_FALSE(compositeIsIdentity(whole));

    CompositeLook nearStart = gated();
    nearStart.camTravelDive = 0.00009f;
    EXPECT_TRUE(compositeIsIdentity(nearStart));
    nearStart.camTravelDive = 0.00011f;
    EXPECT_FALSE(compositeIsIdentity(nearStart));
}

}  // namespace
}  // namespace geode::viz::tile
