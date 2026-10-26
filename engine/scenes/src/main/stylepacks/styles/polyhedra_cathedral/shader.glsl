#version 300 es
precision highp float;
precision highp sampler2D;

in vec2 vUv;
out vec4 fragColor;

//#include lib_scene_uniforms
//#include lib_scene_motion
//#include lib_palette
//#include lib_scene_grade
//#include lib_style
//#include lib_camera
//#include lib_sdf3
//#include lib_march3
//#include lib_poly3
//#include lib_path3
// The noise volume ripples the wet floor and thickens the mist; the blue noise staggers where each
// pixel's march starts, so the glow gathered along the rays never bands (manifest "inputs").
//#include lib_inputs

// Polyhedra Cathedral: a slow flight down the nave of a cathedral drawn in neon lattice, under pointed
// arches and groin vaults, past lattice columns, toward crossing after crossing where a wireframe
// polyhedron hangs beneath a geodesic lantern with a faceted golden sphere at its heart.
//
// It is the showcase of the 3D toolkit, and every part of it is a library call:
//
//   - the MARCH is lib_march3's over-relaxed trace, its steps lengthening with distance and checked
//     after the fact, and the neon halo is lib_march3's glowLine gathered along every ray;
//   - the POLYHEDRA are lib_poly3's exact wireframes: an icosahedron, a dodecahedron and a merkaba,
//     one per crossing in turn; the golden spheres are shaded with its geodesic facets, the lanterns
//     are its geodesic tiling, and the floor's rose under each crossing is its polar fold;
//   - the CAMERA rides lib_path3's wandering Catmull-Rom path down the nave, looking ahead along it
//     and banking into its sways, and takes the Camera's orbit, push and travel (lib_camera);
//   - the AMBIENT OCCLUSION and the FOG (a low mist that thins upward, in closed form) are lib_march3's.
//
// THE NAVE repeats every PC_CELL along +z: a pair of columns, a transverse pointed arch between them,
// the diagonal ribs of a groin vault, a ridge rib along the top and small wall arches. Mirrored across
// the centre line and within each cell, the whole vault is one arch evaluated four ways. Every
// PC_BAY_CELLS cells is a crossing, with its lantern, polyhedron and rose. The floor is dark wet
// stone that mirrors the neon (one short second march, the first thing the hot tiers drop).
//
// ---- what the music moves ---------------------------------------------------
//
//   - THE LATTICE'S EMISSION: the Edge Extra (its default Reaction lifts it on the treble) and the
//     passage's loudness (uSwell, the slowest signal) set how bright the neon burns. A hit sends a band
//     of light along the vault away from the camera (uSpawnAge): it travels, it never flashes.
//   - THE POLYHEDRA'S PULSE: the Size Extra (lifted on hits) and the smoothed bass swell each
//     polyhedron and its golden heart; Spin turns them through their clock (uXSpinTime).
//   - THE CAMERA'S TRAVEL: uFlight (time plus loudness, integrated on the CPU) and the Camera's travel,
//     which the default Reaction lets the bass push, so loud passages carry the flight on faster and
//     the speed only ever eases.
//
// A spike also walks the palette to a new plateau (uFormPhase), which glides there.
//
// ---- the No-flash budget ----------------------------------------------------
//
// The brightest repeats are the arches, one per PC_CELL. At the fastest this Style flies at Speed 1
// (about 2.5 units a second of flight when loud plus 2 of Camera travel) about one crosses a screen
// region a second. Every brightness the music moves is a smoothed signal.
//
// ---- periods ----------------------------------------------------------------
//
// The cells, the crossings, the path's control points and every hash repeat over PC_PERIOD (1000),
// the Camera travel's wrap, which divides the flight's (PC_FLIGHT_RATE puts 6000 units in a wrap of
// uFlight), so neither wrap moves anything. The noise volume tiles every 32 lattice units, and the
// floor reads it at 0.256 per unit along z: 256 lattice units, 8 whole tiles, per period.

#define PC_TAU 6.2831853
#define PC_FLIGHT_WRAP 6283.1853
#define PC_PERIOD 1000.0
/** One cell of the nave: a pair of columns and their arch. Divides PC_PERIOD. */
#define PC_CELL 4.0
#define PC_CELLS 250.0
/** A crossing every PC_BAY_CELLS cells: every 20 units, 50 per period. */
#define PC_BAY_CELLS 5.0
#define PC_BAY 20.0
#define PC_BAYS 50.0
/** Compile-time ceiling on the march: MarchBudget.MAX_STEPS. uSteps is the runtime budget. */
#define PC_MAX_STEPS 128
/** Compile-time ceiling on the floor's mirror march. */
#define PC_MIRROR_STEPS 24
#define PC_FAR 64.0
#define PC_FOCAL 1.25
/** The nave: columns at x = +-PC_HALF, arches springing at PC_SPRING. */
#define PC_HALF 3.0
#define PC_SPRING 3.2
#define PC_WALL 4.6
#define PC_RIB 0.045
#define PC_EYE 1.85
/** The manifest's "effects", by position: what the thermal tiers may switch off (uFxOff). */
#define PC_FX_REFLECTIONS 0
#define PC_FX_AO 1
#define PC_FX_DOME 2

#define MAT_FLOOR 0.0
#define MAT_WALL 1.0
#define MAT_LATTICE 2.0
#define MAT_POLY 3.0
#define MAT_GOLD 4.0

/**
 * The camera's distance down a flight path, integrated on the CPU (ShaderScene, #280). Declared here
 * rather than taken from lib_dmt, whose path and shading this Style does not use.
 */
uniform float uFlight;

const float PC_FLIGHT_RATE = 6000.0 / PC_FLIGHT_WRAP;
/** The transverse arch: offset of its two centres from the axis, radius, and the apex height. */
const float PC_ARCH_C = 1.3;
const float PC_APEX = PC_SPRING + 4.0989023; // sqrt((PC_HALF + PC_ARCH_C)^2 - PC_ARCH_C^2)
/** The diagonal rib spans half the cell's diagonal and meets the same apex. */
const float PC_DIAG = 3.6055513; // sqrt(PC_HALF^2 + (PC_CELL / 2)^2)
const float PC_DIAG_C = (16.8009998 - 13.0) / (2.0 * 3.6055513); // ((PC_APEX - PC_SPRING)^2 - PC_DIAG^2) / (2 PC_DIAG)
/** The crossing's lantern: a geodesic hemisphere on the vault's crown. */
const float PC_DOME_R = 2.6;
const float PC_DOME_FREQ = 4.0;
/** Where the polyhedron hangs, and its circumradius at rest. */
const float PC_POLY_Y = 4.0;
const float PC_POLY_R = 1.05;
/** The march's step fraction: the vault ribs are a slight overestimate off the rib plane. */
const float PC_STEP = 0.9;
/**
 * Each part of the world has a cheap bound and is evaluated in full only near it: when the bound is
 * closer than what is already nearest, or within PC_REACH, where its glow is still worth the cost.
 * Past that, the march takes the bound (a true lower bound) and the glow takes the bound plus a
 * margin: the wires are spread through the bounding volume, not on its skin, and a glow measured from
 * the skin would draw the volume as a glowing sheet.
 */
const float PC_REACH = 0.6;

float gMat;
float gLat;   // the distance to the lattice (ribs, columns, lantern), for its glow
float gPoly;  // the distance to the polyhedron's wires, for theirs
vec3 gLocal;  // the hit point in the golden sphere's own turning frame
mat3 gFrame;  // that frame's axes in the world, for turning its facets back out
float gPolyR;
float gSpin;
float gPulse;

/**
 * A pointed arch in its own plane: `q.x` across from the apex (0) to the springing (w), `q.y` up. Two
 * circular arcs whose centres sit `c` beyond the axis, so they meet at a point. The distance to the
 * arc where q's nearest circle point lies on it, else to the nearer end.
 */
float pcArch(vec2 q, float w, float c, float h0, float apex) {
    q.x = abs(q.x);
    vec2 v = q - vec2(-c, h0);
    float r = w + c;
    // The arc runs from angle 0 (the springing) up to the apex, where v.x = c.
    if (v.y >= 0.0 && v.x * (apex - h0) >= c * v.y) {
        return abs(length(v) - r);
    }
    return min(length(q - vec2(w, h0)), length(q - vec2(0.0, apex)));
}

/** The polyhedron of crossing `id`: an icosahedron, a dodecahedron, a merkaba, in turn. */
float pcPolyWire(vec3 p, float r, float id) {
    float kind = mod(id, 3.0);
    if (kind < 0.5) return wireIcosahedron(p, r);
    if (kind < 1.5) return wireDodecahedron(p, r);
    return wireMerkaba(p, r);
}

/** The crossing's turning frame: each polyhedron tumbles on its own tilted axis. */
mat3 pcPolyFrame(float id) {
    float h = hash11(id + 7.0);
    vec3 axis = normalize(vec3(0.4 + h, 1.0, 0.3 - h));
    return rotAxis(axis, gSpin + id * 1.7) * rotX(0.35 + 0.6 * h);
}

/** z within its cell, -PC_CELL / 2 to PC_CELL / 2 about the cell's columns. */
float pcCellZ(float z) {
    return z - PC_CELL * floor(z / PC_CELL + 0.5);
}

float map(vec3 p) {
    // Mirrored across the nave, and within each cell about its columns.
    vec3 q = vec3(abs(p.x), p.y, p.z);
    float zl = pcCellZ(p.z);
    float za = abs(zl);

    // ---- the shell: floor and walls ------------------------------------------
    float floorD = p.y;
    float wallD = PC_WALL - q.x;
    float d = min(floorD, wallD);
    gMat = floorD < wallD ? MAT_FLOOR : MAT_WALL;

    // ---- the lattice --------------------------------------------------------------
    float lat = 1e9;
    float glow = 1e9;
    // Columns: a lattice tower of four rods and rings, one at each side of each cell.
    float colBound = length(vec2(q.x - PC_HALF, zl)) - 0.22;
    if (colBound < max(d, PC_REACH)) {
        vec2 c = abs(vec2(q.x - PC_HALF, zl)) - 0.13;
        float rods = length(c) - 0.028;
        float ry = p.y - 0.55 * floor(p.y / 0.55 + 0.5);
        float rings = length(vec2(length(vec2(q.x - PC_HALF, zl)) - 0.17, ry)) - 0.02;
        float tower = max(min(rods, rings), p.y - PC_SPRING);
        lat = min(lat, tower);
        glow = min(glow, tower);
    } else {
        lat = min(lat, colBound);
        glow = min(glow, colBound + 0.1);
    }
    // The vault: everything above the springing line.
    float vaultBound = PC_SPRING - PC_RIB - p.y;
    if (vaultBound < max(min(d, lat), PC_REACH)) {
        // The transverse arch across the nave, on the column plane.
        float arch = length(vec2(pcArch(vec2(q.x, p.y), PC_HALF, PC_ARCH_C, PC_SPRING, PC_APEX), zl)) - PC_RIB;
        // The groin vault's diagonal ribs, from each column up to the bay's centre.
        vec2 o = vec2(q.x, za) - vec2(0.0, 0.5 * PC_CELL);
        vec2 dir = vec2(PC_HALF, -0.5 * PC_CELL) / PC_DIAG;
        float s = dot(o, dir);
        float e = dot(o, vec2(-dir.y, dir.x));
        float diag = length(vec2(pcArch(vec2(s, p.y), PC_DIAG, PC_DIAG_C, PC_SPRING, PC_APEX), e)) - PC_RIB * 0.8;
        // The ridge along the crown, and the small wall arches between the columns.
        float ridge = length(vec2(q.x, p.y - PC_APEX)) - PC_RIB * 0.8;
        float wallArch = length(vec2(pcArch(vec2(0.5 * PC_CELL - za, p.y), 0.5 * PC_CELL, 0.6, PC_SPRING, PC_SPRING + 2.5298221),
                                     q.x - PC_HALF)) - PC_RIB * 0.7;
        float vault = min(min(arch, diag), min(ridge, wallArch));
        lat = min(lat, vault);
        glow = min(glow, vault);
    } else {
        lat = min(lat, vaultBound);
        // Below the vault the nearest rib is at best where an arch springs from its column.
        glow = min(glow, length(vec2(vaultBound, min(za, abs(q.x - PC_HALF)))) + 0.3);
    }

    // ---- the crossing: lantern, polyhedron and golden heart -----------------------
    float bayId = floor(p.z / PC_BAY + 0.5);
    float bz = p.z - bayId * PC_BAY;
    bayId = mod(bayId, PC_BAYS);
    if (fxOn(PC_FX_DOME)) {
        vec3 dp = vec3(p.x, p.y - PC_APEX, bz);
        float shell = abs(length(dp) - PC_DOME_R) - 0.06;
        if (shell < max(min(d, lat), PC_REACH)) {
            float dome = wireGeodesic(dp, PC_DOME_R, PC_DOME_FREQ) * 0.8 - PC_RIB * 0.7;
            // The upper half only, closed by a ring where it meets the crown.
            dome = max(dome, -dp.y);
            float ring = length(vec2(length(dp.xz) - PC_DOME_R, dp.y)) - PC_RIB;
            lat = min(lat, min(dome, ring));
            glow = min(glow, min(dome, ring));
        } else {
            lat = min(lat, shell);
            glow = min(glow, shell + 0.3);
        }
    }
    gLat = glow;
    if (lat < d) {
        d = lat;
        gMat = MAT_LATTICE;
    }

    vec3 pp = vec3(p.x, p.y - PC_POLY_Y, bz);
    float r = gPolyR;
    float bound = length(pp) - r * 1.05;
    gPoly = 1e9;
    if (bound < max(d, PC_REACH)) {
        mat3 frame = pcPolyFrame(bayId);
        vec3 lp = pp * frame;
        float wire = pcPolyWire(lp, r, bayId) - 0.032;
        gPoly = wire;
        if (wire < d) {
            d = wire;
            gMat = MAT_POLY;
        }
        float gold = length(lp) - r * 0.42;
        if (gold < d) {
            d = gold;
            gMat = MAT_GOLD;
            gLocal = lp;
            gFrame = frame;
        }
    } else {
        gPoly = bound + 0.35 * r;
        d = min(d, bound);
    }

    // Golden spheres in the side aisles, one per bay of each cell, bobbing on the swell.
    vec3 sp = vec3(q.x - 2.05, p.y - 1.15 - 0.12 * sin(uTime * 0.6 + floor(p.z / PC_CELL) * 2.1), za - 0.5 * PC_CELL);
    float side = length(sp) - 0.3 * gPulse;
    if (side < d) {
        d = side;
        gMat = MAT_GOLD;
        mat3 turn = rotY(gSpin * 0.7);
        gLocal = sp * turn;
        // The aisle on the left is the right one mirrored.
        gFrame = mat3(vec3(p.x < 0.0 ? -1.0 : 1.0, 1.0, 1.0) * turn[0], vec3(p.x < 0.0 ? -1.0 : 1.0, 1.0, 1.0) * turn[1],
                      vec3(p.x < 0.0 ? -1.0 : 1.0, 1.0, 1.0) * turn[2]);
    }
    return d;
}

vec3 normalAt(vec3 p, float eps) {
    vec2 k = vec2(1.0, -1.0);
    return normalize(k.xyy * map(p + k.xyy * eps) + k.yyx * map(p + k.yyx * eps) + k.yxy * map(p + k.yxy * eps)
                     + k.xxx * map(p + k.xxx * eps));
}

/** The neon's colour at height y: the columns' hue below, the vault's above. */
vec3 pcNeon(float y, vec3 low, vec3 high) {
    return mix(low, high, smoothstep(1.0, PC_APEX, y));
}

/**
 * What a golden facet reflects: the nave seen from anywhere, roughly. Neon arches overhead (bands
 * along the reflected direction's depth), a glowing far end, the dark floor below.
 */
vec3 pcEnv(vec3 r, vec3 low, vec3 high) {
    float up = clamp(r.y, -1.0, 1.0);
    float bands = pow(0.5 + 0.5 * cos(atan(r.y, abs(r.z) + 1e-3) * 14.0), 6.0);
    vec3 vault = pcNeon(PC_APEX * (0.3 + 0.7 * up), low, high) * (0.25 + 1.6 * bands) * smoothstep(-0.1, 0.5, up);
    vec3 ends = mix(low, vec3(1.0), 0.4) * pow(abs(r.z), 8.0) * 0.8;
    vec3 ground = vec3(0.02, 0.018, 0.03) * (1.0 - smoothstep(-0.6, 0.0, up));
    return vault + ends + ground + 0.03;
}

void main() {
    vec2 uv = view();
    float bassA = min(uBassSmooth, 1.3);
    float trebA = min(uTrebleSmooth, 1.3);

    // ---- the music's state for map() -------------------------------------------------
    gSpin = 0.35 * uXSpinTime + 0.08 * uEnergyPhase;
    gPulse = 1.0 + 0.1 * bassA + 0.06 * uSwell;
    // Capped so even the largest never reaches down to the flight line.
    gPolyR = min(PC_POLY_R * xm(uXSize) * gPulse, 1.9);

    // ---- camera: the wandering path down the nave ---------------------------------
    vec3 ro0 = vec3(0.0, 0.0, mod(uFlight * PC_FLIGHT_RATE, PC_PERIOD));
    vec3 rdCam = normalize(vec3(uv, PC_FOCAL));
    cameraFlight(ro0, rdCam);
    float travel = mod(ro0.z, PC_PERIOD);
    vec3 tangent;
    vec3 bend;
    vec3 tangentAhead;
    vec3 bendAhead;
    vec3 eye = pathWander(travel, 8.0, 125.0, vec2(1.0, 0.3), 3.0, tangent, bend);
    vec3 ahead = pathWander(travel + 6.0, 8.0, 125.0, vec2(1.0, 0.3), 3.0, tangentAhead, bendAhead);
    vec3 ro = eye + vec3(0.0, PC_EYE, 0.0);
    vec3 target = ahead + vec3(0.0, PC_EYE + 0.55, 0.0);
    float bank = pathBank(tangent, bend, 4.0, 0.22) + 0.04 * sin(uTime * 0.11);
    vec3 rd = normalize(pathFrame(target - ro, bank) * rdCam);
    float cone = 2.0 / (uResolution.y * PC_FOCAL);

    // ---- colours ------------------------------------------------------------------
    float hueBase = 0.82 + 0.3 * (1.0 - abs(2.0 * uFormPhase - 1.0));
    vec3 neonLow = pal(hueBase + 0.45);
    vec3 neonHigh = pal(hueBase);
    vec3 polyCol = mix(pal(hueBase + 0.22), vec3(1.0), 0.25);
    const vec3 GOLD = vec3(1.0, 0.71, 0.29);
    float edge = clamp(uXEdge, 0.0, 2.0);
    float burn = (0.5 + 0.5 * edge) * (0.8 + 0.25 * trebA + 0.2 * uSwell);
    float polyBurn = 0.85 + 0.35 * bassA + 0.2 * uSpike;

    // ---- march ------------------------------------------------------------------------
    float jitter = texelFetch(uBlueNoise, ivec2(gl_FragCoord.xy) & 63, 0).r;
    March3 m = marchStart(0.05 + 0.25 * jitter, 1.45, 0.012);
    vec3 latGlow = vec3(0.0);
    float polyGlow = 0.0;
    for (int i = 0; i < PC_MAX_STEPS; i++) {
        if (float(i) >= uSteps) break;
        vec3 p = ro + rd * m.t;
        float d = map(p) * PC_STEP;
        latGlow += pcNeon(p.y, neonLow, neonHigh) * glowLine(gLat, 0.05, m.step);
        polyGlow += glowLine(gPoly, 0.06, m.step);
        if (marchStep(m, d, cone) || m.t > PC_FAR) break;
    }
    float t = min(m.t, PC_FAR);
    vec3 p = ro + rd * t;
    map(p);
    float mat = gMat;
    vec3 local = gLocal;
    mat3 frame = gFrame;

    // The band of light a hit sends down the vault, away from the camera.
    float rel = p.z - travel;
    float band = exp(-pow((rel - uSpawnAge * 12.0) * 0.35, 2.0)) * spawnFresh(2.0) * clamp(uBeatResponse, 0.0, 2.0);

    vec3 col = vec3(0.0);
    if (m.hit) {
        vec3 n = normalAt(p, max(0.0015 * t, 0.001));
        float ao = 1.0;
        if (fxOn(PC_FX_AO) && mat != MAT_LATTICE && mat != MAT_POLY) {
            float occ = 0.0;
            for (int k = 0; k < 3; k++) {
                float h = aoHeight(k, 0.9);
                occ += aoTap(k, h, map(p + n * h));
            }
            ao = aoResolve(occ, 1.0);
        }
        vec3 neon = pcNeon(p.y, neonLow, neonHigh);
        if (mat == MAT_LATTICE) {
            // A neon tube: a white-hot core in its own colour, brightest face on.
            float face = abs(dot(n, rd));
            col = neon * burn * (1.4 + 1.2 * band) * (0.6 + 0.6 * face) + vec3(1.0) * pow(face, 6.0) * 0.6 * burn;
        } else if (mat == MAT_POLY) {
            float face = abs(dot(n, rd));
            col = polyCol * polyBurn * (1.6 + 0.8 * face);
        } else if (mat == MAT_GOLD) {
            // Cut gold: each facet a flat mirror, tinted, with a Fresnel lift at grazing angles.
            // The facet's normal lives in the sphere's turning frame: turn it back out into the world.
            vec3 fw = normalize(frame * geodesicFacet(local, 3.0));
            vec3 r = reflect(rd, fw);
            float fres = pow(1.0 - max(dot(-rd, fw), 0.0), 5.0);
            vec3 f0 = GOLD;
            vec3 spec = f0 + (1.0 - f0) * fres;
            // Gold keeps its own colour: the neon it mirrors is mostly taken as light, a little as tint.
            vec3 env = pcEnv(r, neonLow * burn, neonHigh * burn);
            env = mix(vec3(dot(env, vec3(0.3333))), env, 0.35);
            col = spec * env * 1.8 * ao;
            // The arches' light on it, gathered by the glow the ray picked up on the way.
            col += GOLD * 0.08 * ao;
        } else if (mat == MAT_WALL) {
            // Dark stone with tall lancet windows of neon tracery between the columns.
            // Across from the window's centre line (the bay's middle), and up.
            float wx = abs(abs(pcCellZ(p.z)) - 0.5 * PC_CELL);
            float aa = 0.02 + 0.0015 * t;
            float outline = p.y > 2.8 ? pcArch(vec2(wx, p.y), 1.1, 0.5, 2.8, 4.3206) : max(abs(wx - 1.1), 1.0 - p.y);
            float inside = step(wx, 1.1) * step(1.0, p.y) * step(length(vec2(wx + 0.5, max(p.y - 2.8, 0.0))), 1.6);
            vec2 lat = abs(fract(vec2(wx + p.y, wx - p.y) * 1.8) - 0.5);
            float tracery = smoothstep(aa * 1.8, 0.0, min(lat.x, lat.y));
            col = vec3(0.012, 0.01, 0.02) * ao;
            col += neon * (smoothstep(aa * 2.0, 0.0, outline) * 1.2 + tracery * inside * 0.3) * burn;
        } else {
            // The wet floor: dark stone, inlaid neon lines along the nave and a rose under each crossing.
            vec3 base = vec3(0.012, 0.011, 0.018);
            float bz = p.z - PC_BAY * floor(p.z / PC_BAY + 0.5);
            vec2 rose = foldPolar(vec2(p.x, bz), 12.0);
            float petals = abs(length(rose - vec2(1.25, 0.0)) - 0.9);
            float rings = min(abs(length(rose) - 2.1), abs(length(rose) - 0.6));
            float roseLine = smoothstep(0.03 + 0.002 * t, 0.0, min(petals, rings)) * step(length(rose), 2.2);
            float lane = smoothstep(0.025 + 0.002 * t, 0.0, abs(abs(p.x) - 1.6));
            float tile = smoothstep(0.02 + 0.002 * t, 0.0, abs(pcCellZ(p.z))) * step(abs(p.x), 2.9);
            col = base * ao + (neonHigh * roseLine * 0.7 + neonLow * (lane * 0.5 + tile * 0.25)) * burn;

            if (fxOn(PC_FX_REFLECTIONS)) {
                // The mirror: a short second march up from the floor, gathering the glow it passes and the
                // emission of what it hits. The stone's ripple bends it a little.
                vec3 np = vec3(p.x, 0.0, p.z * 0.256) * 2.0;
                vec2 ripple = vec2(vnoiseVol(np), vnoiseVol(np + vec3(11.3, 0.0, 5.7))) - 0.5;
                vec3 fn = normalize(vec3(ripple.x * 0.035, 1.0, ripple.y * 0.035));
                vec3 rr = reflect(rd, fn);
                March3 mr = marchStart(0.06, 1.6, 0.03);
                vec3 rGlow = vec3(0.0);
                float rPoly = 0.0;
                for (int i = 0; i < PC_MIRROR_STEPS; i++) {
                    vec3 q = p + rr * mr.t;
                    float dr = map(q) * PC_STEP;
                    rGlow += pcNeon(q.y, neonLow, neonHigh) * glowLine(gLat, 0.05, mr.step);
                    rPoly += glowLine(gPoly, 0.06, mr.step);
                    if (marchStep(mr, dr, cone * 4.0) || mr.t > 24.0) break;
                }
                vec3 refl = rGlow * burn * 0.6 + polyCol * rPoly * polyBurn * 0.5;
                if (mr.hit) {
                    vec3 hq = p + rr * mr.t;
                    if (gMat == MAT_LATTICE) refl += pcNeon(hq.y, neonLow, neonHigh) * burn * 1.2;
                    else if (gMat == MAT_POLY) refl += polyCol * polyBurn * 1.2;
                    else if (gMat == MAT_GOLD) refl += GOLD * 0.6 * burn;
                }
                float fres = 0.12 + 0.88 * pow(1.0 - max(-rd.y, 0.0), 5.0);
                col += refl * fres * exp(-mr.t * 0.05);
            }
        }
    }

    // ---- the glow gathered along the ray ---------------------------------------------
    col += latGlow * burn * (0.22 + 0.25 * band);
    col += polyCol * polyGlow * polyBurn * 0.3;

    // ---- fog: a low mist that thins upward, and the far nave's haze ----------------------
    vec3 haze = mix(neonHigh, neonLow, 0.5) * 0.05;
    float far = fogDistance(t, 0.028);
    float mistNoise = vnoiseVol(vec3(ro.x + rd.x * 6.0, 0.6, (ro.z + rd.z * 6.0) * 0.256) * 1.5 + vec3(0.0, uTime * 0.05, 0.0));
    float mist = fogHeight(ro, rd, t, 0.0, 0.07 * xm(uXFog) * (0.6 + 0.8 * mistNoise), 0.9);
    vec3 mistCol = fogColor(rd, vec3(0.0, 0.0, 1.0), neonLow * 0.08, neonHigh * 0.25 * burn, 0.6);
    col = mix(col, haze, far * clamp(0.7 * xm(uXFog), 0.0, 1.0));
    col = mix(col, mistCol, clamp(mist, 0.0, 0.85));

    // ---- finish ---------------------------------------------------------------------------
    col = vec3(1.0) - exp(-col * 1.3);
    col *= 0.74 + 0.26 * smoothstep(1.9, 0.4, length(vUv * 2.0 - 1.0));
    col += (jitter - 0.5) / 255.0;
    fragColor = vec4(grade(col), 1.0);
}
