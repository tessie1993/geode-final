package dev.geode.data

import android.content.Context
import dev.geode.engine.types.TrackId
import org.json.JSONObject
import java.io.File

data class MusicPlaylist(
    val name: String,
    val trackUris: List<TrackId> = emptyList(),
)

/** The old Library's playlists, read once to move them into Playback (#17). Playback keeps them now. */
class MusicPlaylistStore(
    context: Context,
) {
    private val dir = File(context.filesDir, "music-playlists")

    fun list(): List<MusicPlaylist> =
        dir
            .listFiles { f -> f.extension == "json" }
            ?.mapNotNull { runCatching { fromJson(it.readText()) }.getOrNull() }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()

    private fun fromJson(text: String): MusicPlaylist {
        val o = JSONObject(text)
        val arr = o.getJSONArray("tracks")
        return MusicPlaylist(
            name = o.getString("name"),
            trackUris = (0 until arr.length()).map { TrackId(arr.getString(it)) },
        )
    }
}
