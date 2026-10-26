#include "geode/audio/feed/FeedTap.hpp"

#include <algorithm>

namespace geode::audio::feed {

namespace {

constexpr double kNsPerSecond = 1e9;

}  // namespace

FeedTap::Take FeedTap::take(const FeedRing& ring, int64_t nowNs, float* out, size_t maxFrames) {
    Take result;
    const ReadPoint point = ring.locate(nowNs);
    const bool heard = point.live && point.rateHz > 0;
    if (!started_) {
        started_ = true;
        epoch_ = point.epoch;
        cursor_ = point.end;
        hearing_ = heard;
        if (point.rateHz > 0) rate_ = point.rateHz;
        silentFromNs_ = nowNs;
        result.rateHz = rate_;
        return result;
    }
    if (out == nullptr || maxFrames == 0) {
        result.rateHz = rate_;
        return result;
    }
    if (!heard) {
        if (hearing_) {
            // It just went quiet: the silence starts now.
            hearing_ = false;
            silentFromNs_ = nowNs;
        }
        result.frames = silence(nowNs, out, maxFrames);
        result.rateHz = rate_;
        return result;
    }
    if (!hearing_ || point.epoch != epoch_ || point.rateHz != rate_) restart(point, nowNs, ring);
    // Whatever the writer has already overwritten is gone: go on from the oldest frame still there.
    cursor_ = std::max(cursor_, ring.oldestReadable());
    if (point.end > cursor_) {
        const uint64_t available = point.end - cursor_;
        const size_t frames = available < maxFrames ? static_cast<size_t>(available) : maxFrames;
        ring.copyRange(cursor_, frames, out);
        cursor_ += frames;
        result.frames = frames;
    }
    result.rateHz = rate_;
    return result;
}

void FeedTap::restart(const ReadPoint& point, int64_t nowNs, const FeedRing& ring) {
    const bool sameStream = point.epoch == epoch_ && point.rateHz == rate_;
    // A source that resumes in the same epoch goes on where the tap stopped, if that is still there;
    // anything else starts over at the newest frame heard.
    const bool goOn = sameStream && cursor_ >= ring.oldestReadable() && cursor_ <= point.end;
    if (!goOn) cursor_ = point.end;
    epoch_ = point.epoch;
    rate_ = point.rateHz;
    hearing_ = true;
    silentFromNs_ = nowNs;
}

size_t FeedTap::silence(int64_t nowNs, float* out, size_t maxFrames) {
    const int64_t gap = nowNs - silentFromNs_;
    if (gap <= 0) return 0;
    const double owed = static_cast<double>(gap) * rate_ / kNsPerSecond;
    const size_t whole = static_cast<size_t>(owed);
    const size_t frames = std::min(whole, maxFrames);
    std::fill_n(out, frames * FeedRing::kChannels, 0.0f);
    silentFromNs_ += static_cast<int64_t>(static_cast<double>(frames) * kNsPerSecond / rate_);
    return frames;
}

}  // namespace geode::audio::feed
