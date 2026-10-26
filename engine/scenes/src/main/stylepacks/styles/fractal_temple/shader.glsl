#version 300 es
precision highp float;
precision highp sampler2D;

in vec2 vUv;
out vec4 fragColor;

//#include lib_scene_uniforms
//#include lib_style
//#include lib_scene_motion
//#include lib_palette
//#include lib_scene_grade
//#include lib_sdf3
//#include lib_march3
//#include lib_poly3
//#include lib_touch
//#include lib_camera

// Fractal Temple: a walk down a stepped causeway through an endless nave of geodesic domes. Each bay
// is a wire dome of glowing struts closing overhead; where two bays meet hangs a ring of lamps the
// causeway runs through; filigree spires of stacked rings and ribs stand off either side of the way,
// each with a star at its tip, and the dark water below holds the glow.
//
// Everything is marched in 3D (lib_march3). The struts, lamps and lace are thin, so most of their light
// is gathered along the ray as it passes them (glowLine) rather than shaded where it hits; the causeway
// and the spires' skins are solid and shaded. The nave repeats every BAY units along z, so the walk is
// endless and the Camera's 1000-unit travel wrap lands on a whole number of bays.
//
// ---- the camera ----------------------------------------------------------------------------------
//
// The walk runs on its own monotonic clock (uFlowPhase) down +z; the Camera's travel and push-in add to
// it and its orbit turns the gaze (cameraFlight), the lens and the roll come through cameraView().
//
// ---- the music -----------------------------------------------------------------------------------
//
// uSwell lights the domes over seconds, uBassSmooth the lamp rings, uTrebleSmooth the spires' lace. A
// spike re-seeds which facets of the domes are lit and which spires stand (uSpawnSeed, grown in over a
// second), steps the domes' tessellation to a new plateau (uFormPhase) and leans the walk (uMoveDir);
// the stars on the spires swell on uSpike, which rises over a tenth of a second. Nothing flashes.

#define FT_TAU 6.2831853
/** One bay of the nave along z; 1000 / BAY is whole. */
#define FT_BAY 10.0
/** The causeway's top, the water, and the dome's radius (under half a bay, so domes never cross). */
#define FT_DECK -0.6
#define FT_WATER -1.6
#define FT_DOME 4.7
/** The lamp ring's height and radius, and where the spires stand off the way. */
#define FT_RING_Y 0.7
#define FT_RING_R 1.7
#define FT_SPIRE_X 2.9
#define FT_SPIRE_GAP 2.5
#define FT_FOCAL 1.25
#define FT_EYE 0.05
#define FT_FAR 42.0
#define FT_MAX_STEPS 110

// Set once per pixel in main(), read by map().
float gFreq;
float gSeed;

// What map() found nearest, for the glow and the shading: the dome's struts, the lamp ring, the spires'
// lace, the causeway's lit edges, and which solid the hit is (0 water, 1 causeway, 2 spire).
float gStrut;
float gRing;
float gLace;
float gEdge;
float gTip;
float gMat;
vec3 gBay;

/** A filigree spire standing at the origin, `height` tall: rings stacked up a cone, joined by ribs. */
float spire(vec3 q, float height, float width, float tiers, out float lace, out float tip) {
    float y = clamp(q.y / height, 0.0, 1.0);
    float taper = width * (1.0 - 0.92 * y);
    float rr = length(q.xz);
    // The rings: tori up the cone, one each tier.
    float cell = height / tiers;
    float ry = q.y - cell * (floor(q.y / cell) + 0.5);
    float rings = length(vec2(rr - taper, ry)) - 0.022;
    // Six ribs climbing the cone.
    vec2 rib = foldPolar(q.xz, 6.0);
    float ribs = length(vec2(rib.y, (rib.x - taper) * 0.9)) - 0.018;
    lace = max(min(rings, ribs), max(-q.y, q.y - height));
    // A dim skin under the lace, a little inside it, so the tower is solid.
    float skin = max((rr - taper * 0.82) * 0.9, max(-q.y, q.y - height));
    tip = length(q - vec3(0.0, height + 0.12, 0.0));
    return min(lace, skin);
}

float map(vec3 p) {
    // The bay this point is in, and where in it.
    float bayId = floor(p.z / FT_BAY);
    vec3 q = vec3(p.x, p.y, p.z - (bayId + 0.5) * FT_BAY);
    gBay = vec3(bayId, 0.0, 0.0);

    // The dome: a geodesic wire hemisphere standing on the causeway's level.
    vec3 dq = q - vec3(0.0, FT_DECK, 0.0);
    gStrut = max(wireGeodesic(dq, FT_DOME, gFreq), -dq.y);
    float d = gStrut - 0.028;

    // The lamp ring where this bay meets the next.
    vec3 rq = vec3(p.x, p.y - FT_RING_Y, p.z - (floor(p.z / FT_BAY + 0.5)) * FT_BAY);
    gRing = length(vec2(length(rq.xy) - FT_RING_R, rq.z));
    d = min(d, gRing - 0.05);

    // The causeway, its treads, and its two lit edges.
    float deck = max(abs(p.x) - 1.1, abs(p.y - FT_DECK + 0.35) - 0.35);
    float tz = p.z - 0.8 * (floor(p.z / 0.8) + 0.5);
    float tread = max(length(vec2(p.y - FT_DECK, tz)) - 0.035, abs(p.x) - 1.1);
    gEdge = length(vec2(abs(p.x) - 1.1, p.y - FT_DECK));
    float solid = min(deck, tread);
    gMat = 1.0;

    // The water.
    float water = p.y - FT_WATER;
    if (water < solid) {
        solid = water;
        gMat = 0.0;
    }

    // The spires, mirrored to both sides, one every FT_SPIRE_GAP along the way.
    float sid = floor(p.z / FT_SPIRE_GAP);
    vec3 sq = vec3(abs(p.x) - FT_SPIRE_X, p.y - FT_WATER, p.z - (sid + 0.5) * FT_SPIRE_GAP);
    // Seeded by the spire's place in 100 units of the way, so the travel's wrap never re-rolls one.
    float seed = hash11(mod(sid, 40.0) * 7.13 + (p.x < 0.0 ? 3.7 : 0.0) + gSeed);
    float lace;
    float tip;
    float tower = spire(sq, 2.2 + 1.6 * seed, 0.32 + 0.12 * seed, 7.0 + floor(seed * 5.0), lace, tip);
    gLace = lace;
    gTip = tip;
    if (tower < solid) {
        solid = tower;
        gMat = 2.0;
    }
    return min(d, solid);
}

vec3 normalAt(vec3 p, float e) {
    vec2 k = vec2(1.0, -1.0);
    return normalize(k.xyy * map(p + k.xyy * e) + k.yyx * map(p + k.yyx * e) + k.yxy * map(p + k.yxy * e) +
                     k.xxx * map(p + k.xxx * e));
}

void main() {
    vec2 uv = cameraView();

    float bass = clamp(uBassSmooth, 0.0, 1.5);
    float treb = clamp(uTrebleSmooth, 0.0, 1.5);
    float swell = clamp(uSwell, 0.0, 1.5);
    gFreq = floor(clamp((2.0 + floor(uFormPhase * 3.0)) * xm(uXScale), 1.0, 8.0) + 0.5);
    gSeed = floor(uSpawnSeed * 61.0);

    // ---- the walk ------------------------------------------------------------------------------------
    vec2 lens = uv;
    if (!touchIdle()) lens -= touchAnchor() * (0.3 * touchStrength());
    vec3 ro = vec3(0.32 * uMoveDir.x, FT_DECK + 0.62 + 0.04 * sin(uTime * 0.3), mod(uFlowPhase * 1.1, 1000.0));
    // A gentle look up into the domes.
    vec3 rd = normalize(vec3(lens, FT_FOCAL));
    rd = rotX(-0.12) * rd;
    cameraFlight(ro, rd);
    ro.z = mod(ro.z, 1000.0);
    rd = normalize(rd);
    float cone = 2.0 / (uResolution.y * FT_FOCAL);

    // ---- the march --------------------------------------------------------------------------------------
    vec3 strutCol = pal(0.58);
    vec3 litCol = pal(0.90);
    vec3 lampCol = pal(0.92);
    vec3 laceCol = pal(0.86);
    vec3 edgeCol = pal(0.42);
    vec3 glow = vec3(0.0);
    float lit = 0.0;
    March3 m = marchStart(FT_EYE, 1.3, 0.01);
    float steps = 0.0;
    for (int i = 0; i < FT_MAX_STEPS; i++) {
        if (float(i) >= uSteps) break;
        vec3 p = ro + rd * m.t;
        float d = map(p);
        float fade = exp(-m.t * 0.06);
        // A strut's facet is lit or not by the spawn that chose it, grown in over a second.
        vec3 dq = vec3(p.x, p.y - FT_DECK, p.z - (gBay.x + 0.5) * FT_BAY);
        float facet = hash13(floor(normalize(dq + 1e-4) * gFreq * 2.0) + vec3(mod(gBay.x, 100.0), gSeed, 0.0));
        lit = step(0.8, facet) * spawnGrow(1.1);
        glow += mix(strutCol, litCol, 0.7 * lit) * glowLine(gStrut, 0.022, m.step) * (0.35 + 0.45 * swell + 0.2 * lit) * fade;
        float bead = 0.5 + 0.5 * cos(atan(p.y - FT_RING_Y, p.x) * 26.0 + uFlowPhase * 1.4);
        glow += lampCol * glowLine(gRing, 0.06, m.step) * (0.4 + 1.2 * bead * bead) * (0.6 + 0.6 * bass) * fade;
        glow += edgeCol * glowLine(gEdge, 0.03, m.step) * (0.5 + 0.4 * treb) * fade;
        glow += laceCol * glowLine(gLace, 0.025, m.step) * (0.25 + 0.6 * treb) * fade;
        glow += mix(vec3(1.0), laceCol, 0.3) * glowLine(gTip, 0.04, m.step) * (0.5 + 0.7 * uSpike) * spawnGrow(0.9) * fade;
        steps += 1.0;
        if (marchStep(m, d, cone) || m.t > FT_FAR) break;
    }

    // ---- what the ray stopped on --------------------------------------------------------------------------
    vec3 sky = mix(vec3(0.01, 0.0, 0.03), pal(0.62) * 0.08, smoothstep(-0.1, 0.6, rd.y));
    vec3 col = sky;
    if (m.hit) {
        vec3 p = ro + rd * m.t;
        map(p);
        float mat = gMat;
        vec3 n = normalAt(p, max(0.002, 0.002 * m.t));
        vec3 toLight = normalize(vec3(0.0, 1.2, 1.0));
        float diff = max(dot(n, toLight), 0.0);
        float ao = aoFromSteps(steps, uSteps);
        if (mat < 0.5) {
            // The water: dark, holding a sheen of the lamps overhead.
            col = vec3(0.005, 0.008, 0.02) + lampCol * 0.05 * (0.5 + 0.5 * sin(p.z * 0.6 + p.x * 2.0));
        } else if (mat < 1.5) {
            vec3 stone = mix(pal(0.60), pal(0.46), 0.5 + 0.5 * sin(p.z * 0.05));
            col = stone * (0.05 + 0.25 * diff) * ao;
        } else {
            col = pal(0.94) * (0.04 + 0.18 * diff) * ao;
        }
        col = mix(col, sky, fogDistance(m.t, 0.05));
    }
    col += glow;

    // The glow the next lamp ring throws down the way.
    col += lampCol * 0.05 * (0.4 + 0.4 * bass) * smoothstep(0.6, 1.0, rd.z);

    // ---- on the glass ---------------------------------------------------------------------------------
    col += mix(pal(0.50), vec3(1.0), 0.35) * fluidMotes(uv * 0.9, 5.5, 0.15) * 0.18;
    if (!touchIdle()) {
        col += pal(0.5 + 0.15 * sin(uTime * 0.05)) * min(touchWake(uv), 3.0) * 0.05;
    }
    col = col / (1.0 + 0.3 * col);
    vec2 sv = (vUv - 0.5) * vec2(uResolution.x / max(uResolution.y, 1.0), 1.0) * 2.0;
    col *= 0.66 + 0.34 * smoothstep(2.2, 0.5, length(sv));
    fragColor = vec4(grade(col), 1.0);
}
