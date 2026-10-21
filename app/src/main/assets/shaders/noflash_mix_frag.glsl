#version 300 es
precision highp float;
precision highp sampler2D;

// The No-flash limiter's output (core/viz/NoFlashPass): the frame to show. With uAmount = 1 it
// is the new frame as it is; below 1 the frame shown before is crossfaded toward the new one in
// LINEAR light, so every measured area's mean luminance moves by exactly uAmount of its change,
// which is what FlashLimiter assumes when it picks uAmount.

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uShown;
uniform sampler2D uNext;
uniform float uAmount;

vec3 toLinear(vec3 c) {
    vec3 lo = c / 12.92;
    vec3 hi = pow((c + 0.055) / 1.055, vec3(2.4));
    return mix(hi, lo, vec3(lessThanEqual(c, vec3(0.04045))));
}

vec3 toEncoded(vec3 l) {
    vec3 lo = l * 12.92;
    vec3 hi = 1.055 * pow(l, vec3(1.0 / 2.4)) - 0.055;
    return mix(hi, lo, vec3(lessThanEqual(l, vec3(0.0031308))));
}

void main() {
    vec3 next = texture(uNext, vUv).rgb;
    if (uAmount >= 1.0) {
        fragColor = vec4(next, 1.0);
        return;
    }
    vec3 shown = toLinear(clamp(texture(uShown, vUv).rgb, 0.0, 1.0));
    vec3 l = mix(shown, toLinear(clamp(next, 0.0, 1.0)), clamp(uAmount, 0.0, 1.0));
    fragColor = vec4(toEncoded(l), 1.0);
}
