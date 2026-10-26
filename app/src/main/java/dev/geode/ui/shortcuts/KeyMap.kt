package dev.geode.ui.shortcuts

import android.view.KeyEvent

/**
 * What a hardware key asks of the app (#410), for DeX, Chromebook keyboards and Android TV remotes.
 * [repeats] says whether holding the key down keeps doing it (stepping Sensitivity does; a toggle
 * would flicker on and off, so it does not). [overVisualOnly] says the key means this only while the
 * full-screen Visual is in front: everywhere else the arrows move D-pad focus and Esc is Back.
 */
enum class Shortcut(
    val repeats: Boolean,
    val overVisualOnly: Boolean,
) {
    PREVIOUS_STYLE(repeats = true, overVisualOnly = true),
    NEXT_STYLE(repeats = true, overVisualOnly = true),
    SENSITIVITY_UP(repeats = true, overVisualOnly = true),
    SENSITIVITY_DOWN(repeats = true, overVisualOnly = true),
    PLAY_PAUSE(repeats = false, overVisualOnly = false),
    HOLD(repeats = false, overVisualOnly = false),
    LOVE(repeats = false, overVisualOnly = false),
    RANDOM_STYLE(repeats = false, overVisualOnly = false),
    SNAPSHOT(repeats = false, overVisualOnly = false),
    FULL_SCREEN(repeats = false, overVisualOnly = false),
    EXIT_FULL_SCREEN(repeats = false, overVisualOnly = true),
}

/** The modifier keys held with a key. */
data class KeyModifiers(
    val shift: Boolean = false,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false,
) {
    /** No modifier at all: the only way a shortcut is pressed. */
    val none: Boolean get() = listOf(shift, ctrl, alt, meta).none { it }

    companion object {
        val NONE = KeyModifiers()
    }
}

/**
 * The key map (#410): an Android key code and its modifiers to the [Shortcut] it presses, or null for
 * any other key. Shortcuts are bare keys, so Ctrl+S, Alt+Tab and Shift+arrow selection stay the
 * system's and the text field's. Media keys are never mapped: they go to the media session. Back is
 * not mapped either: the App UI's own Back already leaves the full-screen Visual.
 */
object KeyMap {
    private val keys: Map<Int, Shortcut> =
        mapOf(
            KeyEvent.KEYCODE_DPAD_LEFT to Shortcut.PREVIOUS_STYLE,
            KeyEvent.KEYCODE_DPAD_RIGHT to Shortcut.NEXT_STYLE,
            KeyEvent.KEYCODE_DPAD_UP to Shortcut.SENSITIVITY_UP,
            KeyEvent.KEYCODE_DPAD_DOWN to Shortcut.SENSITIVITY_DOWN,
            KeyEvent.KEYCODE_SPACE to Shortcut.PLAY_PAUSE,
            KeyEvent.KEYCODE_H to Shortcut.HOLD,
            KeyEvent.KEYCODE_L to Shortcut.LOVE,
            KeyEvent.KEYCODE_R to Shortcut.RANDOM_STYLE,
            KeyEvent.KEYCODE_S to Shortcut.SNAPSHOT,
            KeyEvent.KEYCODE_F to Shortcut.FULL_SCREEN,
            KeyEvent.KEYCODE_ESCAPE to Shortcut.EXIT_FULL_SCREEN,
        )

    /** The shortcut [keyCode] presses with [modifiers] held, or null. */
    fun shortcutFor(
        keyCode: Int,
        modifiers: KeyModifiers = KeyModifiers.NONE,
    ): Shortcut? = if (modifiers.none) keys[keyCode] else null
}
