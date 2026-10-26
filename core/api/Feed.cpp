#include "Feed.hpp"

#include <algorithm>
#include <chrono>

#include "geode/analysis/Decimator.hpp"

namespace geode::api {

namespace {

// How often the analysis looks for new audio. It sets latency only: every hop the ring received since
// the last look is analysed then. Writers never wake it, since the audio callback must not signal.
constexpr auto kLookInterval = std::chrono::milliseconds(5);

}  // namespace

Feed::Feed()
    : analysis_(geode_analysis_create(kInitialRateHz, kFftSize, kHopRateHz)),
      scratch_(kPushFrames * audio::feed::FeedRing::kChannels, 0.0f),
      worker_([this] { run(); }) {}

Feed::~Feed() {
    {
        std::lock_guard<std::mutex> guard(wakeLock_);
        quit_.store(true, std::memory_order_release);
    }
    wake_.notify_all();
    worker_.join();
    std::lock_guard<std::mutex> guard(analysisLock_);
    geode_analysis_destroy(analysis_);
    analysis_ = nullptr;
}

int64_t Feed::nowNs() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

void Feed::setActive(bool active) {
    if (active_.exchange(active, std::memory_order_acq_rel) == active) return;
    {
        std::lock_guard<std::mutex> guard(analysisLock_);
        // What was analysed before nobody watched is stale by the time someone does again: none of it
        // may be drawn, and the analysis starts again just before what is heard now.
        if (active) {
            resetPending_ = true;
            wakePending_ = true;
            history_.clear();
        }
    }
    // Taken and dropped so a worker between its check and its wait cannot miss the wake-up.
    { std::lock_guard<std::mutex> guard(wakeLock_); }
    wake_.notify_all();
}

void Feed::setTuning(float sensitivity, float refractoryMs, float attackSeconds, float releaseSeconds) {
    std::lock_guard<std::mutex> guard(analysisLock_);
    geode_analysis_set_tuning(analysis_, sensitivity, refractoryMs, attackSeconds, releaseSeconds);
}

void Feed::resetAnalysis() {
    std::lock_guard<std::mutex> guard(analysisLock_);
    resetPending_ = true;
}

void Feed::attachSnapshots(geode_snapshots* snapshots) {
    std::lock_guard<std::mutex> guard(analysisLock_);
    geode_analysis_attach_snapshots(analysis_, snapshots);
}

void Feed::run() {
    const auto quitting = [this] { return quit_.load(std::memory_order_acquire); };
    while (!quitting()) {
        {
            std::unique_lock<std::mutex> guard(wakeLock_);
            if (active_.load(std::memory_order_acquire)) {
                wake_.wait_for(guard, kLookInterval, quitting);
            } else {
                // Nobody watches: sleep until someone does, rather than waking for nothing.
                wake_.wait(guard, [this, &quitting] { return quitting() || active_.load(std::memory_order_acquire); });
            }
        }
        if (quitting()) break;
        analyse();
    }
}

void Feed::analyse() {
    std::lock_guard<std::mutex> guard(analysisLock_);
    if (!analysis_) return;
    const uint64_t written = ring_.written();
    if (!active_.load(std::memory_order_acquire)) {
        cursor_ = written;
        return;
    }
    const uint32_t epoch = ring_.epoch();
    const uint64_t begin = ring_.begin();
    if (resetPending_ || epoch != analysedEpoch_) {
        // A new source, a seek or a new rate: the analysis starts over from the oldest audio of the epoch
        // the ring still holds, so a source that runs ahead of the speaker has frames for what is heard now.
        geode_analysis_reset(analysis_);
        // Frames from before the reset count their events from another start; none may be drawn again.
        history_.clear();
        resetPending_ = false;
        analysedEpoch_ = epoch;
        if (wakePending_) {
            const int rate = ring_.rateHz() > 0 ? ring_.rateHz() : kInitialRateHz;
            const auto lookback = static_cast<uint64_t>(kFftSize + static_cast<int>(rate * kWakeLookbackSeconds));
            cursor_ = ring_.resumeFrom(nowNs(), lookback);
        } else {
            cursor_ = ring_.oldestReadable();
        }
        wakePending_ = false;
    }
    const int rate = ring_.rateHz();
    if (rate <= 0) return;
    if (rate != analysisRate_) {
        geode_analysis_set_sample_rate(analysis_, rate);
        analysisRate_ = rate;
    }
    cursor_ = std::max(cursor_, ring_.oldestReadable());
    // A hi-res source is decimated before the analysis, so one window spans that many more of its frames.
    const uint64_t half = static_cast<uint64_t>(kFftSize) * static_cast<uint64_t>(analysis::decimationFor(rate)) / 2;
    GeodeFeatureFrame frame{};
    while (cursor_ < written) {
        const size_t n = static_cast<size_t>(std::min<uint64_t>(written - cursor_, kPushFrames));
        ring_.copyRange(cursor_, n, scratch_.data());
        geode_analysis_push(analysis_, scratch_.data(), n, audio::feed::FeedRing::kChannels);
        cursor_ += n;
        // Stamped with the centre of the window it describes, which is when it is heard.
        const uint64_t stamp = std::max(begin, cursor_ > half ? cursor_ - half : 0);
        while (geode_analysis_pull(analysis_, &frame) == 1) history_.publish(epoch, stamp, frame);
    }
}

bool Feed::read(GeodeFeatureFrame& features, float* stereo, size_t frames, int64_t presentNs, audio::feed::ReadPoint* heard) {
    const int64_t now = nowNs();
    const audio::feed::ReadPoint point = ring_.locate(now, audio::feed::heard::presentAheadNs(now, presentNs));
    if (heard) *heard = point;
    ring_.copy(point, stereo, frames);
    if (!point.live || !history_.at(point.epoch, point.end, features)) {
        features = GeodeFeatureFrame{};
        return point.live;
    }
    return true;
}

}  // namespace geode::api
