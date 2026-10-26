#pragma once
#include <array>
#include <cstdint>

namespace geode::viz::bufferpass {

// A Style's own buffer passes (#387): a shader Style that declares "buffers" in its pack
// (docs/style-packs.md) is drawn as up to four buffer passes, A to D, then its image pass, the way
// multi-buffer shader art is written. Each buffer pass is its own fragment shader in the Style's folder
// and draws into a buffer the engine keeps from frame to frame; every pass, the image's included, reads
// the buffers (lib_buffers.glsl's uBufA..uBufD) and the shared inputs the pack declares.
//
// The passes run in order, A first, and every pass reads each buffer's newest picture: a buffer drawn
// earlier this frame shows this frame's, the buffer being drawn and the ones after it show last frame's,
// and the image pass sees every buffer as this frame left it. That is the order the art expects, and it
// needs nothing but two targets a buffer, ping-ponged: a pass reads the one holding the newest picture and
// draws into the other (Chain).
//
// Each buffer is kept at a share of the scene target's size, its "scale", in half float where the device
// renders it. It follows the target's size keeping what it holds (ADR-0005), starts black at each visit
// with the frame counter at 0, and so a Render, which draws every frame on its own clock from a fresh
// visit, gives the same frames every time. What a buffer keeps is the Style's own state, not light on
// screen: only the image pass's output is shown, and it goes through the No-flash limiter like any other.

inline constexpr int kMaxBuffers = 4;
// A buffer's share of the scene target, each way.
inline constexpr float kMinScale = 0.25f;
inline constexpr float kMaxScale = 1.0f;
inline constexpr float kDefaultScale = 1.0f;
// lib_buffers' uBufPass in the image pass; a buffer pass is its index, 0 (A) to 3 (D).
inline constexpr float kImagePass = 4.0f;
// The frame counter (uBufFrame) wraps well inside what a float holds exactly.
inline constexpr std::uint32_t kFrameWrap = 1u << 24;
// The samplers each buffer is read by, A to D (lib_buffers.glsl).
inline constexpr std::array<const char*, kMaxBuffers> kSamplers = {"uBufA", "uBufB", "uBufC", "uBufD"};

// What a Style declares under "buffers": how many buffer passes it runs and each one's share of the
// target. `count` 0 is a Style without buffers.
struct Spec {
    int count = 0;
    std::array<float, kMaxBuffers> scale{kDefaultScale, kDefaultScale, kDefaultScale, kDefaultScale};

    bool operator==(const Spec&) const = default;
};

// `spec` with its count held to [0, kMaxBuffers] and every scale to [kMinScale, kMaxScale] (the default
// for one that is not finite).
Spec sanitized(Spec spec);

struct Size {
    int width = 1;
    int height = 1;

    bool operator==(const Size&) const = default;
};

// A buffer's size for a scene target of width x height: `scale` of each side (held to its range),
// rounded up, never below 1 x 1.
Size bufferSize(int width, int height, float scale);

// The texture unit buffer `index` is read on, the buffers taking the units from `firstUnit` up.
int unitOf(int index, int firstUnit);

// Whether `count` buffers from `firstUnit` up stay below `units` (the units every device has).
bool fits(int count, int firstUnit, int units);

// Whether pass `reader` (0 to count - 1 a buffer pass, count or more the image pass) sees buffer
// `buffer` as drawn this frame (true) or as last frame left it (false): the buffers drawn before it.
bool readsThisFrame(int reader, int buffer);

// The two targets of each buffer and which of them holds its newest picture, plus the frame counter.
class Chain {
public:
    explicit Chain(int count = 0);

    int count() const { return count_; }
    // The target (0 or 1) holding buffer `index`'s newest picture: what every pass reads it from.
    int latest(int index) const;
    // The target buffer `index` draws into this frame: the other one.
    int target(int index) const { return 1 - latest(index); }
    // Buffer `index` drew into its target: that is now its newest picture.
    void drawn(int index);
    // Every pass of the frame ran: the frame counter moves on.
    void endFrame();
    // A new visit: the buffers start black in target 0 and the counter at 0.
    void reset();
    std::uint32_t frame() const { return frame_; }

private:
    bool valid(int index) const { return index >= 0 && index < count_; }

    int count_ = 0;
    std::array<int, kMaxBuffers> latest_{};
    std::uint32_t frame_ = 0;
};

}  // namespace geode::viz::bufferpass
