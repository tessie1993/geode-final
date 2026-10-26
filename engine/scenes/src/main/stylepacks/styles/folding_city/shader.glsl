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

// Folding City: the street ahead bends up and over the camera until the city
// hangs overhead like a ceiling.
//
// The camera drives straight down a street at dusk and stays level. What moves
// is SPACE: a few metres ahead, the world is rolled around an axis parallel to
// the street's width, so the road curls upward, the blocks beyond it tip back,
// and with the fold deep enough the far city runs back over your head, lit
// windows facing down. The fold is a bend of the coordinates, not of the
// camera, so the horizon curls while the frame stays upright.
//
// THE BEND. Ahead of the fold line (axis at height R, FC_FOLD_AHEAD metres
// in front), a point at angle a around the axis and distance rho from it
// unrolls to the flat city at height R - rho and distance R * a along the
// street; past the end of the arc the city simply continues in the rotated
// frame. Points nearer the axis than R are stretched along the arc by R / rho,
// and nothing solid comes closer to the axis than R - FC_TALLEST, so the
// estimate is divided by R / (R - FC_TALLEST) - the 0.6x step the research
// asks for.
//
// The city is one repeated block (a 10 m grid of streets, four towers to a
// block, heights by hash) on a 1000 m period, so the Camera's travel wraps
// without a seam. Lighting is a low warm sun (key), the blue sky (fill), a
// rim off the sun's side, contact occlusion, and window light that the music
// switches on floor by floor.
//
// ---- the music ---------------------------------------------------------------
//
// The fold runs on its own clock: it curls up over about twenty seconds, holds,
// then snaps flat over a couple of seconds with a small bounce. The passage's
// loudness (uSwell) deepens it. Windows light by floor band: low floors follow
// the bass, the middle the mids, the tops the treble, each window fading in on
// its own threshold so the facade fills smoothly.

#define FC_TAU 6.2831853
#define FC_PI 3.1415927
/** Compile-time ceiling on the march; the runtime budget is min(uSteps, this). */
#define FC_MAX_STEPS 96
#define FC_FAR 70.0
#define FC_FOCAL 1.2
/** Bend radius, the fold line's distance ahead, and the tallest tower. */
#define FC_R 14.0
#define FC_FOLD_AHEAD 7.0
#define FC_TALLEST 5.2
/** City grid and period (divides the Camera's travel wrap, 1000). */
#define FC_CELL 10.0
#define FC_PERIOD 1000.0
#define FC_SPEED 4.0
#define FC_EYE 1.5
/** One fold cycle, seconds. */
#define FC_CYCLE 32.0

/** The bend's stretch bound and a margin for the towers' hashed neighbours. */
const float FC_STEP = 0.9 * (FC_R - FC_TALLEST) / FC_R;

float gFold;
vec3 gFlat;
int gMat;
vec3 gCell;

float ease(float x) {
    x = clamp(x, 0.0, 1.0);
    return x * x * (3.0 - 2.0 * x);
}

/** Camera-relative point in bent space -> camera-relative point in the flat city. */
vec3 unbend(vec3 p) {
    vec2 v = vec2(p.z - FC_FOLD_AHEAD, p.y - FC_R);
    if (gFold <= 1e-4) return p;
    // Angle around the axis from straight down, turning forward and up.
    float a = atan(v.x, -v.y);
    float rho = length(v);
    float aw = mod(a, FC_TAU);
    if (aw <= gFold) {
        return vec3(p.x, FC_R - rho, FC_FOLD_AHEAD + FC_R * aw);
    }
    // Outside the arc: nearer the arc's end, or nearer its start?
    float pastEnd = aw - gFold;
    if (pastEnd < 0.5 * (FC_TAU - gFold)) {
        // The city continues beyond the fold in the frame turned by gFold.
        float c = cos(gFold);
        float s = sin(gFold);
        vec2 w = vec2(c * v.x + s * v.y, -s * v.x + c * v.y);
        return vec3(p.x, FC_R + w.y, FC_FOLD_AHEAD + FC_R * gFold + w.x);
    }
    return p;
}

float towerHeight(vec2 cell, vec2 quad) {
    vec2 c = mod(cell, FC_PERIOD / FC_CELL);
    float h = hash11(c.x * 7.31 + c.y * 3.17 + quad.x * 1.9 + quad.y * 5.3);
    return 1.4 + (FC_TALLEST - 1.4) * h * h;
}

float city(vec3 q) {
    // The road and pavements: kerbs 0.12 m high at the block edges.
    vec2 l = mod(q.xz, FC_CELL) - 0.5 * FC_CELL;
    vec2 cell = floor(q.xz / FC_CELL);
    float road = q.y;
    float pave = sdBox(vec3(l.x, q.y - 0.06, l.y), vec3(3.1, 0.06, 3.1));
    float d = min(road, pave);
    gMat = pave < road ? 1 : 0;
    // Four towers per block, one per quadrant.
    for (int i = 0; i < 4; i++) {
        vec2 quad = vec2(float(i & 1), float(i >> 1));
        vec2 centre = (quad - 0.5) * 2.5;
        float h = towerHeight(cell, quad);
        float b = sdBox(vec3(l.x - centre.x, q.y - 0.5 * h, l.y - centre.y), vec3(1.0, 0.5 * h, 1.0));
        if (b < d) {
            d = b;
            gMat = 2;
            gCell = vec3(cell, float(i));
        }
    }
    return d;
}

float map(vec3 p, float camZ) {
    vec3 f = unbend(p);
    f.z += camZ;
    f.y += FC_EYE;
    gFlat = f;
    return city(f) * FC_STEP;
}

vec3 normalAt(vec3 p, float camZ, float eps) {
    vec2 k = vec2(1.0, -1.0);
    return normalize(k.xyy * map(p + k.xyy * eps, camZ) + k.yyx * map(p + k.yyx * eps, camZ)
                   + k.yxy * map(p + k.yxy * eps, camZ) + k.xxx * map(p + k.xxx * eps, camZ));
}

vec3 tonemap(vec3 c) {
    c = max(c, 0.0);
    return clamp(c * (2.51 * c + 0.03) / (c * (2.43 * c + 0.59) + 0.14), 0.0, 1.0);
}

void main() {
    vec2 uv = view();

    // ---- the fold ------------------------------------------------------------------
    float ph = fract(uTime / FC_CYCLE);
    float curl = ease(ph / 0.62);
    float snap = ease((ph - 0.8) / 0.08);
    float bounce = ph > 0.88 ? 0.14 * sin(FC_PI * (ph - 0.88) / 0.12) : 0.0;
    float deep = 0.82 * FC_PI + 0.18 * FC_PI * clamp(uSwell, 0.0, 1.0);
    gFold = clamp(deep * curl * (1.0 - snap) + bounce, 0.0, FC_PI);

    // ---- the camera ----------------------------------------------------------------
    float camZ = mod(uTime * FC_SPEED, FC_PERIOD) + uCamTravel + uCamPush * 1.5;
    vec2 lens = uv;
    if (!touchIdle()) lens -= touchAnchor() * (0.35 * touchStrength());
    // Level, with the faintest float of the car on the road.
    float drift = 0.02 * sin(uTime * 0.21);
    vec3 rd = normalize(vec3(lens.x + drift, lens.y + 0.06, FC_FOCAL));
    rd = uCamRot * rd;
    vec3 ro = vec3(0.0);

    // ---- the march ------------------------------------------------------------------
    float budget = min(uSteps, float(FC_MAX_STEPS));
    float t = 0.05;
    float hitT = -1.0;
    for (int i = 0; i < FC_MAX_STEPS; i++) {
        if (float(i) >= budget) break;
        if (t > FC_FAR) break;
        float d = map(ro + rd * t, camZ);
        float eps = 0.0012 * t + 0.0006;
        if (d < eps) {
            hitT = t;
            break;
        }
        t += min(d, 3.0);
    }

    vec3 sunDir = normalize(vec3(-0.45, 0.22, 0.85));
    vec3 sunCol = mix(vec3(1.0, 0.66, 0.4), pal(0.05), 0.25) * 1.3;
    vec3 skyTop = mix(vec3(0.16, 0.24, 0.48), pal(0.65), 0.3);
    vec3 skyLow = mix(vec3(0.95, 0.55, 0.38), pal(0.05), 0.3);
    float up = rd.y;
    vec3 sky = mix(skyLow, skyTop, smoothstep(-0.05, 0.6, up));
    float sc = max(dot(rd, sunDir), 0.0);
    sky += sunCol * (0.25 * pow(sc, 10.0) + 0.6 * smoothstep(0.9985, 0.9993, sc));
    vec3 col = sky;

    if (hitT > 0.0) {
        vec3 p = ro + rd * hitT;
        map(p, camZ);
        vec3 f = gFlat;
        int mat = gMat;
        vec3 cellId = gCell;
        vec3 n = normalAt(p, camZ, max(0.0015 * hitT, 0.001));
        // The sun and sky are in the flat city's frame too: lighting is done
        // there, so a tower on the ceiling is lit as it would be on the ground.
        vec3 nf = normalize(unbend(p + n * 0.05) - unbend(p));
        float key = max(dot(nf, sunDir), 0.0);
        float fill = 0.55 + 0.45 * nf.y;
        float rim = pow(1.0 - max(dot(n, -rd), 0.0), 3.0) * 0.4;
        // Three taps of contact occlusion along the normal.
        float occ = 0.0;
        for (int i = 1; i <= 3; i++) {
            float h = 0.25 * float(i);
            occ += (h - map(p + n * h, camZ) / FC_STEP) / h;
        }
        float ao = clamp(1.0 - 0.3 * occ, 0.2, 1.0);

        vec3 albedo;
        vec3 emit = vec3(0.0);
        if (mat == 2) {
            float tone = hash11(cellId.x * 1.3 + cellId.y * 2.7 + cellId.z * 0.9);
            albedo = mix(vec3(0.35, 0.33, 0.32), pal(0.6 + 0.3 * tone) * 0.5, 0.35) * (0.7 + 0.5 * tone);
            // Windows on the faces: floors 0.35 m, bays 0.3 m.
            // Along the street the facade reads z on the city's period, so the
            // windows wrap with the travel instead of re-rolling.
            vec2 face = abs(nf.x) > 0.5 ? vec2(mod(f.z, FC_PERIOD), f.y) : vec2(f.x, f.y);
            vec2 g = vec2(face.x / 0.3, face.y / 0.35);
            vec2 wcell = floor(g);
            vec2 wf = abs(fract(g) - 0.5);
            float pane = (1.0 - smoothstep(0.28, 0.36, wf.x)) * (1.0 - smoothstep(0.24, 0.33, wf.y));
            // Far facades: once a window is smaller than a pixel, draw its average
            // instead of letting the grid alias into a crawl as the car moves.
            float fpw = hitT * 2.0 / (FC_FOCAL * uResolution.y * 0.3);
            pane = mix(pane, 0.45, smoothstep(0.25, 0.7, fpw));
            float roof = step(0.5, nf.y);
            float floorBand = clamp(f.y / FC_TALLEST, 0.0, 1.0);
            float level = floorBand < 0.33 ? uBassSmooth : (floorBand < 0.66 ? uMidSmooth : uTrebleSmooth);
            float wh = hash11(wcell.x * 0.73 + wcell.y * 1.37 + tone * 91.0);
            float lit = smoothstep(wh - 0.08, wh + 0.08, 0.25 + 0.5 * clamp(level, 0.0, 1.2));
            vec3 warmWin = mix(vec3(1.0, 0.75, 0.45), pal(0.1 + 0.2 * wh), 0.35);
            albedo = mix(albedo, albedo * 0.35, pane * (1.0 - roof));
            emit = warmWin * pane * lit * (1.0 - roof) * 0.9;
            // Glass catches the sky.
            float fres = pow(1.0 - max(dot(n, -rd), 0.0), 5.0);
            emit += skyLow * pane * (1.0 - roof) * fres * 0.3;
        } else if (mat == 1) {
            albedo = mix(vec3(0.42, 0.4, 0.38), pal(0.1) * 0.5, 0.2);
        } else {
            // Asphalt with a dashed centre line every 2.5 m.
            albedo = mix(vec3(0.16, 0.16, 0.17), pal(0.6) * 0.15, 0.2);
            vec2 l = mod(f.xz, FC_CELL) - 0.5 * FC_CELL;
            // The pixel's footprint from the ray spread (no derivatives inside a branch).
            float lineW = max(hitT * 2.0 / (FC_FOCAL * uResolution.y), 0.02);
            float dash = step(0.5, fract(f.z / 2.5)) * (1.0 - smoothstep(0.06, 0.06 + lineW, abs(f.x - FC_CELL * floor(f.x / FC_CELL + 0.5))));
            albedo = mix(albedo, vec3(0.85, 0.75, 0.45), dash * 0.8 * (1.0 - step(3.0, abs(l.y))));
        }
        col = albedo * (sunCol * key * 0.9 + skyTop * fill * 0.6) * ao + sunCol * rim * albedo + emit;
        // Haze by distance, toward the horizon's colour.
        col = mix(col, skyLow * 0.8, 1.0 - exp(-hitT * 0.025));
    }

    col *= mix(1.0, 0.72, smoothstep(0.6, 2.0, length(uv)));
    col = tonemap(col * 1.15);
    col += (uhashUnit(uhash3(uvec3(gl_FragCoord.xy, 23u))) - 0.5) / 255.0;
    fragColor = vec4(grade(col), 1.0);
}
