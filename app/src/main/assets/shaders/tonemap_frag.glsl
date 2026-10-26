#version 300 es
precision highp float;
// uScene holds HDR colour well above lowp's [-2, 2) range; see composite_frag.glsl.
precision highp sampler2D;

// The frame's one final pass (core/viz/HdrPass, ToneMap.hpp): the HDR composite, plus the bloom in
// linear light, through the highlight roll-off, then the blue-noise dither, into the 8-bit target
// the No-flash limiter measures and limits. Every Style's colour is a display-encoded value (1 is
// the display's white) and stays exactly as the Style made it up to that white; only the highlights
// above it roll off: the brightest channel through a smooth shoulder toward uCeiling, the colour
// bleaching toward white as far as it went, instead of clipping. Mirrors tone::toDisplay in
// ToneMap.cpp, which the host tests check.

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uScene;
// Bloom level 0, linear light; a 1x1 black texture and uBloomStrength 0 while bloom is off. Its rgb
// is the light above the chain's threshold and its alpha the luminance of the bloom's part of it
// (bloom_down_frag.glsl): the bloom is rgb scaled by that share (lens::bloomShare in LensFx.cpp).
uniform sampler2D uBloom;
uniform float uBloomStrength;
// The lens effects' light (flare ghosts, god rays, streaks: lens_frag.glsl), linear, at quarter size;
// a 1x1 black texture and uLensStrength 0 while none is on.
uniform sampler2D uLens;
uniform float uLensStrength;
// The shoulder's ceiling (ToneMap.hpp, kShoulderCeiling).
uniform float uCeiling;
// Blue-noise dither mask, sampled 1:1 with output pixels (see composite_frag.glsl). It is applied
// here, last, because this is the pass that writes the 8-bit surface whose steps it hides.
uniform sampler2D uNoise;
uniform float uDither;

const float kHdrCeiling = 64.0;
const vec3 kLuma = vec3(0.2126, 0.7152, 0.0722);
const float kShareEpsilon = 1e-4;

vec3 sanitize(vec3 c) {
    return any(isnan(c)) ? vec3(0.0) : clamp(c, 0.0, kHdrCeiling);
}

vec3 decode(vec3 c) {
    vec3 lo = c / 12.92;
    vec3 hi = pow((c + 0.055) / 1.055, vec3(2.4));
    return mix(hi, lo, vec3(lessThanEqual(c, vec3(0.04045))));
}

vec3 encode(vec3 l) {
    vec3 lo = l * 12.92;
    vec3 hi = 1.055 * pow(l, vec3(1.0 / 2.4)) - 0.055;
    return mix(hi, lo, vec3(lessThanEqual(l, vec3(0.0031308))));
}

vec3 rollOff(vec3 c) {
    float peak = max(c.r, max(c.g, c.b));
    if (peak <= 1.0) return c;
    float span = uCeiling - 1.0;
    float compressed = 1.0 + span * (1.0 - exp(-(peak - 1.0) / span));
    float bleach = clamp((compressed - 1.0) / span, 0.0, 1.0);
    vec3 hue = c / peak;
    return hue + (vec3(1.0) - hue) * bleach;
}

float bloomShare(float chainLuma, float bloomLuma) {
    if (!(chainLuma > kShareEpsilon)) return 0.0;
    return clamp(bloomLuma / chainLuma, 0.0, 1.0);
}

void main() {
    vec3 c = sanitize(texture(uScene, vUv).rgb);
    if (uBloomStrength > 0.0 || uLensStrength > 0.0) {
        vec4 chain = texture(uBloom, vUv);
        vec3 chainLight = sanitize(chain.rgb);
        float share = bloomShare(dot(chainLight, kLuma), isnan(chain.a) ? 0.0 : chain.a);
        vec3 light = chainLight * share * uBloomStrength;
        light += sanitize(texture(uLens, vUv).rgb) * uLensStrength;
        c = encode(decode(c) + light);
    }
    vec3 col = clamp(rollOff(c), 0.0, 1.0);
    col += (texture(uNoise, gl_FragCoord.xy / 64.0).r - 0.5) * uDither;
    fragColor = vec4(col, 1.0);
}
