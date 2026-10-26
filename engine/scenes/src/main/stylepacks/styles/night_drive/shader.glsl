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
//#include lib_style

// Night Drive: a tracking shot down an endless road at night, toward a low
// banded sun, between two skylines that are the music's spectrum.
//
// THE ROAD is the plane y = 0, met in closed form: wet asphalt with its lane
// markings repeated down it (a dashed line between the lanes, solid edges and
// a double centre line), pools of light under every street lamp, the
// headlights' cone ahead, and the lamps, sky and sun mirrored in the wet.
//
// THE LAMPS AND BARRIERS are the marched part: a light pole every
// ND_POLE_GAP units down each verge, the two sides staggered by half a gap,
// all from one pole by repetition along z, plus a low barrier on each side.
// The march sums a glow off each lamp head as it passes, so the lamps have
// halos without being lights the road has to loop over.
//
// THE SKYLINE is two silhouettes on the horizon, drawn in direction space: a
// far, dark one, and a near one whose buildings run from the bass on the left
// to the treble on the right, each building's height a band of the spectrum
// (uAudioTex), its windows lit by the mids. Both part round the road so the
// sun sets in the gap.
//
// THE SHOT. The camera tracks at headlight height, drifting in its lane and
// now and then easing across into the next one, yawing a touch toward where
// it is drifting and leaning against it.
//
// AUDIO. Energy sets the speed (uFlowPhase, integrated: it eases, never
// jumps), so the markings and poles stream past faster when the music is
// loud. The spectrum is the skyline. Bass swells the sun's glow; a beat
// ripples the bands across the sun (uBeatPhase walks the ripple down it) and
// uSpike swells the lamp halos, both with a rise, never a flash. Treble
// glints on the markings.

#define ND_MAX_STEPS 64
#define ND_REFL_STEPS 20
#define ND_FOCAL 1.3
#define ND_FAR 150.0

const float ND_POLE_GAP = 14.0;
const float ND_POLE_X = 7.6;
const float ND_LAMP_Y = 6.2;
const float ND_ARM = 1.8;
const vec3 ND_SUN = vec3(0.0, 0.0698, 0.99756);

float gLamp;

/** One verge's poles: a post, an arm over the road and a lamp head, repeated every ND_POLE_GAP. */
float ndPoles(vec3 p, float side, float offset) {
    float z = mod(p.z + offset, ND_POLE_GAP) - 0.5 * ND_POLE_GAP;
    vec3 q = vec3(p.x - side * ND_POLE_X, p.y, z);
    float post = max(length(q.xz) - 0.1, abs(q.y - ND_LAMP_Y * 0.5) - ND_LAMP_Y * 0.5);
    float arm = sdBox(q - vec3(-side * ND_ARM * 0.5, ND_LAMP_Y, 0.0), vec3(ND_ARM * 0.5, 0.05, 0.05));
    float lamp = sdBox(q - vec3(-side * ND_ARM, ND_LAMP_Y - 0.08, 0.0), vec3(0.32, 0.07, 0.16));
    gLamp = min(gLamp, lamp);
    return min(min(post, arm), lamp);
}

float ndMap(vec3 p) {
    gLamp = 1e3;
    float d = min(ndPoles(p, 1.0, 0.0), ndPoles(p, -1.0, 0.5 * ND_POLE_GAP));
    float barrier = max(abs(abs(p.x) - 6.9) - 0.18, p.y - 0.75);
    return min(d, barrier);
}

vec3 ndNormal(vec3 p, float eps) {
    vec2 k = vec2(1.0, -1.0);
    return normalize(k.xyy * ndMap(p + k.xyy * eps) + k.yyx * ndMap(p + k.yyx * eps)
                   + k.yxy * ndMap(p + k.yxy * eps) + k.xxx * ndMap(p + k.xxx * eps));
}

vec3 ndLampColour() {
    return mix(pal(0.9), vec3(1.0, 0.85, 0.6), 0.35);
}

/** Light from the two nearest lamps on each verge, falling on a point at p. */
vec3 ndLampLight(vec3 p) {
    float sum = 0.0;
    for (int s = 0; s < 2; s++) {
        float side = s == 0 ? 1.0 : -1.0;
        float offset = s == 0 ? 0.0 : 0.5 * ND_POLE_GAP;
        float dz = mod(p.z + offset, ND_POLE_GAP) - 0.5 * ND_POLE_GAP;
        vec2 lamp = vec2(side * (ND_POLE_X - ND_ARM), ND_LAMP_Y - 0.15);
        for (int k = 0; k < 2; k++) {
            float z = dz - (k == 0 ? 0.0 : sign(dz) * ND_POLE_GAP);
            vec3 d = vec3(p.x - lamp.x, p.y - lamp.y, z);
            sum += 14.0 / (dot(d, d) + 6.0) * smoothstep(0.0, 0.8, -d.y / length(d));
        }
    }
    return ndLampColour() * sum;
}

/** The sky, the low banded sun and the two skylines, seen along rd. */
vec3 ndSky(vec3 rd, float bass, float mid, float beatEnv) {
    float e = rd.y;
    float a = atan(rd.x, rd.z);
    vec3 top = vec3(0.015, 0.008, 0.04) + pal(0.2) * 0.05;
    vec3 low = pal(0.05) * 0.32 + vec3(0.04, 0.01, 0.03);
    vec3 col = mix(low, top, smoothstep(0.0, 0.45, e));
    col += pal(0.93) * 0.25 * exp(-abs(a) * 3.0) * exp(-max(e, 0.0) * 9.0);
    // A few stars high up.
    vec2 sp = vec2(a * 50.0, e * 50.0);
    vec2 sc = floor(sp);
    float st = motionHash(sc + 4.4);
    col += vec3(0.8, 0.8, 1.0) * step(0.94, st) * smoothstep(0.12, 0.0, length(fract(sp) - 0.5)) * smoothstep(0.15, 0.4, e) * 0.6;

    // The sun: a disc from gold at the top to magenta at the bottom, cut by
    // bands that thicken toward the horizon and drift down it. The beat
    // ripples them: a wave walked down the disc by uBeatPhase.
    vec2 sd = vec2(a, e - ND_SUN.y);
    float r = length(sd);
    float R = 0.125;
    float halo = exp(-r * 5.0) * (0.25 + 0.5 * bass) + exp(-r * 14.0) * 0.3;
    col += mix(pal(0.05), pal(0.92), 0.5) * halo;
    if (r < R) {
        float v = sd.y / R;
        vec3 disc = mix(pal(0.08), pal(0.88), smoothstep(-0.8, 0.8, v)) * 1.5;
        float ripple = beatEnv * beatEnv * 0.06 * exp(-pow((v + 2.0 * uBeatPhase - 1.0) * 3.0, 2.0));
        float w = clamp((0.3 - v) * 0.5, 0.0, 0.55);
        float gap = step(fract(v * 3.2 + uTime * 0.1 + ripple * 6.0), w);
        float edge = smoothstep(R, R - 0.004, r);
        col = mix(col, disc, edge * (1.0 - gap));
    }

    // The far skyline: dark blocks.
    float part = smoothstep(0.1, 0.32, abs(a));
    float i2 = floor(a * 40.0);
    float h2 = (0.025 + 0.06 * motionHash(vec2(i2, 2.0))) * part;
    if (e < h2 && e > -0.02) col = mix(pal(0.2) * 0.04, col, 0.25);
    // The near skyline: bass on the left to treble on the right.
    float i1 = floor(a * 14.0);
    float hb = motionHash(vec2(i1, 7.0));
    float band = clamp(0.5 + (i1 + 0.5) / 14.0 * 0.85, 0.0, 1.0);
    float h1 = (0.012 + 0.03 * hb + 0.12 * aband(band) * (0.6 + 0.4 * hb)) * part;
    if (e < h1 && e > -0.02) {
        col = vec3(0.01, 0.008, 0.02);
        vec2 wv = vec2(a * 14.0 * 7.0, e * 150.0);
        vec2 wc = floor(wv);
        float lit = step(0.58, motionHash(wc + i1));
        vec2 wf = abs(fract(wv) - 0.5);
        vec3 wcol = fract(motionHash(wc) * 7.0) > 0.7 ? pal(0.45) : pal(0.9);
        col += wcol * lit * smoothstep(0.32, 0.18, max(wf.x, wf.y)) * (0.25 + 0.45 * mid);
    }
    return col;
}

/**
 * A filmic shoulder before the grade: below the knee the picture is untouched,
 * above it highlights roll off smoothly toward white instead of clipping, so a
 * swell of light stays a swell. Continuous in value and slope at the knee.
 */
vec3 shoulder(vec3 c) {
    vec3 over = max(c - 0.6, 0.0);
    return min(c, 0.6) + over / (1.0 + over * 2.5);
}

/** A fraction of one 8-bit step of noise, so dark gradients do not band. */
float dither() {
    return (motionHash(gl_FragCoord.xy + 0.5) - 0.5) / 255.0;
}

/**
 * Where a finger rests the head turns toward it: x yaws, y pitches, scaled by
 * how firmly it is held, so a release glides the view home. Exactly zero with
 * nothing touched, which leaves the frame bit-identical.
 */
vec2 touchLook() {
    if (touchIdle()) return vec2(0.0);
    return touchAnchor() * touchStrength();
}

/** The lane the camera holds, drifting, easing across to the next now and then. */
float ndLane(float t) {
    return 1.5 + 3.0 * smoothstep(-0.35, 0.35, sin(t * 0.045)) + 0.3 * sin(t * 0.23);
}

void main() {
    vec2 uv = view();
    float bass = min(uBassSmooth, 1.3);
    float mid = min(uMidSmooth, 1.3);
    float treb = min(uTrebleSmooth, 1.3);
    float beatEnv = clamp(uBeat, 0.0, 1.0) * clamp(uBeatResponse, 0.0, 2.0);

    vec3 rdl = normalize(vec3(uv, ND_FOCAL));
    vec2 look = touchLook();
    if (look != vec2(0.0)) rdl = rotY(look.x * 0.6) * rotX(-look.y * 0.4) * rdl;
    vec3 camOff = vec3(0.0);
    cameraFlight(camOff, rdl);

    // Distance driven, INTEGRATED: the Speed control through uTime, loudness through uFlowPhase.
    float z = uTime * 6.0 + uFlowPhase * 22.0 + camOff.z;
    float x = ndLane(uTime);
    vec3 ro = vec3(x, 1.25, z);
    float drift = ndLane(uTime + 0.8) - x;
    float yaw = drift * 0.06 + 0.03 * sin(uTime * 0.07);
    float roll = -drift * 0.03;
    vec3 rd = rotY(yaw) * rotX(-0.1) * rotZ(roll) * rdl;

    vec3 lampCol = ndLampColour();
    float halo = 0.45 * (1.0 + 0.6 * uSpike);
    float tRoad = rd.y < 0.0 ? -ro.y / rd.y : 1e4;
    float tEnd = min(tRoad, ND_FAR);

    float budget = uSteps * (float(ND_MAX_STEPS) / 128.0);
    float t = 0.05;
    bool hit = false;
    vec3 glow = vec3(0.0);
    for (int i = 0; i < ND_MAX_STEPS; i++) {
        if (float(i) >= budget || t > tEnd) break;
        float d = ndMap(ro + rd * t);
        float eps = 0.002 + 0.001 * t;
        if (d < eps) {
            hit = true;
            break;
        }
        float adv = max(d, eps);
        // In proportion to the step taken, so the halo does not change with Detail.
        glow += lampCol * exp(-gLamp * 3.0) * min(adv, 1.0) * halo * exp(-t * 0.012);
        t += adv;
    }

    vec3 sky = ndSky(rd, bass, mid, beatEnv);
    vec3 col = sky;
    if (hit) {
        vec3 p = ro + rd * t;
        ndMap(p);
        bool lampHead = gLamp < 0.01 + 0.001 * t;
        vec3 n = ndNormal(p, 0.002 + 0.001 * t);
        if (lampHead) {
            col = lampCol * (n.y < -0.5 ? 2.2 : 0.4) * (1.0 + 0.4 * uSpike);
        } else {
            // Poles and barriers: dark metal and concrete, lit by the lamps and the headlights.
            vec3 base = abs(p.x) > 7.2 ? vec3(0.05, 0.05, 0.06) : vec3(0.09, 0.085, 0.09);
            col = base * (0.15 + ndLampLight(p) * max(0.2, n.y * 0.5 + 0.5));
            col += pal(0.05) * 0.06 * max(-n.z, 0.0);
        }
        col = mix(col, sky, 1.0 - exp(-t * 0.012));
    } else if (tRoad < ND_FAR) {
        vec3 p = ro + rd * tRoad;
        // The asphalt, wet: a little grain, the lamps' pools and the headlights.
        float grain = motionNoise(p.xz * 3.0);
        vec3 lit = ndLampLight(p);
        float dz = p.z - ro.z;
        float lateral = p.x - ro.x;
        float head = smoothstep(55.0, 4.0, dz) * smoothstep(0.0, 3.0, dz) * exp(-pow(lateral / (1.0 + dz * 0.07), 2.0));
        vec3 road = vec3(0.025, 0.024, 0.028) * (0.8 + 0.4 * grain);
        // A pixel's footprint across the road and along it.
        float fw = tRoad * 2.0 / (uResolution.y * ND_FOCAL);
        float fz = fw / max(-rd.y, 0.01);
        // Expansion joints across the road every few lengths, darker seams that stream past.
        float joint = smoothstep(fz * 0.5 + 0.03, 0.0, abs(fract(p.z / 12.0) - 0.5) - 0.46) * 0.5;
        col = road * (0.4 + lit * 2.5 + head * 7.0) * (1.0 - joint);

        // The markings, faded to their average once a dash is under a pixel.
        float dash = mix(step(fract(p.z / 6.0), 0.45), 0.45, smoothstep(1.0, 4.0, fz));
        float ax = abs(p.x);
        float lineW = 0.07 + fw;
        float lanes = smoothstep(lineW, lineW - fw, abs(ax - 3.0)) * dash;
        float edges = smoothstep(lineW, lineW - fw, abs(ax - 6.0));
        float centre = smoothstep(lineW, lineW - fw, abs(ax - 0.18));
        vec3 white = vec3(0.85, 0.85, 0.8);
        vec3 marks = white * (lanes + edges) + mix(pal(0.85), vec3(1.0, 0.8, 0.3), 0.5) * centre;
        float glint = 0.6 + 0.4 * treb * (0.5 + 0.5 * sin(p.z * 3.0 + uTime * 9.0));
        col += marks * (0.06 + lit * 0.5 + head * 1.6) * glint;
        // Cat's eyes along the lane lines, one every dash, catching the headlights.
        vec2 eye = vec2(abs(ax - 3.0), (fract(p.z / 6.0 + 0.25) - 0.5) * 6.0);
        float cat = smoothstep(0.09 + fw, 0.02, length(eye * vec2(1.0, 0.6))) * smoothstep(3.0, 1.0, fz);
        col += mix(pal(0.92), vec3(1.0, 0.9, 0.6), 0.5) * cat * (0.3 + head * 4.0) * (0.7 + 0.6 * treb);

        // The wet mirror: sky, sun and skyline, and the lamps' halos marched along the reflection.
        vec3 wn = normalize(vec3((grain - 0.5) * 0.03, 1.0, (motionNoise(p.xz * vec2(7.0, 0.8) + 9.0) - 0.5) * 0.14));
        vec3 rr = reflect(rd, wn);
        vec3 refl = ndSky(rr, bass, mid, beatEnv);
        float rBudget = uSteps * (float(ND_REFL_STEPS) / 128.0);
        float tr = 0.05;
        for (int i = 0; i < ND_REFL_STEPS; i++) {
            if (float(i) >= rBudget || tr > 60.0) break;
            float d = ndMap(p + rr * tr);
            float adv = max(d, 0.05);
            refl += lampCol * exp(-gLamp * 1.5) * min(adv, 1.5) * halo * 0.8;
            tr += adv;
        }
        float fres = 0.08 + 0.6 * pow(1.0 - max(-rd.y, 0.0), 5.0);
        col += refl * fres * (0.6 - 0.3 * grain);
        col = mix(col, sky, 1.0 - exp(-tRoad * 0.011 * xm(uXFog)));
    }
    col += glow;

    if (!touchIdle()) {
        col += pal(0.5 + 0.15 * sin(uTime * 0.05)) * min(touchWake(uv), 3.0) * 0.05;
    }
    col *= 0.75 + 0.25 * smoothstep(2.2, 0.5, length(uv));
    fragColor = vec4(grade(shoulder(col)) + dither(), 1.0);
}
