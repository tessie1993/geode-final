package dev.geode.ui.shortcuts

import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.CustomizationCommand
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlayerState
import dev.geode.feature.settings.api.AnalysisCommand
import dev.geode.feature.settings.api.AnalysisSettings
import dev.geode.feature.settings.api.Decibels
import dev.geode.feature.settings.api.SettingsCommand
import dev.geode.feature.visuals.api.StyleMark
import dev.geode.feature.visuals.api.VisualsCommand
import dev.geode.feature.visuals.api.VisualsState
import dev.geode.feature.visuals.ui.toFullScreenModel
import dev.geode.ui.shell.AppUiCommand
import dev.geode.ui.shell.AppUiState
import kotlin.random.Random

/**
 * When a key reaches the shortcuts: [BEFORE_VIEWS], ahead of the screen, so the arrows over the
 * full-screen Visual change the Style rather than move focus; or [AFTER_VIEWS], only once no view
 * used it, so a focused text field keeps every key typed into it.
 */
enum class KeyStage {
    BEFORE_VIEWS,
    AFTER_VIEWS,
}

/**
 * What the shortcuts read of the app when a key is pressed: the App UI, whether Visuals are off, the
 * player, the Styles, Hold, Sensitivity (null until Settings have loaded) and whether a Snapshot can
 * be taken.
 */
data class ShortcutScene(
    val appUi: AppUiState = AppUiState(),
    val visualsOff: Boolean = false,
    val player: PlayerState = PlayerState.Idle,
    val visuals: VisualsState = VisualsState(),
    val styleHeld: Boolean = false,
    val sensitivity: Decibels? = null,
    val canSnapshot: Boolean = false,
) {
    /** The full-screen Visual is in front, with nothing over it. */
    val visualInFront: Boolean get() = appUi.fullScreen && appUi.sheet == null && !visualsOff
}

/** What a shortcut does: one command to one store, or a Snapshot. */
sealed interface ShortcutEffect {
    data class ToAppUi(
        val command: AppUiCommand,
    ) : ShortcutEffect

    data class ToPlayback(
        val command: PlaybackCommand,
    ) : ShortcutEffect

    data class ToVisuals(
        val command: VisualsCommand,
    ) : ShortcutEffect

    data class ToSettings(
        val command: SettingsCommand,
    ) : ShortcutEffect

    data class ToCustomization(
        val command: CustomizationCommand,
    ) : ShortcutEffect

    data object Snapshot : ShortcutEffect
}

/** The shortcuts' decisions (#410), kept apart from Android's key events so they are plain to test. */
object Shortcuts {
    /** How far one press moves Sensitivity. */
    val SENSITIVITY_STEP = Decibels(1f)

    /** When [shortcut] is taken: the arrows ahead of the screen, every other key after it. */
    fun stageOf(shortcut: Shortcut): KeyStage =
        when (shortcut) {
            Shortcut.PREVIOUS_STYLE, Shortcut.NEXT_STYLE, Shortcut.SENSITIVITY_UP, Shortcut.SENSITIVITY_DOWN -> KeyStage.BEFORE_VIEWS
            else -> KeyStage.AFTER_VIEWS
        }

    /** Whether [shortcut] is taken at [stage] in [scene]. */
    fun applies(
        shortcut: Shortcut,
        stage: KeyStage,
        scene: ShortcutScene,
    ): Boolean = stageOf(shortcut) == stage && (!shortcut.overVisualOnly || scene.visualInFront)

    /** What [shortcut] does in [scene], or null when it can do nothing now. [random] draws the random Style. */
    fun effectOf(
        shortcut: Shortcut,
        scene: ShortcutScene,
        random: Random,
    ): ShortcutEffect? =
        when (shortcut) {
            Shortcut.PREVIOUS_STYLE ->
                scene.visuals
                    .toFullScreenModel(false)
                    .previous
                    ?.let(::choose)
            Shortcut.NEXT_STYLE ->
                scene.visuals
                    .toFullScreenModel(false)
                    .next
                    ?.let(::choose)
            Shortcut.SENSITIVITY_UP -> scene.sensitivity?.let { sensitivity(it, up = true) }
            Shortcut.SENSITIVITY_DOWN -> scene.sensitivity?.let { sensitivity(it, up = false) }
            Shortcut.PLAY_PAUSE -> playPause(scene.player)?.let(ShortcutEffect::ToPlayback)
            Shortcut.HOLD -> ShortcutEffect.ToCustomization(CustomizationCommand.HoldStyle(!scene.styleHeld))
            Shortcut.LOVE -> love(scene.visuals)
            Shortcut.RANDOM_STYLE -> randomStyle(scene.visuals, random)
            Shortcut.SNAPSHOT -> ShortcutEffect.Snapshot.takeIf { scene.canSnapshot }
            Shortcut.FULL_SCREEN ->
                ShortcutEffect.ToAppUi(if (scene.appUi.fullScreen) AppUiCommand.ExitFullScreen else AppUiCommand.EnterFullScreen)
            Shortcut.EXIT_FULL_SCREEN -> ShortcutEffect.ToAppUi(AppUiCommand.ExitFullScreen)
        }

    /** Sensitivity one step up or down from [now], kept within its range. */
    fun steppedSensitivity(
        now: Decibels,
        up: Boolean,
    ): Decibels {
        val step = if (up) SENSITIVITY_STEP.value else -SENSITIVITY_STEP.value
        val range = AnalysisSettings.SENSITIVITY
        return Decibels((now.value + step).coerceIn(range.start.value, range.endInclusive.value))
    }

    /** Pauses what is meant to play, plays what is not; nothing while nothing is loaded. */
    fun playPause(player: PlayerState): PlaybackCommand? =
        when (player) {
            PlayerState.Idle -> null
            is PlayerState.Playing, is PlayerState.Buffering -> PlaybackCommand.Pause
            is PlayerState.Loading -> if (player.playWhenReady) PlaybackCommand.Pause else PlaybackCommand.Resume
            is PlayerState.Paused, is PlayerState.Ended, is PlayerState.Error -> PlaybackCommand.Resume
        }

    private fun choose(style: SceneId) = ShortcutEffect.ToVisuals(VisualsCommand.Choose(style))

    private fun sensitivity(
        now: Decibels,
        up: Boolean,
    ) = ShortcutEffect.ToSettings(AnalysisCommand.SetSensitivity(steppedSensitivity(now, up)))

    /** Love for the Style shown, or back to Normal when it is already loved; nothing before the library has loaded. */
    private fun love(visuals: VisualsState): ShortcutEffect? {
        val style = visuals.current ?: return null
        val library = visuals.library ?: return null
        val mark = if (library.markOf(style) == StyleMark.LOVE) StyleMark.NORMAL else StyleMark.LOVE
        return ShortcutEffect.ToVisuals(VisualsCommand.SetMark(style, mark))
    }

    /** Any Style but the one shown and those marked Never, drawn by [random]. */
    private fun randomStyle(
        visuals: VisualsState,
        random: Random,
    ): ShortcutEffect? {
        val never =
            visuals.library
                ?.marks
                .orEmpty()
                .filterValues { it == StyleMark.NEVER }
                .keys
        val choices = visuals.styles.map { it.id }.filter { it != visuals.current && it !in never }
        if (choices.isEmpty()) return null
        return choose(choices[random.nextInt(choices.size)])
    }
}
