package dev.geode.feature.settings.handlers

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.geode.engine.types.Millis
import dev.geode.feature.settings.api.Argb
import dev.geode.feature.settings.api.CornerStyle
import dev.geode.feature.settings.api.CrossfadeCurve
import dev.geode.feature.settings.api.Decibels
import dev.geode.feature.settings.api.ExportCodec
import dev.geode.feature.settings.api.ExportFrameRate
import dev.geode.feature.settings.api.ExportLoudness
import dev.geode.feature.settings.api.ExportQuality
import dev.geode.feature.settings.api.ExportRatio
import dev.geode.feature.settings.api.FolderUri
import dev.geode.feature.settings.api.Millibels
import dev.geode.feature.settings.api.Minutes
import dev.geode.feature.settings.api.PlayerPosition
import dev.geode.feature.settings.api.ReplayGainMode
import dev.geode.feature.settings.api.Seconds
import dev.geode.feature.settings.api.Semitones
import dev.geode.feature.settings.api.SettingsState
import dev.geode.feature.settings.api.SettingsValues
import dev.geode.kernel.FeatureFailure
import dev.geode.kernel.inPlaceRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Real preferences files from an install of an older build (in test resources, `legacy-prefs/`),
 * read by Android's own SharedPreferences, migrated into DataStore and loaded by the Settings store:
 * every setting arrives unchanged, nothing that is not a setting comes along, and the old files are
 * left exactly as they were (#38, ADR-0004).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class LegacyPrefsRobolectricTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob())
    private val failures = mutableListOf<FeatureFailure>()
    private val samples = listOf("geode-prefs", "geode-player", "geode-audiofx", "geode-library")
    private lateinit var originals: Map<String, ByteArray>

    @Before
    fun installOldFiles() {
        val dir = File(context.dataDir, "shared_prefs").apply { mkdirs() }
        originals =
            samples.associateWith { name ->
                val bytes = requireNotNull(javaClass.classLoader?.getResourceAsStream("legacy-prefs/$name.xml")).use { it.readBytes() }
                File(dir, "$name.xml").writeBytes(bytes)
                bytes
            }
    }

    @After
    fun closeStore() = scope.cancel()

    @Test
    fun `every setting in the old files loads unchanged`() {
        val values = load()

        assertEquals(expected, values)
        assertEquals(emptyList<FeatureFailure>(), failures)
    }

    @Test
    fun `nothing that is not a setting is moved`() {
        val store = settingsDataStore(context, scope)
        val stored = runBlocking { withTimeout(WAIT_MS) { store.data.first() } }

        assertEquals(null, stored[booleanPreferencesKey("shuffle")])
        assertEquals(null, stored[stringPreferencesKey("lfos")])
        assertEquals(null, stored[longPreferencesKey("bookmark:content://media/external/audio/media/7")])
        assertEquals(null, stored[stringPreferencesKey("gui_intent")])
        assertEquals("R9_16", stored[stringPreferencesKey("export_ratio")])
        assertEquals(setOf("content://tree/music", "content://tree/more"), stored[stringSetPreferencesKey("roots")])
    }

    @Test
    fun `the old files are left as they were`() {
        load()

        val dir = File(context.dataDir, "shared_prefs")
        for ((name, bytes) in originals) {
            assertArrayEquals(name, bytes, File(dir, "$name.xml").readBytes())
        }
    }

    private fun load(): SettingsValues {
        val settings = settingsStore(settingsDataStore(context, scope), inPlaceRuntime(failures = failures))
        return runBlocking {
            withTimeout(WAIT_MS) { (settings.state.first { it is SettingsState.Ready } as SettingsState.Ready).values }
        }
    }

    private val expected: SettingsValues =
        SettingsValues().let { d ->
            d.copy(
                look =
                    d.look.copy(
                        accentIntensity = 1.25f,
                        backgroundDim = 0.3f,
                        fontColor = Argb(0xFFFFFFFF.toInt()),
                        textScale = 1.15f,
                        barOpacity = 0.5f,
                        playerPosition = PlayerPosition.TOP,
                        cornerStyle = CornerStyle.PILL,
                        compactPlayer = true,
                        clearVisualsMenu = true,
                    ),
                playback =
                    d.playback.copy(
                        speed = 1.25f,
                        pitch = Semitones(-2f),
                        skipSilence = true,
                        pauseWhenUnplugged = false,
                        keepScreenOn = true,
                        autoResume = true,
                        resumeLongTracks = false,
                        sleepTimer = Minutes(45),
                        sleepFinishTrack = true,
                        fade = Millis(1_500),
                        replayGain = ReplayGainMode.ALBUM,
                        replayGainPreamp = Decibels(-3.5f),
                        replayGainClipGuard = false,
                        nativeEngine = true,
                        gapless = false,
                        crossfade = Millis(4_000),
                        crossfadeCurve = CrossfadeCurve.SMOOTH,
                    ),
                equalizer =
                    d.equalizer.copy(
                        enabled = true,
                        bands = listOf(100, 200, 300, 400, 500, -100, -200, -300, -400, -500).map(::Millibels),
                        bassBoost = 300,
                        loudness = Millibels(150),
                    ),
                analysis =
                    d.analysis.copy(
                        beatSensitivity = 5f,
                        beatMinIntervalMs = 450f,
                        presetMorph = Seconds(3f),
                        reactToMicrophone = true,
                    ),
                behavior =
                    d.behavior.copy(
                        touchSmear = true,
                        touchSmearStrength = 1.5f,
                        touchTransform = false,
                        reducedMotion = true,
                    ),
                export =
                    d.export.copy(
                        quality = ExportQuality.UHD4K,
                        frameRate = ExportFrameRate.FPS30,
                        ratio = ExportRatio.R9_16,
                        loopSafe = true,
                        codec = ExportCodec.HEVC,
                        loudness = ExportLoudness.SHORTS_AND_TIKTOK,
                    ),
                folders =
                    d.folders.copy(
                        presetMirror = FolderUri("content://tree/presets"),
                        musicFolders = setOf(FolderUri("content://tree/music"), FolderUri("content://tree/more")),
                    ),
                onboarding =
                    d.onboarding.copy(
                        safetyAcknowledged = true,
                        setupDone = true,
                        tutorialSeen = true,
                        tutorialOnFirstRun = false,
                    ),
            )
        }

    private companion object {
        const val WAIT_MS = 10_000L
    }
}
