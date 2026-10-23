package dev.geode.feature.library.handlers

import dev.geode.engine.types.TrackId
import dev.geode.feature.library.api.Song
import dev.geode.feature.library.api.SongEdit
import dev.geode.feature.library.api.TagEdit
import dev.geode.feature.library.core.FileWriteOutcome
import kotlinx.coroutines.flow.Flow

/** What a look through the phone's music found. */
sealed interface ScanResult {
    /** The phone does not let Geode read its music. */
    data object NoPermission : ScanResult

    data class Found(
        val songs: List<Song>,
    ) : ScanResult
}

/** The phone's media index (ADR-0004: Android's MediaStore finds the files). [MediaStoreScanner] is the real one. */
interface MediaScanner {
    /** Every audio file the media index knows, as songs. Throws when the index cannot be read. */
    suspend fun scan(): ScanResult

    /** One value each time the phone's music changes, for as long as it is collected. */
    val changes: Flow<Unit>
}

/** What a file's own tags say about what the media index often leaves blank. */
data class FileTags(
    val albumArtist: String,
    val composer: String,
    val genre: String,
)

/**
 * Geode's tag reader (ADR-0004: TagLib fills in the tags the media index misses). `:app` wires the
 * native reader at the switch-over (#17); until then nothing is filled in.
 */
fun interface TagReader {
    /** What [song]'s file tags say, or null when they cannot be read. May block on IO. */
    fun read(song: Song): FileTags?

    companion object {
        /** Reads nothing. */
        val NONE = TagReader { null }
    }
}

/**
 * Geode's tag writer (the old track info editor's "write to file", #17): it writes a song's edited info
 * into the file's own tags. `:app` wires the native writer.
 */
fun interface TagWriter {
    /** Writes [edit] and [tags] into [song]'s file, keeping what else the tags hold, and says how it went. May block on IO. */
    fun write(
        song: TrackId,
        edit: SongEdit,
        tags: TagEdit,
    ): FileWriteOutcome

    companion object {
        /** Writes nothing: every file is refused. */
        val NONE = TagWriter { _, _, _ -> FileWriteOutcome.REFUSED }
    }
}

/** Reading and writing the playlist files the user picks, named by content uris. */
interface PlaylistFiles {
    suspend fun read(file: String): String

    suspend fun write(
        file: String,
        text: String,
    )
}

/** [this] song with what its file's [tags] say wherever the media index left it blank. */
fun Song.filledFrom(tags: FileTags): Song =
    copy(
        albumArtist = albumArtist.ifBlank { tags.albumArtist },
        composer = composer.ifBlank { tags.composer },
        genre = genre.ifBlank { tags.genre },
    )
