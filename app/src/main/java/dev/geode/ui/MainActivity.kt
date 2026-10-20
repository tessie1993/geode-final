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
import dagger.hilt.android.AndroidEntryPoint
import dev.geode.R

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val settingsViewModel: SettingsViewModel by viewModels()

    private val visualsViewModel: VisualsViewModel by viewModels()

    private val playerViewModel: PlayerViewModel by viewModels()

    private fun playFromSearch(intent: Intent?) {
        if (intent?.action != MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) return
        playerViewModel.playFromSearch(intent.getStringExtra(SearchManager.QUERY).orEmpty())
        intent.action = null
    }

    private fun importSharedPreset(intent: Intent?) {
        val data = intent?.data?.toString() ?: return
        val message =
            when (val result = visualsViewModel.importSharedPreset(data)) {
                PresetLinkImport.NotALink -> return
                is PresetLinkImport.Imported -> getString(R.string.preset_link_imported, result.name.value)
                PresetLinkImport.Unreadable -> getString(R.string.preset_link_unreadable)
            }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        intent.data = null
    }

    /**
     * Mirrors [importSharedPreset]: only consumes the intent's data once it is confirmed
     * to be a template link, so a preset link that reached here first is left alone for
     * it to have handled and a link neither of them recognises is left for the platform.
     */
    private fun importSharedTemplate(intent: Intent?) {
        val data = intent?.data?.toString() ?: return
        if (!visualsViewModel.isTemplateLink(data)) return
        intent.data = null
        visualsViewModel.importSharedTemplate(data) { result ->
            val message =
                when (result) {
                    is TemplateLinkImport.Imported -> getString(R.string.template_link_imported, result.name)
                    is TemplateLinkImport.Replaced -> getString(R.string.template_link_imported, result.name)
                    is TemplateLinkImport.Unreadable -> getString(R.string.template_link_unreadable)
                }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        importSharedPreset(intent)
        importSharedTemplate(intent)
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
        if (savedInstanceState == null) {
            importSharedPreset(intent)
            importSharedTemplate(intent)
            playFromSearch(intent)
        }
    }
}
