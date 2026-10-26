package dev.geode.studio

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import dev.geode.RingLog
import dev.geode.feature.visuals.api.SnapshotProblem
import dev.geode.feature.visuals.handlers.SnapshotCamera
import dev.geode.render.VisualStill
import dev.geode.render.VisualizerRenderer
import dev.geode.util.bestEffort
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.coroutines.resume

/**
 * Takes Snapshots of the live Visual (#405): Visuals' [SnapshotCamera]. The live renderer reads its
 * next frame back on the GL thread ([VisualizerRenderer.requestStill]), the way a Recording takes its
 * frames from it (#317), so the picture is the Visual alone, at the screen's full size, never the App
 * UI. Everything after the read happens on [work]: the rows are put the right way up, encoded as a
 * PNG and saved to `Pictures/Geode` through MediaStore, which needs no storage permission on any
 * Android Geode runs on (API 29 and up). A Snapshot belongs to no Project.
 */
class LiveVisualSnapshots(
    private val context: Context,
    private val liveVisualShown: () -> Boolean,
    private val work: CoroutineDispatcher,
) : SnapshotCamera {
    override suspend fun save(fileName: String): SnapshotProblem? {
        val renderer = LiveVisualSource.renderer
        if (renderer == null || !liveVisualShown()) return SnapshotProblem.NO_VISUAL
        // No frame drawn in time: the Visual stopped drawing (it left the screen) while we waited.
        val read = readStill(renderer) ?: return SnapshotProblem.NO_VISUAL
        val still = read.still ?: return SnapshotProblem.NOT_SAVED
        return withContext(work) { if (PictureFile.save(context, fileName, still)) null else SnapshotProblem.NOT_SAVED }
    }

    /** What the renderer handed over: the still, or null when it could not read one. */
    private class Read(
        val still: VisualStill?,
    )

    /** The next frame the live renderer draws; null when none is drawn within [STILL_WAIT_MS]. */
    private suspend fun readStill(renderer: VisualizerRenderer): Read? =
        withTimeoutOrNull(STILL_WAIT_MS) {
            suspendCancellableCoroutine<Read> { waiting ->
                val onStill: (VisualStill?) -> Unit = { waiting.resume(Read(it)) }
                waiting.invokeOnCancellation { renderer.cancelStill(onStill) }
                renderer.requestStill(onStill)
            }
        }

    private companion object {
        /** How long a Snapshot waits for the Visual's next frame: many frames, even at the lowest rate. */
        const val STILL_WAIT_MS = 2_000L
    }
}

/** A still's pixels as a picture: GL reads the bottom row first, a picture starts at the top. */
internal object StillPixels {
    /** Turns the [rows] rows of [rowBytes] bytes each in [pixels] upside down, in place; the position is left at 0. */
    fun flipRows(
        pixels: ByteBuffer,
        rowBytes: Int,
        rows: Int,
    ) {
        val top = ByteArray(rowBytes)
        val bottom = ByteArray(rowBytes)
        for (row in 0 until rows / 2) {
            val upper = row * rowBytes
            val lower = (rows - 1 - row) * rowBytes
            pixels.position(upper)
            pixels.get(top)
            pixels.position(lower)
            pixels.get(bottom)
            pixels.position(lower)
            pixels.put(top)
            pixels.position(upper)
            pixels.put(bottom)
        }
        pixels.rewind()
    }
}

/** A Snapshot's PNG in the phone's Pictures library under `Pictures/Geode`, hidden until it is whole. */
internal object PictureFile {
    private const val TAG = "PictureFile"
    private const val BYTES_PER_PIXEL = 4

    /** PNG is lossless; the quality is ignored, but asked for in full. */
    private const val QUALITY = 100

    /** Saves [still] as the PNG [fileName]; false when it could not, and nothing is left behind. */
    fun save(
        context: Context,
        fileName: String,
        still: VisualStill,
    ): Boolean {
        val bitmap = bitmapOf(still) ?: return false
        try {
            val resolver = context.contentResolver
            val values =
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Geode")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            val uri =
                try {
                    resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                } catch (refused: IllegalArgumentException) {
                    RingLog.note(TAG, "the Pictures library would not take the Snapshot", refused)
                    null
                } ?: return false
            val written = write(context, uri, bitmap)
            if (written) {
                val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            } else {
                bestEffort(TAG, "resolver.delete(uri)") { resolver.delete(uri, null, null) }
            }
            return written
        } finally {
            bitmap.recycle()
        }
    }

    /** [still] the right way up as an opaque bitmap; null when there is no room for it. */
    @Suppress("SwallowedException") // A Snapshot too big to hold is a Snapshot not saved; the app goes on.
    private fun bitmapOf(still: VisualStill): Bitmap? {
        StillPixels.flipRows(still.rgba, still.width * BYTES_PER_PIXEL, still.height)
        val bitmap =
            try {
                Bitmap.createBitmap(still.width, still.height, Bitmap.Config.ARGB_8888)
            } catch (tooBig: OutOfMemoryError) {
                return null
            }
        // ARGB_8888 is held as red, green, blue, alpha bytes: GL's own order.
        bitmap.copyPixelsFromBuffer(still.rgba)
        // The Visual has no alpha (#311): the picture is opaque whatever GL read there.
        bitmap.setHasAlpha(false)
        return bitmap
    }

    private fun write(
        context: Context,
        uri: Uri,
        bitmap: Bitmap,
    ): Boolean =
        try {
            context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, QUALITY, it) } == true
        } catch (unwritten: IOException) {
            RingLog.note(TAG, "the Snapshot could not be written", unwritten)
            false
        }
}
