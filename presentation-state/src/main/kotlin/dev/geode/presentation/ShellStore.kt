package dev.geode.presentation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Serializes transitions with their effects. Rendering never owns jobs or executes effects. */
class ShellStore(
    scope: CoroutineScope,
    initialState: ShellState,
    private val currentPreferences: () -> ShellPreferences?,
    private val execute: suspend (ShellEffect) -> Unit,
) {
    private val mutableState = MutableStateFlow(initialState)
    val state = mutableState.asStateFlow()
    private val actions = Channel<ShellAction>(Channel.UNLIMITED)
    private val mutableFailure = MutableStateFlow(false)
    val effectFailed = mutableFailure.asStateFlow()

    init {
        scope
            .launch {
                for (action in actions) {
                    // A notification can queue while a write suspends. Consume current preferences.
                    val currentAction =
                        if (action is ShellAction.Configure) {
                            action.copy(preferences = currentPreferences() ?: action.preferences)
                        } else {
                            action
                        }
                    val transition = ShellReducer.reduce(mutableState.value, currentAction)
                    try {
                        transition.effects.forEach { execute(it) }
                        mutableState.value = transition.state
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        mutableFailure.value = true
                    }
                }
            }.invokeOnCompletion { actions.close() }
    }

    fun dispatch(action: ShellAction) {
        actions.trySend(action)
    }

    fun dismissFailure() {
        mutableFailure.value = false
    }
}
