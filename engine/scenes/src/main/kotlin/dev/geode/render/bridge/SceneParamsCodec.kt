package dev.geode.render.bridge

import dev.geode.render.scene.SceneParams

/** Packs [SceneParams] in `geode_viz_param_name` order; [verify] proves that order against the library once. */
object SceneParamsCodec {
    const val FIELDS = 132

    val NAMES: List<String> =
        listOf(
            "speed",
            "zoom",
            "rotation",
            "endlessZoom",
            "endlessZoomSpeed",
            "sway",
            "pulse",
            "driftX",
            "driftY",
            "shake",
            "audioDrive",
            "beatResponse",
            "turbulence",
            "density",
            "marchDetail",
            "trails",
            "trailLength",
            "trailZoom",
            "trailWarp",
            "mirror",
            "warp",
            "ripple",
            "symmetry",
            "kaleidoscope",
            "morph",
            "pixelate",
            "posterize",
            "particleShape",
            "particleSize",
            "tile",
            "twist",
            "palette",
            "palette2",
            "paletteMix",
            "paletteBaseOverride",
            "paletteRangeOverride",
            "palette2BaseOverride",
            "palette2RangeOverride",
            "paletteLut",
            "milkdropPaletteTint",
            "milkdropBlendPresets",
            "colorShift",
            "hueRange",
            "saturation",
            "brightness",
            "contrast",
            "gamma",
            "colorCycle",
            "cycleSpeed",
            "invert",
            "intensity",
            "duotone",
            "bloom",
            "temperature",
            "solarize",
            "bassGain",
            "midGain",
            "trebGain",
            "chromaAb",
            "vignette",
            "scanlines",
            "grain",
            "glitch",
            "fisheye",
            "paramFadeSec",
            "fluidQuality",
            "fluidAutoQuality",
            "fluidIterations",
            "fluidPressure",
            "fluidCurl",
            "fluidVelocityDissipation",
            "fluidDensityDissipation",
            "fluidChromaticAging",
            "fluidSplatRadius",
            "fluidSplatForce",
            "fluidBeatPattern",
            "fluidBeatSplats",
            "fluidStirrers",
            "fluidStirrerSpeed",
            "fluidBassPump",
            "fluidPaletteCycleSpeed",
            "fluidSparkle",
            "fluidSpawnPath",
            "fluidSpawnPoints",
            "fluidSpawnProgress",
            "fluidCatchPoints",
            "fluidCatchPull",
            "fluidCatchRadius",
            "fluidParticlesEnabled",
            "fluidParticleLife",
            "fluidParticleDrag",
            "fluidParticleBrightness",
            "fluidDyeEnabled",
            "fluidShading",
            "fluidBloom",
            "fluidBloomIntensity",
            "fluidBloomThreshold",
            "fluidSunrays",
            "fluidSunraysWeight",
            "fluidCurlAudio",
            "fluidBloomAudio",
            "fluidFadeAudio",
            "fluidRadiusPulse",
            "flowEnabled",
            "flowStrength",
            "flowForce",
            "flowCurl",
            "waterWaveSpeed",
            "waterDamping",
            "waterRippleStrength",
            "waterDepth",
            "waterSpecular",
            "waterFlow",
            "waterLiquid",
            "waterLiquidFlow",
            "waterLiquidFade",
            "cymaticsGeometry",
            "cymaticsFundamental",
            "cymaticsModes",
            "cymaticsRing",
            "cymaticsFocus",
            "cymaticsScale",
            "cymaticsFill",
            "cymaticsLine",
            "cymaticsGlow",
            "cymaticsIridescence",
            "cymaticsCaustic",
            "cymaticsFlow",
            "cymaticsSwirl",
            "rippleOverlayEnabled",
            "rippleOverlayStrength",
            "rippleOverlaySpecular",
        )

    fun verify(nativeNames: String) {
        val native = nativeNames.split('\n')
        check(native == NAMES) { "native scene parameter order differs from SceneParamsCodec: $native" }
    }

    fun pack(
        p: SceneParams,
        out: FloatArray,
    ) {
        check(out.size >= FIELDS) { "scene parameter frame needs $FIELDS floats, got ${out.size}" }
        out[0] = p.speed
        out[1] = p.zoom
        out[2] = p.rotation
        out[3] = flag(p.endlessZoom)
        out[4] = p.endlessZoomSpeed
        out[5] = p.sway
        out[6] = p.pulse
        out[7] = p.driftX
        out[8] = p.driftY
        out[9] = p.shake
        out[10] = p.audioDrive
        out[11] = p.beatResponse
        out[12] = p.turbulence
        out[13] = p.density
        out[14] = p.marchDetail
        out[15] = flag(p.trails)
        out[16] = p.trailLength
        out[17] = p.trailZoom
        out[18] = p.trailWarp
        out[19] = flag(p.mirror)
        out[20] = p.warp
        out[21] = p.ripple
        out[22] = p.symmetry.toFloat()
        out[23] = flag(p.kaleidoscope)
        out[24] = p.morph
        out[25] = p.pixelate
        out[26] = p.posterize
        out[27] = p.particleShape.toFloat()
        out[28] = p.particleSize
        out[29] = p.tile
        out[30] = p.twist
        out[31] = p.palette.toFloat()
        out[32] = p.palette2.toFloat()
        out[33] = p.paletteMix
        out[34] = p.paletteBaseOverride
        out[35] = p.paletteRangeOverride
        out[36] = p.palette2BaseOverride
        out[37] = p.palette2RangeOverride
        out[38] = p.paletteLut.toFloat()
        out[39] = p.milkdropPaletteTint
        out[40] = flag(p.milkdropBlendPresets)
        out[41] = p.colorShift
        out[42] = p.hueRange
        out[43] = p.saturation
        out[44] = p.brightness
        out[45] = p.contrast
        out[46] = p.gamma
        out[47] = flag(p.colorCycle)
        out[48] = p.cycleSpeed
        out[49] = flag(p.invert)
        out[50] = p.intensity
        out[51] = flag(p.duotone)
        out[52] = p.bloom
        out[53] = p.temperature
        out[54] = flag(p.solarize)
        out[55] = p.bassGain
        out[56] = p.midGain
        out[57] = p.trebGain
        out[58] = p.chromaAb
        out[59] = p.vignette
        out[60] = p.scanlines
        out[61] = p.grain
        out[62] = p.glitch
        out[63] = p.fisheye
        out[64] = p.paramFadeSec
        out[65] = p.fluidQuality.toFloat()
        out[66] = flag(p.fluidAutoQuality)
        out[67] = p.fluidIterations.toFloat()
        out[68] = p.fluidPressure
        out[69] = p.fluidCurl
        out[70] = p.fluidVelocityDissipation
        out[71] = p.fluidDensityDissipation
        out[72] = p.fluidChromaticAging
        out[73] = p.fluidSplatRadius
        out[74] = p.fluidSplatForce
        out[75] = p.fluidBeatPattern.toFloat()
        out[76] = p.fluidBeatSplats.toFloat()
        out[77] = p.fluidStirrers.toFloat()
        out[78] = p.fluidStirrerSpeed
        out[79] = flag(p.fluidBassPump)
        out[80] = p.fluidPaletteCycleSpeed
        out[81] = flag(p.fluidSparkle)
        out[82] = p.fluidSpawnPath.toFloat()
        out[83] = p.fluidSpawnPoints.toFloat()
        out[84] = p.fluidSpawnProgress
        out[85] = p.fluidCatchPoints.toFloat()
        out[86] = p.fluidCatchPull
        out[87] = p.fluidCatchRadius
        out[88] = flag(p.fluidParticlesEnabled)
        out[89] = p.fluidParticleLife
        out[90] = p.fluidParticleDrag
        out[91] = p.fluidParticleBrightness
        out[92] = flag(p.fluidDyeEnabled)
        out[93] = flag(p.fluidShading)
        out[94] = flag(p.fluidBloom)
        out[95] = p.fluidBloomIntensity
        out[96] = p.fluidBloomThreshold
        out[97] = flag(p.fluidSunrays)
        out[98] = p.fluidSunraysWeight
        out[99] = p.fluidCurlAudio
        out[100] = p.fluidBloomAudio
        out[101] = p.fluidFadeAudio
        out[102] = p.fluidRadiusPulse
        out[103] = flag(p.flowEnabled)
        out[104] = p.flowStrength
        out[105] = p.flowForce
        out[106] = p.flowCurl
        out[107] = p.waterWaveSpeed
        out[108] = p.waterDamping
        out[109] = p.waterRippleStrength
        out[110] = p.waterDepth
        out[111] = p.waterSpecular
        out[112] = p.waterFlow
        out[113] = p.waterLiquid
        out[114] = p.waterLiquidFlow
        out[115] = p.waterLiquidFade
        out[116] = p.cymaticsGeometry.toFloat()
        out[117] = p.cymaticsFundamental
        out[118] = p.cymaticsModes.toFloat()
        out[119] = p.cymaticsRing
        out[120] = p.cymaticsFocus
        out[121] = p.cymaticsScale
        out[122] = p.cymaticsFill
        out[123] = p.cymaticsLine
        out[124] = p.cymaticsGlow
        out[125] = p.cymaticsIridescence
        out[126] = p.cymaticsCaustic
        out[127] = p.cymaticsFlow
        out[128] = p.cymaticsSwirl
        out[129] = flag(p.rippleOverlayEnabled)
        out[130] = p.rippleOverlayStrength
        out[131] = p.rippleOverlaySpecular
    }

    private fun flag(on: Boolean): Float = if (on) 1f else 0f
}
