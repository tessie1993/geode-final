package dev.geode.ui

import android.content.SharedPreferences
import dev.geode.ui.theme.ThemePack
import dev.geode.ui.theme.ThemePackCatalog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class GeodeUserData(
    val theme: ThemePack,
    val guiPrefs: GuiPrefs,
)

interface UserDataRepository {
    val userData: StateFlow<GeodeUserData>

    val theme: StateFlow<ThemePack>

    val guiPrefs: StateFlow<GuiPrefs>

    val loaded: StateFlow<Boolean>

    suspend fun setTheme(pack: ThemePack)

    suspend fun setGuiPrefs(prefs: GuiPrefs)

    suspend fun updateGuiPrefs(transform: (GuiPrefs) -> GuiPrefs)
}

class SharedPrefsUserDataRepository(
    prefs: SharedPreferences,
    scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : UserDataRepository {
    private val store = ThemeStore(prefs)
    private val guiWrites = Mutex()

    private val _theme = MutableStateFlow(ThemePackCatalog.bySlug(null))
    override val theme: StateFlow<ThemePack> = _theme.asStateFlow()

    private val _guiPrefs = MutableStateFlow(GuiPrefs())
    override val guiPrefs: StateFlow<GuiPrefs> = _guiPrefs.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    override val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    override val userData: StateFlow<GeodeUserData> =
        combine(_theme, _guiPrefs, ::GeodeUserData)
            .stateIn(scope, SharingStarted.Eagerly, GeodeUserData(_theme.value, _guiPrefs.value))

    init {
        scope.launch {
            val pack = withContext(ioDispatcher) { store.load() }
            val gui = withContext(ioDispatcher) { store.loadGui() }
            if (!_loaded.value) {
                _theme.value = pack
                _guiPrefs.value = gui
                _loaded.value = true
            }
        }
    }

    override suspend fun setTheme(pack: ThemePack) {
        _theme.value = pack
        _loaded.value = true
        withContext(ioDispatcher) { store.save(pack) }
    }

    override suspend fun setGuiPrefs(prefs: GuiPrefs) {
        writeGuiPrefs(requireCommit = false) { prefs }
    }

    override suspend fun updateGuiPrefs(transform: (GuiPrefs) -> GuiPrefs) {
        writeGuiPrefs(requireCommit = true, transform = transform)
    }

    private suspend fun writeGuiPrefs(
        requireCommit: Boolean,
        transform: (GuiPrefs) -> GuiPrefs,
    ) {
        guiWrites.withLock {
            val updated = transform(_guiPrefs.value)
            // Commit on IO before publishing. Failed writes never advance onboarding state.
            withContext(ioDispatcher) { store.saveGui(updated, requireCommit) }
            _guiPrefs.value = updated
            _loaded.value = true
        }
    }
}
