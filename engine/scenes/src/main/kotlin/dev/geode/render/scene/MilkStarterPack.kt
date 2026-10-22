package dev.geode.render.scene

import android.content.Context
import java.io.File

/**
 * Copies the bundled MilkDrop presets (`assets/milk`, listed with their credits in
 * `assets/milk/catalog.json`) into the user's preset folder. A file already there is left alone, so
 * a preset the user has edited keeps their version.
 */
object MilkStarterPack {
    private const val ASSET_DIR = "milk"

    private const val MARKER = ".starter-pack"

    // 2: the curated set of 50 (issue #93) joined Geode's own 6; installs at 1 copy the new files.
    const val VERSION: Int = 2

    @Suppress("ReturnCount")
    fun install(
        context: Context,
        target: File = File(context.filesDir, "milk"),
    ): Int {
        if (readMarker(target) >= VERSION) return 0
        if (!target.isDirectory && !target.mkdirs()) return 0
        val written =
            runCatching { context.assets.list(ASSET_DIR).orEmpty() }
                .getOrDefault(emptyArray())
                .filter { it.endsWith(".milk") }
                .filterNot { File(target, it).exists() }
                .count { copyPreset(context, it, File(target, it)) }
        writeMarker(target)
        return written
    }

    private fun copyPreset(
        context: Context,
        assetName: String,
        destination: File,
    ): Boolean {
        val copied =
            runCatching {
                context.assets.open("$ASSET_DIR/$assetName").use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
            }.isSuccess
        if (!copied) runCatching { destination.delete() }
        return copied
    }

    private fun readMarker(target: File): Int = runCatching { File(target, MARKER).readText().trim().toInt() }.getOrDefault(0)

    private fun writeMarker(target: File) {
        runCatching { File(target, MARKER).writeText(VERSION.toString()) }
    }
}
