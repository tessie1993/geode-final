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

// Dune Sea: low crane shots over moonlit dunes, the wind lifting sand off the
// crests.
//
// THE DUNES are a heightfield: ridged noise stretched across the wind, so its
// crests run in long sharp lines, at two scales, the larger dunes carrying the
// smaller. The ray marches the height above the sand, stepping a fraction of
// it (the slope never exceeds about 2), and the moon casts soft shadows: a
// short march toward it keeps the narrowest clearance it finds over the
// distance it has travelled, which is the penumbra.
//
// THE SAND'S SURFACE is shading only, too fine to march: wind ripples across
// the slopes, fine glints that catch the moon, and streaks of blown sand
// running along the wind, thickest on the crests where the wind lifts it.
//
// THE CRANE. The camera drifts forward while it rises from just over the sand
// to a few metres up and sinks again on a slow wave, tilting down as it
// rises, and pans slowly across the dunes. Its low point clears the sand
// under it and just ahead (a smooth maximum, so the move has no kink).
//
// AUDIO. Energy sets the drift speed (uFlowPhase, integrated: it eases).
// Bass sends slow ripples across the sand: the wind ripples deepen and roll
// along with it. A beat sends a ring of ripple out across the dunes from
// beneath the camera (uBeatPhase is how far it has spread, the envelope how
// deep): a ripple of shading, never a flash. Mids thicken the low haze;
// treble the glints.

#define DS_MAX_STEPS 96
#define DS_SHADOW_STEPS 14
#define DS_FOCAL 1.3
#define DS_FAR 80.0

const vec2 DS_WIND = vec2(0.8, 0.6);
const vec3 DS_MOON = vec3(-0.1925, 0.1925, 0.9623);

float dsRidge(vec2 p) {
    return 1.0 - abs(2.0 * motionNoise(p) - 1.0);
}

float dsHeight(vec2 xz) {
    // Stretched across the wind, so the crests run in long lines.
    vec2 q = vec2(dot(xz, DS_WIND), dot(xz, vec2(-DS_WIND.y, DS_WIND.x)));
    float big = pow(dsRidge(q * vec2(0.07, 0.024)), 2.2) * 2.8;
    float small = pow(dsRidge(q * vec2(0.19, 0.08) + 7.0), 1.8) * 0.55;
    return big + small * (0.4 + 0.6 * big / 2.8);
}

vec3 dsNormal(vec2 xz, float eps) {
    float hx = dsHeight(xz + vec2(eps, 0.0)) - dsHeight(xz - vec2(eps, 0.0));
    float hz = dsHeight(xz + vec2(0.0, eps)) - dsHeight(xz - vec2(0.0, eps));
    return normalize(vec3(-hx, 2.0 * eps, -hz));
}

/** Soft shadow toward the moon: 1 lit, 0 shadowed, the penumbra from the narrowest clearance. */
float dsShadow(vec3 p) {
    float lit = 1.0;
    float t = 0.15;
    for (int i = 0; i < DS_SHADOW_STEPS; i++) {
        vec3 q = p + DS_MOON * t;
        float h = q.y - dsHeight(q.xz);
        lit = min(lit, 6.0 * h / t);
        if (lit < 0.0 || q.y > 4.0) break;
        t += max(h * 0.6, 0.25);
    }
    return clamp(lit, 0.0, 1.0);
}

vec3 dsSky(vec3 rd, float treb) {
    float h = max(rd.y, 0.0);
    vec3 col = mix(pal(0.36) * 0.12 + vec3(0.03, 0.04, 0.07), vec3(0.005, 0.008, 0.025), smoothstep(0.0, 0.6, h));
    vec2 sp = vec2(atan(rd.x, rd.z) * 70.0, rd.y * 70.0);
    vec2 sc = floor(sp);
    float st = motionHash(sc + 5.5);
    float twinkle = 0.75 + 0.25 * sin(uTime * (1.5 + 4.0 * st) + st * 40.0) * treb;
    col += vec3(0.85, 0.9, 1.0) * step(0.92, st) * smoothstep(0.12, 0.0, length(fract(sp) - 0.5)) * smoothstep(0.02, 0.15, h) * twinkle * 0.8;
    float m = max(dot(rd, DS_MOON), 0.0);
    vec3 moon = mix(pal(0.42), vec3(0.92, 0.95, 1.0), 0.75);
    col += moon * (smoothstep(0.9990, 0.9994, m) * 1.4 + pow(m, 200.0) * 0.4 + pow(m, 12.0) * 0.12);
    // The moon-glow along the horizon beneath it.
    col += moon * 0.06 * exp(-h * 10.0) * (0.4 + 0.6 * pow(m, 3.0));
    return col;
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
    vec2 uv = view();
    float bass = min(uBassSmooth, 1.3);
    float mid = min(uMidSmooth, 1.3);
    float treb = min(uTrebleSmooth, 1.3);
    float beatEnv = clamp(uBeat, 0.0, 1.0) * clamp(uBeatResponse, 0.0, 2.0);

    vec3 rdl = normalize(vec3(uv, DS_FOCAL));
    vec2 look = touchLook();
    if (look != vec2(0.0)) rdl = rotY(look.x * 0.6) * rotX(-look.y * 0.4) * rdl;
    vec3 camOff = vec3(0.0);
    cameraFlight(camOff, rdl);

    // The drift, INTEGRATED: the Speed control through uTime, loudness through uFlowPhase.
    float z = uTime * 0.5 + uFlowPhase * 2.2 + camOff.z;
    vec2 xz = vec2(4.0 * sin(z * 0.02), z);
    // The crane: low over the sand to a few metres up, on a slow wave.
    float crane = smoothstep(-0.7, 0.7, sin(uTime * 0.045 + 0.5));
    float ground = smax(smax(dsHeight(xz), dsHeight(xz + vec2(0.0, 2.0)), 0.4), dsHeight(xz + vec2(0.0, 4.5)), 0.4);
    vec3 ro = vec3(xz.x, ground + 0.55 + 3.2 * crane, xz.y);
    float yaw = 0.4 * sin(uTime * 0.03);
    float pitch = 0.06 + 0.2 * crane;
    vec3 rd = rotY(yaw) * rotX(pitch) * rdl;

    float budget = uSteps * (float(DS_MAX_STEPS) / 128.0);
    float t = 0.05;
    bool hit = false;
    for (int i = 0; i < DS_MAX_STEPS; i++) {
        if (float(i) >= budget || t > DS_FAR) break;
        vec3 p = ro + rd * t;
        float h = p.y - dsHeight(p.xz);
        float eps = 0.002 + 0.0015 * t;
        if (h < eps) {
            hit = true;
            break;
        }
        // Climbing rays leave the dunes behind: nothing is higher than 3.4.
        if (rd.y > 0.0 && p.y > 3.6) break;
        t += max(h * 0.45, eps);
    }

    vec3 sky = dsSky(rd, treb);
    vec3 horizon = dsSky(normalize(vec3(rd.x, 0.02, rd.z)), 0.0);
    vec3 col = sky;
    if (hit) {
        vec3 p = ro + rd * t;
        vec3 n = dsNormal(p.xz, 0.02 + 0.003 * t);
        // Wind ripples, shading only: bass deepens them and rolls them along;
        // the beat's ring ripples out from beneath the camera.
        float along = dot(p.xz, DS_WIND);
        float fade = exp(-t * 0.06);
        float ripple = sin(along * 9.0 + 2.5 * motionNoise(p.xz * 0.6) - uTime * (0.4 + 0.6 * bass));
        float ring = length(p.xz - ro.xz) - uBeatPhase * 22.0;
        float wave = beatEnv * beatEnv * exp(-ring * ring * 0.3) * sin(ring * 6.0);
        vec2 tilt = DS_WIND * (ripple * (0.05 + 0.16 * bass) + wave * 0.35) * fade;
        n = normalize(n + vec3(tilt.x, 0.0, tilt.y));
        float shadow = dsShadow(p + n * 0.02);
        float diffuse = max(dot(n, DS_MOON), 0.0);
        vec3 moonCol = mix(pal(0.42), vec3(0.8, 0.88, 1.0), 0.7);
        vec3 sand = mix(pal(0.9), vec3(0.95, 0.78, 0.58), 0.55);
        vec3 skyLight = pal(0.36) * 0.08 + vec3(0.02, 0.03, 0.06);
        // Rim light where a crest stands against the moon-glow behind it.
        float rim = pow(1.0 - max(dot(n, -rd), 0.0), 4.0) * max(dot(rd, DS_MOON), 0.0);
        col = sand * (skyLight * (0.5 + 0.5 * n.y) + moonCol * diffuse * shadow * 1.4) + moonCol * rim * 0.25;
        // Moon glints on the grains.
        vec2 gp = p.xz * 40.0;
        vec2 g = floor(gp);
        float grain = smoothstep(0.3, 0.05, length(fract(gp) - 0.5));
        float glint = step(0.985, motionHash(g)) * grain * pow(max(dot(reflect(rd, n), DS_MOON), 0.0), 8.0) * shadow * fade;
        col += moonCol * glint * (0.4 + 1.2 * treb);
        // Blown sand streaking along the wind, thickest on the crests.
        float crest = smoothstep(1.4, 2.4, p.y);
        float streak = smoothstep(0.62, 0.95, motionNoise(vec2(dot(p.xz, vec2(-DS_WIND.y, DS_WIND.x)) * 5.0, along * 0.35 - uTime * 2.5)));
        col += moonCol * streak * (0.04 + 0.12 * crest) * fade * (0.5 + 0.5 * shadow);
        col = mix(col, horizon, 1.0 - exp(-t * 0.025 * xm(uXFog)));
    }
    // A low haze over the sand, catching the moon, thicker with the mids.
    float low = exp(-max(rd.y + 0.02, 0.0) * 14.0);
    col += mix(pal(0.42), vec3(0.8, 0.88, 1.0), 0.7) * low * (0.02 + 0.04 * mid) * (0.5 + 0.5 * pow(max(dot(rd, DS_MOON), 0.0), 3.0));

    if (!touchIdle()) {
        col += pal(0.5 + 0.15 * sin(uTime * 0.05)) * min(touchWake(uv), 3.0) * 0.05;
    }
    col *= 0.72 + 0.28 * smoothstep(2.2, 0.5, length(uv));
    fragColor = vec4(grade(col), 1.0);
}
