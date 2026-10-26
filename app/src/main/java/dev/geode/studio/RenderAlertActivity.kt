package dev.geode.studio

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import dev.geode.R
import dev.geode.util.bestEffort

/**
 * The one place a finished Render's alert sends its taps (#22, #28). A notification's PendingIntent names
 * this activity explicitly, so no other app can take over the intent; only here, inside Geode, are the
 * open and share intents built and handed to the system's viewer and chooser. It shows nothing and
 * finishes at once.
 */
class RenderAlertActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (uri != null) bestEffort(TAG, "startActivity(target)") { startActivity(target(uri, intent.action == ACTION_SHARE)) }
        finish()
    }

    private fun target(
        uri: Uri,
        share: Boolean,
    ): Intent {
        if (!share) {
            return Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, VIDEO_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val send =
            Intent(Intent.ACTION_SEND)
                .setType(VIDEO_MIME)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, getString(R.string.render_finished_share))
    }

    companion object {
        private const val TAG = "RenderAlertActivity"
        private const val VIDEO_MIME = "video/mp4"
        private const val ACTION_OPEN = "dev.geode.studio.action.OPEN_RENDER"
        private const val ACTION_SHARE = "dev.geode.studio.action.SHARE_RENDER"

        /** An explicit intent to this activity that opens ([share] false) or shares the video at [uri]. */
        fun intent(
            context: Context,
            uri: Uri,
            share: Boolean,
        ): Intent =
            Intent(context, RenderAlertActivity::class.java)
                .setAction(if (share) ACTION_SHARE else ACTION_OPEN)
                .setData(uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
    }
}
