package dev.geode.visuals

import android.content.Context
import dev.geode.RingLog
import dev.geode.data.MilkTextureLinks
import dev.geode.feature.visuals.api.MilkdropKind
import dev.geode.feature.visuals.handlers.FileMilkdropFolder
import dev.geode.feature.visuals.handlers.MilkdropFolder
import dev.geode.render.scene.MilkStarterPack
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The app's MilkDrop folder as Visuals uses it (#305). Before it is first listed, the presets Geode ships
 * are copied in ([MilkStarterPack]), so they play as Styles from the same folder as the person's own; and
 * the texture links are rebuilt ([MilkTextureLinks]) then and after every import and delete, so the engine finds
 * each texture a preset asks for under the name it asks for it. A link that cannot be made never fails
 * the import: the preset is in, and its report says what it is missing. A pack's writes (#399) are made
 * as one batch, and the links rebuilt once after its last, not after each of its files.
 */
class InstalledMilkdropFolder(
    private val context: Context,
    private val folder: MilkdropFolder = FileMilkdropFolder.of(context),
) : MilkdropFolder {
    private val installed = AtomicBoolean(false)
    private val links by lazy { MilkTextureLinks(context) }

    @Volatile
    private var batching = false

    override suspend fun files(kind: MilkdropKind): List<String> {
        install()
        return folder.files(kind)
    }

    override suspend fun write(
        kind: MilkdropKind,
        name: String,
        bytes: ByteArray,
    ) {
        folder.write(kind, name, bytes)
        if (batching) return
        relink { if (kind == MilkdropKind.PRESETS) links.relink(File(StyleTargets.milkFolder(context), name)) else links.relinkAll() }
    }

    override suspend fun delete(
        kind: MilkdropKind,
        name: String,
    ) {
        folder.delete(kind, name)
        // relinkAll sweeps a deleted preset's links, and points every preset away from a deleted texture.
        relink { links.relinkAll() }
    }

    override suspend fun <T> batch(block: suspend () -> T): T {
        batching = true
        try {
            return block()
        } finally {
            batching = false
            relink { links.relinkAll() }
        }
    }

    private fun install() {
        if (!installed.compareAndSet(false, true)) return
        runCatching { MilkStarterPack.install(context, StyleTargets.milkFolder(context)) }
            .onFailure { RingLog.note(TAG, "the presets Geode ships could not be installed", it) }
        relink { links.relinkAll() }
    }

    private inline fun relink(block: () -> Unit) {
        runCatching(block).onFailure { RingLog.note(TAG, "texture links not rebuilt", it) }
    }

    private companion object {
        const val TAG = "MilkdropFolder"
    }
}
