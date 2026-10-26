#include "geode/viz/GravityField.hpp"

#include <algorithm>
#include <cmath>

namespace geode::viz {

namespace {
using Vec3 = std::array<float, 3>;

float lengthOf(const Vec3& v) { return std::sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); }

// Moves `current` the share `k` of the way to `target`, but never further than `maxStep`. A share of
// the gap is a convex step, so the stage can never pass its target: the cascade does not ring.
void approach(Vec3& current, const Vec3& target, float k, float maxStep) {
    Vec3 d{(target[0] - current[0]) * k, (target[1] - current[1]) * k, (target[2] - current[2]) * k};
    const float len = lengthOf(d);
    if (len > maxStep) {
        const float s = maxStep / len;
        for (float& c : d) c *= s;
    }
    for (size_t i = 0; i < d.size(); ++i) current[i] += d[i];
}
}  // namespace

void GravityField::submit(float x, float y, float z) {
    const bool finite = std::isfinite(x) && std::isfinite(y) && std::isfinite(z);
    const float len = finite ? std::sqrt(x * x + y * y + z * z) : 0.0f;
    const bool has = std::isfinite(len) && len >= kMinReading;
    std::lock_guard<std::mutex> guard(lock_);
    inboxHas_ = has;
    inbox_ = has ? Vec3{x / len, y / len, z / len} : Vec3{};
    inboxStrength_ = has ? std::min(len, 1.0f) : 0.0f;
    inboxDirty_ = true;
}

void GravityField::reset() {
    {
        std::lock_guard<std::mutex> guard(lock_);
        inbox_ = {};
        inboxStrength_ = 0.0f;
        inboxHas_ = false;
        inboxDirty_ = false;
    }
    target_ = {};
    strength_ = 0.0f;
    live_ = false;
    reseed_ = false;
    zeroAll();
}

void GravityField::zeroAll() {
    seeded_ = false;
    near_ = {};
    dir_ = {};
    rest_ = {};
    engageNear_ = 0.0f;
    engage_ = 0.0f;
    gravity_ = {};
    tilt_ = {};
}

bool GravityField::idle() const {
    return gravity_[0] == 0.0f && gravity_[1] == 0.0f && gravity_[2] == 0.0f && tilt_[0] == 0.0f && tilt_[1] == 0.0f;
}

void GravityField::adopt(const Vec3& reading, float strength, bool has) {
    if (has && live_) {
        const float cosine = reading[0] * target_[0] + reading[1] * target_[1] + reading[2] * target_[2];
        if (cosine < kReseedCosine) reseed_ = true;
    }
    live_ = has;
    strength_ = has ? strength : 0.0f;
    if (has) target_ = reading;
}

void GravityField::step(float dt, bool reducedMotion) {
    const float t = std::isfinite(dt) ? std::clamp(dt, 0.0f, 0.1f) : 0.0f;
    Vec3 reading{};
    float strength = 0.0f;
    bool has = false;
    bool fresh = false;
    {
        std::lock_guard<std::mutex> guard(lock_);
        if (inboxDirty_) {
            inboxDirty_ = false;
            reading = inbox_;
            strength = inboxStrength_;
            has = inboxHas_;
            fresh = true;
        }
    }
    if (fresh) adopt(reading, strength, has);

    const float want = live_ && !reducedMotion ? strength_ : 0.0f;
    const float ke = 1.0f - std::exp(-t / kEngageTauSeconds);
    engageNear_ += (want - engageNear_) * ke;
    engage_ += (engageNear_ - engage_) * ke;
    if (want == 0.0f && engageNear_ < kSpent && engage_ < kSpent) {
        zeroAll();
        return;
    }

    if (live_ && (!seeded_ || reseed_)) {
        // The first reading of an engagement sets the direction outright. The field is still at no
        // strength, so nothing on screen moves; it then grows in along the right direction instead of
        // swinging round from wherever the last engagement ended, and with no tilt to begin with.
        near_ = target_;
        dir_ = target_;
        rest_ = target_;
        seeded_ = true;
        reseed_ = false;
    }
    if (live_) {
        const float kf = 1.0f - std::exp(-t / kFollowTauSeconds);
        const float maxStep = kMaxSpeed * t;
        approach(near_, target_, kf, maxStep);
        approach(dir_, near_, kf, maxStep);
    }
    const float kr = 1.0f - std::exp(-t / kRestTauSeconds);
    for (size_t i = 0; i < rest_.size(); ++i) rest_[i] += (dir_[i] - rest_[i]) * kr;

    for (size_t i = 0; i < gravity_.size(); ++i) gravity_[i] = dir_[i] * engage_;
    float tx = (dir_[0] - rest_[0]) / kTiltRange;
    float ty = (dir_[1] - rest_[1]) / kTiltRange;
    const float len = std::sqrt(tx * tx + ty * ty);
    if (len > kMaxTilt) {
        tx *= kMaxTilt / len;
        ty *= kMaxTilt / len;
    }
    tilt_ = {tx * engage_, ty * engage_};
}

namespace gravity {

std::array<float, 9> lookRotation(float tiltX, float tiltY) {
    const float yaw = std::isfinite(tiltX) ? tiltX * kLookRadians : 0.0f;
    const float pitch = std::isfinite(tiltY) ? tiltY * kLookRadians : 0.0f;
    if (yaw == 0.0f && pitch == 0.0f) return {1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f};
    // Yaw about y, then pitch about x: R = Ry * Rx, stored column by column (as CameraRig does).
    const float cy = std::cos(yaw), sy = std::sin(yaw);
    const float cx = std::cos(pitch), sx = std::sin(pitch);
    return {cy, 0.0f, -sy, sy * sx, cx, cy * sx, sy * cx, -sx, cy * cx};
}

void inPlaneDown(float x, float y, float weight, float* out) {
    out[0] = 0.0f;
    out[1] = 0.0f;
    if (!(weight > 0.0f) || !std::isfinite(x) || !std::isfinite(y)) return;
    const float m = std::sqrt(x * x + y * y);
    const float u = std::clamp((m - kFlatIgnored) / (kFlatTrusted - kFlatIgnored), 0.0f, 1.0f);
    const float trust = u * u * (3.0f - 2.0f * u);
    if (trust <= 0.0f) return;
    const float w = std::min(weight, 1.0f) * trust / m;
    out[0] = x * w;
    out[1] = y * w;
}

std::array<float, 9> multiply(const std::array<float, 9>& a, const std::array<float, 9>& b) {
    std::array<float, 9> out{};
    for (size_t col = 0; col < 3; ++col) {
        for (size_t row = 0; row < 3; ++row) {
            float sum = 0.0f;
            for (size_t k = 0; k < 3; ++k) sum += a[k * 3 + row] * b[col * 3 + k];
            out[col * 3 + row] = sum;
        }
    }
    return out;
}

}  // namespace gravity

}  // namespace geode::viz
