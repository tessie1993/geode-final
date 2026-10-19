package dev.geode.ui

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.geode.data.BootAnimationStore
import dev.geode.data.CrashReportStore
import dev.geode.data.GeodePrefsFiles
import dev.geode.presentation.GeodeDestination
import dev.geode.presentation.ShellAction
import dev.geode.presentation.ShellEffect
import dev.geode.presentation.ShellPreferences
import dev.geode.presentation.ShellSnapshot
import dev.geode.presentation.ShellStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Owns effects and restoration; the reducer alone decides navigation and entry-flow transitions. */
@HiltViewModel
class ShellViewModel
    @Inject
    constructor(
        application: Application,
        private val userData: UserDataRepository,
        private val savedState: SavedStateHandle,
    ) : ViewModel() {
        private val shell =
            ShellStore(
                scope = viewModelScope,
                initialState = ShellSnapshot.restore(savedState.get<ArrayList<String>>(SNAPSHOT)),
                currentPreferences = { userData.guiPrefs.value.shellPreferences() },
                execute = ::execute,
            )
        val state = shell.state
        private val mutableError = MutableStateFlow(false)
        val effectFailed =
            combine(shell.effectFailed, mutableError) { effect, diagnostic -> effect || diagnostic }
                .stateIn(viewModelScope, SharingStarted.Eagerly, false)
        private val crashStore = CrashReportStore(application.filesDir)
        private val mutableCrash = MutableStateFlow<String?>(null)
        val crashReport = mutableCrash.asStateFlow()

        init {
            viewModelScope.launch {
                state.collect { savedState[SNAPSHOT] = ArrayList(ShellSnapshot.save(it)) }
            }
            viewModelScope.launch {
                val bootEnabled =
                    try {
                        withContext(Dispatchers.IO) { BootAnimationStore(GeodePrefsFiles(application).general).load() }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        mutableError.value = true
                        true
                    }
                combine(userData.loaded, userData.guiPrefs) { loaded, prefs -> loaded to prefs }
                    .collect { (loaded, prefs) ->
                        if (loaded) dispatch(ShellAction.Configure(prefs.shellPreferences(), bootEnabled))
                    }
            }
            viewModelScope.launch {
                try {
                    mutableCrash.value = withContext(Dispatchers.IO) { crashStore.read() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutableError.value = true
                }
            }
        }

        fun dispatch(action: ShellAction) {
            shell.dispatch(action)
        }

        fun dismissError() {
            shell.dismissFailure()
            mutableError.value = false
        }

        fun dismissCrash() {
            viewModelScope.launch {
                try {
                    withContext(Dispatchers.IO) { crashStore.dismiss() }
                    mutableCrash.value = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutableError.value = true
                }
            }
        }

        private suspend fun execute(effect: ShellEffect) {
            userData.updateGuiPrefs { current ->
                when (effect) {
                    ShellEffect.SaveSafetyAcknowledged -> current.copy(safetyAcknowledged = true)
                    ShellEffect.SaveSetupDone -> current.copy(setupDone = true)
                    ShellEffect.SaveTutorialSeen -> current.copy(tutorialSeen = true)
                    is ShellEffect.SaveTutorialOffer -> current.copy(tutorialOnFirstRun = effect.enabled)
                }
            }
        }

        private companion object {
            const val SNAPSHOT = "shell_state_v1"
        }
    }

private fun GuiPrefs.shellPreferences(): ShellPreferences =
    ShellPreferences(
        safetyAcknowledged = safetyAcknowledged,
        setupDone = setupDone,
        tutorialSeen = tutorialSeen,
        tutorialOnFirstRun = tutorialOnFirstRun,
        showsStudio = intent.showsStudio,
        landingDestination = if (intent == UserIntent.MAKING_VIDEOS) GeodeDestination.STUDIO else GeodeDestination.PLAYER,
    )
