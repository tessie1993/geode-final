package dev.geode.studio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import dev.geode.feature.studio.api.RenderJob
import dev.geode.feature.studio.api.RenderStatus
import dev.geode.feature.studio.api.Studio
import dev.geode.feature.studio.api.StudioCommand
import dev.geode.feature.studio.api.StudioState
import dev.geode.studio.media.RenderEta
import dev.geode.util.bestEffort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps Studio's render queue alive while the app is not in front (ADR-0004, #316): a foreground
 * service of the media-processing type with a progress notification. It does not own the Renders —
 * Studio's store runs them — it follows Studio's state, shows how far the queue has got, and stops
 * once nothing waits or runs, or once Settings' Renders keep going is off (#28): then Renders run only
 * while the app is in front, and nothing needs keeping alive. It is sticky: when Android kills the process mid-Render it starts the
 * service again, which brings Studio's store back, and the store resumes the queue from its database.
 */
@AndroidEntryPoint
class RenderService : Service() {
    // Without @JvmSuppressWildcards the field's Java type gets ? super / ? extends for Feature's
    // in/out parameters, which the provided Studio lacks (as in GeodePlaybackService).
    @Inject
    lateinit var studio: @JvmSuppressWildcards Studio

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        bestEffort(TAG, "startForegroundNotification(studio.state.value)") { startForegroundNotification(studio.state.value) }
        watcher =
            scope.launch {
                studio.state.collect { state ->
                    if (!state.loading && (state.renders.none { it.active } || !state.rendersKeepGoing)) {
                        stopSelf()
                    } else {
                        bestEffort(TAG, "startForegroundNotification(state)") { startForegroundNotification(state) }
                    }
                }
            }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int = START_STICKY

    /** Android's time limit for media processing ran out: the Renders still waiting are cancelled. */
    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        val active =
            studio.state.value.renders
                .filter { it.active }
        studio.send(StudioCommand.CancelRenders(active.map { it.id }.toSet()))
        stopSelf()
    }

    override fun onDestroy() {
        watcher?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundNotification(state: StudioState) {
        ensureChannel(this)
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, dev.geode.ui.MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val running = state.runningRender
        val waiting = state.renders.count { it.status == RenderStatus.Queued }
        val builder =
            Notification
                .Builder(this, CHANNEL_ID)
                .setContentTitle(running?.title ?: getString(dev.geode.R.string.export_notification_title))
                .setContentText(textOf(running, waiting))
                .setSmallIcon(dev.geode.R.drawable.ic_stat_capture)
                .setOngoing(true)
                .setContentIntent(open)
        val progress = (running?.status as? RenderStatus.Running)?.progress
        if (progress == null) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(PROGRESS_MAX, (progress * PROGRESS_MAX).toInt(), false)
        }
        val notification = builder.build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, foregroundType())
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** "about 3 min left · 2 more waiting", or the plain line while nothing is known yet. */
    private fun textOf(
        running: RenderJob?,
        waiting: Int,
    ): String {
        val left = (running?.status as? RenderStatus.Running)?.secondsLeft?.let { RenderEta.describe(it) }
        val more = if (waiting > 0) resources.getQuantityString(dev.geode.R.plurals.export_notification_waiting, waiting, waiting) else null
        return listOfNotNull(left, more).joinToString(" · ").ifBlank { getString(dev.geode.R.string.export_notification_text) }
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }

    companion object {
        /** The Renders' notification channel: the queue's progress, and a Render's finished alert (#28). */
        const val CHANNEL_ID = "geode-export"
        private const val NOTIFICATION_ID = 4711
        private const val PROGRESS_MAX = 1000

        /** Makes the Renders' channel, once; asking again changes nothing. */
        fun ensureChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(dev.geode.R.string.export_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(dev.geode.R.string.export_channel_description)
                    setShowBadge(false)
                },
            )
        }

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, RenderService::class.java))
            }
        }
    }
}

private const val TAG = "RenderService"
