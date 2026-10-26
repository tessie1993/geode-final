#version 300 es
precision highp float;
// uScene holds HDR colour well above lowp's [-2, 2) range; see composite_frag.glsl.
precision highp sampler2D;

// Depth of field (core/viz/DofPass, LensFx.hpp), in one pass over the active Style's scene target,
// before the Shared core effects. A shader Style that declares "depthOfField" writes its depth into
// the scene target's alpha (lens::depthAlpha: t / (t + subject distance), 1 for "far"); each texel's
// circle of confusion is the aperture times |1/depth - 1/focus| times focus, as a share of
// uMaxRadius (lens::circleOfConfusion, which the host tests check).
//
// Each pixel gathers uSamples taps on a golden-angle spiral, turned per pixel so the pattern becomes
// fine grain. A tap counts where its own circle covers the distance to it, so a blurred foreground
// spreads over what is behind it; a tap behind the pixel may spread no further than twice the pixel's
// own circle, so a sharp subject keeps its edge against a blurred background. The depth is used up
// here: what this pass writes has alpha 1, as every other target does.

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uScene;
uniform vec2 uTexel;
uniform float uAperture;
uniform float uFocus;
// The largest circle of confusion, in scene-target pixels.
uniform float uMaxRadius;
uniform float uSamples;

const float kHdrCeiling = 64.0;
const int kMaxSamples = 24;
const float kGoldenAngle = 2.39996323;
const float kTau = 6.2831853;
// The nearest depth worked out (lens::kMinDepth).
const float kMinDepth = 0.02;

vec3 sanitize(vec3 c) {
    return any(isnan(c)) ? vec3(0.0) : clamp(c, 0.0, kHdrCeiling);
}

float depthOf(float alpha) {
    return isnan(alpha) ? 1.0 : clamp(alpha, kMinDepth, 1.0);
}

// The circle of confusion in pixels.
float circle(float depth) {
    float share = uAperture * abs(1.0 / depth - 1.0 / uFocus) * uFocus;
    return clamp(share, 0.0, 1.0) * uMaxRadius;
}

float spin(vec2 pixel) {
    return fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))));
}

void main() {
    vec4 centre = texture(uScene, vUv);
    float depth = depthOf(centre.a);
    float own = circle(depth);
    // The spiral reaches halfway out for a sharp pixel (so a blurred foreground can still reach over
    // it) and all the way for the most blurred.
    float reach = mix(uMaxRadius * 0.5, uMaxRadius, own / max(uMaxRadius, 1e-4));
    vec3 sum = sanitize(centre.rgb);
    float total = 1.0;
    float turn = spin(gl_FragCoord.xy) * kTau;
    float samples = max(uSamples, 1.0);
    for (int i = 0; i < kMaxSamples; ++i) {
        if (float(i) >= uSamples) break;
        float k = float(i) + 0.5;
        float r = reach * sqrt(k / samples);
        float angle = k * kGoldenAngle + turn;
        vec4 tap = texture(uScene, vUv + vec2(cos(angle), sin(angle)) * r * uTexel);
        float tapDepth = depthOf(tap.a);
        float spread = circle(tapDepth);
        if (tapDepth > depth) spread = min(spread, own * 2.0);
        float weight = clamp(spread - r + 1.0, 0.0, 1.0);
        sum += sanitize(tap.rgb) * weight;
        total += weight;
    }
    fragColor = vec4(sum / total, 1.0);
}
