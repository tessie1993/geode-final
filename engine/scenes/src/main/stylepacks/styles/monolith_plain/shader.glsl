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

// Monolith Plain: a tracking shot. The camera trucks sideways along an empty
// lane, looking out across an endless plain of tall dark monoliths under a
// low sun. Near slabs sweep past, far ones crawl, the farthest hang still in
// the haze: the parallax of a lateral move is the whole effect. The sun is
// low enough that every slab throws a shadow many times its height, and the
// shadows rake across the plain toward the lens.
//
// ---- traversal, not sphere tracing --------------------------------------------
//
// The plain is a grid of cells, each holding at most one box well inside it.
// A ray walks the cells its ground track crosses (2D DDA) and intersects the
// one box in each exactly (the slab test), so the first hit is the nearest
// and the edges are mathematically sharp. Shadows are the same walk along the
// sun direction from the shaded point. The chord a shadow ray cuts through a
// box shrinks to nothing at the box's silhouette, so a penumbra that widens
// with distance from the occluder is one smoothstep of it: soft, long,
// analytic shadows with no extra rays.
//
// Detail sets how many cells a ray may walk; the haze hides where it stops.
// The plain repeats every 64 cells along the track, so the camera's position
// is wrapped to that period and never loses precision.
//
// ---- audio ------------------------------------------------------------------------
//
// Energy eases the truck's speed through uFlowPhase (integrated, so it can
// slow and speed up but never jump). A beat runs light up every slab's
// vertical edges and across its top (uBeatPhase, gated by the beat envelope);
// uMidSmooth sets the edges' resting glow and hue; uBassSmooth thickens the
// haze around the sun; uTrebleSmooth lights the dust drifting through the
// low light. No transient touches the sky or the sun.

#define MP_CELL 2.0
#define MP_PERIOD 64.0
#define MP_HMAX 5.0
#define MP_MAX_CELLS 96
#define MP_SHADOW_CELLS 24
#define MP_FOCAL 1.25
#define MP_TAU 6.2831853

vec3 gSun;
float gEdgeRun;
float gEdgeGate;
float gEdgeRest;
vec3 gEdgeCol;

/**
 * The slab in a cell, or false for an empty one. The lane the camera rides
 * (the row at z index 0) is always empty, and the hash reads the track index
 * modulo the period, which is what makes the camera's wrap invisible.
 */
bool slabOf(vec2 cell, out vec3 lo, out vec3 hi, out float seed) {
    lo = vec3(0.0);
    hi = vec3(0.0);
    seed = 0.0;
    if (cell.y == 0.0) return false;
    ivec2 key = ivec2(int(mod(cell.x, MP_PERIOD)), int(cell.y));
    if (hashCell(ivec3(key, 1)) < 0.56) return false;
    seed = hashCell(ivec3(key, 2));
    float height = 2.0 + 3.0 * pow(hashCell(ivec3(key, 3)), 1.5);
    // 1 : 4 : 9, turned to face down the plain or across it.
    vec2 half_ = hashCell(ivec3(key, 4)) < 0.5 ? vec2(0.34, 0.075) : vec2(0.075, 0.34);
    vec2 centre = (cell + 0.5) * MP_CELL + 0.9 * (vec2(hashCell(ivec3(key, 5)), hashCell(ivec3(key, 6))) - 0.5);
    lo = vec3(centre.x - half_.x, 0.0, centre.y - half_.y);
    hi = vec3(centre.x + half_.x, height, centre.y + half_.y);
    return true;
}

/** Slab test: .x entry, .y exit. A miss has entry > exit. */
vec2 boxSpan(vec3 ro, vec3 inv, vec3 lo, vec3 hi) {
    vec3 t0 = (lo - ro) * inv;
    vec3 t1 = (hi - ro) * inv;
    vec3 tn = min(t0, t1);
    vec3 tf = max(t0, t1);
    return vec2(max(max(tn.x, tn.y), tn.z), min(min(tf.x, tf.y), tf.z));
}

/** 1/d with each component kept off zero, so an axis-parallel ray still has a finite slab test. */
vec3 safeInverse(vec3 d) {
    vec3 s = vec3(d.x >= 0.0 ? 1.0 : -1.0, d.y >= 0.0 ? 1.0 : -1.0, d.z >= 0.0 ? 1.0 : -1.0);
    return 1.0 / (s * max(abs(d), vec3(1e-6)));
}

/**
 * How much sun reaches p: 1 in the open, 0 behind a slab, with a penumbra
 * from the chord the sun ray cuts through each slab it passes, widening with
 * the distance to that slab.
 */
float sunlight(vec3 p) {
    vec3 inv = safeInverse(gSun);
    vec2 cell = floor(p.xz / MP_CELL);
    vec2 dirSign = vec2(gSun.x >= 0.0 ? 1.0 : -1.0, gSun.z >= 0.0 ? 1.0 : -1.0);
    vec2 invXZ = 1.0 / max(abs(gSun.xz), vec2(1e-6));
    vec2 tMax = ((cell + max(dirSign, 0.0)) * MP_CELL - p.xz) * dirSign * invXZ;
    vec2 tDelta = MP_CELL * invXZ;
    float lit = 1.0;
    for (int i = 0; i < MP_SHADOW_CELLS; i++) {
        vec3 lo;
        vec3 hi;
        float seed;
        if (slabOf(cell, lo, hi, seed)) {
            vec2 span = boxSpan(p, inv, lo, hi);
            if (span.y > max(span.x, 0.0)) {
                float chord = span.y - max(span.x, 0.0);
                lit = min(lit, 1.0 - smoothstep(0.0, 0.03 + 0.025 * span.x, chord));
                if (lit <= 0.0) break;
            }
        }
        float tNext = min(tMax.x, tMax.y);
        if (p.y + gSun.y * tNext > MP_HMAX) break;
        if (tMax.x < tMax.y) {
            tMax.x += tDelta.x;
            cell.x += dirSign.x;
        } else {
            tMax.y += tDelta.y;
            cell.y += dirSign.y;
        }
    }
    return lit;
}

vec3 skyCol(vec3 d, float bass) {
    float s = max(dot(d, gSun), 0.0);
    vec3 horizon = mix(pal(0.9), vec3(1.0), 0.3) * 0.72;
    vec3 zenith = mix(pal(0.3), vec3(0.16, 0.2, 0.36), 0.45) * 0.4;
    vec3 col = mix(horizon, zenith, smoothstep(-0.02, 0.45, d.y));
    vec3 sunTint = mix(pal(0.86), vec3(1.0), 0.45);
    col += sunTint * (0.30 * pow(s, 6.0) * (1.0 + 0.35 * bass) + 0.45 * pow(s, 90.0));
    col += sunTint * smoothstep(0.99955, 0.9998, s) * 0.8;
    return col;
}

/** The haze a distance t along d fades toward: the horizon's colour, warmer toward the sun. */
vec3 hazeCol(vec3 d, float bass) {
    return skyCol(vec3(d.x, 0.02, d.z), bass);
}

// The light along a slab's edges: a resting line plus the beat's pulse, which
// climbs the vertical edges from the ground and then runs across the top.
vec3 edgeLight(vec3 p, vec3 lo, vec3 hi, float seed) {
    vec3 c = 0.5 * (lo + hi);
    vec3 h = 0.5 * (hi - lo);
    vec3 q = abs(p - c);
    vec3 gap = h - q;
    // On a face, one gap is ~0: the edge distance is the smaller of the other two.
    float e;
    float vertical;
    if (gap.x < gap.y && gap.x < gap.z) {
        e = min(gap.y, gap.z);
        vertical = gap.z < gap.y ? 1.0 : 0.0;
    } else if (gap.z < gap.y) {
        e = min(gap.x, gap.y);
        vertical = gap.x < gap.y ? 1.0 : 0.0;
    } else {
        e = min(gap.x, gap.z);
        vertical = 0.0;
    }
    float line = exp(-e / 0.012) + 0.25 * exp(-e / 0.07);
    float up = (p.y - lo.y) / max(hi.y - lo.y, 1e-3);
    float at = mix(1.0, up, vertical);
    float run = gEdgeRun + 0.12 * seed;
    float pulse = exp(-(at - run) * (at - run) * 55.0) * gEdgeGate;
    return gEdgeCol * line * (gEdgeRest + 1.6 * pulse);
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
    return rotY(a.x * 0.9) * (rotX(-a.y * 0.5) * rd);
}

void main() {
    vec2 uv = cameraView();
    float bass = clamp(uBassSmooth, 0.0, 1.5);
    float mid = clamp(uMidSmooth, 0.0, 1.5);
    float treb = clamp(uTrebleSmooth, 0.0, 1.5);
    gSun = normalize(vec3(0.72 + 0.06 * sin(uTime * 0.011), 0.13, 0.68));
    gEdgeRun = uBeatPhase * 1.5 - 0.25;
    gEdgeGate = clamp(uBeat, 0.0, 1.0) * clamp(uBeatResponse, 0.0, 2.0);
    gEdgeRest = 0.10 + 0.14 * mid;
    gEdgeCol = mix(pal(0.48 + 0.08 * mid), vec3(1.0), 0.3);

    // ---- the truck -------------------------------------------------------------
    //
    // Sideways along +x, looking out across the plain (+z) with a slight lead
    // into the move. Wrapped to the plain's period: the world repeats exactly.
    float truck = uTime * 0.35 + uFlowPhase * 3.2 + uCamTravel;
    vec3 ro = vec3(mod(truck, MP_PERIOD * MP_CELL), 1.15 + 0.06 * sin(uTime * 0.21), 0.35 + uCamPush * 1.5);
    vec3 rd = normalize(vec3(uv, MP_FOCAL));
    rd = rotY(0.18 + 0.06 * sin(uTime * 0.029)) * (rotX(-0.06 + 0.03 * sin(uTime * 0.023)) * rd);
    rd = steerLook(rd);
    rd = uCamRot * rd;

    // ---- the walk ----------------------------------------------------------------
    vec3 inv = safeInverse(rd);
    vec2 cell = floor(ro.xz / MP_CELL);
    vec2 dirSign = vec2(rd.x >= 0.0 ? 1.0 : -1.0, rd.z >= 0.0 ? 1.0 : -1.0);
    vec2 invXZ = 1.0 / max(abs(rd.xz), vec2(1e-6));
    vec2 tMax = ((cell + max(dirSign, 0.0)) * MP_CELL - ro.xz) * dirSign * invXZ;
    vec2 tDelta = MP_CELL * invXZ;
    float tGround = rd.y < 0.0 ? -ro.y / rd.y : -1.0;
    float budget = clamp(uSteps * 0.6, 24.0, float(MP_MAX_CELLS));

    float hitT = -1.0;
    vec3 hitLo = vec3(0.0);
    vec3 hitHi = vec3(0.0);
    float hitSeed = 0.0;
    for (int i = 0; i < MP_MAX_CELLS; i++) {
        if (float(i) >= budget) break;
        vec3 lo;
        vec3 hi;
        float seed;
        if (slabOf(cell, lo, hi, seed)) {
            vec2 span = boxSpan(ro, inv, lo, hi);
            if (span.y > max(span.x, 0.0) && (tGround < 0.0 || span.x < tGround)) {
                hitT = span.x;
                hitLo = lo;
                hitHi = hi;
                hitSeed = seed;
                break;
            }
        }
        float tNext = min(tMax.x, tMax.y);
        if (tGround > 0.0 && tGround < tNext) break;
        if (rd.y > 0.0 && ro.y + rd.y * tNext > MP_HMAX) break;
        if (tMax.x < tMax.y) {
            tMax.x += tDelta.x;
            cell.x += dirSign.x;
        } else {
            tMax.y += tDelta.y;
            cell.y += dirSign.y;
        }
    }

    vec3 sunTint = mix(pal(0.88), vec3(1.0), 0.35) * 2.4;
    vec3 ambient = mix(pal(0.6), vec3(0.4, 0.45, 0.6), 0.5) * 0.16;
    vec3 col;
    float tFog;
    if (hitT > 0.0) {
        // ---- a slab: black glass with lit edges --------------------------------
        vec3 p = ro + rd * hitT;
        vec3 c = 0.5 * (hitLo + hitHi);
        vec3 h = 0.5 * (hitHi - hitLo);
        vec3 q = (p - c) / h;
        vec3 aq = abs(q);
        vec3 n = aq.x > aq.y && aq.x > aq.z ? vec3(sign(q.x), 0.0, 0.0)
               : (aq.y > aq.z ? vec3(0.0, sign(q.y), 0.0) : vec3(0.0, 0.0, sign(q.z)));
        float ndl = max(dot(n, gSun), 0.0);
        float lit = ndl > 0.0 ? sunlight(p + n * 0.01) : 0.0;
        float fres = 0.04 + 0.96 * pow(1.0 - max(dot(-rd, n), 0.0), 5.0);
        col = vec3(0.025) * (ambient * 3.0 + sunTint * ndl * lit);
        col += skyCol(reflect(rd, n), bass) * fres * 0.8;
        col += edgeLight(p, hitLo, hitHi, hitSeed);
        tFog = hitT;
    } else if (tGround > 0.0) {
        // ---- the plain: dust raked into ripples, read by the grazing sun -------
        vec3 p = ro + rd * tGround;
        float fine = 1.0 / (1.0 + tGround * 0.15);
        vec2 r = p.xz * vec2(1.7, 2.3);
        vec3 n = normalize(vec3(0.18 * fine * cos(r.x + 1.3 * sin(r.y * 0.7)), 1.0, 0.14 * fine * cos(r.y * 1.4 + sin(r.x))));
        float ndl = max(dot(n, gSun), 0.0);
        float lit = sunlight(p + vec3(0.0, 0.01, 0.0));
        vec3 albedo = mix(pal(0.9), vec3(0.55), 0.6) * (0.28 + 0.06 * motionNoise(p.xz * 0.6));
        col = albedo * (ambient + sunTint * ndl * lit);
        tFog = tGround;
    } else {
        col = skyCol(rd, bass);
        tFog = -1.0;
    }
    if (tFog > 0.0) {
        float haze = 1.0 - exp(-tFog * (0.028 + 0.006 * bass));
        col = mix(col, hazeCol(rd, bass), haze);
    }

    // ---- dust in the low light ------------------------------------------------------
    //
    // Three depths of motes in front of the plain, each sliding past at the
    // speed its distance gives it: the same parallax as the slabs, at no cost.
    float toward = pow(max(dot(rd, gSun), 0.0), 3.0);
    for (int k = 0; k < 3; k++) {
        float depth = 1.6 + 2.2 * float(k);
        vec2 world = vec2(uv.x * depth / MP_FOCAL + truck, uv.y * depth / MP_FOCAL) * (3.5 - 0.8 * float(k));
        vec2 dc = floor(world);
        vec2 df = fract(world) - 0.5;
        float s = motionHash(dc + float(k) * 17.0);
        vec2 off = 0.3 * vec2(sin(s * 40.0 + uTime * 0.3), cos(s * 31.0 + uTime * 0.23));
        float mote = smoothstep(0.08, 0.0, length(df - off)) * step(0.7, s);
        col += sunTint * 0.05 * mote * (0.2 + 0.8 * toward) * (0.3 + 0.9 * treb);
    }

    if (!touchIdle()) {
        col += gEdgeCol * min(touchWake(uv), 3.0) * 0.04;
    }
    col *= 0.76 + 0.24 * smoothstep(2.2, 0.4, length(uv));
    fragColor = vec4(grade(filmic(col)), 1.0);
}
