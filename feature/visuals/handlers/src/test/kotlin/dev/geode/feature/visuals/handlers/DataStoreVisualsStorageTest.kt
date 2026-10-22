package dev.geode.feature.visuals.handlers

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.geode.engine.types.SceneId
import dev.geode.feature.visuals.api.Folder
import dev.geode.feature.visuals.api.FolderId
import dev.geode.feature.visuals.api.StyleLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The library in a real DataStore file, as the app keeps it. DataStore needs no Android runtime. */
class DataStoreVisualsStorageTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val file: File by lazy { File(temporary.root, "visuals.preferences_pb") }

    private val dataStore by lazy { PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }) }

    @After
    fun stop() = scope.cancel()

    private val library =
        StyleLibrary(
            favourites = listOf(SceneId("milkdrop")),
            recent = listOf(SceneId("fluid_ink"), SceneId("tunnel")),
            folders = listOf(Folder(FolderId(1), "Calm", null, listOf(SceneId("tunnel")))),
        )

    @Test
    fun `nothing saved reads as an empty library`() =
        runBlocking {
            assertEquals(StyleLibrary(), DataStoreVisualsStorage(dataStore).load())
        }

    @Test
    fun `a saved library reads back`() =
        runBlocking {
            val storage = DataStoreVisualsStorage(dataStore)

            storage.save(library)

            assertEquals(library, storage.load())
        }

    @Test
    fun `it is kept in its versioned format under its own key`() =
        runBlocking {
            DataStoreVisualsStorage(dataStore).save(StyleLibrary(favourites = listOf(SceneId("tunnel"))))

            val stored = DataStoreVisualsStorage.KEY
            assertEquals("library", stored)
            assertEquals("geode-style-library 1\nfavourite tunnel", dataStore.data.first()[stringPreferencesKey(stored)])
        }
}
