#include <gtest/gtest.h>

#include <array>
#include <cmath>
#include <cstdint>
#include <limits>

#include "geode/viz/FeedbackWarp.hpp"

namespace feedback = geode::viz::feedback;
using feedback::Precision;

namespace {

constexpr float kNan = std::numeric_limits<float>::quiet_NaN();
constexpr float kPi = 3.14159265f;
constexpr std::array<Precision, 3> kPrecisions = {Precision::Half, Precision::Unorm10, Precision::Unorm8};

// A point carried through `frames` frames of `dt`, each frame's map applied to the last one's result:
// where the picture at `p` reads from after that many frames.
std::array<float, 2> carried(const feedback::Affine& frame, std::array<float, 2> p, int frames) {
    for (int i = 0; i < frames; ++i) p = feedback::apply(frame, p);
    return p;
}

float distance(const std::array<float, 2>& a, const std::array<float, 2>& b) {
    return std::hypot(a[0] - b[0], a[1] - b[1]);
}

// One second of Motion::step at `fps`, summing or multiplying what each frame hands the warp.
struct Second {
    std::array<float, 3> keep{1.0f, 1.0f, 1.0f};
    std::array<float, 2> stretch{1.0f, 1.0f};
    float warp = 0.0f;
    float floor = 0.0f;
    float phase = 0.0f;
    feedback::Affine last;
};

Second runSecond(const feedback::WarpSpec& spec, const feedback::Drive& drive, int fps) {
    feedback::Motion motion;
    Second out;
    const float dt = 1.0f / static_cast<float>(fps);
    for (int i = 0; i < fps; ++i) {
        const feedback::Step step = motion.step(spec, drive, dt);
        for (size_t c = 0; c < 3; ++c) out.keep[c] *= step.keep[c];
        for (size_t a = 0; a < 2; ++a) out.stretch[a] *= step.stretch[a];
        out.warp += step.warp;
        out.floor += step.floor;
        out.last = step.source;
    }
    out.phase = motion.phase();
    return out;
}

// A small deterministic noise stream, uniform in [0, 1).
class Noise {
public:
    explicit Noise(std::uint32_t seed) : state_(seed * 2654435761u + 1u) {}
    float next() {
        state_ ^= state_ << 13;
        state_ ^= state_ >> 17;
        state_ ^= state_ << 5;
        return static_cast<float>(state_ >> 8) / 16777216.0f;
    }

private:
    std::uint32_t state_;
};

// The slow decay the stall shows at: 95% kept a second, on a 120 Hz panel.
constexpr int kFps = 120;
constexpr float kSlowDecay = 0.95f;

float slowKeep() { return feedback::perFrame(kSlowDecay, 1.0f / kFps); }

// What one channel stored after `frames` frames of the warp's store, rounded by the GPU each frame.
float stored(float start, int frames, float floor, Precision precision, bool dithered, std::uint32_t seed) {
    Noise noise(seed);
    float v = feedback::quantize(start, precision);
    for (int i = 0; i < frames; ++i)
        v = feedback::quantize(feedback::settle(v, slowKeep(), floor, precision, dithered ? noise.next() : 0.5f),
                               precision);
    return v;
}

}  // namespace

// ---- the spec ------------------------------------------------------------------------------------

TEST(FeedbackSpec, HoldsEveryValueToItsRange) {
    feedback::WarpSpec wild;
    wild.on = true;
    wild.decay = 2.0f;
    wild.zoom = 9.0f;
    wild.rotate = -10.0f;
    wild.flow = {3.0f, -3.0f};
    wild.stretch = {0.1f, 5.0f};
    wild.warp = 4.0f;
    wild.tint = {-1.0f, 0.5f, 2.0f};
    wild.center = {-1.0f, 2.0f};
    wild.react = 7.0f;
    const feedback::WarpSpec held = feedback::sanitized(wild);
    EXPECT_TRUE(held.on);
    EXPECT_FLOAT_EQ(held.decay, feedback::kMaxDecay);
    EXPECT_FLOAT_EQ(held.zoom, feedback::kMaxZoom);
    EXPECT_FLOAT_EQ(held.rotate, -feedback::kMaxRotate);
    EXPECT_FLOAT_EQ(held.flow[0], feedback::kMaxFlow);
    EXPECT_FLOAT_EQ(held.flow[1], -feedback::kMaxFlow);
    EXPECT_FLOAT_EQ(held.stretch[0], feedback::kMinStretch);
    EXPECT_FLOAT_EQ(held.stretch[1], feedback::kMaxStretch);
    EXPECT_FLOAT_EQ(held.warp, feedback::kMaxWarp);
    EXPECT_FLOAT_EQ(held.tint[0], 0.0f);
    EXPECT_FLOAT_EQ(held.tint[1], 0.5f);
    EXPECT_FLOAT_EQ(held.tint[2], 1.0f);
    EXPECT_FLOAT_EQ(held.center[0], 0.0f);
    EXPECT_FLOAT_EQ(held.center[1], 1.0f);
    EXPECT_FLOAT_EQ(held.react, feedback::kMaxReact);

    wild.decay = 0.0f;
    wild.zoom = 0.1f;
    wild.rotate = 10.0f;
    wild.warp = -1.0f;
    wild.react = -1.0f;
    EXPECT_FLOAT_EQ(feedback::sanitized(wild).decay, feedback::kMinDecay);
    EXPECT_FLOAT_EQ(feedback::sanitized(wild).zoom, feedback::kMinZoom);
    EXPECT_FLOAT_EQ(feedback::sanitized(wild).rotate, feedback::kMaxRotate);
    EXPECT_FLOAT_EQ(feedback::sanitized(wild).warp, 0.0f);
    EXPECT_FLOAT_EQ(feedback::sanitized(wild).react, 0.0f);
}

TEST(FeedbackSpec, ABrokenValueTakesItsDefault) {
    feedback::WarpSpec broken;
    broken.decay = kNan;
    broken.zoom = kNan;
    broken.rotate = kNan;
    broken.flow = {kNan, kNan};
    broken.stretch = {kNan, kNan};
    broken.warp = kNan;
    broken.tint = {kNan, kNan, kNan};
    broken.center = {kNan, kNan};
    broken.react = kNan;
    EXPECT_EQ(feedback::sanitized(broken), feedback::WarpSpec{});
    // A spec already in range comes back as it was.
    feedback::WarpSpec tuned;
    tuned.on = true;
    tuned.decay = 0.8f;
    tuned.zoom = 1.2f;
    tuned.rotate = 0.3f;
    tuned.flow = {0.1f, -0.2f};
    tuned.stretch = {1.1f, 0.9f};
    tuned.warp = 0.4f;
    tuned.tint = {1.0f, 0.9f, 0.7f};
    tuned.center = {0.4f, 0.6f};
    tuned.react = 0.5f;
    EXPECT_EQ(feedback::sanitized(tuned), tuned);
}

// ---- the source map ----------------------------------------------------------------------------------

TEST(FeedbackSourceMap, AStalledFrameMovesNothing) {
    for (const float dt : {0.0f, -0.1f, kNan}) {
        const feedback::Affine map = feedback::sourceMap(1.5f, 0.7f, {0.2f, 0.1f}, dt);
        EXPECT_EQ(map.map, (std::array<float, 4>{1.0f, 0.0f, 0.0f, 1.0f}));
        EXPECT_EQ(map.shift, (std::array<float, 2>{0.0f, 0.0f}));
    }
}

TEST(FeedbackSourceMap, AGrowingPictureReadsFromNearerTheCentre) {
    const feedback::Affine map = feedback::sourceMap(2.0f, 0.0f, {0.0f, 0.0f}, 1.0f);
    const auto src = feedback::apply(map, {0.4f, -0.2f});
    EXPECT_NEAR(src[0], 0.2f, 1e-6f);
    EXPECT_NEAR(src[1], -0.1f, 1e-6f);
    // Shrinking reads from further out.
    const auto out = feedback::apply(feedback::sourceMap(0.5f, 0.0f, {0.0f, 0.0f}, 1.0f), {0.4f, -0.2f});
    EXPECT_NEAR(out[0], 0.8f, 1e-6f);
    EXPECT_NEAR(out[1], -0.4f, 1e-6f);
}

TEST(FeedbackSourceMap, AnAnticlockwiseTurnReadsFromBehind) {
    // A quarter turn a second, over half a second: the picture at angle 0 came from angle -pi/4.
    const auto src = feedback::apply(feedback::sourceMap(1.0f, kPi / 2.0f, {0.0f, 0.0f}, 0.5f), {1.0f, 0.0f});
    EXPECT_NEAR(src[0], std::cos(-kPi / 4.0f), 1e-6f);
    EXPECT_NEAR(src[1], std::sin(-kPi / 4.0f), 1e-6f);
}

TEST(FeedbackSourceMap, APlainFlowReadsFromUpstream) {
    const auto src = feedback::apply(feedback::sourceMap(1.0f, 0.0f, {0.2f, -0.1f}, 0.5f), {0.3f, 0.3f});
    EXPECT_NEAR(src[0], 0.2f, 1e-6f);
    EXPECT_NEAR(src[1], 0.35f, 1e-6f);
    // A broken zoom or rate moves as if it were not there; a broken flow does not drift.
    const auto broken = feedback::apply(feedback::sourceMap(kNan, kNan, {kNan, 0.2f}, 0.5f), {0.3f, 0.3f});
    EXPECT_NEAR(broken[0], 0.3f, 1e-6f);
    EXPECT_NEAR(broken[1], 0.2f, 1e-6f);
    const auto negative = feedback::apply(feedback::sourceMap(-2.0f, 0.0f, {0.0f, 0.0f}, 0.5f), {0.3f, 0.3f});
    EXPECT_NEAR(negative[0], 0.3f, 1e-6f);
    const auto endless = feedback::apply(
        feedback::sourceMap(std::numeric_limits<float>::infinity(), 0.0f, {0.0f, 0.0f}, 0.5f), {0.3f, 0.3f});
    EXPECT_NEAR(endless[0], 0.3f, 1e-6f);
    const auto sideways = feedback::apply(feedback::sourceMap(1.0f, 0.0f, {0.2f, kNan}, 0.5f), {0.3f, 0.3f});
    EXPECT_NEAR(sideways[0], 0.2f, 1e-6f);
    EXPECT_NEAR(sideways[1], 0.3f, 1e-6f);
}

TEST(FeedbackSourceMap, ASecondOfFramesIsOneSecondAtAnyFrameRate) {
    const float zoom = 1.3f;
    const float rotate = 0.7f;
    const std::array<float, 2> flow{0.1f, -0.05f};
    const std::array<float, 2> p{0.45f, -0.3f};
    const auto whole = feedback::apply(feedback::sourceMap(zoom, rotate, flow, 1.0f), p);
    for (const int fps : {30, 60, 90, 120, 144}) {
        const auto frame = feedback::sourceMap(zoom, rotate, flow, 1.0f / static_cast<float>(fps));
        EXPECT_LT(distance(carried(frame, p, fps), whole), 2e-5f) << fps << " fps";
    }
    // The flow alone, with nothing turning or growing, is exact too.
    const auto plain = feedback::apply(feedback::sourceMap(1.0f, 0.0f, flow, 1.0f), p);
    EXPECT_LT(distance(carried(feedback::sourceMap(1.0f, 0.0f, flow, 1.0f / 60.0f), p, 60), plain), 1e-5f);
}

TEST(FeedbackSourceMap, TheSlowSeriesMeetsTheExactForm) {
    // Either side of where the flow term switches to its series, the map barely moves.
    const std::array<float, 2> flow{0.3f, 0.2f};
    const float dt = 1.0f / 60.0f;
    const float below = 0.9e-3f / dt;
    const float above = 1.1e-3f / dt;
    const auto a = feedback::sourceMap(1.0f, below, flow, dt);
    const auto b = feedback::sourceMap(1.0f, above, flow, dt);
    EXPECT_NEAR(a.shift[0], b.shift[0], 1e-6f);
    EXPECT_NEAR(a.shift[1], b.shift[1], 1e-6f);
    // And the series composes like the exact form: a slow turn over a second of frames.
    const float slow = 0.03f;
    const auto whole = feedback::apply(feedback::sourceMap(1.0f, slow, flow, 1.0f), {0.2f, 0.1f});
    EXPECT_LT(distance(carried(feedback::sourceMap(1.0f, slow, flow, dt), {0.2f, 0.1f}, 60), whole), 1e-5f);
    // The series itself is the exact flow term to float: over a whole second, where its second-order
    // part is large enough to see, against (e^(-lambda t) - 1) / lambda in double precision.
    const double lr = 0.0004;
    const double li = 0.0008;
    const double scale = std::exp(-lr);
    const double nr = scale * std::cos(-li) - 1.0;
    const double ni = scale * std::sin(-li);
    const double norm = lr * lr + li * li;
    const double kr = (nr * lr + ni * li) / norm;
    const double ki = (ni * lr - nr * li) / norm;
    const auto series =
        feedback::sourceMap(static_cast<float>(std::exp(lr)), static_cast<float>(li), {0.5f, 0.25f}, 1.0f);
    EXPECT_NEAR(series.shift[0], kr * 0.5 - ki * 0.25, 1e-6);
    EXPECT_NEAR(series.shift[1], ki * 0.5 + kr * 0.25, 1e-6);
    // A slow growth reads from upstream and inward at once.
    const auto grow = feedback::sourceMap(1.0001f, 0.0f, {0.0f, 0.1f}, dt);
    EXPECT_NEAR(grow.shift[1], -0.1f * dt, 1e-7f);
}

TEST(FeedbackSourceMap, AppliesTheMapInColumnOrder) {
    feedback::Affine affine;
    affine.map = {1.0f, 2.0f, 3.0f, 4.0f};
    affine.shift = {0.5f, -0.5f};
    const auto p = feedback::apply(affine, {1.0f, 10.0f});
    EXPECT_FLOAT_EQ(p[0], 1.0f + 30.0f + 0.5f);
    EXPECT_FLOAT_EQ(p[1], 2.0f + 40.0f - 0.5f);
}

// ---- rates ---------------------------------------------------------------------------------------

TEST(FeedbackRates, TheDecayExtraStretchesTheHalfLife) {
    EXPECT_FLOAT_EQ(feedback::keptPerSecond(0.6f, 1.0f), 0.6f);
    EXPECT_NEAR(feedback::keptPerSecond(0.64f, 2.0f), 0.8f, 1e-6f);
    EXPECT_NEAR(feedback::keptPerSecond(0.8f, 0.5f), 0.64f, 1e-6f);
    // Never so long the loop stops shrinking.
    EXPECT_FLOAT_EQ(feedback::keptPerSecond(0.95f, 2.0f), feedback::kMaxKept);
    EXPECT_FLOAT_EQ(feedback::keptPerSecond(1.5f, 1.0f), feedback::kMaxKept);
    // No trail at all at 0, or for a broken value.
    EXPECT_FLOAT_EQ(feedback::keptPerSecond(0.6f, 0.0f), 0.0f);
    EXPECT_FLOAT_EQ(feedback::keptPerSecond(0.6f, -1.0f), 0.0f);
    EXPECT_FLOAT_EQ(feedback::keptPerSecond(0.0f, 1.0f), 0.0f);
    EXPECT_FLOAT_EQ(feedback::keptPerSecond(kNan, 1.0f), 0.0f);
    EXPECT_FLOAT_EQ(feedback::keptPerSecond(0.6f, kNan), 0.0f);
}

TEST(FeedbackRates, AShareASecondComesToTheSameOverAnyFrames) {
    for (const int fps : {30, 60, 120, 144}) {
        float product = 1.0f;
        for (int i = 0; i < fps; ++i) product *= feedback::perFrame(0.6f, 1.0f / static_cast<float>(fps));
        EXPECT_NEAR(product, 0.6f, 1e-4f) << fps;
    }
    EXPECT_FLOAT_EQ(feedback::perFrame(0.6f, 0.0f), 1.0f);
    EXPECT_FLOAT_EQ(feedback::perFrame(0.6f, kNan), 1.0f);
    EXPECT_FLOAT_EQ(feedback::perFrame(0.0f, 0.1f), 0.0f);
    EXPECT_FLOAT_EQ(feedback::perFrame(-1.0f, 0.1f), 0.0f);
    EXPECT_FLOAT_EQ(feedback::perFrame(0.25f, 0.5f), 0.5f);
}

TEST(FeedbackRates, TheMusicSpeedsTheMotionWithinItsBounds) {
    feedback::WarpSpec spec;
    feedback::Drive drive;
    EXPECT_FLOAT_EQ(feedback::motionScale(spec, drive), 1.0f);
    drive.swell = 1.0f;
    drive.beat = 1.0f;
    // Without react the music does nothing.
    EXPECT_FLOAT_EQ(feedback::motionScale(spec, drive), 1.0f);
    spec.react = 0.5f;
    EXPECT_FLOAT_EQ(feedback::motionScale(spec, drive), 1.0f + 0.5f * (1.0f + feedback::kBeatShare));
    drive.beat = 0.0f;
    EXPECT_FLOAT_EQ(feedback::motionScale(spec, drive), 1.5f);
    // Signals are held to 0..1.
    drive.swell = 4.0f;
    EXPECT_FLOAT_EQ(feedback::motionScale(spec, drive), 1.5f);
    drive.swell = -1.0f;
    drive.beat = kNan;
    EXPECT_FLOAT_EQ(feedback::motionScale(spec, drive), 1.0f);
    // The speed scales it all, never past kMaxMotion and never backwards.
    drive.swell = 1.0f;
    drive.speed = 2.5f;
    EXPECT_FLOAT_EQ(feedback::motionScale(spec, drive), feedback::kMaxMotion);
    drive.speed = 0.5f;
    EXPECT_FLOAT_EQ(feedback::motionScale(spec, drive), 0.75f);
    drive.speed = -1.0f;
    EXPECT_FLOAT_EQ(feedback::motionScale(spec, drive), 0.0f);
    drive.speed = kNan;
    EXPECT_FLOAT_EQ(feedback::motionScale(spec, drive), 1.5f);
    // A broken react lets the music do nothing.
    spec.react = kNan;
    EXPECT_FLOAT_EQ(feedback::motionScale(spec, drive), 1.0f);
}

// ---- a second of the warp --------------------------------------------------------------------------

TEST(FeedbackMotion, ASecondOfWarpIsTheSameAtEveryFrameRate) {
    feedback::WarpSpec spec;
    spec.on = true;
    spec.decay = 0.5f;
    spec.tint = {1.0f, 0.8f, 0.5f};
    spec.stretch = {1.2f, 0.9f};
    spec.warp = 0.6f;
    spec.react = 1.0f;
    feedback::Drive drive;
    drive.swell = 0.5f;
    const float motion = 1.5f;
    for (const int fps : {30, 60, 120, 144}) {
        const Second second = runSecond(spec, drive, fps);
        EXPECT_NEAR(second.keep[0], 0.5f, 1e-4f) << fps;
        EXPECT_NEAR(second.keep[1], 0.4f, 1e-4f) << fps;
        EXPECT_NEAR(second.keep[2], 0.25f, 1e-4f) << fps;
        EXPECT_NEAR(second.stretch[0], std::pow(1.2f, motion), 1e-4f) << fps;
        EXPECT_NEAR(second.stretch[1], std::pow(0.9f, motion), 1e-4f) << fps;
        EXPECT_NEAR(second.warp, 0.6f * feedback::kWarpReach * motion, 1e-5f) << fps;
        EXPECT_NEAR(second.floor, feedback::kFloorPerSecond, 1e-6f) << fps;
        EXPECT_NEAR(second.phase, feedback::kWarpHz * motion, 1e-4f) << fps;
    }
}

TEST(FeedbackMotion, TheExtrasMoveTheirOwnPartOfTheWarp) {
    feedback::WarpSpec spec;
    spec.on = true;
    spec.decay = 0.64f;
    spec.zoom = 1.5f;
    spec.rotate = 0.4f;
    spec.flow = {0.1f, 0.0f};
    spec.warp = 0.5f;
    const float dt = 1.0f / 60.0f;
    feedback::Drive neutral;
    feedback::Motion motion;
    const feedback::Step plain = motion.step(spec, neutral, dt);
    EXPECT_FLOAT_EQ(plain.dt, dt);
    EXPECT_EQ(plain.center, spec.center);
    const feedback::Affine expected = feedback::sourceMap(1.5f, 0.4f, {0.1f, 0.0f}, dt);
    for (size_t i = 0; i < 4; ++i) EXPECT_NEAR(plain.source.map[i], expected.map[i], 1e-6f);
    for (size_t i = 0; i < 2; ++i) EXPECT_NEAR(plain.source.shift[i], expected.shift[i], 1e-7f);

    // Decay 2: twice the half-life.
    feedback::Drive longer;
    longer.decay = 2.0f;
    EXPECT_NEAR(motion.step(spec, longer, 1.0f).keep[0], 0.8f, 1e-5f);
    // Decay 0: nothing kept.
    feedback::Drive none;
    none.decay = 0.0f;
    EXPECT_FLOAT_EQ(motion.step(spec, none, dt).keep[0], 0.0f);
    // Flight 0 stops the zoom, Spin 0 the turn, Drift 0 the flow: nothing moves.
    feedback::Drive still;
    still.flight = 0.0f;
    still.spin = 0.0f;
    still.drift = 0.0f;
    still.swirl = 0.0f;
    const feedback::Step stopped = motion.step(spec, still, dt);
    EXPECT_NEAR(stopped.source.map[0], 1.0f, 1e-6f);
    EXPECT_NEAR(stopped.source.map[1], 0.0f, 1e-6f);
    EXPECT_NEAR(stopped.source.shift[0], 0.0f, 1e-7f);
    EXPECT_FLOAT_EQ(stopped.warp, 0.0f);
    // Doubled, each goes twice as far.
    feedback::Drive doubled;
    doubled.flight = 2.0f;
    doubled.spin = 2.0f;
    doubled.drift = 2.0f;
    doubled.swirl = 2.0f;
    const feedback::Step faster = motion.step(spec, doubled, dt);
    const feedback::Affine twice = feedback::sourceMap(1.5f * 1.5f, 0.8f, {0.2f, 0.0f}, dt);
    for (size_t i = 0; i < 4; ++i) EXPECT_NEAR(faster.source.map[i], twice.map[i], 1e-6f);
    for (size_t i = 0; i < 2; ++i) EXPECT_NEAR(faster.source.shift[i], twice.shift[i], 1e-7f);
    EXPECT_NEAR(faster.warp, 2.0f * plain.warp, 1e-7f);
    // A broken Extra is neutral.
    feedback::Drive broken;
    broken.swirl = kNan;
    broken.decay = kNan;
    EXPECT_NEAR(motion.step(spec, broken, dt).warp, plain.warp, 1e-7f);
    EXPECT_NEAR(motion.step(spec, broken, dt).keep[0], plain.keep[0], 1e-7f);
}

TEST(FeedbackMotion, TheSwirlClockRunsOnAndStartsOverWithAVisit) {
    feedback::WarpSpec spec;
    spec.on = true;
    feedback::Motion motion;
    feedback::Drive drive;
    drive.speed = 2.0f;
    const feedback::Step first = motion.step(spec, drive, 0.5f);
    EXPECT_NEAR(first.phase, feedback::kWarpHz * 2.0f * 0.5f, 1e-6f);
    EXPECT_FLOAT_EQ(motion.phase(), first.phase);
    // A stalled frame keeps the clock and everything else where it is.
    const feedback::Step stalled = motion.step(spec, drive, 0.0f);
    EXPECT_FLOAT_EQ(stalled.phase, first.phase);
    EXPECT_FLOAT_EQ(stalled.keep[0], 1.0f);
    EXPECT_FLOAT_EQ(stalled.floor, 0.0f);
    EXPECT_FLOAT_EQ(stalled.dt, 0.0f);
    motion.reset();
    EXPECT_FLOAT_EQ(motion.phase(), 0.0f);
    // The step reads the spec held to its ranges.
    spec.decay = 5.0f;
    EXPECT_NEAR(motion.step(spec, feedback::Drive{}, 1.0f).keep[0], feedback::kMaxDecay, 1e-5f);
}

// ---- what the loop paints (#363) ------------------------------------------------------------------

TEST(FeedbackPaint, AnAlphaASecondComesToTheSameOverAnyFrames) {
    // What is left unpainted over a second is 1 - alpha at every frame rate.
    for (const int fps : {30, 60, 120}) {
        const float cover = feedback::paintPerFrame(0.6f, 1.0f / static_cast<float>(fps));
        EXPECT_NEAR(std::pow(1.0f - cover, static_cast<float>(fps)), 0.4f, 1e-4f) << fps;
    }
    EXPECT_NEAR(feedback::paintPerFrame(0.75f, 0.5f), 0.5f, 1e-6f);
    EXPECT_NEAR(feedback::paintPerFrame(0.6f, 1.0f), 0.6f, 1e-6f);
    // Full alpha paints the whole colour every frame; none, or a stalled or broken frame, paints nothing.
    EXPECT_EQ(feedback::paintPerFrame(1.0f, 1.0f / 60.0f), 1.0f);
    EXPECT_EQ(feedback::paintPerFrame(3.0f, 1.0f / 60.0f), 1.0f);
    EXPECT_EQ(feedback::paintPerFrame(0.0f, 1.0f / 60.0f), 0.0f);
    EXPECT_EQ(feedback::paintPerFrame(-1.0f, 1.0f / 60.0f), 0.0f);
    EXPECT_EQ(feedback::paintPerFrame(kNan, 1.0f / 60.0f), 0.0f);
    EXPECT_EQ(feedback::paintPerFrame(0.5f, 0.0f), 0.0f);
    EXPECT_EQ(feedback::paintPerFrame(0.5f, -1.0f), 0.0f);
    EXPECT_EQ(feedback::paintPerFrame(0.5f, kNan), 0.0f);
}

TEST(FeedbackPaint, EasesInOverTheVisitsFirstSecond) {
    EXPECT_EQ(feedback::paintFade(0.0f), 0.0f);
    EXPECT_NEAR(feedback::paintFade(0.25f * feedback::kPaintFadeSeconds), 0.15625f, 1e-6f);
    EXPECT_NEAR(feedback::paintFade(0.5f * feedback::kPaintFadeSeconds), 0.5f, 1e-6f);
    EXPECT_NEAR(feedback::paintFade(0.75f * feedback::kPaintFadeSeconds), 0.84375f, 1e-6f);
    EXPECT_EQ(feedback::paintFade(feedback::kPaintFadeSeconds), 1.0f);
    EXPECT_EQ(feedback::paintFade(5.0f), 1.0f);
    EXPECT_EQ(feedback::paintFade(-1.0f), 0.0f);
    EXPECT_EQ(feedback::paintFade(kNan), 0.0f);
}

TEST(FeedbackPaint, ABorderPaintsOnlyWhenItHasAWidthAndAnAlpha) {
    feedback::Border border;
    border.size = 0.04f;
    border.color = {1.0f, 0.5f, 0.25f};
    border.alpha = 0.75f;
    const feedback::Paint full = feedback::borderPaint(border, 0.5f, feedback::kPaintFadeSeconds);
    EXPECT_NEAR(full.cover, 0.5f, 1e-6f);
    EXPECT_FLOAT_EQ(full.size, 0.04f);
    EXPECT_FLOAT_EQ(full.color[0], 1.0f);
    EXPECT_FLOAT_EQ(full.color[1], 0.5f);
    EXPECT_FLOAT_EQ(full.color[2], 0.25f);
    // Halfway into the ease, half of it.
    EXPECT_NEAR(feedback::borderPaint(border, 0.5f, 0.5f * feedback::kPaintFadeSeconds).cover, 0.25f, 1e-6f);
    EXPECT_EQ(feedback::borderPaint(border, 0.5f, 0.0f).cover, 0.0f);
    border.size = 0.0f;
    EXPECT_EQ(feedback::borderPaint(border, 0.5f, feedback::kPaintFadeSeconds).cover, 0.0f);
    border.size = 0.04f;
    border.alpha = 0.0f;
    EXPECT_EQ(feedback::borderPaint(border, 0.5f, feedback::kPaintFadeSeconds).cover, 0.0f);
}

TEST(FeedbackPaint, TheBordersAreHeldToTheirRanges) {
    feedback::WarpSpec wild;
    wild.outer.size = 0.5f;
    wild.outer.color = {-1.0f, 0.5f, 2.0f};
    wild.outer.alpha = 3.0f;
    wild.inner.size = -0.1f;
    wild.inner.color = {kNan, kNan, kNan};
    wild.inner.alpha = kNan;
    const feedback::WarpSpec held = feedback::sanitized(wild);
    EXPECT_FLOAT_EQ(held.outer.size, feedback::kMaxBorderSize);
    EXPECT_FLOAT_EQ(held.outer.color[0], 0.0f);
    EXPECT_FLOAT_EQ(held.outer.color[1], 0.5f);
    EXPECT_FLOAT_EQ(held.outer.color[2], 1.0f);
    EXPECT_FLOAT_EQ(held.outer.alpha, 1.0f);
    EXPECT_FLOAT_EQ(held.inner.size, 0.0f);
    EXPECT_FLOAT_EQ(held.inner.color[0], 1.0f);
    EXPECT_FLOAT_EQ(held.inner.color[2], 1.0f);
    EXPECT_FLOAT_EQ(held.inner.alpha, 0.0f);
    wild.outer.size = kNan;
    EXPECT_FLOAT_EQ(feedback::sanitized(wild).outer.size, 0.0f);
    // The paint is held too, whatever it is handed.
    EXPECT_FLOAT_EQ(feedback::borderPaint(wild.inner, 0.5f, 1.0f).color[1], 1.0f);
    EXPECT_FLOAT_EQ(feedback::borderPaint(wild.outer, 0.5f, 1.0f).size, 0.0f);
}

TEST(FeedbackPaint, TheMotionEasesTheBordersInWithEachVisit) {
    feedback::WarpSpec spec;
    spec.outer.size = 0.02f;
    spec.outer.alpha = 0.6f;
    spec.inner.size = 0.03f;
    spec.inner.color = {0.2f, 0.4f, 0.6f};
    spec.inner.alpha = 1.0f;
    feedback::Motion motion;
    const float dt = 1.0f / 60.0f;
    const feedback::Step first = motion.step(spec, feedback::Drive{}, dt);
    EXPECT_NEAR(motion.age(), dt, 1e-7f);
    EXPECT_NEAR(first.outer.cover, feedback::paintPerFrame(0.6f, dt) * feedback::paintFade(dt), 1e-7f);
    EXPECT_NEAR(first.inner.cover, feedback::paintFade(dt), 1e-7f);
    EXPECT_LT(first.inner.cover, 0.01f);
    EXPECT_FLOAT_EQ(first.inner.size, 0.03f);
    EXPECT_FLOAT_EQ(first.inner.color[1], 0.4f);
    feedback::Step later = first;
    for (int i = 0; i < 120; ++i) later = motion.step(spec, feedback::Drive{}, dt);
    // Eased in after a second, and the clock holds there.
    EXPECT_EQ(motion.age(), feedback::kPaintFadeSeconds);
    EXPECT_NEAR(later.outer.cover, feedback::paintPerFrame(0.6f, dt), 1e-7f);
    EXPECT_EQ(later.inner.cover, 1.0f);
    // A stalled frame paints nothing.
    EXPECT_EQ(motion.step(spec, feedback::Drive{}, 0.0f).outer.cover, 0.0f);
    // A new visit eases them in again.
    motion.reset();
    EXPECT_EQ(motion.age(), 0.0f);
    EXPECT_NEAR(motion.step(spec, feedback::Drive{}, dt).inner.cover, feedback::paintFade(dt), 1e-7f);
    // A loop with no borders paints none.
    EXPECT_EQ(feedback::Motion{}.step(feedback::WarpSpec{}, feedback::Drive{}, dt).outer.cover, 0.0f);
}

// ---- the store -------------------------------------------------------------------------------------

TEST(FeedbackStore, KnowsEachFormatsSpacing) {
    EXPECT_FLOAT_EQ(feedback::quantum(1.0f, Precision::Half), std::exp2(-10.0f));
    EXPECT_FLOAT_EQ(feedback::quantum(1.9f, Precision::Half), std::exp2(-10.0f));
    EXPECT_FLOAT_EQ(feedback::quantum(0.75f, Precision::Half), std::exp2(-11.0f));
    EXPECT_FLOAT_EQ(feedback::quantum(3.0f, Precision::Half), std::exp2(-9.0f));
    // The subnormals are evenly spaced at the smallest normal binade's step.
    EXPECT_FLOAT_EQ(feedback::quantum(1e-6f, Precision::Half), std::exp2(-24.0f));
    EXPECT_FLOAT_EQ(feedback::quantum(0.0f, Precision::Half), std::exp2(-24.0f));
    EXPECT_FLOAT_EQ(feedback::quantum(0.3f, Precision::Unorm10), 1.0f / 1023.0f);
    EXPECT_FLOAT_EQ(feedback::quantum(0.3f, Precision::Unorm8), 1.0f / 255.0f);
}

TEST(FeedbackStore, RoundsAsTheTargetStores) {
    EXPECT_FLOAT_EQ(feedback::quantize(0.5f + 0.4f / 255.0f, Precision::Unorm8),
                    std::nearbyint(0.5f * 255.0f + 0.4f) / 255.0f);
    EXPECT_FLOAT_EQ(feedback::quantize(2.0f, Precision::Unorm8), 1.0f);
    EXPECT_FLOAT_EQ(feedback::quantize(2.0f, Precision::Unorm10), 1.0f);
    EXPECT_FLOAT_EQ(feedback::quantize(-0.5f, Precision::Unorm8), 0.0f);
    EXPECT_FLOAT_EQ(feedback::quantize(kNan, Precision::Half), 0.0f);
    EXPECT_FLOAT_EQ(feedback::quantize(2.0f, Precision::Half), 2.0f);
    EXPECT_FLOAT_EQ(feedback::quantize(1.0f + 0.6f * std::exp2(-10.0f), Precision::Half), 1.0f + std::exp2(-10.0f));
    EXPECT_FLOAT_EQ(feedback::quantize(1.0f + 0.4f * std::exp2(-10.0f), Precision::Half), 1.0f);
    EXPECT_FLOAT_EQ(feedback::quantize(100.0f / 1023.0f + 0.3f / 1023.0f, Precision::Unorm10), 100.0f / 1023.0f);
}

TEST(FeedbackStore, SettleKeepsFloorsAndHoldsTheLoop) {
    // With noise 0.5 it is the plain decay less the floor.
    EXPECT_FLOAT_EQ(feedback::settle(0.8f, 0.5f, 0.0f, Precision::Half, 0.5f), 0.4f);
    EXPECT_FLOAT_EQ(feedback::settle(0.8f, 0.5f, 0.1f, Precision::Unorm8, 0.5f), 0.3f);
    // The dither is one quantum of what is left, either way.
    EXPECT_FLOAT_EQ(feedback::settle(0.8f, 0.5f, 0.0f, Precision::Unorm8, 1.0f), 0.4f + 0.5f / 255.0f);
    EXPECT_FLOAT_EQ(feedback::settle(0.8f, 0.5f, 0.0f, Precision::Unorm10, 0.0f), 0.4f - 0.5f / 1023.0f);
    EXPECT_FLOAT_EQ(feedback::settle(0.8f, 0.5f, 0.0f, Precision::Half, 1.0f), 0.4f + 0.5f * std::exp2(-12.0f));
    // Nothing left is exactly black, whatever the noise, and nothing goes below it.
    EXPECT_FLOAT_EQ(feedback::settle(0.0f, 0.9f, 0.0f, Precision::Unorm8, 0.99f), 0.0f);
    EXPECT_FLOAT_EQ(feedback::settle(0.001f, 0.9f, 0.01f, Precision::Unorm8, 0.99f), 0.0f);
    EXPECT_NEAR(feedback::settle(0.5f / 255.0f, 1.0f, 0.0f, Precision::Unorm8, 0.0f), 0.0f, 1e-9f);
    EXPECT_GE(feedback::settle(0.3f / 255.0f, 1.0f, 0.0f, Precision::Unorm8, 0.0f), 0.0f);
    // A broken or blazing value cannot feed the loop.
    EXPECT_FLOAT_EQ(feedback::settle(kNan, 0.9f, 0.0f, Precision::Half, 0.5f), 0.0f);
    EXPECT_FLOAT_EQ(feedback::settle(-3.0f, 0.9f, 0.0f, Precision::Half, 0.5f), 0.0f);
    EXPECT_FLOAT_EQ(feedback::settle(1000.0f, 0.5f, 0.0f, Precision::Half, 0.5f), feedback::kCeiling * 0.5f);
}

TEST(FeedbackStore, TheDitherKeepsTheExpectedValueOnTheExactDecay) {
    // Averaged over the noise, what the target stores is exactly what the decay leaves, at every
    // precision and every level: stochastic rounding has no bias for a stall to grow from.
    constexpr int kSamples = 2000;
    for (const Precision precision : kPrecisions) {
        for (const float value : {1.0f, 0.571f, 0.2f, 0.031f, 3.0f / 255.0f}) {
            const float left = value * slowKeep() - 1e-5f;
            double sum = 0.0;
            for (int i = 0; i < kSamples; ++i) {
                const float noise = (static_cast<float>(i) + 0.5f) / kSamples;
                sum += feedback::quantize(feedback::settle(value, slowKeep(), 1e-5f, precision, noise), precision);
            }
            const float q = feedback::quantum(left, precision);
            EXPECT_NEAR(sum / kSamples, left, q * 0.01f + 1e-7f) << static_cast<int>(precision) << " at " << value;
        }
    }
}

TEST(FeedbackStore, APlainSlowDecayStallsIntoAGhost) {
    // What the loop would do without the dither and the floor: at 95% a second on a 120 Hz panel, one
    // frame's decay is under half a step of every one of these formats somewhere above half
    // brightness, so the picture stops fading there for good. This is the ghost the store prevents.
    for (const Precision precision : kPrecisions) {
        EXPECT_GT(stored(1.0f, kFps * 120, 0.0f, precision, false, 1), 0.5f) << static_cast<int>(precision);
    }
}

TEST(FeedbackStore, ASlowDecayFadesSmoothlyToExactBlack) {
    // The same loop through settle(): the expected trajectory is the plain decay less the floor,
    // v(n) = (1 + F / (1 - K)) K^n - F / (1 - K), which reaches 0 after n0 frames. Every channel follows
    // it, never rises, and is exactly black a while after n0, at every precision.
    const float keep = slowKeep();
    const float floor = feedback::kFloorPerSecond / kFps;
    const float tail = floor / (1.0f - keep);
    const int n0 = static_cast<int>(std::ceil(std::log(tail / (1.0f + tail)) / std::log(keep)));
    const int checkpoint = kFps * 30;
    const float expected = (1.0f + tail) * std::pow(keep, static_cast<float>(checkpoint)) - tail;
    ASSERT_GT(expected, 0.1f);
    ASSERT_LT(n0, kFps * 60);
    constexpr int kChannels = 16;
    for (const Precision precision : kPrecisions) {
        double sum = 0.0;
        for (int c = 0; c < kChannels; ++c) {
            Noise noise(static_cast<std::uint32_t>(c) + 7u);
            float v = 1.0f;
            bool rose = false;
            for (int n = 0; n < n0 + kFps * 20; ++n) {
                const float next =
                    feedback::quantize(feedback::settle(v, keep, floor, precision, noise.next()), precision);
                rose = rose || next > v;
                v = next;
                if (n + 1 == checkpoint) sum += v;
            }
            EXPECT_FALSE(rose) << static_cast<int>(precision) << " channel " << c;
            EXPECT_EQ(v, 0.0f) << static_cast<int>(precision) << " channel " << c;
        }
        EXPECT_NEAR(sum / kChannels, expected, 0.03f) << static_cast<int>(precision);
    }
}
