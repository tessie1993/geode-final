#pragma once
#include <cstdint>

namespace geode::audio::player {

// When to try reopening an output stream that disconnected (a route change, a device that went away),
// and when to stop trying and report an output error. Attempts back off, doubling from kFirstDelayMs
// up to kMaxDelayMs, and kMaxAttempts failures in a row use the retries up. Engine thread only.
class OutputRetry {
public:
    static constexpr int kMaxAttempts = 6;
    static constexpr int64_t kFirstDelayMs = 100;
    static constexpr int64_t kMaxDelayMs = 1000;

    // The output needs reopening from nowMs on; the first attempt is due at once.
    void begin(int64_t nowMs);
    // The output is open again, or no longer needed.
    void reset();
    bool pending() const { return pending_; }
    // Whether an attempt should be made at nowMs.
    bool due(int64_t nowMs) const { return pending_ && nowMs >= nextAtMs_; }
    // An attempt at nowMs failed. True when that used the retries up; the retry is then no longer pending.
    bool failed(int64_t nowMs);
    int attempts() const { return attempts_; }

private:
    bool pending_ = false;
    int attempts_ = 0;
    int64_t nextAtMs_ = 0;
};

}  // namespace geode::audio::player
