#pragma once
#include <array>
#include <cstddef>

#include "geode/api/geode_api.h"

namespace geode::viz {

// The analysis frame as every Style family reads it (#294): the structure, rhythm, timbre and space of
// the music, beyond the bands and the beat. The renderer steps one bus per display frame from the frame
// it draws and hands the values to every scene with the Camera (Scene::setFeatures); shader Styles get
// them as the uniforms of lib_scene_features.glsl, the other families through featuremap below.
//
// Nothing here is raw. Every value is shaped for a picture: slow values glide, hits rise over an attack
// and decay (they never step a frame), timbre is latched at phrase rate, and the tempo-dependent values
// fall away while the tracker is unsure, so an uncertain tempo leaves the picture to the energy rather
// than making it jitter. Silence settles every value and holds it. The same frames give the same values,
// so a Render repeats (ADR-0005).
struct FeatureValues {
    // ---- rhythm ----
    // Each drum's own envelope, 0..1: the low body, the mid crack and the high sparkle.
    float kick = 0.0f;
    float snare = 0.0f;
    float hat = 0.0f;
    // The beat within the bar, 0..3, held while the bar is not trusted.
    float beatInBar = 0.0f;
    // The bar's phase, 0..1. Shader Styles read it as the bar shared input instead (uBarPhase).
    float barPhase = 0.0f;
    // The downbeat as a hit envelope, 0..1, fired only while the bar is trusted.
    float downbeat = 0.0f;
    float downbeatConfidence = 0.0f;
    float pulseConfidence = 0.0f;
    float tempoStability = 0.0f;
    // How far the tempo is trusted (0..1), and the bar on top of it: what every tempo-locked use fades by.
    float rhythmTrust = 0.0f;
    float barTrust = 0.0f;
    // The strength a downbeat or a section boundary fired with, on the one frame it fired; else 0.
    float downbeatStrike = 0.0f;
    float sectionStrike = 0.0f;

    // ---- structure ----
    float novelty = 0.0f;
    // A phase 0..1 that steps on to a new place at each section boundary and glides there: read it through
    // a periodic function (a hue, a sine), never as a level.
    float section = 0.0f;
    float buildup = 0.0f;
    // The long glow after a drop and an arrival, 0..1, over several seconds.
    float drop = 0.0f;
    float arrival = 0.0f;
    // The slow loudness of the passage, 0..1.
    float macro = 0.0f;
    // One-shot hits that decay within about a second.
    float sectionHit = 0.0f;
    float dropHit = 0.0f;
    float arrivalHit = 0.0f;

    // ---- timbre, latched at phrase rate ----
    float centroid = 0.0f;
    float harmonicity = 0.5f;
    // Spectral flux against its own recent peak, and the onset strength, both 0..1.
    float flux = 0.0f;
    float onset = 0.0f;

    // ---- space ----
    float stereoWidth = 0.0f;
    float stereoPan = 0.0f;
    float stereoCorrelation = 1.0f;

    // ---- the bands against the song's usual level (#319), bass, mid and treble in that order ----
    // Each band read against its own usual level in this song, so a quiet song and a loud one, or a
    // bass-light mix and a bass-heavy one, move a picture alike. The level follows quickly and reads
    // 0.5 at the usual level and 1 at twice it; the presence is the same reading followed over
    // seconds (how much of the band the passage has); the hits rise when the band jumps well above
    // its usual level and decay.
    std::array<float, 3> bandLevel{};
    std::array<float, 3> bandHits{};
    std::array<float, 3> bandPresence{};
};

namespace featurebus {

// One hit envelope: a fire starts a linear rise to its strength over the attack, then the value decays
// exponentially. A fire while rising raises the peak; it never steps the value.
class Hit {
public:
    float step(float fired, float dt, float attackSeconds, float fallHz);
    float value() const { return value_; }
    void reset();

private:
    float value_ = 0.0f;
    float peak_ = 0.0f;
    bool rising_ = false;
};

// One band against its usual level in this song (#319). The usual level follows what is heard, up
// over seconds and down more slowly, and holds through silence, so a pause does not make the next
// note read as a jump; it starts at the first level heard.
class UsualBand {
public:
    void step(float band, float dt);
    float level() const { return level_; }
    float presence() const { return presence_; }
    float hits() const { return hit_.value(); }
    float usual() const { return usual_; }
    void reset();

private:
    float usual_ = 0.0f;
    bool heard_ = false;
    float level_ = 0.0f;
    float presence_ = 0.0f;
    bool armed_ = true;
    Hit hit_;
};

// One-pole toward `target` with its own rise and fall rates, framerate independent.
float slew(float current, float target, float dt, float riseHz, float fallHz);
// Shortest signed step from `from` to `to` on the 0..1 circle.
float wrappedStep(float from, float to);
// How far a tempo is trusted from the tracker's confidence and stability, 0..1.
float trustOf(float pulseConfidence, float tempoStability);
// The values with every hit (the drums, the downbeat, the one-shot hits, the strikes and the bands' hits) scaled by `scale`,
// for Reduce motion; the slow values, the timbre and the space are left as they are.
FeatureValues calmed(const FeatureValues& v, float scale);

// The longest frame the bus integrates; a stall moves nothing further.
constexpr float kMaxDt = 0.25f;
// Below this an envelope or glide is 0, so silence settles exactly.
constexpr float kSettled = 1e-4f;

constexpr float kTrustFloor = 0.25f;
constexpr float kTrustCeiling = 0.65f;
constexpr float kTrustRiseHz = 0.5f;
constexpr float kTrustFallHz = 1.5f;
// The bar trust at which a downbeat fires, the beat in the bar is followed and the bar paces the latch.
constexpr float kBarTrustFloor = 0.35f;

constexpr float kConfidenceHz = 1.0f;
constexpr float kNoveltyRiseHz = 0.6f;
constexpr float kNoveltyFallHz = 0.25f;
constexpr float kBuildupRiseHz = 1.0f;
constexpr float kBuildupFallHz = 0.4f;
constexpr float kMacroRiseHz = 0.8f;
constexpr float kMacroFallHz = 0.4f;
constexpr float kSpaceHz = 1.2f;
constexpr float kSectionGlideHz = 0.35f;
// The golden-ratio conjugate: each section lands visibly apart from the one before, never repeating.
constexpr float kSectionStep = 0.6180339f;

struct HitShape {
    float attackSeconds;
    float fallHz;
};
constexpr HitShape kKick{0.03f, 7.0f};
constexpr HitShape kSnare{0.02f, 9.0f};
constexpr HitShape kHat{0.015f, 14.0f};
constexpr HitShape kDownbeat{0.06f, 3.0f};
constexpr HitShape kSectionHit{0.12f, 1.4f};
constexpr HitShape kDropHit{0.1f, 1.0f};
constexpr HitShape kArrivalHit{0.15f, 1.2f};
constexpr HitShape kDropGlow{0.6f, 0.12f};
constexpr HitShape kArrivalGlow{0.8f, 0.2f};

// The bands against their usual level (#319). A band softer than kAudible is not heard and leaves its
// usual level where it is; the usual level never falls below kUsualFloor, so a near-silent band does
// not read every whisper as a jump. A hit fires when the band reaches kBandHitRatio times its usual
// level, with half its strength there and all of it at twice that, and fires again once the band has
// fallen back under kBandRearmRatio.
constexpr size_t kBands = 3;
constexpr float kAudible = 1e-3f;
constexpr float kUsualFloor = 0.02f;
constexpr float kUsualRiseSeconds = 4.0f;
constexpr float kUsualFallSeconds = 16.0f;
constexpr float kLevelRiseHz = 20.0f;
constexpr float kLevelFallHz = 6.0f;
constexpr float kPresenceHz = 0.5f;
constexpr float kBandHitRatio = 1.5f;
constexpr float kBandRearmRatio = 1.1f;
constexpr HitShape kBandHit{0.02f, 8.0f};

// Timbre is averaged between latches and glides to each new average. A latch comes every phrase of
// kBarsPerPhrase trusted downbeats (no sooner than kMinLatchSeconds), at a section boundary, or after
// kFallbackLatchSeconds without either; the first comes kFirstLatchSeconds in.
constexpr int kBarsPerPhrase = 4;
constexpr float kMinLatchSeconds = 2.0f;
constexpr float kFirstLatchSeconds = 2.0f;
constexpr float kFallbackLatchSeconds = 8.0f;
constexpr float kTrustedLatchSeconds = 16.0f;
constexpr float kTimbreGlideHz = 0.4f;
// Flux is read against its own peak, which falls away over this long; never below the floor.
constexpr float kFluxPeakSeconds = 10.0f;
constexpr float kFluxFloor = 1e-4f;

}  // namespace featurebus

class FeatureBus {
public:
    // Steps one display frame of dt seconds; the one-hop fields of `f` are the events that fired since the
    // last display frame (DisplayEvents).
    const FeatureValues& step(const GeodeFeatureFrame& f, float dt);
    const FeatureValues& values() const { return values_; }
    void reset();

private:
    void stepRhythm(const GeodeFeatureFrame& f, float dt);
    void stepStructure(const GeodeFeatureFrame& f, float dt);
    void stepTimbre(const GeodeFeatureFrame& f, float dt);
    void stepBands(const GeodeFeatureFrame& f, float dt);

    FeatureValues values_;
    featurebus::Hit kick_;
    featurebus::Hit snare_;
    featurebus::Hit hat_;
    featurebus::Hit downbeat_;
    featurebus::Hit sectionHit_;
    featurebus::Hit dropHit_;
    featurebus::Hit arrivalHit_;
    featurebus::Hit dropGlow_;
    featurebus::Hit arrivalGlow_;
    float sectionTarget_ = 0.0f;

    float sinceLatch_ = 0.0f;
    int barsSinceLatch_ = 0;
    bool latchedOnce_ = false;
    float weight_ = 0.0f;
    std::array<float, 4> sums_{};
    std::array<float, 4> targets_{0.0f, 0.5f, 0.0f, 0.0f};
    float fluxPeak_ = featurebus::kFluxFloor;
    std::array<featurebus::UsualBand, featurebus::kBands> bands_;
};

// The uniforms ShaderScene uploads from the bus, as lib_scene_features.glsl declares them, in upload order.
// A host test holds the list, the library and the shipped Styles to each other: every one is declared,
// nothing else is, and at least one Style reads each.
// clang-format off
constexpr std::array<const char*, 33> kFeatureUniforms = {
    "uKick", "uSnare", "uHat", "uBeatInBar", "uDownbeat", "uDownbeatConfidence", "uPulseConfidence", "uTempoStability",
    "uNovelty", "uSection", "uBuildup", "uDrop", "uArrival", "uMacro", "uSectionHit", "uDropHit", "uArrivalHit",
    "uCentroid", "uHarmonicity", "uFlux", "uOnset",
    "uStereoWidth", "uStereoPan", "uStereoCorrelation",
    "uBassLevel", "uMidLevel", "uTrebleLevel", "uBassHits", "uMidHits", "uTrebleHits",
    "uBassPresence", "uMidPresence", "uTreblePresence",
};
// clang-format on
// The values in kFeatureUniforms' order.
std::array<float, kFeatureUniforms.size()> uniformValues(const FeatureValues& v);

// How the families other than the shader Styles draw the bus (#294): each a factor or offset on a value
// the family already has, neutral (1, or 0) when the music gives it nothing, and slow or enveloped so
// nothing jumps a frame.
namespace featuremap {

// Fluid: the kick pushes the emitters, a drop throws the whole field, the brightness of the sound tints it.
constexpr float kFluidKickForce = 0.6f;
constexpr float kFluidDropForce = 0.9f;
constexpr float kFluidDropCurl = 0.8f;
constexpr float kFluidCentroidHue = 0.2f;
float fluidSplatForce(const FeatureValues& v);
float fluidCurl(const FeatureValues& v);
float fluidHueOffset(const FeatureValues& v);

// Life: while the bar is trusted the kicks seed at the bar's own clock position, so each bar draws the
// same figure; harmonic music leans the rule toward growth, noisy music away from it.
constexpr float kGoldenAngle = 2.3999632f;
constexpr float kLifeRuleSpan = 0.02f;
float lifeKickAngle(float previousAngle, const FeatureValues& v);
float lifeRuleScale(const FeatureValues& v);

// Mycelium: novelty widens the sensors, so a changing passage branches more.
constexpr float kMycoNoveltyBranch = 0.35f;
float mycoSensorAngleScale(const FeatureValues& v);

// Acid: flux deepens the feedback, a drop clears the loop faster for a moment.
constexpr float kAcidFluxFeedback = 0.02f;
constexpr float kAcidDropDecay = 0.06f;
float acidFeedbackScale(const FeatureValues& v);

// Cymatics: while the bar is trusted the plate climbs from half its modes on the downbeat to all of them
// by the bar's end.
int cymaticsModes(int modes, const FeatureValues& v);

// Curl Flow: a build pushes the field harder.
constexpr float kCurlBuild = 0.8f;
float curlForce(const FeatureValues& v);

// Water: each trusted downbeat rings the pool from the centre; a section swells every ripple.
constexpr float kWaterRing = 0.35f;
constexpr float kWaterSectionSwell = 0.8f;
float waterRing(const FeatureValues& v);
float waterSwell(const FeatureValues& v);

// MilkDrop: never the preset's own variables; only Geode's post pass leans in on the downbeat and a drop.
constexpr float kMilkDownbeatZoom = 0.03f;
constexpr float kMilkDropZoom = 0.05f;
float milkdropZoom(const FeatureValues& v);

}  // namespace featuremap

}  // namespace geode::viz
