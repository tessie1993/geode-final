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

// Merkaba Grid: a flight down a hall of sacred geometry and up through its sky. The floor is a web of
// glowing triangles running to the horizon; wire geodesic spheres stand on it in two rows; star
// tetrahedra (merkabas) hang in the air turning slowly, a glint at every point; overhead the sky is
// another web, coarser and dimmer, and far ahead a column of light rises from the horizon and blooms
// where it meets the sky.
//
// The two webs are planes met analytically (a ray meets a plane once), so their lines cost nothing to
// march; the spheres and the merkabas are wire, gathered as light along the ray (lib_march3's
// glowLine) by a march through the hall that repeats every HALL units along z. The column is at
// infinity, read off the ray's direction, so it stays put on the horizon however far the camera flies.
//
// ---- the camera ------------------------------------------------------------------------------------
//
// The flight runs on its own monotonic clock (uFlowPhase) and rises and dips between the floor and the
// sky; the Camera's travel and push-in add to it and the orbit turns the gaze (cameraFlight), so
// pitching up flies the view through the sky's web. The lens and the roll come through cameraView().
// Everything repeats in 1000 units of z, the Camera's travel wrap.
//
// ---- the music ---------------------------------------------------------------------------------------
//
// uBassSmooth breathes the floor web's spacing and the column, uTrebleSmooth lights the glints, uSwell
// widens the column over seconds. A spike re-seeds which web nodes are lit (uSpawnSeed, grown in over a
// second), steps the web's subdivision to a new plateau (uFormPhase), steers its drift (uMoveDir) and
// swells the glints and the bloom on uSpike, which rises over a tenth of a second.

#define MG_TAU 6.2831853
/** One stretch of the hall along z; 1000 / HALL is whole. */
#define MG_HALL 8.0
#define MG_SKY 7.0
#define MG_SPHERE_X 2.7
#define MG_SPHERE_R 0.95
#define MG_MERKABA_X 1.5
#define MG_MERKABA_Y 2.6
#define MG_FOCAL 1.3
#define MG_FAR 30.0
#define MG_MAX_STEPS 64

// Set once per pixel in main().
float gDensity;
float gSpin;

/** A triangular web on a plane: the distance (in the plane's units) to its nearest line. */
float web(vec2 p) {
    float a = abs(fract(p.x) - 0.5);
    vec2 r1 = rot2(1.0471976) * p;
    vec2 r2 = rot2(-1.0471976) * p;
    float b = abs(fract(r1.x) - 0.5);
    float c = abs(fract(r2.x) - 0.5);
    return min(a, min(b, c));
}

/** The wire in the hall: x the spheres, y the merkabas, z the distance to the nearest merkaba point. */
vec3 wires(vec3 p) {
    float cell = floor(p.z / MG_HALL);
    float zc = (cell + 0.5) * MG_HALL;
    // Two rows of geodesic spheres standing on the floor.
    vec3 sq = vec3(abs(p.x) - MG_SPHERE_X, p.y - MG_SPHERE_R, p.z - zc);
    float sphere = wireGeodesic(sq, MG_SPHERE_R, gDensity);
    // Merkabas hanging between the rows, half a stretch on, each turning its own way.
    float mc = floor(p.z / MG_HALL + 0.5);
    float side = p.x < 0.0 ? -1.0 : 1.0;
    float seed = hash11(mod(mc, 125.0) * 7.31 + side);
    vec3 mq = vec3(abs(p.x) - MG_MERKABA_X, p.y - MG_MERKABA_Y - 0.4 * seed, p.z - mc * MG_HALL);
    mq.xz = rot2(gSpin * (0.6 + 0.6 * seed) + seed * MG_TAU) * mq.xz;
    mq.xy = rot2(gSpin * 0.4 + seed) * mq.xy;
    float size = 0.55 + 0.25 * seed;
    float merkaba = wireMerkaba(mq, size);
    // Its eight points sit on the cube's corners.
    float points = length(abs(mq) - vec3(size * 0.57735027));
    return vec3(sphere, merkaba, points);
}

/**
 * The light of a web met at `at` on its plane: lines, and a sparse set of lit nodes. `footprint` is how
 * much of the plane one pixel covers there.
 */
vec3 webLight(vec2 at, float scale, float width, float footprint, vec3 lineCol, vec3 nodeA, vec3 nodeB, float lift) {
    vec2 w = at * scale;
    float d = web(w) / scale;
    // Far lines are wider than a pixel's footprint allows: they fade toward their average, not into moire.
    float line = smoothstep(width + footprint, width * 0.2, d) * width / (width + footprint);
    vec2 node = floor(w);
    float lit = step(0.82, hash13(vec3(mod(node, 400.0), floor(uSpawnSeed * 97.0))));
    vec3 col = lineCol * line * (0.35 + 0.25 * lift);
    col += mix(nodeA, nodeB, hash13(vec3(mod(node, 400.0), 3.0))) * line * lit * (0.5 + 0.7 * lift) * spawnGrow(1.2);
    return col;
}

void main() {
    vec2 uv = cameraView();

    float bass = clamp(uBassSmooth, 0.0, 1.5);
    float treb = clamp(uTrebleSmooth, 0.0, 1.5);
    float swell = clamp(uSwell, 0.0, 1.5);
    gDensity = floor(clamp(xcount(2.0) + floor(uFormPhase * 3.0), 1.0, 7.0) + 0.5);
    gSpin = uFlowPhase * 0.35;

    // ---- the flight ----------------------------------------------------------------------------------
    vec2 lens = uv;
    if (!touchIdle()) lens -= touchAnchor() * (0.3 * touchStrength());
    vec3 ro = vec3(0.0, 0.0, mod(uFlowPhase * 1.2, 1000.0));
    vec3 rd = normalize(vec3(lens, MG_FOCAL));
    rd = rotX(-0.1) * rd;
    cameraFlight(ro, rd);
    ro.z = mod(ro.z, 1000.0);
    // Rising and dipping between the floor and the sky, on a period that divides the travel wrap.
    ro.y = 1.7 + 0.9 * sin(ro.z * MG_TAU / 125.0);
    rd = normalize(rd);
    float cone = 2.0 / (uResolution.y * MG_FOCAL);

    // ---- the planes ------------------------------------------------------------------------------------
    vec2 drift = uMoveDir * (uFlowPhase * 0.05);
    float tFloor = rd.y < -1e-4 ? -ro.y / rd.y : 1e9;
    float tSky = rd.y > 1e-4 ? (MG_SKY - ro.y) / rd.y : 1e9;
    float tPlane = min(tFloor, tSky);

    // ---- the wire, gathered along the ray up to the planes ------------------------------------------------
    vec3 sphereCol = pal(0.55);
    vec3 merkCol = pal(0.30);
    vec3 glintCol = mix(vec3(1.0), pal(0.86), 0.35);
    vec3 glow = vec3(0.0);
    March3 m = marchStart(0.05, 1.3, 0.01);
    float limit = min(tPlane, MG_FAR);
    for (int i = 0; i < MG_MAX_STEPS; i++) {
        if (float(i) >= uSteps * 0.6) break;
        vec3 p = ro + rd * m.t;
        vec3 w = wires(p);
        float fade = exp(-m.t * 0.07);
        glow += sphereCol * glowLine(w.x, 0.02, m.step) * 0.55 * fade;
        glow += merkCol * glowLine(w.y, 0.02, m.step) * 0.7 * fade;
        glow += glintCol * glowLine(w.z, 0.02, m.step) * (0.3 + 0.8 * treb + 0.5 * uSpike) * fade;
        float d = min(min(w.x, w.y), w.z);
        if (marchStep(m, max(d, 0.004), cone) || m.t > limit) break;
    }

    // ---- what lies beyond --------------------------------------------------------------------------------
    vec3 col = mix(vec3(0.0, 0.0, 0.02), pal(0.58) * 0.06, smoothstep(-0.2, 0.5, rd.y));
    if (tFloor < tSky && tFloor < 1e8) {
        vec3 at = ro + rd * tFloor;
        float spacing = 0.55 * (1.0 + 0.06 * bass);
        float footF = tFloor * cone / max(-rd.y, 0.02) / spacing;
        vec3 lightF = webLight(at.xz / spacing + drift, 1.0, 0.03 / spacing, footF, pal(0.58), pal(0.44), pal(0.86), treb);
        col = mix(lightF + col * 0.3, col, fogDistance(tFloor, 0.045));
    } else if (tSky < 1e8) {
        vec3 at = ro + rd * tSky;
        float footS = tSky * cone / max(rd.y, 0.02) / 1.6;
        vec3 lightS = webLight(at.xz / 1.6 + drift * 0.5, 1.0, 0.025, footS, pal(0.52), pal(0.40), pal(0.90), swell);
        col = mix(lightS * 0.7 + col, col, fogDistance(tSky, 0.03));
    }

    // The column at the horizon ahead, rising into the bloom where it meets the sky.
    float across = atan(rd.x, max(rd.z, 1e-3));
    float up = rd.y / max(length(rd.xz), 1e-3);
    float ahead = smoothstep(0.0, 0.3, rd.z);
    float shaft = exp(-across * across * (900.0 - 300.0 * swell)) * smoothstep(-0.05, 0.25, up);
    col += pal(0.52) * shaft * (0.35 + 0.4 * bass) * ahead;
    vec2 burst = vec2(across, up - 0.62);
    float br = length(burst);
    float spokes = 0.55 + 0.45 * cos(atan(burst.y, burst.x) * 12.0 + uFlowPhase * 2.0);
    col += mix(vec3(1.0), pal(0.50), 0.4) * exp(-br * 9.0) * spokes * (0.4 + 0.4 * uSpike) * ahead;
    col += vec3(1.0) * exp(-br * br * 400.0) * 1.2 * ahead;

    col += glow;

    // ---- on the glass ------------------------------------------------------------------------------------
    col += mix(pal(0.48), vec3(1.0), 0.3) * fluidMotes(uv * 0.85, 5.5, 0.15) * 0.18;
    if (!touchIdle()) {
        col += pal(0.5 + 0.15 * sin(uTime * 0.05)) * min(touchWake(uv), 3.0) * 0.05;
    }
    col = col / (1.0 + 0.3 * col);
    vec2 sv = (vUv - 0.5) * vec2(uResolution.x / max(uResolution.y, 1.0), 1.0) * 2.0;
    col *= 0.62 + 0.38 * smoothstep(2.1, 0.4, length(sv));
    fragColor = vec4(grade(col), 1.0);
}
