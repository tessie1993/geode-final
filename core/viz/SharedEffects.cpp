#include "geode/viz/SharedEffects.hpp"

#include <algorithm>
#include <cmath>

#include "geode/viz/FrameRate.hpp"

namespace geode::viz {

namespace {
constexpr float kMinEchoScale = 0.5f;

// `perFrame` (what one reference frame keeps) over a frame of `dt`; nothing for a stalled frame.
float perFrame(float perFrame, float dt) {
    if (!(dt > 0.0f) || !std::isfinite(dt) || !(perFrame > 0.0f)) return 0.0f;
    return std::pow(perFrame, dt * sharedfx::kReferenceFps);
}

// The effects read from any frame that gives each Shared core property's value.
template <typename Frame>
SharedEffects effectsOf(const Frame& e) {
    SharedEffects fx;
    fx.trails = e[mod::Shared::Trails];
    fx.trailZoom = e[mod::Shared::TrailZoom];
    fx.trailWarp = e[mod::Shared::TrailWarp];
    fx.softFocus = e[mod::Shared::SoftFocus];
    fx.motionBlur = e[mod::Shared::MotionBlur];
    fx.particles = e[mod::Shared::Particles];
    fx.particleKind = static_cast<int>(e[mod::Shared::ParticleKind]);
    fx.particleSize = e[mod::Shared::ParticleSize];
    return fx;
}
}  // namespace

SharedEffects SharedEffects::from(const mod::Evaluated& e) { return effectsOf(e); }

SharedEffects SharedEffects::from(const mod::NativeFrame& frame) { return effectsOf(frame); }

namespace sharedfx {

float echoKeep(float trails, float dt) { return perFrame(std::isnan(trails) ? 0.0f : std::min(trails, kMaxTrails), dt); }

float motionKeep(float motionBlur, float dt) {
    return perFrame(mod::unit(motionBlur) * kMaxMotionKeep, dt);
}

float echoZoom(float zoom, float dt) {
    if (!std::isfinite(zoom)) return 0.0f;
    // The per-frame scale stays positive, so a picture never folds through its centre.
    const float perFrameScale = std::max(1.0f - zoom * kEchoZoomScale, kMinEchoScale);
    return (1.0f - framerate::perFrameFactor(perFrameScale, dt)) / kEchoZoomScale;
}

float echoWarp(float warp, float dt) { return std::isfinite(warp) ? warp * framerate::referenceFrames(dt) : 0.0f; }

}  // namespace sharedfx

}  // namespace geode::viz
