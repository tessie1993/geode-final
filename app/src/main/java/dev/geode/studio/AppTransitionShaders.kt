package dev.geode.studio

import android.content.Context
import dev.geode.feature.studio.handlers.edit.TransitionParam
import dev.geode.feature.studio.handlers.edit.TransitionShader
import dev.geode.feature.studio.handlers.edit.TransitionShaders
import dev.geode.render.TransitionCatalog

/**
 * The Edit pipeline's GL Transitions (#318), from the engine's transition catalogue
 * (`gl_transitions.json`), which Studio's handlers may not reach themselves (ADR-0005). The engine's
 * built-in transitions have no GL Transition shader: a clip that names one cuts.
 */
class AppTransitionShaders(
    private val context: Context,
) : TransitionShaders {
    override fun shader(id: String): TransitionShader? =
        TransitionCatalog.definition(context, id)?.let { def ->
            TransitionShader(def.glsl, def.params.map { TransitionParam(it.name, it.type, it.values) })
        }
}
