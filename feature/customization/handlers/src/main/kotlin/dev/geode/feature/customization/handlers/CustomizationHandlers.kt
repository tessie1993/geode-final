package dev.geode.feature.customization.handlers

import android.content.Context
import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.CustomizationCommand
import dev.geode.feature.customization.api.CustomizationFact
import dev.geode.feature.customization.api.CustomizationFeature
import dev.geode.feature.customization.api.CustomizationState
import dev.geode.feature.customization.core.BuiltInStyles
import dev.geode.feature.customization.core.CUSTOMIZATION_FEATURE
import dev.geode.feature.customization.core.CustomizationEvent
import dev.geode.feature.customization.core.CustomizationReducer
import dev.geode.feature.customization.core.CustomizationWork
import dev.geode.feature.customization.core.Defaults
import dev.geode.feature.customization.core.StyleCatalogue
import dev.geode.feature.customization.core.WorkFailure
import dev.geode.feature.settings.api.Settings
import dev.geode.kernel.EffectRunner
import dev.geode.kernel.FeatureRuntime
import dev.geode.kernel.FeatureStore
import dev.geode.kernel.Results
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Carries out Customization's work (ADR-0005): it hands each Customization to the [engine], saves,
 * loads and upgrades the preset files in the [presets] library, and passes on the engine's word that
 * Style switching is due from [styleCues]. Everything that fails at this edge comes back to the reducer
 * as a named [WorkFailure], which reports it on the app-wide error channel (ADR-0004); nothing here
 * stops the audio or the Visual.
 */
class CustomizationHandlers(
    private val engine: EnginePublisher,
    private val presets: PresetLibrary,
    private val styleCues: StyleCues = StyleCues(),
) : EffectRunner<CustomizationWork, CustomizationEvent> {
    override suspend fun run(
        work: CustomizationWork,
        results: Results<CustomizationEvent>,
    ) {
        val event =
            when (work) {
                is CustomizationWork.Publish -> publish(work)
                is CustomizationWork.SavePreset -> save(work)
                is CustomizationWork.LoadPreset ->
                    runCatching { presets.load(work.id) }.fold(
                        onSuccess = { CustomizationEvent.PresetLoaded(it) },
                        onFailure = { failed(WorkFailure.PRESET_NOT_LOADED) },
                    )
                CustomizationWork.UpgradePresets ->
                    runCatching { presets.upgradeOld() }.fold(
                        onSuccess = { null },
                        onFailure = { failed(WorkFailure.PRESETS_NOT_UPGRADED) },
                    )
                CustomizationWork.FollowStyleCues -> {
                    styleCues.cues.collect { results.report(CustomizationEvent.StyleDue) }
                    null
                }
            }
        event?.let(results::report)
    }

    private fun publish(work: CustomizationWork.Publish): CustomizationEvent? {
        // A throw on the way to the bridge counts as a refusal too: the engine did not take it.
        val outcome = runCatching { engine.publish(work.customization, work.switchingHeld) }
        return if (outcome.isFailure || outcome.getOrNull() != null) failed(WorkFailure.ENGINE_REFUSED) else null
    }

    private fun save(work: CustomizationWork.SavePreset): CustomizationEvent? =
        runCatching { presets.save(work.preset) }.fold(
            onSuccess = { if (it == SaveOutcome.SAVED) null else failed(WorkFailure.PRESET_NOT_MIRRORED) },
            onFailure = { failed(WorkFailure.PRESET_NOT_SAVED) },
        )

    private fun failed(failure: WorkFailure): CustomizationEvent = CustomizationEvent.WorkFailed(failure)

    companion object {
        /**
         * The handlers on the phone: presets in the folders Settings' Default paths chose (the app's
         * own folder until the person picks one), the old app-wide slots read from where an older
         * Geode kept them, [fallbackStyle] (the app's single default Style, `SceneIds.DEFAULT`) for
         * a preset whose Style is gone, and the live Visual's [styleCues].
         */
        fun onDevice(
            context: Context,
            settings: Settings,
            engine: EnginePublisher,
            fallbackStyle: SceneId,
            styleCues: StyleCues,
            styles: StyleCatalogue = BuiltInStyles.catalogue,
        ): CustomizationHandlers =
            CustomizationHandlers(
                engine = engine,
                styleCues = styleCues,
                presets =
                    PresetLibrary(
                        places = AndroidPresetPlaces(context, settings),
                        legacy = SharedPreferencesLegacyModulation(context),
                        styles = styles,
                        fallbackStyle = fallbackStyle,
                    ),
            )
    }
}

/**
 * How `:app` builds Customization's one store (#17). It starts from [restored], the state saved when
 * Android last killed the app (ADR-0004), or else from [startStyle]'s own defaults. When it is built
 * it converts the presets an older Geode saved and hands the starting Customization to the engine.
 *
 * Its work runs one piece at a time, in the order the reducer asked for it, so the engine always ends
 * with the newest Customization even when the person drags a slider faster than snapshots cross.
 */
fun customizationStore(
    handlers: CustomizationHandlers,
    startStyle: SceneId,
    runtime: FeatureRuntime,
    restored: CustomizationState? = null,
    styles: StyleCatalogue = BuiltInStyles.catalogue,
): CustomizationFeature {
    val initial = restored ?: CustomizationState(Defaults.customization(styles.manifestOf(startStyle)))
    return FeatureStore<CustomizationState, CustomizationCommand, CustomizationEvent, CustomizationWork, CustomizationFact>(
        name = CUSTOMIZATION_FEATURE,
        initial = initial,
        reducer = CustomizationReducer(styles),
        toEvent = CustomizationEvent::Command,
        runner = handlers,
        runtime = inOrder(runtime),
        start = CustomizationReducer.start(initial),
    )
}

/**
 * [runtime] with its background work taken one piece at a time, in order. A dispatcher that runs work
 * in place keeps the order already.
 */
private fun inOrder(runtime: FeatureRuntime): FeatureRuntime {
    val background = runtime.background
    if (!background.isDispatchNeeded(EmptyCoroutineContext)) return runtime
    return FeatureRuntime(runtime.main, background.limitedParallelism(1), runtime.timers, runtime.errors)
}
