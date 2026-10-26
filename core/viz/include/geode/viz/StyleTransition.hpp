#pragma once
#include <array>
#include <cstdint>
#include <string>

#include "geode/api/geode_api.h"

namespace geode::viz::transition {

// How a change of Style follows the music (#309): it starts on the next downbeat when a tempo is
// heard (DownbeatGate), each kick moves it on a step (Progress), and it takes one of the looks below,
// the person's choice or a seeded draw. Pure: the Renderer draws it.

// Every look a change of Style may take, each one No-flash safe: the picture only ever moves from the
// outgoing Style to the incoming one, never through black, white or a flat colour, and never with a
// strobe of its own. Geode never hard-cuts. tools/glslcheck (--effects) draws each library look along
// its progress and fails one whose brightness leaves the span of its two pictures; the Settings
// picker (TransitionLook) offers exactly these, and NativeTransitionLooksKonsistTest keeps the two
// lists equal. The first four are the composite's own; the rest are GL Transitions (MIT), spliced in.
inline constexpr std::array<const char*, 17> kSafeLooks = {
    "fade",   "melt",       "slide",          "zoom",   "Dreamy", "crosswarp", "Swirl",
    "ripple", "WaterDrop",  "LinearBlur",     "directionalwarp", "Radial", "circleopen",
    "wind",   "perlin",     "morph",          "angular",
};
inline constexpr int kLookCount = static_cast<int>(kSafeLooks.size());
// The look a request for anything else takes: the plain crossfade.
inline constexpr int kFadeSlot = 0;
// A seeded draw from kSafeLooks for each change, never the same look twice in a row.
inline constexpr const char* kRandom = "random";

// The slot of `id` in kSafeLooks, or -1 when it is not one of them.
int slotOf(const std::string& id);
// Whether `id` is a look this engine draws for a change of Style: one of kSafeLooks, or kRandom.
bool isSafe(const std::string& id);
// `requested` when isSafe, and "fade" for anything else (a hard cut included).
std::string safeLook(const std::string& requested);
// The slot in kSafeLooks of the look a change of Style takes: `requested`'s own, the fade's for a look
// that is not safe, or for kRandom the change's draw from `seed` and its `index`, never `previous`
// (the slot the last change took, or -1 for none).
int lookSlot(const std::string& requested, uint64_t seed, uint32_t index, int previous);

// When a change of Style starts: on the next downbeat while the live analysis hears a tempo, and at
// once while it hears none. A downbeat the bar tracker misses is waited for no longer than a bar and
// a beat at the heard tempo, and never longer than kMaxWaitSeconds.
class DownbeatGate {
public:
    // How sure the tempo must be before a change waits for its downbeat: the analysis's own lock, as
    // the beat-timed Automations use it (PlanCodec).
    static constexpr float kTempoHeardConfidence = 0.4f;
    static constexpr float kBeatsPerBar = 4.0f;
    static constexpr float kMaxWaitSeconds = 3.0f;

    // A change was asked for; it starts at the first step() that says so.
    void arm();
    // The change is no longer wanted (the Style asked for is the one drawn again).
    void cancel() { waiting_ = false; }
    bool waiting() const { return waiting_; }
    // One display frame, with the frame's one-frame events (DisplayEvents); true on the frame the
    // change starts, after which the gate waits for nothing.
    bool step(const GeodeFeatureFrame& frame, float dt);

    static bool tempoHeard(const GeodeFeatureFrame& frame);
    // The longest a change waits for a downbeat at `bpm`.
    static float maxWait(float bpm);

private:
    bool waiting_ = false;
    float waited_ = 0.0f;
};

// How far a change of Style has got, 0 to 1. It runs on the clock over the change's length, so with no
// drums it is a steady crossfade that ends on time, and each kick moves it on a step ahead of the
// clock, never more than kMaxLead ahead. A step glides over kGlideSeconds rather than jumping, and the
// progress never goes back: the picture only ever moves from one Style towards the other, so no part
// of a change can make a pair of opposing brightness steps (the No-flash rule).
class Progress {
public:
    static constexpr float kKickStep = 0.1f;
    static constexpr float kMaxLead = 0.3f;
    static constexpr float kGlideSeconds = 0.12f;
    // The shortest a change may last, whatever it is asked for.
    static constexpr float kMinSeconds = 0.5f;

    void start(float seconds);
    // One display frame; `kick` is the frame's kick strength (0 for none). Returns progress().
    float step(float dt, float kick);
    float progress() const { return shown_; }
    bool done() const { return shown_ >= 1.0f; }
    float seconds() const { return seconds_; }

private:
    float seconds_ = 1.0f;
    float clock_ = 1.0f;
    float lead_ = 1.0f;
    float shown_ = 1.0f;
};

// The eased progress the composite draws: smootherstep, so a change starts and lands gently.
float ease(float progress);

// Which Customization the drawn Style wears while a change of Style is on its way. A Customization
// names the Style it is for (the plan's `style`); one for another Style than the one drawn is held,
// so the drawn Style keeps its own look while the change waits for its downbeat, and it is let go on
// the first frame its own Style is drawn. Since the Customization can reach the engine a little before
// the change of Style is asked for, a held one that no change is asked towards is let go after
// kRequestGraceSeconds: it is then for a Style that will not be drawn, and the drawn one takes it.
// Nothing is held longer than kMaxHoldSeconds, the longest a change waits for its downbeat and then
// some, so a change that can never start (a Style this engine cannot build) holds nothing for good.
// Styles are layer::styleHash names; 0 names none.
class LookHold {
public:
    static constexpr float kRequestGraceSeconds = 0.5f;
    static constexpr float kMaxHoldSeconds = 4.0f;

    // A new Customization for `style` came while `drawn` is drawn: true when it applies now, false
    // when it is held (in place of any held before it).
    bool offer(uint32_t style, uint32_t drawn);
    // One display frame while one is held, with the Style drawn and the one asked for now: true on the
    // frame the held Customization applies, after which nothing is held.
    bool step(uint32_t drawn, uint32_t requested, float dt);
    bool holding() const { return holding_; }

private:
    bool holding_ = false;
    uint32_t style_ = 0;
    float unasked_ = 0.0f;
    float held_ = 0.0f;
};
static_assert(LookHold::kMaxHoldSeconds > DownbeatGate::kMaxWaitSeconds + LookHold::kRequestGraceSeconds);

}  // namespace geode::viz::transition
