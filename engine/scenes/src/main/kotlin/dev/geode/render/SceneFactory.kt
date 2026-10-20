package dev.geode.render

import dev.geode.engine.types.SceneId

/**
 * What an offscreen render needs to know about the scene it draws.
 *
 * Lives in `render` rather than beside the exporter so the render code has no dependency on
 * `export` — that was the one edge pointing back out of the engine layer.
 */
interface SceneFactory {
    val sceneId: SceneId

    /** The MilkDrop preset the live renderer had loaded, for an export of the milkdrop scene. */
    val milkPresetPath: String?
}
