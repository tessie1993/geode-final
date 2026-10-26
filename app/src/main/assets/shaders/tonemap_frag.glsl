#version 300 es
precision highp float;
// uScene holds HDR colour well above lowp's [-2, 2) range; see composite_frag.glsl.
precision highp sampler2D;

// The frame's one final pass (core/viz/HdrPass, ToneMap.hpp): the HDR composite, plus the bloom in
// linear light, through the fitted ACES filmic curve, then the blue-noise dither, into the 8-bit
// target the No-flash limiter measures and limits. Every Style's colour is a display-encoded value
// (1 is the display's white), so the curve runs on the encoded value: there its toe and middle stay
// close to what each Style was tuned to, and its shoulder rolls the headroom above 1 off to white
// instead of clipping it. Mirrors tone:: in ToneMap.cpp, which the host tests check.

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uScene;
// Bloom level 0, linear light; a 1x1 black texture and uBloomStrength 0 while bloom is off.
uniform sampler2D uBloom;
uniform float uBloomStrength;
uniform float uExposure;
uniform float uWhite;
// Blue-noise dither mask, sampled 1:1 with output pixels (see composite_frag.glsl). It is applied
// here, last, because this is the pass that writes the 8-bit surface whose steps it hides.
uniform sampler2D uNoise;
uniform float uDither;

const float kHdrCeiling = 64.0;

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

vec3 aces(vec3 x) {
    return x * (2.51 * x + 0.03) / (x * (2.43 * x + 0.59) + 0.14);
}

void main() {
    vec3 c = sanitize(texture(uScene, vUv).rgb);
    if (uBloomStrength > 0.0) {
        c = encode(decode(c) + sanitize(texture(uBloom, vUv).rgb) * uBloomStrength);
    }
    float white = uWhite * uExposure;
    vec3 col = clamp(aces(c * uExposure) / aces(vec3(white)), 0.0, 1.0);
    col += (texture(uNoise, gl_FragCoord.xy / 64.0).r - 0.5) * uDither;
    fragColor = vec4(col, 1.0);
}
