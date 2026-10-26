#pragma once
#include <vector>

namespace geode::analysis {

// Band levels on one shared scale, above a measured noise floor.
//
// Each band's level (dB) is read against two references:
//  - Its noise floor: a minimum tracker that follows the band down within kFloorFallSeconds and up over
//    kFloorRiseSeconds, never above kNoiseCeilingDb. Steady room noise and mic hiss become the floor; a held
//    note or pad louder than any quiet room never does. A band reads 0 until it is kNoiseMarginDb above it.
//  - The level of what is heard: one reference for every band, the mean level of the bands standing above
//    their floors, followed over seconds (up within kLevelRiseSeconds, down over kLevelFallSeconds) and held
//    while nothing stands above the floors. A band at that level reads kHeadroomDb / kSpanDb (one half),
//    kHeadroomDb above it reads 1, and kSpanDb below that reads 0.
// Because the bands share one reference the spectrum keeps its shape (pink and white noise read
// differently, a bass-heavy mix reads bass-heavy), a steady tone reads steadily, and the scale follows the
// volume of the music rather than each band's own recent range.
class AdaptiveRange {
public:
    static constexpr float kSilenceDb = -120.0f;
    static constexpr float kFloorFallSeconds = 0.5f;
    static constexpr float kFloorRiseSeconds = 4.0f;
    static constexpr float kNoiseCeilingDb = -80.0f;
    static constexpr float kNoiseMarginDb = 12.0f;
    static constexpr float kSpanDb = 36.0f;
    static constexpr float kHeadroomDb = 18.0f;
    static constexpr float kLevelRiseSeconds = 1.0f;
    static constexpr float kLevelFallSeconds = 4.0f;
    // The fewest bands above their floors that move the shared level: one band poking out of the noise
    // does not.
    static constexpr int kMinLevelBands = 4;
    // How far the whole hop must stand above the floors to be heard as a sound at all.
    static constexpr float kPresenceDb = 10.0f;

    explicit AdaptiveRange(int bandCount, float warmupSeconds = 1.5f);

    int bandCount() const { return bandCount_; }
    float warmup() const;
    // Whether anything has stood above the floors yet; until then every band reads 0.
    bool primed() const { return primed_; }
    float levelDb() const { return levelDb_; }
    float floorDb(int band) const { return floorDb_[band]; }
    // Steps the floors and the shared level with one hop of band levels (dB) and writes each band on the
    // shared scale (0..1) to out. When excess is not null it also gets how far each band stands above the
    // bottom of the scale, in spans and not clamped at 1: what the drum channels difference.
    void normalize(const float* inputDb, float dtSeconds, float* out, float* excess = nullptr);
    // A level over a noise floor (both dB) on the shared scale, as normalize() reads a band.
    float scaled(float levelDb, float floorDb) const;
    // Bands [from, to) as one: their mean power (linear) over their mean floor, on the shared scale.
    float scaledMean(const float* power, int from, int to) const;
    // The power (linear) a band must pass to count as heard: its floor plus the margin.
    float floorPower(int band) const;
    // Whether this hop's band powers (linear), all together, stand kPresenceDb above the floors.
    bool heard(const float* power) const;
    void reset();

private:
    static float follow(float current, float target, float tauSeconds, float dtSeconds);
    float lowerDb(float floorDb) const;

    int bandCount_;
    float warmupSeconds_;
    std::vector<float> floorDb_;
    bool floorsPrimed_ = false;
    bool primed_ = false;
    float levelDb_ = kSilenceDb;
    float adaptedSeconds_ = 0.0f;
};

}  // namespace geode::analysis
