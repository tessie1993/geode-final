package dev.geode.ui

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.geode.R
import dev.geode.feature.library.api.LibraryCommand
import dev.geode.feature.library.api.Pick
import dev.geode.feature.settings.api.SettingsState
import dev.geode.feature.visuals.api.SnapshotProblem
import dev.geode.feature.visuals.api.VisualsCommand
import dev.geode.feature.visuals.api.VisualsFact
import dev.geode.feature.visuals.core.PresetLinks
import dev.geode.ui.shell.ShellViewModel
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val shell: ShellViewModel by viewModels()

    private fun playFromSearch(intent: Intent?) {
        if (intent?.action != MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) return
        // Library finds the songs and plays what the search lists, as a search in Music would (#17).
        shell.library.send(LibraryCommand.Search(intent.getStringExtra(SearchManager.QUERY).orEmpty()))
        shell.library.send(LibraryCommand.Play(Pick.Shown))
        intent.action = null
    }

    private fun importSharedPreset(intent: Intent?) {
        val data = intent?.data?.toString() ?: return
        if (PresetLinks.findIn(data) == null) return
        // Visuals saves the shared preset; what came of it is told below, as its facts arrive (#17).
        shell.visuals.send(VisualsCommand.ImportPresetLink(data))
        intent.data = null
    }

    /** How a shared preset's import went, and a Snapshot's short confirmation (#405), wherever either was asked for. */
    private suspend fun tellVisualsFacts() {
        shell.visuals.events.collect { fact ->
            val (message, length) =
                when (fact) {
                    is VisualsFact.PresetImported -> getString(R.string.preset_link_imported, fact.id.value) to Toast.LENGTH_LONG
                    is VisualsFact.PresetNotImported -> getString(R.string.preset_link_unreadable) to Toast.LENGTH_LONG
                    is VisualsFact.SnapshotSaved -> getString(R.string.snapshot_saved) to Toast.LENGTH_SHORT
                    is VisualsFact.SnapshotNotSaved -> getString(snapshotProblem(fact.problem)) to Toast.LENGTH_SHORT
                    else -> return@collect
                }
            Toast.makeText(this, message, length).show()
        }
    }

    private fun snapshotProblem(problem: SnapshotProblem): Int =
        when (problem) {
            SnapshotProblem.NO_VISUAL -> R.string.snapshot_no_visual
            SnapshotProblem.NOT_SAVED -> R.string.snapshot_not_saved
        }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        importSharedPreset(intent)
        playFromSearch(intent)
    }

    /**
     * The platform splash stays up until the first frame is drawn, so holding that frame back
     * holds the splash: drawing resumes once the user's data has loaded.
     */
    private fun keepSplashUntilUserDataLoaded() {
        val content = findViewById<View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    val ready = shell.settings.state.value is SettingsState.Ready
                    if (ready) content.viewTreeObserver.removeOnPreDrawListener(this)
                    return ready
                }
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppRoot()
        }
        keepSplashUntilUserDataLoaded()
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { tellVisualsFacts() } }
        if (savedInstanceState == null) {
            importSharedPreset(intent)
            playFromSearch(intent)
        }
    }
}
