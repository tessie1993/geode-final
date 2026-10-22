package dev.geode.feature.visuals.handlers

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dev.geode.feature.visuals.api.StyleLibrary
import dev.geode.feature.visuals.core.StyleLibraryCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first

/**
 * The Visuals feature's own DataStore file (ADR-0004), apart from the settings: what is in it is the
 * person's library, not a preference. A file that cannot be parsed is replaced by an empty one.
 */
fun visualsDataStore(
    context: Context,
    scope: CoroutineScope,
): DataStore<Preferences> {
    val app = context.applicationContext
    return PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = scope,
        produceFile = { app.preferencesDataStoreFile(DataStoreVisualsStorage.FILE) },
    )
}

/** Keeps the library in [store], as one value in [StyleLibraryCodec]'s versioned format. */
class DataStoreVisualsStorage(
    private val store: DataStore<Preferences>,
) : VisualsStorage {
    override suspend fun load(): StyleLibrary = store.data.first()[key]?.let(StyleLibraryCodec::decode) ?: StyleLibrary()

    override suspend fun save(library: StyleLibrary) {
        val text = StyleLibraryCodec.encode(library)
        store.edit { it[key] = text }
    }

    private val key = stringPreferencesKey(KEY)

    companion object {
        /** The DataStore file's name. */
        const val FILE = "visuals"

        /** The one key the library is kept under. */
        const val KEY = "library"
    }
}
