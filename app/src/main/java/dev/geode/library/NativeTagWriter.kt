package dev.geode.library

import android.content.ContentResolver
import android.provider.MediaStore
import dev.geode.data.NativeTags
import dev.geode.data.TagWriteOutcome
import dev.geode.data.TrackTagEdit
import dev.geode.engine.types.TrackId
import dev.geode.feature.library.api.SongEdit
import dev.geode.feature.library.handlers.TagWrite
import dev.geode.feature.library.handlers.TagWriter
import dev.geode.playback.toUri

/**
 * The Library's tag writer: TagLib through [NativeTags], as the old track info editor wrote. The file's
 * album artist is kept, and so is whatever the edit leaves as it was. Only a file in the phone's media
 * index can be unlocked by asking the person.
 */
class NativeTagWriter(
    private val resolver: ContentResolver,
) : TagWriter {
    override fun write(
        song: TrackId,
        edit: SongEdit,
    ): TagWrite {
        val target = song.toUri()
        val existing = NativeTags.read(resolver, target)
        val tags =
            TrackTagEdit(
                title = edit.title,
                artist = edit.artist,
                album = edit.album,
                albumArtist = existing?.albumArtist.orEmpty(),
                genre = edit.genre ?: existing?.genre.orEmpty(),
                comment = edit.comment ?: existing?.comment.orEmpty(),
                year = edit.year ?: existing?.year ?: 0,
                track = edit.trackNumber ?: existing?.track ?: 0,
            )
        return when (NativeTags.write(resolver, target, tags)) {
            TagWriteOutcome.Written -> TagWrite.WRITTEN
            TagWriteOutcome.NeedsConsent ->
                if (target.authority == MediaStore.AUTHORITY) TagWrite.NEEDS_CONSENT else TagWrite.FAILED
            TagWriteOutcome.Refused, TagWriteOutcome.Unsupported -> TagWrite.FAILED
        }
    }
}
