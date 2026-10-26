#include "geode/viz/Layers.hpp"

#include <gtest/gtest.h>

#include <cmath>
#include <fstream>
#include <limits>
#include <optional>
#include <set>
#include <sstream>
#include <string>

#include "geode/viz/StylePacks.hpp"
#include "geode/viz/VisualSafety.hpp"

namespace geode::viz::layer {
namespace {

constexpr float kTolerance = 1e-5f;
constexpr float kStep = 0.05f;
constexpr float kNaN = std::numeric_limits<float>::quiet_NaN();
constexpr uint32_t kJulia = 3997830u;
constexpr uint32_t kPlasma = 15643514u;

Request asking(uint32_t style, Blend blend = Blend::Normal, float opacity = 1.0f, float amount = 0.0f, float swap = 0.0f,
               Influence influence = Influence::None) {
    Request r;
    r.spec.style = style;
    r.spec.blend = blend;
    r.spec.influence = influence;
    r.opacity = opacity;
    r.amount = amount;
    r.swap = swap;
    return r;
}

// A mixer that has shown `request` fully since its first frame.
Mixer showing(const Request& request) {
    Mixer mixer;
    mixer.step(request, kStep);
    return mixer;
}

std::optional<std::string> readShipped(const std::string& path) {
    std::ifstream in(std::string(GEODE_STYLE_PACK_ROOT) + "/" + path, std::ios::binary);
    if (!in) return std::nullopt;
    std::ostringstream text;
    text << in.rdbuf();
    return text.str();
}

TEST(Layers, TheModesAreTheModelsInItsOrder) {
    EXPECT_EQ(kBlendCount, 10);
    EXPECT_EQ(kInfluenceCount, 6);
    EXPECT_EQ(blendAt(0.0f), Blend::Normal);
    EXPECT_EQ(blendAt(1.0f), Blend::Add);
    EXPECT_EQ(blendAt(2.0f), Blend::Screen);
    EXPECT_EQ(blendAt(3.0f), Blend::Lighten);
    EXPECT_EQ(blendAt(4.0f), Blend::Multiply);
    EXPECT_EQ(blendAt(5.0f), Blend::Overlay);
    EXPECT_EQ(blendAt(6.0f), Blend::SoftLight);
    EXPECT_EQ(blendAt(7.0f), Blend::Difference);
    EXPECT_EQ(blendAt(8.0f), Blend::Exclusion);
    EXPECT_EQ(blendAt(9.0f), Blend::Luminosity);
    EXPECT_EQ(influenceAt(0.0f), Influence::None);
    EXPECT_EQ(influenceAt(1.0f), Influence::Bend);
    EXPECT_EQ(influenceAt(2.0f), Influence::Swirl);
    EXPECT_EQ(influenceAt(3.0f), Influence::CutOut);
    EXPECT_EQ(influenceAt(4.0f), Influence::Tint);
    EXPECT_EQ(influenceAt(5.0f), Influence::Outline);
}

TEST(Layers, AModeOrdinalIsRoundedAndAnythingElseIsTheFirstMode) {
    EXPECT_EQ(blendAt(2.4f), Blend::Screen);
    EXPECT_EQ(blendAt(8.6f), Blend::Luminosity);
    EXPECT_EQ(blendAt(9.6f), Blend::Normal);
    EXPECT_EQ(blendAt(10.0f), Blend::Normal);
    EXPECT_EQ(blendAt(-0.6f), Blend::Normal);
    EXPECT_EQ(blendAt(-1.0f), Blend::Normal);
    EXPECT_EQ(blendAt(kNaN), Blend::Normal);
    EXPECT_EQ(blendAt(std::numeric_limits<float>::infinity()), Blend::Normal);
    EXPECT_EQ(influenceAt(2.6f), Influence::CutOut);
    EXPECT_EQ(influenceAt(4.6f), Influence::Outline);
    EXPECT_EQ(influenceAt(6.0f), Influence::None);
    EXPECT_EQ(influenceAt(-1.0f), Influence::None);
    EXPECT_EQ(influenceAt(kNaN), Influence::None);
}

TEST(Layers, OnlyTheModesThatInvertAreHeldToTheFlashDepth) {
    EXPECT_FLOAT_EQ(opacityCap(Blend::Difference), safety::kMaxFlashDepth);
    EXPECT_FLOAT_EQ(opacityCap(Blend::Exclusion), safety::kMaxFlashDepth);
    for (const Blend mode : {Blend::Normal, Blend::Add, Blend::Screen, Blend::Lighten, Blend::Multiply, Blend::Overlay,
                             Blend::SoftLight, Blend::Luminosity}) {
        EXPECT_FLOAT_EQ(opacityCap(mode), 1.0f);
    }
}

TEST(Layers, AStyleIsNamedByItsFoldedFnvHash) {
    EXPECT_EQ(styleHash("julia"), kJulia);
    EXPECT_EQ(styleHash("plasma"), kPlasma);
    EXPECT_EQ(styleHash(""), 1875268u);
    EXPECT_NE(styleHash("julia"), styleHash("julib"));
    EXPECT_LE(styleHash("fluid_neon"), 0xFFFFFFu);
}

TEST(Layers, EveryShippedStyleHasItsOwnNameAndNoneIsNoStyle) {
    const packs::StylePackRegistry registry = packs::StylePackRegistry::load(readShipped);
    ASSERT_FALSE(registry.styles().empty());
    std::set<uint32_t> names;
    for (const packs::StylePack& pack : registry.styles()) {
        const uint32_t name = styleHash(pack.id);
        EXPECT_NE(name, kNoStyle) << pack.id;
        EXPECT_LE(name, 0xFFFFFFu) << pack.id;
        EXPECT_TRUE(names.insert(name).second) << pack.id << " shares its name with another Style";
    }
}

TEST(Mixer, TheFirstFrameShowsLayerTwoAtOnce) {
    Mixer mixer;
    const Mix& mix = mixer.step(asking(kJulia, Blend::Screen, 0.6f, 0.3f, 0.2f, Influence::Tint), kStep);
    EXPECT_EQ(mix.spec.style, kJulia);
    EXPECT_EQ(mix.spec.blend, Blend::Screen);
    EXPECT_EQ(mix.spec.influence, Influence::Tint);
    EXPECT_FLOAT_EQ(mix.presence, 1.0f);
    EXPECT_FLOAT_EQ(mix.opacity, 0.6f);
    EXPECT_FLOAT_EQ(mix.amount, 0.3f);
    EXPECT_FLOAT_EQ(mix.swap, 0.2f);
    EXPECT_TRUE(mix.draws());
}

TEST(Mixer, NoStyleDrawsNothing) {
    Mixer mixer;
    const Mix& mix = mixer.step(asking(kNoStyle, Blend::Multiply), kStep);
    EXPECT_FALSE(mix.draws());
    EXPECT_FLOAT_EQ(mix.presence, 0.0f);
    EXPECT_EQ(mix.spec.blend, Blend::Normal);
    EXPECT_FALSE(mixer.step(asking(kNoStyle, Blend::Screen), kStep).draws());
}

TEST(Mixer, ALayerTurnedOnFadesIn) {
    Mixer mixer = showing(asking(kNoStyle));
    const Mix& adopted = mixer.step(asking(kJulia), kStep);
    EXPECT_EQ(adopted.spec.style, kJulia);
    EXPECT_FLOAT_EQ(adopted.presence, 0.0f);
    EXPECT_FALSE(adopted.draws());
    EXPECT_NEAR(mixer.step(asking(kJulia), kStep).presence, kStep / kFadeSeconds, kTolerance);
    EXPECT_TRUE(mixer.current().draws());
    EXPECT_NEAR(mixer.step(asking(kJulia), kStep).presence, 2.0f * kStep / kFadeSeconds, kTolerance);
    for (int i = 0; i < 20; ++i) mixer.step(asking(kJulia), kStep);
    EXPECT_FLOAT_EQ(mixer.current().presence, 1.0f);
}

TEST(Mixer, ALayerTurnedOffFadesOutBeforeItGoes) {
    Mixer mixer = showing(asking(kJulia));
    const Mix& first = mixer.step(asking(kNoStyle), kStep);
    EXPECT_EQ(first.spec.style, kJulia);
    EXPECT_NEAR(first.presence, 1.0f - kStep / kFadeSeconds, kTolerance);
    EXPECT_TRUE(first.draws());
    for (int i = 0; i < 5; ++i) mixer.step(asking(kNoStyle), kStep);
    EXPECT_EQ(mixer.current().spec.style, kJulia);
    EXPECT_GT(mixer.current().presence, 0.0f);
    mixer.step(asking(kNoStyle), kStep);
    EXPECT_FLOAT_EQ(mixer.current().presence, 0.0f);
    EXPECT_EQ(mixer.current().spec.style, kNoStyle);
    EXPECT_FALSE(mixer.current().draws());
}

TEST(Mixer, ANewStyleOrModeComesInOnlyOnceTheOldOneHasGone) {
    for (const Request& next : {asking(kPlasma), asking(kJulia, Blend::Multiply), asking(kJulia, Blend::Normal, 1.0f, 0.0f, 0.0f,
                                                                                            Influence::CutOut)}) {
        Mixer mixer = showing(asking(kJulia));
        float last = 1.0f;
        int frames = 0;
        while (mixer.current().spec != next.spec) {
            const Mix& mix = mixer.step(next, kStep);
            if (mix.spec != next.spec) {
                EXPECT_LT(mix.presence, last);
                EXPECT_EQ(mix.spec.style, kJulia);
                last = mix.presence;
            }
            ASSERT_LT(++frames, 20);
        }
        EXPECT_EQ(frames, 7);
        EXPECT_FLOAT_EQ(mixer.current().presence, 0.0f);
        EXPECT_NEAR(mixer.step(next, kStep).presence, kStep / kFadeSeconds, kTolerance);
    }
}

TEST(Mixer, OpacityInfluenceAndSwapNeverSwingFasterThanTheSlew) {
    Mixer mixer = showing(asking(kJulia, Blend::Normal, 0.0f, 1.0f, 0.0f));
    const Mix& mix = mixer.step(asking(kJulia, Blend::Normal, 1.0f, 0.0f, 1.0f), kStep);
    const float slew = kStep / kSlewSeconds;
    EXPECT_NEAR(mix.opacity, slew, kTolerance);
    EXPECT_NEAR(mix.amount, 1.0f - slew, kTolerance);
    EXPECT_NEAR(mix.swap, slew, kTolerance);
    for (int i = 0; i < 20; ++i) mixer.step(asking(kJulia, Blend::Normal, 1.0f, 0.0f, 1.0f), kStep);
    EXPECT_FLOAT_EQ(mixer.current().opacity, 1.0f);
    EXPECT_FLOAT_EQ(mixer.current().amount, 0.0f);
    EXPECT_FLOAT_EQ(mixer.current().swap, 1.0f);
    // A small move lands exactly, without overshooting.
    EXPECT_FLOAT_EQ(mixer.step(asking(kJulia, Blend::Normal, 0.95f, 0.02f, 0.97f), kStep).opacity, 0.95f);
    EXPECT_FLOAT_EQ(mixer.current().amount, 0.02f);
    EXPECT_FLOAT_EQ(mixer.current().swap, 0.97f);
}

TEST(Mixer, AFastLoopIsBoundedLikeASlowOneAtAnyFrameRate) {
    for (const float dt : {1.0f / 30.0f, 1.0f / 60.0f, 1.0f / 120.0f}) {
        Mixer mixer = showing(asking(kJulia, Blend::Screen, 0.0f));
        float last = 0.0f;
        float elapsed = 0.0f;
        while (elapsed < 2.0f) {
            // A square wave flipping opacity end to end ten times a second.
            const bool high = static_cast<int>(elapsed * 20.0f) % 2 == 0;
            const float now = mixer.step(asking(kJulia, Blend::Screen, high ? 1.0f : 0.0f), dt).opacity;
            ASSERT_LE(std::fabs(now - last), dt / kSlewSeconds + kTolerance);
            last = now;
            elapsed += dt;
        }
    }
}

TEST(Mixer, TheFlashingModesAreHeldToTheirCap) {
    Mixer difference = showing(asking(kJulia, Blend::Difference, 0.9f));
    EXPECT_FLOAT_EQ(difference.current().opacity, safety::kMaxFlashDepth);
    for (int i = 0; i < 10; ++i) difference.step(asking(kJulia, Blend::Difference, 0.9f), kStep);
    EXPECT_FLOAT_EQ(difference.current().opacity, safety::kMaxFlashDepth);
    EXPECT_FLOAT_EQ(difference.step(asking(kJulia, Blend::Difference, 0.1f), kStep).opacity, 0.15f);
    Mixer screen = showing(asking(kJulia, Blend::Screen, 0.9f));
    EXPECT_FLOAT_EQ(screen.current().opacity, 0.9f);
}

TEST(Mixer, WhileNothingIsOnScreenTheValuesFollowAtOnce) {
    Mixer mixer = showing(asking(kNoStyle, Blend::Normal, 0.0f));
    mixer.step(asking(kJulia, Blend::Normal, 0.8f, 0.6f, 0.4f), kStep);
    EXPECT_FLOAT_EQ(mixer.current().opacity, 0.8f);
    EXPECT_FLOAT_EQ(mixer.current().amount, 0.6f);
    EXPECT_FLOAT_EQ(mixer.current().swap, 0.4f);
}

TEST(Mixer, OutOfRangeValuesAreKeptInRange) {
    Mixer mixer;
    const Mix& mix = mixer.step(asking(kJulia, Blend::Normal, 4.0f, kNaN, -2.0f), kStep);
    EXPECT_FLOAT_EQ(mix.opacity, 1.0f);
    EXPECT_FLOAT_EQ(mix.amount, 0.0f);
    EXPECT_FLOAT_EQ(mix.swap, 0.0f);
}

TEST(Mixer, ABrokenFrameMovesNothingAndAStallMovesOneLongFrame) {
    Mixer mixer = showing(asking(kJulia, Blend::Normal, 0.0f));
    for (const float dt : {kNaN, -1.0f, 0.0f}) {
        EXPECT_FLOAT_EQ(mixer.step(asking(kPlasma, Blend::Normal, 1.0f), dt).presence, 1.0f);
        EXPECT_EQ(mixer.current().spec.style, kJulia);
        EXPECT_FLOAT_EQ(mixer.current().opacity, 0.0f);
    }
    EXPECT_NEAR(mixer.step(asking(kJulia, Blend::Normal, 1.0f), 5.0f).opacity, kMaxStepSeconds / kSlewSeconds, kTolerance);
    EXPECT_NEAR(mixer.step(asking(kPlasma), 5.0f).presence, 1.0f - kMaxStepSeconds / kFadeSeconds, kTolerance);
}

TEST(Mixer, CoolingLetsLayerTwoGoSlowly) {
    Mixer mixer = showing(asking(kJulia));
    Request cool = asking(kNoStyle);
    cool.cooling = true;
    EXPECT_NEAR(mixer.step(cool, kStep).presence, 1.0f - kStep / kCoolingFadeSeconds, kTolerance);
    EXPECT_GE(kCoolingFadeSeconds, 1.0f);
    Mixer quick = showing(asking(kJulia));
    EXPECT_NEAR(quick.step(asking(kNoStyle), kStep).presence, 1.0f - kStep / kFadeSeconds, kTolerance);
}

TEST(Mixer, AResetTakesTheNextRequestAtOnce) {
    Mixer mixer = showing(asking(kJulia));
    mixer.reset();
    EXPECT_FALSE(mixer.current().draws());
    EXPECT_EQ(mixer.current().spec.style, kNoStyle);
    const Mix& mix = mixer.step(asking(kPlasma, Blend::Overlay, 0.4f), kStep);
    EXPECT_EQ(mix.spec.style, kPlasma);
    EXPECT_FLOAT_EQ(mix.presence, 1.0f);
    EXPECT_FLOAT_EQ(mix.opacity, 0.4f);
}

}  // namespace
}  // namespace geode::viz::layer
