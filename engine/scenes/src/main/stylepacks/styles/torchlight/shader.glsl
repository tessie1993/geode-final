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

// Torchlight: walking a catacomb with a handheld light.
//
// The camera is a person: it walks at a steady pace along a winding vaulted
// corridor, its head bobs with each footstep and sways with the stride, and a
// hit in the music jolts it - a trauma value read through smooth noise, never
// white noise, and well under 3 Hz. The torch is held off to the right and
// aims a little ahead of where the corridor turns, so it swings wide into the
// bends before the head follows. Everything is a function of uTime, so the
// walk looks the same at any frame rate.
//
// The catacomb is one distance field, repeated: a barrel-vaulted corridor
// bent onto a path (the same path places the camera, so the camera can never
// clip a wall), burial niches in two rows along both walls with an urn in some
// of them, brick ribs every few metres, and a side passage every ten metres.
// It repeats on 1000 m, so the Camera's travel wraps without a seam.
//
// Most of the frame is dark, and that is the look: the torch is a soft-edged
// cone with an inverse-square falloff, and dust hangs in its beam. Fog ends
// rays early, which is also where the budget comes from.
//
// ---- No-flash ------------------------------------------------------------------
//
// The torch never flickers and the music never brightens it. The beam turns
// only as the path bends (a few degrees a second) or the finger moves it, and
// its edge is soft, so a sweep never snaps a region from dark to lit. The ribs
// pass at a fraction of a hertz.
//
// ---- the music -----------------------------------------------------------------
//
// The bass deepens the footsteps; a hit (uSpike) adds trauma to the head; the
// mids swing the beam; the treble glints in the dust. The finger aims the torch.

/** Compile-time ceiling on the march; the runtime budget is min(uSteps, this). */
#define TL_MAX_STEPS 64
#define TL_FAR 16.0
#define TL_FOCAL 1.25
/** Walking pace, metres per second. */
#define TL_PACE 0.8
/** Footsteps per second. */
#define TL_STEP_RATE 1.7
/** The world's period along the corridor: divides the Camera's travel wrap (1000). */
#define TL_PERIOD 1000.0
#define TL_TAU 6.2831853

/** Path frequencies: whole cycles per period, so the path repeats with the world. */
const float TL_W1 = TL_TAU * 19.0 / TL_PERIOD;
const float TL_W2 = TL_TAU * 47.0 / TL_PERIOD;
const float TL_W3 = TL_TAU * 13.0 / TL_PERIOD;
/**
 * The path warp moves x by px(z), whose slope is at most 1.4*W1 + 0.6*W2
 * (about 0.35), so the straightened field's gradient is at most
 * sqrt(1 + 0.35^2) = 1.06. Stepping 0.8 of the estimate covers it and the
 * vault's own min/max seams.
 */
const float TL_STEP = 0.8;

int gMat;

float pathX(float z) {
    return 1.4 * sin(z * TL_W1) + 0.6 * sin(z * TL_W2 + 1.0);
}

float pathY(float z) {
    return 0.25 * sin(z * TL_W3);
}

float sdBox2(vec2 p, vec2 b) {
    vec2 q = abs(p) - b;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0);
}

/** The corridor's cross-section, negative inside: walls to 0.5 m, then a barrel vault. */
float vault(vec2 p) {
    float walls = sdBox2(p - vec2(0.0, -0.3), vec2(1.0, 0.8));
    float arch = length(p - vec2(0.0, 0.5)) - 1.0;
    return min(walls, arch);
}

float map(vec3 p) {
    vec3 q = vec3(p.x - pathX(p.z), p.y - pathY(p.z), p.z);
    float corridor = vault(q.xy);
    float space = corridor;
    // Side passages every ten metres, closed off six metres in.
    float zb = mod(q.z + 5.0, 10.0) - 5.0;
    float branch = max(vault(vec2(zb, q.y)), abs(q.x) - 6.0);
    space = min(space, branch);
    // Burial niches: two rows in both walls, one cell a metre.
    float cellZ = floor(q.z);
    float zc = q.z - (cellZ + 0.5);
    vec3 qn = vec3(abs(q.x) - 1.2, q.y, zc);
    float niche = min(sdBox(qn - vec3(0.0, -0.65, 0.0), vec3(0.3, 0.19, 0.34)),
                      sdBox(qn - vec3(0.0, -0.08, 0.0), vec3(0.3, 0.19, 0.34)));
    // No niches where a side passage opens.
    niche = max(niche, 1.3 - abs(zb));
    space = min(space, niche);
    float solid = -space;
    gMat = 0;
    // Brick ribs every 2.5 m, standing proud of the vault.
    float zr = mod(q.z, 2.5) - 1.25;
    float rib = max(abs(zr) - 0.13, -(corridor + 0.15));
    if (rib < solid) {
        solid = rib;
        gMat = 1;
    }
    // An urn in some niches: keyed by the cell, the side and the row.
    float side = q.x > 0.0 ? 1.0 : 0.0;
    float cellKey = mod(cellZ, TL_PERIOD);
    float row = q.y > -0.37 ? 1.0 : 0.0;
    float h = hash11(cellKey * 3.17 + side * 1.31 + row * 7.7);
    if (h > 0.45) {
        float baseY = row > 0.5 ? -0.27 : -0.84;
        vec3 uq = qn - vec3(0.02, baseY + 0.11, 0.0);
        uq.y *= 0.8;
        float urn = (length(uq) - 0.11) * 0.8;
        if (urn < solid) {
            solid = urn;
            gMat = 2;
        }
    }
    if (gMat == 0 && q.y < -1.06) gMat = 3;
    return solid;
}

vec3 normalAt(vec3 p, float eps) {
    vec2 k = vec2(1.0, -1.0);
    return normalize(k.xyy * map(p + k.xyy * eps) + k.yyx * map(p + k.yyx * eps)
                   + k.yxy * map(p + k.yxy * eps) + k.xxx * map(p + k.xxx * eps));
}

/** Four taps along the normal: cheap contact darkening in the niches and corners. */
float occlusion(vec3 p, vec3 n) {
    float occ = 0.0;
    float w = 1.0;
    for (int i = 1; i <= 4; i++) {
        float h = 0.06 * float(i);
        occ += (h - map(p + n * h)) * w;
        w *= 0.6;
    }
    return clamp(1.0 - 2.2 * occ, 0.0, 1.0);
}

/** Stone courses, bricks and flagstones: albedo by material, in the straightened frame. */
vec3 albedoAt(vec3 p, int mat, float fp) {
    // Textures read z on the world's period too, so they wrap with it.
    vec3 q = vec3(p.x - pathX(p.z), p.y - pathY(p.z), mod(p.z, TL_PERIOD));
    vec3 stone = mix(vec3(0.5, 0.44, 0.36), pal(0.1) * 0.6, 0.2);
    // Level of detail: where a pixel covers more than the grime's grain or a
    // joint's width, both fade to their average instead of sparkling as the
    // walk slides them across pixels.
    float fine = 1.0 - smoothstep(0.01, 0.05, fp);
    float grime = mix(0.5, fbm3(q * 3.0, 3), fine);
    float jw = 1.0 - smoothstep(0.015, 0.06, fp);
    if (mat == 1) {
        // Bricks across the rib.
        vec2 b = vec2(atan(q.y - 0.5, q.x) * 3.0, q.z * 6.0);
        b.x += 0.5 * mod(floor(b.y), 2.0);
        vec2 f = abs(fract(b) - 0.5);
        float mortar = smoothstep(0.42, 0.48, max(f.x * 1.1, f.y)) * jw + 0.15 * (1.0 - jw);
        vec3 brick = mix(vec3(0.5, 0.27, 0.18), pal(0.02) * 0.6, 0.25);
        return mix(brick * (0.8 + 0.3 * grime), vec3(0.35, 0.32, 0.28), mortar);
    }
    if (mat == 2) {
        return mix(vec3(0.62, 0.5, 0.36), pal(0.15) * 0.7, 0.3) * (0.8 + 0.3 * grime);
    }
    if (mat == 3) {
        vec2 f = abs(fract(vec2(q.x * 1.3, q.z * 1.1)) - 0.5);
        float joint = smoothstep(0.43, 0.49, max(f.x, f.y)) * jw + 0.1 * (1.0 - jw);
        return mix(stone * 0.8 * (0.75 + 0.4 * grime), stone * 0.35, joint);
    }
    // Ashlar courses, every other course offset by half a block.
    vec2 c = vec2(q.z * 2.0, q.y * 4.0);
    c.x += 0.5 * mod(floor(c.y), 2.0) + 0.37 * abs(q.x);
    vec2 f = abs(fract(c) - 0.5);
    float joint = smoothstep(0.44, 0.49, max(f.x * 1.05, f.y)) * jw + 0.1 * (1.0 - jw);
    float block = hash11(floor(c.x) * 1.7 + floor(c.y) * 3.1);
    return mix(stone * (0.7 + 0.25 * block) * (0.7 + 0.45 * grime), stone * 0.3, joint);
}

vec3 tonemap(vec3 c) {
    c = max(c, 0.0);
    return clamp(c * (2.51 * c + 0.03) / (c * (2.43 * c + 0.59) + 0.14), 0.0, 1.0);
}

void main() {
    vec2 uv = view();
    float bassA = clamp(uBassSmooth, 0.0, 1.3);
    float midA = clamp(uMidSmooth, 0.0, 1.3);

    // ---- the walk -------------------------------------------------------------------
    float zc = mod(uTime * TL_PACE, TL_PERIOD) + uCamTravel + uCamPush * 1.5;
    float stepPhase = uTime * 3.1415927 * TL_STEP_RATE;
    float bob = (0.022 + 0.014 * bassA) * (abs(sin(stepPhase)) - 0.63);
    float sway = 0.018 * sin(stepPhase * 0.5);
    vec3 ro = vec3(pathX(zc), pathY(zc) - 0.05 + bob, zc);
    vec3 ahead = vec3(pathX(zc + 1.4), pathY(zc + 1.4) - 0.12, zc + 1.4);
    vec3 fwd = normalize(ahead - ro);
    vec3 right = normalize(cross(vec3(0.0, 1.0, 0.0), fwd));
    ro += right * sway;
    // Trauma: a hit shakes the head through smooth noise, squared so small
    // hits barely register, and slow (about 1.3 Hz).
    float trauma = clamp(uSpike, 0.0, 1.0);
    trauma *= trauma;
    float nt = uTime * 1.3;
    vec2 jolt = vec2(vnoise3(vec3(nt, 1.0, 0.0)), vnoise3(vec3(nt, 5.0, 0.0))) - 0.5;
    float yaw = 0.05 * jolt.x * trauma;
    float pitch = 0.04 * jolt.y * trauma;
    float roll = 0.03 * (vnoise3(vec3(nt, 9.0, 0.0)) - 0.5) * trauma + 0.6 * sway;
    fwd = normalize(fwd + right * yaw + vec3(0.0, pitch, 0.0));
    right = normalize(cross(vec3(0.0, 1.0, 0.0), fwd));
    vec3 up = cross(fwd, right);
    vec2 lens = rot2(roll) * uv;
    vec3 rd = normalize(lens.x * right + lens.y * up + TL_FOCAL * fwd);
    rd = uCamRot * rd;

    // ---- the torch -------------------------------------------------------------------
    // Held low on the right; it leads the head into the bends (it aims further
    // down the path) and the mids swing it slowly.
    vec3 torchPos = ro + right * 0.24 - up * 0.2;
    vec3 lead = vec3(pathX(zc + 3.2), pathY(zc + 3.2) - 0.25, zc + 3.2);
    vec3 beam = normalize(lead - torchPos);
    float swing = 0.12 * midA * sin(uTime * 0.37);
    beam = normalize(beam + right * swing + up * 0.05 * sin(uTime * 0.23));
    if (!touchIdle()) {
        // The finger aims the torch at the point under it.
        vec2 a = touchAnchor();
        vec3 aim = normalize(a.x * right + a.y * up + TL_FOCAL * fwd);
        beam = normalize(mix(beam, aim, 0.8 * touchStrength()));
    }
    vec3 torchCol = mix(vec3(1.0, 0.7, 0.42), pal(0.07), 0.25) * 5.0;

    // ---- the march -------------------------------------------------------------------
    float budget = min(uSteps, float(TL_MAX_STEPS));
    float t = 0.05;
    float hitT = -1.0;
    for (int i = 0; i < TL_MAX_STEPS; i++) {
        if (float(i) >= budget) break;
        if (t > TL_FAR) break;
        float d = map(ro + rd * t);
        float eps = 0.0015 * t + 0.0008;
        if (d < eps) {
            hitT = t;
            break;
        }
        t += d * TL_STEP;
    }

    vec3 fogCol = mix(vec3(0.03, 0.025, 0.02), pal(0.6) * 0.03, 0.4);
    vec3 col = fogCol;
    float tEnd = hitT > 0.0 ? hitT : TL_FAR;
    if (hitT > 0.0) {
        vec3 p = ro + rd * hitT;
        map(p);
        int mat = gMat;
        vec3 n = normalAt(p, max(0.0015 * hitT, 0.001));
        // The pixel's footprint at the hit, from the ray spread.
        vec3 alb = albedoAt(p, mat, hitT * 1.6 / (TL_FOCAL * uResolution.y));
        vec3 toL = torchPos - p;
        float dl = length(toL);
        vec3 l = toL / dl;
        float cone = dot(-l, beam);
        // A soft-edged cone with a faint spill outside it.
        float spot = smoothstep(0.8, 0.97, cone) + 0.035 * smoothstep(0.3, 0.8, cone);
        float atten = 1.0 / (1.0 + 0.2 * dl + 0.3 * dl * dl);
        float diff = max(dot(n, l), 0.0);
        vec3 hv = normalize(l - rd);
        float gloss = mat == 2 ? 48.0 : 18.0;
        float spec = pow(max(dot(n, hv), 0.0), gloss) * (mat == 2 ? 0.5 : 0.12);
        float fres = pow(1.0 - max(dot(n, -rd), 0.0), 4.0);
        float ao = occlusion(p, n);
        vec3 lit = torchCol * spot * atten * (alb * diff + spec + 0.08 * fres * alb);
        // A cold fill from far down the corridor, and the warm bounce of the
        // torch off the walls around it, so the dark has shape.
        vec3 fill = mix(vec3(0.05, 0.07, 0.1), pal(0.6) * 0.08, 0.3) * (0.5 + 0.5 * n.y);
        vec3 bounce = torchCol * 0.04 * atten;
        col = (lit + alb * (fill + bounce)) * ao;
        col = mix(fogCol, col, exp(-hitT * 0.14));
    }

    // Dust in the beam: single scattering, ten samples along the ray up to the
    // hit. The samples are offset per pixel by an interleaved gradient (a
    // structured pattern, far quieter than white noise) so the steps never band.
    float jitter = uhashUnit(uhash3(uvec3(gl_FragCoord.xy, 3u)));
    float ign = fract(52.9829189 * fract(dot(floor(gl_FragCoord.xy), vec2(0.06711056, 0.00583715))));
    float span = min(tEnd, 7.0);
    float scatter = 0.0;
    for (int i = 0; i < 10; i++) {
        float s = (float(i) + 0.25 + 0.5 * ign) / 10.0 * span;
        vec3 sp = ro + rd * s;
        vec3 sl = sp - torchPos;
        float sd = length(sl);
        float sc = smoothstep(0.8, 0.97, dot(sl / max(sd, 1e-3), beam));
        scatter += sc / (1.0 + 0.4 * sd * sd);
    }
    scatter *= span / 10.0;
    col += torchCol * scatter * 0.016;
    // Motes glinting where the beam crosses the view.
    vec3 mp = ro + rd * 1.6;
    vec3 ml = mp - torchPos;
    float beamHere = smoothstep(0.82, 0.97, dot(normalize(ml), beam));
    col += torchCol * 0.08 * beamHere * fluidMotes(uv, 7.0, 0.09);

    col *= mix(1.0, 0.65, smoothstep(0.5, 1.9, length(uv)));
    col = tonemap(col * 1.2);
    col += (jitter - 0.5) / 255.0;
    fragColor = vec4(grade(col), 1.0);
}
