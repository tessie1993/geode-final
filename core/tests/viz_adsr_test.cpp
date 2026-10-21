#include "geode/viz/Adsr.hpp"

#include <gtest/gtest.h>

#include <array>

using geode::viz::AdsrConfig;
using geode::viz::AdsrEngine;
using geode::viz::EnvBand;
using geode::viz::LfoEngine;
using geode::viz::LfoTarget;
using geode::viz::SceneParams;

namespace {

constexpr float kTol = 1e-4f;

AdsrConfig envelope() {
    AdsrConfig c;
    c.enabled = true;
    c.targets = {LfoTarget::Zoom};
    c.attack = 0.1f;
    c.decay = 0.2f;
    c.sustain = 0.5f;
    c.release = 0.4f;
    c.gateThreshold = 0.25f;
    c.band = EnvBand::Bass;
    return c;
}

GeodeFeatureFrame frame(float bass, float transient) {
    GeodeFeatureFrame f{};
    f.bass = bass;
    f.transient = transient;
    return f;
}

}  // namespace

TEST(AdsrEngine, DisabledOrUntargetedEnvelopesAreSilent) {
    AdsrEngine adsr;
    adsr.configs[0] = envelope();
    adsr.configs[0].enabled = false;
    adsr.configs[1] = envelope();
    adsr.configs[1].targets = {LfoTarget::None};
    const auto& out = adsr.tick(0.06f, frame(0.5f, 0.8f));
    EXPECT_EQ(out[0], 0.0f);
    EXPECT_EQ(out[1], 0.0f);
}

TEST(AdsrEngine, NothingHappensWithoutAHit) {
    AdsrEngine adsr;
    adsr.configs[0] = envelope();
    EXPECT_EQ(adsr.tick(0.1f, frame(1.0f, 0.0f))[0], 0.0f);
    // Under the hit floor.
    EXPECT_EQ(adsr.tick(0.1f, frame(1.0f, 0.05f))[0], 0.0f);
}

TEST(AdsrEngine, AttackDecaySustainRelease) {
    AdsrEngine adsr;
    adsr.configs[0] = envelope();
    // Attack toward the hit's strength.
    EXPECT_NEAR(adsr.tick(0.06f, frame(0.5f, 0.8f))[0], 0.48f, kTol);
    EXPECT_NEAR(adsr.tick(0.06f, frame(0.5f, 0.0f))[0], 0.8f, kTol);
    // Decay toward sustain * peak = 0.4, at (1 - 0.4) per decay time.
    EXPECT_NEAR(adsr.tick(0.05f, frame(0.5f, 0.0f))[0], 0.65f, kTol);
    EXPECT_NEAR(adsr.tick(0.1f, frame(0.5f, 0.0f))[0], 0.4f, kTol);
    // Held while the band stays over 85% of the gate.
    EXPECT_NEAR(adsr.tick(0.1f, frame(0.5f, 0.0f))[0], 0.4f, kTol);
    EXPECT_NEAR(adsr.tick(0.1f, frame(0.2f, 0.0f))[0], 0.4f, kTol);
    // Released over 0.4 s per unit, to zero.
    EXPECT_NEAR(adsr.tick(0.1f, frame(0.2f, 0.0f))[0], 0.15f, kTol);
    EXPECT_EQ(adsr.tick(0.1f, frame(0.2f, 0.0f))[0], 0.0f);
    EXPECT_EQ(adsr.tick(0.1f, frame(0.2f, 0.0f))[0], 0.0f);
}

TEST(AdsrEngine, ReleaseReturnsToSustainWhenTheGateReopens) {
    AdsrEngine adsr;
    adsr.configs[0] = envelope();
    adsr.tick(0.06f, frame(0.5f, 0.8f));
    adsr.tick(0.06f, frame(0.5f, 0.0f));
    // The band is already under the gate when decay ends: straight to release.
    EXPECT_NEAR(adsr.tick(0.2f, frame(0.1f, 0.0f))[0], 0.4f, kTol);
    EXPECT_NEAR(adsr.tick(0.05f, frame(0.3f, 0.0f))[0], 0.275f, kTol);
    // Back in sustain: easing toward 0.4.
    EXPECT_NEAR(adsr.tick(0.05f, frame(0.3f, 0.0f))[0], 0.325f, kTol);
}

TEST(AdsrEngine, WithoutRetriggerAHitDuringDecayIsIgnored) {
    AdsrEngine adsr;
    adsr.configs[0] = envelope();
    adsr.configs[0].retrigger = false;
    adsr.tick(0.06f, frame(0.5f, 0.8f));
    adsr.tick(0.06f, frame(0.5f, 0.0f));
    EXPECT_NEAR(adsr.tick(0.05f, frame(0.5f, 1.0f))[0], 0.65f, kTol);
    EXPECT_NEAR(adsr.tick(0.05f, frame(0.5f, 0.0f))[0], 0.5f, kTol);
}

TEST(AdsrEngine, WithoutRetriggerAHitDuringReleaseStillRestarts) {
    AdsrEngine adsr;
    adsr.configs[0] = envelope();
    adsr.configs[0].retrigger = false;
    adsr.tick(0.06f, frame(0.5f, 0.8f));
    adsr.tick(0.06f, frame(0.5f, 0.0f));
    adsr.tick(0.2f, frame(0.1f, 0.0f));
    EXPECT_NEAR(adsr.tick(0.1f, frame(0.1f, 0.0f))[0], 0.15f, kTol);
    // Peak is the louder of the hit and the level; the attack climbs from where it is.
    EXPECT_NEAR(adsr.tick(0.05f, frame(0.1f, 0.6f))[0], 0.45f, kTol);
}

TEST(AdsrEngine, AHitDuringTheAttackOnlyRaisesThePeak) {
    AdsrEngine adsr;
    adsr.configs[0] = envelope();
    EXPECT_NEAR(adsr.tick(0.02f, frame(0.5f, 0.5f))[0], 0.1f, kTol);
    // A weaker hit keeps the first one's peak.
    EXPECT_NEAR(adsr.tick(0.02f, frame(0.5f, 0.3f))[0], 0.2f, kTol);
    EXPECT_NEAR(adsr.tick(0.2f, frame(0.5f, 0.0f))[0], 0.5f, kTol);
}

TEST(AdsrEngine, ARetriggerNeverDropsBelowTheCurrentLevel) {
    AdsrEngine adsr;
    adsr.configs[0] = envelope();
    adsr.tick(0.06f, frame(0.5f, 1.0f));
    EXPECT_NEAR(adsr.tick(0.06f, frame(0.5f, 0.0f))[0], 1.0f, kTol);
    EXPECT_NEAR(adsr.tick(0.05f, frame(0.5f, 0.3f))[0], 1.0f, kTol);
    EXPECT_NEAR(adsr.tick(0.01f, frame(0.5f, 0.0f))[0], 0.975f, kTol);
}

TEST(AdsrEngine, TrackedSustainFollowsTheChosenBand) {
    struct Case {
        EnvBand band;
        float GeodeFeatureFrame::*field;
    };
    for (const Case& c : {Case{EnvBand::Bass, &GeodeFeatureFrame::bass}, Case{EnvBand::Mid, &GeodeFeatureFrame::mid},
                          Case{EnvBand::Treble, &GeodeFeatureFrame::treble}, Case{EnvBand::Rms, &GeodeFeatureFrame::rms},
                          Case{EnvBand::Brightness, &GeodeFeatureFrame::centroid},
                          Case{EnvBand::Width, &GeodeFeatureFrame::stereoWidth}}) {
        AdsrEngine adsr;
        AdsrConfig cfg = envelope();
        cfg.band = c.band;
        cfg.attack = 0.01f;
        cfg.decay = 0.01f;
        cfg.sustain = 1.0f;
        cfg.gateThreshold = 1.0f;
        cfg.sustainTrack = true;
        adsr.configs[0] = cfg;
        GeodeFeatureFrame f{};
        f.*c.field = 0.5f;
        f.transient = 1.0f;
        EXPECT_NEAR(adsr.tick(0.02f, f)[0], 1.0f, kTol) << static_cast<int>(c.band);
        f.transient = 0.0f;
        // Sustain = 1 * (0.5 / gate 1) * peak 1.
        EXPECT_NEAR(adsr.tick(0.02f, f)[0], 0.5f, kTol) << static_cast<int>(c.band);
    }
}

TEST(AdsrEngine, DisablingResetsTheEnvelope) {
    AdsrEngine adsr;
    adsr.configs[0] = envelope();
    adsr.tick(0.06f, frame(0.5f, 0.8f));
    adsr.configs[0].enabled = false;
    EXPECT_EQ(adsr.tick(0.06f, frame(0.5f, 0.0f))[0], 0.0f);
    adsr.configs[0].enabled = true;
    // Back from scratch: no hit, no envelope.
    EXPECT_EQ(adsr.tick(0.06f, frame(0.5f, 0.0f))[0], 0.0f);
    // And the peak is the new hit's, not the old one's.
    EXPECT_NEAR(adsr.tick(0.06f, frame(0.5f, 0.5f))[0], 0.3f, kTol);
}

TEST(AdsrEngine, LfoOffsetsFeedOnlyTheChainTargets) {
    std::array<AdsrConfig, AdsrEngine::kCount> configs{};
    configs[0] = envelope();
    configs[0].amount = 0.5f;
    configs[0].targets = {LfoTarget::Lfo1Rate, LfoTarget::Lfo2Depth, LfoTarget::Zoom};
    configs[1] = envelope();
    configs[1].enabled = false;
    configs[1].targets = {LfoTarget::Lfo3Depth};
    std::array<float, LfoEngine::kSlots> rate{7.0f, 7.0f, 7.0f};
    std::array<float, LfoEngine::kSlots> depth{7.0f, 7.0f, 7.0f};
    AdsrEngine::lfoOffsets(configs, {0.4f, 1.0f}, rate, depth);
    EXPECT_NEAR(rate[0], 0.8f, kTol);
    EXPECT_EQ(rate[1], 0.0f);
    EXPECT_EQ(rate[2], 0.0f);
    EXPECT_EQ(depth[0], 0.0f);
    EXPECT_NEAR(depth[1], 0.2f, kTol);
    EXPECT_EQ(depth[2], 0.0f);

    AdsrEngine::lfoOffsets(configs, {0.0f, 1.0f}, rate, depth);
    EXPECT_EQ(rate[0], 0.0f);
    EXPECT_EQ(depth[1], 0.0f);
}

TEST(AdsrEngine, ApplyMovesTheParameterTargets) {
    std::array<AdsrConfig, AdsrEngine::kCount> configs{};
    configs[0] = envelope();
    configs[0].amount = 0.5f;
    configs[0].targets = {LfoTarget::Zoom, LfoTarget::Lfo1Rate, LfoTarget::None, LfoTarget::Speed};
    configs[1] = envelope();
    configs[1].enabled = false;
    configs[1].targets = {LfoTarget::Warp};
    const SceneParams base;
    const SceneParams out = AdsrEngine::apply(base, configs, {0.4f, 1.0f});
    EXPECT_NEAR(out.zoom, 1.2f, kTol);
    EXPECT_NEAR(out.speed, 1.2f, kTol);
    EXPECT_EQ(out.warp, 0.0f);

    const SceneParams idle = AdsrEngine::apply(base, configs, {0.0f, 1.0f});
    EXPECT_EQ(idle.zoom, base.zoom);
    EXPECT_EQ(idle.speed, base.speed);
}

TEST(AdsrEngine, TheGateOpensAtItsThreshold) {
    AdsrEngine adsr;
    adsr.configs[0] = envelope();
    adsr.tick(0.06f, frame(0.5f, 0.8f));
    adsr.tick(0.06f, frame(0.5f, 0.0f));
    EXPECT_NEAR(adsr.tick(0.2f, frame(0.1f, 0.0f))[0], 0.4f, kTol);
    // Exactly at the threshold the release returns to sustain.
    const float gate = adsr.configs[0].gateThreshold;
    EXPECT_NEAR(adsr.tick(0.05f, frame(gate, 0.0f))[0], 0.275f, kTol);
    EXPECT_NEAR(adsr.tick(0.05f, frame(gate, 0.0f))[0], 0.325f, kTol);
}

TEST(AdsrEngine, TheGateHoldsDownTo85PercentOfItsThreshold) {
    const float hold = envelope().gateThreshold * 0.85f;
    for (const float bass : {hold, 0.23f}) {
        AdsrEngine adsr;
        adsr.configs[0] = envelope();
        adsr.tick(0.06f, frame(0.5f, 0.8f));
        adsr.tick(0.06f, frame(0.5f, 0.0f));
        adsr.tick(0.1f, frame(0.5f, 0.0f));
        EXPECT_NEAR(adsr.tick(0.1f, frame(bass, 0.0f))[0], 0.4f, kTol) << bass;
        // Still sustaining, not released.
        EXPECT_NEAR(adsr.tick(0.1f, frame(bass, 0.0f))[0], 0.4f, kTol) << bass;
    }
}

TEST(AdsrEngine, TrackedSustainDividesByTheGate) {
    AdsrEngine adsr;
    AdsrConfig cfg = envelope();
    cfg.attack = 0.01f;
    cfg.decay = 0.01f;
    cfg.sustain = 1.0f;
    cfg.gateThreshold = 0.5f;
    cfg.sustainTrack = true;
    adsr.configs[0] = cfg;
    EXPECT_NEAR(adsr.tick(0.02f, frame(0.25f, 1.0f))[0], 1.0f, kTol);
    // 0.25 of a 0.5 gate: half the sustain.
    EXPECT_NEAR(adsr.tick(0.02f, frame(0.25f, 0.0f))[0], 0.5f, kTol);
}

TEST(AdsrEngine, DisablingRearmsAnEnvelopeThatDoesNotRetrigger) {
    AdsrEngine adsr;
    adsr.configs[0] = envelope();
    adsr.configs[0].retrigger = false;
    adsr.tick(0.06f, frame(0.5f, 0.8f));
    adsr.configs[0].enabled = false;
    adsr.tick(0.06f, frame(0.5f, 0.0f));
    adsr.configs[0].enabled = true;
    EXPECT_NEAR(adsr.tick(0.06f, frame(0.5f, 0.8f))[0], 0.48f, kTol);
}
