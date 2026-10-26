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

// Colonnade Cuts: the camera trucks along a colonnade, and every so often a
// great pillar sweeps across the lens - and the world behind the colonnade is
// a different one when it has passed.
//
// The hidden cut, the film way: a foreground body fills the frame, the shot
// changes while nothing else can be seen, and the body moves on. Only ONE
// world is ever drawn per pixel, so the cut costs nothing.
//
// Four worlds, each its own small 2.5D landscape with parallax: dunes at dusk,
// a night of stars and nebula with a crescent moon, a neon grid sea under a
// striped sun, and aurora over ice. In front of them a colonnade of fluted
// stone columns on a stylobate under an architrave, lit by whichever world is
// behind it. In front of that, once every ten seconds, the near pillar.
//
// THE CUT. The near pillar is drawn in raw screen space, wider than the frame
// whatever the aspect, so it covers every pixel for about 0.4 s around its
// centre. The world index and the worlds' own parallax clocks change exactly
// at that centre, and the travel clock wraps only there too.
//
// ---- No-flash ------------------------------------------------------------------
//
// The near pillar is lit to the worlds' shared mean level, so its passing is a
// sweep of similar brightness rather than a dark bar; its edge takes about 1.8
// s to cross the frame. The colonnade's columns pass under 1 Hz and the grid
// sea's lines under 1 Hz. Every world is designed to the same overall level.
//
// ---- the music -----------------------------------------------------------------
//
// The passage's loudness swells the aurora, the nebula and the sun's glow; the
// spectrum lights the grid sea's sun stripes and the aurora's curtains band by
// band; the bass, slewed, breathes the grid lines. Touch leans the head, and
// the parallax answers.

#define CC_TAU 6.2831853
/** Truck speed, metres per second. */
#define CC_SPEED 1.2
/** Metres between cuts: one near pillar, and one world, per span. */
#define CC_SPAN 12.0
#define CC_WORLDS 4.0
/** The travel clock wraps after this many spans (a multiple of CC_WORLDS), at a pillar's centre. */
#define CC_WRAP 400.0
#define CC_D_NEAR 0.6
#define CC_D_COLS 2.2
#define CC_COL_GAP 1.6
#define CC_HORIZON (-0.1)

float gWorld;
float gLocal;
float gSalt;

vec3 starDots(vec2 p, float density, float size) {
    vec2 cell = floor(p);
    vec2 f = fract(p) - 0.5;
    float h = hashCell(ivec3(ivec2(cell), 77));
    if (h < 1.0 - density) return vec3(0.0);
    vec2 off = vec2(hashCell(ivec3(ivec2(cell), 78)), hashCell(ivec3(ivec2(cell), 79))) - 0.5;
    float d = length(f - off * 0.7);
    float b = pow((h - 1.0 + density) / density, 2.0);
    return mix(vec3(0.75, 0.85, 1.0), vec3(1.0, 0.85, 0.7), off.x + 0.5) * b * smoothstep(size, 0.0, d);
}

/** The light each world throws on the colonnade: direction (x, y), key colour, ambient. */
void worldLight(float w, out vec2 dir, out vec3 key, out vec3 amb) {
    if (w < 0.5) {
        dir = vec2(0.8, 0.3);
        key = mix(vec3(1.0, 0.62, 0.36), pal(0.05), 0.25);
        amb = mix(vec3(0.42, 0.3, 0.32), pal(0.85), 0.2);
    } else if (w < 1.5) {
        dir = vec2(-0.6, 0.7);
        key = mix(vec3(0.6, 0.7, 1.0), pal(0.6), 0.25) * 0.8;
        amb = mix(vec3(0.16, 0.18, 0.32), pal(0.65), 0.2);
    } else if (w < 2.5) {
        dir = vec2(0.0, 0.2);
        key = mix(vec3(1.0, 0.4, 0.75), pal(0.9), 0.3);
        amb = mix(vec3(0.25, 0.15, 0.38), pal(0.75), 0.2);
    } else {
        dir = vec2(-0.3, 0.8);
        key = mix(vec3(0.4, 1.0, 0.75), pal(0.4), 0.3) * 0.8;
        amb = mix(vec3(0.15, 0.25, 0.32), pal(0.5), 0.2);
    }
}

vec3 world(vec2 p, vec2 lean) {
    float swell = clamp(uSwell, 0.0, 1.2);
    float y = p.y - CC_HORIZON;
    vec3 col;
    if (gWorld < 0.5) {
        // Dunes at dusk: three ranges of dunes under a warm sky and a low sun.
        col = mix(mix(vec3(1.0, 0.62, 0.42), pal(0.05), 0.25), mix(vec3(0.32, 0.3, 0.55), pal(0.7), 0.25), smoothstep(0.0, 0.9, y));
        vec2 sunP = vec2(0.5, 0.12);
        float sd = length(p - sunP);
        col += mix(vec3(1.0, 0.8, 0.5), pal(0.08), 0.2) * ((0.3 + 0.15 * swell) * exp(-sd * 3.0) + smoothstep(0.1, 0.085, sd));
        for (int i = 0; i < 3; i++) {
            float depth = 40.0 / (1.0 + 1.6 * float(i));
            float x = p.x + (gLocal + lean.x * float(i + 1)) / depth + gSalt * 5.0 + float(i) * 3.3;
            float crest = CC_HORIZON - 0.05 * float(i) + (0.07 + 0.03 * float(i)) * (0.6 * sin(x * 1.7) + 0.4 * sin(x * 4.1 + 1.3))
                        + 0.03 * fbm3(vec3(x * 2.0, float(i), gSalt), 3);
            float slope = 1.7 * cos(x * 1.7) * 0.6 + 4.1 * cos(x * 4.1 + 1.3) * 0.4;
            vec3 sand = mix(vec3(0.75, 0.45, 0.28), pal(0.07) * 0.8, 0.25) * (0.55 + 0.25 * float(i));
            sand *= 0.75 + 0.35 * clamp(slope * 0.3, -1.0, 1.0);
            sand = mix(sand, col, 0.35 / (1.0 + float(i)));
            float aa = 1.5 / uResolution.y;
            col = mix(col, sand, 1.0 - smoothstep(crest - aa, crest + aa, p.y));
        }
    } else if (gWorld < 1.5) {
        // Night: a nebula across the stars, a crescent moon, a dark ridge.
        vec2 q = p + vec2(gLocal / 60.0 + gSalt * 3.0, 0.0);
        col = mix(vec3(0.03, 0.04, 0.1), mix(vec3(0.12, 0.1, 0.28), pal(0.66), 0.3), smoothstep(-0.1, 0.8, y));
        float neb = fbm3(vec3(q * 1.6, gSalt), 5);
        col += mix(vec3(0.6, 0.3, 0.8), pal(0.8), 0.4) * smoothstep(0.45, 0.85, neb) * (0.45 + 0.25 * swell);
        col += mix(vec3(0.3, 0.6, 0.9), pal(0.55), 0.4) * smoothstep(0.55, 0.9, fbm3(vec3(q * 3.0, gSalt + 4.0), 4)) * 0.25;
        col += starDots(q * 40.0, 0.25, 0.12) * 1.2 + starDots(q * 90.0, 0.2, 0.15) * 0.7;
        vec2 moonP = vec2(-0.55, 0.5);
        float moon = smoothstep(0.1, 0.095, length(p - moonP)) * smoothstep(0.085, 0.095, length(p - moonP - vec2(0.04, 0.02)));
        col += mix(vec3(1.0, 0.95, 0.85), pal(0.12), 0.15) * (moon * 1.2 + 0.15 * exp(-length(p - moonP) * 5.0));
        // Moonlit hills with mist pooled in the valleys, so the ground carries light too.
        float hx = (p.x + gLocal / 25.0) * 2.0;
        float ridge = CC_HORIZON + 0.01 + 0.45 * max(fbm3(vec3(hx, gSalt, 1.0), 4) - 0.35, 0.0);
        float aa = 1.5 / uResolution.y;
        float below = ridge - p.y;
        vec3 hill = mix(vec3(0.1, 0.12, 0.2), pal(0.65) * 0.2, 0.3) * (0.8 + 0.4 * fbm3(vec3(hx * 6.0, p.y * 10.0, gSalt), 3));
        vec3 mist = mix(vec3(0.35, 0.4, 0.6), pal(0.6) * 0.6, 0.3);
        hill = mix(hill, mist, 0.55 * exp(-max(p.y - CC_HORIZON + 0.25, 0.0) * 4.0) * smoothstep(0.0, 0.05, below));
        col = mix(col, hill, 1.0 - smoothstep(-aa, aa, -below));
    } else if (gWorld < 2.5) {
        // Neon grid sea: a striped sun over a perspective grid.
        col = mix(mix(vec3(0.95, 0.35, 0.55), pal(0.92), 0.3), mix(vec3(0.15, 0.08, 0.3), pal(0.72), 0.3), smoothstep(0.0, 0.8, y));
        vec2 sunP = vec2(0.0, 0.2);
        float sd = length(p - sunP);
        float band = aband(clamp((p.y - sunP.y + 0.3) / 0.6, 0.0, 1.0) * 0.85 + 0.04);
        float stripes = step(0.3 + 0.25 * clamp(band, 0.0, 1.0), fract((p.y - sunP.y) * 14.0)) + step(0.0, p.y - sunP.y);
        float disc = smoothstep(0.32, 0.31, sd) * clamp(stripes, 0.0, 1.0);
        col = mix(col, mix(mix(vec3(1.0, 0.85, 0.3), vec3(1.0, 0.3, 0.6), smoothstep(0.3, -0.25, p.y - sunP.y)), pal(0.05), 0.2), disc);
        col += mix(vec3(1.0, 0.4, 0.7), pal(0.9), 0.3) * exp(-sd * 2.5) * (0.15 + 0.1 * swell);
        if (y < 0.0) {
            float z = 0.5 / max(-y, 0.003);
            float gx = (p.x + lean.x / z) * z + gLocal;
            float fw = fwidth(gx) + 1e-4;
            float fz = fwidth(z) + 1e-4;
            float lx = 1.0 - smoothstep(0.0, 1.5 * fw, abs(fract(gx / 2.0 + 0.5) - 0.5) * 2.0);
            float lz = 1.0 - smoothstep(0.0, 1.5 * fz, abs(fract(z / 2.0 + 0.5) - 0.5) * 2.0);
            // Lines finer than a pixel fade to their average instead of moire.
            float fade = 1.0 - smoothstep(0.3, 1.2, max(fw, fz));
            float grid = max(lx, lz) * fade + 0.1 * (1.0 - fade);
            vec3 floorCol = mix(vec3(0.05, 0.02, 0.12), pal(0.75) * 0.15, 0.3);
            vec3 neon = mix(vec3(0.3, 0.9, 1.0), pal(0.5), 0.35) * (0.8 + 0.25 * clamp(uBassSmooth, 0.0, 1.2));
            col = mix(floorCol + neon * grid * 0.8, col * 0.6, smoothstep(8.0, 40.0, z) * 0.6);
        }
    } else {
        // Aurora over ice: curtains of light, white peaks, a frozen lake.
        vec2 q = p + vec2(gLocal / 50.0 + gSalt * 2.0, 0.0);
        col = mix(vec3(0.03, 0.08, 0.12), mix(vec3(0.05, 0.12, 0.25), pal(0.6), 0.3), smoothstep(0.0, 0.9, y));
        float curtainX = q.x * 1.5 + 0.4 * fbm3(vec3(q.x * 0.8, uTime * 0.03, gSalt), 3);
        float band = aband(fract(curtainX * 0.25) * 0.85 + 0.04);
        float folds = pow(0.5 + 0.5 * sin(curtainX * 6.0 + 2.0 * fbm3(vec3(q * 2.0, gSalt), 3)), 3.0);
        float height = smoothstep(0.05, 0.3, y) * smoothstep(0.95, 0.4, y);
        vec3 aur = mix(mix(vec3(0.3, 1.0, 0.6), pal(0.4), 0.35), mix(vec3(0.7, 0.3, 1.0), pal(0.8), 0.35), smoothstep(0.3, 0.8, y));
        col += aur * folds * height * (0.35 + 0.2 * swell + 0.2 * clamp(band, 0.0, 1.2));
        col += starDots(q * 60.0, 0.15, 0.12) * 0.8;
        float pk = clamp((fbm3(vec3((p.x + gLocal / 30.0) * 1.8, gSalt, 3.0), 4) - 0.3) / 0.45, 0.0, 1.0);
        float peak = CC_HORIZON + 0.01 + 0.3 * pk * pk;
        float aa = 1.5 / uResolution.y;
        vec3 ice = mix(vec3(0.55, 0.65, 0.75), pal(0.55) * 0.7, 0.25) * (0.6 + 0.4 * fbm3(vec3(p * 12.0, gSalt), 3));
        col = mix(col, ice, 1.0 - smoothstep(peak - aa, peak + aa, p.y));
        if (y < 0.0) {
            // The lake mirrors the sky above it, darkened.
            vec2 m = vec2(p.x, CC_HORIZON - y);
            float mf = pow(0.5 + 0.5 * sin((m.x + gLocal / 50.0 + gSalt * 2.0) * 9.0 + 2.0 * fbm3(vec3(m * 2.0, gSalt), 3)), 3.0);
            vec3 lake = aur * mf * 0.35 + mix(vec3(0.12, 0.18, 0.24), pal(0.55) * 0.25, 0.3);
            col = mix(lake * 0.7, lake, smoothstep(-0.4, 0.0, y));
        }
    }
    return col;
}

/** A fluted column of radius r centred at screen x = cx: coverage, and its lit colour. */
vec4 column(vec2 p, float cx, float r, vec2 ldir, vec3 key, vec3 amb, float aa) {
    float u = (p.x - cx) / r;
    float cover = 1.0 - smoothstep(1.0 - aa / r, 1.0 + aa / r, abs(u));
    if (cover <= 0.0) return vec4(0.0);
    float nx = clamp(u, -1.0, 1.0);
    float nz = sqrt(max(1.0 - nx * nx, 0.0));
    // Flutes: shallow grooves around the shaft, fading where they get thin.
    float flutes = 0.5 + 0.5 * cos(asin(nx) * 20.0);
    float fl = mix(flutes, 0.5, smoothstep(0.004, 0.012, aa / max(r, 1e-3) * 0.3));
    vec3 n = normalize(vec3(nx, 0.0, nz) + vec3(0.0, 0.0, -0.12 * (fl - 0.5)));
    float diff = max(dot(n.xz, normalize(vec2(ldir.x, 0.6))), 0.0);
    float rim = pow(1.0 - nz, 3.0);
    vec3 stone = mix(vec3(0.72, 0.68, 0.62), pal(0.1) * 0.8, 0.12);
    vec3 c = stone * (key * diff * 0.8 + amb * 0.9) + key * rim * 0.25;
    c *= 0.82 + 0.18 * fl;
    return vec4(c, cover);
}

vec3 tonemap(vec3 c) {
    c = max(c, 0.0);
    return clamp(c * (2.51 * c + 0.03) / (c * (2.43 * c + 0.59) + 0.14), 0.0, 1.0);
}

void main() {
    vec2 uv = view();
    float aa = 1.5 / uResolution.y;
    float aspect = uResolution.x / uResolution.y;

    float x = mod(uTime * CC_SPEED, CC_SPAN * CC_WRAP);
    float span = floor(x / CC_SPAN);
    gWorld = mod(span, CC_WORLDS);
    gLocal = x - span * CC_SPAN;
    gSalt = hash11(mod(span, CC_WRAP) + 0.37);
    vec2 lean = vec2(0.0);
    if (!touchIdle()) lean = touchAnchor() * (0.5 * touchStrength());

    vec3 col = world(uv, lean);

    // ---- the colonnade ------------------------------------------------------------------
    vec2 ldir;
    vec3 key;
    vec3 amb;
    worldLight(gWorld, ldir, key, amb);
    float cx = (uv.x + lean.x / CC_D_COLS) * CC_D_COLS + x;
    float k = floor(cx / CC_COL_GAP + 0.5);
    float colCentre = (k * CC_COL_GAP - x) / CC_D_COLS - lean.x / CC_D_COLS;
    // The column's shadow on the stylobate and its contact darkening.
    float top = 0.62;
    float base = -0.72;
    vec4 shaft = column(uv, colCentre, 0.17 / CC_D_COLS, ldir, key, amb, aa);
    float inShaft = (1.0 - smoothstep(top - aa, top + aa, uv.y)) * smoothstep(base - aa, base + aa, uv.y);
    // Soft occlusion where shaft meets architrave and base.
    float contact = 1.0 - 0.35 * (exp(-(top - uv.y) * 25.0) + exp(-(uv.y - base) * 25.0));
    col = mix(col, shaft.rgb * contact, shaft.a * inShaft);
    // Architrave above and stylobate below, in blocks that slide with the columns.
    vec3 stone = mix(vec3(0.7, 0.66, 0.6), pal(0.1) * 0.8, 0.12);
    float bx = cx;
    float seam = 1.0 - smoothstep(0.0, 0.03 + aa * CC_D_COLS, abs(fract(bx) - 0.5) * 2.0 - 0.94);
    if (uv.y > top - aa) {
        float face = smoothstep(top + 0.06, top, uv.y);
        vec3 beam = stone * (amb * 0.95 + key * (0.25 + 0.3 * face)) * (0.85 + 0.15 * seam);
        beam *= 1.0 - 0.25 * smoothstep(top + 0.25, top + 0.02, uv.y) * (1.0 - face);
        col = mix(col, beam, smoothstep(top - aa, top + aa, uv.y));
    }
    if (uv.y < base + aa) {
        float lip = smoothstep(base - 0.05, base, uv.y);
        vec3 stepCol = stone * (amb * 0.85 + key * (0.3 + 0.35 * lip)) * (0.85 + 0.15 * seam);
        // Each column's shadow falls along the step, away from the light.
        float shadowX = colCentre - ldir.x * 0.25 * (base - uv.y);
        float sh = 1.0 - smoothstep(0.08, 0.14, abs(uv.x - shadowX));
        stepCol *= 1.0 - 0.35 * sh;
        col = mix(col, stepCol, 1.0 - smoothstep(base - aa, base + aa, uv.y));
    }

    // ---- the near pillar: the cut -------------------------------------------------------
    // Raw screen space, so it covers the frame whatever the view transforms do.
    vec2 sp = vUv * 2.0 - 1.0;
    sp.x *= aspect;
    float nearHalf = aspect + 0.45;
    // Screen position of the pillar whose centre is at the next or the last
    // span boundary: whichever is nearer.
    float toNext = (CC_SPAN - gLocal) / CC_D_NEAR;
    float toLast = -gLocal / CC_D_NEAR;
    float pc = abs(toNext) < abs(toLast) ? toNext : toLast;
    float nu = (sp.x - pc) / nearHalf;
    float nCover = 1.0 - smoothstep(1.0 - aa / nearHalf, 1.0 + aa / nearHalf, abs(nu));
    if (nCover > 0.0) {
        // Lit to the worlds' shared mean, independent of the world, so the
        // cut is not a brightness event: a soft cylinder with broad flutes.
        float nx = clamp(nu, -1.0, 1.0);
        float nz = sqrt(max(1.0 - nx * nx, 0.0));
        float flutes = 0.5 + 0.5 * cos(asin(nx) * 14.0);
        vec3 nearStone = mix(vec3(0.5, 0.46, 0.42), pal(0.08) * 0.6, 0.15);
        // Faint veins in the marble, running up the shaft.
        float vein = fbm3(vec3(nx * 3.0, sp.y * 1.2, 5.0), 4);
        nearStone *= 0.9 + 0.2 * vein;
        vec3 nearCol = nearStone * (0.55 + 0.35 * nz + 0.1 * flutes) + mix(vec3(0.3, 0.25, 0.22), pal(0.1) * 0.3, 0.2) * pow(1.0 - nz, 2.0);
        col = mix(col, nearCol, nCover);
    }

    col *= mix(1.0, 0.75, smoothstep(0.7, 2.1, length(uv)));
    col = tonemap(col * 1.15);
    col += (uhashUnit(uhash3(uvec3(gl_FragCoord.xy, 29u))) - 0.5) / 255.0;
    fragColor = vec4(grade(col), 1.0);
}
