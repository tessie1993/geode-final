package dev.geode.feature.studio.handlers.edit

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dev.geode.engine.types.Millis
import dev.geode.feature.studio.api.EditSource

/**
 * [EditVideos] on the phone (#318): a picked video's facts from the media metadata, and copies into the
 * Save folder through the Storage Access Framework. A copy that fails part-way leaves no file behind.
 */
class AndroidEditVideos(
    private val context: Context,
) : EditVideos {
    override suspend fun read(file: String): EditSource? {
        val uri = Uri.parse(file)
        val retriever = MediaMetadataRetriever()
        val facts =
            try {
                retriever.setDataSource(context, uri)
                Facts(
                    retriever.number(MediaMetadataRetriever.METADATA_KEY_DURATION),
                    retriever.number(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
                    retriever.number(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
                )
            } finally {
                retriever.release()
            }
        if (facts.lengthMs <= 0L) return null
        return EditSource(file, nameOf(uri), Millis(facts.lengthMs), facts.width.toInt(), facts.height.toInt())
    }

    override suspend fun copy(
        file: String,
        folder: String,
    ): String {
        val source = Uri.parse(file)
        val tree = checkNotNull(DocumentFile.fromTreeUri(context, Uri.parse(folder))) { "the save folder cannot be opened" }
        val resolver = context.contentResolver
        val type = resolver.getType(source) ?: GENERIC_TYPE
        val target = checkNotNull(tree.createFile(type, nameOf(source).substringBeforeLast('.'))) { "the copy cannot be made" }
        val copied =
            runCatching {
                resolver.openInputStream(source).use { input ->
                    resolver.openOutputStream(target.uri).use { output ->
                        checkNotNull(input).copyTo(checkNotNull(output))
                    }
                }
            }
        if (copied.isFailure) target.delete()
        copied.getOrThrow()
        return target.uri.toString()
    }

    private fun nameOf(uri: Uri): String =
        runCatching { DocumentFile.fromSingleUri(context, uri)?.name }.getOrNull() ?: uri.lastPathSegment ?: FALLBACK_NAME

    private fun MediaMetadataRetriever.number(key: Int): Long = extractMetadata(key)?.toLongOrNull() ?: 0L

    private class Facts(
        val lengthMs: Long,
        val width: Long,
        val height: Long,
    )

    private companion object {
        const val GENERIC_TYPE = "application/octet-stream"
        const val FALLBACK_NAME = "video"
    }
}
