package dev.geode.feature.settings.api

import dev.geode.kernel.Feature

/**
 * Settings as everything outside it sees it (ADR-0003, ADR-0005). Settings sits at the bottom of the
 * feature graph: other features only read its [SettingsState] and send it [SettingsCommand]s. It has
 * no events of its own.
 *
 * Settings persist on their own and are never "restored over" (ADR-0004): every change is saved by
 * Settings itself, and nothing else writes them.
 */
typealias Settings = Feature<SettingsState, SettingsCommand, Nothing>

sealed interface SettingsState {
    /**
     * The stored settings are being read. Commands sent meanwhile wait in [pending] and are applied,
     * in order, once they are read.
     */
    data class Loading(
        val pending: List<SettingsCommand> = emptyList(),
    ) : SettingsState

    data class Ready(
        val values: SettingsValues,
    ) : SettingsState
}
