#pragma once
#include <atomic>
#include <cstdint>
#include <optional>

namespace geode::audio::player {

// What the BufferTuner needs from an output stream, and nothing else. Output adapts the Oboe stream to it;
// the host tests use a fake. Both calls run on the audio callback thread, so an implementation must not
// allocate, lock or block.
class TunableStream {
public:
    virtual ~TunableStream() = default;
    // Underruns since the stream opened, or nullopt when the device does not report them.
    virtual std::optional<int32_t> xrunCount() = 0;
    // Asks for a new buffer size; returns the size the device actually applied, or nullopt when it refused.
    virtual std::optional<int32_t> setBufferSizeInFrames(int32_t frames) = 0;
};

// Self-tuning output buffer. It starts at the device's best size (kMinBursts bursts), grows one burst after
// an underrun, and eases back one burst once the stream has been quiet for the stable period. Hysteresis:
// growing is immediate but shrinking waits, and an underrun that follows a shrink doubles the wait before
// the next one (up to kMaxStableMs), so the size does not flap between two steps. No user setting.
//
// Time is counted in rendered frames, not read from a clock, so onAudioReady() makes no system calls.
// reset() runs on the engine thread while the stream is stopped; onAudioReady() runs on the audio thread;
// sizeFrames() may be read from any thread.
class BufferTuner {
public:
    // The smallest buffer, in bursts: also where every stream starts.
    static constexpr int32_t kMinBursts = 2;
    // The largest buffer, in bursts (further capped by the stream's capacity).
    static constexpr int32_t kMaxBursts = 8;
    // How far one grow or shrink moves the buffer, in bursts.
    static constexpr int32_t kStepBursts = 1;
    // After a grow, further underruns within this window are treated as the same glitch.
    static constexpr int32_t kGrowCooldownMs = 250;
    // How long the stream must run without an underrun before the buffer eases back one step.
    static constexpr int32_t kStableMs = 10'000;
    // The longest the stable period can back off to after underruns that follow a shrink.
    static constexpr int32_t kMaxStableMs = 160'000;
    // Multiplier applied to the stable period when a shrink is followed by an underrun.
    static constexpr int32_t kStableBackoffFactor = 2;

    enum class Setup { Ready, InvalidStreamGeometry, Rejected };

    BufferTuner() = default;
    BufferTuner(const BufferTuner&) = delete;
    BufferTuner& operator=(const BufferTuner&) = delete;

    // Applies the starting size to the stream and arms the tuner. On anything but Ready the tuner stays
    // inactive and onAudioReady() leaves the stream alone.
    Setup reset(TunableStream& stream, int32_t framesPerBurst, int32_t capacityFrames, int32_t sampleRate);

    // Call once per audio callback with the frames just rendered. Real-time safe.
    void onAudioReady(TunableStream& stream, int32_t numFrames);

    // The buffer size last applied, in frames; 0 while inactive. Safe from any thread (diagnostics).
    int32_t sizeFrames() const { return sizeFrames_.load(std::memory_order_relaxed); }

    bool active() const { return active_; }
    int32_t minFrames() const { return minFrames_; }
    int32_t maxFrames() const { return maxFrames_; }
    int64_t stablePeriodFrames() const { return stableFrames_; }

private:
    void apply(TunableStream& stream, int32_t frames);

    bool active_ = false;
    int32_t burst_ = 0;
    int32_t minFrames_ = 0;
    int32_t maxFrames_ = 0;
    int64_t growCooldownFrames_ = 0;
    int64_t baseStableFrames_ = 0;
    int64_t maxStableFrames_ = 0;
    int64_t stableFrames_ = 0;
    int64_t framesSinceGrow_ = 0;
    int64_t framesQuiet_ = 0;
    std::optional<int32_t> lastXruns_;
    bool lastChangeWasShrink_ = false;
    std::atomic<int32_t> sizeFrames_{0};
};

}  // namespace geode::audio::player
