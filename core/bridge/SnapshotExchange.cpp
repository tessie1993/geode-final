#include "geode/bridge/SnapshotExchange.hpp"

namespace geode::bridge {

namespace {
constexpr std::size_t indexOf(SnapshotReader reader) noexcept {
    return static_cast<std::size_t>(reader);
}
}  // namespace

GeodeStatus SnapshotExchange::publish(std::span<const uint8_t> wire) noexcept {
    if (publishing_.test_and_set(std::memory_order_acquire)) return GEODE_STATUS_BUSY;
    const GeodeStatus status = publishExclusive(wire);
    publishing_.clear(std::memory_order_release);
    return status;
}

GeodeStatus SnapshotExchange::publishExclusive(std::span<const uint8_t> wire) noexcept {
    // Decoded straight into the first reader's back slot: decodeSnapshot leaves it untouched on failure,
    // and an unpublished back slot is never seen by its reader anyway.
    CustomizationSnapshot& decoded = buffers_.front().back();
    const GeodeStatus status = decodeSnapshot(wire, decoded);
    if (status != GEODE_STATUS_OK) return status;
    for (std::size_t i = 1; i < buffers_.size(); ++i) buffers_[i].back() = decoded;
    const uint32_t revision = decoded.revision;
    for (TripleBuffer<CustomizationSnapshot>& buffer : buffers_) buffer.publish();
    publishedRevision_.store(revision, std::memory_order_release);
    return GEODE_STATUS_OK;
}

bool SnapshotExchange::refresh(SnapshotReader reader) noexcept {
    return buffers_[indexOf(reader)].refresh();
}

const CustomizationSnapshot& SnapshotExchange::current(SnapshotReader reader) const noexcept {
    return buffers_[indexOf(reader)].front();
}

}  // namespace geode::bridge
