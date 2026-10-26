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

// Train Window: a side-window journey past layered country, with the line
// poles ticking by.
//
// A lateral truck, built the 2.5D way: each layer slides by travel / depth, so
// the mountains barely move, the hills drift, the tree line runs, the fence
// races and the catenary poles flick past. Six layers and that one rule are
// the whole parallax; there is no march, and it draws at full resolution.
//
// Every so often the line dives into a TUNNEL: its near wall sweeps across the
// window, the inside is dim and lamp-lit, and while it fills the window the
// country behind is swapped for a new one (another palette, other mountains,
// sometimes the sea). The swap, and the wrap of the travel clock, both happen
// only while the tunnel covers the whole window, so neither can be seen.
//
// ---- No-flash ------------------------------------------------------------------
//
// Bright or dark repeats crossing the window stay at or under 2.5 a second:
// poles every 8 m at depth 2.6 pass under 1 Hz, fence posts every 3 m at depth
// 4 pass about 2.3 Hz and are smeared by an analytic motion blur that lowers
// their contrast, and tunnel lamps pass under 1 Hz. A tunnel's wall takes over
// a second to sweep the window, and its inside stays well off black.
//
// ---- the music -----------------------------------------------------------------
//
// The bass rocks the carriage on its springs (slewed); the passage's loudness
// raises the sun a little; the spectrum is the far tree line, band by band.
// Touch leans your head toward the glass: the near layers shift more than the
// far ones, which is the parallax doing the work.

#define TW_TAU 6.2831853
/** Train speed, metres per second. */
#define TW_SPEED 7.0
/** One tunnel per this many metres; its centre is where the country changes. */
#define TW_LEG 160.0
/** The travel clock wraps after this many legs, inside a tunnel. */
#define TW_LEGS 64.0
#define TW_HORIZON (-0.06)
/** Layer depths. */
#define TW_D_MOUNT 300.0
#define TW_D_HILL 60.0
#define TW_D_TREES 18.0
#define TW_D_FENCE 4.0
#define TW_D_POLE 2.6
#define TW_D_TUNNEL 2.0
/** Travel during the shutter, for the motion blur: an assumed 1/120 s. */
#define TW_SHUTTER (1.0 / 120.0)

float gSeed;
float gHue;

float n1(float x, float salt) {
    return vnoise3(vec3(x, salt + gSeed * 17.0, 0.5));
}

float ridge(float x, float salt) {
    float r = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) {
        r += a * (1.0 - abs(2.0 * n1(x, salt + float(i) * 3.7) - 1.0));
        x *= 2.03;
        a *= 0.5;
    }
    return r;
}

/** Coverage of a vertical silhouette edge at height h, anti-aliased over one pixel. */
float below(float y, float h, float aa) {
    return 1.0 - smoothstep(h - aa, h + aa, y);
}

vec3 tonemap(vec3 c) {
    c = max(c, 0.0);
    return clamp(c * (2.51 * c + 0.03) / (c * (2.43 * c + 0.59) + 0.14), 0.0, 1.0);
}

void main() {
    vec2 uv = view();
    float aa = 1.5 / uResolution.y;

    // ---- the window ---------------------------------------------------------------
    float aspect = uResolution.x / uResolution.y;
    vec2 winHalf = vec2(aspect * 0.86, 0.8);
    float corner = 0.14;
    vec2 wq = abs(uv) - (winHalf - corner);
    float win = length(max(wq, 0.0)) + min(max(wq.x, wq.y), 0.0) - corner;

    // ---- the journey --------------------------------------------------------------
    float x = mod(uTime * TW_SPEED, TW_LEG * TW_LEGS);
    float leg = floor(x / TW_LEG + 0.5);
    // Metres since the centre of the last tunnel: the country's own coordinate,
    // which resets (unseen) inside every tunnel.
    float local = x - leg * TW_LEG;
    gSeed = mod(leg, TW_LEGS);
    gHue = hash11(gSeed + 0.31) * 0.6;
    float isSea = step(0.7, hash11(gSeed * 1.9 + 4.0));

    // The carriage on its springs: a slow sway, deepened by the bass.
    float springs = (0.004 + 0.006 * clamp(uBassSmooth, 0.0, 1.3)) * sin(uTime * 2.1) + 0.003 * sin(uTime * 0.77);
    vec2 sv = uv + vec2(0.0, springs);
    // Head toward the glass: a lateral shift that the near layers feel most.
    vec2 lean = vec2(0.0);
    if (!touchIdle()) lean = touchAnchor() * (0.6 * touchStrength());

    // ---- sky ----------------------------------------------------------------------
    float sunY = 0.32 + 0.08 * clamp(uSwell, 0.0, 1.2);
    vec2 sunP = vec2(0.6 * aspect, sunY);
    vec3 skyLow = mix(vec3(1.0, 0.78, 0.58), pal(gHue + 0.05), 0.3);
    vec3 skyHigh = mix(vec3(0.3, 0.5, 0.82), pal(gHue + 0.6), 0.3);
    float sy = sv.y - TW_HORIZON;
    vec3 col = mix(skyLow, skyHigh, smoothstep(0.0, 0.9, sy));
    float sd = length(sv - sunP);
    col += mix(vec3(1.0, 0.85, 0.6), pal(gHue), 0.2) * (0.35 * exp(-sd * 2.5) + 0.8 * smoothstep(0.075, 0.06, sd));
    // Clouds drifting with the far layer.
    float cx = sv.x + local / 900.0 + uTime * 0.004;
    float cloud = smoothstep(0.5, 0.8, fbm3(vec3(cx * 1.4, sy * 3.5, gSeed), 4)) * smoothstep(0.05, 0.3, sy);
    col = mix(col, mix(skyLow, vec3(1.0), 0.6), cloud * 0.5);

    // ---- mountains, or the sea ----------------------------------------------------
    // The mountains are too far for the head's lean to move them.
    float mx = sv.x + local / TW_D_MOUNT + gSeed * 3.1;
    float rg = ridge(mx * 1.6, 1.0);
    float mh = TW_HORIZON + (0.01 + 0.42 * rg * rg) * (1.0 - isSea);
    // Rock: a 2D texture of gullies, lit from the sun's side by its own slope,
    // so the face has relief instead of columns.
    float rock = fbm3(vec3(mx * 9.0, sv.y * 14.0, gSeed + 3.0), 4);
    float rockX = fbm3(vec3(mx * 9.0 + 0.05, sv.y * 14.0, gSeed + 3.0), 4);
    vec3 mCol = mix(mix(vec3(0.35, 0.42, 0.55), pal(gHue + 0.62) * 0.6, 0.3), skyLow, 0.4);
    mCol *= 0.75 + 0.5 * rock + 1.5 * (rockX - rock);
    // Snow on the tall peaks, ragged at its lower edge.
    float snowLine = TW_HORIZON + 0.2 + 0.03 * rock;
    mCol = mix(mCol, mix(skyLow, vec3(1.0), 0.75) * (0.9 + 0.2 * rock), smoothstep(snowLine, snowLine + 0.02, sv.y) * 0.85);
    // Haze thickening toward the foot of the range.
    mCol = mix(mCol, skyLow, 0.35 * smoothstep(TW_HORIZON + 0.12, TW_HORIZON, sv.y));
    col = mix(col, mCol, below(sv.y, mh, aa) * (1.0 - isSea));
    if (isSea > 0.5 && sv.y < TW_HORIZON + 0.02) {
        // A calm sea to the horizon with a glitter path under the sun.
        float depth = TW_HORIZON + 0.02 - sv.y;
        vec3 sea = mix(skyLow * 0.8, mix(vec3(0.1, 0.25, 0.4), pal(gHue + 0.55) * 0.5, 0.3), smoothstep(0.0, 0.2, depth));
        float glint = vnoise3(vec3(sv.x * 60.0 + local * 0.02, depth * 400.0 / (depth + 0.02), 3.0));
        sea += mix(vec3(1.0, 0.85, 0.6), pal(gHue), 0.2) * smoothstep(0.75, 0.95, glint) * exp(-abs(sv.x - sunP.x) * 3.0) * 0.5;
        col = sea;
    }

    // ---- hills ---------------------------------------------------------------------
    float hx = sv.x + (local + lean.x * 3.0) / TW_D_HILL;
    float hh = TW_HORIZON + 0.02 + 0.11 * fbm3(vec3(hx * 1.2, gSeed, 7.0), 3) - 0.04 * isSea;
    float hSlope = (fbm3(vec3(hx * 1.2 + 0.01, gSeed, 7.0), 3) - fbm3(vec3(hx * 1.2 - 0.01, gSeed, 7.0), 3)) * 40.0;
    vec3 hCol = mix(mix(vec3(0.25, 0.4, 0.22), pal(gHue + 0.3) * 0.55, 0.3), skyLow, 0.28);
    hCol *= 0.85 + 0.2 * clamp(-hSlope, -1.0, 1.0);
    col = mix(col, hCol, below(sv.y, hh, aa));

    // ---- the tree line: the spectrum, band by band ----------------------------------
    float tx = sv.x + (local + lean.x * 8.0) / TW_D_TREES;
    float band = aband(fract(tx * 0.05) * 0.85 + 0.04);
    float canopy = TW_HORIZON - 0.03 + 0.035 * fbm3(vec3(tx * 9.0, gSeed, 2.0), 3) + 0.04 * clamp(band, 0.0, 1.2);
    vec3 tCol = mix(mix(vec3(0.12, 0.24, 0.1), pal(gHue + 0.35) * 0.4, 0.3), skyLow, 0.12);
    tCol *= 0.8 + 0.3 * fbm3(vec3(tx * 30.0, sv.y * 40.0, gSeed), 2);
    col = mix(col, tCol, below(sv.y, canopy, aa));

    // ---- the near ground ------------------------------------------------------------
    float groundTop = TW_HORIZON - 0.08;
    if (sv.y < groundTop + aa) {
        // Perspective: a row at screen height y lies at depth 0.6 / (top - y).
        float gz = 0.6 / max(groundTop - sv.y, 0.004);
        float gx = (sv.x + lean.x / gz) * gz + local;
        // Fields 4 m long blurred over one shutter of travel: at depth they
        // read as patches, close up as a streak, never as a strobe.
        float blur = TW_SPEED * TW_SHUTTER + gz * 0.004;
        float cellA = floor(gx / 4.0);
        float fA = fract(gx / 4.0);
        float hA = hash11(cellA + gSeed * 31.0);
        float hB = hash11(cellA + 1.0 + gSeed * 31.0);
        float mixAB = smoothstep(1.0 - blur / 4.0, 1.0, fA);
        float h = mix(hA, hB, mixAB);
        vec3 gCol = mix(vec3(0.3, 0.38, 0.14), vec3(0.55, 0.45, 0.22), h);
        gCol = mix(gCol, pal(gHue + 0.2) * 0.55, 0.25);
        gCol = mix(gCol, hCol, smoothstep(4.0, 30.0, gz) * 0.4);
        // Crop rows run along the line, so they slide without strobing; they
        // fade where they get finer than a pixel.
        float rowsW = fwidth(gz * 1.5);
        float rows = 0.5 + 0.5 * cos(TW_TAU * gz * 1.5) * clamp(1.0 - rowsW * 2.0, 0.0, 1.0);
        gCol *= 0.85 + 0.25 * rows * (1.0 - h * 0.6);
        col = mix(col, gCol, below(sv.y, groundTop, aa));
    }

    // ---- the fence --------------------------------------------------------------------
    {
        float fx = (sv.x + lean.x / TW_D_FENCE) * TW_D_FENCE + local;
        float post = abs(fract(fx / 3.0 + 0.5) - 0.5) * 3.0;
        float smear = TW_SPEED * TW_SHUTTER * 3.0;
        float width = 0.06;
        float cover = clamp((width + smear - post) / max(smear, 1e-3), 0.0, 1.0) * width / (width + smear * 0.5);
        float yTop = TW_HORIZON - 0.12;
        float yBot = TW_HORIZON - 0.3;
        float inBand = below(sv.y, yTop, aa) * (1.0 - below(sv.y, yBot, aa));
        vec3 wood = mix(vec3(0.3, 0.22, 0.15), pal(gHue + 0.1) * 0.4, 0.2);
        col = mix(col, wood, cover * inBand * 0.8);
        float rail = exp(-pow((sv.y - (yTop - 0.03)) / 0.006, 2.0)) + exp(-pow((sv.y - (yTop - 0.1)) / 0.006, 2.0));
        col = mix(col, wood * 0.9, clamp(rail, 0.0, 1.0) * 0.6);
    }

    // ---- the catenary poles and their sagging wires -----------------------------------
    {
        float px = (sv.x + lean.x / TW_D_POLE) * TW_D_POLE + x;
        float span = 8.0;
        float f = fract(px / span);
        float post = abs(f - 0.5) * span;
        float width = 0.07;
        float smear = TW_SPEED * TW_SHUTTER;
        float cover = clamp((width + smear - post) / max(smear, 1e-3), 0.0, 1.0) * width / (width + smear * 0.5);
        vec3 steel = mix(vec3(0.12, 0.13, 0.15), pal(gHue + 0.6) * 0.2, 0.2);
        // The pole stands centred on the span's midpoint (f = 0.5).
        col = mix(col, steel, cover * below(sv.y, 0.62, aa));
        // Two wires sagging between neighbouring poles: the sag is a parabola
        // in the span, so the wires dip and rise as the poles pass.
        float g = fract(px / span + 0.5);
        float sag = 4.0 * g * (1.0 - g);
        for (int i = 0; i < 2; i++) {
            float top = 0.58 - 0.05 * float(i);
            float wy = top - 0.07 * sag;
            float wd = abs(sv.y - wy);
            float wa = max(fwidth(sv.y - wy), aa);
            col = mix(col, steel * 0.8, (1.0 - smoothstep(0.0015, 0.0015 + wa, wd)) * 0.8);
        }
    }

    // ---- the tunnel ------------------------------------------------------------------
    {
        float tunX = (sv.x + lean.x / TW_D_TUNNEL) * TW_D_TUNNEL + x;
        float tl = 30.0 + 14.0 * hash11(floor(tunX / TW_LEG + 0.5) + 0.7);
        float rel = tunX - floor(tunX / TW_LEG + 0.5) * TW_LEG;
        float inside = 1.0 - smoothstep(0.5 * tl - 0.05, 0.5 * tl + 0.05, abs(rel));
        if (inside > 0.0) {
            // Soot-dark masonry lit by lamps every 9 m, never near black.
            vec3 wall = mix(vec3(0.16, 0.14, 0.13), pal(gHue + 0.08) * 0.2, 0.3);
            float course = abs(fract(sv.y * 9.0) - 0.5);
            wall *= 0.85 + 0.15 * smoothstep(0.42, 0.48, course);
            float lamp = abs(fract(tunX / 9.0) - 0.5) * 9.0;
            float glow = exp(-lamp * lamp * 0.8) * exp(-pow((sv.y - 0.45) * 3.0, 2.0));
            wall += mix(vec3(1.0, 0.75, 0.45), pal(gHue + 0.05), 0.2) * glow * 0.45;
            // The portal's face is a little lighter, so the edge reads as stone.
            float lip = 1.0 - smoothstep(0.0, 0.8, 0.5 * tl - abs(rel));
            wall = mix(wall, wall * 1.6, lip * 0.5);
            col = mix(col, wall, inside);
        }
    }

    // ---- the carriage --------------------------------------------------------------------
    // Interior panelling around the window, softly lit from above, with a
    // bevelled frame catching the light. Its level sits near the scene's mean.
    vec3 panel = mix(vec3(0.42, 0.3, 0.2), pal(gHue + 0.08) * 0.5, 0.25);
    panel *= 0.75 + 0.35 * smoothstep(-1.0, 1.0, uv.y);
    float grain = fbm3(vec3(uv * vec2(3.0, 40.0), 1.0), 3);
    panel *= 0.85 + 0.2 * grain;
    float frameW = 0.035;
    float bevel = smoothstep(frameW, 0.0, abs(win - 0.5 * frameW));
    vec3 frameCol = mix(vec3(0.6, 0.6, 0.62), pal(gHue + 0.6) * 0.6, 0.2) * (0.7 + 0.5 * bevel);
    // A faint reflection on the glass: the carriage's own lamp.
    col += vec3(0.04, 0.035, 0.03) * smoothstep(0.8, -0.2, uv.y) * smoothstep(-winHalf.x, winHalf.x, -uv.x);
    float inWin = 1.0 - smoothstep(-aa, aa, win);
    float onFrame = (1.0 - smoothstep(frameW - aa, frameW + aa, win)) * (1.0 - inWin);
    col = mix(panel, col, inWin);
    col = mix(col, frameCol, onFrame);
    // Inner shadow along the frame edge, so the glass sits behind it.
    col *= 1.0 - 0.25 * inWin * exp(win / 0.03);

    col *= mix(1.0, 0.8, smoothstep(0.8, 2.2, length(uv)));
    col = tonemap(col * 1.1);
    col += (uhashUnit(uhash3(uvec3(gl_FragCoord.xy, 19u))) - 0.5) / 255.0;
    fragColor = vec4(grade(col), 1.0);
}
