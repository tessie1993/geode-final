#version 300 es
precision highp float;
// The bloom's light is far above lowp's [-2, 2) range; see composite_frag.glsl.
precision highp sampler2D;

// The bloom's downsample (core/viz/HdrPass, ToneMap.hpp): one level of the dual-filter mip chain,
// the centre texel four times and four diagonal taps one source texel out, so each level blurs as
// it halves. The first level (uPrefilter 1) reads the HDR composite: it sanitises it, decodes it to
// linear light and keeps only the light above a soft threshold (uCurve: threshold, knee), and
// weights each tap by 1 / (1 + its luminance) so a single blazing pixel cannot flicker the glow on
// and off as it moves between texels. Mirrors tone:: in ToneMap.cpp, which the host tests check.

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uSource;
uniform vec2 uTexel;
uniform int uPrefilter;
uniform vec2 uCurve;

const float kHdrCeiling = 64.0;
const vec3 kLuma = vec3(0.2126, 0.7152, 0.0722);

vec3 sanitize(vec3 c) {
    return any(isnan(c)) ? vec3(0.0) : clamp(c, 0.0, kHdrCeiling);
}

// The sRGB transfer, extended above 1 by the same power.
vec3 decode(vec3 c) {
    vec3 lo = c / 12.92;
    vec3 hi = pow((c + 0.055) / 1.055, vec3(2.4));
    return mix(hi, lo, vec3(lessThanEqual(c, vec3(0.04045))));
}

float contribution(float b) {
    float knee = uCurve.y;
    if (knee <= 0.0) return max(b - uCurve.x, 0.0);
    float soft = clamp(b - uCurve.x + knee, 0.0, 2.0 * knee);
    return max(soft * soft / (4.0 * knee), b - uCurve.x);
}

vec3 light(vec2 uv) {
    vec3 c = decode(sanitize(texture(uSource, uv).rgb));
    float b = max(c.r, max(c.g, c.b));
    return c * (contribution(b) / max(b, 0.0001));
}

void main() {
    vec2 d = uTexel;
    vec2 taps[4] = vec2[4](vec2(-d.x, -d.y), vec2(d.x, -d.y), vec2(-d.x, d.y), vec2(d.x, d.y));
    if (uPrefilter == 1) {
        vec3 c = light(vUv);
        float w = 4.0 / (1.0 + dot(c, kLuma));
        vec3 sum = c * w;
        float weight = w;
        for (int i = 0; i < 4; ++i) {
            vec3 t = light(vUv + taps[i]);
            float tw = 1.0 / (1.0 + dot(t, kLuma));
            sum += t * tw;
            weight += tw;
        }
        fragColor = vec4(sum / weight, 1.0);
        return;
    }
    vec3 sum = texture(uSource, vUv).rgb * 4.0;
    for (int i = 0; i < 4; ++i) sum += texture(uSource, vUv + taps[i]).rgb;
    fragColor = vec4(sum / 8.0, 1.0);
}
