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

// Reef Glide: an underwater glide over a coral reef, through dancing caustics,
// shafts of light and drifting plankton.
//
// THE REEF. A sandy seabed, and on it one coral per cell of a repeated grid,
// each cell choosing its own kind, size, offset and colour (a few are bare
// sand): a squashed brain-coral dome, a branching coral of a trunk and three
// arms blended with a smooth minimum, or a cluster of three tubes whose open
// mouths glow. The glowing tips and mouths are tracked as their own distance
// so the march knows where the light is.
//
// THE WATER does three things on top of the march.
//   - Caustics: the light that reaches each surface is a web of bright lines,
//     two layers of ridged noise advected at different speeds and multiplied,
//     projected down from the surface along the light so they lie on the reef
//     the way real caustics do.
//   - Light shafts: ten samples along the ray each ask whether their point
//     is in a shaft, by projecting it up to the surface along the light and
//     reading a slow noise there, so the shafts are slanted, parallel and
//     drift together.
//   - Plankton: motes in a 3D grid, found at four depths along the ray and
//     faded in and out by how near each one is to its depth slice, so they
//     slide past with real parallax and never pop.
// Distance absorbs red first and then fogs everything into the deep colour.
//
// THE GLIDE. The camera swims a few units above the reef along a slow
// meander, looking down at it, rolling a little into each turn.
//
// AUDIO. Energy sets the glide speed (uFlowPhase, integrated: it eases).
// Bass strengthens the caustics and the shafts; mids light the brain corals'
// grooves; treble sparkles the plankton. A beat swells the corals' glowing
// tips (uSpike, with a rise) and sends a ring of brighter caustics across the
// seabed from beneath the camera (uBeatPhase is how far it has spread).
//
// WHY THE ESTIMATE IS SAFE. Every primitive is exact (a squashed sphere is
// divided by its largest scale), the branches are joined with lib_sdf3's
// smin, which never overestimates, and a coral is not centred in its cell,
// so the estimate also takes the distance to the cell border plus the gap
// every coral keeps from it (RF_EXTENT): a neighbour's coral is never nearer.

#define RF_MAX_STEPS 88
#define RF_FOCAL 1.3
#define RF_FAR 30.0
#define RF_SURFACE 9.0

const float RF_CELL = 2.6;
/** How far a coral reaches from its cell's centre: the offset plus the widest coral. */
const float RF_EXTENT = 0.2 + 0.85;
const vec3 RF_LIGHT = vec3(0.2228, 0.9285, 0.2971);

float gTip;
float gHue;
float gKind;
/** Set while probing for occlusion: the cell-border bound is a step limit, not a surface. */
bool gProbe = false;

float rfCapsule(vec3 p, vec3 a, vec3 b, float r) {
    vec3 pa = p - a;
    vec3 ba = b - a;
    float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
    return length(pa - ba * h) - r;
}

float rfFloor(vec2 xz) {
    return 0.2 * motionNoise(xz * 0.35) + 0.08 * motionNoise(xz * 1.2 + 3.0) - 0.1;
}

float rfCoral(vec3 p) {
    vec2 cell = floor(p.xz / RF_CELL);
    vec2 local = p.xz - (cell + 0.5) * RF_CELL;
    vec2 border = 0.5 * RF_CELL - abs(local);
    float safe = min(border.x, border.y) + (0.5 * RF_CELL - RF_EXTENT);
    float h = motionHash(cell + 0.71);
    if (gProbe) safe = 1e3;
    if (h < 0.08) return safe;
    vec2 off = (vec2(motionHash(cell + 3.3), motionHash(cell + 5.1)) - 0.5) * 0.4;
    vec3 q = vec3(local.x - off.x, p.y + 0.12, local.y - off.y);
    float s = 0.75 + 0.25 * fract(h * 7.7);
    float kind = floor(fract(h * 3.9) * 3.0);
    float d;
    float tip = 1e3;
    if (kind < 0.5) {
        // Brain coral: a squashed dome.
        d = (length(q * vec3(1.0, 1.4, 1.0)) - 0.85 * s) / 1.4;
    } else if (kind < 1.5) {
        // Branching coral: a trunk and three arms.
        float r = 0.08 * s;
        d = rfCapsule(q, vec3(0.0, -0.2, 0.0), vec3(0.0, 0.45 * s, 0.0), r * 1.4);
        for (int i = 0; i < 3; i++) {
            float a = h * 20.0 + float(i) * 2.0944;
            vec3 dir = normalize(vec3(cos(a), 1.3, sin(a)));
            vec3 st = vec3(0.0, (0.25 + 0.12 * float(i)) * s, 0.0);
            vec3 en = st + dir * (0.6 + 0.25 * fract(h * float(5 + i))) * s;
            d = smin(d, rfCapsule(q, st, en, r), 0.06);
            tip = min(tip, length(q - en) - r * 1.5);
        }
    } else {
        // Tube coral: three tubes, their open mouths a ring of light.
        d = 1e3;
        for (int i = 0; i < 3; i++) {
            float a = h * 13.0 + float(i) * 2.0944;
            vec2 c = vec2(cos(a), sin(a)) * 0.3 * s;
            float ht = (0.55 + 0.55 * fract(h * float(3 + i) * 7.1)) * s;
            float rr = (0.12 + 0.05 * float(i)) * s;
            vec3 b = q - vec3(c.x, 0.0, c.y);
            d = min(d, max(length(b.xz) - rr, abs(b.y - ht * 0.5) - ht * 0.5));
            tip = min(tip, length(vec2(length(b.xz) - rr * 0.75, b.y - ht)) - 0.035);
        }
    }
    if (d < gTip + 1e3) {
        gTip = tip;
        gHue = h;
        gKind = kind;
    }
    return min(d, safe);
}

float rfMap(vec3 p) {
    gTip = 1e3;
    gKind = -1.0;
    return min(rfCoral(p), p.y - rfFloor(p.xz));
}

vec3 rfNormal(vec3 p, float eps) {
    vec2 k = vec2(1.0, -1.0);
    return normalize(k.xyy * rfMap(p + k.xyy * eps) + k.yyx * rfMap(p + k.yyx * eps)
                   + k.yxy * rfMap(p + k.yxy * eps) + k.xxx * rfMap(p + k.xxx * eps));
}

/** Ridged noise: 1 on the noise's mid-line, falling away either side. */
float rfRidge(vec2 p) {
    return 1.0 - abs(2.0 * motionNoise(p) - 1.0);
}

/** The caustic web at a point, its light projected down from the surface. */
float rfCaustic(vec3 p) {
    vec2 q = p.xz + RF_LIGHT.xz / RF_LIGHT.y * (RF_SURFACE - p.y);
    float a = rfRidge(q * 1.1 + vec2(uTime * 0.21, uTime * 0.13));
    float b = rfRidge(q * 1.7 - vec2(uTime * 0.17, -uTime * 0.19) + 4.0);
    return pow(a * b, 5.0);
}

vec3 rfWater(vec3 rd) {
    vec3 deep = pal(0.47) * 0.08 + vec3(0.0, 0.02, 0.05);
    vec3 lit = pal(0.47) * 0.35 + vec3(0.05, 0.12, 0.16);
    vec3 col = mix(deep, lit, smoothstep(-0.3, 1.0, rd.y));
    // The bright surface far overhead, rippling.
    col += vec3(0.6, 0.85, 0.9) * smoothstep(0.75, 1.0, rd.y) * (0.3 + 0.2 * rfRidge(rd.xz / max(rd.y, 0.1) * 3.0 + uTime * 0.2));
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

void main() {
    vec2 uv = cameraView();
    float bass = min(uBassSmooth, 1.3);
    float mid = min(uMidSmooth, 1.3);
    float treb = min(uTrebleSmooth, 1.3);
    float beatEnv = clamp(uBeat, 0.0, 1.0) * clamp(uBeatResponse, 0.0, 2.0);

    vec3 rdl = normalize(vec3(uv, RF_FOCAL));
    vec2 look = touchLook();
    if (look != vec2(0.0)) rdl = rotY(look.x * 0.6) * rotX(-look.y * 0.4) * rdl;
    vec3 camOff = vec3(0.0);
    cameraFlight(camOff, rdl);

    // The glide, INTEGRATED: the Speed control through uTime, loudness through uFlowPhase.
    float z = uTime * 1.0 + uFlowPhase * 4.5 + camOff.z;
    vec3 ro = vec3(2.2 * sin(z * 0.06) + 0.8 * sin(z * 0.023), 1.9 + 0.25 * sin(z * 0.09), z);
    float slope = 0.132 * cos(z * 0.06) + 0.0184 * cos(z * 0.023);
    float yaw = atan(slope) + 0.1 * sin(uTime * 0.05);
    float pitch = 0.36 + 0.05 * sin(uTime * 0.13);
    float roll = -slope * 0.6 + 0.04 * sin(uTime * 0.3);
    vec3 rd = rotY(yaw) * rotX(pitch) * rotZ(roll) * rdl;

    float budget = uSteps * (float(RF_MAX_STEPS) / 128.0);
    float t = 0.05;
    bool hit = false;
    for (int i = 0; i < RF_MAX_STEPS; i++) {
        if (float(i) >= budget || t > RF_FAR) break;
        float d = rfMap(ro + rd * t);
        float eps = 0.002 + 0.0012 * t;
        if (d < eps) {
            hit = true;
            break;
        }
        t += max(d * 0.85, eps);
    }

    vec3 water = rfWater(rd);
    vec3 col = water;
    if (!hit && rd.y < 0.0) {
        // Out of budget over the seabed: the sand beyond, as deep in the fog as the ray got.
        vec3 sandFar = mix(pal(0.12), vec3(0.85, 0.8, 0.68), 0.7) * (pal(0.47) * 0.35 + vec3(0.12, 0.15, 0.17)) * 0.6;
        col = mix(sandFar * exp(-t * vec3(0.06, 0.02, 0.01)), water, 1.0 - exp(-t * 0.065 * xm(uXFog)));
    }
    // A ray that ran out of budget is left to the water: the fog is deep by then.
    float depth = hit ? t : RF_FAR;
    if (hit) {
        vec3 p = ro + rd * t;
        float dScene = rfMap(p);
        float tip = gTip;
        float hue = gHue;
        float kind = gKind;
        bool coral = abs(p.y - rfFloor(p.xz)) > dScene + 1e-3;
        vec3 n = rfNormal(p, 0.002 + 0.0012 * t);
        float diffuse = max(dot(n, RF_LIGHT), 0.0);
        // The beat's ring of brighter caustics, spreading across the seabed.
        float ring = length(p.xz - ro.xz) - uBeatPhase * 12.0;
        float caustic = rfCaustic(p) * (0.7 + 0.9 * bass + 1.2 * beatEnv * beatEnv * exp(-ring * ring * 0.4));
        // Occlusion: lower is darker, and two probes of the field along the
        // normal darken the hollows between and under the corals.
        gProbe = true;
        float probe = clamp(0.5 * (rfMap(p + n * 0.12) / 0.12 + rfMap(p + n * 0.4) / 0.4), 0.0, 1.0);
        gProbe = false;
        float ao = (0.55 + 0.45 * smoothstep(-0.2, 1.3, p.y)) * (0.4 + 0.6 * probe);
        vec3 base;
        if (coral) {
            base = mix(pal(fract(hue * 3.7)), vec3(0.8, 0.75, 0.7), 0.12) * 1.1;
            if (kind < 0.5) {
                // A brain coral's maze of grooves, glowing faintly with the mids.
                float groove = abs(motionNoise(p.xz * 4.0 + p.y * 3.0) - 0.5);
                base *= 0.6 + 0.4 * smoothstep(0.02, 0.1, groove);
                base += pal(fract(hue * 3.7 + 0.5)) * smoothstep(0.05, 0.0, groove) * (0.05 + 0.35 * mid);
            }
        } else {
            // Pale sand in wind-and-current ripples.
            base = mix(pal(0.12), vec3(0.85, 0.8, 0.68), 0.7) * 0.6;
            base *= 0.85 + 0.15 * sin(p.x * 5.0 + 3.0 * motionNoise(p.xz * 0.7));
        }
        vec3 ambient = pal(0.47) * 0.35 + vec3(0.12, 0.15, 0.17);
        // A rim of the blue light scattered round every edge.
        float rim = pow(1.0 - max(dot(n, -rd), 0.0), 3.0);
        col = base * (ambient + vec3(0.9, 1.0, 1.0) * diffuse * (0.6 + 1.8 * caustic)) * ao + pal(0.47) * rim * 0.12;
        // The glowing tips and mouths, swelling on a beat.
        col += pal(fract(hue * 3.7 + 0.45)) * exp(-max(tip, 0.0) * 14.0) * (0.25 + 0.4 * mid + 1.0 * uSpike);
        // Red goes first with distance, then everything goes to the deep colour.
        col *= exp(-t * vec3(0.06, 0.02, 0.01));
        col = mix(col, water, 1.0 - exp(-t * 0.065 * xm(uXFog)));
    }

    // Light shafts: ten points along the ray projected up to the surface along the light.
    float span = min(depth, 18.0);
    float jitter = motionHash(gl_FragCoord.xy);
    float shafts = 0.0;
    for (int i = 0; i < 10; i++) {
        float s = (float(i) + jitter) / 10.0 * span;
        vec3 q = ro + rd * s;
        vec2 proj = q.xz + RF_LIGHT.xz / RF_LIGHT.y * (RF_SURFACE - q.y);
        shafts += smoothstep(0.55, 0.85, motionNoise(proj * 0.4 + vec2(uTime * 0.04, uTime * 0.03))) * exp(-s * 0.08);
    }
    col += mix(pal(0.47), vec3(0.8, 0.95, 1.0), 0.5) * shafts * span / 10.0 * 0.035 * (0.6 + 0.8 * bass);

    // Plankton: motes in a drifting 3D grid, found at four depths along the ray.
    vec3 current = vec3(0.12, 0.04, -0.06) * uTime;
    for (int k = 0; k < 4; k++) {
        float dk = 1.0 + 1.4 * float(k) + 0.25 * float(k * k);
        if (dk > depth) break;
        vec3 q = (ro + rd * dk + current) / 0.7;
        vec3 cell = floor(q);
        float h = motionHash(cell.xy + cell.z * 17.13);
        if (h > 0.55) {
            vec3 jit = vec3(motionHash(cell.xy + 3.7 + cell.z), motionHash(cell.yz + 8.1), motionHash(cell.zx + 1.9));
            vec3 mote = (cell + 0.2 + 0.6 * jit) * 0.7 - current;
            vec3 rel = mote - ro;
            float along = dot(rel, rd);
            float perp = length(rel - rd * along);
            float fade = 1.0 - smoothstep(0.1, 0.4, abs(along - dk));
            float spark = 0.4 + 0.6 * treb * (0.5 + 0.5 * sin(uTime * 4.0 + h * 40.0));
            col += mix(pal(0.5), vec3(0.9, 1.0, 0.95), 0.5) * smoothstep(0.022, 0.004, perp) * fade * spark * exp(-along * 0.15) * 0.8;
        }
    }

    if (!touchIdle()) {
        col += pal(0.5 + 0.15 * sin(uTime * 0.05)) * min(touchWake(uv), 3.0) * 0.05;
    }
    col *= 0.72 + 0.28 * smoothstep(2.2, 0.5, length(uv));
    fragColor = vec4(grade(shoulder(col)) + dither(), 1.0);
}
