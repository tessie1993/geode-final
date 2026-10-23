package dev.geode.di

import android.app.Application
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.geode.RingLog
import dev.geode.feature.diagnostics.api.Diagnostics
import dev.geode.feature.diagnostics.core.EngineStatsSource
import dev.geode.feature.diagnostics.core.LogSource
import dev.geode.feature.diagnostics.core.Setting
import dev.geode.feature.diagnostics.core.SettingsSource
import dev.geode.feature.diagnostics.handlers.AndroidSystemFacts
import dev.geode.feature.diagnostics.handlers.ChooserShareSheet
import dev.geode.feature.diagnostics.handlers.DiagnosticsHandlers
import dev.geode.feature.diagnostics.handlers.androidReportFiles
import dev.geode.feature.diagnostics.handlers.diagnosticsStore
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.settings.api.SettingsState
import dev.geode.feature.settings.api.SettingsValues
import dev.geode.kernel.FeatureRuntime
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import javax.inject.Singleton

/**
 * Diagnostics' one app-scoped store (#62, ADR-0005): "Share diagnostics" and the developer overlay,
 * reached from Settings. The report reads the local log and the settings; the engine does not publish
 * its stats yet, so the overlay waits for its first sample until it does.
 */
@Module
@InstallIn(SingletonComponent::class)
@JvmSuppressWildcards
object DiagnosticsModule {
    @Provides
    @Singleton
    fun provideDiagnostics(
        application: Application,
        settings: Settings,
        runtime: FeatureRuntime,
    ): Diagnostics =
        diagnosticsStore(
            DiagnosticsHandlers(
                system = AndroidSystemFacts(application),
                log = LogSource { RingLog.dump().lines() },
                settings = SettingsSource { settingsOf(settings.state.filterIsInstance<SettingsState.Ready>().first().values) },
                engine = EngineStatsSource { emptyFlow() },
                files = androidReportFiles(application),
                shareSheet = ChooserShareSheet(application),
            ),
            runtime,
        )

    /** Each section of [values] as one line; the report hides the folders section, which names the user's things. */
    private fun settingsOf(values: SettingsValues): List<Setting> =
        listOf(
            Setting("look", values.look.toString()),
            Setting("playback", values.playback.toString()),
            Setting("equalizer", values.equalizer.toString()),
            Setting("analysis", values.analysis.toString()),
            Setting("behavior", values.behavior.toString()),
            Setting("export", values.export.toString()),
            Setting("folders", values.folders.toString()),
            Setting("onboarding", values.onboarding.toString()),
            Setting("background", values.background.toString()),
            Setting("performance", values.performance.toString()),
        )
}
