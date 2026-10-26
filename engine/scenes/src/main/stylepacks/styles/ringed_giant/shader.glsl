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

// Ringed Giant: a slow flyby of a giant planet that skims through its rings.
//
// NOTHING HERE MARCHES. The planet is a sphere and the rings a disc in its
// equatorial plane, both met in closed form, so every pixel costs the same
// handful of intersections and the Style can draw at a higher scale than the
// marched ones.
//
//   - The planet is banded by latitude, the bands wavered by a noise in
//     longitude that drifts as it turns, with a storm oval in the south. The
//     terminator is soft, the lit limb carries a thin atmosphere, and the
//     rings throw their shadow across it: the ray from each surface point to
//     the sun is met with the ring plane and dimmed by the ring there.
//   - The rings are a radial density: ringlets at several scales, a dark
//     division and a thin outer gap, fine grain, and clumps along the orbit.
//     Seen from the sunlit face they shine by reflection; from the dark face
//     they glow only where they are thin enough to let light through. The
//     planet's shadow falls across them (the ray from a ring point to the sun
//     is met with the sphere). Ringlets finer than a pixel are faded by the
//     ray's footprint, so the rings never shimmer into moire as they recede.
//   - Near the ring plane, four thin slabs of chunks above and below it come
//     into view: ring particles that stream past as the camera skims through.
//
// THE FLYBY. The camera orbits the planet slowly at a radius inside the
// rings' span, rising and sinking through the ring plane as it goes, and
// looks at a point between the planet and the way ahead, so the planet
// sweeps across the frame and the rings turn from a lit sheet to a thin line
// to their dark side.
//
// AUDIO. Energy eases the flyby (uFlowPhase). Mids shimmer the ring bands
// with a ripple that runs outward through them; a beat sends a brighter
// density wave out from the inner edge (uBeatPhase is how far it has gone);
// bass lights aurorae round the planet's poles; treble sparkles the chunks.

#define RG_FOCAL 1.4
#define RG_R 10.0
#define RG_IN 13.0
#define RG_OUT 24.0

/** A ringlet of frequency f at x, faded once it is finer than the footprint dx. */
float rgBand(float x, float f, float dx) {
    return sin(x * f) * clamp(1.0 - f * dx * 0.5, 0.0, 1.0);
}

/** The rings' density at radius r, 0..1, with dx the footprint in the same 0..1 span. */
float rgRing(float r, float dx) {
    float x = (r - RG_IN) / (RG_OUT - RG_IN);
    if (x < 0.0 || x > 1.0) return 0.0;
    float d = 0.55 + 0.22 * rgBand(x, 31.0, dx) + 0.14 * rgBand(x + 0.13, 97.0, dx) + 0.09 * rgBand(x + 0.41, 233.0, dx);
    float grain = motionNoise(vec2(x * 300.0, 0.5));
    d *= 1.0 - 0.35 * grain * clamp(1.0 - 300.0 * dx * 0.3, 0.0, 1.0);
    // The faint inner ring, the dark division and a thin outer gap.
    d *= 0.3 + 0.7 * smoothstep(0.05, 0.28, x);
    d *= smoothstep(0.012, 0.035, abs(x - 0.62));
    d *= smoothstep(0.003, 0.012, abs(x - 0.88));
    d *= smoothstep(0.0, 0.01, x) * smoothstep(1.0, 0.975, x);
    return clamp(d, 0.0, 1.0);
}

/** Distance along the ray to the planet, or -1. */
float rgSphere(vec3 ro, vec3 rd) {
    float b = dot(ro, rd);
    float c = dot(ro, ro) - RG_R * RG_R;
    float h = b * b - c;
    if (h < 0.0) return -1.0;
    float t = -b - sqrt(h);
    return t > 0.0 ? t : -1.0;
}

/** 1 in sunlight, 0 in the planet's shadow, softened at the edge. */
float rgPlanetShadow(vec3 q, vec3 sun) {
    float b = dot(q, sun);
    if (b > 0.0) return 1.0;
    float miss = sqrt(max(dot(q, q) - b * b, 0.0));
    return smoothstep(RG_R * 0.97, RG_R * 1.03, miss);
}

vec3 rgStars(vec3 rd) {
    vec2 sp = vec2(atan(rd.x, rd.z) * 70.0, asin(clamp(rd.y, -1.0, 1.0)) * 70.0);
    vec2 sc = floor(sp);
    float s = motionHash(sc + 9.1);
    vec2 sf = fract(sp) - 0.5 - (vec2(motionHash(sc + 2.3), motionHash(sc + 5.9)) - 0.5) * 0.6;
    vec3 col = vec3(0.85, 0.9, 1.0) * step(0.93, s) * smoothstep(0.13, 0.0, length(sf)) * (0.4 + 0.6 * fract(s * 17.0));
    // A faint band of distant stars and dust across the sky.
    float band = exp(-pow(dot(rd, normalize(vec3(0.3, 1.0, -0.4))) * 3.0, 2.0));
    col += pal(0.36) * 0.03 * band * (0.6 + 0.4 * motionNoise(rd.xz * 6.0 + rd.y * 3.0));
    return col;
}

vec3 rgPlanet(vec3 p, vec3 rd, vec3 sun, float bass) {
    vec3 n = p / RG_R;
    float lat = n.y;
    float lon = atan(n.z, n.x) + uTime * 0.012;
    float w = lat + 0.04 * (motionNoise(vec2(lon * 3.0, lat * 9.0)) - 0.5) + 0.012 * (motionNoise(vec2(lon * 14.0, lat * 60.0)) - 0.5)
        + 0.008 * sin(lon * 9.0 + lat * 40.0);
    float bands = 0.5 + 0.5 * sin(w * 18.0);
    float fine = 0.5 + 0.5 * sin(w * 57.0 + 1.3);
    vec3 cream = vec3(0.85, 0.78, 0.66);
    vec3 col = mix(pal(0.9), pal(0.97), bands);
    col = mix(col, cream, 0.35 + 0.25 * fine);
    // The storm: an oval in the southern belt, with a pale rim.
    vec2 so = vec2((mod(lon + 1.0, 6.2831853) - 3.14159) * 0.55, (lat + 0.38) * 2.6);
    float storm = length(so);
    col = mix(col, pal(0.02) * 0.9, smoothstep(0.16, 0.09, storm));
    col = mix(col, cream, smoothstep(0.03, 0.0, abs(storm - 0.17)) * 0.6);

    float diff = dot(n, sun);
    float light = smoothstep(-0.12, 0.35, diff) * (0.25 + 0.75 * max(diff, 0.0));
    // The rings' shadow on the cloud tops.
    if (abs(sun.y) > 1e-3) {
        float ts = -p.y / sun.y;
        if (ts > 0.0) light *= 1.0 - 0.75 * rgRing(length((p + sun * ts).xz), 0.0);
    }
    col *= light;
    // Ringshine on the night side, and the lit limb's thin atmosphere.
    col += cream * 0.02 * (1.0 - smoothstep(-0.2, 0.1, diff));
    float limb = pow(1.0 - max(dot(n, -rd), 0.0), 3.0);
    col += pal(0.42) * limb * smoothstep(-0.25, 0.4, diff) * 0.6;
    // Aurorae round both poles, lit by the bass.
    float ring = exp(-pow((abs(lat) - 0.93) * 28.0, 2.0));
    col += pal(0.6) * ring * (0.04 + 0.5 * bass) * (0.4 + 0.6 * (1.0 - smoothstep(-0.3, 0.3, diff)));
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

/** The camera on the flyby at phase f (radians of orbit). */
vec3 rgCamera(float f) {
    float r = 21.5 + 3.5 * sin(f * 0.7);
    float y = 2.6 * sin(f * 0.9);
    return vec3(r * cos(f), y, r * sin(f));
}

void main() {
    vec2 uv = view();
    float bass = min(uBassSmooth, 1.3);
    float mid = min(uMidSmooth, 1.3);
    float treb = min(uTrebleSmooth, 1.3);
    float beatEnv = clamp(uBeat, 0.0, 1.0) * clamp(uBeatResponse, 0.0, 2.0);

    vec3 rdl = normalize(vec3(uv, RG_FOCAL));
    vec2 look = touchLook();
    if (look != vec2(0.0)) rdl = rotY(look.x * 0.6) * rotX(-look.y * 0.4) * rdl;
    vec3 camOff = vec3(0.0);
    cameraFlight(camOff, rdl);

    // The orbit's phase, INTEGRATED (the Speed control through uTime, loudness
    // through uFlowPhase). Travel and push-in are world units, about a
    // twentieth of a radian at this radius.
    float f = uTime * 0.022 + uFlowPhase * 0.09 + camOff.z / 20.0;
    vec3 ro = rgCamera(f);
    vec3 ahead = rgCamera(f + 0.35);
    // The planet swings toward the middle of the frame and out again.
    vec3 target = mix(vec3(0.0), ahead, 0.55 + 0.2 * sin(f * 1.3)) + vec3(0.0, -1.0, 0.0);
    vec3 fw = normalize(target - ro);
    vec3 rt = normalize(cross(vec3(0.0, 1.0, 0.0), fw));
    vec3 up = cross(fw, rt);
    float roll = 0.18 * sin(f * 0.9 + 1.2);
    vec3 rgt = cos(roll) * rt + sin(roll) * up;
    vec3 upr = -sin(roll) * rt + cos(roll) * up;
    vec3 rd = mat3(rgt, upr, fw) * rdl;

    // The sun keeps a fixed bearing from the camera's side of the planet, a
    // little ahead of it, so the flyby always sees a gibbous world with its
    // terminator in view rather than spending half the orbit on the night side.
    vec3 sun = normalize(vec3(cos(f + 1.3), 0.36, sin(f + 1.3)));
    float tp = rgSphere(ro, rd);
    vec3 col = rgStars(rd);
    if (tp > 0.0) col = rgPlanet(ro + rd * tp, rd, sun, bass);

    // ---- the ring plane --------------------------------------------------
    if (abs(rd.y) > 1e-4) {
        float tr = -ro.y / rd.y;
        if (tr > 0.0 && (tp < 0.0 || tr < tp)) {
            vec3 q = ro + rd * tr;
            float r = length(q.xz);
            // The ray's footprint across the rings, in the density's 0..1 span.
            float dx = tr * (2.0 / uResolution.y) / (max(abs(rd.y), 0.03) * RG_FOCAL * (RG_OUT - RG_IN));
            float dens = rgRing(r, dx);
            if (dens > 0.0) {
                float x = (r - RG_IN) / (RG_OUT - RG_IN);
                float ang = atan(q.z, q.x);
                // Clumps along the orbit.
                dens *= 0.8 + 0.2 * motionNoise(vec2(x * 60.0, ang * 4.0 + uTime * 0.02));
                vec3 rc = mix(pal(0.9 + 0.16 * x + 0.05 * sin(x * 23.0)), vec3(0.92, 0.87, 0.78), 0.4) * 1.3;
                // Mids shimmer the bands: a ripple running outward through them.
                float shimmer = 1.0 + 0.3 * mid * sin(x * 70.0 - uTime * 2.2 + 3.0 * motionNoise(vec2(x * 9.0, 1.0)));
                // The beat's density wave, out from the inner edge.
                float wave = x - uBeatPhase * 1.2;
                shimmer += 0.6 * beatEnv * beatEnv * exp(-wave * wave * 60.0);
                bool litFace = (ro.y > 0.0) == (sun.y > 0.0);
                float shade = litFace ? 0.35 + 0.65 * abs(sun.y) * 2.0 : 0.0;
                // From the dark face, only light let through: brightest where the ring is thin.
                float through = (1.0 - dens) * 2.2 + pow(max(dot(rd, sun), 0.0), 6.0) * 1.5;
                float lightR = litFace ? shade : through * 0.45;
                lightR *= rgPlanetShadow(q, sun);
                vec3 ringCol = rc * lightR * shimmer;
                float alpha = clamp(dens * (litFace ? 1.0 : 0.85), 0.0, 1.0);
                col = mix(col, ringCol, alpha);
            }
        }
    }

    // ---- ring particles, when the camera is close to the plane -----------
    float near = exp(-abs(ro.y) * 0.9);
    if (near > 0.02) {
        for (int k = 0; k < 4; k++) {
            float ys = (float(k) - 1.5) * 0.22;
            float ts = (ys - ro.y) / rd.y;
            if (ts <= 0.0 || ts > 14.0 || (tp > 0.0 && ts > tp)) continue;
            vec3 q = ro + rd * ts;
            float dens = rgRing(length(q.xz), 0.0);
            vec2 g = q.xz * 2.0 + float(k) * 17.0;
            vec2 cell = floor(g);
            float h = motionHash(cell);
            vec2 fo = fract(g) - 0.5 - (vec2(motionHash(cell + 3.1), motionHash(cell + 7.3)) - 0.5) * 0.5;
            float size = (0.06 + 0.12 * fract(h * 13.0)) * clamp(xm(uXSize), 0.3, 1.4);
            float chunk = smoothstep(size, size * 0.35, length(fo)) * step(1.0 - 0.7 * dens, h);
            float lit = 0.35 + 0.65 * rgPlanetShadow(q, sun);
            vec3 cc = mix(pal(0.93), vec3(0.95, 0.9, 0.82), 0.6) * lit * (0.6 + 0.6 * treb * fract(h * 29.0));
            // Faded right at the lens, where a chunk would be a blur the size of the frame.
            col = mix(col, cc, chunk * near * exp(-ts * 0.18) * smoothstep(0.5, 2.0, ts) * 0.9);
        }
    }

    if (!touchIdle()) {
        col += pal(0.5 + 0.15 * sin(uTime * 0.05)) * min(touchWake(uv), 3.0) * 0.05;
    }
    col *= 0.78 + 0.22 * smoothstep(2.2, 0.5, length(uv));
    fragColor = vec4(grade(shoulder(col)) + dither(), 1.0);
}
