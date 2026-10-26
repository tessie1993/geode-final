package dev.geode.feature.visuals.core

import dev.geode.feature.visuals.api.PresetPlayback

/**
 * How a MilkDrop preset will play in Geode's projectM, read from its text before it is ever loaded
 * (#305): whether its shaders use names our projectM cannot translate, and whether it samples textures
 * the MilkDrop folder does not hold. Pure: the handlers hand it the preset's text and the folder's
 * texture names.
 *
 * Only the preset's shader code counts (its `warp_N` and `comp_N` lines, comments left out): the
 * equations never name a texture, and a name in a comment is not a use.
 */
object MilkdropChecks {
    /**
     * What our projectM cannot translate yet, as the call or name a shader writes: shader functions that
     * BeatDrop and MilkDrop 3 added, and HLSL intrinsics the translator has no entry for. A shader that
     * uses one is dropped, and the preset plays on MilkDrop's plain warp and composite.
     */
    val UNSUPPORTED_CALLS: List<String> =
        listOf(
            "get_fft",
            "get_fft_hz",
            "get_fft_peak",
            "get_fft_peak_hz",
            "get_wave",
            "get_wave_left",
            "get_wave_right",
            "tanh",
            "sinh",
            "cosh",
            "radians",
            "degrees",
            "frexp",
            "all",
            "isfinite",
            "determinant",
            "lit",
            "tex1D",
            "tex1Dlod",
            "tex1Dbias",
            "tex1Dgrad",
            "tex1Dproj",
            "tex3Dgrad",
            "tex3Dproj",
            "texCUBEgrad",
            "texCUBEproj",
        )

    /** Inputs BeatDrop and MilkDrop 3 added that our projectM does not provide yet, as a shader names them. */
    val UNSUPPORTED_NAMES: List<String> =
        listOf(
            "sampler_fft",
            "sampler_wave",
            "aspect_sd",
            "mouse",
            "mouse_x",
            "mouse_y",
            "mouse_pos",
            "mouse_clicked",
            "mouse_released",
            "sysTime",
            "sysDate",
            "sysTimeHour",
            "sysTimeMinute",
            "sysTimeSecond",
            "sysTimeTotalSeconds",
            "sysDateYear",
            "sysDateMonth",
            "sysDateDay",
            "sysDateWeekday",
        )

    /** The textures projectM makes itself, which no folder needs to hold. */
    val BUILT_IN_TEXTURES: Set<String> =
        setOf(
            "main",
            "blur1",
            "blur2",
            "blur3",
            "noise_lq",
            "noise_lq_lite",
            "noise_mq",
            "noise_hq",
            "noisevol_lq",
            "noisevol_hq",
            "fft",
            "wave",
        )

    private val shaderLine = Regex("^\\s*(?:warp|comp)_\\d+\\s*=(.*)$", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
    private val blockComment = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
    private val lineComment = Regex("//[^\\n]*")

    /** `sampler_worms`, or with the filter and wrap mode a preset may ask for first: `sampler_fw_worms`. */
    private val samplerReference = Regex("\\bsampler_(?:(?:fw|fc|pw|pc|wf|cf|wp|cp)_)?(\\w+)", RegexOption.IGNORE_CASE)

    /** A random texture slot, `rand00` to `rand99`, with or without a name prefix after it. */
    private val randomSlot = Regex("rand\\d\\d(?:_\\w*)?")

    /** What an import that renamed a texture appended: `_` and eight hex digits of the old name's hash. */
    private val renameSuffix = Regex("_[0-9a-f]{8}$")

    /** How [text] (a preset file's text) will play, beside a MilkDrop folder holding the textures [textures] (file names). */
    fun playback(
        text: String,
        textures: Collection<String>,
    ): PresetPlayback {
        val code = shaderCode(text)
        val unsupported = unsupported(code)
        if (unsupported.isNotEmpty()) return PresetPlayback.WithoutShader(unsupported)
        val missing = missingTextures(code, textures)
        return if (missing.isEmpty()) PresetPlayback.Full else PresetPlayback.MissingTextures(missing)
    }

    /** The shader code in [text]: every `warp_N` and `comp_N` line's value, in order, without comments. */
    fun shaderCode(text: String): String {
        val lines = shaderLine.findAll(text).map { it.groupValues[1].removePrefix("`") }
        return lineComment.replace(blockComment.replace(lines.joinToString("\n"), " "), "")
    }

    /** The names in [code] our projectM cannot translate, each once, in the order the lists above give them. */
    fun unsupported(code: String): List<String> {
        val calls = UNSUPPORTED_CALLS.filter { Regex("\\b${it}\\s*\\(").containsMatchIn(code) }
        val names = UNSUPPORTED_NAMES.filter { Regex("\\b$it\\b").containsMatchIn(code) }
        return calls + names
    }

    /** The textures [code] samples by name, each once and in the order it first names them; none projectM makes itself. */
    fun textures(code: String): List<String> =
        samplerReference
            .findAll(code)
            .map { it.groupValues[1] }
            .filterNot { it.lowercase() in BUILT_IN_TEXTURES || randomSlot.matches(it.lowercase()) }
            .distinctBy { it.lowercase() }
            .toList()

    /**
     * The textures [code] samples that none of [textures] answers. A texture answers to its own name,
     * case aside, and to its name before an import renamed it, which is how the engine's texture links
     * find it too (`MilkTextureLinks`).
     */
    fun missingTextures(
        code: String,
        textures: Collection<String>,
    ): List<String> {
        val held =
            textures
                .map { it.substringBeforeLast('.') }
                .flatMap { listOf(normalized(it), normalized(it.replace(renameSuffix, ""))) }
                .toSet()
        return textures(code).filterNot { normalized(it) in held }
    }

    private fun normalized(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }
}
