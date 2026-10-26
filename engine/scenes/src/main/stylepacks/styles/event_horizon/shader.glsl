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

// Event Horizon: an inspiral orbit around a black hole whose gravity bends the
// whole sky.
//
// Every ray is INTEGRATED, not faked. A photon around a mass with its
// Schwarzschild radius at 1 obeys, in flat Cartesian coordinates and an affine
// parameter,
//
//     d2x/dl2 = -1.5 * h^2 * x / r^5,      h = |x cross dx/dl| (conserved)
//
// which is the exact null-geodesic equation written so a plain step-and-bend
// loop can follow it. That one line is the whole Style: the starfield behind
// the hole is lensed into arcs and an Einstein ring, rays that come close
// enough fall in and leave the black shadow, and the thin accretion disc is
// seen over the top of the hole and under it at once, because the light from
// its far side is bent up and over. Nothing here pastes a lensing texture on a
// disc; the picture is what the rays do.
//
// THE CAMERA falls inward on a slow decaying orbit, tilting through the disc
// plane, and is slung back out to a wide orbit, over and over. As it falls in,
// its WORLD CLOCK slows - the disc turns more slowly and the sky drifts more
// slowly - which is time dilation used as story. The clock is integrated in
// closed form (see worldClock), never `uTime * rate`, so it can slow without
// ever jumping.
//
// ---- the music ---------------------------------------------------------------
//
// The passage's loudness (uSwell) deepens the inspiral; the spectrum lights the
// disc in rings, band k at radius k; the bass lifts the disc's emission a
// little, slewed, so the hole breathes and never flashes. Steps are cut by
// Detail (uSteps), and each step lengthens to keep the integration reaching
// the sky.

#define EH_TAU 6.2831853
/** Compile-time ceiling on the integration; the runtime budget is min(uSteps, this). */
#define EH_MAX_STEPS 64
/** The radius at which a ray is free of the hole and reads the sky. */
#define EH_ESCAPE 34.0
/** The disc: inner edge just outside the last stable orbit (3 Rs), outer edge. */
#define EH_DISC_IN 2.7
#define EH_DISC_OUT 11.0
/** One inspiral and slingshot, seconds. */
#define EH_ORBIT_PERIOD 72.0
/** How far the world clock slows at the bottom of the fall, 0..1. */
#define EH_DILATION 0.6
#define EH_FOCAL 1.55

const float EH_W = EH_TAU / EH_ORBIT_PERIOD;

/** 0 on the wide orbit, 1 at the bottom of the fall: the inspiral's shape. */
float fallPhase(float t) {
    return 0.5 - 0.5 * cos(EH_W * t);
}

/**
 * The integral of (1 - EH_DILATION * fallPhase(t)) dt, in closed form: a clock
 * whose rate dips as the camera falls in and recovers on the way out, and that
 * never stalls, reverses or jumps.
 */
float worldClock(float t) {
    return t - EH_DILATION * (0.5 * t - sin(EH_W * t) / (2.0 * EH_W));
}

vec3 starfield(vec3 d, float footprint, float drift) {
    d = rotY(drift) * d;
    // The galaxy: a band about a tilted great circle, mottled with dust.
    vec3 gn = normalize(vec3(0.25, 1.0, 0.35));
    float lat = dot(d, gn);
    float band = exp(-lat * lat * 14.0);
    float dust = fbm3(d * 3.2 + 4.0, 4);
    float lanes = smoothstep(0.35, 0.7, fbm3(d * 7.0 + 11.0, 3));
    vec3 sky = mix(pal(0.62), vec3(0.5, 0.55, 0.85), 0.5) * 0.05;
    sky += mix(pal(0.08), vec3(1.0, 0.85, 0.7), 0.45) * band * (0.25 + 0.5 * dust) * (1.0 - 0.6 * lanes);
    sky += pal(0.55) * 0.12 * smoothstep(0.45, 0.85, fbm3(d * 1.6 + 2.0, 3)) * (0.3 + band);
    // Stars: one candidate per cell of a grid on the direction, kept only when
    // the cell's hash says so. The disc of each widens with the pixel's angular
    // footprint and dims by the same area, so a star stretched by the lens
    // smears into an arc instead of aliasing into a twinkle.
    for (int layer = 0; layer < 2; layer++) {
        float scale = layer == 0 ? 70.0 : 150.0;
        vec3 q = d * scale;
        vec3 cell = floor(q);
        float h = hashCell(ivec3(cell) + ivec3(layer * 977));
        if (h > 0.86) {
            vec3 jitter = vec3(hashCell(ivec3(cell) + ivec3(5, 0, 0)), hashCell(ivec3(cell) + ivec3(0, 9, 0)),
                               hashCell(ivec3(cell) + ivec3(0, 0, 13)));
            vec3 c = cell + 0.15 + 0.7 * jitter;
            float dist = length(q - c) / scale;
            float size = max(0.0016, footprint);
            float energy = (0.0016 * 0.0016) / (size * size);
            float mag = pow((h - 0.86) / 0.14, 2.0) * (layer == 0 ? 3.0 : 1.4);
            vec3 tint = mix(vec3(0.75, 0.85, 1.0), vec3(1.0, 0.85, 0.65), jitter.x);
            sky += tint * mag * energy * exp(-dist * dist / (size * size));
        }
    }
    return sky;
}

/** The disc's light at radius r and angle a, before Doppler and redshift. */
vec3 discEmission(float r, float a, float clock) {
    float x = (r - EH_DISC_IN) / (EH_DISC_OUT - EH_DISC_IN);
    // Hotter inside: an r^-2 profile with soft edges at both rims.
    float profile = pow(EH_DISC_IN / r, 2.2) * smoothstep(0.0, 0.06, x) * smoothstep(1.0, 0.55, x);
    // Differential rotation: inner rings lap the outer ones, so the dust
    // shears into streaks the longer it orbits.
    float spinA = a - clock * 0.9 * pow(EH_DISC_IN / r, 1.5);
    vec3 q = vec3(cos(spinA) * r, sin(spinA) * r, r * 1.7);
    float turb = fbm3(q * 0.9, 3);
    float lanes = 0.55 + 0.45 * sin(r * 5.5 + 3.0 * turb);
    // The spectrum, ring by ring.
    float ring = aband(clamp(x, 0.0, 1.0) * 0.85 + 0.02);
    float level = profile * (0.25 + 1.1 * turb * turb * lanes) * (0.8 + 0.35 * clamp(ring, 0.0, 1.2));
    vec3 hot = mix(vec3(1.0, 0.92, 0.78), pal(0.12), 0.2);
    vec3 warm = mix(vec3(1.0, 0.55, 0.16), pal(0.02), 0.3);
    vec3 cool = mix(vec3(0.55, 0.12, 0.08), pal(0.9), 0.35);
    vec3 tint = mix(hot, warm, smoothstep(0.0, 0.35, x));
    tint = mix(tint, cool, smoothstep(0.35, 1.0, x));
    return tint * level;
}

vec3 tonemap(vec3 c) {
    c = max(c, 0.0);
    return clamp(c * (2.51 * c + 0.03) / (c * (2.43 * c + 0.59) + 0.14), 0.0, 1.0);
}

void main() {
    vec2 uv = view();
    float bassA = clamp(uBassSmooth, 0.0, 1.3);

    // ---- the orbit -------------------------------------------------------------
    float fall = fallPhase(uTime);
    float radius = 17.0 - 7.5 * fall - 1.5 * clamp(uSwell, 0.0, 1.2);
    float around = uTime * 0.055 + 1.3;
    // Through the disc plane and out the other side, slowly.
    float incl = 0.1 + 0.3 * sin(uTime * 0.041);
    vec3 ro = radius * vec3(cos(around) * cos(incl), sin(incl), sin(around) * cos(incl));
    // Look a little ahead along the orbit, so the hole sits off centre and the
    // sky streams past it rather than around it.
    vec3 tangent = vec3(-sin(around), 0.0, cos(around));
    vec3 target = tangent * radius * 0.1;
    vec3 fwd = normalize(target - ro);
    vec3 right = normalize(cross(vec3(0.0, 1.0, 0.0), fwd));
    vec3 up = cross(fwd, right);
    // A slow bank with the orbit.
    float roll = 0.12 * sin(uTime * 0.029);
    vec2 lens = rot2(roll) * uv;
    if (!touchIdle()) {
        // The finger is a lens shift: the view swings toward it.
        lens -= touchAnchor() * (0.45 * touchStrength());
    }
    vec3 rd = normalize(lens.x * right + lens.y * up + EH_FOCAL * fwd);
    cameraOrbit(ro, rd);
    // Push-in can bring the eye near; never inside the photon sphere's reach.
    float rEye = length(ro);
    if (rEye < 3.0) ro *= 3.0 / rEye;

    float clock = worldClock(uTime);

    // ---- the integration -------------------------------------------------------
    float budget = clamp(uSteps, 16.0, float(EH_MAX_STEPS));
    float stepScale = float(EH_MAX_STEPS) / budget;
    vec3 p = ro;
    vec3 v = rd;
    vec3 hv = cross(p, v);
    float h2 = dot(hv, hv);
    vec3 col = vec3(0.0);
    float trans = 1.0;
    float glow = 0.0;
    bool captured = false;
    for (int i = 0; i < EH_MAX_STEPS; i++) {
        if (float(i) >= budget) break;
        float r = length(p);
        if (r < 1.0) {
            captured = true;
            break;
        }
        if (r > EH_ESCAPE && dot(p, v) > 0.0) break;
        // Short steps near the hole where the bending is strong, long ones far out.
        float dt = clamp(0.12 * r, 0.04, 2.5) * stepScale;
        vec3 accel = -1.5 * h2 * p / (r * r * r * r * r);
        vec3 prev = p;
        v += accel * dt;
        p += v * dt;
        // A haze of scattered disc light just off the plane.
        float rr0 = length(p.xz);
        if (rr0 > EH_DISC_IN * 0.9 && rr0 < EH_DISC_OUT) {
            glow += exp(-abs(p.y) * 3.0) * pow(EH_DISC_IN / rr0, 2.0) * dt * 0.03;
        }
        // Crossed the disc plane on this step: shade the crossing.
        if (prev.y * p.y < 0.0 && trans > 0.02) {
            float f = prev.y / (prev.y - p.y);
            vec3 x = mix(prev, p, f);
            float rr = length(x.xz);
            if (rr > EH_DISC_IN && rr < EH_DISC_OUT) {
                vec3 emit = discEmission(rr, atan(x.z, x.x), clock);
                // Relativistic beaming and gravitational redshift: the side
                // swinging toward the eye is brighter and bluer.
                vec3 flow = normalize(vec3(-x.z, 0.0, x.x));
                float beta = sqrt(0.5 / rr);
                float gamma = 1.0 / sqrt(1.0 - beta * beta);
                float doppler = 1.0 / (gamma * (1.0 - beta * dot(flow, -normalize(v))));
                float g = doppler * sqrt(1.0 - 1.0 / rr);
                float boost = clamp(pow(g, 2.6), 0.15, 3.0);
                emit *= boost * (1.0 + 0.22 * bassA);
                emit = mix(emit, emit.zyx * vec3(0.7, 0.9, 1.4), clamp((g - 1.0) * 0.6, 0.0, 0.35));
                float alpha = 0.92 * smoothstep(EH_DISC_OUT, EH_DISC_OUT * 0.7, rr);
                col += trans * emit * 1.3;
                trans *= 1.0 - alpha;
            }
        }
    }

    // What is left of the ray reads the sky in the direction it finally took.
    // The pixel's angular footprint on the sky, from the bent direction itself;
    // taken outside the branch, where derivatives are defined for every pixel.
    vec3 dir = normalize(v);
    float footprint = clamp(length(fwidth(dir)) * 0.6, 0.0, 0.08);
    if (!captured) {
        col += trans * starfield(dir, footprint, clock * 0.004);
    }
    col += mix(vec3(1.0, 0.6, 0.3), pal(0.08), 0.3) * glow * (0.9 + 0.2 * bassA);

    float rv = length(uv);
    col *= mix(1.0, 0.7, smoothstep(0.6, 1.9, rv));
    col = tonemap(col * 1.15);
    col += (uhashUnit(uhash3(uvec3(gl_FragCoord.xy, 11u))) - 0.5) / 255.0;
    fragColor = vec4(grade(col), 1.0);
}
