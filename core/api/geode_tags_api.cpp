#include <memory>
#include <optional>
#include <string>

#include "geode/api/geode_api.h"
#include "geode/library/Tags.hpp"

struct geode_tags {
    geode::library::TrackTags tags;
};

namespace {

// The tags API is called from one thread at a time, so a thread-local suffices to remember the last
// geode_tags_write failure reason without adding an out-parameter to the C entry point.
thread_local GeodeTagsError g_lastTagsError = GEODE_TAGS_OK;

GeodeTagsError toApiError(geode::library::TagsWriteError e) {
    using geode::library::TagsWriteError;
    switch (e) {
        case TagsWriteError::kNone: return GEODE_TAGS_OK;
        case TagsWriteError::kOpenFailed: return GEODE_TAGS_ERR_OPEN;
        case TagsWriteError::kReadOnly: return GEODE_TAGS_ERR_READ_ONLY;
        case TagsWriteError::kUnsupported: return GEODE_TAGS_ERR_UNSUPPORTED;
        case TagsWriteError::kSaveFailed: return GEODE_TAGS_ERR_SAVE;
    }
    return GEODE_TAGS_ERR_UNSUPPORTED;
}

// NULL means "leave this field unchanged"; a present pointer (including "") is the new value.
void setIfPresent(std::optional<std::string>& field, const char* value) {
    if (value) field = std::string(value);
}

}  // namespace

extern "C" {

geode_tags* geode_tags_read(int fd) {
    auto handle = std::make_unique<geode_tags>();
    if (!geode::library::readTags(fd, handle->tags)) return nullptr;
    return handle.release();
}

void geode_tags_destroy(geode_tags* h) { std::unique_ptr<geode_tags> owned(h); }

const char* geode_tags_text(const geode_tags* h, GeodeTagText field) {
    if (!h) return "";
    const geode::library::TrackTags& t = h->tags;
    switch (field) {
        case GEODE_TAG_TITLE: return t.title.c_str();
        case GEODE_TAG_ARTIST: return t.artist.c_str();
        case GEODE_TAG_ALBUM: return t.album.c_str();
        case GEODE_TAG_ALBUM_ARTIST: return t.albumArtist.c_str();
        case GEODE_TAG_GENRE: return t.genre.c_str();
        case GEODE_TAG_COMMENT: return t.comment.c_str();
        case GEODE_TAG_TEXT_COUNT: return "";
    }
    return "";
}

int geode_tags_year(const geode_tags* h) { return h ? h->tags.year : 0; }

int geode_tags_track(const geode_tags* h) { return h ? h->tags.track : 0; }

int geode_tags_duration_ms(const geode_tags* h) { return h ? h->tags.durationMs : 0; }

size_t geode_tags_art_bytes(const geode_tags* h) { return h ? h->tags.artBytes : 0; }

int geode_tags_replaygain(const geode_tags* h, float* track_gain_db, float* track_peak, float* album_gain_db,
                          float* album_peak) {
    if (!h) return 0;
    const geode::library::TrackTags& t = h->tags;
    int mask = 0;
    if (track_gain_db) *track_gain_db = t.trackGainDb;
    if (track_peak) *track_peak = t.trackPeak;
    if (album_gain_db) *album_gain_db = t.albumGainDb;
    if (album_peak) *album_peak = t.albumPeak;
    if (t.hasTrackGain) mask |= GEODE_TAG_TRACK_GAIN;
    if (t.hasTrackPeak) mask |= GEODE_TAG_TRACK_PEAK;
    if (t.hasAlbumGain) mask |= GEODE_TAG_ALBUM_GAIN;
    if (t.hasAlbumPeak) mask |= GEODE_TAG_ALBUM_PEAK;
    return mask;
}

int geode_tags_write(int fd, const char* const* texts, int year, int track) {
    geode::library::TrackTagEdit edit;
    if (texts) {
        setIfPresent(edit.title, texts[GEODE_TAG_TITLE]);
        setIfPresent(edit.artist, texts[GEODE_TAG_ARTIST]);
        setIfPresent(edit.album, texts[GEODE_TAG_ALBUM]);
        setIfPresent(edit.albumArtist, texts[GEODE_TAG_ALBUM_ARTIST]);
        setIfPresent(edit.genre, texts[GEODE_TAG_GENRE]);
        setIfPresent(edit.comment, texts[GEODE_TAG_COMMENT]);
    }
    edit.year = year;
    edit.track = track;
    geode::library::TagsWriteError error = geode::library::TagsWriteError::kNone;
    const bool ok = geode::library::writeTags(fd, edit, &error);
    g_lastTagsError = toApiError(error);
    return ok ? 1 : 0;
}

int geode_tags_last_error(void) { return g_lastTagsError; }

}  // extern "C"
