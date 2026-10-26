#include "geode/viz/SharedCamera.hpp"

#include <algorithm>
#include <cmath>

namespace geode::viz {

namespace {
constexpr float kTwoPi = 6.2831853f;
constexpr float kStill = 1e-4f;
constexpr float kReducedTravel = 0.3f;
// Reduced motion: the lens moves half as far, and the roll turns no faster than this share of its rate.
constexpr float kReducedLens = 0.5f;
constexpr float kReducedRoll = 0.25f;
// The wobble's four slow oscillators (Hz): incommensurate, so the shake never settles into a loop.
constexpr std::array<float, 4> kWobbleHz = {1.7f, 2.9f, 1.3f, 2.3f};
constexpr float kWobbleMix = 0.6f;

float finiteOr(float value, float fallback) { return std::isfinite(value) ? value : fallback; }

// One-pole toward `target`: the same share of the gap per second whatever the frame rate.
float glide(float current, float target, float dt, float hz) {
    return current + (target - current) * (1.0f - std::exp(-dt * hz));
}

// A 0..1 phase, advanced by `rate` cycles per second; with no rate it settles on the nearest whole
// cycle, where the dive draws the picture as it is.
float divePhase(float phase, float rate, float dt) {
    if (std::fabs(rate) > kStill) {
        const float next = std::fmod(phase + rate * dt, 1.0f);
        return next < 0.0f ? next + 1.0f : next;
    }
    const float settled = glide(phase, std::round(phase), dt, camera::kGlideHz);
    return std::fabs(settled - std::round(settled)) < kStill ? 0.0f : settled;
}

float wrapPhase(float phase, float hz, float dt) { return std::fmod(phase + hz * dt, 1.0f); }

// `current` moved toward `target` by at most `maxStep`.
float approach(float current, float target, float maxStep) {
    return current + std::clamp(target - current, -maxStep, maxStep);
}

// An angle as the same turn within half a turn either way of upright, so it never grows past float
// precision and its distance from upright is its size.
float wrapAngle(float angle) { return angle - kTwoPi * std::round(angle / kTwoPi); }
}  // namespace

CameraInput CameraInput::from(const mod::NativeFrame& f) {
    CameraInput in;
    in.pushIn = std::clamp(finiteOr(f[mod::Shared::PushIn], 0.0f), 0.0f, 1.0f);
    in.orbitYaw = std::clamp(finiteOr(f[mod::Shared::OrbitYaw], 0.0f), -1.0f, 1.0f);
    in.orbitPitch = std::clamp(finiteOr(f[mod::Shared::OrbitPitch], 0.0f), -1.0f, 1.0f);
    in.travel = std::clamp(finiteOr(f[mod::Shared::Travel], 0.0f), -1.0f, 1.0f);
    in.shake = std::clamp(finiteOr(f[mod::Shared::Shake], 0.0f), 0.0f, 1.0f);
    in.diveSpeed = std::max(finiteOr(f[mod::Shared::DiveSpeed], 0.0f), 0.0f);
    in.endlessZoom = f[mod::Shared::EndlessZoom] >= 0.5f;
    in.fieldOfView = std::clamp(finiteOr(f[mod::Shared::FieldOfView], 0.0f), -1.0f, 1.0f);
    in.roll = std::clamp(finiteOr(f[mod::Shared::Roll], 0.0f), -1.0f, 1.0f);
    in.speedRamp = std::clamp(finiteOr(f[mod::Shared::SpeedRamp], 0.0f), 0.0f, 1.0f);
    return in;
}

bool CameraPose::still() const {
    return push < kStill && std::fabs(tiltYaw) < kStill && std::fabs(tiltPitch) < kStill && travelDive < kStill &&
           endlessDive < kStill && std::fabs(shakeX) < kStill && std::fabs(shakeY) < kStill &&
           std::fabs(shakeRoll) < kStill && lens > -kStill && std::fabs(roll) < kStill;
}

float CameraPose::flatPush() const {
    // c / (1 + 0.6 push) narrowed by 2^lens is c / (1 + 0.6 flatPush), as composite_frag draws it.
    const float narrow = std::exp2(std::max(-lens, 0.0f));
    return ((1.0f + 0.6f * push) * narrow - 1.0f) / 0.6f;
}

const CameraPose& CameraRig::step(const CameraInput& in, float hit, float dt, bool reducedMotion) {
    const float t = std::isfinite(dt) ? std::clamp(dt, 0.0f, 0.1f) : 0.0f;
    push_ = glide(push_, in.pushIn, t, camera::kGlideHz);
    yaw_ = glide(yaw_, in.orbitYaw, t, camera::kGlideHz);
    pitch_ = glide(pitch_, in.orbitPitch, t, camera::kGlideHz);
    const float travel = in.travel * camera::kTravelSpeed * (reducedMotion ? kReducedTravel : 1.0f);
    travelSpeed_ = glide(travelSpeed_, travel, t, camera::kGlideHz);
    stepLens(in, t, reducedMotion);
    const float loud = std::clamp(finiteOr(hit, 0.0f), 0.0f, 1.0f);
    const float shakeTarget =
        reducedMotion ? 0.0f : in.shake * ((1.0f - camera::kShakeHitShare) + camera::kShakeHitShare * loud);
    shake_ = glide(shake_, shakeTarget, t, shakeTarget > shake_ ? camera::kShakeRiseHz : camera::kShakeFallHz);
    for (size_t i = 0; i < wobble_.size(); ++i) wobble_[i] = wrapPhase(wobble_[i], kWobbleHz[i], t);

    CameraPose& p = pose_;
    p.push = push_ < kStill ? 0.0f : push_;
    p.yaw = yaw_ * camera::kMaxYaw;
    p.pitch = pitch_ * camera::kMaxPitch;
    p.tiltYaw = std::fabs(yaw_) < kStill ? 0.0f : yaw_ * camera::kMaxTiltYaw;
    p.tiltPitch = std::fabs(pitch_) < kStill ? 0.0f : pitch_ * camera::kMaxTiltPitch;
    p.rampSpeed = ramp_ < kStill ? 0.0f : ramp_;
    const float flight = travelSpeed_ + p.rampSpeed;
    p.travel = std::fmod(p.travel + flight * t, camera::kTravelWrap);
    p.travelDive = divePhase(p.travelDive, flight * camera::kTravelDivePerUnit, t);
    p.lens = std::fabs(lens_) < kStill ? 0.0f : lens_ * camera::kLensOctaves;
    p.roll = wrapAngle(p.roll + rollRate_ * t);
    if (std::fabs(p.roll) < kStill && std::fabs(rollRate_) < kStill) {
        p.roll = 0.0f;
        rollRate_ = 0.0f;
    }
    p.endlessDive = divePhase(p.endlessDive, in.endlessZoom ? in.diveSpeed * camera::kEndlessDiveHz : 0.0f, t);
    const float amount = shake_ < kStill ? 0.0f : shake_;
    const auto wave = [&](size_t i) { return std::sin(kTwoPi * wobble_[i]); };
    p.shakeX = amount * camera::kMaxShake * (kWobbleMix * wave(0) + (1.0f - kWobbleMix) * wave(1));
    p.shakeY = amount * camera::kMaxShake * (kWobbleMix * wave(2) + (1.0f - kWobbleMix) * wave(3));
    p.shakeRoll = amount * camera::kMaxShakeRoll * wave(3);

    // Yaw about y, then pitch about x: R = Ry * Rx, stored column by column.
    const float cy = std::cos(p.yaw), sy = std::sin(p.yaw);
    const float cx = std::cos(p.pitch), sx = std::sin(p.pitch);
    p.rotation = {cy, 0.0f, -sy, sy * sx, cx, cy * sx, sy * cx, -sx, cy * cx};
    return p;
}

// The lens, the roll's rate and the ramp: each moves smoothly toward its setting, the lens fast out
// and slow back (a punch), the roll's rate no faster than kRollAccel, the ramp in faster than it eases off.
void CameraRig::stepLens(const CameraInput& in, float t, bool reducedMotion) {
    const float lens = in.fieldOfView * (reducedMotion ? kReducedLens : 1.0f);
    const bool opening = std::fabs(lens) > std::fabs(lens_);
    const float lensHz = reducedMotion ? camera::kGlideHz : (opening ? camera::kLensPunchHz : camera::kLensSettleHz);
    lens_ = glide(lens_, lens, t, lensHz);
    float rate = in.roll * camera::kMaxRollRate * (reducedMotion ? kReducedRoll : 1.0f);
    // With no roll set, the camera rolls back upright the shortest way and comes to rest there.
    if (std::fabs(rate) < kStill) {
        rate = std::clamp(-pose_.roll * camera::kLevelHz, -camera::kLevelRate, camera::kLevelRate);
    }
    rollRate_ = approach(rollRate_, rate, camera::kRollAccel * t);
    const float ramp = in.speedRamp * camera::kRampSpeed * (reducedMotion ? kReducedTravel : 1.0f);
    ramp_ = glide(ramp_, ramp, t, ramp > ramp_ ? camera::kRampRiseHz : camera::kRampFallHz);
}

void CameraRig::reset() { *this = CameraRig{}; }

}  // namespace geode::viz
