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
//#include lib_style

// Ten Powers: a pull-back through the orders of magnitude.
//
// THE MOVE is a continuous logarithmic zoom out. The camera's coordinate is
// log10 of the frame's width, L, which only ever grows: dew becomes a leaf,
// the leaf a garden, the garden a city, the city a coastline, the coast a
// planet, the planet one of the lights of a galaxy - and the galaxy, looked at
// closely enough, was a drop of dew. Seven worlds, one per decade, in a loop.
//
// HOW ONE PASS DRAWS A JOURNEY THAT LONG. Every world m is drawn in its own
// local units, where its extent is a disc of radius 1: a screen point at
// radius rr sits at local radius rr * 10^(L - m). Each pixel picks the ONE
// decade whose disc it falls in at local radius 0.1..1 - so the centre of the
// frame shows the small worlds and the rim the big one - and only a narrow
// band at the inner edge of each disc blends in the world below. Nothing ever
// grows past local radius ~2, so the coordinates are re-based every decade by
// construction and floats never run away however long the pull-back goes.
//
// THE MUSIC. L advances on uFlowPhase - integrated loudness - so a loud
// passage pulls back fast (about 4 s a decade) and a quiet one hovers (about
// 20 s). It never runs backwards and never jumps, because uFlowPhase cannot.
// Treble twinkles the small lights (dew glints, windows, stars), uSwell warms
// the whole picture a little, and a new form (uFormPhase) slides the palette.
//
// NO-FLASH. Every feature only shrinks toward the centre at the zoom's pace;
// nothing crosses the frame. The twinkle is a small share of tiny points.

#define TP_TAU 6.2831853
#define TP_LN10 2.3025851
/** How many decades of L one second of uFlowPhase covers. */
#define TP_RATE 0.55
/** How far below the current decade the centre of the frame is still resolved. */
#define TP_DEPTH 3.0

float tpNoise(vec2 p) { return motionNoise(p); }

float tpFbm(vec2 p) {
    float s = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) {
        s += a * tpNoise(p);
        p = p * 2.03 + vec2(17.1, 9.3);
        a *= 0.5;
    }
    return s / 0.9375;
}

/** Soft line of half-width w at distance d, antialiased over px. */
float tpLine(float d, float w, float px) {
    return 1.0 - smoothstep(w - px, w + px, d);
}

// ---- the seven worlds, each in its own disc units ---------------------------

vec3 worldDew(vec2 x, float px, float hue, float glint) {
    vec3 base = mix(vec3(0.05, 0.15, 0.08), pal(hue + 0.3) * 0.18, 0.4) * (0.7 + 0.6 * tpFbm(x * 3.0));
    vec2 g = x * 4.0;
    vec2 c = floor(g);
    vec3 col = base;
    for (int j = -1; j <= 1; j++) {
        for (int i = -1; i <= 1; i++) {
            vec2 cell = c + vec2(float(i), float(j));
            float h = hashCell(ivec3(int(cell.x), int(cell.y), 3));
            if (h < 0.35) continue;
            vec2 o = cell + 0.5 + 0.3 * vec2(hashCell(ivec3(int(cell.x), int(cell.y), 5)) - 0.5,
                                             hashCell(ivec3(int(cell.x), int(cell.y), 7)) - 0.5);
            float rad = 0.12 + 0.25 * (h - 0.35);
            vec2 d = (g - o) / rad;
            float r2 = dot(d, d);
            float edge = 1.0 - smoothstep(1.0 - px * 8.0 / rad, 1.0, r2);
            if (edge <= 0.0) continue;
            // A lens: the background seen through it, flipped and brightened, a
            // dark meniscus at the rim and a specular glint up and to the left.
            vec3 seen = mix(base, pal(hue + 0.25), 0.35) * (1.3 - 0.6 * r2);
            float rim = smoothstep(0.55, 1.0, r2);
            float spec = exp(-dot(d - vec2(-0.35, 0.4), d - vec2(-0.35, 0.4)) * 18.0);
            vec3 drop = seen * (1.0 - 0.7 * rim) + vec3(1.0) * spec * (0.7 + 0.5 * glint);
            col = mix(col, drop, edge);
        }
    }
    return col;
}

vec3 worldLeaf(vec2 x, float px, float hue) {
    float lam = tpFbm(x * 6.0);
    vec3 col = mix(vec3(0.05, 0.2, 0.06), pal(hue + 0.33) * 0.35, 0.3) * (0.75 + 0.5 * lam);
    // The midrib, then the side veins sweeping out of it at an angle.
    float mid = tpLine(abs(x.y + 0.04 * sin(x.x * 3.0)), 0.02, px);
    float vein = abs(fract(x.x * 3.0 - abs(x.y) * 2.2) - 0.5) / 3.0;
    float side = tpLine(vein, 0.006 + 0.01 * (1.0 - min(abs(x.y), 1.0)), px);
    // Areoles: the cells between the fine veins.
    float cells = tpLine(abs(tpNoise(x * 22.0) - 0.5), 0.03, px * 22.0) * 0.35;
    vec3 veinCol = mix(vec3(0.55, 0.7, 0.3), pal(hue + 0.15), 0.3);
    col = mix(col, veinCol, max(max(mid, side * 0.8), cells));
    return col;
}

vec3 worldGarden(vec2 x, float px, float hue, float glint) {
    // Beds in rows, paths between, flowers dotted along the beds.
    vec2 g = x * vec2(5.0, 9.0);
    float row = floor(g.y);
    float inBed = smoothstep(0.08, 0.08 + px * 9.0, fract(g.y)) * smoothstep(0.92, 0.92 - px * 9.0, fract(g.y));
    vec3 path = vec3(0.42, 0.36, 0.28) * (0.8 + 0.3 * tpNoise(x * 40.0));
    vec3 bed = mix(vec3(0.06, 0.22, 0.07), pal(hue + 0.3) * 0.3, 0.25) * (0.7 + 0.6 * tpFbm(x * 20.0));
    vec3 col = mix(path, bed, inBed);
    vec2 f = vec2(g.x * 3.0, g.y);
    vec2 cell = floor(f);
    vec2 local = fract(f) - 0.5;
    float h = hashCell(ivec3(int(cell.x), int(row), 11));
    float bloom = smoothstep(0.3, 0.3 - px * 30.0, length(local * vec2(1.0, 2.0)));
    col = mix(col, pal(hue + h * 0.6) * (0.8 + 0.3 * glint), bloom * step(0.4, h) * inBed);
    return col;
}

vec3 worldCity(vec2 x, float px, float hue, float glint) {
    vec2 g = x * 7.0;
    vec2 block = floor(g);
    vec2 f = fract(g);
    // Streets around each block, avenues every third.
    float street = max(smoothstep(0.1 + px * 7.0, 0.1, min(f.x, 1.0 - f.x)), smoothstep(0.1 + px * 7.0, 0.1, min(f.y, 1.0 - f.y)));
    float h = hashCell(ivec3(int(block.x), int(block.y), 13));
    vec3 roof = vec3(0.12, 0.12, 0.14) + 0.1 * h;
    // Windows: a fine grid, some of them lit warm.
    vec2 w = floor(g * 9.0);
    float lit = step(0.62, hashCell(ivec3(int(w.x), int(w.y), 17)));
    vec2 wf = fract(g * 9.0);
    float pane = step(0.25, wf.x) * step(0.25, wf.y);
    vec3 warm = mix(vec3(1.0, 0.75, 0.4), pal(hue + 0.08), 0.35);
    vec3 col = roof + warm * lit * pane * (0.45 + 0.25 * glint * h);
    // Parks: some blocks are green.
    if (h < 0.12) col = vec3(0.05, 0.18, 0.07) * (0.7 + 0.5 * tpNoise(g * 6.0));
    vec3 road = vec3(0.05, 0.05, 0.06) + warm * 0.25 * tpLine(abs(min(f.x, f.y) - 0.0), 0.02, px * 7.0);
    return mix(col, road, street);
}

vec3 worldCoast(vec2 x, float px, float hue) {
    float n = tpFbm(x * 2.2 + vec2(3.0, 1.0)) + 0.18 * x.x;
    float land = smoothstep(0.52, 0.52 + px * 6.0, n);
    vec3 sea = mix(vec3(0.02, 0.08, 0.2), pal(hue + 0.6) * 0.25, 0.3) * (0.7 + 0.5 * smoothstep(0.3, 0.52, n));
    vec3 ground = mix(vec3(0.12, 0.25, 0.08), vec3(0.45, 0.38, 0.25), smoothstep(0.6, 0.8, n)) * (0.75 + 0.4 * tpFbm(x * 14.0));
    float foam = tpLine(abs(n - 0.52), 0.006, px * 6.0);
    vec3 col = mix(sea, ground, land);
    col += vec3(0.8, 0.85, 0.9) * foam * 0.6;
    // Cloud wisps over everything.
    col = mix(col, vec3(0.85), smoothstep(0.62, 0.85, tpFbm(x * 1.4 + vec2(9.0, 4.0))) * 0.55);
    return col;
}

vec3 worldPlanet(vec2 x, float px, float hue, float glint) {
    float r = length(x);
    const float R = 0.82;
    vec3 space = vec3(0.004, 0.006, 0.015);
    // Stars behind.
    vec2 sg = floor(x * 40.0);
    float st = hashCell(ivec3(int(sg.x), int(sg.y), 19));
    space += vec3(0.8) * step(0.985, st) * smoothstep(0.35, 0.0, length(fract(x * 40.0) - 0.5)) * (0.5 + 0.3 * glint);
    if (r > R + px * 2.0 + 0.12) return space;
    // The disc as a lit sphere: normal from the disc position, light from the upper left.
    vec2 q = x / R;
    float z = sqrt(max(1.0 - dot(q, q), 0.0));
    vec3 n = vec3(q, z);
    float lambert = max(dot(n, normalize(vec3(-0.6, 0.45, 0.65))), 0.0);
    float lat = asin(clamp(q.y, -1.0, 1.0));
    float bands = tpFbm(vec2(lat * 3.0, q.x * 0.6) * 4.0);
    vec3 surf = mix(vec3(0.05, 0.12, 0.3), mix(vec3(0.15, 0.3, 0.12), vec3(0.8), smoothstep(0.62, 0.8, bands)), smoothstep(0.45, 0.55, bands));
    surf = mix(surf, pal(hue + 0.55) * 0.5, 0.15);
    vec3 col = surf * (0.08 + 1.1 * lambert);
    float disc = 1.0 - smoothstep(R - px * 2.0, R + px * 2.0, r);
    // The atmosphere: a thin blue rim, brightest on the lit limb.
    float limb = exp(-abs(r - R) * 45.0) * (0.3 + 0.9 * max(dot(normalize(x + 1e-5), vec2(-0.6, 0.45)), 0.0));
    vec3 atmo = mix(vec3(0.35, 0.6, 1.0), pal(hue + 0.6), 0.3) * limb;
    return mix(space, col, disc) + atmo * 0.7;
}

vec3 worldGalaxy(vec2 x, float px, float hue, float glint) {
    float r = length(x);
    float a = atan(x.y, x.x);
    // Two logarithmic-spiral arms, dust lanes along their inner edges.
    float spiral = sin(2.0 * (a - log(max(r, 1e-3)) * 2.4));
    float arms = smoothstep(0.1, 1.0, spiral) * exp(-r * 2.2);
    float dust = smoothstep(-0.2, -0.75, spiral) * exp(-r * 2.6);
    float grain = tpFbm(x * 18.0);
    vec3 core = mix(vec3(1.0, 0.85, 0.6), pal(hue + 0.1), 0.25) * exp(-r * 9.0) * 1.2;
    vec3 armCol = mix(vec3(0.45, 0.6, 1.0), pal(hue + 0.65), 0.35) * arms * (0.5 + 0.8 * grain);
    vec3 col = vec3(0.004, 0.005, 0.012) + core + armCol;
    col *= 1.0 - 0.6 * dust;
    vec2 sg = floor(x * 70.0);
    float st = hashCell(ivec3(int(sg.x), int(sg.y), 23));
    col += vec3(0.9) * step(0.97 - 0.05 * arms, st) * smoothstep(0.3, 0.0, length(fract(x * 70.0) - 0.5)) * (0.4 + 0.3 * glint);
    return col;
}

vec3 tpWorld(float m, vec2 x, float px, float hue, float glint) {
    float id = mod(m, 7.0);
    float h = hue + id * 0.143;
    // Each decade turns a little against the last, so the handoffs read as a turn of the lens.
    x = rot2(m * 1.1) * x;
    if (id < 0.5) return worldDew(x, px, h, glint);
    if (id < 1.5) return worldLeaf(x, px, h);
    if (id < 2.5) return worldGarden(x, px, h, glint);
    if (id < 3.5) return worldCity(x, px, h, glint);
    if (id < 4.5) return worldCoast(x, px, h);
    if (id < 5.5) return worldPlanet(x, px, h, glint);
    return worldGalaxy(x, px, h, glint);
}

vec3 tpTone(vec3 c) {
    return 1.0 - exp(-max(c, 0.0) * 1.4);
}

void main() {
    vec2 uv = view();
    // A finger moves the point the zoom recedes into.
    uv -= touchAnchor() * 0.5;
    float hue = 0.02 + 0.2 * uFormPhase;
    float glint = min(uTrebleSmooth, 1.3);

    // Log-width of the frame, in decades. Integrated loudness only, so it never steps or reverses.
    float L = uFlowPhase * TP_RATE;
    float pxScreen = 2.0 / max(uResolution.y, 1.0) * xm(uXScale);
    vec2 suv = uv * xm(uXScale);

    float rr = max(length(suv), 1e-5);
    float m = floor(log(rr) / TP_LN10 + L) + 1.0;
    m = max(m, floor(L) - TP_DEPTH);
    float k = pow(10.0, L - m);
    vec2 x = suv * k;
    float rLocal = rr * k;

    vec3 col = tpWorld(m, x, pxScreen * k, hue, glint);
    // The handoff: the inner edge of this disc dissolves into the world below.
    float wIn = smoothstep(0.17, 0.1, rLocal);
    if (wIn > 0.0 && m > floor(L) - TP_DEPTH) {
        col = mix(col, tpWorld(m - 1.0, x * 10.0, pxScreen * k * 10.0, hue, glint), wIn);
    }

    col *= 0.85 + 0.25 * clamp(uSwell, 0.0, 1.0);
    if (!touchIdle()) {
        col += pal(hue + 0.5) * min(touchWake(uv), 2.0) * 0.05;
    }
    col = tpTone(col);
    col *= 0.78 + 0.22 * smoothstep(1.9, 0.4, length(uv));
    fragColor = vec4(grade(col), 1.0);
}
