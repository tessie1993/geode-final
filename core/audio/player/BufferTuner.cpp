#include "geode/audio/player/BufferTuner.hpp"

#include <algorithm>

namespace geode::audio::player {

namespace {

constexpr int64_t kMsPerSecond = 1000;

int64_t msToFrames(int32_t ms, int32_t sampleRate) {
    return static_cast<int64_t>(ms) * sampleRate / kMsPerSecond;
}

}  // namespace

BufferTuner::Setup BufferTuner::reset(TunableStream& stream, int32_t framesPerBurst, int32_t capacityFrames,
                                      int32_t sampleRate) {
    active_ = false;
    sizeFrames_.store(0, std::memory_order_relaxed);
    if (framesPerBurst <= 0 || capacityFrames <= 0 || sampleRate <= 0) return Setup::InvalidStreamGeometry;

    burst_ = framesPerBurst;
    maxFrames_ = std::min(framesPerBurst * kMaxBursts, capacityFrames);
    minFrames_ = std::min(framesPerBurst * kMinBursts, maxFrames_);
    growCooldownFrames_ = msToFrames(kGrowCooldownMs, sampleRate);
    baseStableFrames_ = msToFrames(kStableMs, sampleRate);
    maxStableFrames_ = msToFrames(kMaxStableMs, sampleRate);
    stableFrames_ = baseStableFrames_;
    framesSinceGrow_ = growCooldownFrames_;
    framesQuiet_ = 0;
    lastXruns_.reset();
    lastChangeWasShrink_ = false;

    const auto applied = stream.setBufferSizeInFrames(minFrames_);
    if (!applied) return Setup::Rejected;
    sizeFrames_.store(*applied, std::memory_order_relaxed);
    active_ = true;
    return Setup::Ready;
}

void BufferTuner::onAudioReady(TunableStream& stream, int32_t numFrames) {
    if (!active_) return;
    const auto xruns = stream.xrunCount();
    if (!xruns) return;

    const int64_t frames = std::max<int32_t>(numFrames, 0);
    framesSinceGrow_ += frames;
    framesQuiet_ += frames;

    // The first reading (or a counter that went backwards) is only a baseline.
    if (!lastXruns_ || *xruns < *lastXruns_) {
        lastXruns_ = xruns;
        return;
    }

    const int32_t size = sizeFrames_.load(std::memory_order_relaxed);
    if (*xruns > *lastXruns_) {
        lastXruns_ = xruns;
        framesQuiet_ = 0;
        if (framesSinceGrow_ < growCooldownFrames_) return;
        if (lastChangeWasShrink_) {
            stableFrames_ = std::min(stableFrames_ * kStableBackoffFactor, maxStableFrames_);
        }
        lastChangeWasShrink_ = false;
        framesSinceGrow_ = 0;
        if (size < maxFrames_) apply(stream, std::min(size + burst_ * kStepBursts, maxFrames_));
        return;
    }

    if (framesQuiet_ >= stableFrames_ && size > minFrames_) {
        framesQuiet_ = 0;
        lastChangeWasShrink_ = true;
        apply(stream, std::max(size - burst_ * kStepBursts, minFrames_));
    }
}

void BufferTuner::apply(TunableStream& stream, int32_t frames) {
    const auto applied = stream.setBufferSizeInFrames(frames);
    if (applied) sizeFrames_.store(*applied, std::memory_order_relaxed);
}

}  // namespace geode::audio::player
