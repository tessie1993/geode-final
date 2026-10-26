#pragma once
#include <array>
#include <cstddef>
#include <cstdint>

namespace geode::viz::milkdrop {

// How MilkDrop follows load and time (ADR-0005, "MilkDrop adapts in two steps" and "Renders are
// frame-exact"). Pure: MilkdropScene feeds it frame intervals and applies what it decides to
// projectM.

struct MeshSize {
    int columns;
    int rows;
    bool operator==(const MeshSize&) const = default;
};

// The warp-mesh detail, finest first. Changing it is safe at any time: projectM rebuilds the mesh
// on the next frame without touching the picture.
inline constexpr std::array<MeshSize, 4> kMeshLadder = {{{48, 32}, {40, 28}, {32, 24}, {24, 18}}};

// The engine's buffer size as a fraction of the window size, largest first. A resize starts the
// picture over, so a new step waits for the next preset change, which blends from the old picture
// anyway.
inline constexpr std::array<float, 3> kBufferScaleLadder = {1.0f, 0.85f, 0.7f};

// The seed a Render's engine draws every random value from, so the same input gives the same frames.
inline constexpr uint32_t kRenderSeed = 0x6e0de;

// The frame rate projectM is told (presets read it as `fps`).
inline constexpr int kDefaultEngineFps = 60;
inline constexpr int kMinEngineFps = 1;
inline constexpr int kMaxEngineFps = 240;

// The paced display rate when the display is paced, else the measured rate, else the default.
int engineFps(float pacedFps, float measuredFps);

// A Render's frame rate, from the render clock's frame interval.
int renderFps(float frameSeconds);

// projectM's beat sensitivity for a Style's beat response (tools/projectm-v4.1.8-loudness.patch: how far
// bass, mid and treble swing away from 1.0). 1, the default, leaves a preset reacting as it was
// written; 0 holds it still; 2, the most, doubles every swing. Not a number reads as 1.
inline constexpr float kMaxBeatSensitivity = 2.0f;
float beatSensitivity(float beatResponse);

// The audio projectM analyses each frame: one window of kWindowFrames stereo frames, of which it
// measures only the oldest 480. At 60 fps and below that leaves part of the music unmeasured, and a
// kick can fall in the gap, so each frame hands projectM the loudest window that ends in the audio
// heard since the last frame rather than simply the newest one: kicks are never skipped, at the cost of
// at most one frame interval, and the reaction no longer depends on the refresh rate.
inline constexpr int kWindowFrames = 576;

// The first frame of the chosen window in `stereo` (`frames` interleaved stereo frames, newest last):
// of the windows of `window` frames that end within the newest `fresh` frames (or the newest window
// alone when fresh is 0), the one with the most energy, the newest of equals. 0 when there is no room
// for a whole window.
int loudestWindow(const float* stereo, int frames, int window, int fresh);

// How many frames of audio were heard between two Visual frames, from where each one's audio ended: 0
// unless both heard something, in the same epoch of the feed, and the second ended later; never more
// than `most`.
int heardSince(bool wasLive, uint32_t wasEpoch, uint64_t wasEnd, bool live, uint32_t epoch, uint64_t end, int most);

// The engine buffer for a window at a buffer scale; never below one pixel a side.
struct BufferSize {
    int width;
    int height;
    bool operator==(const BufferSize&) const = default;
};
BufferSize bufferSize(int windowWidth, int windowHeight, float scale);

// Follows the frame rate against its target and steps MilkDrop's detail down under a sustained
// deficit and back up after sustained headroom: the mesh first, then the buffer. Between the two
// thresholds it holds, so the detail does not flip back and forth.
class LoadAdapter {
public:
    // Frame rate the free-running display is held to (PerformanceMonitor's target).
    static constexpr float kFreeRunningTargetFps = 50.0f;
    // Below this fraction of the target the frame rate is falling behind.
    static constexpr float kBehindFraction = 0.85f;
    // At or above this fraction of the target there is room to spare.
    static constexpr float kHeadroomFraction = 0.97f;
    // How long a deficit lasts before a step down, and headroom before a step up.
    static constexpr float kStepDownSeconds = 2.5f;
    static constexpr float kStepUpSeconds = 10.0f;
    // The weight of each new frame in the averaged frame interval.
    static constexpr float kSmoothing = 0.1f;
    // Longer gaps are stalls (a trip to the background, a preset compile), not load.
    static constexpr float kMaxFrameSeconds = 0.25f;

    // One frame: its interval and the paced display rate (0 when free-running).
    void onFrame(float frameSeconds, float pacedFps);
    // A preset change: the buffer takes the pending step. True when the buffer scale changed.
    bool onPresetChange();
    // Full detail, no history: a new engine, or a Render, which never adapts.
    void reset();

    int meshStep() const { return meshStep_; }
    MeshSize mesh() const { return kMeshLadder[static_cast<size_t>(meshStep_)]; }
    float bufferScale() const { return kBufferScaleLadder[static_cast<size_t>(bufferStep_)]; }
    float pendingBufferScale() const { return kBufferScaleLadder[static_cast<size_t>(pendingBufferStep_)]; }
    bool bufferChangePending() const { return pendingBufferStep_ != bufferStep_; }
    // The averaged frame rate; 0 until a frame has been measured.
    float averageFps() const { return averageSeconds_ > 0.0f ? 1.0f / averageSeconds_ : 0.0f; }

private:
    static constexpr int kLastMeshStep = static_cast<int>(kMeshLadder.size()) - 1;
    static constexpr int kLastBufferStep = static_cast<int>(kBufferScaleLadder.size()) - 1;

    void stepDown();
    void stepUp();

    int meshStep_ = 0;
    int bufferStep_ = 0;
    int pendingBufferStep_ = 0;
    float averageSeconds_ = 0.0f;
    float behindSeconds_ = 0.0f;
    float headroomSeconds_ = 0.0f;
};

// A Render's time: the sum of the frame intervals the render clock handed out, from zero. The same
// frames give the same times, whatever the wall clock does.
class RenderClock {
public:
    void advance(float frameSeconds) { seconds_ += static_cast<double>(frameSeconds); }
    void reset() { seconds_ = 0.0; }
    double seconds() const { return seconds_; }

private:
    double seconds_ = 0.0;
};

}  // namespace geode::viz::milkdrop
