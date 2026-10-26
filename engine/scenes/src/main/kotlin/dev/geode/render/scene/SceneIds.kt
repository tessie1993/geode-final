package dev.geode.render.scene

import dev.geode.engine.types.SceneId

/**
 * Ids of Styles that built-in content (presets, templates, suggestions) refers to. These are not a
 * list of the Styles: the Styles are the packs ([StylePacks]), and content naming a Style whose pack
 * has been removed falls back to [DEFAULT] or drops out ([SceneCapabilities.storedOrDefault]).
 */
object SceneIds {
    val JULIA = SceneId("julia")
    val TUNNEL = SceneId("tunnel")
    val BARS = SceneId("bars")
    val RING = SceneId("ring")
    val PLASMA = SceneId("plasma")
    val KALEIDO = SceneId("kaleido")
    val WARP = SceneId("warp")
    val GRID = SceneId("grid")
    val VORONOI = SceneId("voronoi")
    val MANDEL = SceneId("mandel")
    val LISS = SceneId("liss")
    val METABALLS = SceneId("metaballs")
    val RIPPLES = SceneId("ripples")
    val STARFIELD = SceneId("starfield")
    val HEXGRID = SceneId("hexgrid")
    val SPIRAL = SceneId("spiral")
    val AURORA = SceneId("aurora")
    val SOLAR = SceneId("solar")
    val WINTER = SceneId("winter")
    val LAVA = SceneId("lava")
    val MILKDROP = SceneId("milkdrop")
    val FLUID = SceneId("fluid")
    val CURLFLOW = SceneId("curlflow")
    val WATER = SceneId("water")
    val CYMATICS = SceneId("cymatics")
    val VANISHING = SceneId("vanishing")
    val MORPHOGEN = SceneId("morphogen")
    val NEBULA = SceneId("nebula")
    val NONEUCLID = SceneId("noneuclid")
    val KIFS = SceneId("kifs")
    val ORB_LATTICE = SceneId("orb_lattice")
    val ROD_TUNNEL = SceneId("rod_tunnel")
    val NEON_TILES = SceneId("neon_tiles")
    val CHROMA_ORB = SceneId("chroma_orb")
    val MANDALA_DOME = SceneId("mandala_dome")
    val BEAD_VORTEX = SceneId("bead_vortex")
    val MERKABA_GRID = SceneId("merkaba_grid")
    val SPIRAL_EYE = SceneId("spiral_eye")
    val BLACKLIGHT_BLOOM = SceneId("blacklight_bloom")
    val FRACTAL_TEMPLE = SceneId("fractal_temple")
    val CURL_BLOOM = SceneId("curl_bloom")
    val NECTAR_FLOW = SceneId("nectar_flow")

    /**
     * The default Style: the one a fresh install opens on, and the one an id no Style has
     * (a stored preset or setting naming a deleted Style) loads as.
     *
     * A stand-in, a calm shader Style, until the owner picks the real default; change it here
     * only. Declared last so the Style it names is already initialised. The app requests its
     * Style from the native renderer before every frame; the native side names no Style of its own.
     */
    val DEFAULT = AURORA
}
