#pragma once
// A lock-free hand-off of whole values from one writer thread to one reader thread (ADR-0005,
// "Hand-off without locks").
//
// Three slots: the reader owns `front`, the writer owns `back`, and the third sits in the middle.
// The writer fills its back slot and publish() swaps it with the middle one; the reader's refresh()
// swaps its front slot with the middle one when something new is there. Neither side ever waits for
// the other, neither allocates, and a reader always sees a whole value, never one half written.
// A reader that falls behind skips straight to the newest value.

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>

namespace geode::bridge {

template <typename T>
class TripleBuffer {
public:
    // Writer thread only: the slot to fill before publish(). It holds an older value, not the last
    // one published, so fill every field.
    T& back() noexcept { return slots_[back_]; }

    // Writer thread only: hands the back slot to the reader and takes a free one in exchange.
    void publish() noexcept {
        const uint8_t handed = static_cast<uint8_t>(back_ | kFresh);
        back_ = static_cast<uint8_t>(middle_.exchange(handed, std::memory_order_acq_rel) & kSlotMask);
    }

    // Reader thread only: moves to the newest published value. False (and front() unchanged) when
    // nothing was published since the last refresh.
    bool refresh() noexcept {
        if ((middle_.load(std::memory_order_acquire) & kFresh) == 0) return false;
        front_ = static_cast<uint8_t>(middle_.exchange(front_, std::memory_order_acq_rel) & kSlotMask);
        return true;
    }

    // Reader thread only: the value the last successful refresh() moved to (a default T before any).
    const T& front() const noexcept { return slots_[front_]; }

private:
    static constexpr std::size_t kSlots = 3;
    static constexpr uint8_t kSlotMask = 0x3;
    static constexpr uint8_t kFresh = 0x4;
    static constexpr std::size_t kCacheLine = 64;

    std::array<T, kSlots> slots_{};
    uint8_t front_ = 0;
    alignas(kCacheLine) std::atomic<uint8_t> middle_{1};
    alignas(kCacheLine) uint8_t back_ = 2;

    static_assert(std::atomic<uint8_t>::is_always_lock_free, "the hand-off must never take a lock");
};

}  // namespace geode::bridge
