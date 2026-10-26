package dev.geode.studio

import android.content.Context
import android.media.MediaCodec
import android.net.Uri
import dev.geode.RingLog
import dev.geode.analysis.AnalysisCache
import dev.geode.analysis.AnalysisProfile
import dev.geode.analysis.BeatTuning
import dev.geode.analysis.FeatureTimeline
import dev.geode.analysis.OfflineAnalyzer
import dev.geode.analysis.SongMap
import dev.geode.engine.types.Millis
import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.Customization
import dev.geode.feature.customization.api.StyleSwitching
import dev.geode.feature.customization.core.BuiltInStyles
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.settings.api.SettingsState
import dev.geode.feature.settings.api.SettingsValues
import dev.geode.feature.studio.api.NewVideo
import dev.geode.feature.studio.api.RenderFormat
import dev.geode.feature.studio.api.RenderJob
import dev.geode.feature.studio.api.RenderPhase
import dev.geode.feature.studio.api.RenderProblem
import dev.geode.feature.studio.api.RenderTrack
import dev.geode.feature.studio.api.VideoKind
import dev.geode.feature.studio.core.ContinuousPlan
import dev.geode.feature.studio.core.LongForm
import dev.geode.feature.studio.core.RenderEnd
import dev.geode.feature.studio.core.RenderPlan
import dev.geode.feature.studio.core.TrackCues
import dev.geode.feature.studio.handlers.FrozenLook
import dev.geode.feature.studio.handlers.RenderEngine
import dev.geode.feature.visuals.handlers.FileMilkdropFolder
import dev.geode.playback.toUri
import dev.geode.render.SceneFactory
import dev.geode.render.offscreen.RenderSwitch
import dev.geode.render.offscreen.StyleSwitches
import dev.geode.render.scene.SceneIds
import dev.geode.studio.media.ExportAspect
import dev.geode.studio.media.ExportRange
import dev.geode.studio.media.LongFormAudio
import dev.geode.studio.media.LoopExtend
import dev.geode.studio.media.LoopRender
import dev.geode.studio.media.LoopSpec
import dev.geode.studio.media.MixClip
import dev.geode.studio.media.PlaylistSound
import dev.geode.studio.media.VideoExporter
import dev.geode.visuals.EngineSnapshots
import dev.geode.visuals.StyleTargets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.File
import java.io.IOException

/**
 * Makes a Render's video on the phone (#316): Studio's [RenderEngine] over the native engine. The whole
 * Track is analysed first (ADR-0001), or its analysis read back from the cache; the frozen look is
 * handed to an offscreen renderer on its own engine instance (ADR-0004), which draws everything the
 * live Visual would for it (#337); the encoder muxes the Track's sound beside it, levelled to the
 * Render's loudness target (#329), and the video is saved to `Movies/Geode`, from where Studio adds it to
 * the Render's Project.
 *
 * A Render is not bound to real time: it draws as fast as the encoder takes frames, except while the
 * live Visual is on screen ([liveVisualShown]) or the CPU/GPU save mode is on, when it waits a little
 * after each frame so the live picture stays smooth and the phone cool (ADR-0004, #28). While it is
 * [held] (Renders keep going is off and the app is not in front, #28) it stops between frames until the
 * app is back or the Render is cancelled.
 */
class NativeRenderEngine(
    private val context: Context,
    private val settings: Settings,
    private val liveVisualShown: () -> Boolean,
    private val held: () -> Boolean,
    private val work: CoroutineDispatcher,
) : RenderEngine {
    private val exporter = VideoExporter(context)
    private val analyzer = OfflineAnalyzer(context)
    private val loudness = TrackLoudness(context)

    override suspend fun render(
        job: RenderJob,
        look: FrozenLook,
        trackLooks: List<FrozenLook>,
        progress: (RenderPhase, Float) -> Unit,
        isCancelled: () -> Boolean,
    ): RenderEnd {
        if (job.tracks.size > 1) return renderContinuous(job, trackLooks, progress, isCancelled)
        val track = job.tracks.singleOrNull() ?: return RenderEnd.Failed(RenderProblem.ENGINE_FAILED)
        val values = (settings.state.value as? SettingsState.Ready)?.values ?: SettingsValues()
        progress(RenderPhase.PREPARING, 0f)
        val customization = lookOf(look) ?: return RenderEnd.Failed(RenderProblem.LOOK_UNAVAILABLE)
        val context = runCatching { RenderLookFormat.decodeContext(look.text) }.getOrDefault(SwitchingContext())
        val uri = track.id.toUri()
        val timeline =
            timelineOf(uri, values, isCancelled) { progress(RenderPhase.ANALYSING, it * ANALYSIS_SHARE) }
                ?: return if (isCancelled()) RenderEnd.Cancelled else RenderEnd.Failed(RenderProblem.TRACK_UNREADABLE)
        if (isCancelled()) return RenderEnd.Cancelled
        // The Track is measured once and its sound levelled to the target the Render carries (#329).
        val gainDb = loudness.gainDb(uri, job.extras.loudness, isCancelled)
        if (isCancelled()) return RenderEnd.Cancelled
        val format = job.format
        val frame = RenderPlan.frameOf(format)
        val scene = sceneOf(customization, job)
        RenderPlan.longFormOf(job.length, track.length)?.let { longForm ->
            val drawn = LongFormDrawing(track, uri, timeline, scene, customization, values, format, gainDb)
            return renderLongForm(longForm, drawn, progress, isCancelled)
        }
        val section = RenderPlan.sectionOf(job.length, track.length)
        val made =
            VideoExporter.Job(
                audioUri = uri,
                timeline = timeline,
                sceneFactory = scene,
                look = EngineSnapshots.forRender(customization, values),
                aspect = ExportAspect(frame.width, frame.height, RenderPlan.bitRateOf(format)),
                fps = format.fps,
                range = section?.let { ExportRange(it.start.value, it.length.value) },
                switches = switchesOf(customization, context, values),
                codec = format.codec,
                supersample = RenderPlan.supersampleOf(format),
                gainDb = gainDb,
            )
        val result = encode(made, track.title, progress, isCancelled) ?: return RenderEnd.Failed(RenderProblem.ENCODER_REFUSED)
        return endOf(result, track.title, frame.width, frame.height)
    }

    /**
     * One continuous video of a playlist (#326, as the owner decided on #273). Each track is measured and
     * levelled to the Render's loudness target on its own (#329), then the tracks are joined into one sound
     * with the Playback settings' crossfade ([PlaylistSound]); that sound is analysed whole (ADR-0001) and
     * drawn as one Track would be. The Visual starts with the first track's Customization and moves to each
     * next track's on the first downbeat once that track has come in ([ContinuousPlan.changesAt]), crossfading
     * as any change of Style does (#309); each track's own Style switching runs while it plays (#343). It is
     * as long as the playlist, refused only when the phone's free storage would not hold it.
     */
    private suspend fun renderContinuous(
        job: RenderJob,
        trackLooks: List<FrozenLook>,
        progress: (RenderPhase, Float) -> Unit,
        isCancelled: () -> Boolean,
    ): RenderEnd {
        val values = (settings.state.value as? SettingsState.Ready)?.values ?: SettingsValues()
        progress(RenderPhase.PREPARING, 0f)
        val looks = trackLooks.map { lookOf(it) ?: return RenderEnd.Failed(RenderProblem.LOOK_UNAVAILABLE) }
        if (looks.size != job.tracks.size) return RenderEnd.Failed(RenderProblem.LOOK_UNAVAILABLE)
        val contexts = trackLooks.map { runCatching { RenderLookFormat.decodeContext(it.text) }.getOrDefault(SwitchingContext()) }
        if (!roomFor(job)) return RenderEnd.Failed(RenderProblem.NO_SPACE)
        val joined =
            joinedSound(job, isCancelled) { progress(RenderPhase.PREPARING, it * JOIN_SHARE) }
                ?: return if (isCancelled()) RenderEnd.Cancelled else RenderEnd.Failed(RenderProblem.TRACK_UNREADABLE)
        try {
            // The joined sound is made for this Render alone, so its analysis is not cached.
            val timeline =
                timelineOf(joined.uri, values, isCancelled, cache = false) { progress(RenderPhase.ANALYSING, JOIN_SHARE + it * JOIN_SHARE) }
                    ?: return if (isCancelled()) RenderEnd.Cancelled else RenderEnd.Failed(RenderProblem.TRACK_UNREADABLE)
            if (isCancelled()) return RenderEnd.Cancelled
            val downbeats = timeline.frames.filter { it.features.downbeat }.map { it.timeMs }
            val cues = TrackCues(ContinuousPlan.changesAt(joined.startsMs, joined.overlapsMs, downbeats, timeline.bpm))
            val switching =
                PlaylistSwitches(
                    cues,
                    switchTo = { track -> trackSwitch(looks[track], job, values) },
                    stylesOf = { track -> switchesOf(looks[track], contexts[track], values) },
                )
            val format = job.format
            val frame = RenderPlan.frameOf(format)
            val made =
                VideoExporter.Job(
                    audioUri = joined.uri,
                    timeline = timeline,
                    sceneFactory = sceneOf(looks.first(), job),
                    look = EngineSnapshots.forRender(looks.first(), values),
                    aspect = ExportAspect(frame.width, frame.height, RenderPlan.bitRateOf(format)),
                    fps = format.fps,
                    codec = format.codec,
                    supersample = RenderPlan.supersampleOf(format),
                    switches = switching,
                    cues = switching,
                )
            val result =
                encode(made, job.title, progress, isCancelled, from = 2 * JOIN_SHARE)
                    ?: return RenderEnd.Failed(RenderProblem.ENCODER_REFUSED)
            return endOf(result, job.title, frame.width, frame.height)
        } finally {
            joined.delete()
        }
    }

    /**
     * Whether the phone's free storage holds a continuous video of [job] while it is made: its only limit
     * (#273). The video goes to shared storage and its joined sound to the cache, so both must have room.
     */
    private fun roomFor(job: RenderJob): Boolean {
        val needed = ContinuousPlan.bytesNeeded(job.format, ContinuousPlan.lengthOf(job.tracks, job.join))
        val cache = context.cacheDir.usableSpace
        val shared = context.getExternalFilesDir(null)?.usableSpace ?: cache
        val free = minOf(cache, shared)
        if (free >= needed) return true
        RingLog.note(TAG, "a continuous video needs $needed bytes free and the phone has $free")
        return false
    }

    /**
     * A continuous video's sound (#326): each track levelled to the Render's loudness target on its own, then
     * all joined by the Render's crossfade. Null when it could not be made, or the Render was cancelled.
     */
    private suspend fun joinedSound(
        job: RenderJob,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): PlaylistSound.Joined? {
        val tracks =
            job.tracks.map { track ->
                val uri = track.id.toUri()
                PlaylistSound.Track(uri, track.length.value, loudness.gainDb(uri, job.extras.loudness, isCancelled))
            }
        if (isCancelled()) return null
        return when (val result = withContext(work) { PlaylistSound(context).join(tracks, job.join, isCancelled, onProgress) }) {
            is PlaylistSound.Result.Made -> result.joined
            is PlaylistSound.Result.Failed -> {
                RingLog.note(TAG, "the playlist's sound could not be joined: ${result.message}")
                null
            }
            PlaylistSound.Result.Cancelled -> null
        }
    }

    /**
     * The change to a track's own Customization in a continuous video (#326): its Style, drawn with its look,
     * crossfading over its Style switching's transition, and the MilkDrop preset it plays.
     */
    private fun trackSwitch(
        customization: Customization,
        job: RenderJob,
        values: SettingsValues,
    ): RenderSwitch {
        val scene = sceneOf(customization, job)
        val transition = customization.automations.style?.transition ?: StyleSwitching.DEFAULT_TRANSITION
        return RenderSwitch(scene.sceneId, EngineSnapshots.forRender(customization, values), transition, scene.milkPresetPath)
    }

    /** What a long-form Render draws: its Track and analysis, its Style and look, its format, and its sound's gain. */
    private class LongFormDrawing(
        val track: RenderTrack,
        val uri: Uri,
        val timeline: FeatureTimeline,
        val scene: SceneFactory,
        val customization: Customization,
        val values: SettingsValues,
        val format: RenderFormat,
        val gainDb: Double,
    )

    /**
     * A seamless loop, or N minutes of one (#346): the old loop render draws one loop of the Track's
     * opening, trimmed to whole bars, and dissolves its tail into its opening frames so it wraps with no
     * seam; the old extender repeats the encoded loop, without drawing it again, under the Track played
     * back to back until the video is as long as asked. A loop alone carries the Track's sound for its
     * own length.
     */
    private suspend fun renderLongForm(
        longForm: LongForm,
        drawn: LongFormDrawing,
        progress: (RenderPhase, Float) -> Unit,
        isCancelled: () -> Boolean,
    ): RenderEnd {
        val format = drawn.format
        val frame = RenderPlan.frameOf(format)
        val rendered =
            LoopRender(context).render(
                timeline = drawn.timeline,
                sceneFactory = drawn.scene,
                aspect = ExportAspect(frame.width, frame.height, RenderPlan.bitRateOf(format)),
                spec = LoopSpec.of(longForm.loop.value, fps = format.fps, bpm = drawn.timeline.bpm),
                look = { stop -> EngineSnapshots.forRender(stop.applyTo(drawn.customization), drawn.values) },
                codec = format.codec,
                onProgress = { progress(RenderPhase.RENDERING, ANALYSIS_SHARE + it * LOOP_SHARE) },
                isCancelled = isCancelled,
                audioUri = drawn.uri,
                supersample = RenderPlan.supersampleOf(format),
                pace = { pace(isCancelled) },
            )
        val reel =
            when (rendered) {
                is LoopRender.Result.Rendered -> rendered.reel
                is LoopRender.Result.Failed -> {
                    RingLog.note(TAG, "the Render's loop was not made: ${rendered.message}")
                    return RenderEnd.Failed(RenderProblem.ENCODER_REFUSED)
                }
                LoopRender.Result.Cancelled -> return RenderEnd.Cancelled
            }
        try {
            // A loop alone is as long as the loop came out, once trimmed to whole bars.
            val totalUs = if (longForm.total == longForm.loop) reel.loopDurationUs else longForm.total.value * MICROS_PER_MILLI
            val trackLength = if (drawn.track.length.value > 0) drawn.track.length else Millis(drawn.timeline.durationMs)
            val clip = MixClip(drawn.uri, drawn.track.title)
            val plays = RenderPlan.playsFor(Millis(totalUs / MICROS_PER_MILLI), trackLength)
            val audio = if (plays == 1) LongFormAudio.SingleTrack(clip) else LongFormAudio.Mix(List(plays) { clip })
            val extended =
                LoopExtend(context).extend(
                    reel = reel,
                    audio = audio,
                    fileName = fileNameOf(drawn.track.title),
                    onProgress = { progress(RenderPhase.SAVING, REPEAT_FROM + it * (1f - REPEAT_FROM)) },
                    isCancelled = isCancelled,
                    maxDurationUs = totalUs,
                    gainDb = drawn.gainDb,
                )
            return when (extended) {
                is LoopExtend.Result.Saved ->
                    RenderEnd.Saved(videoOf(drawn.track.title, extended.uri, extended.durationMs, frame.width, frame.height))
                is LoopExtend.Result.Failed -> {
                    RingLog.note(TAG, "the Render's long video was not saved: ${extended.message}")
                    RenderEnd.Failed(RenderProblem.NOT_SAVED)
                }
                LoopExtend.Result.Cancelled -> RenderEnd.Cancelled
            }
        } finally {
            reel.delete()
        }
    }

    /**
     * The look's Style switching replayed in the export loop (#343), or null when it has none: each due
     * switch picks the next Style by Customization's seeded rules and crossfades over its transition, so
     * two Renders of the same spec switch at the same moments to the same Styles.
     */
    private fun switchesOf(
        customization: Customization,
        context: SwitchingContext,
        values: SettingsValues,
    ): StyleSwitches? {
        if (customization.automations.style == null) return null
        val switcher = RenderStyleSwitcher(customization, context, BuiltInStyles.catalogue)
        return StyleSwitches {
            switcher.next()?.let { switched ->
                val transition = switched.automations.style?.transition ?: StyleSwitching.DEFAULT_TRANSITION
                // A MilkDrop preset's own Style switches to the MilkDrop scene with that preset (#305).
                val target = StyleTargets.of(switched.style, milkFolder)
                RenderSwitch(target.scene, EngineSnapshots.forRender(switched, values), transition, target.milkPreset?.path)
            }
        }
    }

    /** The frozen look as a Customization, or null when it cannot be read. */
    private fun lookOf(look: FrozenLook): Customization? =
        try {
            RenderLookFormat.decode(look.text, SceneIds.DEFAULT)
        } catch (unreadable: JSONException) {
            RingLog.note(TAG, "the Render's look could not be read", unreadable)
            null
        } catch (unreadable: IllegalArgumentException) {
            RingLog.note(TAG, "the Render's look is not one this version reads", unreadable)
            null
        }

    /**
     * The Track's analysis, or null when it cannot be read or the Render was cancelled meanwhile; read from
     * and kept in the cache unless [cache] is off.
     */
    private suspend fun timelineOf(
        uri: Uri,
        values: SettingsValues,
        isCancelled: () -> Boolean,
        cache: Boolean = true,
        onProgress: (Float) -> Unit,
    ): FeatureTimeline? =
        try {
            analyse(uri, values, isCancelled, cache, onProgress)
        } catch (stopped: CancellationException) {
            if (!isCancelled()) throw stopped
            null
        } catch (unreadable: IOException) {
            RingLog.note(TAG, "the Track could not be read", unreadable)
            null
        } catch (unreadable: IllegalArgumentException) {
            RingLog.note(TAG, "the Track has no sound to analyse", unreadable)
            null
        }

    /**
     * [made] encoded as a video named [title], or null when the encoder would not take its format. Its
     * progress fills the Render's from [from] on.
     */
    private suspend fun encode(
        made: VideoExporter.Job,
        title: String,
        progress: (RenderPhase, Float) -> Unit,
        isCancelled: () -> Boolean,
        from: Float = ANALYSIS_SHARE,
    ): VideoExporter.Result? =
        try {
            exporter.export(
                job = made,
                fileName = fileNameOf(title),
                onProgress = { progress(phaseAt(it), from + it * (1f - from)) },
                isCancelled = isCancelled,
                pace = { pace(isCancelled) },
            )
        } catch (refused: MediaCodec.CodecException) {
            RingLog.note(TAG, "the encoder refused the Render", refused)
            null
        } catch (refused: IllegalArgumentException) {
            RingLog.note(TAG, "the encoder refused the Render's format", refused)
            null
        } catch (unsaved: IOException) {
            RingLog.note(TAG, "the Render could not be written", unsaved)
            VideoExporter.Result.Failed(unsaved.message.orEmpty())
        }

    private fun endOf(
        result: VideoExporter.Result,
        title: String,
        width: Int,
        height: Int,
    ): RenderEnd =
        when (result) {
            is VideoExporter.Result.Saved -> RenderEnd.Saved(videoOf(title, result.uri, result.lengthMs, width, height))
            is VideoExporter.Result.Failed -> {
                RingLog.note(TAG, "the Render was not saved: ${result.message}")
                RenderEnd.Failed(RenderProblem.NOT_SAVED)
            }
            VideoExporter.Result.Cancelled -> RenderEnd.Cancelled
        }

    /** A finished Render's video, named [title] after its Track, or every track of a continuous video. */
    private fun videoOf(
        title: String,
        uri: Uri,
        lengthMs: Long,
        width: Int,
        height: Int,
    ): NewVideo =
        NewVideo(
            kind = VideoKind.RENDER,
            name = title,
            file = uri.toString(),
            thumbnail = null,
            length = Millis(lengthMs),
            width = width,
            height = height,
            song = title,
        )

    /**
     * The Track's whole-song analysis with Settings' beat tuning, from the cache when it holds one, laid
     * out by its Song map. The beat's shortest gap keeps the live Visual's floor, so a Render never
     * pulses faster than live.
     */
    private suspend fun analyse(
        uri: Uri,
        values: SettingsValues,
        isCancelled: () -> Boolean,
        cache: Boolean,
        onProgress: (Float) -> Unit,
    ): FeatureTimeline {
        val sensitivity = values.analysis.beatSensitivity
        val gap = BeatTuning.clampIntervalMs(values.analysis.beatMinIntervalMs)
        // A fresh analysis is drawn at the precision the cache keeps, so a Render of it and a Render of it
        // read back from the cache are the same (#302).
        val fresh = {
            AnalysisCache.stored(analyzer.analyzeBlocking(uri, sensitivity, gap, onProgress, AnalysisProfile.RENDER) { !isCancelled() })
        }
        val analysed =
            withContext(work) {
                if (!cache) {
                    fresh()
                } else {
                    AnalysisCache.load(context, uri, sensitivity, gap, AnalysisProfile.RENDER)
                        ?: fresh().also { AnalysisCache.save(context, uri, it, AnalysisProfile.RENDER) }
                }
            }
        onProgress(1f)
        // The whole track is known before the first frame: the Song map puts hits, bars, sections and
        // builds where the music has them (#320). The live Visual has no such map and no lookahead.
        val tuned = analysed.withBeatSensitivity(sensitivity, gap)
        return SongMap.of(tuned).choreographed(tuned)
    }

    /**
     * Waits after a frame: for as long as the Render is held, then a little while the live Visual shows or
     * the save mode is on.
     */
    private fun pace(isCancelled: () -> Boolean) {
        while (held() && !isCancelled()) Thread.sleep(HOLD_POLL_MS)
        val slowed = (settings.state.value as? SettingsState.Ready)?.values?.performance?.rendersSlowed == true
        if (slowed || liveVisualShown()) Thread.sleep(PACE_MS)
    }

    /**
     * The MilkDrop preset [job] plays, as a file the engine loads, when its look is MilkDrop or its Style
     * switching can reach MilkDrop (#341, #343): the one in its spec, whatever the live Visual shows. It
     * loads with the MilkDrop scene, with the textures beside it in the MilkDrop folder. A preset no
     * longer in the folder leaves MilkDrop's idle picture.
     */
    private fun milkdropPathOf(
        customization: Customization,
        job: RenderJob,
    ): String? {
        val reaches = customization.style == SceneIds.MILKDROP || customization.automations.style != null
        val file = job.milkdrop?.takeIf { reaches } ?: return null
        val preset = File(File(context.filesDir, FileMilkdropFolder.FOLDER), file)
        if (preset.isFile) return preset.absolutePath
        RingLog.note(TAG, "the Render's MilkDrop preset is no longer in the MilkDrop folder: $file")
        return null
    }

    /**
     * The Style a Render draws first: a MilkDrop preset's own Style (#305) is the MilkDrop scene with that
     * preset loaded before the first frame; any other Style draws with the Render's MilkDrop preset, if
     * it has one, for when it is or switches to MilkDrop.
     */
    private fun sceneOf(
        customization: Customization,
        job: RenderJob,
    ): StyleScene {
        val target = StyleTargets.of(customization.style, milkFolder)
        val preset = target.milkPreset ?: return StyleScene(target.scene, milkdropPathOf(customization, job))
        return StyleScene(target.scene, preset.path.takeIf { preset.isFile })
    }

    /** The app's MilkDrop folder, where a MilkDrop preset's own Style finds its file. */
    private val milkFolder: File get() = StyleTargets.milkFolder(context)

    /** The Style a Render draws, and the MilkDrop preset it loads first when that Style is MilkDrop. */
    private class StyleScene(
        override val sceneId: SceneId,
        override val milkPresetPath: String?,
    ) : SceneFactory

    private companion object {
        const val TAG = "NativeRenderEngine"

        /** How much of a Render's progress the analysis takes; the encoder's own progress shares the rest. */
        const val ANALYSIS_SHARE = 0.1f

        /** How much of a continuous video's progress joining its sound takes, and then analysing it (#326). */
        const val JOIN_SHARE = 0.1f

        /** How much of a long-form Render's progress drawing its loop takes; repeating it takes the rest. */
        const val LOOP_SHARE = 0.8f
        const val REPEAT_FROM = ANALYSIS_SHARE + LOOP_SHARE

        const val MICROS_PER_MILLI = 1_000L

        /** Where the encoder's progress moves from its sound to its frames, and on to saving (see VideoExporter). */
        const val FRAMES_FROM = 0.1f
        const val SAVING_FROM = 0.95f

        /** How long a Render waits after each frame while it gives way. */
        const val PACE_MS = 12L

        /** How often a held Render looks whether it may go on. */
        const val HOLD_POLL_MS = 250L

        /** The longest a video's name grows from its Track's title. */
        const val MAX_NAME = 80

        fun phaseAt(encoded: Float): RenderPhase =
            when {
                encoded < FRAMES_FROM -> RenderPhase.PREPARING
                encoded < SAVING_FROM -> RenderPhase.RENDERING
                else -> RenderPhase.SAVING
            }

        /** The video's file name: its [title], without characters a file name cannot hold. */
        fun fileNameOf(title: String): String {
            val name =
                title
                    .replace(Regex("[\\\\/:*?\"<>|]"), " ")
                    .trim()
                    .take(MAX_NAME)
                    .ifBlank { "Render" }
            return "$name (Geode).mp4"
        }
    }
}
