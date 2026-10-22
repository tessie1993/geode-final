package dev.geode.feature.visuals.handlers

import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.Customization
import dev.geode.feature.customization.api.CustomizationCommand
import dev.geode.feature.customization.api.CustomizationFact
import dev.geode.feature.customization.api.CustomizationFeature
import dev.geode.feature.customization.api.CustomizationState
import dev.geode.feature.customization.api.StyleFamily
import dev.geode.feature.customization.api.StyleManifest
import dev.geode.feature.visuals.api.StyleLibrary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/** A Customization showing [style]; nothing else of it matters here. */
fun customizationOf(style: SceneId): CustomizationState =
    CustomizationState(Customization(style, emptyMap(), emptyMap(), emptyList(), emptyList(), emptyList(), emptyMap()))

fun manifest(
    id: SceneId,
    family: StyleFamily,
): StyleManifest = StyleManifest(id, family, false, emptyList(), emptyMap(), emptyList(), emptyList())

/** Customization as Visuals sees it: it switches Style when asked, and records every Command. */
class FakeCustomization(
    style: SceneId,
) : CustomizationFeature {
    private val mutableState = MutableStateFlow(customizationOf(style))
    override val state: StateFlow<CustomizationState> = mutableState
    override val events: Flow<CustomizationFact> = emptyFlow()

    val sent = mutableListOf<CustomizationCommand>()

    /** What the next send throws, once; null lets it through. */
    var failNextSend: Exception? = null

    override fun send(command: CustomizationCommand) {
        failNextSend?.let {
            failNextSend = null
            throw it
        }
        sent += command
        if (command is CustomizationCommand.ChooseStyle) show(command.style)
    }

    /** Customization moves to [style] by itself, as a preset or an undo would. */
    fun show(style: SceneId) {
        mutableState.value = customizationOf(style)
    }
}

/** Storage in memory, which can be made to fail. */
class FakeVisualsStorage(
    var saved: StyleLibrary? = null,
) : VisualsStorage {
    var failLoad = false
    var failSave = false
    var saves = 0

    override suspend fun load(): StyleLibrary {
        check(!failLoad) { "disk unreadable" }
        return saved ?: StyleLibrary()
    }

    override suspend fun save(library: StyleLibrary) {
        check(!failSave) { "disk full" }
        saves++
        saved = library
    }
}
