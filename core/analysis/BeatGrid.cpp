#include "geode/analysis/BeatGrid.hpp"

#include <cmath>

namespace geode::analysis {

bool BeatGrid::step(float periodFrames, float tempoPhase, float confidence, bool onset) {
    locked_ = confidence >= kLockConfidence && periodFrames > 0.0f;

    if (locked_) {
        phase_ = tempoPhase;
    } else if (periodFrames > 0.0f) {
        phase_ += 1.0f / periodFrames;
        while (phase_ >= 1.0f) phase_ -= 1.0f;
    }

    if (!onset) {
        beat_ = false;
    } else if (!locked_) {
        phase_ = 0.0f;
        beat_ = true;
    } else {
        // On the grid when it lands within the tolerance of a beat; an offbeat hit, or one the tempo has not
        // caught up with yet, is not, and the grid keeps the tempo's phase either way.
        const float error = phase_ > 0.5f ? phase_ - 1.0f : phase_;
        beat_ = std::fabs(error) <= kOnGridTolerance;
    }
    return beat_;
}

void BeatGrid::reset() {
    phase_ = 0.0f;
    beat_ = false;
    locked_ = false;
}

}  // namespace geode::analysis
