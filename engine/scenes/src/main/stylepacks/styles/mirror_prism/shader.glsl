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
// The blue noise staggers where each pixel's march starts, so the glow gathered along the rays never
// bands (manifest "inputs").
//#include lib_inputs
// Drawn through the engine's passes (manifest "multipass"): half resolution, upscaled and accumulated.
//#include lib_multipass

// Mirror Prism: a flight down a corridor of black mirror glass laced with neon wireframe, which opens,
// every so often, into a great mirrored chamber where a crystal turns inside a neon cage, bending the
// chamber's light through its facets and splitting it into colour.
//
//   - THE MIRRORS are space folded, not rays bounced: the corridor is one box of wire whose walls,
//     floor and ceiling are mirrors, so a ray that would reflect off a wall walks on, straight, into
//     the mirror image of the box beside it (prFold, a reflection per cell, distances kept). The march
//     sees the corridor repeated in every direction for the price of one, and every wall a ray has
//     passed through dims and cools what it sees, as dark glass does.
//   - THE WIREFRAME is tubes: rails along the corners and down the middle of each face, a frame every
//     PR_RING, crossed braces on the walls. Their neon is lib_march3's glow, gathered along every ray;
//     dashes of light stream along the rails, and a hit sends a band of light away down the frames.
//   - THE CHAMBER is a larger mirror box, with a grid on its floor and ceiling and the corridors'
//     mouths framed in its end walls (which the mirrors multiply into a wall of corridors). The crystal
//     is lib_poly3's icosahedron, tumbling, and its cage the same solid's wireframe, turning the other way.
//   - THE REFRACTION is traced through the solid exactly: each facet is a plane, so where a ray enters,
//     which face it leaves by and how it bends at both is closed form (prExit), once per colour channel,
//     each with its own index of refraction. What the ray finds outside is the chamber's neon, worked
//     out from its direction (prEnv). The faked dispersion is that spread: three rays, three slightly
//     different paths, the chamber's lines split into red, green and blue fringes, the crystal's inner
//     edges three times over. The lens flare's own dispersion and its star carry it into the glare.
//   - THE CAMERA rides lib_path3's wandering path down the corridor and swings wide around the crystal
//     in each chamber, looking in at it as it passes; it takes the Camera's orbit, push and travel.
//
// ---- what the music moves ---------------------------------------------------
//
//   - THE PRISM'S REFRACTION: the bass (smoothed) and the passage's swell raise the crystal's index of
//     refraction, so its facets bend the chamber harder, and widen the spread between the colours; the
//     Spread Extra (its default Reaction pushes it on hits) scales the spread.
//   - THE CORRIDOR'S SPEED: uFlight (time plus loudness, integrated on the CPU) and the Camera's travel,
//     which the default Reaction lets the bass push; the rail dashes stream at uEnergyPhase, the
//     integral of loudness, so loud passages pour light down the rails faster.
//   - THE NEON: the Edge Extra (lifted by the treble) and the swell set how bright it burns; a hit
//     sends a band of light down the frames (uSpawnAge), and walks the palette (uFormPhase).
//
// ---- the No-flash budget ----------------------------------------------------
//
// The brightest repeats are the frames, one per PR_RING; at the fastest flight (about 4 units a second
// plus the Camera's travel) a few cross a region of the screen a second, each a thin line. Everything
// the music brightens is a smoothed signal or a travelling band; the refraction moves colour, never
// the level of the frame.
//
// ---- periods ----------------------------------------------------------------
//
// The corridor and chamber repeat every PR_CYCLE, the frames every PR_RING and PR_RING2, the path's
// control points every 8 units over 128 points: all divide PR_PERIOD (1024), the wrap of the travel,
// and PR_FLIGHT_RATE puts six periods in a wrap of uFlight, so neither wrap moves anything.

#define PR_FLIGHT_WRAP 6283.1853
#define PR_PERIOD 1024.0
/** A corridor of PR_CORRIDOR, then a chamber, every PR_CYCLE. */
#define PR_CYCLE 64.0
#define PR_CYCLES 16.0
#define PR_CORRIDOR 40.0
/** Where the crystal stands in its cycle. */
#define PR_PRISM_Z 52.0
/** The corridor's mirror box, half width and half height, and its frames. */
#define PR_W 2.0
#define PR_H 1.5
#define PR_RING 2.0
/** The chamber's. */
#define PR_W2 6.0
#define PR_H2 4.0
#define PR_RING2 4.0
#define PR_TUBE 0.028
/** Compile-time ceiling on the march: MarchBudget.MAX_STEPS. uSteps is the runtime budget. */
#define PR_MAX_STEPS 128
#define PR_FAR 60.0
#define PR_FOCAL 1.3
/** What a mirror keeps of the light that passes through it to the next image. */
#define PR_MIRROR 0.62
/** The manifest's "effects", by position: what the thermal tiers may switch off (uFxOff). */
#define PR_FX_DISPERSION 0
#define PR_FX_GLOW 1

#define MAT_NONE 0.0
#define MAT_CORRIDOR 1.0
#define MAT_CHAMBER 2.0
#define MAT_CAGE 3.0
#define MAT_CRYSTAL 4.0

/** The camera's distance down a flight path, integrated on the CPU (ShaderScene, #280). */
uniform float uFlight;

const float PR_FLIGHT_RATE = 6144.0 / PR_FLIGHT_WRAP;
/** An icosahedron's inradius over its circumradius (lib_poly3). */
const float PR_INRADIUS = 0.79465447;
/** Beyond this the parts of the world are taken by their bounds (lib_poly3's polyhedra cathedral idiom). */
const float PR_REACH = 0.5;

// The icosahedron's 20 face normals: (+-1, +-1, +-1) / sqrt3 and the cyclic turns of (0, +-phi, +-1/phi)
// / sqrt3, the directions lib_poly3's sdIcosahedron folds into its octant.
const float PR_A = 0.57735027;
const float PR_B = 0.93417236;
const float PR_C = 0.35682209;
const vec3 PR_FACES[20] = vec3[20](
    vec3(PR_A, PR_A, PR_A), vec3(PR_A, PR_A, -PR_A), vec3(PR_A, -PR_A, PR_A), vec3(PR_A, -PR_A, -PR_A),
    vec3(-PR_A, PR_A, PR_A), vec3(-PR_A, PR_A, -PR_A), vec3(-PR_A, -PR_A, PR_A), vec3(-PR_A, -PR_A, -PR_A),
    vec3(0.0, PR_B, PR_C), vec3(0.0, PR_B, -PR_C), vec3(0.0, -PR_B, PR_C), vec3(0.0, -PR_B, -PR_C),
    vec3(PR_C, 0.0, PR_B), vec3(-PR_C, 0.0, PR_B), vec3(PR_C, 0.0, -PR_B), vec3(-PR_C, 0.0, -PR_B),
    vec3(PR_B, PR_C, 0.0), vec3(PR_B, -PR_C, 0.0), vec3(-PR_B, PR_C, 0.0), vec3(-PR_B, -PR_C, 0.0));

float gMat;
float gWire;    // the distance to the nearest neon tube, for its glow
float gOrder;   // how many mirrors the point is seen through
float gRegion;  // 0 in a corridor, 1 in a chamber
vec2 gFlip;     // the mirror image's handedness in x and y, to fold a direction the same way
vec3 gLocal;    // the point in the crystal's own turning frame
mat3 gFrame;    // the crystal's turning frame
mat3 gCage;     // its cage's
float gPrismR;
float gStream;  // how far the rail dashes have streamed

/**
 * Mirror folding: `p` in boxes of half size `extent`, each the mirror image of its neighbours, folded
 * into the one around the origin. `order` is how many walls lie between, `flip` the image's handedness.
 */
vec2 prFold(vec2 p, vec2 extent, out vec2 flip, out float order) {
    vec2 k = floor((p + extent) / (2.0 * extent));
    vec2 l = p - 2.0 * extent * k;
    flip = 1.0 - 2.0 * mod(k, 2.0);
    order = abs(k.x) + abs(k.y);
    return l * flip;
}

/** The corridor: rails, frames and braces of one mirror box, its z continuing through every image. */
float prCorridor(vec3 p, out float order, out vec2 flip) {
    vec2 q = prFold(p.xy, vec2(PR_W, PR_H), flip, order);
    vec2 a = abs(q);
    float zr = p.z - PR_RING * floor(p.z / PR_RING + 0.5);
    float rail = length(a - vec2(PR_W, PR_H)) - PR_TUBE;
    float lane = min(length(vec2(a.x, PR_H - a.y)), length(vec2(PR_W - a.x, a.y))) - PR_TUBE * 0.6;
    float frame = min(length(vec2(PR_W - a.x, zr)), length(vec2(PR_H - a.y, zr))) - PR_TUBE * 0.8;
    // The walls' braces: the diagonals of each panel between two frames.
    float diag = abs(abs(zr) * (2.0 * PR_H / PR_RING) - a.y) * (PR_RING / length(vec2(PR_RING, 2.0 * PR_H)));
    float brace = length(vec2(PR_W - a.x, diag)) - PR_TUBE * 0.5;
    return min(min(rail, lane), min(frame, brace));
}

/**
 * The chamber's box, `q` the point folded into it: its corner rails, frames, floor grid and the
 * corridors' mouths in its end walls.
 */
float prChamberBox(vec2 q, float z, float zl) {
    vec2 a = abs(q);
    float zr = z - PR_RING2 * floor(z / PR_RING2 + 0.5);
    float rail = length(a - vec2(PR_W2, PR_H2)) - PR_TUBE * 1.5;
    float frame = min(length(vec2(PR_W2 - a.x, zr)), length(vec2(PR_H2 - a.y, zr))) - PR_TUBE;
    float gx = q.x - 2.0 * floor(q.x * 0.5 + 0.5);
    float grid = length(vec2(PR_H2 - a.y, gx)) - PR_TUBE * 0.5;
    // The mouth of a corridor, outlined where the end walls meet it.
    vec2 m = abs(q) - vec2(PR_W, PR_H);
    float outline = abs(length(max(m, 0.0)) + min(max(m.x, m.y), 0.0));
    float wall = min(abs(zl - PR_CORRIDOR), min(zl, PR_CYCLE - zl));
    float mouth = length(vec2(outline, wall)) - PR_TUBE * 1.6;
    return min(min(rail, frame), min(grid, mouth));
}

float map(vec3 p) {
    float zl = mod(p.z, PR_CYCLE);
    bool inCorridor = zl < PR_CORRIDOR;
    // How far each region lies: 0 inside it, else the distance along z to its nearer end.
    float toCorridor = inCorridor ? 0.0 : min(zl - PR_CORRIDOR, PR_CYCLE - zl);
    float toChamber = inCorridor ? min(PR_CORRIDOR - zl, zl) : 0.0;
    gMat = MAT_NONE;

    float orderC = 0.0;
    vec2 flipC = vec2(1.0);
    float corridor = toCorridor;
    if (toCorridor < PR_REACH) corridor = max(prCorridor(p, orderC, flipC), toCorridor);

    float orderH = 0.0;
    vec2 flipH = vec2(1.0);
    float chamber = toChamber;
    float crystal = 1e9;
    float cage = 1e9;
    if (toChamber < max(corridor, PR_REACH)) {
        vec2 q = prFold(p.xy, vec2(PR_W2, PR_H2), flipH, orderH);
        chamber = max(prChamberBox(q, p.z, zl), toChamber);
        vec3 pp = vec3(q, zl - PR_PRISM_Z);
        float bound = length(pp) - gPrismR * 1.35;
        if (bound < max(min(corridor, chamber), PR_REACH)) {
            gLocal = pp * gFrame;
            crystal = sdIcosahedron(gLocal, gPrismR);
            cage = wireIcosahedron(pp * gCage, gPrismR * 1.3) - PR_TUBE * 1.2;
        } else {
            cage = bound + 0.3;
            crystal = bound;
        }
        cage = max(cage, toChamber);
        crystal = max(crystal, toChamber);
    }

    float wireC = corridor;
    float wireH = min(chamber, cage);
    gWire = min(wireC, wireH);
    bool chamberNearer = wireH < wireC;
    gRegion = chamberNearer ? 1.0 : 0.0;
    gOrder = chamberNearer ? orderH : orderC;
    gFlip = chamberNearer ? flipH : flipC;
    float d = gWire;
    gMat = chamberNearer ? (cage < chamber ? MAT_CAGE : MAT_CHAMBER) : MAT_CORRIDOR;
    if (crystal < d) {
        d = crystal;
        gMat = MAT_CRYSTAL;
        gRegion = 1.0;
        gOrder = orderH;
        gFlip = flipH;
    }
    return d;
}

/** The face of the crystal (circumradius r) a point on its surface lies on: the plane it is furthest out on. */
vec3 prFace(vec3 p) {
    vec3 best = PR_FACES[0];
    float far = dot(p, best);
    for (int i = 1; i < 20; i++) {
        float h = dot(p, PR_FACES[i]);
        if (h > far) {
            far = h;
            best = PR_FACES[i];
        }
    }
    return best;
}

/**
 * Where a ray inside the crystal (inradius `inR`), at `p` going along unit `d`, leaves it: the distance
 * to the first face plane ahead, and that face's normal.
 */
float prExit(vec3 p, vec3 d, float inR, out vec3 normal) {
    float best = 1e9;
    normal = d;
    for (int i = 0; i < 20; i++) {
        float c = dot(d, PR_FACES[i]);
        if (c > 1e-4) {
            float s = (inR - dot(p, PR_FACES[i])) / c;
            if (s < best) {
                best = s;
                normal = PR_FACES[i];
            }
        }
    }
    return clamp(best, 0.0, 4.0 * inR);
}

/**
 * The chamber seen from its crystal along `d`: the frames and corner rails of its mirror box where the
 * direction meets a wall, and the corridors' mouths glowing far down the hall either way.
 */
vec3 prEnv(vec3 d, vec3 frameCol, vec3 railCol) {
    d = normalize(d);
    vec2 tw = vec2(PR_W2, PR_H2) / max(abs(d.xy), vec2(1e-3));
    float t = min(tw.x, tw.y);
    vec3 h = d * t;
    float zr = abs(fract(h.z / PR_RING2 + 0.5) - 0.5) * PR_RING2;
    float corner = tw.x < tw.y ? PR_H2 - abs(h.y) : PR_W2 - abs(h.x);
    float w = 0.05 + 0.012 * t;
    float fade = exp(-t * 0.03);
    vec3 col = (frameCol * smoothstep(w, 0.0, zr) + railCol * 1.3 * smoothstep(w * 1.5, 0.0, corner)) * fade;
    col += mix(frameCol, vec3(1.0), 0.35) * pow(abs(d.z), 28.0) * 1.4;
    return col + railCol * 0.02;
}

void main() {
    vec2 uv = view();
    float bassA = min(uBassSmooth, 1.3);
    float trebA = min(uTrebleSmooth, 1.3);

    // ---- the music's state for map() -------------------------------------------------
    float spin = 0.3 * uXSpinTime + 0.06 * uEnergyPhase;
    gFrame = rotAxis(vec3(0.35, 1.0, 0.2), spin) * rotX(0.4);
    gCage = rotAxis(vec3(-0.3, 1.0, 0.45), -0.7 * spin);
    gPrismR = min(1.55 * xm(uXSize) * (1.0 + 0.05 * uSwell), 2.3);
    gStream = uEnergyPhase * 5.0 + uTime * 0.6;

    // ---- camera: down the corridor, wide round the crystal ----------------------------
    vec3 ro0 = vec3(0.0, 0.0, mod(uFlight * PR_FLIGHT_RATE, PR_PERIOD));
    vec3 rdCam = normalize(vec3(uv, PR_FOCAL));
    cameraFlight(ro0, rdCam);
    float travel = mod(ro0.z, PR_PERIOD);
    vec3 tangent;
    vec3 bend;
    vec3 tangentAhead;
    vec3 bendAhead;
    vec3 eye = pathWander(travel, 8.0, 128.0, vec2(0.45, 0.3), 5.0, tangent, bend);
    vec3 ahead = pathWander(travel + 6.0, 8.0, 128.0, vec2(0.45, 0.3), 5.0, tangentAhead, bendAhead);
    // In a chamber the path swings out round the crystal, to the side this cycle chose, and looks in.
    float cycleStart = PR_CYCLE * floor(travel / PR_CYCLE);
    float zl = travel - cycleStart;
    float side = hash11(mod(floor(travel / PR_CYCLE), PR_CYCLES) + 2.0) < 0.5 ? -1.0 : 1.0;
    float swing = smoothstep(PR_CORRIDOR - 2.0, PR_PRISM_Z - 4.0, zl) * (1.0 - smoothstep(PR_PRISM_Z + 2.0, PR_CYCLE - 1.0, zl));
    float zla = mod(zl + 6.0, PR_CYCLE);
    float swingAhead = smoothstep(PR_CORRIDOR - 2.0, PR_PRISM_Z - 4.0, zla) * (1.0 - smoothstep(PR_PRISM_Z + 2.0, PR_CYCLE - 1.0, zla));
    vec3 ro = eye + vec3(side * 3.8 * swing, 0.4 * swing, 0.0);
    vec3 target = ahead + vec3(side * 3.8 * swingAhead, 0.4 * swingAhead, 0.0);
    vec3 prism = vec3(0.0, 0.0, cycleStart + PR_PRISM_Z);
    float look = smoothstep(PR_CORRIDOR, PR_PRISM_Z - 6.0, zl) * (1.0 - smoothstep(PR_PRISM_Z - 2.0, PR_PRISM_Z + 6.0, zl));
    target = mix(target, prism, 0.55 * look);
    float bank = pathBank(tangent, bend, 3.0, 0.18) + 0.03 * sin(uTime * 0.13);
    mat3 frame = pathFrame(target - ro, bank);
    vec3 rd = normalize(frame * rdCam);
    if (mpCameraPass()) {
        fragColor = mpCameraTexel(ro, frame * uCamRot, PR_FOCAL);
        return;
    }
    float cone = mpCone(2.0 / (uResolution.y * PR_FOCAL));

    // ---- colours ------------------------------------------------------------------
    float hueBase = 0.55 + 0.35 * (1.0 - abs(2.0 * uFormPhase - 1.0));
    vec3 neonFrame = pal(hueBase);
    vec3 neonRail = pal(hueBase + 0.33);
    vec3 neonChamber = pal(hueBase + 0.62);
    vec3 cageCol = mix(pal(hueBase + 0.15), vec3(1.0), 0.15);
    float burn = (0.5 + 0.5 * clamp(uXEdge, 0.0, 2.0)) * (0.8 + 0.25 * trebA + 0.2 * uSwell);
    // The glass dims and cools what it shows with every mirror between.
    const vec3 GLASS = vec3(0.82, 0.92, 1.0);

    // ---- march ------------------------------------------------------------------------
    float jitter = texelFetch(uBlueNoise, ivec2(gl_FragCoord.xy) & 63, 0).r;
    March3 m = marchStart(mpStart(0.05 + 0.2 * jitter), 1.4, 0.01);
    vec3 glow = vec3(0.0);
    bool glowing = fxOn(PR_FX_GLOW);
    for (int i = 0; i < PR_MAX_STEPS; i++) {
        if (float(i) >= uSteps) break;
        vec3 p = ro + rd * m.t;
        float d = map(p);
        if (glowing) {
            vec3 neon = gRegion > 0.5 ? (gMat == MAT_CAGE ? cageCol * 0.6 : neonChamber) : neonRail;
            glow += neon * glowLine(gWire, 0.04, m.step) * pow(PR_MIRROR, gOrder);
        }
        if (marchStep(m, d * 0.9, cone) || m.t > PR_FAR) break;
    }
    float t = min(m.t, PR_FAR);
    if (mpCoarsePass()) {
        fragColor = vec4(0.0, 0.0, 0.0, mpDepth(t));
        return;
    }
    vec3 p = ro + rd * t;
    map(p);
    float mat = gMat;
    float order = gOrder;
    vec2 flip = gFlip;
    vec3 local = gLocal;

    // The band of light a hit sends away down the frames.
    float rel = p.z - travel;
    float band = exp(-pow((rel - uSpawnAge * 14.0) * 0.3, 2.0)) * spawnFresh(2.0) * clamp(uBeatResponse, 0.0, 2.0);

    vec3 col = vec3(0.0);
    if (m.hit) {
        if (mat == MAT_CRYSTAL) {
            // ---- the crystal: refraction through its facets, one ray per colour ----------------
            // The ray as the folded image sees it, then in the crystal's turning frame.
            vec3 rdF = vec3(rd.xy * flip, rd.z);
            vec3 lrd = rdF * gFrame;
            vec3 n = prFace(local);
            float inR = gPrismR * PR_INRADIUS;
            float fres = 0.04 + 0.96 * pow(1.0 - max(dot(-lrd, n), 0.0), 5.0);
            vec3 surface = prEnv(gFrame * reflect(lrd, n), neonFrame, neonChamber) * fres;
            // The music bends it: a denser crystal and a wider split between the colours.
            float ior = 1.38 + 0.16 * bassA + 0.06 * uSwell;
            float spread = 0.035 * xm(uXSpread) * (0.6 + 0.5 * bassA + 0.3 * uSwell);
            vec3 iors = ior + spread * vec3(-1.0, 0.0, 1.0);
            int channels = fxOn(PR_FX_DISPERSION) ? 3 : 1;
            vec3 through = vec3(0.0);
            vec3 edges = vec3(0.0);
            for (int c = 0; c < 3; c++) {
                if (c >= channels) break;
                float e = channels == 3 ? iors[c] : ior;
                vec3 t1 = refract(lrd, n, 1.0 / e);
                vec3 ne;
                float s = prExit(local, t1, inR, ne);
                vec3 pe = local + t1 * s;
                vec3 t2 = refract(t1, -ne, e);
                if (dot(t2, t2) < 1e-6) {
                    // Totally reflected inside: one more face, then out (or mirrored once more).
                    vec3 tb = reflect(t1, -ne);
                    vec3 nb;
                    float sb = prExit(pe, tb, inR, nb);
                    pe += tb * sb;
                    s += sb;
                    t2 = refract(tb, -nb, e);
                    if (dot(t2, t2) < 1e-6) t2 = reflect(tb, -nb);
                }
                vec3 env = prEnv(gFrame * t2, neonFrame, neonChamber) * exp(-s * 0.06);
                // The crystal's inner edges, seen where the ray leaves it: split by the dispersion too.
                float edge = smoothstep(0.05, 0.0, wireIcosahedron(pe, gPrismR));
                if (channels == 3) {
                    through[c] = env[c];
                    edges[c] = edge;
                } else {
                    through = env;
                    edges = vec3(edge);
                }
            }
            col = surface + through * (1.0 - fres) * 1.15 + edges * mix(cageCol, vec3(1.0), 0.5) * 0.9 * burn;
            col += neonChamber * 0.06 * (0.6 + 0.6 * bassA);
        } else {
            // ---- the neon: a white-hot core in its own colour, brightest face on ------------------
            vec3 n = normalize(vec3(map(p + vec3(0.002, 0.0, 0.0)) - map(p - vec3(0.002, 0.0, 0.0)),
                                    map(p + vec3(0.0, 0.002, 0.0)) - map(p - vec3(0.0, 0.002, 0.0)),
                                    map(p + vec3(0.0, 0.0, 0.002)) - map(p - vec3(0.0, 0.0, 0.002))));
            float face = abs(dot(n, rd));
            vec3 neon = mat == MAT_CAGE ? cageCol : (mat == MAT_CHAMBER ? neonChamber : neonRail);
            // Dashes stream down the rails at the pace of the music's loudness.
            float dash = smoothstep(0.55, 1.0, sin((p.z - gStream) * 1.7) * 0.5 + 0.5);
            float lift = mat == MAT_CAGE ? 0.9 : 1.3 + 1.1 * band + (mat == MAT_CORRIDOR ? 0.8 * dash : 0.0);
            float core = mat == MAT_CAGE ? 0.2 : 0.5;
            col = neon * burn * lift * (0.6 + 0.6 * face) + vec3(1.0) * pow(face, 6.0) * core * burn;
            // The frames take their own colour.
            float zr = abs(p.z - PR_RING * floor(p.z / PR_RING + 0.5));
            col = mix(col, neonFrame * burn * (1.5 + 1.3 * band), mat == MAT_CORRIDOR ? smoothstep(0.06, 0.02, zr) : 0.0);
        }
        // Every mirror between dims and cools it.
        col *= pow(PR_MIRROR * GLASS, vec3(order));
    }

    // ---- the glow gathered along the ray ---------------------------------------------
    col += glow * burn * (0.35 + 0.3 * band);

    // ---- haze: the far corridor fades into its own colour --------------------------------
    float haze = fogDistance(t, 0.022 * xm(uXFog));
    col = mix(col, mix(neonRail, neonFrame, 0.5) * 0.04, clamp(haze, 0.0, 0.9));

    // ---- finish ---------------------------------------------------------------------------
    col = vec3(1.0) - exp(-col * 1.25);
    col *= 0.76 + 0.24 * smoothstep(1.9, 0.4, length(vUv * 2.0 - 1.0));
    col += (jitter - 0.5) / 255.0;
    fragColor = vec4(grade(col), mpDepth(t));
}
