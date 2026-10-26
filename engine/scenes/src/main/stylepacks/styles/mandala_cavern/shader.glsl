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

// Mandala Cavern: a flight down a bending cave whose walls are tiled with
// neon mandalas, along a glowing walkway between rows of spotted caps,
// toward a pink portal ring that throws light streaks back up the tube.
//
// THE WORLD is a cylinder bent onto a path of three slow sines, with a floor
// cut across its lower part. The distance to it is cheap (a length and a
// subtraction), so the march converges in a dozen or two steps; the caps are
// half-buried ellipsoids repeated along the floor in two rows.
//
// THE WALL is drawn in (angle, depth): one mandala per cell, petals, rings, a
// ring of dots and a centre stone, with crescents in the gaps. It is neon on
// a dark ground, lit a little by a headlamp and by the portal, and every line
// is anti-aliased against its own pixel footprint. Depth fog takes the far
// tube to the portal's haze, which is also what keeps distant ornament from
// shimmering.
//
// THE CAMERA rides the path a little above the walkway, aims a few units
// ahead along it (a pilot's lead, so it anticipates the turns) and banks
// into them. The Camera controls turn and push it (lib_camera).
//
// ---- what the music moves ---------------------------------------------------
//
// The speed is uFlight plus the Camera's travel, both integrated on the CPU;
// the default Reaction lets the bass push the travel, so the bass drives the
// speed and it only ever eases. A hit sends a band of light down the walls
// away from the camera (uSpawnAge) and its Reaction on Edge lifts the neon a
// little; a spike walks the palette to a new plateau. Nothing flashes: the
// band moves, and the lift is a share of the lines, not of the frame.
//
// ---- the No-flash budget ----------------------------------------------------
//
// Bright repeats crossing a screen region pass at speed / spacing. One
// mandala per MC_CELL along the tube, at the fastest travel this Style flies
// at Speed 1 (about 3.2 units a second of flight at full loudness plus 2 of
// Camera travel), passes about 2 a second.
//
// ---- periods ----------------------------------------------------------------
//
// The path, the cells and their hashes all repeat over MC_PERIOD (1000), which
// is the Camera travel's wrap and divides the flight's (MC_FLIGHT_RATE puts
// 4000 units in a wrap of uFlight), so neither wrap moves anything.

#define MC_TAU 6.2831853
#define MC_FLIGHT_WRAP 6283.1853
#define MC_PERIOD 1000.0
/** The depth of one ornament cell, in world units. Divides MC_PERIOD. */
#define MC_CELL 2.5
/** MC_PERIOD / MC_CELL: the cells in a period, so a cell's hash wraps with it. */
#define MC_CELLS 400.0
/** Compile-time ceiling on the march: MarchBudget.MAX_STEPS. uSteps is the runtime budget. */
#define MC_MAX_STEPS 128
#define MC_FAR 42.0
#define MC_RADIUS 2.0
#define MC_FLOOR 1.35
#define MC_EYE 0.45
#define MC_FOCAL 1.15
/** The manifest's "effects", by position: what the thermal tiers may switch off (uFxOff). */
#define MC_FX_STREAKS 0
#define MC_FX_CAPS 1

#define MAT_WALL 0.0
#define MAT_FLOOR 1.0
#define MAT_CAP 2.0

/**
 * The camera's distance down a flight path, integrated on the CPU (ShaderScene, #280). Declared here
 * rather than taken from lib_dmt, whose path and shading this Style does not use.
 */
uniform float uFlight;

const float MC_FLIGHT_RATE = 4000.0 / MC_FLIGHT_WRAP;
/** The path's angular frequencies: whole cycles per MC_PERIOD (20, 33 and 26). */
const vec3 MC_PATH_W = vec3(20.0, 33.0, 26.0) * MC_TAU / MC_PERIOD;
/**
 * The march's step fraction. The path's steepest slope is under 0.31, so the bent tube's distance has
 * a gradient under 1.05; the caps' ellipsoid bound is exact up to its own scale. 0.8 leaves margin.
 */
const float MC_STEP = 0.8;

float gMat;
float gCell;

vec2 mcPath(float z) {
    return vec2(0.9 * sin(z * MC_PATH_W.x) + 0.45 * sin(z * MC_PATH_W.y + 1.3), 0.6 * sin(z * MC_PATH_W.z + 0.7));
}

/** The path's sideways acceleration, which the camera banks against. */
float mcPathBend(float z) {
    return -0.9 * MC_PATH_W.x * MC_PATH_W.x * sin(z * MC_PATH_W.x) - 0.45 * MC_PATH_W.y * MC_PATH_W.y * sin(z * MC_PATH_W.y + 1.3);
}

float map(vec3 p) {
    vec2 c = mcPath(p.z);
    vec2 v = p.xy - c;
    float wall = MC_RADIUS - length(v);
    float floorD = v.y + MC_FLOOR;
    float d = min(wall, floorD);
    gMat = wall < floorD ? MAT_WALL : MAT_FLOOR;
    gCell = 0.0;
    if (fxOn(MC_FX_CAPS)) {
        // Two rows of caps, offset half a cell from each other.
        float side = v.x < 0.0 ? -1.0 : 1.0;
        float zs = p.z + (side > 0.0 ? 0.5 * MC_CELL : 0.0);
        float cell = floor(zs / MC_CELL);
        float h = motionHash(vec2(mod(cell, MC_CELLS), side + 4.0));
        vec3 q = vec3(abs(v.x) - 0.95 - 0.12 * h, v.y + MC_FLOOR, mod(zs, MC_CELL) - 0.5 * MC_CELL);
        vec3 r = vec3(0.42, 0.3, 0.42) * (0.75 + 0.45 * h);
        float cap = (length(q / r) - 1.0) * min(r.x, min(r.y, r.z));
        // Some cells are empty: their cap is pushed far below the floor.
        cap += step(h, 0.22) * 10.0;
        if (cap < d) {
            d = cap;
            gMat = MAT_CAP;
            gCell = h;
        }
    }
    return d;
}

/** A line `w` wide at distance `d`, anti-aliased over `aa`. */
float mcLine(float d, float w, float aa) {
    return 1.0 - smoothstep(w - aa, w + aa, d);
}

/**
 * One mandala at `m` (metric units, its centre at 0), `aa` the pixel footprint there. Returns the neon
 * in rgb and the ornament's coverage in a (how much of the ground it hides).
 */
vec4 mcMandala(vec2 m, float hue, float seed, float aa, float edge, float sparkle) {
    float r = length(m);
    float a = atan(m.y, m.x);
    float petals = 8.0 + 4.0 * floor(seed * 2.0);
    vec3 c1 = pal(hue);
    vec3 c2 = pal(hue + 0.33);
    vec3 c3 = pal(hue + 0.55);
    vec3 col = vec3(0.0);
    float cover = 0.0;
    float w = 0.022 * edge;
    // Outer petals: a scalloped outline with a second, inner echo.
    float rp = 0.6 + 0.085 * cos(petals * a);
    float petal = mcLine(abs(r - rp), w, aa);
    float echo = mcLine(abs(r - rp * 0.86), w * 0.7, aa);
    // Petal veins, faded toward the rim.
    float vein = mcLine(abs(sin(petals * 0.5 * a)) * r, w * 0.6, aa) * smoothstep(0.62, 0.3, r) * step(0.3, r);
    // Rings and a ring of dots between them.
    float rings = mcLine(abs(r - 0.42), w, aa) + mcLine(abs(r - 0.25), w * 0.8, aa);
    float dots = 12.0;
    float cellA = (floor(a / MC_TAU * dots) + 0.5) / dots * MC_TAU;
    float dotD = length(m - 0.335 * vec2(cos(cellA), sin(cellA)));
    float dotMask = 1.0 - smoothstep(0.035 - aa, 0.035 + aa, dotD);
    // The centre stone, with a dark pupil.
    float stone = 1.0 - smoothstep(0.15 - aa, 0.15 + aa, r);
    float pupil = 1.0 - smoothstep(0.06 - aa, 0.06 + aa, r);
    col += c1 * (petal + 0.6 * echo);
    col += c2 * (0.55 * vein + rings);
    col += mix(c3, vec3(1.0), 0.5) * dotMask * (0.7 + 0.6 * sparkle);
    col += c3 * stone * (1.0 - pupil) * 0.9;
    cover = clamp(petal + echo + rings + dotMask + stone, 0.0, 1.0);
    return vec4(col, cover);
}

void main() {
    vec2 uv = view();
    float trebA = min(uTrebleSmooth, 1.3);

    // ---- camera -----------------------------------------------------------------
    vec3 ro0 = vec3(0.0, 0.0, mod(uFlight * MC_FLIGHT_RATE, MC_PERIOD));
    vec3 rdCam = normalize(vec3(uv, MC_FOCAL));
    cameraFlight(ro0, rdCam);
    float travel = mod(ro0.z, MC_PERIOD);
    vec3 ro = vec3(mcPath(travel) + vec2(0.0, -MC_EYE), travel);
    vec3 target = vec3(mcPath(travel + 3.5) + vec2(0.0, -MC_EYE + 0.12), travel + 3.5);
    vec3 fwd = normalize(target - ro);
    // Lean into the turn, plus a slow free sway.
    float bank = clamp(mcPathBend(travel + 2.0) * 1.8, -0.35, 0.35) + 0.06 * sin(uTime * 0.13);
    vec3 up0 = vec3(sin(bank), cos(bank), 0.0);
    vec3 right = normalize(cross(up0, fwd));
    vec3 up = cross(fwd, right);
    vec3 rd = normalize(mat3(right, up, fwd) * rdCam);

    // ---- march ------------------------------------------------------------------
    float t = 0.05;
    for (int i = 0; i < MC_MAX_STEPS; i++) {
        if (float(i) >= uSteps) break;
        float d = map(ro + rd * t);
        if (d < 0.0006 * t + 0.0003 || t > MC_FAR) break;
        t += d * MC_STEP;
    }
    t = min(t, MC_FAR);
    vec3 p = ro + rd * t;
    map(p);
    float mat = gMat;
    float capSeed = gCell;

    // ---- shading (unconditional, so the derivatives below are defined) ---------
    float hueBase = 0.86 + 0.35 * (1.0 - abs(2.0 * uFormPhase - 1.0));
    vec2 c = mcPath(p.z);
    vec2 v = p.xy - c;
    float edge = clamp(uXEdge, 0.0, 2.0);
    float rel = p.z - travel;
    vec3 n;
    vec3 emis = vec3(0.0);
    vec3 albedo;

    // The wall's (around, along) in metric units: continuous, so their
    // footprints are measured before the cells fold them.
    float sectors = xcount(8.0);
    float arc = MC_TAU * MC_RADIUS / sectors;
    float around = (atan(v.y, v.x) / MC_TAU * sectors + 0.05 * uXSwirl * rel) * arc;
    float along = p.z;
    // atan's seam (behind the left wall) would read as an infinitely wide
    // footprint: measure the angle's derivative on a copy turned half a turn
    // and keep whichever of the two is continuous here.
    float aroundFlip = atan(-v.y, -v.x) / MC_TAU * sectors * arc;
    float aaWall = max(min(fwidth(around), fwidth(aroundFlip)), fwidth(along)) * 0.75 + 1e-4;
    vec2 cellId = vec2(floor(around / arc), mod(floor(along / MC_CELL), MC_CELLS));
    vec2 m = vec2(mod(around, arc) - 0.5 * arc, mod(along, MC_CELL) - 0.5 * MC_CELL);
    float seed = motionHash(cellId + vec2(17.0, 3.0));
    float scale = xm(uXScale);
    vec4 orn = mcMandala(m / (scale * arc * 0.5 / 0.75), hueBase + 0.08 * seed, seed, aaWall / (scale * arc * 0.5 / 0.75), edge,
                         trebA * motionHash(cellId + floor(uTime * 3.0)));
    // Crescents in the gaps between mandalas, at the cell's ends.
    vec2 g = vec2(m.x, abs(m.y) - 0.5 * MC_CELL);
    float moonA = length(g) - 0.36;
    float moonB = length(g - vec2(0.0, 0.2)) - 0.32;
    float moon = 1.0 - smoothstep(-aaWall, aaWall, max(moonA, -moonB));
    vec3 wallNeon = orn.rgb + pal(hueBase + 0.45) * moon * 0.9;
    // A fine diamond lattice on the ground, dim.
    vec2 lat = abs(fract(vec2(around, along) * 2.2) - 0.5);
    float lattice = mcLine(abs(lat.x + lat.y - 0.5), 0.03, aaWall * 2.2) * 0.12;
    vec3 wallGround = pal(hueBase + 0.62) * 0.06 + pal(hueBase + 0.2) * lattice;

    if (mat == MAT_WALL) {
        n = normalize(vec3(-v, 0.0));
        albedo = wallGround;
        emis = wallNeon;
    } else if (mat == MAT_FLOOR) {
        n = vec3(0.0, 1.0, 0.0);
        // The walkway: a glowing strip with chevrons, one per cell.
        float x = abs(v.x);
        float aaF = fwidth(v.x) + 1e-4;
        float strip = 1.0 - smoothstep(0.3 - aaF, 0.3 + aaF, x);
        float chev = mcLine(abs(mod(p.z - x * 1.4, MC_CELL) - 0.5 * MC_CELL), 0.08, fwidth(p.z) + aaF);
        float rails = mcLine(abs(x - 0.3), 0.025, aaF);
        albedo = pal(hueBase + 0.62) * 0.08;
        emis = pal(hueBase + 0.45) * (strip * (0.25 + 0.8 * chev) + rails * 1.2);
        emis += wallNeon * 0.35 * (1.0 - strip);
    } else {
        // A cap: deep red with white spots, lit.
        vec3 r = vec3(0.42, 0.3, 0.42) * (0.75 + 0.45 * capSeed);
        float side = v.x < 0.0 ? -1.0 : 1.0;
        float zs = p.z + (side > 0.0 ? 0.5 * MC_CELL : 0.0);
        vec3 q = vec3(abs(v.x) - 0.95 - 0.12 * capSeed, v.y + MC_FLOOR, mod(zs, MC_CELL) - 0.5 * MC_CELL);
        vec3 nq = normalize(q / (r * r));
        n = normalize(vec3(nq.x * side, nq.y, nq.z));
        vec3 sq = normalize(q / r);
        // Spots on a jittered grid over the cap's surface.
        vec2 sp = vec2(atan(sq.z, sq.x) * 2.2, sq.y * 6.0);
        vec2 spc = fract(sp) - 0.5;
        float spot = 1.0 - smoothstep(0.2, 0.27, length(spc + 0.15 * (vec2(motionHash(floor(sp)), motionHash(floor(sp) + 9.0)) - 0.5)));
        albedo = mix(vec3(0.5, 0.03, 0.12) * (0.7 + 0.6 * capSeed), vec3(0.95), spot * step(0.25, sq.y));
        emis = vec3(0.0);
    }

    // Light: a soft headlamp from the camera and the portal far down the tube.
    vec3 toEye = -rd;
    float lamp = 1.0 / (1.0 + 0.04 * t * t);
    float diff = max(dot(n, toEye), 0.0);
    vec3 portalDir = normalize(vec3(mcPath(travel + 40.0), travel + 40.0) - p);
    vec3 portalCol = mix(pal(hueBase + 0.05), vec3(1.0, 0.4, 0.7), 0.4);
    float pdiff = max(dot(n, portalDir), 0.0) * 0.35;
    vec3 h = normalize(toEye + portalDir);
    float spec = pow(max(dot(n, h), 0.0), 40.0) * (mat == MAT_CAP ? 0.6 : 0.1);
    vec3 col = albedo * (vec3(0.9) * diff * lamp * 1.6 + portalCol * pdiff + 0.05) + portalCol * spec * lamp;

    // A hit's band of light travels away down the walls and fades as it goes.
    float band = exp(-pow((rel - uSpawnAge * 14.0) * 0.9, 2.0)) * spawnFresh(1.6) * clamp(uBeatResponse, 0.0, 2.0);
    col += emis * (0.85 + 0.9 * band) * (0.65 + 0.35 * edge);

    // Depth: the far tube sinks into the portal's haze.
    vec3 haze = mix(pal(hueBase + 0.62) * 0.05, portalCol * 0.16, 0.5);
    float fogAmt = 1.0 - exp(-t * 0.05 * xm(uXFog));
    col = mix(col, haze, fogAmt);

    // The portal: a ring at the end of the tube, seen through whatever fog is left.
    vec3 portalView = normalize(vec3(mcPath(travel + MC_FAR), travel + MC_FAR) - ro);
    float pa = acos(clamp(dot(rd, portalView), -1.0, 1.0));
    float seen = smoothstep(0.55, 0.95, fogAmt);
    float pix = 2.0 / (uResolution.y * MC_FOCAL);
    float ring = exp(-pow((pa - 0.06) / max(0.007, pix), 2.0));
    float halo = exp(-max(pa - 0.06, 0.0) * 18.0) * step(0.06, pa);
    float hole = smoothstep(0.06, 0.045, pa);
    col = mix(col, col * 0.4, hole * seen);
    col += portalCol * (ring * 1.6 + halo * 0.35) * seen;
    // Light streaks thrown back from the portal across the walls.
    if (fxOn(MC_FX_STREAKS)) {
        vec3 sideA = normalize(cross(portalView, vec3(0.0, 1.0, 0.0)));
        vec3 sideB = cross(sideA, portalView);
        float phi = atan(dot(rd, sideB), dot(rd, sideA));
        float rays = 48.0;
        float k = phi / MC_TAU * rays + 0.15 * uTime;
        float rayId = floor(k);
        float rh = motionHash(vec2(mod(rayId, rays), 7.0));
        float rayD = abs(fract(k) - 0.5) / rays * MC_TAU * max(pa, 0.02);
        float rayLine = 1.0 - smoothstep(0.0, 0.004 + pix, rayD);
        float reach = smoothstep(0.08, 0.14, pa) * exp(-pa * 1.6);
        col += mix(pal(hueBase + 0.25), vec3(1.0, 0.95, 0.6), 0.5) * rayLine * step(0.7, rh) * reach * 0.6;
    }

    // ---- finish ---------------------------------------------------------------
    col = vec3(1.0) - exp(-col * 1.35);
    col *= 0.75 + 0.25 * smoothstep(1.9, 0.4, length(vUv * 2.0 - 1.0));
    col += (motionHash(gl_FragCoord.xy + fract(uTime) * 61.0) - 0.5) / 255.0;
    fragColor = vec4(grade(col), 1.0);
}
