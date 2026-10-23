package dev.geode.feature.library.handlers

import dev.geode.engine.types.TrackId
import dev.geode.feature.library.api.Song
import dev.geode.feature.library.api.SongEdit
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

/** How writing tags into a file went. */
enum class TagWrite {
    WRITTEN,

    /** The phone must ask the person before the file may be changed. */
    NEEDS_CONSENT,

    /** The file could not be written, and asking will not help. */
    FAILED,
}

/** Geode's tag writer (ADR-0004: TagLib), which `:app` provides. */
fun interface TagWriter {
    /** Writes [edit] into [song]'s file, where the fields it leaves null keep what the file has. May block on IO. */
    fun write(
        song: TrackId,
        edit: SongEdit,
    ): TagWrite

    companion object {
        /** Writes nothing. */
        val NONE = TagWriter { _, _ -> TagWrite.FAILED }
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
