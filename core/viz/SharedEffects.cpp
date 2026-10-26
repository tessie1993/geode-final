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
    fx.echoCopy = e[mod::Shared::EchoCopy];
    fx.echoCopyZoom = e[mod::Shared::EchoCopyZoom];
    const float flip = e[mod::Shared::EchoCopyFlip];
    fx.echoCopyFlip = std::isfinite(flip) ? static_cast<int>(std::lround(std::clamp(flip, 0.0f, 3.0f))) : 0;
    fx.softFocus = e[mod::Shared::SoftFocus];
    fx.motionBlur = e[mod::Shared::MotionBlur];
    fx.particles = e[mod::Shared::Particles];
    fx.particleKind = static_cast<int>(e[mod::Shared::ParticleKind]);
    fx.particleSize = e[mod::Shared::ParticleSize];
    fx.anaglyph = e[mod::Shared::Anaglyph] >= 0.5f ? 1.0f : 0.0f;
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

EchoCopyFade stepEchoCopy(const EchoCopyFade& fade, int targetFlip, float dt) {
    EchoCopyFade out{std::clamp(fade.flip, 0, kEchoCopyFlips - 1), mod::unit(fade.presence)};
    if (!(dt > 0.0f) || !std::isfinite(dt)) return out;
    const int target = std::clamp(targetFlip, 0, kEchoCopyFlips - 1);
    const float step = dt / kEchoCopyFadeSeconds;
    if (out.flip == target) {
        out.presence = std::min(out.presence + step, 1.0f);
        return out;
    }
    out.presence = std::max(out.presence - step, 0.0f);
    if (out.presence == 0.0f) out.flip = target;
    return out;
}

float echoCopyMix(float amount, float presence) { return kEchoCopyMaxMix * mod::unit(amount) * mod::unit(presence); }

float echoCopyZoom(float zoom) { return std::isfinite(zoom) ? std::clamp(zoom, kMinEchoCopyZoom, kMaxEchoCopyZoom) : 1.0f; }

std::array<float, 2> echoCopyFlipScale(int flip) {
    const int f = std::clamp(flip, 0, kEchoCopyFlips - 1);
    return {(f & 1) != 0 ? -1.0f : 1.0f, (f & 2) != 0 ? -1.0f : 1.0f};
}

float anaglyphMix(float current, float target, float dt) {
    const float from = mod::unit(current);
    const float to = mod::unit(target);
    if (!(dt > 0.0f) || !std::isfinite(dt)) return from;
    const float step = dt / kAnaglyphFadeSeconds;
    return from < to ? std::min(from + step, to) : std::max(from - step, to);
}

}  // namespace sharedfx

}  // namespace geode::viz
