#pragma once
#include <vector>

namespace geode::analysis {

// The tempo heard in the last few seconds, from a bank of comb resonators over the onset strength.
//
// No tempo is preferred. The onset strength has its running mean removed, so a resonator scores only what
// repeats, and each score is corrected for the noise its own length lets through, so no period wins by
// being long. Periods are fractional (an allpass carries the part of a hop an integer delay cannot), so
// every tempo in [kMinBpm, kMaxBpm] rings as well as any other.
//
// A beat pattern rings every resonator whose period is a whole number of beats, so the strongest one
// fixes the family, not the beat. Which member is the beat is decided by evidence: the strongest
// resonator's own state holds one cycle of the pattern, and its period is split into 5, 3 or 2 while hits
// as strong as kHitEvidence of the main one land at every split point. The confidence is how strongly the
// music rings, lowered when a split was a close call (a backbeat half as loud as the downbeat could be
// either tempo), so a confident tempo is one whose octave is clear too. The beat's phase is read from
// the same state: how long ago the main hit was, in beats.
class TempoTracker {
public:
    static constexpr float kMinBpm = 40.0f;
    static constexpr float kMaxBpm = 250.0f;
    static constexpr int kResonators = 160;
    // How long a resonator remembers, and how long its energy is averaged: the tempo follows a song
    // whose tempo moves within a few seconds.
    static constexpr float kHalfLifeSeconds = 3.0f;
    static constexpr float kEnergyAverageSeconds = 2.0f;
    // How long the onset strength's running mean (removed before the resonators) averages over.
    static constexpr float kMeanSeconds = 2.0f;
    // A split point counts as a beat when its hit is at least this share of the main hit.
    static constexpr float kHitEvidence = 0.5f;
    // How far from kHitEvidence a split's evidence must be for the choice to count as clear, and how much
    // of the confidence a choice right at kHitEvidence costs.
    static constexpr float kClearMargin = 0.3f;
    static constexpr float kAmbiguityCost = 0.5f;

    explicit TempoTracker(float hopRateHz, float minBpm = kMinBpm, float maxBpm = kMaxBpm,
                          int resonatorCount = kResonators, float halfLifeSeconds = kHalfLifeSeconds,
                          float energyAverageSeconds = kEnergyAverageSeconds);

    float periodFrames() const { return periodFrames_; }
    float bpm() const { return bpm_; }
    float confidence() const { return confidence_; }
    // How far through the current beat the last hop is: 0 on the beat, rising to 1 at the next.
    float phase() const { return phase_; }
    void step(float onset);
    void reset();

private:
    struct Comb {
        float period;
        int delay;
        float allpass;
        float feedback;
        float noiseGain;
        std::vector<float> line;
        int write;
        float allpassState;
        float energy;
    };

    void decide();
    float valueAt(const Comb& comb, float lag) const;
    float hitAt(const Comb& comb, float lag, float baseline) const;
    float evidence(const Comb& comb, float mainLag, float mainHit, float baseline, float fraction) const;

    float hopRateHz_;
    float logStep_;
    float minPeriod_;
    std::vector<Comb> combs_;
    std::vector<float> scores_;
    std::vector<float> pattern_;
    float meanPole_;
    float energyPole_;
    float mean_ = 0.0f;
    bool seeded_ = false;
    float previous_ = 0.0f;
    float beforePrevious_ = 0.0f;
    float inputEnergy_ = 0.0f;
    float periodFrames_ = 0.0f;
    float bpm_ = 0.0f;
    float confidence_ = 0.0f;
    float phase_ = 0.0f;
};

}  // namespace geode::analysis
