#pragma once
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>

namespace geode::viz {

enum class ThermalTier { Full, Eased, Reduced, Minimal };

// What each tier allows: the ceiling it puts on the load scale of the scene and trail buffers
// (RenderScale.hpp), and whether the optional overlay passes (flow, ripple) run.
struct ThermalTierInfo {
    float renderScale;
    bool optionalPasses;
};
inline constexpr ThermalTierInfo kTierFull{1.0f, true};
inline constexpr ThermalTierInfo kTierEased{0.85f, true};
inline constexpr ThermalTierInfo kTierReduced{0.7f, false};
inline constexpr ThermalTierInfo kTierMinimal{0.6f, false};
const ThermalTierInfo& thermalTierInfo(ThermalTier tier);

// Port of PerformanceMonitor.kt: downgrades on a sustained deficit, never upgrades.
class PerformanceMonitor {
public:
    static constexpr float kFreeRunning = 0.0f;
    static constexpr float kDefaultTargetFps = 50.0f;
    static constexpr float kDefaultSustainSeconds = 2.5f;
    static constexpr int kDefaultWindowSize = 30;

    explicit PerformanceMonitor(float targetFps = kDefaultTargetFps, float sustainSeconds = kDefaultSustainSeconds,
                                int windowSize = kDefaultWindowSize);
    void setPacedFps(float fps) { pacedFps_.store(fps); }
    float averageFps() const;
    int onFrame(float dtSeconds);
    void reset();

private:
    static constexpr float kKeepingUpFraction = 0.85f;
    // Intervals outside this band are a pause or a stutter, not a frame rate.
    static constexpr float kMinCredibleFps = 5.0f;
    static constexpr float kMaxCredibleFps = 180.0f;
    // A deficit deeper than this fraction of the target asks for two steps of relief, not one.
    static constexpr float kSevereDeficit = 0.35f;
    static constexpr float kMinFrameSeconds = 1e-6f;
    float effectiveTargetFps() const;

    float targetFps_;
    float sustainSeconds_;
    int windowSize_;
    std::array<float, 64> samples_{};
    int count_ = 0;
    int index_ = 0;
    float deficitSeconds_ = 0.0f;
    float lastTargetFps_ = 0.0f;
    std::atomic<float> pacedFps_{kFreeRunning};
};

// Settles a thermal tier from the platform's readings (status, headroom), which the host pushes in.
// How busy the GPU is - the frame times - is the load scale's job (RenderScale.hpp), not this one's.
class ThermalGovernor {
public:
    ThermalTier tier() const { return offscreen() ? ThermalTier::Full : settled_.load(); }
    // The ceiling on the load scale: the tier's, or the person's Visual quality when that is lower.
    // A Render ignores both and draws at its exact export size.
    float renderCeiling() const {
        const float heat = thermalTierInfo(tier()).renderScale;
        return offscreen() ? heat : std::min(heat, qualityCeiling_.load());
    }
    // The person's Visual quality on screen (Settings, #28) as a render scale ceiling. The automatic
    // cooling can only lower it further. Kept between the coolest tier's scale and 1; NaN means 1.
    void setQualityCeiling(float ceiling) {
        qualityCeiling_.store(std::isnan(ceiling) ? kMaxQualityCeiling
                                                  : std::clamp(ceiling, kMinQualityCeiling, kMaxQualityCeiling));
    }
    float qualityCeiling() const { return qualityCeiling_.load(); }
    bool offscreen() const { return offscreenDepth_.load() > 0; }
    void setPacedFps(float fps) { pacedFps_.store(fps); }
    float pacedFps() const { return pacedFps_.load(); }

    void setPlatformStatus(int status) { osStatus_.store(status); }
    // NaN means no forecast; sampled by the host at most once a second.
    void setThermalHeadroom(float headroom) { headroom_.store(headroom); }

    void onFrame(float dtSeconds);
    void onSurfaceRecreated();
    void beginOffscreenRender() { offscreenDepth_.fetch_add(1); }
    void endOffscreenRender();

private:
    static constexpr int kStatusNone = 0;
    static constexpr int kStatusLight = 1;
    static constexpr int kStatusModerate = 2;
    static constexpr float kSamplePeriodSeconds = 1.25f;
    static constexpr float kEscalateDwellSeconds = 3.0f;
    static constexpr float kRelaxDwellSeconds = 60.0f;
    static constexpr float kHeadroomEased = 0.75f;
    static constexpr float kHeadroomReduced = 0.85f;
    static constexpr float kHeadroomMinimal = 0.95f;
    static constexpr float kMinQualityCeiling = kTierMinimal.renderScale;
    static constexpr float kMaxQualityCeiling = 1.0f;

    ThermalTier observe() const;
    void settle(ThermalTier observed);
    static ThermalTier statusTier(int status);
    ThermalTier headroomTier() const;

    std::atomic<ThermalTier> settled_{ThermalTier::Full};
    std::atomic<int> osStatus_{kStatusNone};
    std::atomic<float> headroom_{NAN};
    std::atomic<int> offscreenDepth_{0};
    std::atomic<float> pacedFps_{0.0f};
    std::atomic<float> qualityCeiling_{kMaxQualityCeiling};
    float sampleClock_ = 0.0f;
    float hotDwellSeconds_ = 0.0f;
    float coolDwellSeconds_ = 0.0f;
};

}  // namespace geode::viz
