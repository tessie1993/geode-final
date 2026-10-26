#version 300 es
precision highp float;
precision highp sampler2D;

in vec2 vUv;
out vec4 fragColor;

//#include lib_scene_uniforms
//#include lib_scene_motion
//#include lib_palette
//#include lib_scene_grade
//#include lib_sdf3
//#include lib_touch
//#include lib_style
//#include lib_camera

// Frozen Moment: bullet time. A splash bursts, time all but stops, and the
// camera walks around the frozen droplets before time lets go again.
//
// TWO CLOCKS. The camera runs on real time; the world runs on its own clock,
// wt, whose RATE eases from 1 down to 0.05 a moment after each burst, holds
// there for about four seconds, and eases back to 1. wt is the exact integral
// of that rate - the integral of a smoothstep is the closed form
// x^3 - x^4 / 2 - so it is monotonic, continuous and the same at any frame
// rate: the world never stutters, never jumps and never runs backwards.
//
// THE BURST is 28 drops thrown from the origin along hashed directions, with
// drag and a little gravity, as a function of wt alone. While the world clock
// runs at full speed each drop is stretched along its velocity - the eye's
// own motion blur - and as time freezes they round up into beads, which is
// the moment the camera starts its arc: about 125 degrees around the splash
// on an eased curve, rising a little, while the drops hang in the air. Then
// time resumes, the drops fly on and fall, shrink away, and the next burst
// comes up from nothing.
//
// The cycle is uTime's (9 s), which keeps a burst from ever being cut off
// mid-air by a busy drum line - each burst is its own shape (a fresh hash per
// cycle). The music moves what is not a clock: the orbit between arcs
// advances on uFlowPhase (integrated loudness), uSwell inflates the drops,
// treble sharpens their glints and a new form (uFormPhase) slides their hues.
//
// NO-FLASH. The freeze changes speed, never light: the backdrop is constant
// and the drops are small. Nothing passes the camera.

#define FM_TAU 6.2831853
#define FM_DROPS 28
#define FM_CYCLE 9.0
/** The camera's arc during the freeze, radians. */
#define FM_ARC 2.2
#define FM_FLOOR -1.9

/** The integral from 0 to a of smoothstep(e0, e1, x). */
float fmRamp(float a, float e0, float e1) {
    float w = e1 - e0;
    float u = clamp((a - e0) / w, 0.0, 1.0);
    return w * (u * u * u - 0.5 * u * u * u * u) + max(a - e1, 0.0);
}

/** World time inside a cycle: the integral of a rate that freezes after the burst. */
float fmWorldTime(float a) {
    return a - 0.95 * (fmRamp(a, 0.3, 0.7) - fmRamp(a, 4.6, 5.4));
}

/** The world clock's rate at cycle time a, for the motion stretch. */
float fmRate(float a) {
    return 1.0 - 0.95 * (smoothstep(0.3, 0.7, a) - smoothstep(4.6, 5.4, a));
}

vec3 gCenter;
vec3 gAxis;
float gRadius;
float gStretch;

/** Drop i of the cycle `seed` at world time wt: centre, velocity axis, radius. */
void fmDrop(int i, float seed, float wt, float life, float rate) {
    float fi = float(i);
    float h1 = hash11(seed + fi * 1.37);
    float h2 = hash11(seed + fi * 2.71 + 5.0);
    float h3 = hash11(seed + fi * 4.13 + 9.0);
    // Mostly outward and upward: a crown, not a sphere.
    float z = mix(-0.35, 0.95, h1);
    float phi = FM_TAU * h2;
    vec3 dir = vec3(sqrt(1.0 - z * z) * cos(phi), z, sqrt(1.0 - z * z) * sin(phi));
    float v = mix(1.3, 2.8, h3);
    const float drag = 0.8;
    vec3 g = vec3(0.0, -1.1, 0.0);
    gCenter = dir * v * (1.0 - exp(-drag * wt)) / drag + 0.5 * g * wt * wt;
    vec3 vel = dir * v * exp(-drag * wt) + g * wt;
    gAxis = normalize(vel + vec3(1e-4));
    gRadius = mix(0.1, 0.27, hash11(seed + fi * 6.07)) * life * (1.0 + 0.3 * clamp(uSwell, 0.0, 1.0)) * xm(uXSize);
    gStretch = 1.0 + 2.2 * rate * clamp(length(vel) * 0.45, 0.0, 1.0);
}

/** Ray against the current drop's ellipsoid: hit distance or -1, and the normal. */
float fmHit(vec3 ro, vec3 rd, out vec3 n) {
    n = vec3(0.0, 1.0, 0.0);
    vec3 oc = ro - gCenter;
    float bound = gRadius * gStretch;
    float b0 = dot(oc, rd);
    float c0 = dot(oc, oc) - bound * bound;
    if (c0 > 0.0 && b0 > 0.0) return -1.0;
    if (b0 * b0 - c0 < 0.0) return -1.0;
    // Into the space where the ellipsoid is the unit sphere.
    float k = 1.0 / gStretch - 1.0;
    vec3 o = (oc + k * dot(oc, gAxis) * gAxis) / gRadius;
    vec3 d = (rd + k * dot(rd, gAxis) * gAxis) / gRadius;
    float A = dot(d, d);
    float B = dot(o, d);
    float C = dot(o, o) - 1.0;
    float disc = B * B - A * C;
    if (disc < 0.0) return -1.0;
    float t = (-B - sqrt(disc)) / A;
    if (t < 0.0) return -1.0;
    vec3 q = o + d * t;
    n = normalize(q + k * dot(q, gAxis) * gAxis);
    return t;
}

/** The studio: a graded cyclorama, a soft key spot, and a floor that catches the shadows. */
vec3 fmBackdrop(vec3 rd, float hue) {
    float up = rd.y;
    vec3 top = mix(vec3(0.05, 0.06, 0.09), pal(hue + 0.55) * 0.12, 0.4);
    vec3 horizon = mix(vec3(0.32, 0.3, 0.34), pal(hue + 0.6) * 0.35, 0.35);
    vec3 col = mix(horizon, top, smoothstep(-0.05, 0.6, up));
    col += pal(hue + 0.1) * 0.25 * exp(-pow(length(rd.xz - vec2(0.0, 0.6)) * 1.4, 2.0)) * smoothstep(-0.2, 0.3, up);
    return col;
}

vec3 fmTone(vec3 c) {
    return 1.0 - exp(-max(c, 0.0) * 1.35);
}

void main() {
    vec2 uv = cameraView();
    float hue = 0.55 + 0.3 * uFormPhase;
    float glint = min(uTrebleSmooth, 1.3);

    float cycle = floor(uTime / FM_CYCLE);
    float a = uTime - cycle * FM_CYCLE;
    float wt = fmWorldTime(a);
    float rate = fmRate(a);
    float seed = hash11(cycle * 0.618 + 3.0) * 97.0;
    float life = smoothstep(0.0, 0.14, wt) * (1.0 - smoothstep(7.2, 8.7, a));

    // The camera: a slow orbit on integrated loudness, plus the eased arc of each freeze.
    float arcU = clamp((a - 0.75) / 3.9, 0.0, 1.0);
    float arcEase = arcU * arcU * arcU * (arcU * (arcU * 6.0 - 15.0) + 10.0);
    float theta = mod(cycle, 64.0) * FM_ARC + FM_ARC * arcEase + uFlowPhase * 0.25;
    float rise = 0.35 * sin(arcEase * 3.14159);
    theta += touchAnchor().x * 0.8;
    float elev = 0.18 + rise + touchAnchor().y * 0.4;
    float radius = 5.6 - 0.7 * sin(arcEase * 3.14159);
    vec3 target = vec3(0.0, 0.15, 0.0);
    vec3 eye = target + radius * vec3(sin(theta) * cos(elev), sin(elev), -cos(theta) * cos(elev));
    vec3 fw = normalize(target - eye);
    vec3 rt = normalize(cross(vec3(0.0, 1.0, 0.0), fw));
    vec3 upv = cross(fw, rt);
    vec3 rd = normalize(rt * uv.x + upv * uv.y + fw * 1.6);
    vec3 ro = eye;
    cameraOrbit(ro, rd);

    // The nearest drop.
    float best = 1e9;
    vec3 bestN = vec3(0.0);
    float bestH = 0.0;
    for (int i = 0; i < FM_DROPS; i++) {
        fmDrop(i, seed, wt, life, rate);
        if (gRadius < 1e-3) continue;
        vec3 n;
        float t = fmHit(ro, rd, n);
        if (t > 0.0 && t < best) {
            best = t;
            bestN = n;
            bestH = hash11(seed + float(i) * 8.3);
        }
    }

    vec3 col = fmBackdrop(rd, hue);
    // The floor, with a soft shadow pooled under each drop.
    float tf = rd.y < 0.0 ? (FM_FLOOR - ro.y) / rd.y : 1e9;
    if (tf < best) {
        vec3 p = ro + rd * tf;
        float shade = 1.0;
        for (int i = 0; i < FM_DROPS; i++) {
            fmDrop(i, seed, wt, life, rate);
            float hgt = gCenter.y - FM_FLOOR;
            float spread = gRadius * 1.3 + hgt * 0.12;
            float dd = length(p.xz - gCenter.xz) / max(spread, 1e-3);
            shade *= 1.0 - 0.22 * exp(-dd * dd) * smoothstep(0.0, 0.05, gRadius);
        }
        vec3 floorCol = mix(vec3(0.3, 0.29, 0.31), pal(hue + 0.6) * 0.3, 0.3);
        floorCol *= 0.55 + 0.45 * exp(-dot(p.xz, p.xz) * 0.03);
        col = mix(floorCol * max(shade, 0.5), col, smoothstep(9.0, 22.0, tf));
    }

    if (best < 1e8) {
        vec3 n = bestN;
        vec3 v = -rd;
        vec3 key = normalize(vec3(-0.5, 0.8, -0.4));
        vec3 fillDir = normalize(vec3(0.7, 0.2, -0.3));
        float fres = 0.04 + 0.96 * pow(1.0 - max(dot(n, v), 0.0), 5.0);
        vec3 tint = pal(hue + bestH * 0.4);
        // Glass: what is behind, bent and tinted, plus the studio reflected.
        vec3 refr = (fmBackdrop(refract(rd, n, 0.75), hue) + 0.12) * tint * 2.0;
        vec3 refl = fmBackdrop(reflect(rd, n), hue) * 1.2;
        vec3 body = mix(refr, refl, fres);
        float spec = pow(max(dot(reflect(-key, n), v), 0.0), 90.0);
        float fill = pow(max(dot(reflect(-fillDir, n), v), 0.0), 30.0);
        float rim = pow(1.0 - max(dot(n, v), 0.0), 3.0) * max(dot(n, vec3(0.0, 0.3, 1.0)), 0.0);
        // A caustic glow inside, opposite the key: light focused through the bead.
        float core = pow(max(dot(n, -key), 0.0), 3.0);
        col = body + vec3(1.0) * spec * (0.9 + 0.5 * glint) + tint * fill * 0.25 + tint * rim * 0.5 + tint * core * 0.35;
    }

    if (!touchIdle()) {
        col += pal(hue + 0.3) * min(touchWake(uv), 2.0) * 0.05;
    }
    col = fmTone(col);
    col *= 0.76 + 0.24 * smoothstep(2.0, 0.4, length(uv));
    fragColor = vec4(grade(col), 1.0);
}
