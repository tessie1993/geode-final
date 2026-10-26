#include "geode/audio/feed/FeedRing.hpp"

#include <algorithm>
#include <cmath>

namespace geode::audio::feed {

namespace {

constexpr double kNsPerSecond = 1e9;

size_t roundUp(size_t n) {
    size_t p = 1;
    while (p < n) p <<= 1;
    return p;
}

}  // namespace

FeedRing::FeedRing(size_t capacityFrames)
    : capacity_(roundUp(std::max(capacityFrames, 4 * kMaxWriteFrames))),
      mask_(capacity_ - 1),
      samples_(capacity_ * kChannels, 0.0f) {
    for (size_t i = 0; i < kSources; ++i) {
        running_[i].store(true, std::memory_order_relaxed);
        delayNs_[i].store(0, std::memory_order_relaxed);
        speed_[i].store(1.0f, std::memory_order_relaxed);
        measured_[i].store(true, std::memory_order_relaxed);
    }
}

size_t FeedRing::indexOf(FeedSource source) {
    const int i = static_cast<int>(source);
    return i > 0 && i < static_cast<int>(kSources) ? static_cast<size_t>(i) : 0;
}

void FeedRing::startEpoch() {
    begin_.store(written_.load(std::memory_order_acquire), std::memory_order_release);
    packetNs_.store(kInitialPacketNs, std::memory_order_relaxed);
    measureFresh_.store(true, std::memory_order_relaxed);
    epoch_.fetch_add(1, std::memory_order_acq_rel);
}

void FeedRing::select(FeedSource source) {
    if (active_.exchange(static_cast<int>(source), std::memory_order_acq_rel) == static_cast<int>(source)) return;
    startEpoch();
}

void FeedRing::flush(FeedSource source) {
    if (indexOf(source) == 0 || selected() != source) return;
    startEpoch();
}

void FeedRing::setRunning(FeedSource source, bool running) {
    const size_t i = indexOf(source);
    if (i == 0) return;
    const bool was = running_[i].exchange(running, std::memory_order_acq_rel);
    // A resume follows a gap the source explained: it is not a packet interval.
    if (running && !was) measureFresh_.store(true, std::memory_order_relaxed);
}

void FeedRing::setPresentation(FeedSource source, int64_t delayNs, float speed, bool measured) {
    const size_t i = indexOf(source);
    if (i == 0) return;
    delayNs_[i].store(std::clamp<int64_t>(delayNs, 0, kMaxDelayNs), std::memory_order_relaxed);
    speed_[i].store(std::isfinite(speed) && speed > 0.0f ? speed : 1.0f, std::memory_order_relaxed);
    measured_[i].store(measured, std::memory_order_relaxed);
}

void FeedRing::setRoute(OutputRoute route) {
    route_.store(static_cast<int>(route), std::memory_order_relaxed);
}

void FeedRing::setGainDb(float db) {
    const float clamped = std::isfinite(db) ? std::clamp(db, kMinGainDb, kMaxGainDb) : 0.0f;
    gain_.store(std::pow(10.0f, clamped / 20.0f), std::memory_order_relaxed);
}

void FeedRing::setUserOffset(int64_t offsetNs) {
    userOffsetNs_.store(heard::clampUserOffsetNs(offsetNs), std::memory_order_relaxed);
}

size_t FeedRing::write(FeedSource source, const float* interleaved, size_t frames, int channels, int rateHz,
                       int64_t nowNs) {
    if (!interleaved || frames == 0 || channels <= 0 || rateHz <= 0 || indexOf(source) == 0) return 0;
    // Checked before taking the flag too, so a source nobody listens to never holds up the one listened to.
    if (selected() != source) return 0;
    if (writing_.test_and_set(std::memory_order_acquire)) return 0;
    if (selected() != source) {
        writing_.clear(std::memory_order_release);
        return 0;
    }
    if (rate_.load(std::memory_order_relaxed) != rateHz) {
        rate_.store(rateHz, std::memory_order_release);
        startEpoch();
    }
    const int64_t last = lastWriteNs_.load(std::memory_order_relaxed);
    if (measureFresh_.exchange(false, std::memory_order_relaxed)) {
        packetNs_.store(kInitialPacketNs, std::memory_order_relaxed);
    } else {
        const int64_t gap = std::max<int64_t>(nowNs - last, 0);
        const int64_t packet = packetNs_.load(std::memory_order_relaxed);
        // Rises at once to a longer gap, so jitter never expires live audio; falls back slowly.
        const int64_t next = gap > packet ? std::min(gap, kMaxPacketNs) : packet - (packet - gap) / 8;
        packetNs_.store(next, std::memory_order_relaxed);
    }
    uint64_t w = written_.load(std::memory_order_relaxed);
    const size_t step = static_cast<size_t>(channels);
    size_t done = 0;
    while (done < frames) {
        const size_t n = std::min(frames - done, kMaxWriteFrames);
        for (size_t i = 0; i < n; ++i) {
            const float* in = interleaved + (done + i) * step;
            float* out = samples_.data() + ((w + i) & mask_) * kChannels;
            out[0] = in[0];
            out[1] = channels >= 2 ? in[1] : in[0];
        }
        w += n;
        done += n;
        lastWriteNs_.store(nowNs, std::memory_order_relaxed);
        written_.store(w, std::memory_order_release);
    }
    writing_.clear(std::memory_order_release);
    return frames;
}

ReadPoint FeedRing::locate(int64_t nowNs, int64_t aheadNs) const {
    ReadPoint point;
    const FeedSource source = selected();
    const size_t i = indexOf(source);
    if (i == 0) return point;
    point.epoch = epoch_.load(std::memory_order_acquire);
    point.begin = begin_.load(std::memory_order_acquire);
    const uint64_t w = written_.load(std::memory_order_acquire);
    point.rateHz = rate_.load(std::memory_order_acquire);
    point.end = std::max(w, point.begin);
    if (!running_[i].load(std::memory_order_acquire) || w <= point.begin || point.rateHz <= 0) return point;
    // What sits between the write point and the speaker: the source's own delay, and what its route adds
    // when the source could not measure that delay.
    const int64_t unmeasured = measured_[i].load(std::memory_order_relaxed) ? 0 : heard::routeFallbackNs(route());
    const int64_t buffered = delayNs_[i].load(std::memory_order_relaxed) + unmeasured;
    const int64_t age = std::max<int64_t>(nowNs - lastWriteNs_.load(std::memory_order_relaxed), 0);
    if (age > packetNs_.load(std::memory_order_relaxed) + buffered) return point;
    point.live = true;
    const int64_t offset = source == FeedSource::Player ? userOffsetNs_.load(std::memory_order_relaxed) : 0;
    const int64_t delay = buffered + offset;
    if (delay <= 0) return point;
    // The newest frame minus what sits between the write point and the speaker, moved on by the time
    // since that write (and on to the moment asked for) at the speed the frames are heard. Never past
    // the newest frame, never further back than the ring safely holds.
    const double framesPerSecond = point.rateHz * static_cast<double>(speed_[i].load(std::memory_order_relaxed));
    const double maxBehind = static_cast<double>(capacity_ - 2 * kMaxWriteFrames);
    const double behind = std::min(static_cast<double>(delay) * framesPerSecond / kNsPerSecond, maxBehind);
    const double ahead = static_cast<double>(age + std::max<int64_t>(aheadNs, 0)) * framesPerSecond / kNsPerSecond;
    const double heard = static_cast<double>(w) - behind + ahead;
    const double first = static_cast<double>(point.begin);
    point.end = heard >= static_cast<double>(w) ? w : heard <= first ? point.begin : static_cast<uint64_t>(heard);
    return point;
}

uint64_t FeedRing::oldestReadable() const {
    const uint64_t w = written_.load(std::memory_order_acquire);
    // A write under way may already have overwritten up to kMaxWriteFrames frames past the published count.
    const uint64_t reach = w + kMaxWriteFrames;
    const uint64_t safe = reach > capacity_ ? reach - capacity_ : 0;
    return std::max(safe, begin_.load(std::memory_order_acquire));
}

size_t FeedRing::copyRange(uint64_t from, size_t frames, float* out) const {
    if (!out || frames == 0) return 0;
    const uint64_t w = written_.load(std::memory_order_acquire);
    const float gain = gain_.load(std::memory_order_relaxed);
    for (size_t i = 0; i < frames; ++i) {
        const uint64_t index = from + i;
        float* dst = out + i * kChannels;
        if (index >= w) {
            dst[0] = 0.0f;
            dst[1] = 0.0f;
            continue;
        }
        const float* src = samples_.data() + (index & mask_) * kChannels;
        dst[0] = src[0] * gain;
        dst[1] = src[1] * gain;
    }
    // Checked after the copy: anything the writer reached while it ran is not trusted.
    const uint64_t after = written_.load(std::memory_order_acquire) + kMaxWriteFrames;
    const uint64_t safe = after > capacity_ ? after - capacity_ : 0;
    size_t carried = 0;
    for (size_t i = 0; i < frames; ++i) {
        const uint64_t index = from + i;
        if (index < safe) {
            out[i * kChannels] = 0.0f;
            out[i * kChannels + 1] = 0.0f;
        } else if (index < w) {
            ++carried;
        }
    }
    return carried;
}

size_t FeedRing::copy(const ReadPoint& point, float* out, size_t frames) const {
    if (!out || frames == 0) return 0;
    std::fill_n(out, frames * kChannels, 0.0f);
    if (!point.live || point.end <= point.begin) return 0;
    // The window ends at point.end; the part of it before the epoch began stays silent.
    const uint64_t span = point.end - point.begin;
    const size_t audible = span < frames ? static_cast<size_t>(span) : frames;
    const size_t lead = frames - audible;
    return copyRange(point.end - audible, audible, out + lead * kChannels);
}

}  // namespace geode::audio::feed
