#pragma once
#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace geode::audio::feed {

// Who writes the Visual's audio: the player (Geode's own engine or ExoPlayer, whichever the session
// opened) or Live input (the microphone). Exactly one is listened to at a time.
enum class FeedSource : int { None = 0, Player = 1, Live = 2 };

// Where the Visual reads this frame. Frames are numbered from the first one the feed ever took.
struct ReadPoint {
    // False when nothing is heard: no source, a paused one, or one that went quiet for longer than its
    // packets and its buffered audio explain. The window is then silence.
    bool live = false;
    // One past the last frame heard now.
    uint64_t end = 0;
    // The first frame of this epoch: nothing before it came from the source listened to now.
    uint64_t begin = 0;
    uint32_t epoch = 0;
    int rateHz = 0;
};

// The Visual's one audio feed: a stereo ring stamped with frame indices, written by exactly one
// chosen source and read at the frame being heard.
//
// A source writes as it produces audio, which may be well ahead of the speaker (ExoPlayer's tap sits
// before half a second of AudioTrack buffer). It says how far ahead with setPresentation, and the
// reader backs off by that much, moving on with the wall clock between writes. A source that stops
// (a pause) says so with setRunning; one that just goes quiet expires once no audio has come for
// longer than its packet interval plus its buffered duration. Selecting another source, a flush (a
// seek dropped what was buffered) or a change of sample rate starts a new epoch, and the reader never
// reaches back across one.
//
// write() never blocks or allocates, so the native player's audio callback calls it directly. A write
// that finds another one under way is dropped rather than waited for; that happens only around a
// change of source. There is one reader for locate() and copy() at a time per caller; readers never
// write, so the Visual and the analysis may read concurrently.
class FeedRing {
public:
    static constexpr int kChannels = 2;
    // The most frames one write stores before publishing them; longer writes are split.
    static constexpr size_t kMaxWriteFrames = 4096;
    // The packet interval assumed until two writes have measured one.
    static constexpr int64_t kInitialPacketNs = 25'000'000;
    // A gap longer than this (a pause the source did not report) does not count as a packet interval.
    static constexpr int64_t kMaxPacketNs = 250'000'000;
    // The furthest a source may say it runs ahead of the speaker.
    static constexpr int64_t kMaxDelayNs = 2'000'000'000;

    // capacityFrames is rounded up to a power of two, and to at least 4 * kMaxWriteFrames.
    explicit FeedRing(size_t capacityFrames);

    // Control: any thread.
    void select(FeedSource source);
    FeedSource selected() const { return static_cast<FeedSource>(active_.load(std::memory_order_acquire)); }
    // The source threw away what it had buffered ahead (a seek): its audio starts over from here.
    void flush(FeedSource source);
    // Whether the source is playing; a paused source is silent at once, whatever it buffered.
    void setRunning(FeedSource source, bool running);
    // How far ahead of the speaker the source writes, and the speed its frames are heard at.
    void setPresentation(FeedSource source, int64_t delayNs, float speed);

    // Writers. Returns the frames stored: 0 when `source` is not the one listened to, when another write
    // is under way, or for an unusable format. Mono is heard on both channels; past two channels only
    // the first two are kept.
    size_t write(FeedSource source, const float* interleaved, size_t frames, int channels, int rateHz, int64_t nowNs);

    // Readers.
    ReadPoint locate(int64_t nowNs) const;
    // The `frames` frames ending at point.end, interleaved stereo. Frames outside the epoch, or that the
    // writer may already have overwritten, read as silence; a point that is not live reads all silence.
    // Returns how many frames carried audio.
    size_t copy(const ReadPoint& point, float* out, size_t frames) const;
    // Frames [from, from + frames) as copy() would give them, without regard to epoch or liveness.
    size_t copyRange(uint64_t from, size_t frames, float* out) const;
    // The oldest frame still safe to read in the current epoch.
    uint64_t oldestReadable() const;

    uint64_t written() const { return written_.load(std::memory_order_acquire); }
    uint64_t begin() const { return begin_.load(std::memory_order_acquire); }
    uint32_t epoch() const { return epoch_.load(std::memory_order_acquire); }
    int rateHz() const { return rate_.load(std::memory_order_acquire); }
    int64_t packetNs() const { return packetNs_.load(std::memory_order_acquire); }
    size_t capacity() const { return capacity_; }

private:
    static constexpr size_t kSources = 3;

    static size_t indexOf(FeedSource source);
    void startEpoch();

    size_t capacity_;
    size_t mask_;
    std::vector<float> samples_;
    std::atomic<int> active_{static_cast<int>(FeedSource::None)};
    std::atomic<uint32_t> epoch_{0};
    std::atomic<uint64_t> begin_{0};
    std::atomic<uint64_t> written_{0};
    std::atomic<int> rate_{0};
    std::atomic<int64_t> lastWriteNs_{0};
    std::atomic<int64_t> packetNs_{kInitialPacketNs};
    // Set when a new epoch or a resume means the next write has no previous write to measure from.
    std::atomic<bool> measureFresh_{true};
    std::atomic_flag writing_;
    std::array<std::atomic<bool>, kSources> running_{};
    std::array<std::atomic<int64_t>, kSources> delayNs_{};
    std::array<std::atomic<float>, kSources> speed_{};
};

}  // namespace geode::audio::feed
