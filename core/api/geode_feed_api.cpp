#include <memory>

#include "geode/api/geode_api.h"
#include "FeedHandle.hpp"

namespace {

using geode::audio::feed::FeedSource;

static_assert(static_cast<int>(FeedSource::Player) == GEODE_FEED_PLAYER);
static_assert(static_cast<int>(FeedSource::Live) == GEODE_FEED_LIVE);
static_assert(geode::audio::feed::FeedRing::kChannels == 2);

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
