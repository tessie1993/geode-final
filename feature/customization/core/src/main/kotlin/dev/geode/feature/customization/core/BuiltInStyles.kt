package dev.geode.feature.customization.core

import dev.geode.engine.types.Millis
import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.ExtraKind
import dev.geode.feature.customization.api.ExtraKind.CENTER_X
import dev.geode.feature.customization.api.ExtraKind.CENTER_Y
import dev.geode.feature.customization.api.ExtraKind.COMPLEXITY
import dev.geode.feature.customization.api.ExtraKind.COUNT
import dev.geode.feature.customization.api.ExtraKind.DRIFT
import dev.geode.feature.customization.api.ExtraKind.EDGE
import dev.geode.feature.customization.api.ExtraKind.FLIGHT
import dev.geode.feature.customization.api.ExtraKind.FOG
import dev.geode.feature.customization.api.ExtraKind.FORCE
import dev.geode.feature.customization.api.ExtraKind.GROWTH
import dev.geode.feature.customization.api.ExtraKind.SCALE
import dev.geode.feature.customization.api.ExtraKind.SIZE
import dev.geode.feature.customization.api.ExtraKind.SPIN
import dev.geode.feature.customization.api.ExtraKind.SWIRL
import dev.geode.feature.customization.api.ExtraKind.THICKNESS
import dev.geode.feature.customization.api.ExtraKind.TURN
import dev.geode.feature.customization.api.ExtraSpec
import dev.geode.feature.customization.api.Reaction
import dev.geode.feature.customization.api.ReactionId
import dev.geode.feature.customization.api.ReactionSource
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.SoundSignal
import dev.geode.feature.customization.api.SoundSignal.BASS
import dev.geode.feature.customization.api.SoundSignal.BEAT
import dev.geode.feature.customization.api.SoundSignal.BRIGHTNESS
import dev.geode.feature.customization.api.SoundSignal.CHANGE
import dev.geode.feature.customization.api.SoundSignal.HIT
import dev.geode.feature.customization.api.SoundSignal.LEVEL
import dev.geode.feature.customization.api.SoundSignal.MID
import dev.geode.feature.customization.api.SoundSignal.SURGE
import dev.geode.feature.customization.api.SoundSignal.TREBLE
import dev.geode.feature.customization.api.StyleFamily
import dev.geode.feature.customization.api.StyleManifest
import dev.geode.feature.customization.api.Target

/** How fast a default Reaction follows its signal up and back down. */
val DEFAULT_RISE = Millis(30)
val DEFAULT_FALL = Millis(250)

/** How slowly a spark burst settles after the drop or arrival that threw it. */
private val BURST_FALL = Millis(1_600)

/** The amount of a default "hit brightens it" Reaction; it always rises (No-flash rule). */
const val HIT_GLOW: Float = 0.2f

/** One Style pack as PackExtras.kt lists it: its id, its family and its Extras, in the pack's order. */
internal class PackStyle(
    val id: String,
    val family: StyleFamily,
    val extras: List<ExtraSpec>,
)

/** A pack's Style with [extras]. */
internal fun pack(
    id: String,
    family: StyleFamily,
    vararg extras: ExtraSpec,
): PackStyle = PackStyle(id, family, extras.toList())

/**
 * A pack's Extra: its native range [min]..[max], which the Customization's 0..1 maps onto, starting at
 * [default] there. The variant steps on whole numbers, as the engine's Normaliser steps it.
 */
internal fun extra(
    kind: ExtraKind,
    min: Float,
    max: Float,
    default: Float,
): ExtraSpec = ExtraSpec(kind, min, max, (default - min) / (max - min), stepped = kind == ExtraKind.VARIANT)

/**
 * The manifests of the Styles this version draws, from the per-Style audit on #11: each Style's
 * hard-wired sound couplings as editable default Reactions, its own flight, and its retired in-Style
 * effects as Shared core starting values. Silk (#86) and user GLSL (#87) are gone, and so is formDrive
 * (#88); nothing here refers to them. Whole-frame beat flashes (julia, plasma, Acid's overdrive) are not
 * carried over: the No-flash rule forbids them.
 *
 * A Style's Extras are its pack's (docs/style-packs.md, "extras"), read from the packs the engine draws
 * with through the generated [PackExtras], never written down a second time here: every one is a
 * multiplier of the Style's own amount (an offset for the centres and the variant) whose default draws
 * the Style exactly as its pack tunes it.
 */
object BuiltInStyles {
    /** The packs, by id. Declared first: the Styles below read it as they are built. */
    private val packs: Map<String, PackStyle> = PackExtras.styles.associateBy { it.id }

    /** The id every default Reaction carries until a Customization numbers it. */
    private val UNNUMBERED = ReactionId(0)

    /** A default Reaction: [signal] pushes [kind] (an Extra) by [amount]. */
    fun react(
        signal: SoundSignal,
        kind: ExtraKind,
        amount: Float,
    ): Reaction = react(signal, Target.Extra(kind), amount)

    /** A default Reaction: [signal] pushes [property] (Shared core) by [amount]. */
    fun react(
        signal: SoundSignal,
        property: SharedProperty,
        amount: Float,
    ): Reaction = react(signal, Target.Shared(property), amount)

    private fun react(
        signal: SoundSignal,
        target: Target,
        amount: Float,
    ): Reaction = Reaction(UNNUMBERED, ReactionSource.Sound(signal), target, amount, rise = DEFAULT_RISE, fall = DEFAULT_FALL)

    /** A hit brightens the Style's glow, with a rise instead of a snap. */
    private val hitGlow: Reaction = react(HIT, SharedProperty.GLOW, HIT_GLOW)

    /** A Style's manifest: its 6 suggested Macros follow from its family, its flight and its [extras]. */
    private fun manifest(
        id: String,
        family: StyleFamily,
        ownFlight: Boolean,
        extras: List<ExtraSpec>,
        reactions: List<Reaction> = emptyList(),
        shared: Map<SharedProperty, Float> = emptyMap(),
    ): StyleManifest =
        StyleManifest(
            id = SceneId(id),
            family = family,
            ownFlight = ownFlight,
            extras = extras,
            shared = shared,
            macros = DefaultMacros.forStyle(family, ownFlight, extras.map { it.kind }.toSet()),
            reactions = reactions,
        )

    private fun style(
        id: String,
        family: StyleFamily,
        ownFlight: Boolean,
        reactions: List<Reaction> = emptyList(),
        shared: Map<SharedProperty, Float> = emptyMap(),
    ): StyleManifest = manifest(id, family, ownFlight, packs[id]?.extras.orEmpty(), reactions, shared)

    private fun shader(
        id: String,
        ownFlight: Boolean,
        reactions: List<Reaction> = emptyList(),
        shared: Map<SharedProperty, Float> = emptyMap(),
    ): StyleManifest = style(id, StyleFamily.SHADER, ownFlight, reactions, shared)

    /** The 22 classic 2D shader Styles. */
    private val classicShaders: List<StyleManifest> =
        listOf(
            shader(
                "julia",
                false,
                listOf(react(BASS, CENTER_X, 0.2f), react(MID, CENTER_Y, 0.2f), react(TREBLE, SharedProperty.HUE_SHIFT, 0.15f)),
            ),
            shader("tunnel", true, listOf(react(LEVEL, FLIGHT, 0.5f), react(TREBLE, SPIN, 0.2f))),
            shader("mandel", false, listOf(react(BASS, SharedProperty.ZOOM, 0.1f))),
            // Its own fixed 6 folds are retired into the shared kaleidoscope (#11, rounds 4 and 5).
            shader(
                "kaleido",
                false,
                listOf(react(BASS, SCALE, 0.2f)),
                mapOf(SharedProperty.KALEIDOSCOPE to 1f, SharedProperty.SYMMETRY to KALEIDO_FOLDS),
            ),
            shader("plasma", false, listOf(react(BASS, SCALE, 0.3f), react(LEVEL, DRIFT, 0.2f))),
            shader("bars", false),
            shader("ring", false, listOf(react(BASS, SIZE, 0.2f))),
            shader("scope", false, listOf(react(LEVEL, SCALE, 0.3f))),
            shader("liss", false, listOf(react(MID, SCALE, 0.2f))),
            shader("warp", true, listOf(react(LEVEL, FLIGHT, 0.5f), react(BEAT, FLIGHT, 0.3f))),
            shader("grid", true, listOf(react(BASS, FLIGHT, 0.6f), hitGlow)),
            shader("voronoi", false, listOf(react(BASS, EDGE, 0.2f))),
            shader("metaballs", false, listOf(react(LEVEL, SIZE, 0.4f), hitGlow)),
            shader("ripples", false, listOf(react(BASS, SCALE, 0.3f), hitGlow)),
            shader("starfield", true, listOf(react(LEVEL, FLIGHT, 0.5f), hitGlow)),
            shader("waves", false, listOf(react(BASS, THICKNESS, 0.3f), react(LEVEL, SCALE, 0.2f))),
            // Cell scale was Morph's second meaning (+6·Morph); it is the SCALE Extra now.
            shader("hexgrid", true, listOf(react(TREBLE, EDGE, 0.2f), react(LEVEL, FLIGHT, 0.3f))),
            // Arm count was Morph's second meaning (+4·Morph); it is the COUNT Extra now.
            shader("spiral", false, listOf(react(BASS, THICKNESS, 0.3f), hitGlow)),
            shader("aurora", false, listOf(react(MID, DRIFT, 0.2f), hitGlow)),
            // Ray count was Morph's second meaning (+10·Morph); it is the COUNT Extra now.
            shader("solar", false, listOf(react(BASS, SIZE, 0.2f), hitGlow)),
            shader("winter", false, listOf(react(BASS, SharedProperty.RIPPLE, 0.2f), hitGlow)),
            shader("lava", false, listOf(react(TREBLE, SWIRL, 0.3f), react(BASS, DRIFT, 0.2f))),
        )

    /** The 17 newer shader Styles. The hidden hit machinery (new direction, spawn, fractal) is removed. */
    private val newerShaders: List<StyleManifest> =
        listOf(
            // Its own grain is retired into the shared one.
            shader(
                "vanishing",
                true,
                listOf(react(LEVEL, FLIGHT, 0.3f), react(BASS, SIZE, 0.2f), hitGlow),
                mapOf(SharedProperty.GRAIN to RETIRED_GRAIN),
            ),
            // Hold time was Morph's second meaning; it is the HOLD Extra now.
            shader("morphogen", true, listOf(react(HIT, COMPLEXITY, 0.2f), react(BASS, SCALE, 0.2f))),
            // Its own vignette is retired into the shared one.
            shader("nebula", true, listOf(react(BASS, FOG, 0.2f), hitGlow), mapOf(SharedProperty.VIGNETTE to RETIRED_VIGNETTE)),
            shader(
                "noneuclid",
                true,
                listOf(react(BASS, SharedProperty.PUSH_IN, 0.3f), hitGlow),
                mapOf(SharedProperty.VIGNETTE to RETIRED_VIGNETTE),
            ),
            shader("kifs", true, listOf(react(BASS, SCALE, 0.2f), react(BASS, SharedProperty.PUSH_IN, 0.2f))),
            shader("orb_lattice", false, listOf(react(BASS, SIZE, 0.2f))),
            shader("rod_tunnel", true, listOf(react(LEVEL, FLIGHT, 0.3f), react(TREBLE, THICKNESS, 0.2f))),
            shader("neon_tiles", false, listOf(react(BASS, COMPLEXITY, 0.2f))),
            shader("chroma_orb", false, listOf(react(BASS, SIZE, 0.3f))),
            shader("mandala_dome", false, listOf(react(BASS, SCALE, 0.2f), react(TREBLE, SharedProperty.HUE_SHIFT, 0.1f))),
            shader("bead_vortex", false, listOf(react(BASS, SWIRL, 0.3f))),
            shader("merkaba_grid", false, listOf(react(LEVEL, SharedProperty.WARP, 0.15f))),
            shader("spiral_eye", false, listOf(react(BASS, THICKNESS, 0.2f))),
            shader("blacklight_bloom", false, listOf(react(BASS, SCALE, 0.2f))),
            shader("fractal_temple", true, listOf(react(LEVEL, SharedProperty.TRAVEL, 0.2f))),
            shader("curl_bloom", true, listOf(react(BASS, SIZE, 0.2f))),
            shader("nectar_flow", true, listOf(react(BASS, FOG, 0.2f))),
        )

    /**
     * The 3D-world Styles. Each flies a camera of its own through a world it marches or intersects, so
     * every one owns its flight and moves with the Camera (lib_camera.glsl). Their sound couplings are
     * drawn in the shader; their Extras are their packs'.
     */
    private val worldShaders: List<StyleManifest> =
        listOf(
            "canyon_run",
            "lantern_river",
            "ringed_giant",
            "crystal_shaft",
            "night_drive",
            "reef_glide",
            "cloud_deck",
            "gyro_core",
            "endless_stacks",
            "dune_sea",
        ).map { shader(it, true) }

    /**
     * The camera-world shader Styles: each moves a real camera through a 3D world of its own (it reads
     * the Camera through lib_camera.glsl, so push-in, orbit and travel move that camera) and wires its
     * music in its shader, so none has Extras or default Reactions yet.
     */
    private val cameraWorldShaders: List<StyleManifest> =
        listOf(
            "hyper_lattice",
            "dew_macro",
            "monolith_plain",
            "spiral_descent",
            "glass_vines",
            "swarm_chase",
            "origami_peaks",
            "rain_glass",
            "sea_cave",
            "pulsar_orbit",
        ).map { shader(it, true) }

    /**
     * The camera library's journeys (#396): each Style is built around one camera move and, where it
     * draws a 3D world, owns its Camera (lib_camera). Their motion is their own clock; their Reactions
     * only shift colour or push the Camera, never brightness.
     */
    private val cameraJourneys: List<StyleManifest> =
        listOf(
            shader("little_planet", true, listOf(react(MID, SharedProperty.HUE_SHIFT, 0.1f))),
            shader("event_horizon", true, listOf(react(TREBLE, SharedProperty.HUE_SHIFT, 0.08f))),
            shader("wormhole_pass", true, listOf(react(MID, SharedProperty.HUE_SHIFT, 0.08f))),
            shader("grand_reveal", true, listOf(react(MID, SharedProperty.HUE_SHIFT, 0.08f))),
            shader("torchlight", true, listOf(react(BASS, SharedProperty.SHAKE, 0.1f))),
            shader("train_window", false, listOf(react(MID, SharedProperty.HUE_SHIFT, 0.06f))),
            shader("folding_city", true, listOf(react(MID, SharedProperty.HUE_SHIFT, 0.06f))),
            shader("colonnade_cuts", false, listOf(react(MID, SharedProperty.HUE_SHIFT, 0.06f))),
        )

    /**
     * The rest of the camera library (#396) and the wave 1 worlds (#310): each moves a camera of its own
     * through its world (lib_camera), so each owns its flight; their Extras and music are their packs'.
     */
    private val cameraWorlds: List<StyleManifest> =
        listOf(
            "vertigo_well",
            "ten_powers",
            "star_gate",
            "frozen_moment",
            "toy_town",
            "loop_coaster",
            "penrose_climb",
            "tide_line",
            "warp_drive",
            "mandala_cavern",
            "polyhedra_cathedral",
            "mirror_prism",
            "iris_droste",
        ).map { shader(it, true) }

    /**
     * A spark burst: [signal] (a drop or an arrival) pushes the FORCE Extra by [amount], rising no faster
     * than the No-flash rise and settling over [BURST_FALL], so a burst blooms and dies away.
     */
    private fun burst(
        signal: SoundSignal,
        amount: Float,
    ): Reaction =
        Reaction(
            UNNUMBERED,
            ReactionSource.Sound(signal),
            Target.Extra(FORCE),
            amount,
            rise = NO_FLASH_MIN_RISE,
            fall = BURST_FALL,
        )

    /**
     * The Visuals v3 Styles. Vortex Forge (#323) owns its camera (lib_camera) and reads FORCE above its
     * neutral 1 as its spark burst, which drops and arrivals throw.
     */
    private val forgeShaders: List<StyleManifest> =
        listOf(
            shader("vortex_forge", true, listOf(burst(SURGE, 0.5f), burst(CHANGE, 0.4f))),
        )

    private fun life(id: String): StyleManifest =
        style(id, StyleFamily.LIFE, false, listOf(react(HIT, FORCE, 0.3f), react(LEVEL, GROWTH, 0.1f)))

    private val lifeStyles: List<StyleManifest> =
        listOf(
            "life_orbium",
            "life_gyre",
            "life_helix",
            "life_pulsar",
            "life_hydro",
            "life_bug",
            "life_mitosis",
            "life_coral",
            "life_labyrinth",
            "life_worms",
        ).map(::life)

    private fun myco(id: String): StyleManifest =
        style(
            id,
            StyleFamily.MYCELIUM,
            false,
            listOf(react(BASS, FORCE, 0.3f), react(LEVEL, DRIFT, 0.2f), react(TREBLE, TURN, 0.15f)),
        )

    private val mycoStyles: List<StyleManifest> =
        listOf(
            "myco_polycephalum",
            "myco_rivals",
            "myco_symbiosis",
            "myco_predator",
            "myco_ghosts",
            "myco_circuit",
            "myco_silkroad",
            "myco_sporestorm",
            "myco_capillary",
            "myco_frostvein",
        ).map(::myco)

    /**
     * Acid's own CRT (glitch, scanlines, screen curve) is retired into the shared lens and glitch
     * controls; its glitch is held to the shared cap. Its overdrive was a whole-frame flash keyed on an
     * instant hit, and is not carried over.
     */
    private fun acid(
        id: String,
        glitch: Float = 0f,
        scanline: Float = 0f,
        curve: Float = 0f,
    ): StyleManifest =
        style(
            id,
            StyleFamily.ACID,
            true,
            listOf(react(BASS, FLIGHT, 0.3f), react(HIT, SWIRL, 0.2f), react(TREBLE, DRIFT, 0.2f)),
            mapOf(
                SharedProperty.GLITCH to glitch,
                SharedProperty.SCANLINES to scanline,
                SharedProperty.FISHEYE to curve,
            ).filterValues { it != 0f },
        )

    private val acidStyles: List<StyleManifest> =
        listOf(
            acid("acid_tv", glitch = 0.3f, scanline = 0.25f, curve = 0.3f),
            acid("acid_well", scanline = 0.45f, curve = 0.5f),
            acid("acid_kaleid"),
            acid("acid_droste"),
            acid("acid_prism"),
            acid("acid_mosh", glitch = 1f),
            acid("acid_scan", glitch = 0.7f, scanline = 0.8f, curve = 0.6f),
            acid("acid_solar"),
            acid("acid_mirror"),
            acid("acid_smear"),
        )

    /** Fluid's own bloom is retired into the shared glow; the catalog's multiplier sets where it starts. */
    private fun fluid(
        id: String,
        bloom: Float = 1f,
    ): StyleManifest =
        style(
            id,
            StyleFamily.FLUID,
            true,
            listOf(react(MID, SWIRL, 0.25f), react(LEVEL, SharedProperty.GLOW, 0.25f), react(HIT, SIZE, 0.2f), react(BASS, FORCE, 0.3f)),
            mapOf(SharedProperty.GLOW to Rules.unit(FLUID_BLOOM * bloom)),
        )

    private val fluidStyles: List<StyleManifest> =
        listOf(
            fluid("fluid"),
            fluid("fluid_ink", bloom = 0.4f),
            fluid("fluid_oilslick"),
            fluid("fluid_neon", bloom = 1.4f),
            fluid("fluid_chrome", bloom = 0.6f),
            fluid("fluid_smoke"),
            fluid("fluid_lava", bloom = 1.5f),
            fluid("fluid_marble", bloom = 0.5f),
            fluid("fluid_aurora", bloom = 1.2f),
        )

    private val curlFlow: StyleManifest =
        style("curlflow", StyleFamily.CURL_FLOW, true, listOf(react(MID, SWIRL, 0.3f), react(TREBLE, DRIFT, 0.2f)))

    private val water: StyleManifest = style("water", StyleFamily.WATER, true, listOf(react(TREBLE, FORCE, 0.3f)))

    /** Cymatics' own glow is retired into the shared glow. */
    private fun cymatics(id: String): StyleManifest =
        style(
            id,
            StyleFamily.CYMATICS,
            true,
            listOf(react(BRIGHTNESS, COUNT, 0.3f), react(BASS, SCALE, 0.2f), hitGlow),
            mapOf(SharedProperty.GLOW to CYMATICS_GLOW),
        )

    private val cymaticsStyles: List<StyleManifest> =
        listOf(
            "cymatics",
            "chladni_sand",
            "bessel_drum",
            "harmonograph",
            "faraday",
            "harmonic_shell",
            "caustic_sheet",
            "levitator",
            "standing_chamber",
            "rosensweig",
            "kundt_tube",
        ).map(::cymatics)

    /** MilkDrop gets the Shared core only; its presets fly and react by themselves. */
    private val milkDrop: StyleManifest = style("milkdrop", StyleFamily.MILKDROP, true)

    /** The Styles the #11 audit designed (their own Reactions and starting values) and the ones that fly their own camera. */
    internal val designed: List<StyleManifest> =
        classicShaders + newerShaders + worldShaders + cameraWorldShaders + cameraJourneys + cameraWorlds + forgeShaders +
            lifeStyles + mycoStyles + acidStyles + fluidStyles + listOf(curlFlow, water) + cymaticsStyles + milkDrop

    /**
     * [audited], then a plain manifest (its pack's family and Extras, no Reactions or own flight) for
     * every pack in [fromPacks] that [audited] does not cover, so a new Style's Extras reach Customize
     * from its pack alone.
     */
    internal fun catalogueOf(
        audited: List<StyleManifest>,
        fromPacks: List<PackStyle>,
    ): List<StyleManifest> {
        val known = audited.map { it.id.value }.toSet()
        return audited + fromPacks.filter { it.id !in known }.map { manifest(it.id, it.family, ownFlight = false, it.extras) }
    }

    /** Every Style this version draws. */
    val all: List<StyleManifest> = catalogueOf(designed, PackExtras.styles)

    val catalogue: StyleCatalogue = StyleCatalogue(all)

    private const val KALEIDO_FOLDS = 6f
    private const val RETIRED_GRAIN = 0.12f
    private const val RETIRED_VIGNETTE = 0.3f
    private const val FLUID_BLOOM = 0.8f
    private const val CYMATICS_GLOW = 0.5f
}
