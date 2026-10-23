#pragma once
#include <vector>

namespace geode::analysis {

// The tempo heard in the last few seconds, from a bank of comb resonators over the onset strength.
// No tempo is preferred: every candidate in [kMinBpm, kMaxBpm] counts only by how strongly the music
// rings at it, so a slow song is not read as a fast one, and when two octaves ring alike the
// confidence says so instead of a built-in favourite deciding.
class TempoTracker {
public:
    static constexpr float kMinBpm = 40.0f;
    static constexpr float kMaxBpm = 250.0f;
    static constexpr int kResonators = 80;
    // How long a resonator remembers, and how long its energy is averaged: the tempo follows a song
    // whose tempo moves within a few seconds.
    static constexpr float kHalfLifeSeconds = 4.0f;
    static constexpr float kEnergyAverageSeconds = 2.0f;

    explicit TempoTracker(float hopRateHz, float minBpm = kMinBpm, float maxBpm = kMaxBpm,
                          int resonatorCount = kResonators, float halfLifeSeconds = kHalfLifeSeconds,
                          float energyAverageSeconds = kEnergyAverageSeconds);

    float periodFrames() const { return periodFrames_; }
    float bpm() const { return bpm_; }
    float confidence() const { return confidence_; }
    void step(float onset);
    void reset();

private:
    float clarity(float bestScore);
    float bpmOf(int periodFrames) const { return 60.0f * hopRateHz_ / periodFrames; }
    std::vector<int> buildPeriods(int count) const;

    float hopRateHz_;
    float minBpm_;
    float maxBpm_;
    std::vector<int> periods_;
    std::vector<std::vector<float>> delayLines_;
    std::vector<int> delayIndex_;
    std::vector<float> feedback_;
    std::vector<float> energy_;
    std::vector<float> scores_;
    std::vector<float> scoreScratch_;
    float energyPole_;
    float periodFrames_ = 0.0f;
    float bpm_ = 0.0f;
    float confidence_ = 0.0f;
};

}  // namespace geode::analysis
