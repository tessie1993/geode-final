package dev.geode.feature.diagnostics.handlers

import dev.geode.engine.types.Millis
import dev.geode.feature.diagnostics.api.Diagnostics
import dev.geode.feature.diagnostics.api.DiagnosticsCommand
import dev.geode.feature.diagnostics.api.DiagnosticsFact
import dev.geode.feature.diagnostics.api.DiagnosticsState
import dev.geode.feature.diagnostics.api.EngineStats
import dev.geode.feature.diagnostics.core.DiagnosticsEvent
import dev.geode.feature.diagnostics.core.DiagnosticsReducer
import dev.geode.feature.diagnostics.core.DiagnosticsReport
import dev.geode.feature.diagnostics.core.DiagnosticsWork
import dev.geode.feature.diagnostics.core.EngineStatsSource
import dev.geode.feature.diagnostics.core.LogSource
import dev.geode.feature.diagnostics.core.ReportFile
import dev.geode.feature.diagnostics.core.ReportInputs
import dev.geode.feature.diagnostics.core.SettingsSource
import dev.geode.feature.diagnostics.core.SystemFacts
import dev.geode.kernel.EffectRunner
import dev.geode.kernel.FeatureRuntime
import dev.geode.kernel.FeatureStore
import dev.geode.kernel.Perform
import dev.geode.kernel.Results
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest

/** The name Diagnostics reports its failures under on the app-wide error channel. */
const val DIAGNOSTICS = "diagnostics"

/** Where the diagnostics file is written; a content uri to it is what other apps get. */
fun interface ReportFiles {
    /** Writes [text] as the diagnostics file, replacing the last one. Throws when it cannot. */
    fun write(text: String): ReportFile
}

/** The system share sheet. */
fun interface ShareSheet {
    /** Opens the share sheet with [file]; false when nothing can receive it. */
    fun open(file: ReportFile): Boolean
}

/**
 * Carries out Diagnostics' work (ADR-0005): gathers the report from the app's ports, writes it, opens
 * the share sheet, and forwards the engine's stats while the overlay shows them. Every failure at
 * its edge comes back as an event; nothing leaves the phone except through the share sheet the user
 * opened.
 */
class DiagnosticsHandlers(
    private val system: SystemFacts,
    private val log: LogSource,
    private val settings: SettingsSource,
    private val engine: EngineStatsSource,
    private val files: ReportFiles,
    private val shareSheet: ShareSheet,
    private val clock: () -> Millis = { Millis(System.currentTimeMillis()) },
) : EffectRunner<DiagnosticsWork, DiagnosticsEvent> {
    // Whether the overlay wants the engine's stats; collected only while it does.
    private val sampling = MutableStateFlow(false)

    override suspend fun run(
        work: DiagnosticsWork,
        results: Results<DiagnosticsEvent>,
    ) {
        when (work) {
            DiagnosticsWork.FollowEngine ->
                sampling.collectLatest { on ->
                    if (on) engine.stats().collect { results.report(DiagnosticsEvent.StatsSampled(it)) }
                }
            is DiagnosticsWork.SampleEngine -> sampling.value = work.on
            is DiagnosticsWork.WriteReport -> results.report(write(work.engine))
            is DiagnosticsWork.OpenShareSheet ->
                results.report(if (open(work.file)) DiagnosticsEvent.ShareOpened else DiagnosticsEvent.NoShareTarget)
        }
    }

    private suspend fun write(engine: EngineStats?): DiagnosticsEvent {
        val inputs =
            ReportInputs(
                generatedAt = clock(),
                app = system.app(),
                device = system.device(),
                settings = settings.current(),
                engine = engine,
                log = log.lines(),
            )
        val text = DiagnosticsReport.compose(inputs)
        return runCatching { files.write(text) }
            .fold(onSuccess = { DiagnosticsEvent.ReportWritten(it) }, onFailure = { DiagnosticsEvent.WriteFailed })
    }

    private fun open(file: ReportFile): Boolean = runCatching { shareSheet.open(file) }.getOrDefault(false)
}

/** How `:app` builds Diagnostics' one store; following the engine is its start effect. */
fun diagnosticsStore(
    handlers: DiagnosticsHandlers,
    runtime: FeatureRuntime,
): Diagnostics =
    FeatureStore<DiagnosticsState, DiagnosticsCommand, DiagnosticsEvent, DiagnosticsWork, DiagnosticsFact>(
        name = DIAGNOSTICS,
        initial = DiagnosticsState(),
        reducer = DiagnosticsReducer,
        toEvent = DiagnosticsEvent::Command,
        runner = handlers,
        runtime = runtime,
        start = listOf(Perform(DiagnosticsWork.FollowEngine)),
    )
