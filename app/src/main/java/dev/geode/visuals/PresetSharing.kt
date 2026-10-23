package dev.geode.visuals

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.geode.engine.types.PresetId
import dev.geode.feature.customization.api.Customization
import dev.geode.feature.customization.core.PresetRules
import dev.geode.feature.customization.handlers.PresetFormat
import dev.geode.feature.customization.handlers.PresetLibrary
import dev.geode.feature.visuals.api.PresetShare
import dev.geode.feature.visuals.core.PresetLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands a preset to the phone's share sheet (the app stays offline): as a link when it is small
 * enough for one, otherwise as its file, offered through the presets file provider.
 */
@Singleton
class PresetSharing
    @Inject
    constructor(
        private val application: Application,
        private val library: PresetLibrary,
    ) {
        /** Shares a saved preset as the Presets browser made it ready (#144). */
        @Suppress("InjectDispatcher")
        suspend fun share(
            context: Context,
            share: PresetShare,
        ) {
            when (share) {
                is PresetShare.Link -> send(context, linkIntent(share.id.value, share.link))
                is PresetShare.File -> {
                    val text = withContext(Dispatchers.IO) { runCatching { library.text(share.id) }.getOrNull() } ?: return
                    fileIntent(share.id.value, text)?.let { send(context, it) }
                }
            }
        }

        /** Shares the Customization on show as a preset named [name] that keeps every part (Customize's Share). */
        @Suppress("InjectDispatcher")
        suspend fun share(
            context: Context,
            name: String,
            customization: Customization,
        ) {
            val text = PresetFormat.encode(PresetRules.capture(name, customization, PresetRules.ALL_PARTS))
            val link = withContext(Dispatchers.Default) { PresetLinks.encode(text) }
            val intent = if (PresetLinks.fits(link)) linkIntent(name, link) else fileIntent(name, text)
            intent?.let { send(context, it) }
        }

        private fun linkIntent(
            name: String,
            link: String,
        ): Intent =
            Intent(Intent.ACTION_SEND).apply {
                type = TEXT
                putExtra(Intent.EXTRA_SUBJECT, name)
                putExtra(Intent.EXTRA_TEXT, link)
            }

        @Suppress("InjectDispatcher")
        private suspend fun fileIntent(
            name: String,
            text: String,
        ): Intent? {
            val file =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val folder = File(application.cacheDir, SHARED_FOLDER).apply { mkdirs() }
                        File(folder, PresetLibrary.fileNameOf(name)).apply { writeText(text) }
                    }.getOrNull()
                } ?: return null
            val uri = FileProvider.getUriForFile(application, application.packageName + AUTHORITY, file)
            return Intent(Intent.ACTION_SEND).apply {
                type = JSON
                putExtra(Intent.EXTRA_SUBJECT, name)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        private fun send(
            context: Context,
            intent: Intent,
        ) {
            runCatching { context.startActivity(Intent.createChooser(intent, null)) }
        }

        private companion object {
            const val TEXT = "text/plain"
            const val JSON = "application/json"
            const val AUTHORITY = ".presets"
            const val SHARED_FOLDER = "shared-presets"
        }
    }
