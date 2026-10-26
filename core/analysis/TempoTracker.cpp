#include "geode/analysis/TempoTracker.hpp"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <limits>

namespace geode::analysis {

namespace {
// The splits tried, largest first. Between 40 and 250 BPM a resonator's period holds at most six beats, so
// 5, 3 and 2, applied in turn (2 x 2, 2 x 3), reach every whole number of them.
constexpr int kSplits[] = {5, 3, 2};
// The resonators' input energy at or below which nothing has been heard.
constexpr float kSilentEnergy = 1e-12f;
// How far below the shortest resonator a split's period may land and still count (rounding of the peak).
constexpr float kPeriodTolerance = 0.98f;

float wrap(float lag, float period) {
    return lag - period * std::floor(lag / period);
}
}  // namespace

TempoTracker::TempoTracker(float hopRateHz, float minBpm, float maxBpm, int resonatorCount, float halfLifeSeconds,
                           float energyAverageSeconds)
    : hopRateHz_(hopRateHz),
      meanPole_(1.0f - std::exp(-1.0f / (kMeanSeconds * hopRateHz))),
      energyPole_(1.0f - std::exp(-1.0f / (energyAverageSeconds * hopRateHz))) {
    const double shortest = 60.0 * hopRateHz / maxBpm;
    const double longest = 60.0 * hopRateHz / minBpm;
    logStep_ = static_cast<float>(std::log(longest / shortest) / (resonatorCount - 1));
    minPeriod_ = static_cast<float>(shortest);
    combs_.reserve(static_cast<size_t>(resonatorCount));
    for (int i = 0; i < resonatorCount; i++) {
        Comb c{};
        c.period = static_cast<float>(shortest * std::exp(static_cast<double>(logStep_) * i));
        // The allpass delays accurately by half a hop to one and a half, so the integer delay leaves that much.
        c.delay = static_cast<int>(std::floor(c.period - 0.5f));
        const float fraction = c.period - static_cast<float>(c.delay);
        c.allpass = (1.0f - fraction) / (1.0f + fraction);
        c.feedback = std::pow(0.5f, c.period / (halfLifeSeconds * hopRateHz));
        c.noiseGain = (1.0f - c.feedback) / (1.0f + c.feedback);
        // The allpass reads y[n - delay - 1]; reading one cycle back between two hops needs one more.
        c.line.assign(static_cast<size_t>(c.delay + 3), 0.0f);
        combs_.push_back(std::move(c));
    }
    scores_.assign(combs_.size(), 0.0f);
    pattern_.reserve(static_cast<size_t>(longest) + 2);
}

void TempoTracker::step(float onset) {
    if (!seeded_) {
        mean_ = onset;
        seeded_ = true;
    }
    mean_ += (onset - mean_) * meanPole_;
    const float raw = onset - mean_;
    // Three-tap smoothing, so a hit that falls between two hops still lines up with itself from cycle to cycle.
    const float x = 0.25f * raw + 0.5f * previous_ + 0.25f * beforePrevious_;
    beforePrevious_ = previous_;
    previous_ = raw;
    inputEnergy_ += (x * x - inputEnergy_) * energyPole_;

    for (auto& c : combs_) {
        const int size = static_cast<int>(c.line.size());
        const float old = c.line[(c.write - c.delay + size) % size];
        const float older = c.line[(c.write - c.delay - 1 + size) % size];
        const float delayed = c.allpass * (old - c.allpassState) + older;
        c.allpassState = delayed;
        const float y = c.feedback * delayed + (1.0f - c.feedback) * x;
        c.line[c.write] = y;
        c.write = c.write + 1 == size ? 0 : c.write + 1;
        c.energy += (y * y - c.energy) * energyPole_;
    }

    if (inputEnergy_ <= kSilentEnergy) {
        periodFrames_ = 0.0f;
        bpm_ = 0.0f;
        confidence_ = 0.0f;
        phase_ = 0.0f;
        return;
    }
    // How much of the input each resonator holds beyond the noise its length lets through: 1 for an input
    // that repeats at its period, 0 for noise, whatever the period.
    for (size_t i = 0; i < combs_.size(); i++) {
        const Comb& c = combs_[i];
        scores_[i] = (c.energy / inputEnergy_ - c.noiseGain) / (1.0f - c.noiseGain);
    }
    decide();
}

void TempoTracker::decide() {
    const int count = static_cast<int>(combs_.size());
    const int best = static_cast<int>(std::max_element(scores_.begin(), scores_.end()) - scores_.begin());
    float peak = scores_[best];
    if (peak <= 0.0f) {
        periodFrames_ = 0.0f;
        bpm_ = 0.0f;
        confidence_ = 0.0f;
        phase_ = 0.0f;
        return;
    }
    // The peak between two resonators: a parabola through the best score and its neighbours, in log-period.
    float period = combs_[best].period;
    if (best > 0 && best < count - 1) {
        const float a = scores_[best - 1];
        const float b = scores_[best];
        const float c = scores_[best + 1];
        const float curve = a - 2.0f * b + c;
        if (curve < 0.0f) {
            const float offset = 0.5f * (a - c) / curve;
            peak = b - 0.25f * (a - c) * offset;
            period *= std::exp(offset * logStep_);
        }
    }

    // One cycle of the strongest resonator: where its main hit is, and its median as the level of no hit.
    const Comb& comb = combs_[best];
    pattern_.clear();
    const int cycle = static_cast<int>(std::ceil(comb.period));
    for (int lag = 0; lag < cycle; lag++) pattern_.push_back(valueAt(comb, static_cast<float>(lag)));
    const int mainLag = static_cast<int>(std::max_element(pattern_.begin(), pattern_.end()) - pattern_.begin());
    const auto middle = pattern_.begin() + static_cast<std::ptrdiff_t>(pattern_.size() / 2);
    std::nth_element(pattern_.begin(), middle, pattern_.end());
    const float baseline = *middle;
    const float mainHit = hitAt(comb, static_cast<float>(mainLag), baseline);

    // Split the period while every split point carries a hit; remember the decision nearest the line.
    int beats = 1;
    float closest = std::numeric_limits<float>::max();
    float rejected = 0.0f;
    bool splitting = mainHit > 0.0f;
    while (splitting) {
        splitting = false;
        float strongest = 0.0f;
        for (const int split : kSplits) {
            if (period / static_cast<float>(beats * split) < minPeriod_ * kPeriodTolerance) continue;
            float weakest = 1.0f;
            for (int m = 0; m < beats; m++) {
                for (int j = 1; j < split; j++) {
                    const float fraction = (static_cast<float>(m) + static_cast<float>(j) / split) / beats;
                    weakest = std::min(weakest, evidence(comb, static_cast<float>(mainLag), mainHit, baseline, fraction));
                }
            }
            if (weakest >= kHitEvidence) {
                closest = std::min(closest, weakest - kHitEvidence);
                beats *= split;
                splitting = true;
                break;
            }
            strongest = std::max(strongest, weakest);
        }
        if (!splitting) rejected = strongest;
    }
    closest = std::min(closest, std::fabs(rejected - kHitEvidence));

    const float ambiguity = std::max(0.0f, 1.0f - closest / kClearMargin);
    confidence_ = std::min(peak, 1.0f) * (1.0f - kAmbiguityCost * ambiguity);
    periodFrames_ = period / static_cast<float>(beats);
    bpm_ = 60.0f * hopRateHz_ / periodFrames_;
    // The main hit was mainLag hops ago in the smoothed input, which runs one hop late.
    const float beat = comb.period / static_cast<float>(beats);
    phase_ = wrap(static_cast<float>(mainLag + 1), beat) / beat;
}

float TempoTracker::valueAt(const Comb& comb, float lag) const {
    const int size = static_cast<int>(comb.line.size());
    const int newest = comb.write == 0 ? size - 1 : comb.write - 1;
    const int whole = static_cast<int>(lag);
    const float part = lag - static_cast<float>(whole);
    const float a = comb.line[(newest - whole + size) % size];
    const float b = comb.line[(newest - whole - 1 + size) % size];
    return a + (b - a) * part;
}

// The hit around `lag`: the comb's output over three neighbouring hops above the baseline, so a hit that
// falls between two hops counts in full.
float TempoTracker::hitAt(const Comb& comb, float lag, float baseline) const {
    float sum = 0.0f;
    for (int d = -1; d <= 1; d++) sum += std::max(valueAt(comb, wrap(lag + static_cast<float>(d), comb.period)) - baseline, 0.0f);
    return sum;
}

// The strongest hit within a hop of `fraction` of a period after the main hit, over the main hit.
float TempoTracker::evidence(const Comb& comb, float mainLag, float mainHit, float baseline, float fraction) const {
    const float at = mainLag - fraction * comb.period;
    float strongest = 0.0f;
    for (int d = -1; d <= 1; d++) {
        strongest = std::max(strongest, hitAt(comb, wrap(at + static_cast<float>(d), comb.period), baseline));
    }
    return std::min(strongest / mainHit, 1.0f);
}

void TempoTracker::reset() {
    for (auto& c : combs_) {
        std::fill(c.line.begin(), c.line.end(), 0.0f);
        c.write = 0;
        c.allpassState = 0.0f;
        c.energy = 0.0f;
    }
    std::fill(scores_.begin(), scores_.end(), 0.0f);
    mean_ = 0.0f;
    seeded_ = false;
    previous_ = 0.0f;
    beforePrevious_ = 0.0f;
    inputEnergy_ = 0.0f;
    periodFrames_ = 0.0f;
    bpm_ = 0.0f;
    confidence_ = 0.0f;
    phase_ = 0.0f;
}

}  // namespace geode::analysis
