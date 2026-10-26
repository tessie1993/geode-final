#pragma once
#include <array>
#include <cstdint>

namespace geode::viz::feedback {

// The feedback warp layer (#321): a shader Style that declares "feedback" in its pack
// (docs/style-packs.md) is drawn as a loop, the way MilkDrop draws every preset. Each frame the
// picture the Style kept is carried through a coarse motion grid (zoom, rotation, flow, stretch and a
// swirl field), decayed and tinted into a high-precision buffer, and the Style reads that as its
// previous frame (uPrevFrame) and draws over it; what it draws is kept for the next frame and shown.
//
// Everything here is per second, so the motion and the fading are the same at 30, 60 and 120 Hz:
// zoom, rotation and flow are integrated exactly over each frame's dt (sourceMap), and the decay,
// the tint and the stretch are raised to the frame's share of a second. The loop always shrinks:
// what is kept in a second never reaches kMaxKept, so it can only fade, never build to a flash (the
// No-flash rule, CONTEXT.md; the limiter still bounds the output).
//
// The buffer is half float where the device renders it (RGB10_A2 elsewhere), and the warp rounds
// stochastically (settle): a slow decay multiplied into any fixed number of bits stalls where one
// step no longer moves the stored value, leaving a ghost that never fades. A dither of one quantum
// before the store keeps the expected value on the exact decay at every precision, and a small
// floor taken off each second ends every trail at exact black. lib_feedback.glsl mirrors settle().

// ---- what a Style declares -----------------------------------------------------------------------

// Every declared value is held to these ranges (sanitized()); tools/ci/check_style_packs.py holds the
// packs to the same numbers.
inline constexpr float kMinDecay = 0.05f;
inline constexpr float kMaxDecay = 0.95f;
inline constexpr float kDefaultDecay = 0.6f;
inline constexpr float kMinZoom = 0.5f;
inline constexpr float kMaxZoom = 2.0f;
inline constexpr float kMaxRotate = 3.0f;
inline constexpr float kMaxFlow = 0.5f;
inline constexpr float kMinStretch = 0.5f;
inline constexpr float kMaxStretch = 2.0f;
inline constexpr float kMaxWarp = 1.0f;
inline constexpr float kMaxReact = 1.0f;

// The share of light kept over one second, after the Decay Extra stretched the Style's own: below 1,
// so the loop always shrinks.
inline constexpr float kMaxKept = 0.97f;
// Light taken off every channel each second on top of the decay, so every trail ends at exact black.
inline constexpr float kFloorPerSecond = 1.0f / 256.0f;
// The brightest the kept picture may be (an HDR light), whatever a pack's warp shader adds.
inline constexpr float kCeiling = 4.0f;
// What the motion grid's swirl moves the picture at warp 1, in frame heights per second.
inline constexpr float kWarpReach = 0.15f;
// How fast the swirl field itself turns over, in radians of its phase per second at motion 1.
inline constexpr float kWarpHz = 0.6f;
inline constexpr float kPhaseWrap = 6283.1853f;
// The share of the beat envelope added to the swell when the music drives the motion.
inline constexpr float kBeatShare = 0.5f;
// The fastest the music and the Speed control may drive the motion: three times the Style's own.
inline constexpr float kMaxMotion = 3.0f;

// The coarse motion grid the warp is drawn on: the motion is worked out at its vertices and
// interpolated across each cell (feedback_warp_vert.glsl).
inline constexpr int kGridColumns = 32;
inline constexpr int kGridRows = 24;
inline constexpr int kGridVertices = kGridColumns * kGridRows * 6;

// What the warp paints into the loop before it keeps it (lib_feedback.glsl fbPaint): borders, whose
// copies the zoom carries inward into a tunnel or outward off the frame (#363). What is painted is a
// source of light like the Style's own drawing, so the loop's decay still bounds it; it eases in over
// the first kPaintFadeSeconds of each visit, so nothing appears in one frame.
inline constexpr float kMaxBorderSize = 0.1f;
inline constexpr float kPaintFadeSeconds = 1.0f;

// One border: a band `size` frame heights wide, the outer one along the frame's edge and the inner one
// just inside it, painted in `color` with `alpha`, the share of it the band takes on over a second.
struct Border {
    float size = 0.0f;
    std::array<float, 3> color{1.0f, 1.0f, 1.0f};
    float alpha = 0.0f;

    bool operator==(const Border&) const = default;
};

struct WarpSpec {
    bool on = false;
    // The share of the picture's light still there after one second (before the tint).
    float decay = kDefaultDecay;
    // How much the picture grows each second about `center` (above 1 it flows outward).
    float zoom = 1.0f;
    // How far it turns each second about `center`, in radians (positive: anticlockwise).
    float rotate = 0.0f;
    // How far it drifts each second, in frame heights, x then y.
    std::array<float, 2> flow{0.0f, 0.0f};
    // How much it grows each second along x and along y, on top of the zoom.
    std::array<float, 2> stretch{1.0f, 1.0f};
    // The swirl field's strength, 0..1.
    float warp = 0.0f;
    // The share of each channel kept each second, on top of the decay: a tint the trails fade into.
    std::array<float, 3> tint{1.0f, 1.0f, 1.0f};
    // The point the zoom and rotation turn about, 0..1 from the bottom left.
    std::array<float, 2> center{0.5f, 0.5f};
    // How much the music's swell and beat speed the motion up, 0..1.
    float react = 0.0f;
    // The borders painted into the loop (#363); off while their size or alpha is 0.
    Border outer;
    Border inner;

    bool operator==(const WarpSpec&) const = default;
};

// `spec` with every value held to its range; NaNs take the default.
WarpSpec sanitized(const WarpSpec& spec);

// ---- per frame -----------------------------------------------------------------------------------

// What moves the warp this frame beyond the Style's own spec: the Shared core's speed, the music
// (slow loudness and the beat envelope, both already smoothed) and the Extras that map onto it, each a
// multiplier with 1 neutral (Decay stretches the trails' half-life, Flight the zoom, Spin the rotation,
// Drift the flow and Swirl the swirl).
struct Drive {
    float speed = 1.0f;
    float swell = 0.0f;
    float beat = 0.0f;
    float decay = 1.0f;
    float flight = 1.0f;
    float spin = 1.0f;
    float drift = 1.0f;
    float swirl = 1.0f;
};

// The source map of one frame: the warped picture at p reads the kept one at map * p + shift, with p
// relative to the centre and in frame heights (x scaled by the aspect ratio). `map` is a 2x2 matrix in
// column order, as GLSL's mat2 takes it.
struct Affine {
    std::array<float, 4> map{1.0f, 0.0f, 0.0f, 1.0f};
    std::array<float, 2> shift{0.0f, 0.0f};
};

// One frame of a border as the warp paints it: its colour and the share of it painted this frame
// (`cover`, 0 while the border is off), and its width in frame heights.
struct Paint {
    std::array<float, 3> color{1.0f, 1.0f, 1.0f};
    float cover = 0.0f;
    float size = 0.0f;
};

// The uniforms one frame of the warp draws with (lib_feedback.glsl, feedback_warp_vert.glsl).
struct Step {
    Affine source;
    // The frame's stretch: p is divided by it before the source map.
    std::array<float, 2> stretch{1.0f, 1.0f};
    std::array<float, 2> center{0.5f, 0.5f};
    // How far the swirl field moves the picture this frame, in frame heights.
    float warp = 0.0f;
    // The swirl field's phase.
    float phase = 0.0f;
    // What is kept this frame of each channel's light: the decay times the tint.
    std::array<float, 3> keep{1.0f, 1.0f, 1.0f};
    // The light taken off this frame after the keep.
    float floor = 0.0f;
    float dt = 0.0f;
    // The borders painted this frame, before the keep.
    Paint outer;
    Paint inner;
};

// How much faster than the Style's own pace the warp moves: the speed times (1 + react x (swell +
// kBeatShare x beat)), each signal held to 0..1, and the whole within 0..kMaxMotion.
float motionScale(const WarpSpec& spec, const Drive& drive);

// The share of light kept over a second when the trails' half-life is `halfLifeScale` times the
// Style's own `decay`: decay ^ (1 / halfLifeScale), at most kMaxKept; 0 for a scale of 0 or less.
float keptPerSecond(float decay, float halfLifeScale);

// What a share per second comes to over a frame of `dt` seconds: perSecond ^ dt. 1 for a stalled or
// broken frame; 0 when the share itself is not positive.
float perFrame(float perSecond, float dt);

// The exact source map of a frame of `dt` seconds for a picture that grows by `zoomPerSecond` and turns
// by `rotatePerSecond` about the centre while drifting by `flow` frame heights a second: the motion
// z' = lambda z + flow, lambda = ln(zoom) + i rotate, run backwards over dt. Maps of consecutive frames
// compose into the map of their summed dt, so the motion of a second is the same at any frame rate.
Affine sourceMap(float zoomPerSecond, float rotatePerSecond, const std::array<float, 2>& flow, float dt);

// The share of a colour painted over a frame of `dt` seconds so that `alpha` (held to 0..1) of it is
// painted over a second: 1 - (1 - alpha) ^ dt. 0 for a stalled or broken frame, 1 for alpha 1.
float paintPerFrame(float alpha, float dt);

// How far what the warp paints has eased in `age` seconds into a visit: 0 at its start, 1 from
// kPaintFadeSeconds on, smooth between.
float paintFade(float age);

// A border as one frame of `dt` paints it, `age` seconds into the visit: nothing while its size or
// alpha is 0.
Paint borderPaint(const Border& border, float dt, float age);

// `p` through `affine`: map * p + shift.
std::array<float, 2> apply(const Affine& affine, const std::array<float, 2>& p);

// The warp's clocks: the swirl field's phase, which runs at the motion's pace and never jumps, and the
// visit's age, which eases in what the warp paints.
class Motion {
public:
    Step step(const WarpSpec& spec, const Drive& drive, float dt);
    void reset() {
        phase_ = 0.0f;
        age_ = 0.0f;
    }
    float phase() const { return phase_; }
    // Seconds into the visit, held at kPaintFadeSeconds once what the warp paints has eased in.
    float age() const { return age_; }

private:
    float phase_ = 0.0f;
    float age_ = 0.0f;
};

// ---- the store: stochastic rounding ----------------------------------------------------------------

// The formats the feedback buffer can be: half float, or RGB10_A2 and RGBA8 normalised.
enum class Precision : std::uint8_t { Half, Unorm10, Unorm8 };

inline constexpr float kHalfMinNormal = 6.103515625e-05f;  // 2^-14
inline constexpr float kHalfMantissaBits = 10.0f;

// The spacing of the values `precision` can store around `value` (>= 0): for half floats the step of
// value's binade (a smallest normal one below 2^-14), for the normalised formats one level.
float quantum(float value, Precision precision);
// `value` as `precision` stores it: rounded to the nearest representable value, the normalised
// formats clamped to 0..1. What a GPU's store does, for the tests.
float quantize(float value, Precision precision);
// One channel of the warp's store before the GPU rounds it: `value` (held to 0..kCeiling, a NaN read
// as 0) kept by `keep`, less `floor`, plus a dither of (noise - 0.5) quanta of what is left; exactly 0
// once nothing is left, and never below 0. `noise`
// is uniform in [0, 1), fresh every frame. With noise 0.5 it is the plain decay.
float settle(float value, float keep, float floor, Precision precision, float noise);

}  // namespace geode::viz::feedback
