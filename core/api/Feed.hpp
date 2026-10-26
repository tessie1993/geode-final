#pragma once
#include <atomic>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <thread>
#include <vector>

#include "geode/api/geode_api.h"
#include "geode/audio/feed/FeatureHistory.hpp"
#include "geode/audio/feed/FeedRing.hpp"

namespace geode::api {

// The Visual's one native audio feed (geode_feed): the ring every source writes into, the analysis
// that runs natively off it on a thread of its own, and the read the drawing thread makes at the start
// of each frame. Nothing on the way from a source to the Visual crosses JNI or touches the main thread.
class Feed {
public:
    static constexpr size_t kCapacityFrames = size_t{1} << 17;
    static constexpr int kFftSize = 2048;
    static constexpr float kHopRateHz = 60.0f;
    // The frames handed to the analysis at a time: each pulled hop is stamped to within this many.
    static constexpr size_t kPushFrames = 256;
    // The analysis' rate until a source says otherwise.
    static constexpr int kInitialRateHz = 48000;
    // How far before the frame heard now an analysis that slept starts again: enough for its window and
    // a few hops, little enough to catch up within the next Visual frame.
    static constexpr float kWakeLookbackSeconds = 0.25f;

    Feed();
    ~Feed();
    Feed(const Feed&) = delete;
    Feed& operator=(const Feed&) = delete;

    audio::feed::FeedRing& ring() { return ring_; }

    // Any thread. The analysis runs only while someone watches; it starts over on waking.
    void setActive(bool active);
    void setTuning(float sensitivity, float refractoryMs, float attackSeconds, float releaseSeconds);
    void resetAnalysis();
    void attachSnapshots(geode_snapshots* snapshots);

    // The drawing thread: this frame's features and the `frames` stereo frames heard when the frame reaches
    // the screen, at presentNs on the steady clock (0 or less for now). Returns false, with an empty frame
    // and silence, when nothing is heard. `heard`, when given, gets where the window ends.
    bool read(GeodeFeatureFrame& features, float* stereo, size_t frames, int64_t presentNs = 0,
              audio::feed::ReadPoint* heard = nullptr);

    static int64_t nowNs();

private:
    void run();
    void analyse();

    audio::feed::FeedRing ring_{kCapacityFrames};
    audio::feed::FeatureHistory history_;
    std::mutex analysisLock_;
    geode_analysis* analysis_ = nullptr;
    int analysisRate_ = kInitialRateHz;
    uint32_t analysedEpoch_ = 0;
    bool resetPending_ = true;
    bool wakePending_ = false;
    uint64_t cursor_ = 0;
    std::vector<float> scratch_;
    std::atomic<bool> active_{false};
    std::atomic<bool> quit_{false};
    std::mutex wakeLock_;
    std::condition_variable wake_;
    std::thread worker_;
};

}  // namespace geode::api
