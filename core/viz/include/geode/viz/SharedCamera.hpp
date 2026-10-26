#pragma once
#include <array>
#include <cstddef>

#include "geode/viz/Normaliser.hpp"
#include "geode/viz/SharedCore.hpp"
#include "geode/viz/StyleExtras.hpp"

namespace geode::viz {

// The Camera (CONTEXT.md, the Shared core's first group) as every Style gets it. The Customization's
// Camera values, evaluated each frame (ReactionGraph, Normaliser), are turned here into one smooth
// pose:
//   - push-in, orbit and travel move a real camera in a Style that raymarches its own world (it reads
//     uCamRot, uCamPush and uCamTravel through lib_camera.glsl and so owns its camera);
//   - for every other Style the composite pass moves the finished picture the nearest 2D way: push-in
//     magnifies, orbit tilts the picture plane in perspective, travel dives through it;
//   - shake is a small, slow, bounded wobble of the whole frame, the same on every Style;
//   - Camera v2 (#330): the field of view, the roll and the speed ramp. In a Style that owns its
//     camera the lens widens or narrows and rolls (lib_camera's cameraView()) and the ramp flies it
//     faster down its own flight; on a flat picture the composite narrows (a picture has no more of
//     itself to show wider), rolls the picture, and the ramp speeds the travel's dive.
// Zoom, rotation, sway, drift and endless zoom keep the paths every Style already draws them on (the
// shader Styles' view(), the composite's geometry block, MilkDrop's own post pass).
//
// Every value glides, at the same rate per second whatever the frame rate, so a Reaction or a Macro
// moving the Camera never jumps the picture (No-flash rule). With every Camera value at its starting
// value the pose is the identity and the picture is exactly the Style's own.
struct CameraInput {
    float pushIn = 0.0f;
    float orbitYaw = 0.0f;
    float orbitPitch = 0.0f;
    float travel = 0.0f;
    float shake = 0.0f;
    float diveSpeed = 0.3f;
    bool endlessZoom = false;
    float fieldOfView = 0.0f;
    float roll = 0.0f;
    float speedRamp = 0.0f;

    // The Camera values of one evaluated frame, in the Shared core's units.
    static CameraInput from(const mod::NativeFrame& frame);
};

struct CameraPose {
    // How far the camera has pushed in, 0..1.
    float push = 0.0f;
    // The orbit about the subject, in radians: a half turn either way, and most of a quarter turn up or down.
    float yaw = 0.0f;
    float pitch = 0.0f;
    // The same orbit as a tilt of a flat picture plane, in radians, kept small enough that the plane
    // always faces the viewer.
    float tiltYaw = 0.0f;
    float tiltPitch = 0.0f;
    // The distance flown along the Style's flight axis, in its world units (wraps at kTravelWrap).
    float travel = 0.0f;
    // 0..1 phases of a dive through a flat picture: from travel, and from endless zoom (for the Styles
    // whose renderer has no endless zoom of its own). 0 is the picture as it is.
    float travelDive = 0.0f;
    float endlessDive = 0.0f;
    // The shake: an offset in fractions of the frame and a roll in radians.
    float shakeX = 0.0f;
    float shakeY = 0.0f;
    float shakeRoll = 0.0f;
    // The lens's width as a power of two (0 is the Style's own framing, above 0 wider), and the
    // camera's roll about the way it looks, in radians (within half a turn of upright).
    float lens = 0.0f;
    float roll = 0.0f;
    // The flight speed the ramp adds, in world units a second (0 at rest).
    float rampSpeed = 0.0f;
    // The orbit as a rotation, column-major (uCamRot): yaw about the vertical, then pitch.
    std::array<float, 9> rotation{1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f};

    // True when the composite has nothing of the Camera to draw.
    bool still() const;
    // The push-in a flat picture is drawn with: the push, magnified further by a narrowed lens (a
    // flat picture cannot show more of itself, so a wider lens leaves it as it is).
    float flatPush() const;
};

namespace camera {
// How fast each Camera value glides to where it is set, per second.
constexpr float kGlideHz = 2.5f;
// The flat tilt a full orbit gives, in radians.
constexpr float kMaxTiltYaw = 0.55f;
constexpr float kMaxTiltPitch = 0.45f;
// The 3D orbit a full setting gives, in radians.
constexpr float kMaxYaw = 3.1415927f;
constexpr float kMaxPitch = 1.35f;
// World units a full travel flies per second, and how many flat dive cycles one unit is.
constexpr float kTravelSpeed = 2.0f;
constexpr float kTravelDivePerUnit = 0.2f;
constexpr float kTravelWrap = 1000.0f;
// Endless zoom's flat dive: cycles per second at a Dive speed of 1.
constexpr float kEndlessDiveHz = 0.5f;
// The largest shake: this fraction of the frame, and this roll. Its wobble is slow (under 3 Hz).
constexpr float kMaxShake = 0.02f;
constexpr float kMaxShakeRoll = 0.02f;
// How much louder hits make the shake, and how fast it follows them up and back down.
constexpr float kShakeHitShare = 0.65f;
constexpr float kShakeRiseHz = 8.0f;
constexpr float kShakeFallHz = 2.5f;
// The lens: a full field of view widens or narrows it by this power of two. It springs open and
// closed at kLensPunchHz and settles back at kLensSettleHz, so a hit punches it out and it eases in.
constexpr float kLensOctaves = 0.6f;
constexpr float kLensPunchHz = 9.0f;
constexpr float kLensSettleHz = 1.8f;
// The roll: the fastest rate (a quarter turn a second) and how fast that rate may change, so the
// roll accelerates into a spin and slows out of one rather than starting or stopping dead.
constexpr float kMaxRollRate = 1.5707964f;
constexpr float kRollAccel = 1.2f;
// With the roll at 0 the camera levels out: back toward upright at this share of its tilt a second,
// never faster than kLevelRate.
constexpr float kLevelHz = 1.5f;
constexpr float kLevelRate = 0.8f;
// The speed ramp: the flight speed a full ramp adds, in world units a second (three times a full
// travel's, the surge of the reference flights over their cruise), how fast it ramps in and how
// slowly it eases off.
constexpr float kRampSpeed = 6.0f;
constexpr float kRampRiseHz = 1.4f;
constexpr float kRampFallHz = 0.6f;
}  // namespace camera

class CameraRig {
public:
    // One frame: `hit` is the live hit envelope (0..1), `dt` the frame's seconds. Reduced motion
    // keeps the orbit and push-in but stills the shake, slows travel and the speed ramp, takes the
    // punch out of the lens (it glides) and holds the roll to a slow turn.
    const CameraPose& step(const CameraInput& in, float hit, float dt, bool reducedMotion);
    const CameraPose& pose() const { return pose_; }
    void reset();

private:
    void stepLens(const CameraInput& in, float t, bool reducedMotion);

    CameraPose pose_;
    float push_ = 0.0f;
    float yaw_ = 0.0f;
    float pitch_ = 0.0f;
    float travelSpeed_ = 0.0f;
    float shake_ = 0.0f;
    float lens_ = 0.0f;
    float rollRate_ = 0.0f;
    float ramp_ = 0.0f;
    std::array<float, 4> wobble_{};
};

}  // namespace geode::viz
