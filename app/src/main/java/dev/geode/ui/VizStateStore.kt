package dev.geode.ui

import android.content.SharedPreferences
import dev.geode.data.Preset
import dev.geode.data.PresetStore
import dev.geode.render.TransitionCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

internal class VizStateStore(
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
) {
    val state = MutableStateFlow(restore())

    @Volatile
    private var dirty = false

    private val scheduled = AtomicBoolean(false)

    private fun restore(): VizUiState {
        var base = VizUiState()
        // Restore the transition id first, then re-derive the style from it (falling back to
        // whatever default the style key carries) so the two never disagree after a restore.
        val transitionId = prefs.getString(KEY_TRANSITION_ID, null)
        if (transitionId != null) {
            base =
                base.copy(
                    transitionId = transitionId,
                    transitionStyle = TransitionCatalog.builtIn(transitionId) ?: base.transitionStyle,
                )
        }
        if (prefs.contains(KEY_TRANSITION_DURATION_SEC)) {
            base = base.copy(transitionDurationSec = prefs.getFloat(KEY_TRANSITION_DURATION_SEC, base.transitionDurationSec))
        }
        val json = prefs.getString(KEY_LIVE_STATE, null) ?: return base
        return runCatching {
            val p = PresetStore.fromJson(json)
            base.copy(sceneId = p.sceneId, attack = p.attack, decay = p.decay, params = p.params)
        }.getOrDefault(base)
    }

    fun persist() {
        dirty = true
        if (!scheduled.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            delay(PERSIST_WINDOW_MS)
            scheduled.set(false)
            write()
        }
    }

    fun flushIfDirty() {
        if (dirty) write()
    }

    private fun write() {
        dirty = false
        val s = state.value
        val json = PresetStore.toJson(Preset("__live__", s.sceneId, s.attack, s.decay, s.params))
        prefs
            .edit()
            .putString(KEY_LIVE_STATE, json)
            .putString(KEY_TRANSITION_ID, s.transitionId)
            .putFloat(KEY_TRANSITION_DURATION_SEC, s.transitionDurationSec)
            .commit()
    }

    private companion object {
        const val KEY_LIVE_STATE = "live_state"
        const val KEY_TRANSITION_ID = "transition_id"
        const val KEY_TRANSITION_DURATION_SEC = "transition_duration_sec"
        const val PERSIST_WINDOW_MS = 400L
    }
}
