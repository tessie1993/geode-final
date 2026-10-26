package dev.geode.studio

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import dev.geode.R
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.settings.api.SettingsState
import dev.geode.feature.studio.api.Studio
import dev.geode.feature.studio.api.StudioFact
import dev.geode.feature.studio.api.Video
import dev.geode.feature.studio.api.VideoKind
import dev.geode.util.bestEffort
import kotlinx.coroutines.flow.filterIsInstance

/**
 * Export-finished alerts (#22, #28): when a Render's video, or an Edit exported through the render
 * queue (#318), lands in its Project, a notification on the Renders' channel says so, with Open and
 * Share. Settings' Export preferences can turn them off. The alert is the system's notification, so a
 * person who refused notifications simply gets none.
 */
class RenderAlerts(
    private val context: Context,
    private val studio: Studio,
    private val settings: Settings,
) {
    /** Follows Studio's facts for as long as the app runs. */
    suspend fun follow() {
        studio.events.filterIsInstance<StudioFact.VideosAdded>().collect { added ->
            if (!wanted()) return@collect
            val ids = added.videos.toSet()
            studio.state.value.videos
                .filter { it.id in ids && it.kind in ALERTED }
                .forEach { video -> bestEffort(TAG, "alert(video)") { alert(video) } }
        }
    }

    private fun wanted(): Boolean = (settings.state.value as? SettingsState.Ready)?.values?.export?.finishedAlerts ?: true

    private fun alert(video: Video) {
        RenderService.ensureChannel(context)
        val uri = Uri.parse(video.file)
        // Both taps go to Geode's own RenderAlertActivity by name; the implicit view and share intents
        // are only made there.
        val open = RenderAlertActivity.intent(context, uri, share = false)
        val share = RenderAlertActivity.intent(context, uri, share = true)
        val id = NOTIFICATION_BASE + (video.id.value % NOTIFICATION_SPAN).toInt()
        val notification =
            Notification
                .Builder(context, RenderService.CHANNEL_ID)
                .setContentTitle(context.getString(R.string.render_finished_title))
                .setContentText(video.name)
                .setSmallIcon(R.drawable.ic_stat_capture)
                .setAutoCancel(true)
                .setContentIntent(pending(id, open))
                .addAction(Notification.Action.Builder(icon(), context.getString(R.string.render_finished_open), pending(id, open)).build())
                .addAction(
                    Notification.Action.Builder(icon(), context.getString(R.string.render_finished_share), pending(id + 1, share)).build(),
                ).build()
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    private fun icon(): Icon = Icon.createWithResource(context, R.drawable.ic_stat_capture)

    private fun pending(
        request: Int,
        intent: Intent,
    ): PendingIntent =
        PendingIntent.getActivity(context, request, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private companion object {
        const val TAG = "RenderAlerts"

        /** The videos the render queue makes. */
        val ALERTED = setOf(VideoKind.RENDER, VideoKind.EDIT)

        // Apart from the progress notification's id; each video gets its own alert, and Share its own request.
        const val NOTIFICATION_BASE = 5_000
        const val NOTIFICATION_SPAN = 10_000L
    }
}
