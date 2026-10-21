package dev.geode.feature.settings.handlers

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import dev.geode.feature.settings.api.LookCommand
import dev.geode.feature.settings.api.LookSettings
import dev.geode.feature.settings.api.OnboardingCommand
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.settings.api.SettingsState
import dev.geode.feature.settings.api.SettingsValues
import dev.geode.feature.settings.core.SAVE_DELAY
import dev.geode.kernel.FakeClock
import dev.geode.kernel.FeatureFailure
import dev.geode.kernel.inPlaceRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Settings end to end on a real DataStore file: migrated once from the old preferences, read when the
 * store starts, and saved once a burst of changes settles.
 */
class SettingsStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val clock = FakeClock()
    private val failures = mutableListOf<FeatureFailure>()
    private val scopes = mutableListOf<CoroutineScope>()
    private var legacyReads = 0

    private val legacy: Map<String, Any?> =
        mapOf("gui_compact_player" to true, "gui_text_scale" to 1.2f, "gui_intent" to "WATCH", "shuffle" to true)

    private val file: File by lazy { File(folder.root, "geode-settings.preferences_pb") }

    @After
    fun closeStores() = reopen()

    @Test
    fun `the first start takes the old settings, unchanged`() {
        val settings = settingsStore(dataStore(), inPlaceRuntime(clock, failures))

        val values = settings.ready()

        assertEquals(LookSettings(compactPlayer = true, textScale = 1.2f), values.look)
        assertEquals(true, values.onboarding.setupDone)
        assertEquals(1, legacyReads)
        assertEquals(emptyList<FeatureFailure>(), failures)
    }

    @Test
    fun `the migration runs once and marks its version`() {
        settingsStore(dataStore(), inPlaceRuntime(clock, failures)).ready()
        reopen()

        val stored = runBlocking { dataStore().data.first() }

        assertEquals(1, legacyReads)
        assertEquals(1, stored[intPreferencesKey("settings_version")])
        assertEquals(true, stored[booleanPreferencesKey("gui_compact_player")])
        assertEquals(null, stored[booleanPreferencesKey("shuffle")])
    }

    @Test
    fun `a change is saved once the changes settle, and read back on the next start`() {
        val store = dataStore()
        val settings = settingsStore(store, inPlaceRuntime(clock, failures))
        settings.ready()

        settings.send(LookCommand.SetTextScale(1.1f))
        settings.send(LookCommand.SetTextScale(1.3f))
        settings.send(OnboardingCommand.FinishTutorial)
        clock.advanceBy(SAVE_DELAY)
        runBlocking { withTimeout(WAIT_MS) { store.data.first { it[floatPreferencesKey("gui_text_scale")] == 1.3f } } }
        reopen()

        val again = settingsStore(dataStore(), inPlaceRuntime(clock, failures)).ready()

        assertEquals(1.3f, again.look.textScale)
        assertEquals(true, again.onboarding.tutorialSeen)
        assertEquals(true, again.look.compactPlayer)
    }

    @Test
    fun `nothing is written before the changes settle`() {
        val store = dataStore()
        val settings = settingsStore(store, inPlaceRuntime(clock, failures))
        settings.ready()

        settings.send(LookCommand.SetTextScale(1.1f))

        assertEquals(1.2f, runBlocking { store.data.first() }[floatPreferencesKey("gui_text_scale")])
        assertEquals(1, clock.pendingCount)
    }

    @Test
    fun `a file that cannot be read starts from the defaults and says so`() {
        val settings = settingsStore(Unreadable, inPlaceRuntime(clock, failures))

        assertEquals(SettingsValues(), settings.ready())
        val reported = failures.map { "${it.feature}: ${it.message}" }
        assertEquals(listOf("settings: settings could not be read, using the defaults: disk gone"), reported)
    }

    private fun dataStore(): DataStore<Preferences> {
        val scope = CoroutineScope(SupervisorJob())
        scopes += scope
        return PreferenceDataStoreFactory.create(
            migrations = listOf(LegacySettingsMigration { legacy.also { legacyReads++ } }),
            scope = scope,
            produceFile = { file },
        )
    }

    /**
     * Closes the open stores, as when the app is killed, so the file can be opened again. DataStore
     * lets a file go only once its scope has finished, not when the cancel is sent, so this waits.
     */
    private fun reopen() {
        runBlocking { scopes.forEach { it.coroutineContext.job.cancelAndJoin() } }
        scopes.clear()
    }

    private fun Settings.ready(): SettingsValues =
        runBlocking {
            withTimeout(WAIT_MS) { (state.first { it is SettingsState.Ready } as SettingsState.Ready).values }
        }

    private object Unreadable : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw IOException(GONE) }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw IOException(GONE)
    }

    private companion object {
        const val WAIT_MS = 5_000L
        const val GONE = "disk gone"
    }
}
