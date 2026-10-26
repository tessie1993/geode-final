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
//#include lib_camera

// Swarm Chase: a chase camera tailing a swarm. A glowing core weaves ahead
// through dark space with a cloud of motes orbiting and flocking round it,
// and the camera follows on a spring: it lags into the core's turns, swings
// wide on the curves and drops back when the swarm speeds up, catching up
// again when it eases off.
//
// ---- the spring, in closed form ----------------------------------------------------
//
// A camera on a critically damped spring has no per-frame state to keep if
// the thing it follows moves on sinusoids: the spring's response to a sine of
// frequency w is the same sine, scaled by 1 / (1 + (w/w0)^2) and delayed by
// 2 atan(w/w0). So the camera's sideways path is the core's path with every
// term attenuated and delayed by those exact amounts, and its following
// distance grows by the spring's steady lag on a ramp, 2 v / w0, with v the
// speed the integrated flow is advancing at. It is the same at any frame rate
// because it is a function of the clocks, not a simulation.
//
// Everything is drawn relative to the core's distance down the track, so the
// numbers stay small however long the chase runs.
//
// ---- motes ---------------------------------------------------------------------------
//
// Each mote is analytic: an orbit of its own radius, tilt and rate round the
// core. Drawn motion-blurred: the streak from where it is to where it was a
// shutter ago, so fast motes smear into arcs; the glow is the closest distance
// from the pixel's ray to that segment, never narrower than a pixel.
//
// ---- audio ---------------------------------------------------------------------------
//
// Energy sets the chase speed through uFlowPhase and so the spring's lag and
// the motes' orbit rate (both integrated or slewed, never jumping). uSwell
// widens the swarm, uMidSmooth roughens the flocking, uTrebleSmooth twinkles
// the motes, uBassSmooth breathes the core. A beat sends a ripple out through
// the swarm's shells (uBeatPhase, gated by the beat envelope). A finger steers
// the look.

#define SC_MOTES 56
#define SC_TAU 6.2831853
#define SC_W0 0.9

vec3 gCore;

/** The core's sideways path at time s, and the spring-followed version of it. */
vec2 coreSway(float s) {
    return vec2(1.5 * sin(0.31 * s) + 0.6 * sin(0.73 * s + 1.0), 0.9 * sin(0.41 * s + 1.0) + 0.4 * sin(0.97 * s));
}

/** One sinusoid term as the spring camera sees it: attenuated and delayed. */
float spring(float amp, float w, float phase, float s) {
    float k = w / SC_W0;
    return amp / (1.0 + k * k) * sin(w * s + phase - 2.0 * atan(k));
}

vec2 camSway(float s) {
    return vec2(spring(1.5, 0.31, 0.0, s) + spring(0.6, 0.73, 1.0, s),
                spring(0.9, 0.41, 1.0, s) + spring(0.4, 0.97, 0.0, s));
}

/** Closest distance from the ray to segment ab, and the ray parameter there. */
vec2 raySegment(vec3 ro, vec3 rd, vec3 a, vec3 b) {
    vec3 ab = b - a;
    vec3 ao = ro - a;
    float abab = dot(ab, ab) + 1e-8;
    float abrd = dot(ab, rd);
    float abao = dot(ab, ao);
    float rdao = dot(rd, ao);
    float denom = abab - abrd * abrd;
    float s = denom > 1e-6 ? clamp((abao - abrd * rdao) / denom, 0.0, 1.0) : 0.0;
    vec3 q = a + ab * s;
    float t = max(dot(q - ro, rd), 0.0);
    return vec2(length(ro + rd * t - q), t);
}

/** Where mote i is at orbit phase `turn`, relative to the core. */
vec3 motePos(float fi, float turn, float spread, float ripple, float rough) {
    float h1 = hash11(fi * 1.37 + 0.11);
    float h2 = hash11(fi * 2.71 + 0.53);
    float h3 = hash11(fi * 3.17 + 0.97);
    float radius = (0.55 + 1.25 * h1) * spread;
    // The beat's ripple runs out through the shells: inner first.
    radius *= 1.0 + 0.28 * ripple * exp(-(h1 - uBeatPhase) * (h1 - uBeatPhase) * 18.0);
    vec3 axis = normalize(vec3(h2 - 0.5, 1.0, h3 - 0.5));
    vec3 u = normalize(cross(axis, vec3(0.3, 0.1, 1.0)));
    vec3 v = cross(axis, u);
    float rate = (0.6 + 0.9 * h2) * (h3 < 0.5 ? -1.0 : 1.0);
    float a = rate * turn + h1 * SC_TAU;
    vec3 p = (u * cos(a) + v * sin(a)) * radius;
    // Flocking: a slow shared drift that bends the orbits, rougher on the mids.
    p += rough * vec3(sin(a * 0.5 + uTime * 0.7), sin(a * 0.37 + uTime * 0.53 + 2.0), cos(a * 0.41 + uTime * 0.61));
    return p;
}

vec3 space(vec3 d) {
    vec3 col = mix(pal(0.3), vec3(0.01, 0.012, 0.03), 0.7) * (0.25 + 0.2 * d.y);
    float neb = motionFbm(vec2(atan(d.x, d.z) * 1.6, d.y * 2.2) + 3.0);
    col += mix(pal(0.2), pal(0.5), neb) * 0.09 * smoothstep(0.3, 0.8, neb);
    return col;
}

vec3 filmic(vec3 c) {
    c = max(c, 0.0) * 1.2;
    return c * (1.0 + c * 0.25) / (1.0 + c);
}

// A finger steers the look toward where it is held; on release the look
// glides home as the touch fades. Untouched, the ray comes back as it went in.
vec3 steerLook(vec3 rd) {
    if (touchIdle()) return rd;
    vec2 a = touchAnchor() * touchStrength();
    return rotY(a.x * 0.8) * (rotX(-a.y * 0.55) * rd);
}

void main() {
    vec2 uv = view();
    float bass = clamp(uBassSmooth, 0.0, 1.5);
    float mid = clamp(uMidSmooth, 0.0, 1.5);
    float treb = clamp(uTrebleSmooth, 0.0, 1.5);
    float swell = clamp(uSwell, 0.0, 1.5);
    float ripple = clamp(uBeat, 0.0, 1.0) * clamp(uBeatResponse, 0.0, 2.0);
    float energy = clamp(uEnergySmooth, 0.0, 1.5);

    // ---- the chase ---------------------------------------------------------------------
    float s = uTime;
    gCore = vec3(coreSway(s), 0.0);
    // Forward speed is the flow's own rate (ShaderScene: 0.08 + 0.22 * energy
    // per second) times the track scale; the spring's steady lag on it.
    float speed = 0.5 + 4.0 * (0.08 + 0.22 * energy);
    float back = 2.3 + 0.35 * (2.0 * speed / SC_W0);
    vec3 cam = vec3(camSway(s), -back) + vec3(0.0, 0.55, 0.0);
    vec3 look = gCore + vec3(0.0, 0.1, 0.8);
    vec3 fwd = normalize(look - cam);
    // Bank with the core's sideways swing.
    float roll = clamp(-0.35 * (gCore.x - cam.x), -0.4, 0.4);
    vec3 right = normalize(cross(vec3(sin(roll), cos(roll), 0.0), fwd));
    vec3 up = cross(fwd, right);
    vec3 rd = mat3(right, up, fwd) * steerLook(normalize(vec3(uv, 1.05)));
    // The Camera orbits and pushes about the core.
    vec3 rel = cam - gCore;
    cameraOrbit(rel, rd);
    vec3 ro = gCore + rel;

    vec3 col = space(rd);

    // ---- dust rushing past ---------------------------------------------------------------
    //
    // Three slabs of specks streaming toward the lens with the integrated
    // travel, so the chase reads as speed even where the swarm is not.
    float travel = uTime * 0.5 + uFlowPhase * 4.0;
    for (int k = 0; k < 3; k++) {
        float phase = fract(float(k) / 3.0 + travel * 0.11);
        float depth = mix(9.0, 0.6, phase);
        vec2 q = rd.xy / max(rd.z, 0.05) * depth * 1.6 + float(k) * 7.3;
        vec2 cell = floor(q);
        vec2 f = fract(q) - 0.5;
        float h = motionHash(cell + float(k) * 13.0 + floor(travel * 0.11 + float(k) / 3.0) * 3.1);
        vec2 off = (vec2(motionHash(cell + 1.7), motionHash(cell + 4.1)) - 0.5) * 0.6;
        float speck = smoothstep(0.06 + 0.02 * phase, 0.0, length(f - off)) * step(0.82, h);
        col += mix(pal(0.6), vec3(1.0), 0.6) * speck * 0.25 * sin(phase * 3.14159);
    }

    // ---- the core --------------------------------------------------------------------------
    float coreR = 0.32 * (1.0 + 0.12 * bass);
    vec3 oc = ro - gCore;
    float b = dot(oc, rd);
    float c = dot(oc, oc) - coreR * coreR;
    float disc = b * b - c;
    float tCore = 1e9;
    vec3 coreCol = mix(pal(0.06), vec3(1.0), 0.35);
    float closest = sqrt(max(dot(oc, oc) - b * b, 0.0));
    if (disc > 0.0) {
        tCore = -b - sqrt(disc);
        vec3 n = normalize(oc + rd * tCore);
        float facing = clamp(dot(-rd, n), 0.0, 1.0);
        float swirl = motionFbm(vec2(atan(n.z, n.x) * 2.0 + uTime * 0.4, n.y * 3.0 - uTime * 0.3));
        // Hot inside, a cooler rim: a plasma ball lit from within.
        col = mix(coreCol, vec3(1.0), 0.55 * facing * facing) * (0.7 + 0.5 * swirl) * (0.55 + 0.75 * facing)
            + pal(0.9) * pow(1.0 - facing, 3.0) * 0.6;
    }
    col += coreCol * (0.3 * coreR * coreR / (coreR * coreR + closest * closest * 0.5) + 0.2 * exp(-closest * 1.2)) * (1.0 + 0.3 * bass);

    // ---- the swarm -------------------------------------------------------------------------
    float spread = 1.0 + 0.3 * swell;
    float rough = 0.06 + 0.16 * mid;
    float turn = uTime * 0.7 + uFlowPhase * 2.5;
    float shutter = 0.06 + 0.10 * energy;
    float pixel = 1.5 / (uResolution.y * 1.05);
    vec3 motes = vec3(0.0);
    for (int i = 0; i < SC_MOTES; i++) {
        float fi = float(i);
        vec3 a = gCore + motePos(fi, turn, spread, ripple, rough);
        vec3 bb = gCore + motePos(fi, turn - shutter, spread, ripple, rough);
        vec2 hit = raySegment(ro, rd, a, bb);
        if (hit.y > tCore) continue;
        float r = max(0.03, hit.y * pixel);
        float glow = exp(-hit.x * hit.x / (r * r)) + 0.2 * r * r / (r * r + hit.x * hit.x);
        // Streaks spread their light over their length.
        glow *= 0.18 / (0.18 + length(a - bb));
        float h = hash11(fi * 5.13);
        float twinkle = 0.75 + 0.5 * treb * (0.5 + 0.5 * sin(uTime * 6.0 + fi * 2.4));
        motes += mix(pal(0.45 + 0.35 * h), vec3(1.0), 0.25) * glow * twinkle;
    }
    col += motes * 1.5;

    if (!touchIdle()) {
        col += coreCol * min(touchWake(uv), 3.0) * 0.04;
    }
    col *= 0.7 + 0.3 * smoothstep(2.2, 0.4, length(uv));
    fragColor = vec4(grade(filmic(col)), 1.0);
}
