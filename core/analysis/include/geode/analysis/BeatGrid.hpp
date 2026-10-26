#pragma once

namespace geode::analysis {

// The beat grid: where the current beat is, and whether a hit lands on it. While the tempo is confident
// the phase is the tempo tracker's own (read from its resonator, so it re-finds the beat by itself if it
// ever drifts); before that it runs on the estimated period from the last hit.
class BeatGrid {
public:
    static constexpr float kLockConfidence = 0.4f;
    static constexpr float kOnGridTolerance = 0.12f;

    float phase() const { return phase_; }
    bool beat() const { return beat_; }
    bool locked() const { return locked_; }
    // tempoPhase: the tempo tracker's phase, 0 on the beat.
    bool step(float periodFrames, float tempoPhase, float confidence, bool onset);
    void reset();

private:
    float phase_ = 0.0f;
    bool beat_ = false;
    bool locked_ = false;
};

}  // namespace geode::analysis
