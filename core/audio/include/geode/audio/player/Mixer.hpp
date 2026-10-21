#pragma once
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <mutex>
#include <vector>

#include "geode/api/geode_api.h"
#include "geode/audio/player/Deck.hpp"
#include "geode/audio/player/SpscRing.hpp"

namespace geode::audio::player {

struct DeckCommand {
    enum class Op : uint8_t { SetCurrent, SetNext, ClearNext };
    Op op;
    Deck* deck;
};

// A dsp chain setDsp() swapped out, waiting to be destroyed once it is confirmed the audio thread can no
// longer be inside geode_dsp_process() with it. See Mixer::setDsp / Mixer::reclaimDsp.
struct RetiredDsp {
    geode_dsp* dsp = nullptr;
    bool waitForCallback = false;  // true if the stream was running at swap time
    uint64_t readyAt = 0;          // safe once callbacks_ has advanced past this value
};

// Drains the current deck, joins the next one gaplessly or through a crossfade, then applies volume, the
// DSP chain and the analysis tap. render() runs on the audio thread and never blocks or allocates; the
// engine thread reaches it through the command ring and takes retired decks back from the retired ring.
class Mixer {
public:
    static constexpr int kChannels = 2;
    static constexpr size_t kMaxRenderFrames = 4096;

    Mixer();
    // Force-destroys any dsp chain still waiting in retiredDsp_ (e.g. the audio thread never got to
    // confirm it moved past one, such as during a stalled shutdown). Only safe because by this point
    // the audio thread is guaranteed gone: Player::~Player() joins the engine thread, and the engine
    // thread only stops after Output::close(), which itself only returns once Oboe's stream is closed
    // and can no longer call render().
    ~Mixer();

    // Engine thread.
    bool post(DeckCommand command) { return commands_.push(&command, 1) == 1; }
    Deck* takeRetired();
    // Only while no audio callback can run (stream paused, stopped or closed).
    void drainCommands() { applyCommands(); }
    void setCrossfade(int64_t frames, int curve);
    // Publishes the new chain and queues the previous one (if any) for destruction; never blocks the
    // caller. The previous chain is destroyed by reclaimDsp(), once the audio thread can no longer be
    // using it, not by setDsp()'s caller. See Mixer.cpp for the reasoning and the PR notes for the
    // geode_player_set_dsp doc text this implies.
    void setDsp(geode_dsp* dsp, bool streamRunning);
    // Engine thread only: destroys any dsp chain setDsp() retired that the audio thread is now known to
    // have moved past. Pass the stream's current running state; call once per engine-thread loop tick
    // (Player::reconcile already does).
    void reclaimDsp(bool streamRunning);
    void setVolume(float volume) { volume_.store(volume, std::memory_order_relaxed); }

    // Audio thread: frames <= kMaxRenderFrames.
    void render(float* stereo, size_t frames);

    // Any thread.
    Deck* currentDeck() const { return current_.load(std::memory_order_acquire); }
    bool ended() const { return ended_.load(std::memory_order_acquire); }
    size_t readTap(float* stereo, size_t frames) { return tap_.pop(stereo, frames * kChannels) / kChannels; }

private:
    void applyCommands();
    void retire(Deck* deck);
    void beginTransition(Deck* from, Deck* to);
    static size_t pull(Deck& deck, float* stereo, size_t frames);
    void fade(float* out, const float* incoming, size_t frames, int64_t firstFrame, int64_t fadeStart,
              int64_t fadeFrames) const;

    SpscRing<DeckCommand> commands_{64};
    SpscRing<Deck*> retired_{64};
    SpscRing<float> tap_;
    std::vector<float> scratch_;
    std::atomic<Deck*> current_{nullptr};
    std::atomic<Deck*> next_{nullptr};
    std::atomic<bool> ended_{false};
    std::atomic<int64_t> crossfadeFrames_{0};
    std::atomic<int> curve_{0};
    std::atomic<geode_dsp*> dsp_{nullptr};
    std::atomic<float> volume_{1.0f};
    std::atomic<uint64_t> callbacks_{0};
    // setDsp() may be called from any thread per the C API's contract, and reclaimDsp() runs on the
    // engine thread; std::deque isn't safe for concurrent push/pop, so both sides take this lock. It is
    // never touched from the audio callback (render() never calls into either), so the lock is fine here.
    std::mutex dspRetireLock_;
    std::deque<RetiredDsp> retiredDsp_;
};

}  // namespace geode::audio::player
