#include "geode/viz/FeatureBus.hpp"

#include <algorithm>
#include <cmath>

namespace geode::viz {

namespace featurebus {

float Hit::step(float fired, float dt, float attackSeconds, float fallHz) {
    const float elapsed = std::clamp(dt, 0.0f, kMaxDt);
    const float strength = std::clamp(fired, 0.0f, 1.0f);
    if (strength > 0.0f) {
        peak_ = std::max(rising_ ? peak_ : value_, strength);
        rising_ = true;
    }
    if (rising_) {
        value_ += elapsed * peak_ / std::max(attackSeconds, 1e-3f);
        if (value_ >= peak_) {
            value_ = peak_;
            rising_ = false;
        }
        return value_;
    }
    value_ *= std::exp(-elapsed * fallHz);
    if (value_ < kSettled) value_ = 0.0f;
    return value_;
}

void Hit::reset() {
    value_ = 0.0f;
    peak_ = 0.0f;
    rising_ = false;
}

float slew(float current, float target, float dt, float riseHz, float fallHz) {
    const float hz = target > current ? riseHz : fallHz;
    const float next = current + (target - current) * (1.0f - std::exp(-std::clamp(dt, 0.0f, kMaxDt) * hz));
    return std::fabs(target - next) < kSettled ? target : next;
}

float wrappedStep(float from, float to) {
    float d = std::fmod(to - from + 0.5f, 1.0f);
    if (d < 0.0f) d += 1.0f;
    return d - 0.5f;
}

float trustOf(float pulseConfidence, float tempoStability) {
    const float weakest = std::min(pulseConfidence, tempoStability);
    return std::clamp((weakest - kTrustFloor) / (kTrustCeiling - kTrustFloor), 0.0f, 1.0f);
}

FeatureValues calmed(const FeatureValues& v, float scale) {
    FeatureValues out = v;
    for (float* hit : {&out.kick, &out.snare, &out.hat, &out.downbeat, &out.downbeatStrike, &out.sectionStrike,
                       &out.sectionHit, &out.dropHit, &out.arrivalHit}) {
        *hit *= scale;
    }
    return out;
}

}  // namespace featurebus

namespace fb = featurebus;

const FeatureValues& FeatureBus::step(const GeodeFeatureFrame& f, float dt) {
    const float elapsed = std::clamp(dt, 0.0f, fb::kMaxDt);
    stepRhythm(f, elapsed);
    stepStructure(f, elapsed);
    stepTimbre(f, elapsed);
    FeatureValues& v = values_;
    v.stereoWidth = fb::slew(v.stereoWidth, std::clamp(f.stereoWidth, 0.0f, 1.0f), elapsed, fb::kSpaceHz, fb::kSpaceHz);
    v.stereoPan = fb::slew(v.stereoPan, std::clamp(f.stereoPan, -1.0f, 1.0f), elapsed, fb::kSpaceHz, fb::kSpaceHz);
    v.stereoCorrelation = fb::slew(v.stereoCorrelation, std::clamp(f.stereoCorrelation, -1.0f, 1.0f), elapsed,
                                   fb::kSpaceHz, fb::kSpaceHz);
    return values_;
}

void FeatureBus::stepRhythm(const GeodeFeatureFrame& f, float dt) {
    FeatureValues& v = values_;
    v.kick = kick_.step(f.kick, dt, fb::kKick.attackSeconds, fb::kKick.fallHz);
    v.snare = snare_.step(f.snare, dt, fb::kSnare.attackSeconds, fb::kSnare.fallHz);
    v.hat = hat_.step(f.hat, dt, fb::kHat.attackSeconds, fb::kHat.fallHz);
    v.pulseConfidence = fb::slew(v.pulseConfidence, std::clamp(f.pulseConfidence, 0.0f, 1.0f), dt, fb::kConfidenceHz,
                                 fb::kConfidenceHz);
    v.tempoStability =
        fb::slew(v.tempoStability, std::clamp(f.tempoStability, 0.0f, 1.0f), dt, fb::kConfidenceHz, fb::kConfidenceHz);
    v.downbeatConfidence = fb::slew(v.downbeatConfidence, std::clamp(f.downbeatConfidence, 0.0f, 1.0f), dt,
                                    fb::kConfidenceHz, fb::kConfidenceHz);
    v.rhythmTrust = fb::slew(v.rhythmTrust, fb::trustOf(f.pulseConfidence, f.tempoStability), dt, fb::kTrustRiseHz,
                             fb::kTrustFallHz);
    v.barTrust = v.rhythmTrust * v.downbeatConfidence;
    const bool barTrusted = v.barTrust >= fb::kBarTrustFloor;
    v.barPhase = std::clamp(f.barPhase, 0.0f, 1.0f);
    if (barTrusted) v.beatInBar = std::clamp(std::round(f.beatInBar), 0.0f, 3.0f);
    v.downbeatStrike = barTrusted && f.downbeat > 0.0f ? v.barTrust : 0.0f;
    v.downbeat = downbeat_.step(v.downbeatStrike, dt, fb::kDownbeat.attackSeconds, fb::kDownbeat.fallHz);
}

void FeatureBus::stepStructure(const GeodeFeatureFrame& f, float dt) {
    FeatureValues& v = values_;
    v.novelty = fb::slew(v.novelty, std::clamp(f.novelty, 0.0f, 1.0f), dt, fb::kNoveltyRiseHz, fb::kNoveltyFallHz);
    v.buildup = fb::slew(v.buildup, std::clamp(f.buildup, 0.0f, 1.0f), dt, fb::kBuildupRiseHz, fb::kBuildupFallHz);
    v.macro = fb::slew(v.macro, std::clamp(f.macroEnergy, 0.0f, 1.0f), dt, fb::kMacroRiseHz, fb::kMacroFallHz);
    const bool section = f.sectionBoundary > 0.0f;
    const bool drop = f.drop > 0.0f;
    const bool arrival = f.arrival > 0.0f;
    v.sectionStrike = section ? 1.0f : 0.0f;
    if (section) sectionTarget_ = std::fmod(sectionTarget_ + fb::kSectionStep, 1.0f);
    // Glides the short way round and lands exactly, so a settled phase holds to the bit.
    const float toTarget = fb::wrappedStep(v.section, sectionTarget_);
    if (std::fabs(toTarget) < fb::kSettled) {
        v.section = sectionTarget_;
    } else {
        float next = v.section + toTarget * (1.0f - std::exp(-dt * fb::kSectionGlideHz));
        if (next < 0.0f) next += 1.0f;
        if (next >= 1.0f) next -= 1.0f;
        v.section = next;
    }
    v.sectionHit = sectionHit_.step(v.sectionStrike, dt, fb::kSectionHit.attackSeconds, fb::kSectionHit.fallHz);
    v.dropHit = dropHit_.step(drop ? 1.0f : 0.0f, dt, fb::kDropHit.attackSeconds, fb::kDropHit.fallHz);
    v.arrivalHit = arrivalHit_.step(arrival ? 1.0f : 0.0f, dt, fb::kArrivalHit.attackSeconds, fb::kArrivalHit.fallHz);
    v.drop = dropGlow_.step(drop ? 1.0f : 0.0f, dt, fb::kDropGlow.attackSeconds, fb::kDropGlow.fallHz);
    v.arrival = arrivalGlow_.step(arrival ? 1.0f : 0.0f, dt, fb::kArrivalGlow.attackSeconds, fb::kArrivalGlow.fallHz);
}

void FeatureBus::stepTimbre(const GeodeFeatureFrame& f, float dt) {
    FeatureValues& v = values_;
    const float flux = std::max(f.flux, 0.0f);
    fluxPeak_ = std::max({flux, fluxPeak_ * std::exp(-dt / fb::kFluxPeakSeconds), fb::kFluxFloor});
    const std::array<float, 4> heard = {
        std::clamp(f.centroid, 0.0f, 1.0f),
        std::clamp(f.harmonicity, 0.0f, 1.0f),
        std::clamp(flux / fluxPeak_, 0.0f, 1.0f),
        std::clamp(f.onset, 0.0f, 1.0f),
    };
    for (size_t i = 0; i < heard.size(); ++i) sums_[i] += heard[i] * dt;
    weight_ += dt;
    sinceLatch_ += dt;
    const bool barTrusted = v.barTrust >= fb::kBarTrustFloor;
    // Only a trusted downbeat strikes, so the bars counted are trusted ones.
    if (v.downbeatStrike > 0.0f) ++barsSinceLatch_;
    const bool settled = sinceLatch_ >= fb::kMinLatchSeconds;
    const bool phrase = settled && barsSinceLatch_ >= fb::kBarsPerPhrase;
    const bool sectionTurn = settled && v.sectionStrike > 0.0f;
    const bool overdue = sinceLatch_ >= (barTrusted ? fb::kTrustedLatchSeconds : fb::kFallbackLatchSeconds);
    const bool first = !latchedOnce_ && sinceLatch_ >= fb::kFirstLatchSeconds;
    // Every latch comes at least kMinLatchSeconds after the last, so there is always time to average.
    if (phrase || sectionTurn || overdue || first) {
        for (size_t i = 0; i < sums_.size(); ++i) targets_[i] = sums_[i] / weight_;
        sums_.fill(0.0f);
        weight_ = 0.0f;
        sinceLatch_ = 0.0f;
        barsSinceLatch_ = 0;
        latchedOnce_ = true;
    }
    v.centroid = fb::slew(v.centroid, targets_[0], dt, fb::kTimbreGlideHz, fb::kTimbreGlideHz);
    v.harmonicity = fb::slew(v.harmonicity, targets_[1], dt, fb::kTimbreGlideHz, fb::kTimbreGlideHz);
    v.flux = fb::slew(v.flux, targets_[2], dt, fb::kTimbreGlideHz, fb::kTimbreGlideHz);
    v.onset = fb::slew(v.onset, targets_[3], dt, fb::kTimbreGlideHz, fb::kTimbreGlideHz);
}

void FeatureBus::reset() {
    values_ = FeatureValues{};
    for (fb::Hit* h :
         {&kick_, &snare_, &hat_, &downbeat_, &sectionHit_, &dropHit_, &arrivalHit_, &dropGlow_, &arrivalGlow_})
        h->reset();
    sectionTarget_ = 0.0f;
    sinceLatch_ = 0.0f;
    barsSinceLatch_ = 0;
    latchedOnce_ = false;
    weight_ = 0.0f;
    sums_.fill(0.0f);
    targets_ = {0.0f, 0.5f, 0.0f, 0.0f};
    fluxPeak_ = fb::kFluxFloor;
}

std::array<float, kFeatureUniforms.size()> uniformValues(const FeatureValues& v) {
    // clang-format off
    return {
        v.kick, v.snare, v.hat, v.beatInBar, v.downbeat, v.downbeatConfidence, v.pulseConfidence, v.tempoStability,
        v.novelty, v.section, v.buildup, v.drop, v.arrival, v.macro, v.sectionHit, v.dropHit, v.arrivalHit,
        v.centroid, v.harmonicity, v.flux, v.onset,
        v.stereoWidth, v.stereoPan, v.stereoCorrelation,
    };
    // clang-format on
}

namespace featuremap {

float fluidSplatForce(const FeatureValues& v) { return 1.0f + kFluidKickForce * v.kick + kFluidDropForce * v.dropHit; }

float fluidCurl(const FeatureValues& v) { return 1.0f + kFluidDropCurl * v.dropHit; }

float fluidHueOffset(const FeatureValues& v) { return v.centroid * kFluidCentroidHue; }

float lifeKickAngle(float previousAngle, const FeatureValues& v) {
    if (v.barTrust >= featurebus::kBarTrustFloor) return v.barPhase * 6.2831853f;
    return std::fmod(previousAngle + kGoldenAngle, 6.2831853f);
}

float lifeRuleScale(const FeatureValues& v) { return 1.0f + kLifeRuleSpan * (2.0f * v.harmonicity - 1.0f); }

float mycoSensorAngleScale(const FeatureValues& v) { return 1.0f + kMycoNoveltyBranch * v.novelty; }

float acidFeedbackScale(const FeatureValues& v) {
    return (1.0f + kAcidFluxFeedback * v.flux) * (1.0f - kAcidDropDecay * v.dropHit);
}

int cymaticsModes(int modes, const FeatureValues& v) {
    if (modes <= 1 || v.barTrust < featurebus::kBarTrustFloor) return modes;
    const float share = 0.5f + 0.5f * std::clamp(v.barPhase, 0.0f, 1.0f);
    return std::clamp(static_cast<int>(std::ceil(static_cast<float>(modes) * share)), 1, modes);
}

float curlForce(const FeatureValues& v) { return 1.0f + kCurlBuild * v.buildup; }

float waterRing(const FeatureValues& v) { return kWaterRing * v.downbeatStrike; }

float waterSwell(const FeatureValues& v) { return 1.0f + kWaterSectionSwell * v.sectionHit; }

float milkdropZoom(const FeatureValues& v) { return kMilkDownbeatZoom * v.downbeat + kMilkDropZoom * v.dropHit; }

}  // namespace featuremap

}  // namespace geode::viz
