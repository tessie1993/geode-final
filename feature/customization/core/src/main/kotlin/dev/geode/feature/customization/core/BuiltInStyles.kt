package dev.geode.feature.customization.core

import dev.geode.engine.types.Millis
import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.ExtraKind
import dev.geode.feature.customization.api.ExtraKind.CENTER_X
import dev.geode.feature.customization.api.ExtraKind.CENTER_Y
import dev.geode.feature.customization.api.ExtraKind.COMPLEXITY
import dev.geode.feature.customization.api.ExtraKind.COUNT
import dev.geode.feature.customization.api.ExtraKind.DECAY
import dev.geode.feature.customization.api.ExtraKind.DRIFT
import dev.geode.feature.customization.api.ExtraKind.EDGE
import dev.geode.feature.customization.api.ExtraKind.FLIGHT
import dev.geode.feature.customization.api.ExtraKind.FOG
import dev.geode.feature.customization.api.ExtraKind.FORCE
import dev.geode.feature.customization.api.ExtraKind.GROWTH
import dev.geode.feature.customization.api.ExtraKind.HOLD
import dev.geode.feature.customization.api.ExtraKind.ORBIT
import dev.geode.feature.customization.api.ExtraKind.REACH
import dev.geode.feature.customization.api.ExtraKind.SCALE
import dev.geode.feature.customization.api.ExtraKind.SIZE
import dev.geode.feature.customization.api.ExtraKind.SPIN
import dev.geode.feature.customization.api.ExtraKind.SPREAD
import dev.geode.feature.customization.api.ExtraKind.SWIRL
import dev.geode.feature.customization.api.ExtraKind.THICKNESS
import dev.geode.feature.customization.api.ExtraKind.TURN
import dev.geode.feature.customization.api.ExtraKind.VARIANT
import dev.geode.feature.customization.api.ExtraKind.VISCOSITY
import dev.geode.feature.customization.api.ExtraSpec
import dev.geode.feature.customization.api.Reaction
import dev.geode.feature.customization.api.ReactionId
import dev.geode.feature.customization.api.ReactionSource
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.SoundSignal
import dev.geode.feature.customization.api.SoundSignal.BASS
import dev.geode.feature.customization.api.SoundSignal.BEAT
import dev.geode.feature.customization.api.SoundSignal.BRIGHTNESS
import dev.geode.feature.customization.api.SoundSignal.HIT
import dev.geode.feature.customization.api.SoundSignal.LEVEL
import dev.geode.feature.customization.api.SoundSignal.MID
import dev.geode.feature.customization.api.SoundSignal.TREBLE
import dev.geode.feature.customization.api.StyleFamily
import dev.geode.feature.customization.api.StyleManifest
import dev.geode.feature.customization.api.Target

/** How fast a default Reaction follows its signal up and back down. */
val DEFAULT_RISE = Millis(30)
val DEFAULT_FALL = Millis(250)

/** The amount of a default "hit brightens it" Reaction; it always rises (No-flash rule). */
const val HIT_GLOW: Float = 0.2f

/**
 * The manifests of the Styles this version draws, from the per-Style audit on #11: each Style's real
 * variables as generic Extras, its hard-wired sound couplings as editable default Reactions, its own
 * flight, and its retired in-Style effects as Shared core starting values. Silk (#86) and user GLSL
 * (#87) are gone, and so is formDrive (#88); nothing here refers to them. Whole-frame beat flashes
 * (julia, plasma, Acid's overdrive) are not carried over: the No-flash rule forbids them.
 *
 * Each Extra's native range is chosen so that its starting position is the Style's value today.
 * Where the audit gives no number, the Extra starts mid-range on a 0..1 native scale ([generic]).
 */
object BuiltInStyles {
    /** The id every default Reaction carries until a Customization numbers it. */
    private val UNNUMBERED = ReactionId(0)

    /** An Extra whose native range is [min]..[max], starting at today's [native] value. */
    fun extra(
        kind: ExtraKind,
        native: Float,
        min: Float,
        max: Float,
        stepped: Boolean = false,
    ): ExtraSpec = ExtraSpec(kind, min, max, Rules.unit((native - min) / (max - min)), stepped)

    /** An Extra that can go from 0 to twice today's positive [native] value; it starts mid-range. */
    fun around(
        kind: ExtraKind,
        native: Float,
    ): ExtraSpec = extra(kind, native, 0f, native * 2f)

    /** A counted Extra, from 1 to twice today's [native] count. */
    fun count(
        native: Int,
        kind: ExtraKind = COUNT,
    ): ExtraSpec = extra(kind, native.toFloat(), 1f, native * 2f, stepped = true)

    /** An Extra the audit found no number for: a 0..1 native scale, starting mid-range. */
    fun generic(kind: ExtraKind): ExtraSpec = extra(kind, GENERIC_MIDDLE, 0f, 1f)

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

    private fun style(
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

    private fun shader(
        id: String,
        ownFlight: Boolean,
        extras: List<ExtraSpec>,
        reactions: List<Reaction> = emptyList(),
        shared: Map<SharedProperty, Float> = emptyMap(),
    ): StyleManifest = style(id, StyleFamily.SHADER, ownFlight, extras, reactions, shared)

    /** The 22 classic 2D shader Styles. */
    private val classicShaders: List<StyleManifest> =
        listOf(
            shader(
                "julia",
                false,
                listOf(extra(CENTER_X, -0.745f, -1.5f, 0.5f), extra(CENTER_Y, 0.186f, -1f, 1f), around(ORBIT, 0.11f), around(DRIFT, 0.15f)),
                listOf(react(BASS, CENTER_X, 0.2f), react(MID, CENTER_Y, 0.2f), react(TREBLE, SharedProperty.HUE_SHIFT, 0.15f)),
            ),
            shader(
                "tunnel",
                true,
                listOf(around(SCALE, 2f), around(FLIGHT, 0.6f), around(SPIN, 0.05f)),
                listOf(react(LEVEL, FLIGHT, 0.5f), react(TREBLE, SPIN, 0.2f)),
            ),
            shader(
                "mandel",
                false,
                listOf(extra(CENTER_X, -0.743643f, -2f, 1f), extra(CENTER_Y, 0.131825f, -1.5f, 1.5f), around(DRIFT, 0.02f)),
                listOf(react(BASS, SharedProperty.ZOOM, 0.1f)),
            ),
            // Its own fixed 6 folds are retired into the shared kaleidoscope (#11, rounds 4 and 5).
            shader(
                "kaleido",
                false,
                listOf(around(SCALE, 7f), around(DRIFT, 0.05f)),
                listOf(react(BASS, SCALE, 0.2f)),
                mapOf(SharedProperty.KALEIDOSCOPE to 1f, SharedProperty.SYMMETRY to KALEIDO_FOLDS),
            ),
            shader(
                "plasma",
                false,
                listOf(around(SCALE, 2f), around(DRIFT, 0.6f)),
                listOf(react(BASS, SCALE, 0.3f), react(LEVEL, DRIFT, 0.2f)),
            ),
            shader("bars", false, listOf(count(48), around(THICKNESS, 0.8f), around(EDGE, 9f))),
            shader(
                "ring",
                false,
                listOf(around(SIZE, 0.35f), around(THICKNESS, 0.5f), around(SPIN, 0.02f)),
                listOf(react(BASS, SIZE, 0.2f)),
            ),
            shader("scope", false, listOf(around(THICKNESS, 60f), count(4), around(SCALE, 0.6f)), listOf(react(LEVEL, SCALE, 0.3f))),
            shader("liss", false, listOf(count(64), around(SIZE, 30f), around(SCALE, 0.75f)), listOf(react(MID, SCALE, 0.2f))),
            shader("warp", true, listOf(count(3), around(FLIGHT, 0.5f)), listOf(react(LEVEL, FLIGHT, 0.5f), react(BEAT, FLIGHT, 0.3f))),
            shader(
                "grid",
                true,
                listOf(around(FLIGHT, 2f), around(SIZE, 0.35f), count(24), around(EDGE, 24f)),
                listOf(react(BASS, FLIGHT, 0.6f), hitGlow),
            ),
            shader("voronoi", false, listOf(around(SCALE, 3f), generic(DRIFT), around(EDGE, 10f)), listOf(react(BASS, EDGE, 0.2f))),
            shader(
                "metaballs",
                false,
                listOf(count(7), around(SIZE, 0.05f), around(ORBIT, 0.75f)),
                listOf(react(LEVEL, SIZE, 0.4f), hitGlow),
            ),
            shader("ripples", false, listOf(around(SCALE, 24f), generic(ORBIT)), listOf(react(BASS, SCALE, 0.3f), hitGlow)),
            shader("starfield", true, listOf(count(48), around(FLIGHT, 0.4f)), listOf(react(LEVEL, FLIGHT, 0.5f), hitGlow)),
            shader(
                "waves",
                false,
                listOf(count(5), around(SCALE, 0.34f), around(THICKNESS, 36f)),
                listOf(react(BASS, THICKNESS, 0.3f), react(LEVEL, SCALE, 0.2f)),
            ),
            // Cell scale was Morph's second meaning (+6·Morph); it is the SCALE Extra now.
            shader(
                "hexgrid",
                true,
                listOf(around(SCALE, 5f), around(FLIGHT, 0.4f), generic(EDGE)),
                listOf(react(TREBLE, EDGE, 0.2f), react(LEVEL, FLIGHT, 0.3f)),
            ),
            // Arm count was Morph's second meaning (+4·Morph); it is the COUNT Extra now.
            shader(
                "spiral",
                false,
                listOf(count(2), around(THICKNESS, 5f), around(SPIN, 1.8f)),
                listOf(react(BASS, THICKNESS, 0.3f), hitGlow),
            ),
            shader(
                "aurora",
                false,
                listOf(around(SCALE, 1.5f), around(DRIFT, 0.35f), around(THICKNESS, 2.4f)),
                listOf(react(MID, DRIFT, 0.2f), hitGlow),
            ),
            // Ray count was Morph's second meaning (+10·Morph); it is the COUNT Extra now.
            shader("solar", false, listOf(around(SIZE, 0.26f), count(8), around(EDGE, 3.5f)), listOf(react(BASS, SIZE, 0.2f), hitGlow)),
            shader("winter", false, listOf(count(3), generic(SIZE)), listOf(react(BASS, SharedProperty.RIPPLE, 0.2f), hitGlow)),
            shader(
                "lava",
                false,
                listOf(count(6), generic(DRIFT), around(SWIRL, 0.22f)),
                listOf(react(TREBLE, SWIRL, 0.3f), react(BASS, DRIFT, 0.2f)),
            ),
        )

    /** The 17 newer shader Styles. The hidden hit machinery (new direction, spawn, fractal) is removed. */
    private val newerShaders: List<StyleManifest> =
        listOf(
            // Its own grain is retired into the shared one.
            shader(
                "vanishing",
                true,
                listOf(generic(FLIGHT), generic(SIZE), generic(SPIN), generic(FOG)),
                listOf(react(LEVEL, FLIGHT, 0.3f), react(BASS, SIZE, 0.2f), hitGlow),
                mapOf(SharedProperty.GRAIN to RETIRED_GRAIN),
            ),
            // Hold time was Morph's second meaning; it is the HOLD Extra now.
            shader(
                "morphogen",
                true,
                listOf(generic(HOLD), generic(SCALE), generic(COMPLEXITY)),
                listOf(react(HIT, COMPLEXITY, 0.2f), react(BASS, SCALE, 0.2f)),
            ),
            // Its own vignette is retired into the shared one.
            shader(
                "nebula",
                true,
                listOf(generic(FOG), generic(COMPLEXITY), generic(DRIFT)),
                listOf(react(BASS, FOG, 0.2f), hitGlow),
                mapOf(SharedProperty.VIGNETTE to RETIRED_VIGNETTE),
            ),
            shader(
                "noneuclid",
                true,
                listOf(generic(COMPLEXITY), generic(THICKNESS), generic(FOG)),
                listOf(react(BASS, SharedProperty.PUSH_IN, 0.3f), hitGlow),
                mapOf(SharedProperty.VIGNETTE to RETIRED_VIGNETTE),
            ),
            shader(
                "kifs",
                true,
                listOf(generic(SCALE), generic(COMPLEXITY), generic(ORBIT), generic(SPIN)),
                listOf(react(BASS, SCALE, 0.2f), react(BASS, SharedProperty.PUSH_IN, 0.2f)),
            ),
            shader("orb_lattice", false, listOf(generic(SIZE), generic(SCALE)), listOf(react(BASS, SIZE, 0.2f))),
            shader(
                "rod_tunnel",
                true,
                listOf(generic(FLIGHT), generic(SCALE), generic(THICKNESS), generic(COUNT), generic(FOG)),
                listOf(react(LEVEL, FLIGHT, 0.3f), react(TREBLE, THICKNESS, 0.2f)),
            ),
            shader("neon_tiles", false, listOf(generic(COMPLEXITY)), listOf(react(BASS, COMPLEXITY, 0.2f))),
            shader("chroma_orb", false, listOf(generic(SIZE)), listOf(react(BASS, SIZE, 0.3f))),
            shader(
                "mandala_dome",
                false,
                listOf(generic(SCALE)),
                listOf(react(BASS, SCALE, 0.2f), react(TREBLE, SharedProperty.HUE_SHIFT, 0.1f)),
            ),
            shader("bead_vortex", false, listOf(generic(SWIRL)), listOf(react(BASS, SWIRL, 0.3f))),
            shader("merkaba_grid", false, listOf(generic(COUNT)), listOf(react(LEVEL, SharedProperty.WARP, 0.15f))),
            shader("spiral_eye", false, listOf(generic(THICKNESS)), listOf(react(BASS, THICKNESS, 0.2f))),
            shader("blacklight_bloom", false, listOf(generic(SCALE)), listOf(react(BASS, SCALE, 0.2f))),
            shader("fractal_temple", true, listOf(generic(SCALE)), listOf(react(LEVEL, SharedProperty.TRAVEL, 0.2f))),
            shader("curl_bloom", true, listOf(generic(ORBIT), generic(SIZE)), listOf(react(BASS, SIZE, 0.2f))),
            shader("nectar_flow", true, listOf(generic(COMPLEXITY), generic(FOG)), listOf(react(BASS, FOG, 0.2f))),
        )

    /**
     * The 3D-world Styles. Each flies a camera of its own through a world it marches or intersects, so
     * every one owns its flight and moves with the Camera (lib_camera.glsl). Their sound couplings are
     * drawn in the shader; the Extras listed are the ones each shader reads.
     */
    private val worldShaders: List<StyleManifest> =
        listOf(
            shader("canyon_run", true, listOf(generic(TURN), generic(FOG))),
            shader("lantern_river", true, listOf(generic(SIZE), generic(FOG))),
            shader("ringed_giant", true, listOf(generic(SIZE))),
            shader("crystal_shaft", true, listOf(generic(FOG))),
            shader("night_drive", true, listOf(generic(FOG))),
        )

    private fun life(
        id: String,
        grayScott: Boolean,
        growth: Float,
        spread: Float,
    ): StyleManifest {
        val extras =
            if (grayScott) {
                listOf(extra(GROWTH, growth, GS_FEED_MIN, GS_FEED_MAX), extra(SPREAD, spread, GS_KILL_MIN, GS_KILL_MAX))
            } else {
                listOf(extra(GROWTH, growth, LENIA_MU_MIN, LENIA_MU_MAX), extra(SPREAD, spread, LENIA_SIGMA_MIN, LENIA_SIGMA_MAX))
            }
        val rule = extra(VARIANT, if (grayScott) 1f else 0f, 0f, 1f, stepped = true)
        return style(
            id,
            StyleFamily.LIFE,
            false,
            extras + rule + generic(FORCE),
            listOf(react(HIT, FORCE, 0.3f), react(LEVEL, GROWTH, 0.1f)),
        )
    }

    private val lifeStyles: List<StyleManifest> =
        listOf(
            life("life_orbium", false, 0.15f, 0.017f),
            life("life_gyre", false, 0.156f, 0.0224f),
            life("life_helix", false, 0.3f, 0.0505f),
            life("life_pulsar", false, 0.38f, 0.07f),
            life("life_hydro", false, LENIA_MU, LENIA_SIGMA),
            life("life_bug", false, 0.31f, LENIA_SIGMA),
            life("life_mitosis", true, 0.0367f, 0.0649f),
            life("life_coral", true, 0.0545f, 0.062f),
            life("life_labyrinth", true, 0.026f, 0.055f),
            life("life_worms", true, 0.078f, 0.061f),
        )

    private fun myco(
        id: String,
        reach: Float = MYCO_REACH,
        turn: Float = MYCO_TURN,
        drift: Float = MYCO_REAIM,
    ): StyleManifest =
        style(
            id,
            StyleFamily.MYCELIUM,
            false,
            listOf(
                extra(REACH, reach, MYCO_REACH_MIN, MYCO_REACH_MAX),
                extra(TURN, turn, MYCO_TURN_MIN, MYCO_TURN_MAX),
                extra(DRIFT, drift, 0f, MYCO_REAIM_MAX),
                generic(DECAY),
                generic(FORCE),
            ),
            listOf(react(BASS, FORCE, 0.3f), react(LEVEL, DRIFT, 0.2f), react(TREBLE, TURN, 0.15f)),
        )

    private val mycoStyles: List<StyleManifest> =
        listOf(
            myco("myco_polycephalum", drift = 0.15f),
            myco("myco_rivals"),
            myco("myco_symbiosis"),
            myco("myco_predator"),
            myco("myco_ghosts"),
            myco("myco_circuit"),
            myco("myco_silkroad", reach = 22f),
            myco("myco_sporestorm", drift = 0.5f),
            myco("myco_capillary", reach = 5f, turn = 1.1f),
            myco("myco_frostvein"),
        )

    /**
     * Acid's own CRT (glitch, scanlines, screen curve) is retired into the shared lens and glitch
     * controls; its glitch is held to the shared cap. Its overdrive was a whole-frame flash keyed on an
     * instant hit, and is not carried over.
     */
    private fun acid(
        id: String,
        mode: Int,
        zoom: Float = ACID_ZOOM,
        rotate: Float = 0f,
        feedback: Float = ACID_FEEDBACK,
        hueRate: Float = ACID_HUE_RATE,
        liquid: Float = 0f,
        modulate: Float = 0f,
        glitch: Float = 0f,
        scanline: Float = 0f,
        curve: Float = 0f,
    ): StyleManifest =
        style(
            id,
            StyleFamily.ACID,
            true,
            listOf(
                extra(VARIANT, mode.toFloat(), 0f, ACID_MODES, stepped = true),
                extra(FLIGHT, zoom, ACID_ZOOM_MIN, ACID_ZOOM_MAX),
                extra(SPIN, rotate, -ACID_ROTATE_MAX, ACID_ROTATE_MAX),
                extra(DECAY, feedback, ACID_FEEDBACK_MIN, ACID_FEEDBACK_MAX),
                extra(DRIFT, hueRate, 0f, ACID_HUE_RATE_MAX),
                extra(VISCOSITY, liquid, 0f, 1f),
                extra(SWIRL, modulate, 0f, 1f),
            ),
            listOf(react(BASS, FLIGHT, 0.3f), react(HIT, SWIRL, 0.2f), react(TREBLE, DRIFT, 0.2f)),
            mapOf(
                SharedProperty.GLITCH to glitch,
                SharedProperty.SCANLINES to scanline,
                SharedProperty.FISHEYE to curve,
            ).filterValues { it != 0f },
        )

    private val acidStyles: List<StyleManifest> =
        listOf(
            acid("acid_tv", 0, liquid = 0.8f, glitch = 0.3f, scanline = 0.25f, curve = 0.3f),
            acid("acid_well", 1, zoom = 1.035f, scanline = 0.45f, curve = 0.5f),
            acid("acid_kaleid", 2, modulate = 0.5f, liquid = 0.5f),
            acid("acid_droste", 3, zoom = 1f),
            acid("acid_prism", 4, rotate = -0.0014f),
            acid("acid_mosh", 5, glitch = 1f),
            acid("acid_scan", 6, glitch = 0.7f, scanline = 0.8f, curve = 0.6f),
            acid("acid_solar", 7, hueRate = 0.08f, feedback = 0.93f),
            acid("acid_mirror", 8, modulate = 0.45f),
            acid("acid_smear", 9, liquid = 0.3f),
        )

    /** Fluid's own bloom is retired into the shared glow. The catalog's multipliers set where it starts. */
    private fun fluid(
        id: String,
        look: Int,
        curl: Float = 1f,
        velocity: Float = 1f,
        density: Float = 1f,
        radius: Float = 1f,
        bloom: Float = 1f,
    ): StyleManifest =
        style(
            id,
            StyleFamily.FLUID,
            true,
            listOf(
                extra(VARIANT, look.toFloat(), 0f, FLUID_LOOKS, stepped = true),
                extra(SWIRL, FLUID_CURL * curl, 0f, FLUID_CURL_MAX),
                extra(VISCOSITY, FLUID_VELOCITY_DISSIPATION * velocity, 0f, 1f),
                extra(DECAY, FLUID_DENSITY_DISSIPATION * density, 0f, FLUID_DENSITY_DISSIPATION_MAX),
                extra(SIZE, FLUID_SPLAT_RADIUS * radius, 0f, FLUID_SPLAT_RADIUS_MAX),
                extra(FORCE, 1f, 0f, FLUID_SPLAT_FORCE_MAX),
            ),
            listOf(react(MID, SWIRL, 0.25f), react(LEVEL, SharedProperty.GLOW, 0.25f), react(HIT, SIZE, 0.2f), react(BASS, FORCE, 0.3f)),
            mapOf(SharedProperty.GLOW to Rules.unit(FLUID_BLOOM * bloom)),
        )

    private val fluidStyles: List<StyleManifest> =
        listOf(
            fluid("fluid", 0),
            fluid("fluid_ink", 1, curl = 0.6f, velocity = 1.6f, density = 0.5f, bloom = 0.4f),
            fluid("fluid_oilslick", 2, curl = 1.3f, velocity = 0.7f, density = 0.6f),
            fluid("fluid_neon", 3, curl = 1.1f, bloom = 1.4f),
            fluid("fluid_chrome", 4, density = 0.45f, radius = 1.4f, bloom = 0.6f),
            fluid("fluid_smoke", 5, curl = 1.5f, velocity = 0.5f, density = 1.4f, radius = 1.6f),
            fluid("fluid_lava", 6, curl = 0.5f, velocity = 2f, density = 0.35f, bloom = 1.5f),
            fluid("fluid_marble", 7, density = 0.3f, bloom = 0.5f),
            fluid("fluid_aurora", 8, curl = 1.4f, velocity = 0.6f, bloom = 1.2f),
        )

    private val curlFlow: StyleManifest =
        style(
            "curlflow",
            StyleFamily.CURL_FLOW,
            true,
            listOf(generic(SWIRL), generic(DRIFT)),
            listOf(react(MID, SWIRL, 0.3f), react(TREBLE, DRIFT, 0.2f)),
        )

    private val water: StyleManifest =
        style(
            "water",
            StyleFamily.WATER,
            true,
            listOf(
                extra(DRIFT, 1f, 0f, WATER_WAVE_SPEED_MAX),
                extra(VISCOSITY, WATER_DAMPING, WATER_DAMPING_MIN, WATER_DAMPING_MAX),
                extra(FORCE, 1f, 0f, WATER_RIPPLE_MAX),
                extra(FOG, WATER_DEPTH, 0f, 1f),
                extra(SWIRL, WATER_FLOW, 0f, 1f),
            ),
            listOf(react(TREBLE, FORCE, 0.3f)),
        )

    /** Cymatics' own glow is retired into the shared glow. */
    private fun cymatics(
        id: String,
        geometry: Int = 0,
    ): StyleManifest =
        style(
            id,
            StyleFamily.CYMATICS,
            true,
            listOf(
                extra(VARIANT, geometry.toFloat(), 0f, 1f, stepped = true),
                extra(COUNT, CYMATICS_MODES, 1f, CYMATICS_MODES_MAX, stepped = true),
                extra(SCALE, CYMATICS_SCALE, 1f, CYMATICS_SCALE_MAX),
                extra(THICKNESS, 1f, CYMATICS_LINE_MIN, CYMATICS_LINE_MAX),
                extra(EDGE, CYMATICS_RING, 0f, 1f),
                extra(SWIRL, CYMATICS_SWIRL, 0f, CYMATICS_SWIRL_MAX),
                extra(DRIFT, CYMATICS_FLOW, 0f, 1f),
                extra(SIZE, CYMATICS_FILL, 0f, 1f),
            ),
            listOf(react(BRIGHTNESS, COUNT, 0.3f), react(BASS, SCALE, 0.2f), hitGlow),
            mapOf(SharedProperty.GLOW to CYMATICS_GLOW),
        )

    private val cymaticsStyles: List<StyleManifest> =
        listOf(
            cymatics("cymatics"),
            cymatics("chladni_sand", geometry = 1),
            cymatics("bessel_drum"),
            cymatics("harmonograph"),
            cymatics("faraday"),
            cymatics("harmonic_shell"),
            cymatics("caustic_sheet"),
            cymatics("levitator"),
            cymatics("standing_chamber"),
            cymatics("rosensweig"),
            cymatics("kundt_tube"),
        )

    /** MilkDrop gets the Shared core only; its presets fly and react by themselves. */
    private val milkDrop: StyleManifest = style("milkdrop", StyleFamily.MILKDROP, true, emptyList())

    /** Every Style this version draws. */
    val all: List<StyleManifest> =
        classicShaders + newerShaders + worldShaders + lifeStyles + mycoStyles + acidStyles + fluidStyles +
            listOf(curlFlow, water) + cymaticsStyles + milkDrop

    val catalogue: StyleCatalogue = StyleCatalogue(all)

    private const val GENERIC_MIDDLE = 0.5f
    private const val KALEIDO_FOLDS = 6f
    private const val RETIRED_GRAIN = 0.12f
    private const val RETIRED_VIGNETTE = 0.3f

    private const val LENIA_MU = 0.15f
    private const val LENIA_SIGMA = 0.017f
    private const val LENIA_MU_MIN = 0.1f
    private const val LENIA_MU_MAX = 0.45f
    private const val LENIA_SIGMA_MIN = 0.01f
    private const val LENIA_SIGMA_MAX = 0.08f
    private const val GS_FEED_MIN = 0.01f
    private const val GS_FEED_MAX = 0.09f
    private const val GS_KILL_MIN = 0.045f
    private const val GS_KILL_MAX = 0.07f

    private const val MYCO_REACH = 9f
    private const val MYCO_REACH_MIN = 3f
    private const val MYCO_REACH_MAX = 24f
    private const val MYCO_TURN = 0.6f
    private const val MYCO_TURN_MIN = 0.1f
    private const val MYCO_TURN_MAX = 1.2f
    private const val MYCO_REAIM = 0.3f
    private const val MYCO_REAIM_MAX = 0.6f

    private const val ACID_MODES = 9f
    private const val ACID_ZOOM = 1.02f
    private const val ACID_ZOOM_MIN = 0.98f
    private const val ACID_ZOOM_MAX = 1.06f
    private const val ACID_ROTATE_MAX = 0.004f
    private const val ACID_FEEDBACK = 0.95f
    private const val ACID_FEEDBACK_MIN = 0.85f
    private const val ACID_FEEDBACK_MAX = 0.99f
    private const val ACID_HUE_RATE = 0.02f
    private const val ACID_HUE_RATE_MAX = 0.12f

    private const val FLUID_LOOKS = 8f
    private const val FLUID_CURL = 30f
    private const val FLUID_CURL_MAX = 60f
    private const val FLUID_VELOCITY_DISSIPATION = 0.2f
    private const val FLUID_DENSITY_DISSIPATION = 1f
    private const val FLUID_DENSITY_DISSIPATION_MAX = 3f
    private const val FLUID_SPLAT_RADIUS = 0.12f
    private const val FLUID_SPLAT_RADIUS_MAX = 0.4f
    private const val FLUID_SPLAT_FORCE_MAX = 3f
    private const val FLUID_BLOOM = 0.8f

    private const val WATER_WAVE_SPEED_MAX = 3f
    private const val WATER_DAMPING = 0.985f
    private const val WATER_DAMPING_MIN = 0.95f
    private const val WATER_DAMPING_MAX = 0.999f
    private const val WATER_RIPPLE_MAX = 3f
    private const val WATER_DEPTH = 0.6f
    private const val WATER_FLOW = 0.3f

    private const val CYMATICS_MODES = 5f
    private const val CYMATICS_MODES_MAX = 10f
    private const val CYMATICS_SCALE = 3.2f
    private const val CYMATICS_SCALE_MAX = 8f
    private const val CYMATICS_LINE_MIN = 0.2f
    private const val CYMATICS_LINE_MAX = 3f
    private const val CYMATICS_RING = 0.35f
    private const val CYMATICS_SWIRL = 0.05f
    private const val CYMATICS_SWIRL_MAX = 0.5f
    private const val CYMATICS_FLOW = 0.35f
    private const val CYMATICS_FILL = 0.45f
    private const val CYMATICS_GLOW = 0.5f
}
