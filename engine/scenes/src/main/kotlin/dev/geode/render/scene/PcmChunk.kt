package dev.geode.render.scene

/** The mono samples a PCM ring received since the renderer's last frame: [count] of [data], newest last. */
class PcmChunk(
    val data: FloatArray,
    val count: Int,
)
