#include <memory>
#include <span>

#include "geode/api/geode_api.h"
#include "SnapshotHandle.hpp"

extern "C" {

geode_snapshots* geode_snapshots_create(void) {
    return std::make_unique<geode_snapshots>().release();
}

void geode_snapshots_destroy(geode_snapshots* s) {
    std::unique_ptr<geode_snapshots> owned(s);
}

GeodeStatus geode_snapshots_publish(geode_snapshots* s, const uint8_t* bytes, size_t length) {
    if (!s) return GEODE_STATUS_NULL_HANDLE;
    if (!bytes) return GEODE_STATUS_INVALID_ARGUMENT;
    return s->exchange.publish(std::span<const uint8_t>(bytes, length));
}

uint32_t geode_snapshots_revision(const geode_snapshots* s) {
    return s ? s->exchange.publishedRevision() : 0;
}

}  // extern "C"
