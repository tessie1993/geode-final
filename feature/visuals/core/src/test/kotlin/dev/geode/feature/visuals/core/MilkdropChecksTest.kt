package dev.geode.feature.visuals.core

import dev.geode.feature.visuals.api.PresetPlayback
import org.junit.Assert.assertEquals
import org.junit.Test

/** What reading a preset says about how it will play (#305): its shaders, and the textures it needs. */
class MilkdropChecksTest {
    /** A preset whose warp and composite shaders are [warp] and [comp], one line each, beside some equations. */
    private fun preset(
        warp: String = "ret = tex2D(sampler_main, uv).xyz;",
        comp: String = "ret = tex2D(sampler_main, uv).xyz;",
    ): String =
        """
        MILKDROP_PRESET_VERSION=201
        PSVERSION=2
        [preset00]
        fDecay=0.98
        per_frame_1=zoom = 1.01 + 0.02*sin(time);
        warp_1=`shader_body
        warp_2=`{
        warp_3=`    $warp
        warp_4=`}
        comp_1=`shader_body
        comp_2=`{
        comp_3=`    $comp
        comp_4=`}
        """.trimIndent()

    @Test
    fun `a preset with plain shaders and no textures plays fully`() {
        assertEquals(PresetPlayback.Full, MilkdropChecks.playback(preset(), emptyList()))
    }

    @Test
    fun `a preset with no shaders at all plays fully`() {
        assertEquals(PresetPlayback.Full, MilkdropChecks.playback("[preset00]\nfDecay=0.9\nper_frame_1=q1=bass;\n", emptyList()))
    }

    @Test
    fun `the shader code is the warp and comp lines, in order, without comments`() {
        val text =
            """
            [preset00]
            per_frame_1=tanh(1);
            warp_1=`shader_body { // tanh(x) is in a comment
            WARP_2=float3 a = 1; /* get_fft(0.5)
            comp_1=still in the block comment */ float3 b = 2;
            comp_2= ret = a;
            """.trimIndent()

        assertEquals("shader_body { \nfloat3 a = 1;   float3 b = 2;\n ret = a;", MilkdropChecks.shaderCode(text))
        assertEquals(emptyList<String>(), MilkdropChecks.unsupported(MilkdropChecks.shaderCode(text)))
    }

    @Test
    fun `shader functions our projectM cannot translate drop the shader`() {
        val playback = MilkdropChecks.playback(preset(warp = "ret = lit(frexp(uv.x, e), 0, 1).xyz;"), emptyList())

        assertEquals(PresetPlayback.WithoutShader(listOf("frexp", "lit")), playback)
    }

    @Test
    fun `the extras our projectM translates since #306 play fully`() {
        val warp =
            "ret = tanh(get_fft(uv.x)) * sinh(get_wave_left(uv.y)) + cosh(get_fft_peak_hz(440.0)) * mouse.x + " +
                "trunc(radians(degrees(sysTime.x))) + aspect_sd.x + mouse_x + sysDateWeekday + tex2D(sampler_fft, uv).x;"

        assertEquals(PresetPlayback.Full, MilkdropChecks.playback(preset(warp = warp), emptyList()))
    }

    @Test
    fun `a call is only a call with its bracket, and only a whole word`() {
        assertEquals(listOf("frexp"), MilkdropChecks.unsupported("x = frexp (440.0, e);"))
        assertEquals(emptyList<String>(), MilkdropChecks.unsupported("float frexpish = 1; float my_frexp = 2; float all = 3;"))
        assertEquals(listOf("all"), MilkdropChecks.unsupported("if (all(x)) ret = 1;"))
        assertEquals(listOf("tex1D"), MilkdropChecks.unsupported("ret = tex1D(sampler_main, uv.x) + tex1DSomething;"))
    }

    @Test
    fun `every call it lists is found`() {
        for (call in MilkdropChecks.UNSUPPORTED_CALLS) {
            assertEquals(call, listOf(call), MilkdropChecks.unsupported("ret = $call(uv);"))
        }
    }

    @Test
    fun `the textures a shader samples, once each, without projectM's own`() {
        val code =
            "ret = tex2D(sampler_worms, uv) + tex2D(sampler_fw_Clouds, uv) + tex2D(sampler_pc_worms, uv) + " +
                "tex2D(sampler_main, uv) + tex2D(sampler_blur1, uv) + tex2D(sampler_noise_lq, uv) + " +
                "tex3D(sampler_noisevol_hq, uv) + tex2D(sampler_rand03, uv) + tex2D(sampler_rand12_smalltiled, uv) + " +
                "tex2D(sampler_fft, uv) + tex2D(sampler_wave, uv) + tex2D(sampler_CF_cells, uv) + texsize_worms.x;"

        assertEquals(listOf("worms", "Clouds", "cells"), MilkdropChecks.textures(code))
    }

    @Test
    fun `a texture is there under its own name, any case, or under the name an import renamed it to`() {
        val code = "ret = tex2D(sampler_worms, uv) + tex2D(sampler_Old_Map, uv) + tex2D(sampler_cells, uv) + tex2D(sampler_sky, uv);"
        val held = listOf("WORMS.png", "Old_Map_1a2b3c4d.jpg", "cells_extra.png", "skyline.tga")

        assertEquals(listOf("cells", "sky"), MilkdropChecks.missingTextures(code, held))
        assertEquals(listOf("worms", "Old_Map", "cells", "sky"), MilkdropChecks.missingTextures(code, emptyList()))
    }

    @Test
    fun `missing textures are named`() {
        val comp = "ret = tex2D(sampler_worms, uv) * tex2D(sampler_lichen, uv);"
        val playback = MilkdropChecks.playback(preset(comp = comp), listOf("worms.png"))

        assertEquals(PresetPlayback.MissingTextures(listOf("lichen")), playback)
        assertEquals(
            PresetPlayback.Full,
            MilkdropChecks.playback(preset(comp = "ret = tex2D(sampler_worms, uv);"), listOf("lichen.jpg", "worms.png")),
        )
    }

    @Test
    fun `a shader that cannot run says so, whatever textures it is missing`() {
        val playback = MilkdropChecks.playback(preset(warp = "ret = tex2D(sampler_worms, uv) * determinant(m);"), emptyList())

        assertEquals(PresetPlayback.WithoutShader(listOf("determinant")), playback)
    }
}
