#pragma once
#include <optional>
#include <string>
#include <string_view>

namespace geode::viz::scale {

// How big the scene and trail buffers are drawn, as a factor of the surface (or export) size.
//
//   total = supersample x max(style x min(load, thermal), kMinQualityScale)   live
//   total = export supersample (a named setting, kExportSupersample by default)  Render (export)
//
// The frame shown is always exactly the surface or export size. The scene targets follow this factor;
// the composite, the tonemap and the No-flash limiter draw at the smaller of the two on each side
// (tile::frameSize), so a supersampled scene is downsampled by the composite and a reduced one is
// upscaled, with linear filtering, only by the limiter's last pass onto the output.

// ---- supersampling -------------------------------------------------------------------------------

// Live supersampling follows one continuous rule: draw about kLiveTargetMegapixels, never below the
// surface and never above kMaxLiveSupersample of it. (It replaces three steps keyed on the longest
// side, under which a 1080x2160 panel drew more pixels than a 1080x2400 one.)
constexpr float kLiveTargetMegapixels = 2.6f;
constexpr float kMinLiveSupersample = 1.0f;
constexpr float kMaxLiveSupersample = 1.4f;

// Renders draw their scene at exactly the export size times the export supersample setting, whatever
// the phone's panel is, so the same project renders the same picture on every phone. The composite
// still writes exactly the export size.
constexpr float kExportSupersample = 1.0f;
constexpr float kMinExportSupersample = 1.0f;
constexpr float kMaxExportSupersample = 2.0f;

float liveSupersample(int surfaceWidth, int surfaceHeight);

// ---- per-Style render scale ----------------------------------------------------------------------

// A Style may declare its own render scale in its pack manifest (`styles/<id>/manifest.json`, key
// "renderScale", read by StylePacks): below 1 for the heavy looks (long raymarches, deep fractal folds).
// A Style that declares none draws at kDefaultStyleRenderScale.
constexpr float kDefaultStyleRenderScale = 1.0f;
constexpr float kMinStyleRenderScale = 0.5f;
constexpr float kMaxStyleRenderScale = 1.0f;

// The render scale a Style draws at for what its manifest declares: clamped to
// [kMinStyleRenderScale, kMaxStyleRenderScale]; kDefaultStyleRenderScale when nothing usable is declared.
float styleRenderScale(std::optional<double> declared);

// The floor under style x min(load, thermal), so no combination draws softer than this.
constexpr float kMinQualityScale = 0.5f;

// ---- load scale ----------------------------------------------------------------------------------

// The frame-time distribution the frame pacer reports (FramePacer.stats on the Kotlin side): how many
// frames its window holds and the 95th percentile of the intervals between drawn frames.
struct FrameLoad {
    int frames = 0;
    float p95Ms = 0.0f;
};

// The frame rate assumed when the pacer runs uncapped.
constexpr float kDefaultFrameRate = 60.0f;

// The interval each frame has, in milliseconds, at the paced rate (0 = uncapped).
float frameBudgetMs(float pacedFps);

enum class LoadVerdict {
    Unknown,  // too few frames to judge
    Over,     // frames are being missed
    Within,   // every frame lands on time
    Steady,   // in between: neither step down nor up
};

struct LoadScaleConfig {
    float minScale = 0.6f;
    float maxScale = 1.0f;
    float step = 0.05f;
    // Fewer frames than this in the pacer's window and the reading is ignored.
    int minFrames = 30;
    // p95 above budget x this: more than one frame in twenty misses its vsync.
    float overBudgetRatio = 1.25f;
    // p95 at or below budget x this: frames land on time, with room for timing noise.
    float withinBudgetRatio = 1.1f;
    // Over budget this long steps down; within budget this long steps up.
    float downDwellSeconds = 2.0f;
    float upDwellSeconds = 15.0f;
    // No two steps closer than this. Longer than the pacer's 4 s window, so a step is never judged
    // on frames drawn before it.
    float minHoldSeconds = 5.0f;
    // A step down this soon after a step up means the step up was too much: the next step up then
    // waits twice as long, up to maxUpDwellSeconds.
    float probeWindowSeconds = 12.0f;
    float maxUpDwellSeconds = 120.0f;
};

LoadVerdict judge(const FrameLoad& load, float budgetMs, const LoadScaleConfig& config);

// Scales the scene buffers with load, with hysteresis: down quickly when frames are missed, up
// slowly once they are not, in quantised steps with a minimum hold between any two of them.
class LoadScaleController {
public:
    explicit LoadScaleController(LoadScaleConfig config = {});

    // Advances by dtSeconds with the latest reading and returns the load scale.
    float step(float dtSeconds, const FrameLoad& load, float budgetMs);
    float scale() const;
    // Steps taken below maxScale.
    int level() const { return level_; }
    float upDwellSeconds() const { return upDwellSeconds_; }
    void reset();

private:
    int maxLevel() const;
    void changeLevel(int delta);

    LoadScaleConfig config_;
    int level_ = 0;  // steps below maxScale
    float overSeconds_ = 0.0f;
    float withinSeconds_ = 0.0f;
    float sinceChangeSeconds_ = 0.0f;
    bool lastStepWasUp_ = false;
    float upDwellSeconds_;
};

// ---- the render size -----------------------------------------------------------------------------

struct RenderSize {
    int width = 1;
    int height = 1;
    bool operator==(const RenderSize&) const = default;
};

struct ScaleInputs {
    int surfaceWidth = 1;
    int surfaceHeight = 1;
    bool offscreen = false;  // a Render: exact export size
    float exportSupersample = kExportSupersample;
    float styleScale = 1.0f;
    float loadScale = 1.0f;
    float thermalCeiling = 1.0f;
};

float totalScale(const ScaleInputs& in);
RenderSize renderSize(const ScaleInputs& in);

}  // namespace geode::viz::scale
