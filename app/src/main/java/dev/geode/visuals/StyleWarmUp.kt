package dev.geode.visuals

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import dev.geode.RingLog
import dev.geode.engine.types.SceneId

/**
 * Warms every Style once while the phone is idle and charging (#304): each Style is drawn for a frame
 * offscreen, and each MilkDrop preset loaded, so their programs (and MilkDrop's translated shaders) land
 * in the engine's binary cache, and switching to any of them later compiles nothing. The cache survives
 * restarts and starts over after an app or driver update (ShaderCacheStamp, ProgramBinaryCache), and so
 * does the warm-up: its stamp names the install, the GPU driver and the presets it covered.
 */
object StyleWarmUp {
    /** The job's id, unique among the app's jobs. */
    const val JOB_ID = 7304

    private const val PREFS = "style_warm_up"
    private const val KEY_STAMP = "warmed"

    /** What a warm-up covered: the app's [install], the GPU [driver] and the MilkDrop [presets] (file names). */
    fun stamp(
        install: String,
        driver: String,
        presets: List<String>,
    ): String = listOf(install, driver, presets.sorted().joinToString("/")).joinToString("|")

    /** Whether a warm-up is due, after one that covered [stored] (null for none) and with [current] the stamp now. */
    fun due(
        stored: String?,
        current: String,
    ): Boolean = stored != current

    /**
     * The Styles to warm, in the order they are drawn: every one the engine draws, the plain MilkDrop one
     * last, since it is warmed again with each preset.
     */
    fun order(
        styles: List<SceneId>,
        milkdrop: SceneId,
    ): List<SceneId> = styles.filterNot { it == milkdrop } + styles.filter { it == milkdrop }

    /** Asks for a warm-up the next time the phone is idle and charging; once is enough while one waits. */
    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (scheduler.getPendingJob(JOB_ID) != null) return
        val job =
            JobInfo
                .Builder(JOB_ID, ComponentName(context, StyleWarmUpService::class.java))
                .setRequiresCharging(true)
                .setRequiresDeviceIdle(true)
                .build()
        runCatching { scheduler.schedule(job) }.onFailure { RingLog.note(TAG, "warm-up not scheduled", it) }
    }

    internal fun storedStamp(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_STAMP, null)

    internal fun storeStamp(
        context: Context,
        stamp: String,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_STAMP, stamp).apply()
    }

    private const val TAG = "StyleWarmUp"
}
