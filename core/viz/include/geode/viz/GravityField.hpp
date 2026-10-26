#pragma once
#include <array>
#include <mutex>

namespace geode::viz {

// Which way is down, beside TouchField, in one place every Style family can read (#315).
//
// The app's GravitySource hands over the latest reading from the UI thread, latest-wins (a picture
// wants where down IS, not every place it passed through); the GL thread steps the field once per
// frame and every scene reads the same values:
//
// - gravity: the down direction in the Visual's screen space (x right, y up, z out of the screen),
//   scaled by the weight. Upright in portrait it is (0, -1, 0); flat on a table, face up, (0, 0, -1).
// - tilt: how far the phone has been turned away from the pose it has been resting in, in screen
//   x and y, -1..1 per axis (1 is kTiltRange away), scaled by the weight. It settles back to 0 while
//   the phone is held still, so a Style's camera moves while the person tilts and then comes home,
//   wherever they hold the phone.
// - weight: 0..1, how engaged the field is times the person's Tilt setting (Subtle is half, Full
//   all). Every helper in lib_gravity.glsl is the identity while it is 0.
//
// The reading's LENGTH carries the setting: a unit reading is Full, half a unit Subtle, and nothing
// (or too short) is none.
//
// Nothing here ever jumps the picture (No-flash rule). The direction follows the reading through two
// cascaded one-pole filters (a critically damped response: it never overshoots or rings, and each
// stage moves at most kMaxSpeed per second); engaging and letting go ease the whole field in and out
// with the same kind of filter, so a sensor that starts or stops - the setting, the screen, reduce
// motion - fades the field rather than snapping it. All of it is per second, whatever the frame rate.
//
// With no reading - no sensor, the setting off, an emulator, a Render - every value is EXACTLY 0,
// never a made-up vector, so a Style that reads the field draws what it drew before the field existed.
class GravityField {
public:
    // Each of the two cascaded stages the direction follows the reading through.
    static constexpr float kFollowTauSeconds = 0.12f;
    // Each of the two stages that ease the field in when a reading arrives and out when it stops.
    static constexpr float kEngageTauSeconds = 0.3f;
    // How slowly the resting pose follows the phone: the time a held tilt takes to settle.
    static constexpr float kRestTauSeconds = 2.5f;
    // The most either direction stage may move per second, so even a phone flipped over turns the
    // field in a visible glide rather than a jump.
    static constexpr float kMaxSpeed = 3.0f;
    // How far (as a distance between unit down vectors, about 30 degrees) is a tilt of 1.
    static constexpr float kTiltRange = 0.5f;
    // The longest tilt the field reports.
    static constexpr float kMaxTilt = 1.0f;
    // Two readings further apart than this (a cosine) cannot be a hand turning a phone in one sensor
    // period: the display rotated under it. The direction and the resting pose start again there.
    static constexpr float kReseedCosine = 0.5f;
    // Below this engagement a released field is spent and everything returns to exact zero.
    static constexpr float kSpent = 1e-3f;
    // A reading shorter than this has no direction and counts as none.
    static constexpr float kMinReading = 1e-3f;

    // Any thread: the latest reading of down in screen space; its length (clamped to 1) is the
    // weight to engage at. Zero, too short or not finite says there is no reading any more.
    void submit(float x, float y, float z);
    // GL thread: drop everything at once, for a surface loss.
    void reset();
    // GL thread: one frame. Reduce motion holds the field at level: it eases out and stays at zero.
    void step(float dt, bool reducedMotion);

    float x() const { return gravity_[0]; }
    float y() const { return gravity_[1]; }
    float z() const { return gravity_[2]; }
    float tiltX() const { return tilt_[0]; }
    float tiltY() const { return tilt_[1]; }
    // How engaged the field is times the setting, 0..1: uTiltWeight.
    float engagement() const { return engage_; }
    float weight() const { return engage_; }
    // True when every published value is exactly zero.
    bool idle() const;

private:
    using Vec3 = std::array<float, 3>;

    void adopt(const Vec3& reading, float strength, bool has);
    void zeroAll();

    std::mutex lock_;
    Vec3 inbox_{};
    float inboxStrength_ = 0.0f;
    bool inboxHas_ = false;
    bool inboxDirty_ = false;

    Vec3 target_{};
    float strength_ = 0.0f;
    bool live_ = false;
    bool reseed_ = false;
    bool seeded_ = false;
    Vec3 near_{};
    Vec3 dir_{};
    Vec3 rest_{};
    float engageNear_ = 0.0f;
    float engage_ = 0.0f;
    Vec3 gravity_{};
    std::array<float, 2> tilt_{};
};

namespace gravity {
// How far a tilt of 1 turns a Style's camera, in radians: 3 degrees, and never a roll.
constexpr float kLookRadians = 0.052f;
// Within this sine of flat (about 5 degrees) the in-plane down means nothing; by kFlatTrusted (about
// 12 degrees) it is trusted fully.
constexpr float kFlatIgnored = 0.08f;
constexpr float kFlatTrusted = 0.2f;

// The in-plane down a flat (2D) family pushes along: the unit direction of (x, y), faded out near
// flat where it is badly conditioned, times `weight`, into out[0..1]. Exactly 0 when weight is 0.
void inPlaneDown(float x, float y, float weight, float* out);

// The small look rotation a tilt gives a Style that moves a camera of its own: yaw about its up axis
// by tiltX, then pitch about its right axis by tiltY, column-major like CameraPose::rotation. Turning
// the phone's right edge down looks right; tipping its top away looks down. The identity, exactly,
// for no tilt.
std::array<float, 9> lookRotation(float tiltX, float tiltY);
// a * b, both column-major 3x3.
std::array<float, 9> multiply(const std::array<float, 9>& a, const std::array<float, 9>& b);
}  // namespace gravity

}  // namespace geode::viz
