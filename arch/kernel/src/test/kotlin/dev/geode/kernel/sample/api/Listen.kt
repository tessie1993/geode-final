package dev.geode.kernel.sample.api

import dev.geode.kernel.Feature

// A sample feature that exists only in tests, to prove the foundation end to end. Its packages stand
// for the modules of a real feature (ADR-0005): this one for `:feature:listen:api`, `core` for
// `:feature:listen:core` and `handlers` for `:feature:listen:handlers`. It has no screens. It listens
// to a capture source and notices when the capture is lost. It is not Capture (#8).

/** The public interface: what other features and the screens may use. */
typealias Listen = Feature<ListenState, ListenCommand, ListenFact>

enum class ListenSource {
    MICROPHONE,
}

enum class ListenProblem {
    PERMISSION,
    UNAVAILABLE,
    DID_NOT_START,
}

sealed interface ListenState {
    data object Off : ListenState

    data class Starting(
        val source: ListenSource,
    ) : ListenState

    data class Listening(
        val source: ListenSource,
        val quiet: Boolean,
    ) : ListenState

    data class Failed(
        val problem: ListenProblem,
    ) : ListenState
}

sealed interface ListenCommand {
    data class Start(
        val source: ListenSource,
    ) : ListenCommand

    data object Stop : ListenCommand
}

sealed interface ListenFact {
    data object Lost : ListenFact
}
