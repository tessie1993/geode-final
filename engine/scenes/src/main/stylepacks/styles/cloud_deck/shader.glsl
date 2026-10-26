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

// Cloud Deck: flying just above a sea of clouds at golden hour.
//
// NO VOLUME MARCH. The cloud sea is CD_DECKS thin horizontal decks stacked
// one under another, each met in closed form and given its own coverage from
// a cheap noise, then composited front to back. Nearer decks slide past
// faster than deeper ones, and that parallax is what reads as depth; a ray
// costs a fixed handful of noise reads however far it looks.
//
//   - Coverage thickens with depth, so the top decks are scattered tufts and
//     the bottom one is a solid floor: the gaps in the top show the deeper
//     billows, darker and bluer, under them.
//   - Each deck is lit from the low sun by reading its own coverage a step
//     toward the sun: where the cloud thins toward the light its edge burns
//     gold (the silver lining), where it thickens it falls into shade.
//   - Every deck fades into the horizon haze with distance, and a thin high
//     veil above the camera gives parallax overhead too.
//
// THE FLIGHT skims the deck, climbing and diving on slow incommensurate
// waves, pitching with the climb, and banking through long gentle turns.
//
// AUDIO. Energy sets the airspeed (uFlowPhase, integrated: it eases). Bass
// heaves the billows, slowly; mids broaden the sun's glare; treble burns the
// silver linings brighter. A beat swells the sun (uSpike, with a rise) and
// rolls a band of brighter light across the cloud tops away from the camera
// (uBeatPhase is how far it has gone): a swell, never a flash.

#define CD_DECKS 7
#define CD_FOCAL 1.3
#define CD_FAR 90.0

const float CD_TOP = -0.05;
const float CD_GAP = 0.16;

vec3 cdSun() {
    return normalize(vec3(-0.12, 0.08, 1.0));
}

float cdNoise(vec2 p) {
    return 0.5 * motionNoise(p) + 0.28 * motionNoise(p * 2.13 + 5.2) + 0.14 * motionNoise(p * 4.41 + 1.7)
        + 0.08 * motionNoise(p * 9.1 + 3.3);
}

/**
 * A deck's coverage at xz: thin tufts for the top deck k = 0, a closed floor for the last. The edge
 * softens with distance t, so far billows blur into a sheet instead of shimmering into ribbons.
 */
float cdCover(vec2 xz, float k, float t) {
    vec2 drift = vec2(uTime * 0.05, uTime * 0.02) * (1.0 + 0.2 * k);
    float n = cdNoise(xz * 0.3 + drift + k * 3.7);
    float threshold = mix(0.62, 0.2, k / float(CD_DECKS - 1));
    return smoothstep(threshold, threshold + 0.18 + t * 0.006, n);
}

vec3 cdSky(vec3 rd, vec3 sun, float mid) {
    float h = rd.y;
    vec3 zenith = pal(0.38) * 0.25 + vec3(0.05, 0.08, 0.16);
    vec3 horizon = mix(pal(0.92), vec3(1.0, 0.75, 0.5), 0.4) * 0.95;
    vec3 col = mix(horizon, zenith, smoothstep(-0.02, 0.5, h));
    float s = max(dot(rd, sun), 0.0);
    col += mix(pal(0.92), vec3(1.0, 0.85, 0.6), 0.5) * (pow(s, 4.0) * (0.25 + 0.25 * mid) + pow(s, 32.0) * 0.5);
    col += vec3(1.0, 0.95, 0.85) * smoothstep(0.9985, 0.9993, s) * (1.0 + 0.3 * uSpike);
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

float cdHeight(float z) {
    return 0.8 + 0.45 * sin(z * 0.025) + 0.15 * sin(z * 0.07 + 1.0);
}

void main() {
    vec2 uv = view();
    float bass = min(uBassSmooth, 1.3);
    float mid = min(uMidSmooth, 1.3);
    float treb = min(uTrebleSmooth, 1.3);
    float beatEnv = clamp(uBeat, 0.0, 1.0) * clamp(uBeatResponse, 0.0, 2.0);

    vec3 rdl = normalize(vec3(uv, CD_FOCAL));
    vec2 look = touchLook();
    if (look != vec2(0.0)) rdl = rotY(look.x * 0.6) * rotX(-look.y * 0.4) * rdl;
    vec3 camOff = vec3(0.0);
    cameraFlight(camOff, rdl);

    // Airspeed, INTEGRATED: the Speed control through uTime, loudness through uFlowPhase.
    float z = uTime * 1.6 + uFlowPhase * 6.0 + camOff.z;
    float turn = sin(z * 0.012);
    vec3 ro = vec3(18.0 * sin(z * 0.012 - 1.0), cdHeight(z), z);
    float climb = (cdHeight(z + 2.0) - cdHeight(z - 2.0)) / 4.0;
    float yaw = 0.22 * turn;
    float pitch = 0.1 - climb * 2.5;
    float roll = 0.12 * cos(z * 0.012);
    vec3 rd = rotY(yaw) * rotX(pitch) * rotZ(roll) * rdl;

    vec3 sun = cdSun();
    vec3 sky = cdSky(rd, sun, mid);
    vec3 haze = cdSky(normalize(vec3(rd.x, 0.0, rd.z)), sun, mid);
    vec3 col = vec3(0.0);
    float trans = 1.0;

    // The high veil, above the camera.
    if (rd.y > 0.0) {
        float tv = (6.0 - ro.y) / rd.y;
        vec2 q = (ro + rd * tv).xz;
        float veil = smoothstep(0.45, 0.85, cdNoise(q * vec2(0.05, 0.12) + uTime * 0.01)) * exp(-tv * 0.01);
        sky = mix(sky, mix(pal(0.92), vec3(1.0, 0.9, 0.8), 0.6) * 0.8, veil * 0.35);
    } else {
        // ---- the decks, top first -----------------------------------------
        for (int k = 0; k < CD_DECKS; k++) {
            if (trans < 0.02) break;
            float fk = float(k);
            float y = CD_TOP - fk * CD_GAP;
            float t = (y - ro.y) / rd.y;
            if (t <= 0.0 || t > CD_FAR) continue;
            vec3 p = ro + rd * t;
            // The billows heave slowly with the bass.
            vec2 xz = p.xz + vec2(0.0, 0.25 * bass * sin(p.x * 0.15 + uTime * 0.4));
            float cover = cdCover(xz, fk, t);
            if (cover <= 0.001) continue;
            // Lit by reading the coverage a step toward the sun.
            float toward = cdCover(xz + normalize(sun.xz) * 0.7, fk, t);
            float edge = clamp(cover - toward, 0.0, 1.0);
            float depth = fk / float(CD_DECKS - 1);
            vec3 lit = mix(pal(0.92), vec3(1.0, 0.88, 0.72), 0.55) * 1.25;
            vec3 shade = mix(pal(0.36), vec3(0.45, 0.42, 0.55), 0.5) * 0.45;
            vec3 c = mix(lit, shade, clamp(depth * 0.8 + (toward - cover) * 0.8 + 0.15, 0.0, 1.0));
            c += mix(pal(0.94), vec3(1.0, 0.8, 0.5), 0.4) * edge * (1.2 + 1.2 * treb) * (1.0 - depth * 0.6);
            // The beat's band of light, rolling away across the tops.
            float band = t * 0.04 - uBeatPhase * 1.4;
            c += lit * 0.35 * beatEnv * beatEnv * exp(-band * band * 30.0) * (1.0 - depth);
            c = mix(c, haze, 1.0 - exp(-t * 0.028 * xm(uXFog)));
            float a = cover * mix(0.55, 1.0, depth);
            col += trans * a * c;
            trans *= 1.0 - a;
        }
    }
    col += trans * sky;

    if (!touchIdle()) {
        col += pal(0.5 + 0.15 * sin(uTime * 0.05)) * min(touchWake(uv), 3.0) * 0.05;
    }
    col *= 0.78 + 0.22 * smoothstep(2.2, 0.5, length(uv));
    fragColor = vec4(grade(shoulder(col)) + dither(), 1.0);
}
