package dev.geode.ui

import dev.geode.analysis.AnalysisEngine
import dev.geode.render.scene.SceneParams
import kotlinx.coroutines.flow.update

internal class VisualSettingsController(
    private val engine: AnalysisEngine,
    private val vizStateStore: VizStateStore,
    private val host: Host,
) {
    interface Host {
        val guiPrefs: GuiPrefs

        fun setGuiPrefs(prefs: GuiPrefs)
    }

    private val state get() = vizStateStore.state

    fun setReactivity(
        attack: Float,
        decay: Float,
    ) {
        engine.attack = attack
        engine.decay = decay
        state.update { it.copy(attack = attack, decay = decay) }
        vizStateStore.persist()
    }

    fun setSceneParams(params: SceneParams) {
        state.update { it.copy(params = params) }
        vizStateStore.persist()
    }

    }
}
