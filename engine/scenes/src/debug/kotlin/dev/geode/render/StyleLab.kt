package dev.geode.render

import android.content.Context
import dev.geode.render.bridge.NativeViz
import java.io.File

/** A debug build watches the Style lab folder (docs/style-packs.md, "Authoring loop"). */
internal val styleLab: StyleLabHook? = StyleLabHook { context, viz -> DebugStyleLab.attach(context, viz) }

internal object DebugStyleLab {
    private const val FOLDER = "stylelab"

    /**
     * `<external files>/stylelab/`, where `adb push` puts a pack as `<id>/manifest.json` and its shader:
     * the app reads it with no permission, and the shell user can write it.
     */
    fun folder(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, FOLDER)

    fun attach(
        context: Context,
        viz: NativeViz,
    ) {
        viz.withHandle { handle -> nativeAttach(handle, folder(context).absolutePath) }
    }

    // core/viz/RendererStyleLab.cpp, through app/src/main/cpp/geode_viz_jni.cpp; built into debug builds only.
    private external fun nativeAttach(
        handle: Long,
        folder: String,
    ): Int
}
