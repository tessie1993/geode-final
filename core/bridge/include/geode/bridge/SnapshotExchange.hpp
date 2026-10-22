#pragma once
// Where Kotlin's Customization snapshot meets the engine's threads (ADR-0004, ADR-0005).
//
// Kotlin publishes a snapshot only when the Customization changes. Each reader thread (drawing,
// audio) has its own triple buffer, so at the start of its next frame it calls refresh() and, when
// that returns true, applies current(). Nobody waits: publishing never blocks a reader, a reader
// never blocks the publisher, and a second publisher arriving mid-publish is told GEODE_STATUS_BUSY
// instead of waiting.

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <span>

#include "geode/api/geode_status.h"
#include "geode/bridge/CustomizationSnapshot.hpp"
#include "geode/bridge/TripleBuffer.hpp"

namespace geode::bridge {

enum class SnapshotReader : std::size_t {
    Draw = 0,   // the GL thread, at the start of each rendered frame
    Audio = 1,  // the analysis thread, at the start of each hop
};
inline constexpr std::size_t kSnapshotReaderCount = 2;

class SnapshotExchange {
public:
    // Any thread. Decodes `wire` and hands it to every reader; on any status but OK nothing changes.
    GeodeStatus publish(std::span<const uint8_t> wire) noexcept;

    // That reader's thread only: true when a newer snapshot was published since its last refresh.
    bool refresh(SnapshotReader reader) noexcept;
    // That reader's thread only: the snapshot its last successful refresh() moved to.
    const CustomizationSnapshot& current(SnapshotReader reader) const noexcept;

    // Any thread: the revision of the last snapshot published, 0 before any.
    uint32_t publishedRevision() const noexcept { return publishedRevision_.load(std::memory_order_acquire); }

private:
    GeodeStatus publishExclusive(std::span<const uint8_t> wire) noexcept;

    std::array<TripleBuffer<CustomizationSnapshot>, kSnapshotReaderCount> buffers_{};
    std::atomic_flag publishing_;
    std::atomic<uint32_t> publishedRevision_{0};
};

}  // namespace geode::bridge
