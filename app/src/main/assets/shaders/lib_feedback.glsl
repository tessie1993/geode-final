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
//
// Before it settles, fbSettle() paints what the Style's manifest asks the loop for (fbPaint): the outer
// and inner borders (#363) and the streaks along the motion field (#386). Each is a share of a colour
// (uFbOuter.a, uFbInner.a, uFbStreak.a: this frame's share, eased in over the visit) laid over the
// picture, so what is painted is kept and decays like anything the Style draws; the zoom carries the
// borders' copies into a tunnel, and the motion draws each streak out into a trail. Every share is 0
// for a Style that asks for none, and the picture is then exactly what the stage handed over.

uniform highp sampler2D uFbPrev;
uniform vec2 uFbTexel;
uniform float uFbDt;
uniform vec3 uFbKeep;
uniform float uFbFloor;
uniform float uFbHalf;
uniform float uFbStep;
uniform float uFbSeed;
// The borders: rgb and this frame's share of it, and the band's width in frame heights.
uniform vec4 uFbOuter;
uniform float uFbOuterSize;
uniform vec4 uFbInner;
uniform float uFbInnerSize;
// The streaks: rgb and this frame's share of it, and one cell of their grid in frame heights.
uniform vec4 uFbStreak;
uniform float uFbStreakCell;

in vec2 vUv;
in vec2 vSrc;

const float FB_CEILING = 4.0;
const float FB_HALF_MIN = 6.103515625e-05;
const float FB_HALF_MANTISSA = 10.0;
// The streaks show where the picture moves faster than FB_STREAK_STILL frame heights a second, and are
// at their full length, FB_STREAK_REACH of a cell either side of its centre, from FB_STREAK_FULL.
const float FB_STREAK_STILL = 0.01;
const float FB_STREAK_FULL = 0.15;
const float FB_STREAK_REACH = 0.42;

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

/** 1 inside the band from `from` to `to` frame heights in from the edge, softened over a texel. */
float fbBand(float edge, float from, float to) {
    float h = 0.5 * uFbTexel.y;
    return clamp(smoothstep(from - h, from + h, edge) - smoothstep(to - h, to + h, edge), 0.0, 1.0);
}

/**
 * How much of this point's cell's streak covers it: a line through the cell's centre (`p`, in frame
 * heights) along the way the picture moves here, the motion grid's own (vUv - vSrc over the frame),
 * longer the faster it moves and gone where it is still. The field is smooth, so the motion here stands
 * for the motion at the centre.
 */
float fbStreak(vec2 p, vec2 frame) {
    if (uFbDt <= 0.0) return 0.0;
    vec2 motion = (vUv - vSrc) * frame / uFbDt;
    float speed = length(motion);
    float shown = smoothstep(FB_STREAK_STILL, FB_STREAK_FULL, speed);
    if (shown <= 0.0) return 0.0;
    vec2 along = motion / speed;
    vec2 d = p - (floor(p / uFbStreakCell) + 0.5) * uFbStreakCell;
    float reach = FB_STREAK_REACH * uFbStreakCell * shown;
    float off = length(d - along * clamp(dot(d, along), -reach, reach));
    return shown * (1.0 - smoothstep(0.5 * uFbTexel.y, 1.5 * uFbTexel.y, off));
}

/** `c` with what the manifest paints into the loop laid over it: the borders and the streaks. */
vec3 fbPaint(vec3 c) {
    if (uFbOuter.a <= 0.0 && uFbInner.a <= 0.0 && uFbStreak.a <= 0.0) return c;
    vec2 frame = vec2(uFbTexel.y / uFbTexel.x, 1.0);
    vec2 p = vUv * frame;
    if (uFbStreak.a > 0.0) c = mix(c, uFbStreak.rgb, uFbStreak.a * fbStreak(p, frame));
    // How far this point is in from the nearest edge, in frame heights.
    float edge = min(min(p.x, frame.x - p.x), min(p.y, 1.0 - p.y));
    c = mix(c, uFbOuter.rgb, uFbOuter.a * fbBand(edge, -1.0, uFbOuterSize));
    c = mix(c, uFbInner.rgb, uFbInner.a * fbBand(edge, uFbOuterSize, uFbOuterSize + uFbInnerSize));
    return c;
}

/** What the warp stage keeps of `c`: painted, decayed, tinted, floored and rounded without a stall. */
vec3 fbSettle(vec3 c) {
    c = fbPaint(c);
    float n = fbNoise(gl_FragCoord.xy);
    return vec3(fbSettleChannel(c.r, uFbKeep.r, n), fbSettleChannel(c.g, uFbKeep.g, fract(n + 0.381966)),
                fbSettleChannel(c.b, uFbKeep.b, fract(n + 0.618034)));
}

/** The shared warp: the kept picture read where the motion grid says, and settled. */
vec4 fbWarp() {
    return vec4(fbSettle(fbFetch(vSrc)), 1.0);
}
