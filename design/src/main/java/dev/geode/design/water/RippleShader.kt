package dev.geode.design.water

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.asComposeRenderEffect

/** Makes the render effect that draws a [RippleField] on a layer. */
internal fun interface RippleEffectSource {
    /** The effect for [field] at [nowNanos], or null when nothing ripples (the layer stays cached). */
    fun effect(
        field: RippleField,
        nowNanos: Long,
    ): androidx.compose.ui.graphics.RenderEffect?
}

/**
 * The AGSL water ripple of Android 13 and later. It displaces the glass it runs on along each
 * ring (the same banded sine as [RipplePhysics.displacement]) and lights the crest faintly. There is
 * no colour fringing and no tint, so text under it bends a little and stays legible.
 * Each of the [RippleField.MAX_RIPPLES] slots is (x, y, wavefront radius, amplitude); a zero
 * amplitude leaves a slot still.
 */
@RequiresApi(RippleTier.SHADER_MIN_SDK)
internal class RippleShader(
    private val spec: RippleSpec,
    private val crestLight: Float,
) : RippleEffectSource {
    private val shader = RuntimeShader(SOURCE)

    override fun effect(
        field: RippleField,
        nowNanos: Long,
    ): androidx.compose.ui.graphics.RenderEffect? {
        if (field.isIdle) return null
        SLOTS.forEachIndexed { index, slot ->
            val ripple = field.ripples.getOrNull(index)
            if (ripple == null) {
                shader.setFloatUniform(slot, 0f, 0f, 0f, 0f)
            } else {
                val frame = RipplePhysics.frame(ripple, nowNanos, spec)
                shader.setFloatUniform(slot, ripple.x, ripple.y, frame.radiusPx, frame.amplitudePx)
            }
        }
        shader.setFloatUniform("wavelength", spec.wavelengthPx)
        shader.setFloatUniform("band", spec.bandPx)
        shader.setFloatUniform("crestLight", crestLight)
        shader.setFloatUniform("peak", spec.peakAmplitudePx.coerceAtLeast(MIN_PEAK_PX))
        return RenderEffect.createRuntimeShaderEffect(shader, CONTENT).asComposeRenderEffect()
    }

    private companion object {
        const val CONTENT = "content"

        /** Keeps the crest light defined for a spec with no displacement. */
        const val MIN_PEAK_PX = 0.001f
        val SLOTS = listOf("ripple0", "ripple1")

        val SOURCE =
            """
            uniform shader content;
            uniform float4 ripple0;
            uniform float4 ripple1;
            uniform float wavelength;
            uniform float band;
            uniform float crestLight;
            uniform float peak;

            const float TWO_PI = 6.28318530718;

            // The displacement along the ring of one ripple at p, and how high its crest stands there.
            float3 ring(float2 p, float4 r) {
                float2 d = p - r.xy;
                float dist = length(d);
                if (r.w <= 0.0 || dist < 0.001) return float3(0.0);
                float offset = dist - r.z;
                float envelope = exp(-(offset * offset) / (band * band));
                float wave = sin(TWO_PI * offset / wavelength) * envelope;
                return float3(d / dist * wave * r.w, max(wave, 0.0) * r.w / peak);
            }

            half4 main(float2 p) {
                float3 a = ring(p, ripple0);
                float3 b = ring(p, ripple1);
                half4 c = content.eval(p + a.xy + b.xy);
                float crest = clamp(a.z + b.z, 0.0, 1.0) * crestLight;
                return half4(c.rgb + crest * c.a, c.a);
            }
            """.trimIndent()
    }
}
