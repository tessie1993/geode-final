#include "geode/viz/FeedbackWarp.hpp"

#include <algorithm>
#include <cmath>

#include "geode/viz/FrameRate.hpp"

namespace geode::viz::feedback {

namespace {

// Below this |lambda| x dt the exact flow term (e^(-lambda dt) - 1) / lambda loses its digits to
// cancellation; its series to second order is exact to float there.
constexpr float kSeriesLimit = 1e-3f;
constexpr float kUnorm10Levels = 1023.0f;
constexpr float kUnorm8Levels = 255.0f;

float held(float value, float low, float high, float fallback) {
    return std::isfinite(value) ? std::clamp(value, low, high) : fallback;
}

float unit(float value) { return held(value, 0.0f, 1.0f, 0.0f); }

// A Drive multiplier: never below 0, and neutral when broken.
float gain(float value) { return std::isfinite(value) ? std::max(value, 0.0f) : 1.0f; }

float seconds(float dt) { return std::isfinite(dt) && dt > 0.0f ? dt : 0.0f; }

Border sanitizedBorder(const Border& border) {
    Border out;
    out.size = held(border.size, 0.0f, kMaxBorderSize, 0.0f);
    for (size_t c = 0; c < 3; ++c) out.color[c] = held(border.color[c], 0.0f, 1.0f, 1.0f);
    out.alpha = unit(border.alpha);
    return out;
}

}  // namespace

WarpSpec sanitized(const WarpSpec& spec) {
    WarpSpec out;
    out.on = spec.on;
    out.decay = held(spec.decay, kMinDecay, kMaxDecay, kDefaultDecay);
    out.zoom = held(spec.zoom, kMinZoom, kMaxZoom, 1.0f);
    out.rotate = held(spec.rotate, -kMaxRotate, kMaxRotate, 0.0f);
    for (size_t i = 0; i < 2; ++i) {
        out.flow[i] = held(spec.flow[i], -kMaxFlow, kMaxFlow, 0.0f);
        out.stretch[i] = held(spec.stretch[i], kMinStretch, kMaxStretch, 1.0f);
        out.center[i] = held(spec.center[i], 0.0f, 1.0f, 0.5f);
    }
    out.warp = held(spec.warp, 0.0f, kMaxWarp, 0.0f);
    for (size_t c = 0; c < 3; ++c) out.tint[c] = held(spec.tint[c], 0.0f, 1.0f, 1.0f);
    out.react = held(spec.react, 0.0f, kMaxReact, 0.0f);
    out.outer = sanitizedBorder(spec.outer);
    out.inner = sanitizedBorder(spec.inner);
    return out;
}

float motionScale(const WarpSpec& spec, const Drive& drive) {
    const float music = unit(drive.swell) + kBeatShare * unit(drive.beat);
    const float react = held(spec.react, 0.0f, kMaxReact, 0.0f);
    return std::clamp(gain(drive.speed) * (1.0f + react * music), 0.0f, kMaxMotion);
}

float keptPerSecond(float decay, float halfLifeScale) {
    if (!(halfLifeScale > 0.0f) || !(decay > 0.0f)) return 0.0f;
    return std::min(std::pow(decay, 1.0f / halfLifeScale), kMaxKept);
}

float perFrame(float perSecond, float dt) {
    const float t = seconds(dt);
    if (t == 0.0f) return 1.0f;
    if (!(perSecond > 0.0f)) return 0.0f;
    return std::pow(perSecond, t);
}

Affine sourceMap(float zoomPerSecond, float rotatePerSecond, const std::array<float, 2>& flow, float dt) {
    const float t = seconds(dt);
    Affine out;
    if (t == 0.0f) return out;
    // lambda = ln(zoom) + i rotate: the picture's growth and turn as one complex rate.
    const float lr = zoomPerSecond > 0.0f && std::isfinite(zoomPerSecond) ? std::log(zoomPerSecond) : 0.0f;
    const float li = std::isfinite(rotatePerSecond) ? rotatePerSecond : 0.0f;
    // Backwards over the frame: m = e^(-lambda t).
    const float scale = std::exp(-lr * t);
    const float mr = scale * std::cos(-li * t);
    const float mi = scale * std::sin(-li * t);
    out.map = {mr, mi, -mi, mr};
    // The flow's share: k = (m - 1) / lambda, which is -t when nothing turns or grows.
    float kr = 0.0f;
    float ki = 0.0f;
    const float size = std::hypot(lr, li);
    if (size * t < kSeriesLimit) {
        kr = -t + 0.5f * lr * t * t;
        ki = 0.5f * li * t * t;
    } else {
        const float nr = mr - 1.0f;
        const float ni = mi;
        const float norm = size * size;
        kr = (nr * lr + ni * li) / norm;
        ki = (ni * lr - nr * li) / norm;
    }
    const float fx = std::isfinite(flow[0]) ? flow[0] : 0.0f;
    const float fy = std::isfinite(flow[1]) ? flow[1] : 0.0f;
    out.shift = {kr * fx - ki * fy, ki * fx + kr * fy};
    return out;
}

float paintPerFrame(float alpha, float dt) {
    const float t = seconds(dt);
    const float a = unit(alpha);
    if (t == 0.0f || a == 0.0f) return 0.0f;
    if (a == 1.0f) return 1.0f;
    return 1.0f - std::pow(1.0f - a, t);
}

float paintFade(float age) {
    const float t = held(age / kPaintFadeSeconds, 0.0f, 1.0f, 0.0f);
    return t * t * (3.0f - 2.0f * t);
}

Paint borderPaint(const Border& raw, float dt, float age) {
    const Border border = sanitizedBorder(raw);
    Paint out;
    out.color = border.color;
    out.size = border.size;
    if (border.size > 0.0f) out.cover = paintPerFrame(border.alpha, dt) * paintFade(age);
    return out;
}

std::array<float, 2> apply(const Affine& affine, const std::array<float, 2>& p) {
    const auto& m = affine.map;
    return {m[0] * p[0] + m[2] * p[1] + affine.shift[0], m[1] * p[0] + m[3] * p[1] + affine.shift[1]};
}

Step Motion::step(const WarpSpec& raw, const Drive& drive, float dt) {
    const WarpSpec spec = sanitized(raw);
    const float t = seconds(dt);
    const float motion = motionScale(spec, drive);
    Step out;
    out.dt = t;
    out.center = spec.center;
    const float zoom = std::exp(std::log(spec.zoom) * gain(drive.flight) * motion);
    const float rotate = spec.rotate * gain(drive.spin) * motion;
    const float drift = gain(drive.drift) * motion;
    out.source = sourceMap(zoom, rotate, {spec.flow[0] * drift, spec.flow[1] * drift}, t);
    for (size_t i = 0; i < 2; ++i) out.stretch[i] = std::exp(std::log(spec.stretch[i]) * motion * t);
    out.warp = spec.warp * kWarpReach * gain(drive.swirl) * motion * t;
    phase_ = framerate::advancePhase(phase_, kWarpHz * motion, t, kPhaseWrap);
    out.phase = phase_;
    const float kept = perFrame(keptPerSecond(spec.decay, gain(drive.decay)), t);
    for (size_t c = 0; c < 3; ++c) out.keep[c] = kept * perFrame(spec.tint[c], t);
    out.floor = kFloorPerSecond * t;
    age_ = std::min(age_ + t, kPaintFadeSeconds);
    out.outer = borderPaint(spec.outer, t, age_);
    out.inner = borderPaint(spec.inner, t, age_);
    return out;
}

float quantum(float value, Precision precision) {
    if (precision == Precision::Half) {
        const float v = value > kHalfMinNormal ? value : kHalfMinNormal;
        return std::exp2(std::floor(std::log2(v)) - kHalfMantissaBits);
    }
    return 1.0f / (precision == Precision::Unorm10 ? kUnorm10Levels : kUnorm8Levels);
}

float quantize(float value, Precision precision) {
    if (!(value > 0.0f)) return 0.0f;
    if (precision != Precision::Half) value = std::min(value, 1.0f);
    const float q = quantum(value, precision);
    return std::nearbyint(value / q) * q;
}

float settle(float value, float keep, float floor, Precision precision, float noise) {
    const float v = std::isnan(value) ? 0.0f : std::clamp(value, 0.0f, kCeiling);
    const float left = v * keep - floor;
    if (!(left > 0.0f)) return 0.0f;
    return std::max(left + (noise - 0.5f) * quantum(left, precision), 0.0f);
}

}  // namespace geode::viz::feedback
