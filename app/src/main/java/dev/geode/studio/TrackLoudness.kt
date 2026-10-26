package dev.geode.studio

import android.content.Context
import android.net.Uri
import dev.geode.feature.studio.api.RenderLoudness
import dev.geode.feature.studio.core.Loudness
import dev.geode.feature.studio.handlers.edit.SoundLevel
import dev.geode.feature.studio.handlers.edit.SoundLevels
import dev.geode.studio.media.IntegratedLoudness
import dev.geode.studio.media.LoudnessMeter
import dev.geode.studio.media.LoudnessResult

/**
 * How loud a Track or a video is (#329), measured by the loudness meter (ITU-R BS.1770) over the whole
 * file, and the gain that brings it to a Render's or an Edit's loudness target. A file whose loudness
 * cannot be told is left as it is.
 */
class TrackLoudness(
    private val context: Context,
) : SoundLevels {
    override suspend fun measure(file: String): SoundLevel? = levelOf(Uri.parse(file)) { false }

    /** The gain in dB that brings [uri]'s sound to [target]; 0 when it is left as it is or cannot be measured. */
    suspend fun gainDb(
        uri: Uri,
        target: RenderLoudness,
        isCancelled: () -> Boolean,
    ): Double {
        if (target == RenderLoudness.LEAVE_AS_IS) return 0.0
        val level = levelOf(uri, isCancelled) ?: return 0.0
        return Loudness.gainDb(target, level.lufs, level.truePeakDbtp)
    }

    private suspend fun levelOf(
        uri: Uri,
        isCancelled: () -> Boolean,
    ): SoundLevel? {
        val report = (LoudnessMeter(context).measure(uri, isCancelled) as? LoudnessResult.Measured)?.report ?: return null
        val lufs = (report.integrated as? IntegratedLoudness.Lufs)?.value ?: return null
        return SoundLevel(lufs, report.truePeakDbtp)
    }
}
