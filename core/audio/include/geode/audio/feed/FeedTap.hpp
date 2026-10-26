#pragma once
#include <cstddef>
#include <cstdint>

#include "geode/audio/feed/FeedRing.hpp"

namespace geode::audio::feed {

// What a Recording hears (#317): the feed's audio as the listener hears it, handed over in order and
// once. Each take gives the stereo frames heard since the last one, at the point the Visual reads, so
// the sound lines up with the picture drawn from it. While nothing is heard (a pause, no source) it
// gives silence for the time that passed, so the Recording's sound never falls behind its picture.
// A seek, a change of source or a change of rate starts over at the newest frame heard; a source that
// resumes in the same epoch goes on from where the tap stopped.
//
// One thread takes. A take never blocks or allocates.
class FeedTap {
public:
    // The rate silence is counted at until the feed has had one.
    static constexpr int kFallbackRateHz = 48000;

    struct Take {
        // Stereo frames written to `out`.
        size_t frames = 0;
        // The rate they are at.
        int rateHz = 0;
    };

    // Up to maxFrames stereo frames into `out` (interleaved, 2 * maxFrames floats). The first take only
    // marks where the Recording starts and gives nothing.
    Take take(const FeedRing& ring, int64_t nowNs, float* out, size_t maxFrames);

    int rateHz() const { return rate_; }
    bool started() const { return started_; }

private:
    void restart(const ReadPoint& point, int64_t nowNs, const FeedRing& ring);
    size_t silence(int64_t nowNs, float* out, size_t maxFrames);

    bool started_ = false;
    bool hearing_ = false;
    uint64_t cursor_ = 0;
    uint32_t epoch_ = 0;
    int rate_ = kFallbackRateHz;
    // Where the silence still owed starts, while nothing is heard.
    int64_t silentFromNs_ = 0;
};

}  // namespace geode::audio::feed
