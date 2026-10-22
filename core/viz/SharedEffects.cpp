#include "geode/viz/SharedEffects.hpp"

#include <algorithm>
#include <cmath>

namespace geode::viz {

namespace {
// `perFrame` (what one reference frame keeps) over a frame of `dt`; nothing for a stalled frame.
float perFrame(float perFrame, float dt) {
    if (!(dt > 0.0f) || !std::isfinite(dt) || !(perFrame > 0.0f)) return 0.0f;
    return std::pow(perFrame, dt * sharedfx::kReferenceFps);
}
}  // namespace

SharedEffects SharedEffects::from(const mod::Evaluated& e) {
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

namespace sharedfx {

float echoKeep(float trails, float dt) { return perFrame(std::isnan(trails) ? 0.0f : std::min(trails, kMaxTrails), dt); }

float motionKeep(float motionBlur, float dt) {
    return perFrame(mod::unit(motionBlur) * kMaxMotionKeep, dt);
}

}  // namespace sharedfx

}  // namespace geode::viz
