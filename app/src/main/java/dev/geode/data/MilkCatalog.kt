package dev.geode.data

import android.content.Context
import androidx.annotation.WorkerThread
import dev.geode.RingLog
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** Where bundled MilkDrop presets came from: Geode itself or an upstream pack at a pinned commit. */
data class MilkCatalogSource(
    val id: String,
    val name: String,
    val licence: String,
    val url: String? = null,
    val commit: String? = null,
)

/**
 * One bundled MilkDrop preset: its file name in `assets/milk` (and in the user's preset folder once
 * installed), the short name the App UI shows, its author credit and the id of its source.
 */
data class MilkCatalogEntry(
    val file: String,
    val name: String,
    val author: String,
    val source: String,
)

/** The catalog file is not one this version of the app can read. */
class MilkCatalogFormatException(
    message: String,
) : IllegalArgumentException(message)

/**
 * The bundled MilkDrop presets as data (`assets/milk/catalog.json`): display names and credits,
 * keyed by file name. A preset file the catalog does not list (one the user imported, say) keeps
 * its file name as its display name.
 */
class MilkCatalog(
    val sources: List<MilkCatalogSource>,
    val presets: List<MilkCatalogEntry>,
) {
    private val byFile = presets.associateBy { it.file }

    /** The catalog entry for a preset file name such as `Geiss - Artifact Plasma.milk`. */
    fun entryFor(fileName: String): MilkCatalogEntry? = byFile[fileName]

    /** The name to show for a preset file: the catalog's display name, else the bare file name. */
    fun displayNameOf(fileName: String): String = entryFor(fileName)?.name ?: fileName.removeSuffix(MILK_EXTENSION)

    companion object {
        const val ASSET_PATH = "milk/catalog.json"

        const val SCHEMA_VERSION = 1

        private const val MILK_EXTENSION = ".milk"

        val EMPTY = MilkCatalog(emptyList(), emptyList())

        /**
         * Reads a catalog. Throws [MilkCatalogFormatException] for another schema version and
         * [JSONException] for a file that is not a catalog.
         */
        fun parse(json: String): MilkCatalog {
            val root = JSONObject(json)
            val version = root.getInt("schemaVersion")
            if (version != SCHEMA_VERSION) {
                throw MilkCatalogFormatException("milk catalog schema $version, expected $SCHEMA_VERSION")
            }
            val sources = root.getJSONArray("sources")
            val presets = root.getJSONArray("presets")
            return MilkCatalog(
                sources =
                    (0 until sources.length()).map { i ->
                        val s = sources.getJSONObject(i)
                        MilkCatalogSource(
                            id = s.getString("id"),
                            name = s.getString("name"),
                            licence = s.getString("licence"),
                            url = s.optString("url").ifEmpty { null },
                            commit = s.optString("commit").ifEmpty { null },
                        )
                    },
                presets =
                    (0 until presets.length()).map { i ->
                        val p = presets.getJSONObject(i)
                        MilkCatalogEntry(
                            file = p.getString("file"),
                            name = p.getString("name"),
                            author = p.getString("author"),
                            source = p.getString("source"),
                        )
                    },
            )
        }

        /** The bundled catalog, or [EMPTY] (file names as display names) if it cannot be read. */
        @WorkerThread
        fun load(context: Context): MilkCatalog =
            try {
                parse(context.assets.open(ASSET_PATH).use { it.readBytes().decodeToString() })
            } catch (e: IOException) {
                RingLog.note("MilkCatalog", "milk catalog unreadable", e)
                EMPTY
            } catch (e: JSONException) {
                RingLog.note("MilkCatalog", "milk catalog malformed", e)
                EMPTY
            } catch (e: MilkCatalogFormatException) {
                RingLog.note("MilkCatalog", "milk catalog unsupported", e)
                EMPTY
            }
    }
}
