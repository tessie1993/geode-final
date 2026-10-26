#include <algorithm>
#include <cstdint>
#include <memory>

#include "geode/api/geode_api.h"
#include "FeedHandle.hpp"

namespace {

using geode::audio::feed::FeedSource;

static_assert(static_cast<int>(FeedSource::Player) == GEODE_FEED_PLAYER);
static_assert(static_cast<int>(FeedSource::Live) == GEODE_FEED_LIVE);
static_assert(geode::audio::feed::FeedRing::kChannels == 2);
static_assert(static_cast<int>(geode::audio::feed::OutputRoute::Unknown) == GEODE_FEED_ROUTE_UNKNOWN);
static_assert(static_cast<int>(geode::audio::feed::OutputRoute::Speaker) == GEODE_FEED_ROUTE_SPEAKER);
static_assert(static_cast<int>(geode::audio::feed::OutputRoute::Wired) == GEODE_FEED_ROUTE_WIRED);
static_assert(static_cast<int>(geode::audio::feed::OutputRoute::Usb) == GEODE_FEED_ROUTE_USB);
static_assert(static_cast<int>(geode::audio::feed::OutputRoute::Bluetooth) == GEODE_FEED_ROUTE_BLUETOOTH);
static_assert(static_cast<int>(geode::audio::feed::OutputRoute::BluetoothLe) == GEODE_FEED_ROUTE_BLUETOOTH_LE);
static_assert(static_cast<int>(geode::audio::feed::OutputRoute::Hdmi) == GEODE_FEED_ROUTE_HDMI);

FeedSource sourceOf(int source) {
    switch (source) {
        case GEODE_FEED_PLAYER: return FeedSource::Player;
        case GEODE_FEED_LIVE: return FeedSource::Live;
        default: return FeedSource::None;
    }
}

}  // namespace

extern "C" {

geode_feed* geode_feed_create(void) {
    return std::make_unique<geode_feed>().release();
}

void geode_feed_destroy(geode_feed* f) {
    std::unique_ptr<geode_feed> owned(f);
}

void geode_feed_select(geode_feed* f, int source) {
    if (f) f->feed->ring().select(sourceOf(source));
}

void geode_feed_flush(geode_feed* f, int source) {
    if (f) f->feed->ring().flush(sourceOf(source));
}

void geode_feed_set_running(geode_feed* f, int source, int running) {
    if (f) f->feed->ring().setRunning(sourceOf(source), running != 0);
}

void geode_feed_set_presentation(geode_feed* f, int source, int64_t delay_us, float speed) {
    if (f) f->feed->ring().setPresentation(sourceOf(source), delay_us * 1000, speed);
}

void geode_feed_set_route(geode_feed* f, int route) {
    if (f) f->feed->ring().setRoute(geode::audio::feed::heard::routeOf(route));
}

void geode_feed_set_user_offset(geode_feed* f, int64_t offset_us) {
    // Clamped before scaling, so no offset overflows on its way to nanoseconds.
    constexpr int64_t kMaxUs = geode::audio::feed::heard::kMaxUserOffsetNs / 1000;
    if (f) f->feed->ring().setUserOffset(std::clamp<int64_t>(offset_us, -kMaxUs, kMaxUs) * 1000);
}

void geode_feed_set_gain(geode_feed* f, float gain_db) {
    if (f) f->feed->ring().setGainDb(gain_db);
}

size_t geode_feed_write(geode_feed* f, int source, const float* interleaved, size_t frames, int channels, int sample_rate) {
    if (!f) return 0;
    return f->feed->ring().write(sourceOf(source), interleaved, frames, channels, sample_rate, geode::api::Feed::nowNs());
}

void geode_feed_set_active(geode_feed* f, int active) {
    if (f) f->feed->setActive(active != 0);
}

void geode_feed_set_tuning(geode_feed* f, float sensitivity, float refractory_ms, float attack_seconds, float release_seconds) {
    if (f) f->feed->setTuning(sensitivity, refractory_ms, attack_seconds, release_seconds);
}

void geode_feed_reset_analysis(geode_feed* f) {
    if (f) f->feed->resetAnalysis();
}

GeodeStatus geode_feed_attach_snapshots(geode_feed* f, geode_snapshots* snapshots) {
    if (!f) return GEODE_STATUS_NULL_HANDLE;
    f->feed->attachSnapshots(snapshots);
    return GEODE_STATUS_OK;
}

}  // extern "C"
