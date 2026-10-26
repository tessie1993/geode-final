#pragma once
#include <array>
#include <cstdint>
#include <limits>

namespace geode::viz {

// The No-flash rule (CONTEXT.md): the one limiter every finished frame of the Visual passes
// through, after the composite pass and so after every Style, MilkDrop included, and after every
// Reaction, Modulator and Automation. It has no switch.
//
// It follows WCAG 2.3.1. A flash is a pair of opposing transitions:
//   - general flash: relative luminance changes by at least 10% of the maximum, and the darker
//     state is below 0.80;
//   - red flash: the saturated-red value (R - G - B) * 320, over pixels whose red share
//     R / (R + G + B) is at least 0.8, changes by at least 20.
// At most three flashes may happen in any one second at any place. The limiter measures the whole
// frame and every quarter-of-the-frame region of a coarse grid, and gives each of them, for each
// kind of flash, its own one-second budget of six transitions. While any budget is spent, it
// crossfades the new frame toward the one shown before, in linear light, by just enough that no
// area whose budget is spent completes another transition. Normal motion, which changes an area
// at most a few times a second, never reaches a budget and passes untouched.
//
// Pure: the GL side (NoFlashPass) reads the grid back from the GPU and does the crossfade.
namespace noflash {

constexpr int kGridColumns = 4;
constexpr int kGridRows = 4;
constexpr int kCellCount = kGridColumns * kGridRows;
// A region is kRegionCells x kRegionCells grid cells, a quarter of the frame, slid one cell at a time.
constexpr int kRegionCells = 2;
constexpr int kRegionColumns = kGridColumns - kRegionCells + 1;
constexpr int kRegionRows = kGridRows - kRegionCells + 1;
constexpr int kRegionCount = kRegionColumns * kRegionRows;
// The whole frame first, then the regions row by row.
constexpr int kAreaCount = 1 + kRegionCount;
constexpr int kWholeFrame = 0;

constexpr int kMaxFlashesPerSecond = 3;
constexpr int kTransitionsPerFlash = 2;
constexpr int kMaxTransitionsPerWindow = kMaxFlashesPerSecond * kTransitionsPerFlash;
constexpr double kWindowSeconds = 1.0;

constexpr float kGeneralFlashDelta = 0.1f;
constexpr float kGeneralFlashDarkLimit = 0.8f;
constexpr float kRedFlashScale = 320.0f;
constexpr float kRedFlashThreshold = 20.0f;
constexpr float kRedFlashDelta = kRedFlashThreshold / kRedFlashScale;
constexpr float kSaturatedRedShare = 0.8f;
constexpr float kNoDarkLimit = std::numeric_limits<float>::infinity();

// How far inside a transition threshold a damped frame is held, so rounding cannot complete one.
constexpr float kBandMargin = 0.002f;

// Relative luminance (WCAG) of a linear-light colour.
constexpr float kLumaRed = 0.2126f;
constexpr float kLumaGreen = 0.7152f;
constexpr float kLumaBlue = 0.0722f;

// The readback layout: one RGBA8 texel per cell, red channel the mean saturated-red value, green
// the mean relative luminance, rows bottom up as glReadPixels returns them.
constexpr int kReadbackChannels = 4;
constexpr int kReadbackBytes = kCellCount * kReadbackChannels;
constexpr int kReadbackRedChannel = 0;
constexpr int kReadbackLumaChannel = 1;
constexpr float kByteMax = 255.0f;

float srgbToLinear(float encoded);
float relativeLuminance(float r, float g, float b);
// (R - G - B) of a saturated red in linear light, 0 for every other colour.
float saturatedRed(float r, float g, float b);

}  // namespace noflash

// Mean linear measurements per grid cell, row major from the bottom row.
struct LumaGrid {
    std::array<float, noflash::kCellCount> luminance{};
    std::array<float, noflash::kCellCount> red{};

    static LumaGrid uniform(float luminance, float red = 0.0f);
    static LumaGrid fromReadback(const std::array<uint8_t, noflash::kReadbackBytes>& rgba);
};

// One measured value over time: reports when it completes a WCAG transition, that is a change of
// at least `delta` against the last transition's direction, measured from the extreme it has
// reached since. A rise only counts from below `darkLimit`, a fall only to below it.
class TransitionTracker {
public:
    enum class Direction : int8_t { None, Rise, Fall };

    TransitionTracker(float delta, float darkLimit) : delta_(delta), darkLimit_(darkLimit) {}
    void reset(float value);
    // True when `value` completes a transition.
    bool feed(float value);
    // The next value completes no transition while it stays strictly between floor() and ceiling().
    float ceiling() const;
    float floor() const;
    Direction last() const { return last_; }

private:
    float delta_;
    float darkLimit_;
    float low_ = 0.0f;
    float high_ = 0.0f;
    Direction last_ = Direction::None;
};

// The transitions one area made inside the trailing window.
class TransitionWindow {
public:
    void reset();
    void record(double time);
    void dropBefore(double cutoff);
    int count() const { return count_; }
    bool spent() const { return count_ >= noflash::kMaxTransitionsPerWindow; }

private:
    // Room for every transition a budget allows plus the ones rounding might still let through.
    static constexpr int kCapacity = noflash::kMaxTransitionsPerWindow * 2;

    std::array<double, kCapacity> times_{};
    int head_ = 0;
    int count_ = 0;
};

class FlashLimiter {
public:
    FlashLimiter();
    void reset();
    // Takes the candidate frame's grid and the time since the last frame, and returns how much of
    // the candidate to show: 1 shows it as it is, less crossfades from the frame shown before
    // toward it (in linear light), 0 holds the frame shown before.
    float step(float dt, const LumaGrid& candidate);
    // The most transitions any one area and kind has inside the trailing one-second window.
    int transitionsInWindow() const;
    // What the limiter believes is on screen after the last step.
    const LumaGrid& shown() const { return shown_; }

    static void areaMeans(const std::array<float, noflash::kCellCount>& cells, std::array<float, noflash::kAreaCount>& out);

private:
    float safeAmount(const LumaGrid& candidate) const;
    static float amountWithin(const TransitionTracker& tracker, float shown, float candidate);
    void feedShown();

    std::array<TransitionTracker, noflash::kAreaCount> general_;
    std::array<TransitionTracker, noflash::kAreaCount> red_;
    std::array<TransitionWindow, noflash::kAreaCount> generalWindows_{};
    std::array<TransitionWindow, noflash::kAreaCount> redWindows_{};
    LumaGrid shown_;
    double now_ = 0.0;
    bool primed_ = false;
};

// When the No-flash pass shows each candidate, now that its measurement reaches the CPU a frame
// late. The GPU measures every candidate into a pixel-pack buffer behind a fence; reading it back in
// the same frame would stall the pipeline until the GPU caught up. So a candidate waits one frame
// as the pending frame, and is limited and shown in the next, when its 16 texels have long arrived.
// The limiter's logic is unchanged: it still sees every frame it lets through, in order, each with
// the time since the one before, and decides before anything of that frame reaches the screen.
//
// After a reset nothing is pending and nothing has been shown, so the first candidate is measured
// and shown at once (one wait, then never again). The next frame has no pending frame to show yet,
// so it shows the last frame again (a held frame is never a flash) while its own candidate becomes
// the pending one. Synchronous mode (the offscreen Render, where a stall costs nothing and every
// frame must be the one asked for) always measures and shows at once, dropping a pending frame: the
// limiter then judges the new candidate against what was really shown, with the dropped frame's
// time added to its own.
class DelayedPresent {
public:
    enum class Action : uint8_t {
        // Wait for this candidate's measurement, limit it and show it.
        MeasureAndShow,
        // Limit and show the pending frame; this candidate becomes the pending one.
        ShowPending,
        // Show the last frame again; this candidate becomes the pending one.
        HoldShown,
    };
    struct Step {
        Action action = Action::MeasureAndShow;
        // The time since the last frame the limiter saw, for the frame this step limits.
        float dt = 0.0f;
        // A pending frame was dropped unshown (its slot is free again).
        bool droppedPending = false;
    };

    void reset();
    // A new candidate has been drawn `dt` after the one before.
    Step onCandidate(float dt, bool synchronous);
    bool hasPending() const { return pending_; }
    bool hasShown() const { return shown_; }

private:
    bool shown_ = false;
    bool pending_ = false;
    // The pending frame's time since the frame before it; read only while one is pending.
    float pendingDt_ = 0.0f;
};

}  // namespace geode::viz
