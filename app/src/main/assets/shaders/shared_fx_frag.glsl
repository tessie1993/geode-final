#version 300 es
// The Shared core effects that work on every Style's finished picture before the composite pass
// (SharedFxPass, SharedEffects.hpp): soft focus, the particle layer, motion blur and Trails/echo.
// Every amount is 0 by default, and the renderer skips this pass entirely while all of them are.
// The rest of the Shared core is in composite_frag.glsl.
precision highp float;
precision highp sampler2D;

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uScene;
// What this pass drew last frame; only read while uEchoKeep or uMotionKeep is above 0.
uniform sampler2D uHistory;
uniform vec2 uTexel;
uniform float uAspect;
uniform float uTime;
uniform float uSoftFocus;
uniform float uParticles;
uniform float uParticleKind;
uniform float uParticleSize;
// How much of the last frame an echo keeps (frame-rate corrected on the CPU: sharedfx::echoKeep).
uniform float uEchoKeep;
// The echo's zoom and warp this frame (sharedfx::echoZoom and echoWarp: the 60 fps amounts, scaled by dt).
uniform float uEchoZoom;
uniform float uEchoWarp;
// How much of the last frame motion blur mixes in (sharedfx::motionKeep).
uniform float uMotionKeep;

float fxHash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

vec2 fxHash22(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.xx + p3.yz) * p3.zy);
}

// Soft focus: a 3x3 tent blur whose reach grows with the amount, mixed over the sharp picture.
vec3 softFocus(vec2 uv) {
    vec3 c = texture(uScene, uv).rgb;
    if (uSoftFocus <= 0.001) return c;
    vec2 r = uTexel * (1.0 + 5.0 * uSoftFocus);
    vec3 blur = c * 0.25;
    blur += 0.125 * (texture(uScene, uv + vec2(r.x, 0.0)).rgb + texture(uScene, uv - vec2(r.x, 0.0)).rgb +
                     texture(uScene, uv + vec2(0.0, r.y)).rgb + texture(uScene, uv - vec2(0.0, r.y)).rgb);
    blur += 0.0625 * (texture(uScene, uv + r).rgb + texture(uScene, uv - r).rgb +
                      texture(uScene, uv + vec2(r.x, -r.y)).rgb + texture(uScene, uv + vec2(-r.x, r.y)).rgb);
    return mix(c, blur, clamp(uSoftFocus, 0.0, 1.0) * 0.85);
}

// One particle's light at offset d from its centre, radius r: 0 motes, 1 sparks, 2 dust, 3 bubbles.
float particleShape(vec2 d, float r, int kind) {
    if (kind == 1) return exp(-(d.x * d.x) / (r * r * 0.06) - (d.y * d.y) / (r * r * 2.5));
    if (kind == 2) return 0.6 * smoothstep(r * 0.35, 0.0, length(d));
    if (kind == 3) {
        float ring = (length(d) - r) / (r * 0.2);
        return 0.7 * exp(-ring * ring) + 0.1 * smoothstep(r, 0.0, length(d));
    }
    return 0.65 * exp(-dot(d, d) / (r * r));
}

// The shared particle layer: two layers of hashed cells, a share of them (the amount) holding one
// particle that drifts the way its kind does and twinkles slowly.
float particleLight(vec2 uv) {
    int kind = int(uParticleKind + 0.5);
    float size = clamp(uParticleSize, 0.3, 2.5);
    vec2 p = vec2(uv.x * uAspect, uv.y);
    vec2 drift = kind == 1 ? vec2(0.02, 0.25) : kind == 3 ? vec2(0.0, 0.08) : vec2(0.015, 0.03);
    float light = 0.0;
    for (int layer = 0; layer < 2; layer++) {
        float l = float(layer);
        float cells = (12.0 + 8.0 * l) / size;
        vec2 q = (p - drift * uTime * (1.0 + 0.6 * l)) * cells;
        vec2 id = floor(q);
        vec2 f = fract(q);
        float seed = fxHash12(id + 17.0 * l);
        if (seed < 1.0 - clamp(uParticles, 0.0, 1.0)) continue;
        vec2 pos = 0.25 + 0.5 * fxHash22(id + 3.1 + l);
        pos += 0.12 * vec2(sin(uTime * 0.7 + seed * 6.2831), cos(uTime * 0.5 + seed * 4.0));
        float twinkle = 0.65 + 0.35 * sin(uTime * 2.0 + seed * 6.2831);
        light += particleShape(f - pos, 0.12, kind) * twinkle;
    }
    return light;
}

void main() {
    vec3 col = softFocus(vUv);
    if (uParticles > 0.001) {
        vec3 tint = mix(vec3(1.0, 0.97, 0.9), clamp(col + 0.25, 0.0, 1.0), 0.4);
        col += particleLight(vUv) * (0.35 + 0.4 * uParticles) * tint;
    }
    if (uEchoKeep > 0.001 || uMotionKeep > 0.001) {
        // The echo resamples the last frame slightly zoomed and warped, as trail_warp_frag does.
        vec2 c = (vUv - 0.5) * (1.0 - uEchoZoom * 0.06);
        vec2 h = c + 0.5;
        if (uEchoWarp > 0.001) {
            h += vec2(sin(h.y * 11.0 + uTime * 0.7), sin(h.x * 13.0 - uTime * 0.9)) * uEchoWarp * 0.004;
        }
        vec3 prev = texture(uHistory, clamp(h, vec2(0.001), vec2(0.999))).rgb;
        col = mix(col, prev, clamp(uMotionKeep, 0.0, 0.95));
        // An echo only ever adds what is fading behind the new frame; it never darkens it.
        col = max(col, prev * clamp(uEchoKeep, 0.0, 0.98));
    }
    fragColor = vec4(col, 1.0);
}
