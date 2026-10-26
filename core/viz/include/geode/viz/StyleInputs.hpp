#pragma once
#include <array>
#include <cstdint>
#include <optional>
#include <string_view>
#include <vector>

namespace geode::viz::inputs {

// The shared Style inputs (#312): what MilkDrop's shared shader include gives every preset, for Geode's
// shader Styles. Seeded noise textures in three smoothness levels, a noise volume and the blue-noise
// mask; blur levels of the Style's last frame at 1/4, 1/8 and 1/16 of its size, made with the bloom
// chain's own down filter (HdrPass); the last frame itself; and clocks: the frame index, the frame's
// dt, a random vector drawn once per visit to the Style, the Style's age and the bar clock.
//
// A Style declares the inputs it reads in its manifest ("inputs", docs/style-packs.md) and the engine
// makes and binds only those: an input no Style declares is never made, and one the drawing Style does
// not declare costs it nothing. The shader reads them through lib_inputs.glsl. The noise is seeded
// from the Render seed and the visit random vector from it too while a Render is made, so a Render
// gives the same frames every time (ADR-0005).

enum class Input : std::uint8_t {
    NoiseLq,        // uNoiseLq: white noise, one value per texel
    NoiseMq,        // uNoiseMq: smooth value noise, a lattice point every kMqLattice texels
    NoiseHq,        // uNoiseHq: smoother, a lattice point every kHqLattice texels
    NoiseVolume,    // uNoiseVol: a kNoiseVolumeSize^3 volume of white noise (lib_inputs' vnoiseVol, fbmVol)
    BlueNoise,      // uBlueNoise: the 64x64 blue-noise mask the tonemap dithers with
    Blur1,          // uBlur1: the last frame blurred at 1/4 size
    Blur2,          // uBlur2: at 1/8
    Blur3,          // uBlur3: at 1/16
    PreviousFrame,  // uPrevFrame: the last frame the Style drew (with feedback: carried through the warp, FeedbackWarp.hpp)
    Clocks,         // uFrame, uFrameDt, uVisitRand, uStyleAge
    Bar,            // uBarPhase, uBarConfidence
};

inline constexpr int kInputCount = 11;
// Each input's name in a manifest, in Input's order.
inline constexpr std::array<std::string_view, kInputCount> kInputNames = {
    "noise_lq", "noise_mq", "noise_hq", "noise_vol", "blue_noise", "blur1", "blur2", "blur3", "previous_frame", "clocks", "bar",
};

// The inputs a Style declares: bit i set for Input i.
using Set = std::uint32_t;

constexpr Set bit(Input input) { return 1u << static_cast<unsigned>(input); }
constexpr bool has(Set set, Input input) { return (set & bit(input)) != 0; }

// The input a manifest names `name`; nullopt for a name that is none of them.
std::optional<Input> named(std::string_view name);

// ---- noise ----------------------------------------------------------------------------------------

// The Render seed every noise texture is drawn from, live and in a Render alike.
inline constexpr std::uint32_t kNoiseSeed = 0x6E0DE5u;
// The 2D noise textures are kNoiseSize square, RGBA8, four independent channels, and tile.
inline constexpr int kNoiseSize = 256;
inline constexpr int kMqLattice = 4;
inline constexpr int kHqLattice = 8;
// The noise volume is kNoiseVolumeSize on every side, RGBA8, and tiles.
inline constexpr int kNoiseVolumeSize = 32;

// One lattice value: a 32-bit hash of the seed, the channel and the cell. Every bit of every argument
// reaches every bit of the result (lib_inputs' noise is only as good as this).
std::uint32_t hashCell(std::uint32_t seed, int channel, int x, int y, int z);
// The spacing of noise input `input`'s lattice in texels: 1 for the white noise and the volume,
// kMqLattice and kHqLattice for the smooth levels; 0 for an input that is no noise texture.
int lattice(Input input);
// A size x size RGBA8 plane of value noise with a lattice point every `lattice` texels, smoothstep
// interpolated between them and wrapping at the edges so the texture tiles; each channel its own
// noise. Empty unless size > 0, lattice > 0 and the lattice divides the size.
std::vector<std::uint8_t> noisePlane(std::uint32_t seed, int size, int lattice);
// A size^3 RGBA8 volume of white noise, x fastest, then y, then z. Empty unless size > 0.
std::vector<std::uint8_t> noiseVolume(std::uint32_t seed, int size);

// ---- the history: previous frame and blur levels ----------------------------------------------------

inline constexpr int kBlurLevels = 3;

// True when the Style keeps any of its last frame: the previous frame or a blur level.
bool needsHistory(Set set);
// How many levels of the bloom's mip chain (tone::bloomLevels: level 0 at 1/2, each next half the one
// before) the declared blur levels need. Blur k (1..3) is chain level k, so 1/4, 1/8 and 1/16; level 0
// is the step between. 0 when no blur level is declared, else 1 + the deepest one declared.
int blurChainLength(Set set);
// The chain level blur `level` (1..kBlurLevels) is read from when only `built` levels could be made
// (a small target has fewer): its own level, else the deepest one built; -1 when none was.
int blurSource(int level, int built);

// ---- clocks ---------------------------------------------------------------------------------------

// uFrame counts the frames of a visit and wraps here, the largest count a float holds exactly.
inline constexpr std::uint32_t kFrameWrap = 1u << 24;
// uStyleAge stops here: seconds, an hour into the visit.
inline constexpr float kStyleAgeMax = 3600.0f;

// The seed a Style's visit random vectors are drawn from: `base` (the Render seed in a Render, any
// live entropy otherwise) mixed with the Style's id, so two Styles never draw the same vectors.
std::uint32_t visitSeed(std::uint32_t base, std::string_view styleId);
// The random vector of visit number `visit`: four values in [0, 1).
std::array<float, 4> visitRandom(std::uint32_t seed, std::uint32_t visit);

// uFrame, uFrameDt, uVisitRand and uStyleAge for one Style. A visit begins when the Style starts to be
// drawn (it became Layer 1 or Layer 2); its frame count and age start from 0 and it draws a new
// random vector.
class VisitClock {
public:
    void beginVisit(std::uint32_t seed);
    // One frame drawn: dt is the frame's, in seconds; a broken one (negative, not finite) counts as 0.
    void step(float dt);

    std::uint32_t frame() const { return frame_; }
    float dt() const { return dt_; }
    float age() const { return age_; }
    std::uint32_t visits() const { return visits_; }
    const std::array<float, 4>& random() const { return random_; }

private:
    std::uint32_t frame_ = 0;
    float dt_ = 0.0f;
    float age_ = 0.0f;
    std::uint32_t visits_ = 0;
    std::array<float, 4> random_{};
};

// uBarPhase and uBarConfidence: where the music is in its bar, 0..1, as a clock that always runs
// forward and never jumps. It runs at the tempo the analysis hears (four beats a bar, BarTracker) and,
// while the analysis is sure enough of the downbeat, pulls itself onto the bar it hears; a tracker
// that changes its mind moves the clock's pace for a moment, never the clock. With nothing heard it
// runs on at the last tempo. The confidence follows the analysis' slowly, so a Style can fade a
// bar-locked motion in and out with it.
class BarClock {
public:
    static constexpr float kBeatsPerBar = 4.0f;
    // 120 bpm until a tempo is heard.
    static constexpr float kDefaultBarsPerSecond = 0.5f;
    // The tempos it follows: 40 to 240 bpm.
    static constexpr float kMinBarsPerSecond = 40.0f / 60.0f / kBeatsPerBar;
    static constexpr float kMaxBarsPerSecond = 240.0f / 60.0f / kBeatsPerBar;
    // Below this downbeat confidence the heard bar is not followed.
    static constexpr float kLockConfidence = 0.3f;
    // How fast the clock closes the gap to the heard bar.
    static constexpr float kLockHz = 1.5f;
    // The clock's pace stays between these shares of the tempo while it catches up or waits.
    static constexpr float kMinPace = 0.5f;
    static constexpr float kMaxPace = 2.0f;
    static constexpr float kConfidenceHz = 1.0f;

    // One frame of dt seconds: the analysis' bar phase (0..1), its downbeat confidence and its tempo.
    void step(float barPhase, float confidence, float bpm, float dt);
    void reset();

    float phase() const { return phase_; }
    float confidence() const { return confidence_; }
    float barsPerSecond() const { return rate_; }

private:
    float phase_ = 0.0f;
    float confidence_ = 0.0f;
    float rate_ = kDefaultBarsPerSecond;
};

}  // namespace geode::viz::inputs
