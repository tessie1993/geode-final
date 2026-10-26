#pragma once

namespace geode::viz::framerate {

// The same look at 30, 60 and 120 Hz (#280). Anything that moves by a fixed amount per frame runs
// twice as fast on a 120 Hz panel as on a 60 Hz one, and a decay applied per frame fades twice as
// fast. These are the forms that turn such steps into rates per second. Every value tuned "per frame"
// in Geode was tuned at kReferenceFps, so a frame of dt counts as dt x kReferenceFps of those frames.

constexpr float kReferenceFps = 60.0f;

// How many reference frames a frame of `dt` seconds stands for; 0 for a stalled or broken frame.
float referenceFrames(float dt);

// What a factor applied once per reference frame (a decay, a zoom) comes to over a frame of `dt`:
// perFrame ^ referenceFrames(dt). 1 for a stalled frame; 0 when the factor itself is not positive.
float perFrameFactor(float perFrame, float dt);

// `phase` advanced by `rate` per second over `dt` and wrapped into [0, period). A phase whose rate
// follows loudness speeds up and slows down but never jumps, where `time x rate` would teleport the
// picture every time the rate changed. A non-positive period leaves the phase unwrapped.
float advancePhase(float phase, float rate, float dt, float period);

// Runs a discrete simulation (a cellular field, an agent colony) a fixed number of steps per second
// whatever the frame rate: each frame asks how many whole steps its dt has earned. At 120 Hz a 60
// steps-per-second clock steps every other frame, at 30 Hz twice a frame. A frame never runs more
// than `maxStepsPerFrame`; a stall beyond that is dropped rather than caught up, so one long frame
// cannot make the next ones spiral.
class FixedStepClock {
public:
    FixedStepClock(float stepsPerSecond, int maxStepsPerFrame);

    // Advances by dt and returns how many steps to run this frame.
    int advance(float dt);
    // The share of the next step already earned, in [0, 1).
    float pending() const { return pending_; }
    void reset() { pending_ = kStartPending; }

    // A fresh clock starts half a step in, so frames at exactly the step rate sit in the middle of a
    // step rather than on its edge, where the jitter of real vsync timestamps would turn one step a
    // frame into a stutter of none and two.
    static constexpr float kStartPending = 0.5f;

private:
    float stepsPerSecond_;
    int maxStepsPerFrame_;
    float pending_ = kStartPending;
};

}  // namespace geode::viz::framerate
