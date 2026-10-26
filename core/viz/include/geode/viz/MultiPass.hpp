#pragma once
#include <array>
#include <cstdint>

namespace geode::viz::multipass {

// Multi-pass Styles (#332): a shader Style that declares "multipass" in its pack (docs/style-packs.md)
// is drawn by the engine in passes, so a heavy raymarched world fits a mid-range phone. The Style's
// folder stays one manifest and one shader (ADR-0004): the engine runs that one shader once per pass,
// telling it which pass it is in (uMpPass, lib_multipass.glsl), and owns everything between.
//
//   1. CAMERA: a 5 x 1 target where the Style writes its camera (where it stands, its three axes and
//      its focal length) and returns before it marches. Kept one frame, it is how the resolve knows
//      where last frame's picture now lies.
//   2. COARSE: the Style marches at kCoarseDivisor times below the march size with a wide cone and
//      writes only how far each ray got. Each march ray starts a little short of the nearest of its
//      coarse neighbours instead of at the camera (mpStart).
//   3. MARCH: the Style draws its picture at `scale` of the target, each frame offset by a sub-pixel
//      jitter (jitter()), with its depth in alpha (mpDepth: t / (t + kDepthK)).
//   4. RESOLVE: the engine's own shader (multipass_resolve_frag.glsl) brings it to the full target:
//      a depth-aware upscale (no light bleeds across a depth edge), then last frame's resolved picture
//      reprojected through both cameras and accumulated. History is rejected where the depth it kept
//      disagrees with where the point now is (something came out from behind) and clamped to the
//      colours around the pixel this frame, so a fast camera move never leaves a ghost; the faster a
//      pixel moved, the more of this frame it takes.
//
// The passes need a half-float target (the depth in alpha, the camera's numbers): where the scene
// targets are not half float the Style draws in one pass at the target's size, as any other Style.
// What the resolve keeps is a convex mix of this frame's own colours, so it never brightens a pixel
// past its neighbourhood (the No-flash rule), and the jitter and history restart with each visit, so a
// Render gives the same frames every time.

inline constexpr float kMinScale = 0.25f;
inline constexpr float kMaxScale = 0.75f;
inline constexpr float kDefaultScale = 0.5f;
// The coarse prepass is this many times smaller than the march, each way.
inline constexpr int kCoarseDivisor = 4;
// The camera pass's texels: the position's whole part, its fraction, then right (with the focal
// length in alpha), up and forward.
inline constexpr int kCameraTexels = 5;
// The whole part of the position is kept modulo this (half floats hold whole numbers exactly to 2048),
// and the resolve unwraps the difference between two frames' positions with it.
inline constexpr float kPositionWrap = 1024.0f;
// The jitter walks this many Halton points, then starts again.
inline constexpr std::uint32_t kJitterCycle = 8;
// lib_multipass's depth encoding: alpha = t / (t + kDepthK), 1 for nothing hit.
inline constexpr float kDepthK = 4.0f;
// The share of a pixel last frame's resolved picture keeps when nothing moved.
inline constexpr float kHistoryWeight = 0.9f;
// A camera that moved further than this in one frame (world units) cut: the history is dropped.
inline constexpr float kMaxCameraStep = 4.0f;
// The frame counter wraps well inside what a float holds exactly.
inline constexpr std::uint32_t kFrameWrap = 1u << 24;

// What a Style declares under "multipass": the march's share of the target, and whether the coarse
// prepass and the temporal accumulation run. `on` is false when the manifest does not declare it.
struct Spec {
    bool on = false;
    float scale = kDefaultScale;
    bool coarse = true;
    bool temporal = true;

    bool operator==(const Spec&) const = default;
};

// `spec` with its scale held to [kMinScale, kMaxScale] (the default for one that is not finite).
Spec sanitized(Spec spec);

struct Size {
    int width = 1;
    int height = 1;
};

// The passes one frame of a Style runs, for a draw target of the given size.
struct Plan {
    // False: the Style draws in one pass straight into the target, at its size.
    bool passes = false;
    bool coarse = false;
    // The camera pass and the resolve's use of the history.
    bool temporal = false;
    Size march;
    Size coarseSize;
};

// The pass sizes, never below 1 x 1: the march at `scale` of the target (rounded up), the coarse
// prepass kCoarseDivisor times below the march (rounded up, so it covers every march pixel).
Size marchSize(int width, int height, float scale);
Size coarseSize(Size march);

// What runs this frame: nothing extra when the Style does not declare multipass, the target is empty or
// the scene targets are not half float (`halfFloat`).
Plan plan(const Spec& spec, int width, int height, bool halfFloat);

// The radical inverse of `index` in `base` (the Halton sequence): in [0, 1), 0 for index 0.
float halton(std::uint32_t index, std::uint32_t base);

// This frame's sub-pixel offset of the march, in march pixels, each in [-0.5, 0.5): the Halton (2, 3)
// points 1 to kJitterCycle, centred. Over one cycle they cover the pixel evenly, so the target pixels
// between two march pixels are each sampled in turn and the accumulation fills them in.
std::array<float, 2> jitter(std::uint32_t frame);

// The march's share of the pixels a single-pass Style of the same target would march: scale squared,
// plus the coarse prepass's own. The resolve is a few texture reads a pixel, and the camera pass five
// pixels, both left out.
float marchShare(const Spec& spec);

// Whether last frame's resolved picture can be read: never on a visit's first frame, after a resize,
// or after a frame whose resolve did not run.
class History {
public:
    // Before the resolve: true when the kept picture is last frame's at this size. Notes the size.
    bool begin(int width, int height);
    // After a resolve kept its picture: the next frame may read it, and the frame counter moves on.
    void kept();
    // Nothing is kept (a frame drawn without the passes): the next frame starts afresh.
    void lost();
    // A new visit: no history, and the jitter starts its cycle again.
    void reset();
    std::uint32_t frame() const { return frame_; }

private:
    bool valid_ = false;
    int width_ = 0;
    int height_ = 0;
    std::uint32_t frame_ = 0;
};

}  // namespace geode::viz::multipass
