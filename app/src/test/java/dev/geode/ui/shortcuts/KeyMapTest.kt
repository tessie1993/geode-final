package dev.geode.ui.shortcuts

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The key map (#410) as a table: every key the owner named, and the keys it must leave alone. */
class KeyMapTest {
    @Test
    fun `every named key presses its shortcut`() {
        val expected =
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
        expected.forEach { (key, shortcut) -> assertEquals("key $key", shortcut, KeyMap.shortcutFor(key)) }
        assertEquals(Shortcut.entries.toSet(), expected.values.toSet())
    }

    @Test
    fun `media keys stay the media session's and Back stays the App UI's`() {
        val left =
            listOf(
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_STOP,
                KeyEvent.KEYCODE_HEADSETHOOK,
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_TAB,
                KeyEvent.KEYCODE_A,
            )
        left.forEach { assertNull("key $it", KeyMap.shortcutFor(it)) }
    }

    @Test
    fun `a shortcut is a bare key, so any modifier leaves the key to the system`() {
        val held =
            listOf(
                KeyModifiers(shift = true),
                KeyModifiers(ctrl = true),
                KeyModifiers(alt = true),
                KeyModifiers(meta = true),
            )
        held.forEach { modifiers ->
            assertNull("$modifiers S", KeyMap.shortcutFor(KeyEvent.KEYCODE_S, modifiers))
            assertNull("$modifiers left", KeyMap.shortcutFor(KeyEvent.KEYCODE_DPAD_LEFT, modifiers))
            assertFalse(modifiers.none)
        }
        assertTrue(KeyModifiers.NONE.none)
        assertEquals(Shortcut.SNAPSHOT, KeyMap.shortcutFor(KeyEvent.KEYCODE_S, KeyModifiers.NONE))
    }

    @Test
    fun `only stepping repeats while a key is held, and the arrows and Esc belong to the Visual`() {
        val repeating = Shortcut.entries.filter { it.repeats }.toSet()
        assertEquals(
            setOf(Shortcut.PREVIOUS_STYLE, Shortcut.NEXT_STYLE, Shortcut.SENSITIVITY_UP, Shortcut.SENSITIVITY_DOWN),
            repeating,
        )
        assertEquals(repeating + Shortcut.EXIT_FULL_SCREEN, Shortcut.entries.filter { it.overVisualOnly }.toSet())
    }
}
