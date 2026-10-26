package dev.geode.analysis

import android.content.Context
import android.net.Uri
import dev.geode.util.bestEffort
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

object AnalysisCache {
    private const val MAGIC = 0x4D564143

    // v3 adds the musical-structure and stereo/chroma fields of AudioFeatures that v2
    // silently zero-filled on load (tempoStability, barPhase, beatInBar, downbeat,
    // downbeatConfidence, novelty, sectionBoundary, buildup, drop, arrival, harmonicity,
    // warmup, beatStrength, beatPhase, transient, pulseConfidence, macroEnergy, kick,
    // snare, hat, stereoWidth, stereoCorrelation, stereoPan, chroma, chromaConfidence).
    // A v2 file fails the version check below and is treated as a cache miss.
    private const val VERSION = 3
    private const val MAX_ENTRIES = 15

    // Bands and chroma are stored as shorts in 1/8192 steps, the waveform in 1/32767 steps.
    private const val BAND_SCALE = 8192f
    private const val WAVE_SCALE = 32767f
    private const val MAX_BEAT_IN_BAR = 255

    private fun dir(context: Context): File = File(context.filesDir, "analysis").apply { mkdirs() }

    private fun fileFor(
        context: Context,
        uri: Uri,
        profile: AnalysisProfile,
    ): File {
        val (size, mtime) = contentStamp(context, uri)
        return File(dir(context), cacheKey(uri.toString(), size, mtime, AnalysisIdentity.CURRENT + profile.tag) + ".mvac")
    }

    internal fun cacheKey(
        uriString: String,
        sizeBytes: Long,
        lastModifiedMs: Long,
        identity: String = AnalysisIdentity.CURRENT,
    ): String {
        val digest =
            MessageDigest
                .getInstance("SHA-1")
                .digest("$uriString|$sizeBytes|$lastModifiedMs|$identity".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun contentStamp(
        context: Context,
        uri: Uri,
    ): Pair<Long, Long> =
        runCatching {
            when (uri.scheme) {
                null, "file" -> {
                    val f = File(uri.path ?: return@runCatching 0L to 0L)
                    f.length() to f.lastModified()
                }
                "content" ->
                    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        if (!c.moveToFirst()) return@use 0L to 0L

                        fun col(name: String): Long {
                            val i = c.getColumnIndex(name)
                            return if (i >= 0 && !c.isNull(i)) c.getLong(i) else 0L
                        }
                        val size = col(android.provider.OpenableColumns.SIZE)
                        val mtime =
                            col(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                                .takeIf { it != 0L }
                                ?: (col(android.provider.MediaStore.MediaColumns.DATE_MODIFIED) * 1000L)
                        size to mtime
                    } ?: (0L to 0L)
                else -> 0L to 0L
            }
        }.getOrDefault(0L to 0L)

    fun load(
        context: Context,
        uri: Uri,
        beatSensitivity: Float,
        beatMinIntervalMs: Float,
        profile: AnalysisProfile = AnalysisProfile.LIVE,
    ): FeatureTimeline? {
        val f = fileFor(context, uri, profile)
        if (!f.exists()) return null
        val loaded =
            runCatching {
                DataInputStream(f.inputStream().buffered()).use { d ->
                    read(d)?.also { f.setLastModified(System.currentTimeMillis()) }
                }
            }.getOrNull()
        if (loaded == null) {
            bestEffort(TAG, "f.delete()") { f.delete() }
            return null
        }
        // The drum channels are read back as analysed, not worked out again from the stored bands, so a
        // cached analysis draws exactly what a fresh one does (#302).
        return loaded.withBeatSensitivity(beatSensitivity, beatMinIntervalMs)
    }

    /**
     * The analysis [write] put in [d], or null when [d] holds another version or a damaged one. Bands,
     * the waveform and chroma come back at the precision they are stored in, which [stored] gives.
     */
    internal fun read(d: DataInputStream): FeatureTimeline? {
        if (d.readInt() != MAGIC || d.readInt() != VERSION) return null
        val hopMs = d.readLong()
        val hopRateHz = d.readFloat()
        val key = d.readUTF()
        val frameCount = d.readInt()
        val bandCount = d.readInt()
        val waveSize = d.readInt()
        val chromaSize = d.readInt()
        if (frameCount < 0 || frameCount > 1_000_000) return null
        if (bandCount < 0 || bandCount > 4_096) return null
        if (waveSize < 0 || waveSize > 65_536) return null
        if (chromaSize < 0 || chromaSize > 4_096) return null
        val frames = ArrayList<TimelineFrame>(frameCount)
        repeat(frameCount) {
            val timeMs = d.readLong()
            val bands = FloatArray(bandCount) { d.readShort() / BAND_SCALE }
            val wave = FloatArray(waveSize) { d.readShort() / WAVE_SCALE }
            val rms = d.readFloat()
            val bass = d.readFloat()
            val mid = d.readFloat()
            val treble = d.readFloat()
            val onset = d.readFloat()
            val bpm = d.readFloat()
            val centroid = d.readFloat()
            val beat = d.readByte().toInt() != 0
            val flux = d.readFloat()
            val beatStrength = d.readFloat()
            val beatPhase = d.readFloat()
            val transient = d.readFloat()
            val pulseConfidence = d.readFloat()
            val macroEnergy = d.readFloat()
            val kick = d.readFloat()
            val snare = d.readFloat()
            val hat = d.readFloat()
            val stereoWidth = d.readFloat()
            val stereoCorrelation = d.readFloat()
            val stereoPan = d.readFloat()
            val chroma = FloatArray(chromaSize) { d.readShort() / BAND_SCALE }
            val chromaConfidence = d.readFloat()
            val tempoStability = d.readFloat()
            val barPhase = d.readFloat()
            // readByte() sign-extends; mask to undo the unsigned 0..255 range written below.
            val beatInBar = d.readByte().toInt() and 0xFF
            val downbeat = d.readByte().toInt() != 0
            val downbeatConfidence = d.readFloat()
            val novelty = d.readFloat()
            val sectionBoundary = d.readByte().toInt() != 0
            val buildup = d.readFloat()
            val drop = d.readByte().toInt() != 0
            val arrival = d.readByte().toInt() != 0
            val harmonicity = d.readFloat()
            val warmup = d.readFloat()
            frames +=
                TimelineFrame(
                    timeMs,
                    AudioFeatures(
                        bands = bands,
                        waveform = wave,
                        rms = rms,
                        bass = bass,
                        mid = mid,
                        treble = treble,
                        onset = onset,
                        beat = beat,
                        bpm = bpm,
                        centroid = centroid,
                        flux = flux,
                        beatStrength = beatStrength,
                        beatPhase = beatPhase,
                        transient = transient,
                        pulseConfidence = pulseConfidence,
                        macroEnergy = macroEnergy,
                        kick = kick,
                        snare = snare,
                        hat = hat,
                        stereoWidth = stereoWidth,
                        stereoCorrelation = stereoCorrelation,
                        stereoPan = stereoPan,
                        chroma = chroma,
                        chromaConfidence = chromaConfidence,
                        tempoStability = tempoStability,
                        barPhase = barPhase,
                        beatInBar = beatInBar,
                        downbeat = downbeat,
                        downbeatConfidence = downbeatConfidence,
                        novelty = novelty,
                        sectionBoundary = sectionBoundary,
                        buildup = buildup,
                        drop = drop,
                        arrival = arrival,
                        harmonicity = harmonicity,
                        warmup = warmup,
                    ),
                )
        }
        return FeatureTimeline(frames, hopMs, key, hopRateHz)
    }

    fun save(
        context: Context,
        uri: Uri,
        t: FeatureTimeline,
        profile: AnalysisProfile = AnalysisProfile.LIVE,
    ) {
        if (t.frames.isEmpty()) return
        val f = fileFor(context, uri, profile)
        runCatching {
            DataOutputStream(f.outputStream().buffered()).use { d -> write(t, d) }
            evict(context)
        }.onFailure { runCatching { f.delete() } }
    }

    /** Writes [t] to [d], as [read] reads it back. [t] must hold at least one frame. */
    internal fun write(
        t: FeatureTimeline,
        d: DataOutputStream,
    ) {
        d.writeInt(MAGIC)
        d.writeInt(VERSION)
        d.writeLong(t.hopMs)
        d.writeFloat(t.hopRateHz)
        d.writeUTF(t.key)
        d.writeInt(t.frames.size)
        val bandCount =
            t.frames[0]
                .features.bands.size
        val waveSize =
            t.frames[0]
                .features.waveform.size
        val chromaSize =
            t.frames[0]
                .features.chroma.size
        d.writeInt(bandCount)
        d.writeInt(waveSize)
        d.writeInt(chromaSize)
        for (fr in t.frames) {
            d.writeLong(fr.timeMs)
            val fe = fr.features
            for (i in 0 until bandCount) {
                d.writeShort(quantized(fe.bands.getOrElse(i) { 0f }, BAND_SCALE))
            }
            for (i in 0 until waveSize) {
                d.writeShort(quantized(fe.waveform.getOrElse(i) { 0f }, WAVE_SCALE))
            }
            d.writeFloat(fe.rms)
            d.writeFloat(fe.bass)
            d.writeFloat(fe.mid)
            d.writeFloat(fe.treble)
            d.writeFloat(fe.onset)
            d.writeFloat(fe.bpm)
            d.writeFloat(fe.centroid)
            d.writeByte(if (fe.beat) 1 else 0)
            d.writeFloat(fe.flux)
            d.writeFloat(fe.beatStrength)
            d.writeFloat(fe.beatPhase)
            d.writeFloat(fe.transient)
            d.writeFloat(fe.pulseConfidence)
            d.writeFloat(fe.macroEnergy)
            d.writeFloat(fe.kick)
            d.writeFloat(fe.snare)
            d.writeFloat(fe.hat)
            d.writeFloat(fe.stereoWidth)
            d.writeFloat(fe.stereoCorrelation)
            d.writeFloat(fe.stereoPan)
            for (i in 0 until chromaSize) {
                d.writeShort(quantized(fe.chroma.getOrElse(i) { 0f }, BAND_SCALE))
            }
            d.writeFloat(fe.chromaConfidence)
            d.writeFloat(fe.tempoStability)
            d.writeFloat(fe.barPhase)
            d.writeByte(fe.beatInBar.coerceIn(0, MAX_BEAT_IN_BAR))
            d.writeByte(if (fe.downbeat) 1 else 0)
            d.writeFloat(fe.downbeatConfidence)
            d.writeFloat(fe.novelty)
            d.writeByte(if (fe.sectionBoundary) 1 else 0)
            d.writeFloat(fe.buildup)
            d.writeByte(if (fe.drop) 1 else 0)
            d.writeByte(if (fe.arrival) 1 else 0)
            d.writeFloat(fe.harmonicity)
            d.writeFloat(fe.warmup)
        }
    }

    /**
     * [t] at the precision the cache keeps it in: bands, the waveform and chroma rounded as they are
     * stored, and every frame sized as the first. A fresh analysis is used in this form, so it draws
     * exactly what the same analysis read back from the cache does (#302).
     */
    fun stored(t: FeatureTimeline): FeatureTimeline {
        if (t.frames.isEmpty()) return t
        val first = t.frames[0].features
        val bandCount = first.bands.size
        val waveSize = first.waveform.size
        val chromaSize = first.chroma.size
        val frames =
            t.frames.map { fr ->
                val fe = fr.features
                TimelineFrame(
                    fr.timeMs,
                    fe.copy(
                        bands = fe.bands.storedAs(bandCount, BAND_SCALE),
                        waveform = fe.waveform.storedAs(waveSize, WAVE_SCALE),
                        chroma = fe.chroma.storedAs(chromaSize, BAND_SCALE),
                        beatInBar = fe.beatInBar.coerceIn(0, MAX_BEAT_IN_BAR),
                    ),
                )
            }
        return FeatureTimeline(frames, t.hopMs, t.key, t.hopRateHz)
    }

    /** [value] as the short the cache stores it as, at [scale] steps per unit, rounded to the nearest. */
    private fun quantized(
        value: Float,
        scale: Float,
    ): Int = Math.round(value * scale).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())

    private fun FloatArray.storedAs(
        size: Int,
        scale: Float,
    ): FloatArray = FloatArray(size) { i -> quantized(getOrElse(i) { 0f }, scale).toShort() / scale }

    private fun evict(context: Context) {
        val files = dir(context).listFiles()?.sortedBy { it.lastModified() } ?: return
        var excess = files.size - MAX_ENTRIES
        for (f in files) {
            if (excess <= 0) break
            if (f.delete()) excess--
        }
    }

    fun sizeBytes(context: Context): Long = dir(context).listFiles()?.sumOf { it.length() } ?: 0L

    fun entryCount(context: Context): Int = dir(context).listFiles()?.size ?: 0

    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }
}

private const val TAG = "AnalysisCache"
