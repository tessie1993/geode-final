#version 300 es
precision highp float;
precision highp sampler2D;

// The No-flash limiter's measurement (core/viz/NoFlashPass, FlashLimiter). Drawn into a
// uGrid-sized target, one fragment per grid cell: each averages a kTaps x kTaps lattice of
// bilinear samples over its cell of the finished frame, in linear light, and writes
//   r = mean saturated-red value (R - G - B where R is at least 0.8 of R + G + B, else 0),
//   g = mean relative luminance (WCAG),
// which the CPU reads back (16 texels) and hands to the limiter. Mirrors noflash:: in
// FlashLimiter.cpp, which the host tests check.

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uFrame;
uniform vec2 uGrid;

const int kTaps = 16;
const float kSaturatedRedShare = 0.8;
const vec3 kLuma = vec3(0.2126, 0.7152, 0.0722);

vec3 toLinear(vec3 c) {
    vec3 lo = c / 12.92;
    vec3 hi = pow((c + 0.055) / 1.055, vec3(2.4));
    return mix(hi, lo, vec3(lessThanEqual(c, vec3(0.04045))));
}

void main() {
    vec2 cell = floor(gl_FragCoord.xy);
    vec2 cellSize = 1.0 / uGrid;
    float luminance = 0.0;
    float red = 0.0;
    for (int y = 0; y < kTaps; ++y) {
        for (int x = 0; x < kTaps; ++x) {
            vec2 uv = (cell + (vec2(float(x), float(y)) + 0.5) / float(kTaps)) * cellSize;
            vec3 l = toLinear(clamp(textureLod(uFrame, uv, 0.0).rgb, 0.0, 1.0));
            luminance += dot(l, kLuma);
            float sum = l.r + l.g + l.b;
            red += (sum > 0.0 && l.r >= kSaturatedRedShare * sum) ? max(l.r - l.g - l.b, 0.0) : 0.0;
        }
    }
    float taps = float(kTaps * kTaps);
    fragColor = vec4(red / taps, luminance / taps, 0.0, 1.0);
}
