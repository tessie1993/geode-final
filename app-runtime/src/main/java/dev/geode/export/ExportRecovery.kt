package dev.geode.export

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import dev.geode.util.bestEffort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Startup recovery policy for abandoned export files and unpublished MediaStore rows. */
internal class ExportRecovery(
    private val context: Context,
    private val scratchFiles: RenderScratchFiles,
) {
    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            bestEffort(TAG, "sweep stale render scratch") { scratchFiles.sweepStale() }
        }
        sweepOrphanedPendingExports(context, scope)
    }

    /**
     * Deletes MediaStore rows a render left `IS_PENDING` when its process died mid-write — a
     * crash, a force-stop, a low-memory kill of [dev.geode.export.ExportService] before it could
     * finish. [dev.geode.export.VideoExporter], [dev.geode.export.StudioExporter] and
     * [dev.geode.export.LoopExtend] all insert with `IS_PENDING` set and clear it on success or
     * delete the row on a clean failure; nothing runs `START_STICKY` or persists the run for a
     * retry, so a row that is still pending once the app restarts belongs to nobody and never
     * finishes on its own. It would otherwise sit in Movies/Geode or Pictures/Geode forever, a
     * broken, unplayable, ever-growing invisible file.
     *
     * `IS_PENDING` only exists from API 29 (scoped storage), so there is nothing to sweep below
     * it. A short grace period (rather than sweeping every pending row on sight) keeps this from
     * racing a render that is genuinely in flight in this same process.
     */
    private fun sweepOrphanedPendingExports(
        context: Context,
        scope: CoroutineScope,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val cutoffSeconds = (System.currentTimeMillis() - PENDING_EXPORT_GRACE_MS) / 1000
        scope.launch(Dispatchers.IO) {
            bestEffort(TAG, "sweep orphaned pending exports") {
                deletePendingRowsOlderThan(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cutoffSeconds)
                deletePendingRowsOlderThan(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cutoffSeconds)
            }
        }
    }

    private fun deletePendingRowsOlderThan(
        context: Context,
        collection: Uri,
        cutoffSeconds: Long,
    ) {
        val resolver = context.contentResolver
        val selection =
            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? AND " +
                "${MediaStore.MediaColumns.IS_PENDING} = 1 AND " +
                "${MediaStore.MediaColumns.DATE_ADDED} < ? AND " +
                "(${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?)"
        val args =
            arrayOf(
                context.packageName,
                cutoffSeconds.toString(),
                "Movies/Geode%",
                "Pictures/Geode%",
            )
        resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            while (cursor.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                bestEffort(TAG, "resolver.delete(orphaned pending row)") { resolver.delete(uri, null, null) }
            }
        }
    }

    private companion object {
        const val TAG = "ExportRecovery"
        const val PENDING_EXPORT_GRACE_MS = 5 * 60 * 1000L
    }
}
