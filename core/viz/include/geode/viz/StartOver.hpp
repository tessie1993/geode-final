#pragma once
#include <cstdint>

namespace geode::viz::startover {

// Start over (#400): the Style drawn begins again. A fresh copy of its scene is built, so its
// simulations reseed (Life, Fluid, Water, reaction-diffusion and the like) and its clocks, kept frames
// and feedback buffers start new, and the old copy fades out over it the way a change of Style does, in
// the plain crossfade: nothing is cut and nothing jumps (the No-flash rule). MilkDrop, whose one
// projectM engine cannot be built twice, loads its preset again from its file instead (#382), which
// blends as a change of preset does. Pure: the Renderer carries it out.

// What a Start over asks of the renderer on a frame.
enum class Step {
    // Nothing asked, or what was asked waits.
    None,
    // The Style drawn is MilkDrop: load its preset again.
    ReloadPreset,
    // Build the fresh copy now. Its fade starts on a later frame, so the build (its programs compiled
    // or read back from the cache) never lands in the fade's first frame, the most visible one (#304).
    Build,
    // Fade from the old copy to the fresh one.
    Fade,
};

// What the renderer is doing on the frame it asks.
struct Frame {
    // A Style is drawn.
    bool drawing = false;
    // That Style is MilkDrop.
    bool milkdrop = false;
    // A change of Style, or an earlier Start over, is under way or waits for its downbeat.
    bool changing = false;
    // The fresh copy is built.
    bool built = false;
};

// One Start over at a time: asked for from any number of taps, carried out once, after any change of
// Style under way has finished. Asked with nothing drawn, it is forgotten.
class Request {
public:
    void ask() { asked_ = true; }
    // Forgets what was asked: the fresh copy could not be built, or the scenes were let go.
    void drop() { asked_ = false; }
    bool asked() const { return asked_; }
    // One display frame: what to do now. ReloadPreset and Fade end the request; Build keeps it.
    Step step(const Frame& frame);

private:
    bool asked_ = false;
};

// The fade's length, in seconds: the person's length for a change of Style (`changeMs`), held between
// these, so a Start over never snaps and never drags.
inline constexpr float kMinFadeSeconds = 1.0f;
inline constexpr float kMaxFadeSeconds = 3.0f;
float fadeSeconds(int64_t changeMs);

}  // namespace geode::viz::startover
