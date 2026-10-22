package dev.geode.ui

import android.app.Application
import dev.geode.R
import dev.geode.audio.AudioFxState
import dev.geode.data.ExportDefaults
import dev.geode.data.PlayerPrefs
import dev.geode.data.PlayerPrefsRepository
import dev.geode.feature.playback.api.Playback
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.settings.api.EqualizerCommand
import dev.geode.feature.settings.api.Millibels
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.settings.api.SettingsCommand
import dev.geode.feature.settings.api.SettingsState
import dev.geode.feature.settings.api.SettingsValues
import dev.geode.geodeContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton
import dev.geode.feature.playback.api.RepeatMode as PlaybackRepeat

/**
 * Settings as the old screens still use it, until Settings' own screens replace them (#17, Settings
 * slice).
 *
 * Every setting the old screens read comes from the one app-scoped [Settings] store, and every save
 * becomes Settings Commands for exactly what changed (ADR-0003: one source of truth). The old
 * SharedPreferences files are only read once, by Settings' own first-run migration. Shuffle and
 * repeat, which the old player settings also carried, belong to [Playback].
 */
@Singleton
class SettingsForOldScreens internal constructor(
    private val settings: Settings,
    private val playback: Playback,
    private val presetNames: List<String>,
    scope: CoroutineScope,
) : UserDataRepository,
    PlayerPrefsRepository {
    @Inject
    constructor(settings: Settings, playback: Playback, application: Application) :
        this(settings, playback, EQUALIZER_PRESETS.map { application.getString(it) }, application.geodeContainer.appScope)

    /** Settings' values, or the defaults while the stored ones are being read. */
    private val values: StateFlow<SettingsValues> =
        settings.state
            .map(
                ::valuesOf,
            ).stateIn(scope, SharingStarted.Eagerly, valuesOf(settings.state.value))

    override val guiPrefs: StateFlow<GuiPrefs> =
        values
            .map(
                OldScreenSettings::guiPrefsOf,
            ).stateIn(scope, SharingStarted.Eagerly, OldScreenSettings.guiPrefsOf(values.value))

    override val loaded: StateFlow<Boolean> =
        settings.state
            .map {
                it is SettingsState.Ready
            }.stateIn(scope, SharingStarted.Eagerly, settings.state.value is SettingsState.Ready)

    override suspend fun setGuiPrefs(prefs: GuiPrefs) = sendAll(OldScreenSettings.commandsFor(guiPrefs.value, prefs))

    override val prefs: StateFlow<PlayerPrefs> =
        combine(values, playback.state) { v, p -> OldScreenSettings.playerPrefsOf(v.playback, p.options) }
            .stateIn(scope, SharingStarted.Eagerly, OldScreenSettings.playerPrefsOf(values.value.playback, playback.state.value.options))

    override suspend fun loaded(): PlayerPrefs {
        settings.state.filterIsInstance<SettingsState.Ready>().first()
        return prefs.value
    }

    override suspend fun update(transform: (PlayerPrefs) -> PlayerPrefs) = setPlayerPrefs(transform(prefs.value))

    /**
     * Saves the old player settings. Settings keeps them, the crossfade and gapless among them, which
     * Playback follows (TransitionsFromSettings); shuffle and repeat are Playback's own.
     */
    fun setPlayerPrefs(next: PlayerPrefs) {
        val before = prefs.value
        val after = next.coerced()
        sendAll(OldScreenSettings.commandsFor(before, after))
        if (after.shuffle != before.shuffle) playback.send(PlaybackCommand.SetShuffle(after.shuffle))
        if (after.repeatMode != before.repeatMode) playback.send(PlaybackCommand.SetRepeat(repeatOf(after.repeatMode)))
    }

    /** The old equalizer panel's state, from Settings. */
    val audioFx: StateFlow<AudioFxState> =
        values
            .map { OldScreenSettings.audioFxStateOf(it.equalizer, presetNames) }
            .stateIn(scope, SharingStarted.Lazily, OldScreenSettings.audioFxStateOf(values.value.equalizer, presetNames))

    fun setEqualizerEnabled(enabled: Boolean) = settings.send(EqualizerCommand.SetEnabled(enabled))

    fun setEqualizerBand(
        band: Int,
        levelMb: Int,
    ) = settings.send(OldScreenSettings.bandCommand(band, levelMb))

    fun useEqualizerPreset(index: Int) {
        OldScreenSettings.presetCommand(index)?.let(settings::send)
    }

    fun setBassBoost(strength: Int) = settings.send(EqualizerCommand.SetBassBoost(strength))

    fun setLoudness(gainMb: Int) = settings.send(EqualizerCommand.SetLoudness(Millibels(gainMb)))

    /** What new renders start with. */
    fun exportDefaults(): ExportDefaults = OldScreenSettings.exportDefaultsOf(values.value.export)

    fun saveExportDefaults(defaults: ExportDefaults) = sendAll(OldScreenSettings.commandsFor(exportDefaults(), defaults))

    private fun sendAll(commands: List<SettingsCommand>) = commands.forEach(settings::send)

    private companion object {
        /** The equalizer presets' names, in Settings' preset order. */
        val EQUALIZER_PRESETS =
            listOf(
                R.string.eq_preset_flat,
                R.string.eq_preset_bass,
                R.string.eq_preset_treble,
                R.string.eq_preset_vocal,
                R.string.eq_preset_rock,
                R.string.eq_preset_electronic,
            )

        fun valuesOf(state: SettingsState): SettingsValues =
            when (state) {
                is SettingsState.Loading -> SettingsValues()
                is SettingsState.Ready -> state.values
            }

        fun repeatOf(old: Int): PlaybackRepeat =
            PlaybackRepeat.entries.firstOrNull { OldScreenModels.repeatModeOf(it) == old } ?: PlaybackRepeat.OFF
    }
}
