#include "geode/viz/RenderScale.hpp"

#include <algorithm>
#include <cmath>

#include "geode/util/Json.hpp"

namespace geode::viz::scale {

namespace {
constexpr float kPixelsPerMegapixel = 1.0e6f;
constexpr float kMsPerSecond = 1000.0f;
constexpr const char* kStyleDir = "styles/";
constexpr const char* kManifestFile = "/manifest.json";
}  // namespace

// ---- supersampling -------------------------------------------------------------------------------

float liveSupersample(int surfaceWidth, int surfaceHeight) {
    if (surfaceWidth <= 0 || surfaceHeight <= 0) return kMinLiveSupersample;
    const float pixels = static_cast<float>(surfaceWidth) * static_cast<float>(surfaceHeight);
    const float factor = std::sqrt(kLiveTargetMegapixels * kPixelsPerMegapixel / pixels);
    return std::clamp(factor, kMinLiveSupersample, kMaxLiveSupersample);
}

// ---- per-Style render scale ----------------------------------------------------------------------

std::string styleManifestPath(std::string_view styleId) {
    std::string path(kStyleDir);
    path.append(styleId);
    path.append(kManifestFile);
    return path;
}

float manifestRenderScale(const std::string& manifestJson) {
    const auto doc = json::parse(manifestJson);
    if (!doc || doc->kind() != json::Value::Kind::Object) return kDefaultStyleRenderScale;
    const json::Value* value = doc->get(std::string(kStyleManifestKey));
    if (value == nullptr || value->kind() != json::Value::Kind::Number) return kDefaultStyleRenderScale;
    const float declared = static_cast<float>(value->number());
    if (!std::isfinite(declared)) return kDefaultStyleRenderScale;
    return std::clamp(declared, kMinStyleRenderScale, kMaxStyleRenderScale);
}

// ---- load scale ----------------------------------------------------------------------------------

float frameBudgetMs(float pacedFps) {
    const float fps = pacedFps > 0.0f ? pacedFps : kDefaultFrameRate;
    return kMsPerSecond / fps;
}

LoadVerdict judge(const FrameLoad& load, float budgetMs, const LoadScaleConfig& config) {
    if (load.frames < config.minFrames || budgetMs <= 0.0f || !(load.p95Ms > 0.0f)) return LoadVerdict::Unknown;
    if (load.p95Ms > budgetMs * config.overBudgetRatio) return LoadVerdict::Over;
    if (load.p95Ms <= budgetMs * config.withinBudgetRatio) return LoadVerdict::Within;
    return LoadVerdict::Steady;
}

LoadScaleController::LoadScaleController(LoadScaleConfig config) : config_(config), upDwellSeconds_(config.upDwellSeconds) {}

int LoadScaleController::maxLevel() const {
    if (config_.step <= 0.0f || config_.maxScale <= config_.minScale) return 0;
    return static_cast<int>(std::lround((config_.maxScale - config_.minScale) / config_.step));
}

float LoadScaleController::scale() const {
    return std::clamp(config_.maxScale - static_cast<float>(level_) * config_.step, config_.minScale, config_.maxScale);
}

void LoadScaleController::changeLevel(int delta) {
    level_ = std::clamp(level_ + delta, 0, maxLevel());
    overSeconds_ = 0.0f;
    withinSeconds_ = 0.0f;
    sinceChangeSeconds_ = 0.0f;
    lastStepWasUp_ = delta < 0;
}

float LoadScaleController::step(float dtSeconds, const FrameLoad& load, float budgetMs) {
    const float dt = std::max(dtSeconds, 0.0f);
    sinceChangeSeconds_ += dt;
    switch (judge(load, budgetMs, config_)) {
        case LoadVerdict::Over:
            overSeconds_ += dt;
            withinSeconds_ = 0.0f;
            break;
        case LoadVerdict::Within:
            withinSeconds_ += dt;
            overSeconds_ = 0.0f;
            break;
        case LoadVerdict::Steady:
        case LoadVerdict::Unknown:
            overSeconds_ = 0.0f;
            withinSeconds_ = 0.0f;
            break;
    }
    const bool held = sinceChangeSeconds_ >= config_.minHoldSeconds;
    if (held && overSeconds_ >= config_.downDwellSeconds && level_ < maxLevel()) {
        // Stepping down again soon after a step up: that step up asked for more than the phone has.
        if (lastStepWasUp_ && sinceChangeSeconds_ < config_.probeWindowSeconds) {
            upDwellSeconds_ = std::min(upDwellSeconds_ * 2.0f, config_.maxUpDwellSeconds);
        }
        changeLevel(+1);
    } else if (held && withinSeconds_ >= upDwellSeconds_ && level_ > 0) {
        changeLevel(-1);
    }
    return scale();
}

void LoadScaleController::reset() {
    level_ = 0;
    overSeconds_ = 0.0f;
    withinSeconds_ = 0.0f;
    sinceChangeSeconds_ = 0.0f;
    lastStepWasUp_ = false;
    upDwellSeconds_ = config_.upDwellSeconds;
}

// ---- the render size -----------------------------------------------------------------------------

float totalScale(const ScaleInputs& in) {
    if (in.offscreen) return std::clamp(in.exportSupersample, kMinExportSupersample, kMaxExportSupersample);
    const float quality = std::max(in.styleScale * std::min(in.loadScale, in.thermalCeiling), kMinQualityScale);
    return liveSupersample(in.surfaceWidth, in.surfaceHeight) * quality;
}

RenderSize renderSize(const ScaleInputs& in) {
    const float total = totalScale(in);
    const int w = std::max(static_cast<int>(std::lround(static_cast<float>(std::max(in.surfaceWidth, 1)) * total)), 1);
    const int h = std::max(static_cast<int>(std::lround(static_cast<float>(std::max(in.surfaceHeight, 1)) * total)), 1);
    return {w, h};
}

}  // namespace geode::viz::scale
