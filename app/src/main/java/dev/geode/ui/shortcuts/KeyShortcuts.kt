package dev.geode.ui.shortcuts

import android.view.KeyEvent
import dev.geode.feature.settings.api.SettingsState
import dev.geode.ui.shell.ShellViewModel
import kotlin.random.Random

/**
 * The hardware key shortcuts (#410), fed by the activity: [beforeViews] from its dispatchKeyEvent,
 * ahead of the screen, and [afterViews] from its onKeyDown, which only sees keys no view used. Each
 * answers whether it took the key. They read the stores at the moment of the press, decide with
 * [KeyMap] and [Shortcuts], and send the one command that results.
 */
class KeyShortcuts(
    private val shell: ShellViewModel,
    private val random: Random = Random.Default,
) {
    /**
     * Takes a Snapshot of the Visual (#405); while null, S is left alone. Wired where Snapshot is built:
     * `onSnapshot = { shell.visuals.send(VisualsCommand.Snapshot) }`.
     */
    var onSnapshot: (() -> Unit)? = null

    fun beforeViews(event: KeyEvent): Boolean = take(event, KeyStage.BEFORE_VIEWS)

    fun afterViews(event: KeyEvent): Boolean = take(event, KeyStage.AFTER_VIEWS)

    private fun take(
        event: KeyEvent,
        stage: KeyStage,
    ): Boolean {
        val modifiers = KeyModifiers(event.isShiftPressed, event.isCtrlPressed, event.isAltPressed, event.isMetaPressed)
        val shortcut = KeyMap.shortcutFor(event.keyCode, modifiers) ?: return false
        val scene = scene()
        if (!Shortcuts.applies(shortcut, stage, scene)) return false
        // The key's release, and a held toggle's repeats, are taken too, so no view sees half a press.
        if (event.action != KeyEvent.ACTION_DOWN || (event.repeatCount > 0 && !shortcut.repeats)) return true
        val effect = Shortcuts.effectOf(shortcut, scene, random) ?: return false
        send(effect)
        return true
    }

    private fun scene(): ShortcutScene {
        val settings = (shell.settings.state.value as? SettingsState.Ready)?.values
        return ShortcutScene(
            appUi = shell.appUi.state.value,
            visualsOff = settings?.performance?.visualsOff ?: false,
            player = shell.playback.state.value.player,
            visuals = shell.visuals.state.value,
            styleHeld = shell.customization.state.value.styleHeld,
            sensitivity = settings?.analysis?.sensitivity,
            canSnapshot = onSnapshot != null,
        )
    }

    private fun send(effect: ShortcutEffect) {
        when (effect) {
            is ShortcutEffect.ToAppUi -> shell.appUi.send(effect.command)
            is ShortcutEffect.ToPlayback -> shell.playback.send(effect.command)
            is ShortcutEffect.ToVisuals -> shell.visuals.send(effect.command)
            is ShortcutEffect.ToSettings -> shell.settings.send(effect.command)
            is ShortcutEffect.ToCustomization -> shell.customization.send(effect.command)
            ShortcutEffect.Snapshot -> onSnapshot?.invoke()
        }
    }
}
