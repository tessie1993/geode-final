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

// Hyper Lattice: the camera floats inside the strut lattice of a 4D cubic
// grid. The grid turns through the fourth dimension and what the eye sees is
// its 3D cross-section, so struts swing, cross and reconnect as the slice
// tilts: the space itself folds round the viewer while the camera barely moves.
//
// ---- why the struts are the grid's FACES -------------------------------------
//
// A line in 4D meets a 3D slice in a single point, so slicing the grid's
// edges gives beads, not struts. A 2-plane meets a 3D slice in a LINE. So the
// struts here are the cross-section of the grid's square faces: the six face
// families, each named by the two axes it runs along and close wherever the
// other two coordinates are near whole numbers. The distance to a family is
// the length of those two offsets - two squares and a root, six times a step.
// The grid's edges, sliced, are the beads at the nodes where struts meet.
//
// The slice's normal wanders round the 4D main diagonal and never toward an
// axis. Near an axis, the three face families that do not involve it would lie
// almost inside the slice and the struts would smear into glowing sheets: a
// whole-frame brightness jump. Held near the diagonal, every family stays a
// line; exactly on it, the cross-section is periodic on an FCC lattice, and the
// wander folds it in and out of that order.
//
// ---- the float ------------------------------------------------------------------
//
// A 3D point q becomes P = E q + O, with E three orthonormal 4-vectors spanning
// the slice, so a ray is the 4D line P0 + D t and a step is four multiply-adds.
// O is the camera's place in the grid. The grid has period 1 on every axis, so
// O is wrapped with fract() and never grows: hours of drift keep full
// precision. O moves partly within the slice (the camera travels) and partly
// across it (the cross-section itself changes).
//
// ---- glow, not surfaces -----------------------------------------------------------
//
// Nothing is hit. Every step adds the struts' emission at its distance,
// weighted by the step length, so the glow is a line integral and does not
// change with Detail; a strut the ray only grazes still leaves its halo. The
// step shortens near a strut and lengthens in the open, which is what lets a
// fixed budget see nine cells deep.
//
// ---- audio --------------------------------------------------------------------------
//
// Energy sets the drift through uFlowPhase (integrated, so louder speeds the
// float up without ever teleporting it). uBassSmooth thickens the struts and
// uMidSmooth spreads the six families across the palette. A beat sends a bead
// of light along every strut (uBeatPhase, gated by the beat envelope), and a
// spike re-aims the slice's wander (uFormPhase, which glides). uTrebleSmooth
// makes the nodes twinkle. Nothing keys whole-frame brightness off a transient.

#define HL_MAX_STEPS 96
#define HL_FAR 9.0
#define HL_FOCAL 0.95
#define HL_TAU 6.2831853

/** The slice's axes in grid space. */
vec4 gEx;
vec4 gEy;
vec4 gEz;

vec4 embed(vec3 q) {
    return gEx * q.x + gEy * q.y + gEz * q.z;
}

/** Removes the parts of v along the unit vectors a and b, then normalizes. */
vec4 orthonormal(vec4 v, vec4 a, vec4 b) {
    v -= a * dot(v, a);
    v -= b * dot(v, b);
    return normalize(v);
}

/** Soft rolloff toward white, applied before grade() so its controls keep their meaning. */
vec3 filmic(vec3 c) {
    c = max(c, 0.0) * 1.2;
    return c * (1.0 + c * 0.25) / (1.0 + c);
}

// A finger steers the look toward where it is held; on release the look
// glides home as the touch fades. Untouched, the ray comes back as it went in.
vec3 steerLook(vec3 rd) {
    if (touchIdle()) return rd;
    vec2 a = touchAnchor() * touchStrength();
    return rotY(a.x * 0.9) * (rotX(-a.y * 0.6) * rd);
}

void main() {
    vec2 uv = view();
    float bass = clamp(uBassSmooth, 0.0, 1.5);
    float mid = clamp(uMidSmooth, 0.0, 1.5);
    float treb = clamp(uTrebleSmooth, 0.0, 1.5);
    float swell = clamp(uSwell, 0.0, 1.5);
    float beatGate = clamp(uBeat, 0.0, 1.0) * clamp(uBeatResponse, 0.0, 2.0);

    // ---- the 4D turn ---------------------------------------------------------
    //
    // The slice's normal wanders round the diagonal on fixed rates plus bounded,
    // periodic offsets from uFormPhase: a spike re-aims the wander by gliding the
    // offsets, never by changing a rate that multiplies a long-running clock.
    float turn = uTime * 0.05 + uFlowPhase * 0.35;
    vec4 lean = 1.1 * sin(HL_TAU * (uFormPhase + vec4(0.0, 0.25, 0.5, 0.75)));
    vec4 nrm = normalize(vec4(1.0) + 0.32 * sin(turn * vec4(1.0, 0.83, 0.67, 0.59) + vec4(0.0, 1.7, 3.1, 4.4) + lean));
    // Three helpers that are orthonormal and perpendicular to the diagonal,
    // Gram-Schmidted onto the wandering slice. The normal never strays far
    // from the diagonal, so they never come close to degenerate.
    gEx = orthonormal(vec4(0.70710678, -0.70710678, 0.0, 0.0), nrm, vec4(0.0));
    gEy = orthonormal(vec4(0.40824829, 0.40824829, -0.81649658, 0.0), nrm, gEx);
    gEz = orthonormal(orthonormal(vec4(0.28867513, 0.28867513, 0.28867513, -0.8660254), nrm, gEx), gEy, vec4(0.0));

    // ---- the float -------------------------------------------------------------
    //
    // The drift heading lives in lattice space, so as the slice turns the
    // apparent direction of travel wanders: the camera floats rather than flies.
    float drift = uTime * 0.10 + uFlowPhase * 1.6;
    vec4 origin = fract(vec4(0.23, 0.11, 0.94, 0.19) * drift + vec4(0.5, 0.5, 0.0, 0.31));
    vec3 rd = normalize(vec3(uv, HL_FOCAL));
    rd = rotY(0.30 * sin(uTime * 0.043)) * (rotX(0.18 * sin(uTime * 0.031 + 1.3)) * rd);
    rd = steerLook(rd);
    vec3 ro = vec3(0.0);
    cameraFlight(ro, rd);
    vec4 p0 = embed(ro) + origin;
    vec4 dir = embed(rd);

    // ---- the look of the struts ---------------------------------------------------
    float radius = 0.022 * (1.0 + 0.55 * bass) * (1.0 + 0.15 * swell);
    float r2 = radius * radius;
    float nodeR2 = 2.4 * 2.4 * r2;
    float spread = 0.04 + 0.05 * mid;
    float hue = 0.17 + 0.04 * sin(uTime * 0.02);
    // The six face families, by the axes they run along: xy, xz, xw | yz, yw, zw.
    // The two halves sit a third of the palette apart, so the lattice reads as
    // two interleaved colours of neon rather than a rainbow.
    vec3 famA0 = pal(hue);
    vec3 famA1 = pal(hue + spread);
    vec3 famA2 = pal(hue + 2.0 * spread);
    vec3 famB0 = pal(hue + 0.33);
    vec3 famB1 = pal(hue + 0.33 + spread);
    vec3 famB2 = pal(hue + 0.33 + 2.0 * spread);
    vec3 nodeCol = mix(pal(hue + 0.5), vec3(1.0), 0.5);
    // Fog: the slice fades out nine cells deep; a louder passage sees further.
    float fogK = 0.30 - 0.07 * swell;

    float budget = clamp(uSteps * 0.75, 24.0, float(HL_MAX_STEPS));
    vec3 glow = vec3(0.0);
    float t = 0.04;
    for (int i = 0; i < HL_MAX_STEPS; i++) {
        if (float(i) >= budget || t > HL_FAR) break;
        vec4 p = p0 + dir * t;
        vec4 c = p - floor(p + 0.5);
        vec4 s = c * c;
        // Squared distance to each face family: the two coordinates it does NOT run along.
        vec3 dA = vec3(s.z + s.w, s.y + s.w, s.y + s.z);
        vec3 dB = vec3(s.x + s.w, s.x + s.z, s.x + s.y);
        // The edges, sliced: where three struts meet, the other three coordinates are whole.
        float total = s.x + s.y + s.z + s.w;
        float n2 = total - max(max(s.x, s.y), max(s.z, s.w));
        vec3 m = min(dA, dB);
        float dmin = sqrt(min(m.x, min(m.y, m.z)));
        float h = clamp(dmin * 0.6, 0.012, 0.26);
        // The strut core and a soft sheath; the sheath is what fills the air.
        vec3 coreA = exp(-dA / r2) + 0.05 * exp(-dA / (r2 * 14.0));
        vec3 coreB = exp(-dB / r2) + 0.05 * exp(-dB / (r2 * 14.0));
        // The beat's bead of light, running along each strut with uBeatPhase:
        // the families with x free carry it on p.x, the rest on p.y or p.z.
        vec3 runA = vec3(fract(p.x - uBeatPhase) - 0.5);
        vec3 runB = fract(vec3(p.y, p.y, p.z) - uBeatPhase) - 0.5;
        coreA *= 1.0 + 1.3 * beatGate * exp(-runA * runA * 70.0);
        coreB *= 1.0 + 1.3 * beatGate * exp(-runB * runB * 70.0);
        // Which 4D cell this is: the slice crosses many, and each has a tint.
        float cell = fract(dot(floor(p + 0.5), vec4(0.17, 0.29, 0.41, 0.618)));
        vec3 tint = mix(vec3(1.0), pal(hue + 0.4 * cell), 0.3);
        float twinkle = 0.5 + 0.5 * sin(uTime * 2.3 + cell * 40.0);
        float node = 1.6 * exp(-n2 / nodeR2) * (0.5 + 0.7 * treb * twinkle);
        vec3 e = (famA0 * coreA.x + famA1 * coreA.y + famA2 * coreA.z
                + famB0 * coreB.x + famB1 * coreB.y + famB2 * coreB.z) * tint
               + nodeCol * node;
        // Struts the camera is passing through fade out rather than smear across the lens.
        glow += e * (h * exp(-t * fogK) * smoothstep(0.05, 0.7, t));
        t += h;
    }

    // The deep background the lattice dissolves into.
    vec3 col = pal(hue + 0.25) * 0.035 * (0.6 + 0.4 * rd.y) + glow * 3.0;
    // The fingers' wake, a faint shimmer where they were.
    if (!touchIdle()) {
        col += pal(hue + 0.3) * min(touchWake(uv), 3.0) * 0.04;
    }
    col *= 0.72 + 0.28 * smoothstep(2.2, 0.4, length(uv));
    fragColor = vec4(grade(filmic(col)), 1.0);
}
