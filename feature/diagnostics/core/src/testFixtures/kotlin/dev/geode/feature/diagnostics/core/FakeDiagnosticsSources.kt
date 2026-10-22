package dev.geode.feature.diagnostics.core

import dev.geode.feature.diagnostics.api.EngineStats
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** An [EngineStatsSource] whose samples a test sends with [sample]; it reports how many collect. */
class FakeEngineStatsSource : EngineStatsSource {
    private val samples =
        MutableSharedFlow<EngineStats>(extraBufferCapacity = SAMPLE_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** How many collect the stats right now: the engine measures only while someone does. */
    val collectors: Int get() = samples.subscriptionCount.value

    override fun stats(): Flow<EngineStats> = samples

    fun sample(stats: EngineStats) {
        samples.tryEmit(stats)
    }

    private companion object {
        const val SAMPLE_BUFFER = 16
    }
}

/** A [LogSource] holding [log]. */
class FakeLogSource(
    var log: List<String> = emptyList(),
) : LogSource {
    override fun lines(): List<String> = log
}

/** A [SettingsSource] holding [settings]. */
class FakeSettingsSource(
    var settings: List<Setting> = emptyList(),
) : SettingsSource {
    override suspend fun current(): List<Setting> = settings
}

/** [SystemFacts] for a made-up phone. */
class FakeSystemFacts(
    private val app: AppFacts = AppFacts(versionName = "1.8.0", versionCode = 32),
    private val device: DeviceFacts = DeviceFacts(manufacturer = "Google", model = "Pixel 8", androidRelease = "15", sdk = 35),
) : SystemFacts {
    override fun app(): AppFacts = app

    override fun device(): DeviceFacts = device
}
