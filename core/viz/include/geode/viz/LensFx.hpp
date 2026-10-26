#pragma once
#include <array>

namespace geode::viz::lens {

// The shared lens effects (#313) and the 3D particle layer (#314): what a Style declares in its pack
// ("lens" and "particles3d", docs/style-packs.md) and the per-frame maths the GL passes draw from.
//
// Every effect here is light, and so luminous: it all runs inside the HDR frame graph, before the
// tonemap and the No-flash limiter, which still runs last and holds (CONTEXT.md). Nothing follows the
// music faster than a swell: the audio drive glides up over a fifth of a second at the quickest, a
// Style change fades the effects over kStyleFadeSeconds, and the thermal tiers switch them off or thin
// them (ThermalGovernor.hpp, QualityTiers.hpp).
//
// - Flare ghosts, god rays and streaks (glare) read the bloom chain (HdrPass, ToneMap.hpp) at quarter
//   size after its downsample: the chain carries the light above the lens threshold in rgb and the
//   bloom's own share in alpha (bloomShare), so no second blur runs. lens_frag.glsl draws them.
// - Depth of field reads a Style's depth from its scene target's alpha (a shader Style that declares
//   it writes depthAlpha() there) and gathers a golden-angle disc sized by the circle of confusion.
//   dof_frag.glsl draws it over the scene target, before the Shared core effects.
// - The particles are streaks and sparks placed in camera space, flown toward the camera at an
//   integrated, audio-driven speed, and drawn as instanced, motion-stretched quads added onto the HDR
//   composite (particles3d_vert.glsl, particles3d_frag.glsl), so bloom catches them.

// ---- what a Style declares -----------------------------------------------------------------------

// Every declared value is held to these ranges (sanitized()); tools/ci/check_style_packs.py holds the
// packs to the same numbers.
inline constexpr float kMinThreshold = 0.5f;
inline constexpr float kMaxThreshold = 1.0f;
inline constexpr float kDefaultThreshold = 0.85f;
inline constexpr int kMinGhosts = 1;
inline constexpr int kMaxGhosts = 8;
inline constexpr float kMinRayLength = 0.1f;
inline constexpr float kMaxRayLength = 1.0f;
inline constexpr float kMinRayDecay = 0.8f;
inline constexpr float kMaxRayDecay = 0.99f;
inline constexpr int kMinSpokes = 2;
inline constexpr int kMaxSpokes = 8;
inline constexpr float kMinStreakLength = 0.1f;
inline constexpr float kMaxStreakLength = 1.0f;
inline constexpr float kMinFocus = 0.05f;
inline constexpr float kMaxFocus = 0.95f;
inline constexpr float kMinParticleCount = 0.25f;
inline constexpr float kMaxParticleCount = 2.0f;
inline constexpr float kMinParticleSpeed = 0.1f;
inline constexpr float kMaxParticleSpeed = 3.0f;
inline constexpr float kMinParticleSize = 0.3f;
inline constexpr float kMaxParticleSize = 3.0f;
inline constexpr float kMaxTint = 2.0f;

// Ghosts of the bright light mirrored through the frame's centre, with a halo ring and a chromatic
// fringe.
struct Flare {
    float amount = 0.0f;
    int ghosts = 4;
    float halo = 0.5f;
    float dispersion = 0.5f;
};

// Light streaming out from a source (screen position, 0..1 from the bottom left) past whatever is
// dark: the march toward the source covers `length` of the way, each step keeping `decay`.
struct GodRays {
    float amount = 0.0f;
    float x = 0.5f;
    float y = 0.5f;
    float length = 0.6f;
    float decay = 0.94f;
};

// Star: `spokes` rays out of every bright light. Zoom: the light smeared along the line from the
// frame's centre, as flight smears it. Anamorphic: a long horizontal line through it.
enum class StreakShape : int { Star = 0, Zoom = 1, Anamorphic = 2 };

struct Streaks {
    float amount = 0.0f;
    StreakShape shape = StreakShape::Star;
    int spokes = 4;
    float length = 0.5f;
};

// Focus is a depth in the alpha's own units (depthAlpha(), 0 at the eye, toward 1 far away); the
// aperture, 0..1, scales the circle of confusion up to kDofMaxRadius.
struct DepthOfField {
    float aperture = 0.0f;
    float focus = 0.5f;
};

struct LensSpec {
    // The light (encoded, the brightest channel) the glare gathers from; the chain uses the lower of
    // this and the bloom's own threshold while any glare is on.
    float threshold = kDefaultThreshold;
    Flare flare;
    GodRays godRays;
    Streaks streaks;
    DepthOfField depthOfField;

    // Whether any of flare, god rays or streaks is declared.
    bool glare() const { return flare.amount > 0.0f || godRays.amount > 0.0f || streaks.amount > 0.0f; }
};

// Streaks: warp-speed lines. Sparks: short bright glints with short tails.
enum class ParticleKind : int { Streaks = 0, Sparks = 1 };

struct ParticleSpec {
    float amount = 0.0f;
    ParticleKind kind = ParticleKind::Streaks;
    // Multipliers of the tier's particle count, the flight speed and the streak width.
    float count = 1.0f;
    float speed = 1.0f;
    float size = 1.0f;
    std::array<float, 3> tint{1.0f, 1.0f, 1.0f};
};

// Every value held to its range; a value that is not a number takes its default.
LensSpec sanitized(const LensSpec& spec);
ParticleSpec sanitized(const ParticleSpec& spec);

// ---- the frame -------------------------------------------------------------------------------------

// The live sound the effects follow, each 0..1: the level (loudness) and the hit envelope.
struct Drive {
    float level = 0.0f;
    float hit = 0.0f;
};

// How fast a Style's effects fade in or out when the Style changes, or a tier turns them on or off.
inline constexpr float kStyleFadeSeconds = 0.6f;
// The music's swell: up over this long at the quickest, back down slower. No faster than a swell.
inline constexpr float kSwellRiseSeconds = 0.25f;
inline constexpr float kSwellFallSeconds = 0.9f;
// The glare's amount swings from kGlareRest of the declared amount in silence to
// kGlareRest + kGlareSwing at full swell.
inline constexpr float kGlareRest = 0.75f;
inline constexpr float kGlareSwing = 0.5f;
// Reduced motion halves how far zoom and star streaks reach.
inline constexpr float kReducedMotionStreak = 0.5f;

// Samples per pixel at each thermal tier, coolest first: 0 means the tier has switched it off.
inline constexpr std::array<int, 4> kRaySamples = {24, 16, 0, 0};
inline constexpr std::array<int, 4> kStreakSamples = {8, 6, 0, 0};
inline constexpr std::array<int, 4> kDofSamples = {24, 16, 0, 0};
// The largest circle of confusion, as a fraction of the scene target's height.
inline constexpr float kDofMaxRadius = 0.01f;
// The nearest depth the circle of confusion is worked out at (depthAlpha() never writes 0 itself).
inline constexpr float kMinDepth = 0.02f;

// The samples a tier draws (0 off): a tier outside 0..3 counts as the hottest; nothing runs while
// the tier has dropped the optional passes.
int raySamples(int tier, bool optionalPasses);
int streakSamples(int tier, bool optionalPasses);
int dofSamples(int tier, bool optionalPasses);

// What lens_frag.glsl draws this frame.
struct Glare {
    float threshold = kDefaultThreshold;
    float flare = 0.0f;
    int ghosts = 4;
    float halo = 0.5f;
    float dispersion = 0.5f;
    float rays = 0.0f;
    float rayX = 0.5f;
    float rayY = 0.5f;
    float rayLength = 0.6f;
    float rayDecay = 0.94f;
    int raySamples = 0;
    float streaks = 0.0f;
    StreakShape streakShape = StreakShape::Star;
    int spokes = 4;
    float streakLength = 0.5f;
    int streakSamples = 0;

    // Whether the lens pass has anything to draw.
    bool on() const { return raySamples > 0 && (flare > 0.0f || rays > 0.0f || streaks > 0.0f); }
};

// The threshold the bloom chain gathers above: the lower of the bloom's and the lens's while the
// glare is on, the bloom's alone otherwise (the chain is then exactly the bloom it always was).
float chainThreshold(float bloomThreshold, const Glare& glare);

// The bloom's share of the chain's light at one texel: the chain carries the light above the chain
// threshold in rgb (luminance `chainLuma`) and the luminance of the light above the bloom's own
// threshold in alpha (`bloomLuma`). The bloom is the rgb scaled by this share, in 0..1; with no glare
// the two are equal and the share is 1. tonemap_frag.glsl mirrors it.
inline constexpr float kShareEpsilon = 1e-4f;
float bloomShare(float chainLuma, float bloomLuma);

// The circle of confusion of a texel at `depth`, as a fraction of kDofMaxRadius, 0..1: the aperture
// times |1/depth - 1/focus| times focus, so a texel at half the focal depth is at the full radius
// with the aperture fully open. dof_frag.glsl mirrors it.
float circleOfConfusion(float depth, float focus, float aperture);

// The depth a shader Style writes into its alpha for depth of field: a hit at distance t from the eye,
// with the Style's subject at distance `scale`, as t / (t + scale) (0.5 at the subject; 1 is "far").
float depthAlpha(float t, float scale);

// What dof_frag.glsl draws this frame; off (0 samples) when the Style declares none or the tier says.
struct Dof {
    float aperture = 0.0f;
    float focus = 0.5f;
    int samples = 0;

    bool on() const { return samples > 0 && aperture > 0.0f; }
};
// Depth of field belongs to the picture it focuses, so it does not fade across Styles: it is the
// active Style's, as its tier allows.
Dof dofFor(const LensSpec& spec, int tier, bool optionalPasses);

// A value moved toward `target` by at most `step`; it holds on a step that is not a positive number.
float approach(float current, float target, float step);
// A value gliding toward `target`, at the same rate per second whatever the frame rate: up with
// time constant `riseSeconds`, down with `fallSeconds`. It holds on a dt that is not positive.
float glide(float current, float target, float riseSeconds, float fallSeconds, float dt);

// The glare for each frame: the Style's amounts faded in and out, swung by the music's swell.
class LensDrive {
public:
    Glare step(const LensSpec& spec, const Drive& drive, int tier, bool optionalPasses, bool reducedMotion, float dt);
    float swell() const { return swell_; }
    void reset();

private:
    float swell_ = 0.0f;
    float flare_ = 0.0f;
    float rays_ = 0.0f;
    float streaks_ = 0.0f;
};

// ---- the particles ---------------------------------------------------------------------------------

// The particles at count 1 on the coolest tier, the most any Style can ask for, and each tier's share.
inline constexpr int kBaseParticles = 900;
inline constexpr int kMaxParticles = 1800;
inline constexpr std::array<float, 4> kParticleTierShare = {1.0f, 0.75f, 0.5f, 0.3f};
// The camera-space slab the particles fly through, in units of the focal length (the frame's half
// height at depth 1): they come in at kFarDepth and leave at kNearDepth.
inline constexpr float kNearDepth = 0.5f;
inline constexpr float kFarDepth = 12.5f;
inline constexpr float kDepthSpan = kFarDepth - kNearDepth;
// The flight phase wraps at a whole number of spans, so the field never jumps.
inline constexpr float kPhaseWrap = kDepthSpan * 100.0f;
// Depth units flown a second at speed 1: kSpeedRest of it in silence, plus kSpeedSwing at full swell.
inline constexpr float kFlightSpeed = 5.0f;
inline constexpr float kSpeedRest = 0.35f;
inline constexpr float kSpeedSwing = 1.3f;
inline constexpr float kReducedMotionSpeed = 0.4f;
// How many of the particles are lit: kEmissionRest of the amount in silence, all of it at full swell.
inline constexpr float kEmissionRest = 0.4f;
// A hit lifts the swell the particles follow by this share of it (a spark burst, never a flash).
inline constexpr float kHitShare = 0.6f;
inline constexpr float kParticleRiseSeconds = 0.2f;
inline constexpr float kParticleFallSeconds = 0.7f;
// The shutter that stretches each particle along its flight, in seconds.
inline constexpr float kStreakShutter = 0.05f;
inline constexpr float kSparkShutter = 0.012f;

// How many particles a tier draws for a Style asking for `count` (a multiplier, held to its range).
int particleCount(float count, int tier);

// What the particle pass draws this frame.
struct Particles {
    int count = 0;
    ParticleKind kind = ParticleKind::Streaks;
    // How many of them are lit (0..1), and how bright.
    float emission = 0.0f;
    // The distance flown (wraps at kPhaseWrap), and the depth each particle's tail trails its head by.
    float phase = 0.0f;
    float tail = 0.0f;
    float size = 1.0f;
    std::array<float, 3> tint{1.0f, 1.0f, 1.0f};

    bool on() const { return count > 0 && emission > 0.0f; }
};

class ParticleFlight {
public:
    Particles step(const ParticleSpec& spec, const Drive& drive, int tier, bool reducedMotion, float dt);
    float phase() const { return phase_; }
    float speed() const { return speed_; }
    void reset();

private:
    float swell_ = 0.0f;
    float amount_ = 0.0f;
    float phase_ = 0.0f;
    float speed_ = 0.0f;
};

}  // namespace geode::viz::lens
