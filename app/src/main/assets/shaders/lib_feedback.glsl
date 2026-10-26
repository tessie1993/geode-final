// The feedback warp's store (core/viz FeedbackWarp.hpp, docs/style-packs.md "feedback"): what a warp
// stage reads and how it writes. The shared stage (feedback_warp_frag.glsl) is fbWarp(); a Style's own
// warp stage (its manifest's "feedback" "shader") includes this, reads the kept picture with fbFetch()
// wherever it likes around vSrc and hands what it keeps to fbSettle(), which must be the last thing it
// does to the colour.
//
//   vSrc        where the motion grid says this point of the warped picture reads the kept one from
//   vUv         this point, 0..1 from the bottom left
//   uFbPrev     the picture the Style kept last frame (half float where the device renders it)
//   uFbTexel    one texel of it
//   uFbDt       the frame's seconds: scale anything a stage moves per frame by it
//
// fbSettle() keeps uFbKeep of each channel, takes uFbFloor off, and rounds stochastically: a dither of
// one quantum of the target's format (uFbHalf: half float; else uFbStep, one level of RGB10_A2 or
// RGBA8) keeps the expected value on the exact decay, where plain rounding stalls a slow decay into a
// ghost that never fades. It mirrors feedback::settle(), which the host tests run at every precision.
// The loop must only shrink (the No-flash rule): a stage may move, blur and recolour the picture, never
// brighten it, and fbSettle() holds it under FB_CEILING whatever it is handed.

uniform highp sampler2D uFbPrev;
uniform vec2 uFbTexel;
uniform float uFbDt;
uniform vec3 uFbKeep;
uniform float uFbFloor;
uniform float uFbHalf;
uniform float uFbStep;
uniform float uFbSeed;

in vec2 vUv;
in vec2 vSrc;

const float FB_CEILING = 4.0;
const float FB_HALF_MIN = 6.103515625e-05;
const float FB_HALF_MANTISSA = 10.0;

/** The kept picture at `uv`, fading to black over a texel outside the frame, so nothing smears in from the edge. */
vec3 fbFetch(vec2 uv) {
    vec2 inside = clamp(min(uv, 1.0 - uv) / uFbTexel + 0.5, 0.0, 1.0);
    return texture(uFbPrev, uv).rgb * (inside.x * inside.y);
}

/** Uniform in [0, 1), fresh for every pixel and every frame (uFbSeed counts the frames). */
float fbNoise(vec2 pixel) {
    uvec2 q = uvec2(ivec2(pixel));
    uint h = q.x * 0x8da6b343u ^ q.y * 0xd8163841u ^ uint(uFbSeed) * 0xcb1ab31fu;
    h ^= h >> 16;
    h *= 0x7feb352du;
    h ^= h >> 15;
    h *= 0x846ca68bu;
    h ^= h >> 16;
    return float(h >> 8) * (1.0 / 16777216.0);
}

/** The spacing of the values the target stores around `v`: feedback::quantum(). */
float fbQuantum(float v) {
    if (uFbHalf > 0.5) return exp2(floor(log2(max(v, FB_HALF_MIN))) - FB_HALF_MANTISSA);
    return uFbStep;
}

/** One channel kept, floored and dithered: feedback::settle(). */
float fbSettleChannel(float v, float keep, float noise) {
    float left = (isnan(v) ? 0.0 : clamp(v, 0.0, FB_CEILING)) * keep - uFbFloor;
    if (!(left > 0.0)) return 0.0;
    return max(left + (noise - 0.5) * fbQuantum(left), 0.0);
}

/** What the warp stage keeps of `c`: decayed, tinted, floored and rounded without a stall. */
vec3 fbSettle(vec3 c) {
    float n = fbNoise(gl_FragCoord.xy);
    return vec3(fbSettleChannel(c.r, uFbKeep.r, n), fbSettleChannel(c.g, uFbKeep.g, fract(n + 0.381966)),
                fbSettleChannel(c.b, uFbKeep.b, fract(n + 0.618034)));
}

/** The shared warp: the kept picture read where the motion grid says, and settled. */
vec4 fbWarp() {
    return vec4(fbSettle(fbFetch(vSrc)), 1.0);
}
