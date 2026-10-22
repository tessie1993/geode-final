package dev.geode.feature.diagnostics.core

import dev.geode.feature.diagnostics.api.EngineStats
import kotlinx.coroutines.flow.Flow

// What Diagnostics reads from the rest of the app. Each is a narrow port that :app wires to the real
// thing at the switch-over (ADR-0003); until then only the fakes in this module's test fixtures
// implement them.

/**
 * The engine's live stats for the developer overlay. The native engine does not publish them yet;
 * :app wires the real source once it does.
 */
fun interface EngineStatsSource {
    /** A sample every so often while collected; nothing is measured for a collector that is gone. */
    fun stats(): Flow<EngineStats>
}

/** The app's local log, newest line last (today's RingLog). */
fun interface LogSource {
    fun lines(): List<String>
}

/** The user's current settings, as names and shown values. */
fun interface SettingsSource {
    suspend fun current(): List<Setting>
}

/** What the phone and the app are: the only facts about the device the report carries. */
interface SystemFacts {
    fun app(): AppFacts

    fun device(): DeviceFacts
}
