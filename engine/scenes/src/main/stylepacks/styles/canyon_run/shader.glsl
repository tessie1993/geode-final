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

// Canyon Run: a low, banking flight down a winding canyon of stratified rock.
//
// THE WORLD is one distance field. A channel winds along +z (its centre line
// is cnPath), its walls lean outward with height and jut in and out stratum by
// stratum, a rim caps them, and a sandy floor runs between with a thin stream
// down the middle. The strata are the colouring of the same height coordinate
// the ledges are cut from, so a band of colour is always a shelf of rock.
//
// THE FLIGHT rides the same centre line a little above the floor. It aims a
// few units ahead along the path rather than along its tangent, so the camera
// turns into a bend before it reaches it, and it rolls by the path's
// curvature: leaning into the turn is what makes a path read as flown.
//
// AUDIO. Energy sets the flight speed through uFlowPhase, which the CPU
// integrates, so the camera eases faster and slower and never jumps. Bass
// warms the seams between strata, from a cool ember toward a hot glow. A beat
// sends a band of light climbing the walls: uBeatPhase is how far it has
// climbed, the beat envelope how bright it is, so it swells and fades rather
// than flashing. Mids thicken the sunlit haze; treble glints on the stream.
//
// WHY THE ESTIMATE IS SAFE. The walls are a distance measured across the
// channel, sheared along the winding path (slope at most 0.48), cut into
// ledges (slope at most 0.75 in height) and eroded by two value noises (slope
// at most about 0.5). Together they bound the field's gradient by about 2,
// so the ray steps half its estimate (CN_STEP).

#define CN_MAX_STEPS 96
#define CN_FOCAL 1.25
#define CN_FAR 70.0
/** Strata per world unit of height. */
#define CN_STRATA 1.25

const float CN_STEP = 0.5;
const float CN_LEAD = 7.0;

float cnPath(float z) {
    return 3.2 * sin(z * 0.045) + 1.6 * sin(z * 0.11 + 1.3) + 0.7 * sin(z * 0.23 + 0.4);
}

/** cnPath's second derivative: the bend the camera leans into. */
float cnBend(float z) {
    return -0.00648 * sin(z * 0.045) - 0.01936 * sin(z * 0.11 + 1.3) - 0.03703 * sin(z * 0.23 + 0.4);
}

float cnRim(float z) {
    return 6.0 + 1.4 * sin(z * 0.06 + 2.0) + 0.6 * sin(z * 0.17);
}

/**
 * How far each stratum juts into the channel, 0..1. Constant through most of a
 * band and easing to the next band's value over its top half, so the ledges
 * are shelves with sloped lips rather than cliffs a ray could step through.
 */
float cnLedge(float y) {
    float b = y * CN_STRATA;
    float i = floor(b);
    float a = motionHash(vec2(i, 17.0));
    float c = motionHash(vec2(i + 1.0, 17.0));
    return mix(a, c, smoothstep(0.45, 1.0, b - i));
}

/** Half the channel's width at height y and depth z. */
float cnWidth(float y, float z) {
    float w = 1.65 + 0.3 * max(y, 0.0) - 0.22 * cnLedge(y);
    w += 0.35 * motionNoise(vec2(y * 0.8, z * 0.3));
    w += 0.18 * motionNoise(vec2(z * 0.7, y * 0.12 + 3.0));
    return w;
}

float cnFloor(vec2 xz, float across) {
    float h = 0.25 * motionNoise(xz * 0.45) + 0.1 * motionNoise(xz * 1.3 + 7.0);
    // The stream bed: a shallow trough along the centre line.
    return h - 0.14 * smoothstep(0.7, 0.0, abs(across));
}

/** The rock (x) and the ground (y), each a distance; the scene is their minimum. */
vec2 cnParts(vec3 p) {
    float across = p.x - cnPath(p.z);
    float rock = max(cnWidth(p.y, p.z) - abs(across), p.y - cnRim(p.z));
    float ground = p.y - cnFloor(p.xz, across);
    return vec2(rock, ground);
}

float cnMap(vec3 p) {
    vec2 d = cnParts(p);
    return min(d.x, d.y);
}

vec3 cnNormal(vec3 p, float eps) {
    vec2 k = vec2(1.0, -1.0);
    return normalize(k.xyy * cnMap(p + k.xyy * eps) + k.yyx * cnMap(p + k.yyx * eps)
                   + k.yxy * cnMap(p + k.yxy * eps) + k.xxx * cnMap(p + k.xxx * eps));
}

vec3 cnSky(vec3 rd, vec3 sun) {
    float h = clamp(rd.y, -0.2, 1.0);
    vec3 top = pal(0.36) * 0.22 + vec3(0.02, 0.025, 0.06);
    vec3 horizon = pal(0.92) * 0.8 + vec3(0.06, 0.04, 0.03);
    vec3 col = mix(horizon, top, pow(smoothstep(-0.05, 0.6, h), 0.6));
    float s = max(dot(rd, sun), 0.0);
    col += pal(0.95) * (pow(s, 6.0) * 0.45 + pow(s, 60.0) * 0.7);
    col += vec3(1.0, 0.93, 0.82) * smoothstep(0.9986, 0.9996, s);
    return col;
}

/** A camera basis looking along fwd, rolled by roll radians about it. */
mat3 cnBasis(vec3 fwd, float roll) {
    vec3 f = normalize(fwd);
    vec3 r = normalize(cross(vec3(0.0, 1.0, 0.0), f));
    vec3 u = cross(f, r);
    float c = cos(roll);
    float s = sin(roll);
    return mat3(c * r + s * u, -s * r + c * u, f);
}

/** Where the camera is at depth z along the flight. */
vec3 cnCamera(float z) {
    float y = 1.25 + 0.45 * sin(z * 0.085) + 0.25 * sin(z * 0.031 + 1.0);
    return vec3(cnPath(z) + 0.35 * sin(z * 0.05 + 0.7), y, z);
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

void main() {
    vec2 uv = cameraView();
    float bass = min(uBassSmooth, 1.3);
    float mid = min(uMidSmooth, 1.3);
    float treb = min(uTrebleSmooth, 1.3);
    float beatEnv = clamp(uBeat, 0.0, 1.0) * clamp(uBeatResponse, 0.0, 2.0);

    // The camera's own frame: the screen ray, turned by a finger and then by
    // the Camera (orbit turns it, push-in and travel move it down the path).
    vec3 rdl = normalize(vec3(uv, CN_FOCAL));
    vec2 look = touchLook();
    if (look != vec2(0.0)) rdl = rotY(look.x * 0.6) * rotX(-look.y * 0.4) * rdl;
    vec3 camOff = vec3(0.0);
    cameraFlight(camOff, rdl);

    // Distance flown, INTEGRATED: uTime carries the Speed control, uFlowPhase
    // the loudness, and neither can step backwards or jump.
    float z = uTime * 1.7 + uFlowPhase * 7.0 + camOff.z;
    vec3 ro = cnCamera(z);
    vec3 fwd = cnCamera(z + CN_LEAD) - ro + vec3(0.0, 0.3, 0.0);
    float roll = clamp(cnBend(z + 2.0) * 9.0 * xm(uXTurn), -0.5, 0.5);
    vec3 rd = cnBasis(fwd, roll) * rdl;

    vec3 sun = normalize(vec3(0.45, 0.3, 0.84));
    float budget = uSteps * (float(CN_MAX_STEPS) / 128.0);
    float t = 0.05;
    bool hit = false;
    for (int i = 0; i < CN_MAX_STEPS; i++) {
        if (float(i) >= budget || t > CN_FAR) break;
        float d = cnMap(ro + rd * t);
        float eps = 0.002 + 0.0015 * t;
        if (d < eps) {
            hit = true;
            break;
        }
        t += max(d * CN_STEP, eps);
    }
    // A ray that ran out of budget short of the far plane is shaded where it
    // stopped: fog has all but swallowed it there, and a hole of sky in a
    // wall would read far worse than rock a little out of place.
    if (!hit && t < CN_FAR) hit = true;

    vec3 sky = cnSky(rd, sun);
    vec3 col = sky;
    if (hit) {
        vec3 p = ro + rd * t;
        vec2 parts = cnParts(p);
        vec3 n = cnNormal(p, 0.002 + 0.0015 * t);
        float rim = cnRim(p.z);
        float across = p.x - cnPath(p.z);
        // Height in the canyon: deep rock sees less of the sky. Two probes of
        // the field along the normal darken the creases under each ledge.
        float probe = clamp(0.5 * (cnMap(p + n * 0.15) / 0.15 + cnMap(p + n * 0.45) / 0.45), 0.0, 1.0);
        float ao = (0.3 + 0.7 * smoothstep(-0.3, rim * 0.85, p.y)) * (0.45 + 0.55 * probe);
        float diffuse = max(dot(n, sun), 0.0);
        vec3 sunCol = mix(pal(0.93), vec3(1.0, 0.9, 0.75), 0.5);
        vec3 skyCol = pal(0.38) * 0.25 + 0.2;
        // Light bouncing between the walls, warm, so the shaded side is
        // dim rock rather than a black cut-out.
        vec3 bounce = pal(0.9) * 0.22;
        vec3 light = sunCol * diffuse * 1.3 + skyCol * (0.45 + 0.3 * n.y) + bounce;

        if (parts.x < parts.y) {
            // Rock. The strata coordinate dips and wavers a little so the
            // bands read as laid down, not ruled.
            float sv = p.y * CN_STRATA + 0.6 * sin(p.z * 0.03) + 0.35 * (motionNoise(vec2(p.z * 0.15, p.x * 0.2)) - 0.5);
            float band = floor(sv);
            float tone = motionHash(vec2(band, 3.7));
            vec3 rock = pal(0.86 + 0.12 * tone) * (0.45 + 0.55 * motionHash(vec2(band, 9.1)));
            rock = mix(rock, vec3(dot(rock, vec3(0.3333))), 0.2);
            // Fine grain across each band.
            rock *= 0.85 + 0.15 * motionNoise(vec2(p.z * 2.0, p.y * 9.0));
            col = rock * light * ao;
            // The sky's rim along edges that turn away from the eye.
            col += skyCol * pow(1.0 - max(dot(n, -rd), 0.0), 4.0) * 0.35 * ao;

            // The seams between strata glow, every other one or so. Bass
            // warms them: cool when quiet, a hot ember under a bass line.
            float f = fract(sv);
            float edge = min(f, 1.0 - f) / CN_STRATA;
            float seamBand = band + step(0.5, f);
            float lit = step(0.4, motionHash(vec2(seamBand, 5.3)));
            float seam = exp(-edge * 60.0) * lit;
            vec3 seamCol = mix(pal(0.42), pal(0.95), clamp(0.2 + 0.8 * bass, 0.0, 1.0));
            // The beat's band of light, climbing from the floor to the rim.
            float climb = p.y / max(rim, 1.0) - uBeatPhase * 1.5;
            float wave = beatEnv * beatEnv * exp(-climb * climb * 22.0);
            col += seamCol * seam * (0.22 + 1.2 * bass + 1.6 * wave);
            col += seamCol * wave * 0.12;
        } else {
            // The floor: pale sand, and the stream down the middle, which
            // mirrors the sky and glints with the treble.
            vec3 sand = mix(pal(0.9), vec3(0.75, 0.68, 0.58), 0.55) * 0.6;
            // Wind ripples across the sand, finer than the march resolves,
            // so they are shading only.
            float ripple = sin(p.z * 9.0 + p.x * 2.0 + 3.0 * motionNoise(p.xz * 0.8));
            sand *= 0.82 + 0.18 * ripple;
            col = sand * light * ao;
            float stream = smoothstep(0.3, 0.14, abs(across + 0.08 * sin(p.z * 0.9)));
            if (stream > 0.0) {
                vec2 q = p.xz * vec2(3.0, 1.2) + vec2(0.0, uTime * 0.8);
                vec3 wn = normalize(vec3(motionNoise(q) - 0.5, 4.0, motionNoise(q + 13.0) - 0.5));
                vec3 refl = cnSky(reflect(rd, wn), sun);
                float glint = pow(max(dot(reflect(rd, wn), sun), 0.0), 40.0);
                col = mix(col, refl * 0.7 + vec3(glint) * (0.2 + 0.8 * treb), stream);
            }
        }
        // Aerial haze toward the horizon colour of this direction.
        col = mix(col, sky * 0.9, 1.0 - exp(-t * 0.032 * xm(uXFog)));
    }
    // The sunlit dust between the walls, thicker with the mids.
    float toSun = max(dot(rd, sun), 0.0);
    float depth = hit ? t : CN_FAR;
    col += pal(0.93) * pow(toSun, 4.0) * (1.0 - exp(-depth * 0.03)) * (0.12 + 0.25 * mid);

    if (!touchIdle()) {
        col += pal(0.5 + 0.15 * sin(uTime * 0.05)) * min(touchWake(uv), 3.0) * 0.05;
    }
    col *= 0.72 + 0.28 * smoothstep(2.2, 0.5, length(uv));
    fragColor = vec4(grade(shoulder(col)) + dither(), 1.0);
}
