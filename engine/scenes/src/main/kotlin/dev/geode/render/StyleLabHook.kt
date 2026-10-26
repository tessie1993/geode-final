package dev.geode.render

import android.content.Context
import dev.geode.render.bridge.NativeViz

/**
 * The debug build's Style lab (docs/style-packs.md, "Authoring loop"): points a native renderer at a
 * folder that Style packs pushed with adb reload from, without a restart. `styleLab` is defined per
 * build type and is null in a release build, which has no hot-reload path.
 */
internal fun interface StyleLabHook {
    fun attach(
        context: Context,
        viz: NativeViz,
    )
}
