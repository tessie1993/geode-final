#pragma once
#include <vector>

namespace geode::analysis {

// Sections, build-ups, drops and arrivals. Every level rule is relative to the track's own loudness (a slow
// level in dB that quiet passages do not drag down), so the same song fires the same moments at any volume,
// mastering or engine.
class StructureTracker {
public:
    static constexpr float kWarmupSeconds = 5.0f;
    static constexpr float kSectionFloor = 0.5f;
    static constexpr float kSectionRearm = 0.35f;
    static constexpr float kSectionRefractorySeconds = 8.0f;
    static constexpr float kPeakDecay = 0.9997f;
    static constexpr float kNoveltyPeakFloor = 0.05f;
    static constexpr float kBuildupScale = 0.3f;
    // The build-up reads the level in dB over this span, so only its change counts, never its value.
    static constexpr float kLevelSpanDb = 24.0f;
    static constexpr float kBuildupMemorySeconds = 3.0f;
    static constexpr float kDropBuildup = 0.4f;
    static constexpr float kDropDipWindowSeconds = 1.0f;
    // A drop lands this far above the track's level, within kDropDipWindowSeconds of a dip.
    static constexpr float kDropSlamDb = 3.0f;
    static constexpr float kDropRefractorySeconds = 4.0f;
    // A dip is this far under the track's level for kMinDipSeconds (or silence).
    static constexpr float kDipDb = 10.0f;
    static constexpr float kMinDipSeconds = 0.1f;
    // An arrival: kArrivalQuietDb under the track's level for kArrivalQuietSeconds, then back within
    // kArrivalRecoveryDb of it.
    static constexpr float kArrivalQuietDb = 12.0f;
    static constexpr float kArrivalQuietSeconds = 2.0f;
    static constexpr float kArrivalRecoveryDb = 4.0f;
    // The track's level follows hops at most kLoudnessGateDb under it over kTrackSeconds, and quieter ones only
    // over kQuietTrackSeconds, so a breakdown or a pause leaves it where the music is.
    static constexpr float kTrackSeconds = 6.0f;
    static constexpr float kQuietTrackSeconds = 30.0f;
    static constexpr float kLoudnessGateDb = 10.0f;
    static constexpr float kMinRms = 1e-6f;

    StructureTracker(int bandCount, float hopRateHz);

    float novelty() const { return novelty_; }
    bool sectionBoundary() const { return sectionBoundary_; }
    int sectionCount() const { return sectionCount_; }
    float buildup() const { return buildup_; }
    bool drop() const { return drop_; }
    bool arrival() const { return arrival_; }
    // The track's level, dB.
    float trackDb() const { return trackDb_; }
    void step(const float* bands, float rms, float onset);
    // A hop of silence: it counts as a dip and as quiet, and changes nothing else.
    void stepSilent();
    void reset();

private:
    float poleFor(float seconds) const;

    int bandCount_;
    float hopRateHz_;
    float dt_;
    float fastPole_;
    float slowPole_;
    float statsPole_;
    float fastEnergyPole_;
    float slowEnergyPole_;
    float noveltySmoothPole_;
    float trackPole_;
    float quietTrackPole_;
    float memoryDecay_;
    std::vector<float> fast_;
    std::vector<float> slow_;
    float noveltyPeak_ = kNoveltyPeakFloor;
    float noveltyMean_ = 0.0f;
    float noveltyDev_ = 0.0f;
    bool sectionArmed_ = true;
    float sinceSection_;
    float fastEnergy_ = 0.0f;
    float slowEnergy_ = 0.0f;
    bool energySeeded_ = false;
    float trackDb_ = 0.0f;
    float buildupMemory_ = 0.0f;
    float sinceDip_;
    float dipSeconds_ = 0.0f;
    float sinceDrop_;
    float quietSeconds_ = 0.0f;
    bool arrivalArmed_ = false;
    float warmupSeconds_ = 0.0f;
    float novelty_ = 0.0f;
    bool sectionBoundary_ = false;
    int sectionCount_ = 0;
    float buildup_ = 0.0f;
    bool drop_ = false;
    bool arrival_ = false;
};

}  // namespace geode::analysis
