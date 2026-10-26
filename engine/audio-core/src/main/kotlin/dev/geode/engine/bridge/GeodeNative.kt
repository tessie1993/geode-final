package dev.geode.engine.bridge

/**
 * The JNI surface of libgeode.so. No C++ exception crosses it (ADR-0005): every call runs inside the
 * native bridge's guard. A call with nothing else to return gives its [NativeStatus] code, which
 * [NativeFailures.check] turns into a named failure. A call that returns a value gives a safe fallback
 * when the engine threw, and the guard reports that failure to [NativeFailures] on its own.
 */
object GeodeNative {
    init {
        System.loadLibrary("geode")
    }

    external fun version(): String

    external fun featureFrameFloats(): Int

    external fun analysisCreate(
        sampleRate: Int,
        fftSize: Int,
        hopRateHz: Float,
    ): Long

    external fun analysisDestroy(handle: Long): Int

    external fun analysisSetSampleRate(
        handle: Long,
        sampleRate: Int,
    ): Int

    external fun analysisSetTuning(
        handle: Long,
        sensitivity: Float,
        refractoryMs: Float,
        attackSeconds: Float,
        releaseSeconds: Float,
    ): Int

    external fun analysisReset(handle: Long): Int

    external fun analysisAnalyze(
        handle: Long,
        mid: FloatArray,
        side: FloatArray?,
        dtSeconds: Float,
        out: FloatArray,
    ): Int

    external fun analysisPush(
        handle: Long,
        interleaved: FloatArray,
        frames: Int,
        channels: Int,
    ): Int

    external fun analysisPull(
        handle: Long,
        out: FloatArray,
    ): Boolean

    external fun analysisKey(handle: Long): String

    external fun pulseReplay(
        flux: FloatArray,
        rms: FloatArray,
        hopRateHz: Float,
        sensitivity: Float,
        refractoryMs: Float,
        out: FloatArray,
    ): Int

    external fun drumsCreate(
        bandCount: Int,
        hopRateHz: Float,
        sampleRate: Int,
    ): Long

    external fun drumsDestroy(handle: Long): Int

    external fun drumsStep(
        handle: Long,
        bands: FloatArray,
        out: FloatArray,
    ): Int

    external fun dspCreate(
        sampleRate: Int,
        channels: Int,
    ): Long

    external fun dspDestroy(handle: Long): Int

    external fun dspBandCount(): Int

    external fun dspBandCenterHz(band: Int): Float

    external fun dspSetEnabled(
        handle: Long,
        enabled: Boolean,
    ): Int

    external fun dspSetBand(
        handle: Long,
        band: Int,
        millibels: Int,
    ): Int

    external fun dspBand(
        handle: Long,
        band: Int,
    ): Int

    external fun dspSetBassBoost(
        handle: Long,
        permille: Int,
    ): Int

    external fun dspSetLoudnessMb(
        handle: Long,
        millibels: Int,
    ): Int

    external fun dspSetGainDb(
        handle: Long,
        db: Float,
    ): Int

    external fun dspSetCrossfeed(
        handle: Long,
        enabled: Boolean,
    ): Int

    external fun dspSetLimiter(
        handle: Long,
        enabled: Boolean,
    ): Int

    external fun dspReset(handle: Long): Int

    /** [buffer] must be a direct float buffer holding [frames] interleaved frames; processed in place. */
    external fun dspProcess(
        handle: Long,
        buffer: java.nio.ByteBuffer,
        frames: Int,
    ): Int

    /**
     * Reads the tags of [fd], which native closes. Fills [texts] with UTF-8 title, artist, album, album artist,
     * genre, comment; [ints] with year, track, duration ms, art bytes; [gains] with track gain dB, track peak,
     * album gain dB, album peak. Returns the mask of gains present (1, 2, 4, 8), -1 when unreadable.
     */
    external fun tagsRead(
        fd: Int,
        texts: Array<ByteArray?>,
        ints: IntArray,
        gains: FloatArray,
    ): Int

    /**
     * Writes the six UTF-8 [texts] plus [year] and [track] to [fd], which native closes. A missing array
     * slot leaves that field unchanged. On a false return, see [tagsLastError] for why.
     */
    external fun tagsWrite(
        fd: Int,
        texts: Array<ByteArray>,
        year: Int,
        track: Int,
    ): Boolean

    external fun playerCreate(): Long

    external fun playerDestroy(handle: Long): Int

    /** [fd] belongs to native from here on; [length] <= 0 reads to the end. */
    external fun playerOpen(
        handle: Long,
        fd: Int,
        offset: Long,
        length: Long,
        token: Long,
    ): Int

    /** Pre-rolls the track that follows for a gapless join; [fd] < 0 clears it. */
    external fun playerSetNext(
        handle: Long,
        fd: Int,
        offset: Long,
        length: Long,
        token: Long,
    ): Int

    external fun playerPlay(handle: Long): Int

    external fun playerPause(handle: Long): Int

    external fun playerStop(handle: Long): Int

    external fun playerSeek(
        handle: Long,
        positionUs: Long,
    ): Int

    external fun playerSetCrossfade(
        handle: Long,
        durationMs: Int,
        curve: Int,
    ): Int

    /** [dsp] is a dspCreate handle at the player's output rate with 2 channels, or 0 to bypass. */
    external fun playerSetDsp(
        handle: Long,
        dsp: Long,
    ): Int

    external fun playerSetVolume(
        handle: Long,
        volume: Float,
    ): Int

    external fun playerState(handle: Long): Int

    external fun playerPlayWhenReady(handle: Long): Boolean

    external fun playerPositionUs(handle: Long): Long

    external fun playerDurationUs(handle: Long): Long

    external fun playerCurrentToken(handle: Long): Long

    external fun playerOutputSampleRate(handle: Long): Int

    external fun playerLastError(handle: Long): String

    /** What the player's error state is about: GEODE_PLAYER_ERROR_* in geode_api.h (0 none, 1 decode, 2 output). */
    external fun playerErrorKind(handle: Long): Int

    /** The player's mix, before volume and DSP, goes to the [feed] (a [feedCreate] handle; 0 stops it). */
    external fun playerSetFeed(
        handle: Long,
        feed: Long,
    ): Int

    /** [assets] is the app's `android.content.res.AssetManager`; typed as Any because this module has no Android SDK. */
    external fun vizCreate(
        assets: Any,
        cacheDir: String,
    ): Long

    external fun vizDestroy(handle: Long): Int

    external fun vizParamNames(): String

    external fun vizSetParams(
        handle: Long,
        values: FloatArray,
    ): Int

    external fun vizSetParam(
        handle: Long,
        name: String,
        value: Float,
    ): Boolean

    external fun vizSetFeatures(
        handle: Long,
        frame: FloatArray,
    ): Int

    external fun vizSetReducedMotion(
        handle: Long,
        on: Boolean,
    ): Int

    external fun vizSetLayer(
        handle: Long,
        sceneId: String,
        mix: Float,
        blendMode: Int,
    ): Int

    external fun vizSetTransition(
        handle: Long,
        id: String,
        durationMs: Long,
    ): Int

    external fun vizBeginParamMorph(
        handle: Long,
        seconds: Float,
    ): Int

    external fun vizSetTouch(
        handle: Long,
        xy: FloatArray,
        points: Int,
    ): Int

    external fun vizQueueTouchStroke(
        handle: Long,
        nx: Float,
        ny: Float,
        ndx: Float,
        ndy: Float,
        dt: Float,
        strength: Float,
    ): Int

    external fun vizLoadMilkPreset(
        handle: Long,
        path: String,
    ): Int

    external fun vizReloadMilkPreset(handle: Long): Int

    external fun vizSetMilkTextureDir(
        handle: Long,
        dir: String,
    ): Int

    external fun vizTakeMilkPresetLoaded(handle: Long): String?

    external fun vizSetLfo(
        handle: Long,
        slot: Int,
        config: FloatArray,
    ): Int

    external fun vizSetAdsr(
        handle: Long,
        slot: Int,
        config: FloatArray,
    ): Int

    external fun vizSetThermal(
        handle: Long,
        platformStatus: Int,
        headroom: Float,
    ): Int

    external fun vizSetPacedFps(
        handle: Long,
        fps: Float,
    ): Int

    /** The Visual quality on screen as a render scale ceiling under the thermal one (#28). */
    external fun vizSetQualityCeiling(
        handle: Long,
        ceiling: Float,
    ): Int

    external fun vizSetFrameLoad(
        handle: Long,
        frames: Int,
        p95Ms: Float,
    ): Int

    external fun vizSetOffscreen(
        handle: Long,
        on: Boolean,
    ): Int

    external fun vizKnows(
        handle: Long,
        sceneId: String,
    ): Boolean

    external fun vizSceneIds(handle: Long): String

    external fun vizLastError(handle: Long): String

    external fun vizSurfaceCreated(handle: Long): Int

    external fun vizSurfaceChanged(
        handle: Long,
        width: Int,
        height: Int,
    ): Int

    external fun vizSetScene(
        handle: Long,
        sceneId: String,
    ): Boolean

    external fun vizWarmTransition(
        handle: Long,
        id: String,
    ): Int

    external fun vizCut(handle: Long): Int

    external fun vizRender(
        handle: Long,
        timeSeconds: Double,
        targetFbo: Int,
    ): Int

    external fun vizReleaseScenes(handle: Long): Int

    /** Not RT-safe on the native side: only call this on a dsp handle not yet passed to [playerSetDsp]. */
    external fun dspSetSampleRate(
        handle: Long,
        sampleRate: Int,
    ): Int

    external fun dspSampleRate(handle: Long): Int

    /** The reason the most recent [tagsWrite] on this thread returned false; GEODE_TAGS_OK (0) otherwise. */
    external fun tagsLastError(): Int

    /**
     * Full-frame RGBA8 overlay drawn last, premultiplied alpha, over the finished composite. `pixels`
     * is Android's `Bitmap.getPixels` ARGB layout, width*height entries; null (or a non-positive
     * size) clears it. Any thread; latched for the next frame.
     */
    external fun vizSetOverlay(
        handle: Long,
        pixels: IntArray?,
        width: Int,
        height: Int,
    ): Int

    /**
     * Full-frame RGBA8 underlay blended UNDER/INTO the scene: [blend] 0 = screen (replaces the scene
     * where it is black), 1 = multiply, 2 = add; [amount] 0..1. Same pixel layout as [vizSetOverlay];
     * null (or a non-positive size) clears it. Any thread; latched for the next frame.
     */
    external fun vizSetUnderlay(
        handle: Long,
        pixels: IntArray?,
        width: Int,
        height: Int,
        blend: Int,
        amount: Float,
    ): Int

    /** A Customization snapshot exchange (geode_snapshots); 0 when it could not be created. */
    external fun snapshotsCreate(): Long

    /** Only once no Visual or analysis has it attached. */
    external fun snapshotsDestroy(handle: Long): Int

    /** [wire] is [CustomizationSnapshot.encode]'s output. Returns a [NativeStatus] code. */
    external fun snapshotsPublish(
        handle: Long,
        wire: ByteArray,
    ): Int

    /** The revision of the last snapshot published, 0 before any. */
    external fun snapshotsRevision(handle: Long): Int

    /** GL thread: the Visual reads the live sound from [feed] at the start of each frame from now on; 0 detaches. */
    external fun vizAttachFeed(
        handle: Long,
        feed: Long,
    ): Int

    /** The Visual's one audio feed (geode_feed); 0 when it could not be created. */
    external fun feedCreate(): Long

    /** [source] is GEODE_FEED_PLAYER (1) or GEODE_FEED_LIVE (2): the one source the Visual listens to. */
    external fun feedSelect(
        handle: Long,
        source: Int,
    ): Int

    external fun feedFlush(
        handle: Long,
        source: Int,
    ): Int

    external fun feedSetRunning(
        handle: Long,
        source: Int,
        running: Boolean,
    ): Int

    external fun feedSetPresentation(
        handle: Long,
        source: Int,
        delayUs: Long,
        speed: Float,
    ): Int

    /** Returns the frames taken: 0 when [source] is not the one listened to. */
    external fun feedWrite(
        handle: Long,
        source: Int,
        interleaved: FloatArray,
        frames: Int,
        channels: Int,
        sampleRate: Int,
    ): Int

    external fun feedSetActive(
        handle: Long,
        active: Boolean,
    ): Int

    external fun feedSetTuning(
        handle: Long,
        sensitivity: Float,
        refractoryMs: Float,
        attackSeconds: Float,
        releaseSeconds: Float,
    ): Int

    external fun feedResetAnalysis(handle: Long): Int

    /** The feed's analysis reads [snapshots] at the start of each hop from now on; 0 detaches. */
    external fun feedAttachSnapshots(
        handle: Long,
        snapshots: Long,
    ): Int

    /** The Visual reads [snapshots] at the start of each frame from now on; 0 detaches. */
    external fun vizAttachSnapshots(
        handle: Long,
        snapshots: Long,
    ): Int

    /** The analysis reads [snapshots] at the start of each hop from now on; 0 detaches. */
    external fun analysisAttachSnapshots(
        handle: Long,
        snapshots: Long,
    ): Int
}
