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
import dev.geode.feature.visuals.api.Visuals
import dev.geode.feature.visuals.api.VisualsCommand
import dev.geode.feature.visuals.api.VisualsFact
import dev.geode.feature.visuals.core.PresetLinks
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val settingsViewModel: SettingsViewModel by viewModels()

    /** The Visuals store, which imports a preset link shared into the app (#144). */
    @Inject
    lateinit var visuals: @JvmSuppressWildcards Visuals

    private val playerViewModel: PlayerViewModel by viewModels()

    private fun playFromSearch(intent: Intent?) {
        if (intent?.action != MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) return
        playerViewModel.playFromSearch(intent.getStringExtra(SearchManager.QUERY).orEmpty())
        intent.action = null
    }

    private fun importSharedPreset(intent: Intent?) {
        val link = intent?.data?.toString()?.let(PresetLinks::findIn) ?: return
        visuals.send(VisualsCommand.ImportPresetLink(link))
        intent.data = null
    }

    /** How a preset link shared into the app went, said once it has been imported or refused. */
    private fun reportPresetImports() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                visuals.events.collect { fact ->
                    val message =
                        when (fact) {
                            is VisualsFact.PresetImported -> getString(R.string.preset_link_imported, fact.id.value)
                            is VisualsFact.PresetNotImported -> getString(R.string.preset_link_unreadable)
                            else -> return@collect
                        }
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
            }
        }
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
                    val ready = settingsViewModel.userDataLoaded.value
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
        reportPresetImports()
        if (savedInstanceState == null) {
            importSharedPreset(intent)
            playFromSearch(intent)
        }
    }
}
