package dev.geode.ui

import android.content.SharedPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class UserDataPublicationTest {
    @Test
    fun `failed commit never publishes acknowledged onboarding even if preference memory changed`() =
        runTest {
            val storage = PreferencesFake()
            val repository = SharedPrefsUserDataRepository(storage.preferences, backgroundScope, StandardTestDispatcher(testScheduler))
            runCurrent()
            assertTrue(repository.loaded.value)
            storage.commitSucceeds = false
            var failed = false
            launch {
                try {
                    repository.updateGuiPrefs { it.copy(safetyAcknowledged = true) }
                } catch (_: IOException) {
                    failed = true
                }
            }
            runCurrent()
            assertTrue(failed)
            // Android commits update preference memory even if writing the disk fails.
            assertTrue(storage.preferences.getBoolean(ThemeStore.KEY_SAFETY_ACKNOWLEDGED, false))
            assertFalse(repository.guiPrefs.value.safetyAcknowledged)
            storage.commitSucceeds = true
            launch { repository.updateGuiPrefs { it.copy(safetyAcknowledged = true) } }
            runCurrent()
            assertTrue(repository.guiPrefs.value.safetyAcknowledged)
        }

    @Test
    fun `serialized partial updates preserve changes from the previous writer`() =
        runTest {
            val storage = PreferencesFake()
            val repository = SharedPrefsUserDataRepository(storage.preferences, backgroundScope, StandardTestDispatcher(testScheduler))
            runCurrent()
            launch { repository.updateGuiPrefs { it.copy(safetyAcknowledged = true) } }
            launch { repository.updateGuiPrefs { it.copy(setupDone = true) } }
            runCurrent()
            assertTrue(repository.guiPrefs.value.safetyAcknowledged)
            assertTrue(repository.guiPrefs.value.setupDone)
            assertEquals(2, storage.commits)
        }

    /** Models the actual SharedPreferences memory-before-disk contract without Android runtime IO. */
    private class PreferencesFake {
        var commitSucceeds = true
        var commits = 0
        private val values = mutableMapOf<String, Any?>()
        val preferences: SharedPreferences =
            proxy(SharedPreferences::class.java) { name, args ->
                when {
                    name == "edit" -> editor()
                    name == "contains" -> values.containsKey(args[0])
                    name == "getAll" -> values.toMap()
                    name.startsWith("get") -> values[args[0]] ?: args[1]
                    else -> null
                }
            }

        private fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, Any?>()
            lateinit var editor: SharedPreferences.Editor
            editor =
                proxy(SharedPreferences.Editor::class.java) { name, args ->
                    when {
                        name.startsWith("put") -> {
                            pending[args[0] as String] = args[1]
                            editor
                        }
                        name == "remove" -> {
                            pending[args[0] as String] = null
                            editor
                        }
                        name == "clear" -> {
                            values.clear()
                            editor
                        }
                        name == "commit" -> {
                            commits++
                            values.putAll(pending)
                            commitSucceeds
                        }
                        name == "apply" -> error("GUI publication requires a checked commit")
                        else -> null
                    }
                }
            return editor
        }

        private fun <T> proxy(
            type: Class<T>,
            call: (String, Array<out Any?>) -> Any?,
        ): T = type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args -> call(method.name, args.orEmpty()) })
    }
}
