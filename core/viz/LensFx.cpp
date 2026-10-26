#include "geode/viz/LensFx.hpp"

#include <algorithm>
#include <cmath>

namespace geode::viz::lens {

namespace {

constexpr int kHottestTier = 3;

float held(float value, float lo, float hi, float fallback) {
    return std::isfinite(value) ? std::clamp(value, lo, hi) : fallback;
}

float unit(float value, float fallback) { return held(value, 0.0f, 1.0f, fallback); }

int tierIndex(int tier) { return tier < 0 || tier > kHottestTier ? kHottestTier : tier; }

int samplesAt(const std::array<int, 4>& table, int tier, bool optionalPasses) {
    return optionalPasses ? table[static_cast<size_t>(tierIndex(tier))] : 0;
}

}  // namespace

LensSpec sanitized(const LensSpec& spec) {
    const LensSpec d;
    LensSpec s = spec;
    s.threshold = held(spec.threshold, kMinThreshold, kMaxThreshold, d.threshold);
    s.flare.amount = unit(spec.flare.amount, 0.0f);
    s.flare.ghosts = std::clamp(spec.flare.ghosts, kMinGhosts, kMaxGhosts);
    s.flare.halo = unit(spec.flare.halo, d.flare.halo);
    s.flare.dispersion = unit(spec.flare.dispersion, d.flare.dispersion);
    s.godRays.amount = unit(spec.godRays.amount, 0.0f);
    s.godRays.x = unit(spec.godRays.x, d.godRays.x);
    s.godRays.y = unit(spec.godRays.y, d.godRays.y);
    s.godRays.length = held(spec.godRays.length, kMinRayLength, kMaxRayLength, d.godRays.length);
    s.godRays.decay = held(spec.godRays.decay, kMinRayDecay, kMaxRayDecay, d.godRays.decay);
    s.streaks.amount = unit(spec.streaks.amount, 0.0f);
    s.streaks.spokes = std::clamp(spec.streaks.spokes, kMinSpokes, kMaxSpokes);
    s.streaks.length = held(spec.streaks.length, kMinStreakLength, kMaxStreakLength, d.streaks.length);
    s.depthOfField.aperture = unit(spec.depthOfField.aperture, 0.0f);
    s.depthOfField.focus = held(spec.depthOfField.focus, kMinFocus, kMaxFocus, d.depthOfField.focus);
    return s;
}

ParticleSpec sanitized(const ParticleSpec& spec) {
    const ParticleSpec d;
    ParticleSpec s = spec;
    s.amount = unit(spec.amount, 0.0f);
    s.count = held(spec.count, kMinParticleCount, kMaxParticleCount, d.count);
    s.speed = held(spec.speed, kMinParticleSpeed, kMaxParticleSpeed, d.speed);
    s.size = held(spec.size, kMinParticleSize, kMaxParticleSize, d.size);
    for (size_t i = 0; i < s.tint.size(); ++i) s.tint[i] = held(spec.tint[i], 0.0f, kMaxTint, d.tint[i]);
    return s;
}

int raySamples(int tier, bool optionalPasses) { return samplesAt(kRaySamples, tier, optionalPasses); }
int streakSamples(int tier, bool optionalPasses) { return samplesAt(kStreakSamples, tier, optionalPasses); }
int dofSamples(int tier, bool optionalPasses) { return samplesAt(kDofSamples, tier, optionalPasses); }

float chainThreshold(float bloomThreshold, const Glare& glare) {
    return glare.on() ? std::min(bloomThreshold, glare.threshold) : bloomThreshold;
}

float bloomShare(float chainLuma, float bloomLuma) {
    if (!(chainLuma > kShareEpsilon) || !std::isfinite(bloomLuma)) return 0.0f;
    return std::clamp(bloomLuma / chainLuma, 0.0f, 1.0f);
}

float circleOfConfusion(float depth, float focus, float aperture) {
    const float d = held(depth, kMinDepth, 1.0f, 1.0f);
    const float f = held(focus, kMinFocus, kMaxFocus, 0.5f);
    const float a = unit(aperture, 0.0f);
    return std::clamp(a * std::fabs(1.0f / d - 1.0f / f) * f, 0.0f, 1.0f);
}

float depthAlpha(float t, float scale) {
    if (!(scale > 0.0f) || !std::isfinite(t)) return 1.0f;
    const float distance = std::max(t, 0.0f);
    return distance / (distance + scale);
}

Dof dofFor(const LensSpec& spec, int tier, bool optionalPasses) {
    Dof dof;
    dof.aperture = unit(spec.depthOfField.aperture, 0.0f);
    dof.focus = held(spec.depthOfField.focus, kMinFocus, kMaxFocus, 0.5f);
    dof.samples = dof.aperture > 0.0f ? dofSamples(tier, optionalPasses) : 0;
    return dof;
}

float approach(float current, float target, float step) {
    if (!(step > 0.0f) || !std::isfinite(step)) return current;
    if (current < target) return std::min(current + step, target);
    return std::max(current - step, target);
}

float glide(float current, float target, float riseSeconds, float fallSeconds, float dt) {
    if (!(dt > 0.0f) || !std::isfinite(dt) || !std::isfinite(target)) return current;
    const float seconds = target > current ? riseSeconds : fallSeconds;
    if (!(seconds > 0.0f)) return target;
    return current + (target - current) * (1.0f - std::exp(-dt / seconds));
}

Glare LensDrive::step(const LensSpec& declared, const Drive& drive, int tier, bool optionalPasses, bool reducedMotion,
                      float dt) {
    const LensSpec spec = sanitized(declared);
    Glare out;
    out.raySamples = raySamples(tier, optionalPasses);
    out.streakSamples = streakSamples(tier, optionalPasses);
    const bool allowed = out.raySamples > 0;
    const float fade = std::isfinite(dt) && dt > 0.0f ? dt / kStyleFadeSeconds : 0.0f;
    swell_ = glide(swell_, unit(drive.level, 0.0f), kSwellRiseSeconds, kSwellFallSeconds, dt);
    flare_ = approach(flare_, allowed ? spec.flare.amount : 0.0f, fade);
    rays_ = approach(rays_, allowed ? spec.godRays.amount : 0.0f, fade);
    streaks_ = approach(streaks_, allowed ? spec.streaks.amount : 0.0f, fade);
    const float swing = kGlareRest + kGlareSwing * swell_;
    out.threshold = spec.threshold;
    out.flare = flare_ * swing;
    out.ghosts = spec.flare.ghosts;
    out.halo = spec.flare.halo;
    out.dispersion = spec.flare.dispersion;
    out.rays = rays_ * swing;
    out.rayX = spec.godRays.x;
    out.rayY = spec.godRays.y;
    out.rayLength = spec.godRays.length;
    out.rayDecay = spec.godRays.decay;
    out.streaks = streaks_ * swing;
    out.streakShape = spec.streaks.shape;
    out.spokes = spec.streaks.spokes;
    out.streakLength = spec.streaks.length * swing * (reducedMotion ? kReducedMotionStreak : 1.0f);
    return out;
}

void LensDrive::reset() {
    swell_ = 0.0f;
    flare_ = 0.0f;
    rays_ = 0.0f;
    streaks_ = 0.0f;
}

int particleCount(float count, int tier) {
    const float multiplier = held(count, kMinParticleCount, kMaxParticleCount, 1.0f);
    const float share = kParticleTierShare[static_cast<size_t>(tierIndex(tier))];
    const int n = static_cast<int>(std::lround(static_cast<float>(kBaseParticles) * multiplier * share));
    return std::clamp(n, 1, kMaxParticles);
}

Particles ParticleFlight::step(const ParticleSpec& declared, const Drive& drive, int tier, bool reducedMotion,
                               float dt) {
    const ParticleSpec spec = sanitized(declared);
    const bool moving = std::isfinite(dt) && dt > 0.0f;
    const float lift = std::max(unit(drive.level, 0.0f), kHitShare * unit(drive.hit, 0.0f));
    swell_ = glide(swell_, lift, kParticleRiseSeconds, kParticleFallSeconds, dt);
    amount_ = approach(amount_, spec.amount, moving ? dt / kStyleFadeSeconds : 0.0f);
    speed_ =
        spec.speed * kFlightSpeed * (kSpeedRest + kSpeedSwing * swell_) * (reducedMotion ? kReducedMotionSpeed : 1.0f);
    // The phase is integrated, never the clock scaled by loudness: a louder bar flies faster, it
    // never jumps the field.
    if (moving) phase_ = std::fmod(phase_ + speed_ * dt, kPhaseWrap);
    Particles out;
    out.kind = spec.kind;
    out.count = amount_ > 0.0f ? particleCount(spec.count, tier) : 0;
    out.emission = amount_ * (kEmissionRest + (1.0f - kEmissionRest) * swell_);
    out.phase = phase_;
    out.tail = speed_ * (spec.kind == ParticleKind::Sparks ? kSparkShutter : kStreakShutter);
    out.size = spec.size;
    out.tint = spec.tint;
    return out;
}

void ParticleFlight::reset() {
    swell_ = 0.0f;
    amount_ = 0.0f;
    phase_ = 0.0f;
    speed_ = 0.0f;
}

}  // namespace geode::viz::lens
