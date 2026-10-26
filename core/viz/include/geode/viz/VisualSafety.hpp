#pragma once
#include <string>

#include "geode/viz/Lfo.hpp"
#include "geode/viz/Params.hpp"

namespace geode::viz {

enum class TransitionStyle { Cut, Fade, Melt, Slide, Zoom };

const char* transitionStyleId(TransitionStyle style);

// Port of VisualSafety.kt: the unconditional per-parameter luminance clamp. The frame itself is
// bounded by the No-flash limiter (FlashLimiter), which every finished frame passes through.
namespace safety {

constexpr float kWcagFlashesPerSecond = 3.0f;
constexpr float kMaxFlashDepth = 0.25f;
constexpr float kReducedMotionScale = 0.4f;

SceneParams apply(const SceneParams& p, bool reducedMotion = false);
float limitLfoRate(float rateHz, LfoTarget target);
float beatMinIntervalMs(float requestedMs);
TransitionStyle transitionStyle(TransitionStyle requested);
std::string transitionId(const std::string& requested);

}  // namespace safety

}  // namespace geode::viz
