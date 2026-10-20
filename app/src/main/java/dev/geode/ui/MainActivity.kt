package dev.geode.ui

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { !settingsViewModel.userDataLoaded.value }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppRoot()
        }
        if (savedInstanceState == null) {
            importSharedPreset(intent)
            importSharedTemplate(intent)
            playFromSearch(intent)
        }
    }
}
