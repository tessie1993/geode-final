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

// Dew Macro: a macro lens over dew drops on a leaf. The camera hangs a few
// centimetres over the leaf and makes a slow dolly-zoom on the phrase: it
// moves toward the point it is looking at while the lens widens in step, so
// the drops at the point of focus keep their size while the leaf behind them
// stretches away and the drops in front swell past the lens - the vertigo
// move. Meanwhile the look point glides along the leaf, so drops drift
// through the plane of focus and out again.
//
// ---- no march ------------------------------------------------------------------
//
// Everything is closed form. The drops are spheres sitting on the plane, one
// to a cell, each held well inside its cell, so a ray only has to test the
// cells its path crosses inside the thin slab the drops live in: a 2D grid
// walk of a few cells, in front-to-back order, so the first drop found is the
// nearest. A drop refracts: the ray bends in, crosses the drop, and comes out
// onto the leaf beneath (magnified veins) or up into the garden behind (a tiny
// inverted bokeh), mixed with the reflection by Fresnel.
//
// ---- depth of field, as edge width -------------------------------------------------
//
// There is no blur pass in a single-pass Style, so blur is drawn rather than
// filtered: every feature is drawn with an edge as wide as the circle of
// confusion at its depth (|z - focus| / z, the thin-lens law). A drop in focus
// has a hard silhouette; a drop in front of focus becomes a soft disc; a vein
// far behind it spreads into a faint band carrying the same light. Each
// drop sits a margin away from its cell's edge so its widest soft edge never
// crosses into a cell the walk did not test.
//
// ---- audio --------------------------------------------------------------------------
//
// uSwell sets how deep the dolly-zoom goes and uFlowPhase (energy, integrated)
// how fast the look point glides along the leaf. uBassSmooth makes the drops'
// skins shiver; a beat sends a ripple down every drop from crown to foot
// (uBeatPhase, gated by the beat envelope). uTrebleSmooth sharpens the sun
// glints, uMidSmooth backlights the veins, and a spike turns the garden
// bokeh's colours (uFormPhase, which glides). No transient touches the frame's
// overall brightness.

#define DM_TAU 6.2831853
#define DM_CELL 1.0
/** Clearance between a drop and its cell's edge: the widest soft edge a drop may draw. */
#define DM_MARGIN 0.18
/** The top of the slab the drops live in: the tallest drop plus its soft edge. */
#define DM_SLAB 0.62
#define DM_MAX_CELLS 10
#define DM_IOR 1.33
#define DM_DIST 3.2
#define DM_FOCAL 1.7

vec3 gSun;
float gAperture;
float gFocus;
float gFocal;
float gWobble;
float gRipple;
float gTreb;
float gMid;
float gPixel;

/** The drop a cell holds: xyz its centre, w its radius, or all zero for an empty cell. */
vec4 dropOf(vec2 cell) {
    ivec2 c = ivec2(cell);
    if (hashCell(ivec3(c, 11)) < 0.36) return vec4(0.0);
    float h1 = hashCell(ivec3(c, 23));
    float r = mix(0.035, 0.30, h1 * h1 * h1);
    float room = DM_CELL - 2.0 * (r + DM_MARGIN);
    vec2 xz = cell * DM_CELL + vec2(r + DM_MARGIN) + room * vec2(hashCell(ivec3(c, 37)), hashCell(ivec3(c, 41)));
    // Beaded up on a waxy leaf: the centre stands above the surface.
    return vec4(xz.x, 0.45 * r, xz.y, r);
}

/** World-space blur radius at distance z from the lens. */
float blurAt(float z) {
    float coc = gAperture * abs(z - gFocus) / max(z, 0.05);
    return coc * z / gFocal;
}

/** A line of half-width w at distance d, spread by blur b without gaining or losing light. */
float softLine(float d, float w, float b) {
    return (w / (w + b)) * (1.0 - smoothstep(0.0, w + b, d));
}

vec3 sky(vec3 d) {
    vec3 haze = mix(pal(0.84), vec3(1.0), 0.45) * 0.5;
    vec3 high = mix(pal(0.52), vec3(0.55), 0.35) * 0.22;
    vec3 col = mix(haze, high, smoothstep(-0.05, 0.6, d.y));
    float s = max(dot(d, gSun), 0.0);
    col += mix(pal(0.85), vec3(1.0), 0.5) * (0.25 * pow(s, 5.0) + 0.45 * pow(s, 48.0));
    return col;
}

// The garden behind the leaf, always far out of focus: soft discs with a
// brighter rim, which is how a real lens draws a point of light it is not
// focused on. Two scales of disc so the background has depth of its own.
vec3 bokeh(vec3 d) {
    vec3 col = vec3(0.0);
    float band = smoothstep(1.1, 0.05, abs(d.y - 0.1));
    for (int layer = 0; layer < 2; layer++) {
        float fl = float(layer);
        vec2 q = vec2(atan(d.x, d.z) * (2.4 + 1.6 * fl), d.y * (4.0 + 2.5 * fl));
        q.x += uTime * (0.012 + 0.008 * fl) + fl * 3.7;
        vec2 cell = floor(q);
        vec2 f = fract(q) - 0.5;
        ivec2 ic = ivec2(cell);
        float seed = hashCell(ivec3(ic, 3 + layer));
        if (seed < 0.3) continue;
        vec2 jitter = 0.14 * (vec2(hashCell(ivec3(ic, 5)), hashCell(ivec3(ic, 7))) - 0.5);
        float r = mix(0.18, 0.32, hashCell(ivec3(ic, 9)));
        float dist = length(f - jitter);
        float disc = smoothstep(r, r - 0.05, dist) * (1.0 + 0.7 * smoothstep(r - 0.1, r - 0.02, dist));
        vec3 tint = pal(0.78 + 0.3 * seed + 0.2 * uFormPhase);
        col += mix(tint, vec3(1.0), 0.25) * disc * (0.26 - 0.08 * fl) * (seed - 0.15);
    }
    return col * band;
}

vec3 garden(vec3 d) {
    return sky(d) + bokeh(d);
}

// The leaf at x, drawn as the lens sees it at blur b: the midrib, the lateral
// veins, the fine cells between them, and the shadow and focused caustic of
// the drop standing in this cell.
vec3 leafAt(vec2 x, float b, vec3 rd) {
    float midrib = abs(x.x - 0.25 * sin(x.y * 0.21));
    // Lateral veins leave the midrib at a slant and bow as they go; a finer,
    // fainter net of veinlets fills between them.
    float lateral = abs(fract((x.y + 0.85 * midrib + 0.18 * sin(midrib * 1.7 + x.y * 0.4)) * 1.4) - 0.5) / 1.9;
    float net = abs(sin(x.x * 9.0 + 2.0 * sin(x.y * 5.0))) * abs(sin(x.y * 8.0 + 2.0 * sin(x.x * 6.0)));
    float veins = softLine(midrib, 0.05, b) + 0.55 * softLine(lateral, 0.011, b)
                + 0.1 * softLine(net, 0.05, b * 6.0);
    float cells = sin(x.x * 23.0 + sin(x.y * 9.0)) * sin(x.y * 21.0 + sin(x.x * 7.0));
    cells *= 1.0 / (1.0 + b * 40.0);
    float blotch = motionNoise(x * 0.35);
    vec3 albedo = mix(pal(0.67), pal(0.78), 0.25 + 0.5 * blotch + 0.12 * cells) * (0.26 + 0.05 * cells);
    // Low sun behind the leaf: it glows where it faces the light.
    vec3 col = albedo * (0.8 + 0.35 * blotch);
    // Backlit: the veins carry light through the leaf.
    col += mix(pal(0.75), vec3(1.0), 0.35) * veins * (0.16 + 0.22 * gMid);
    // The drop standing here throws its shadow toward the lens and focuses the
    // sun into a bright point inside it.
    vec2 cell = floor(x / DM_CELL);
    vec4 drop = dropOf(cell);
    if (drop.w > 0.0) {
        vec2 away = normalize(gSun.xz + vec2(1e-4));
        float r = drop.w;
        float sd = length(x - (drop.xz - away * 0.8 * r));
        col *= 1.0 - 0.45 * smoothstep(r * 1.05 + b, r * 0.7 - b, sd);
        float cd = length(x - (drop.xz - away * 0.45 * r));
        float spot = (0.06 * r * r) / (0.06 * r * r + b * b);
        col += mix(pal(0.85), vec3(1.0), 0.5) * 0.5 * spot * exp(-cd * cd / (0.06 * r * r + b * b));
    }
    // A waxy sheen toward the sun.
    col += vec3(0.08) * pow(max(dot(reflect(rd, vec3(0.0, 1.0, 0.0)), gSun), 0.0), 24.0);
    return col;
}

/** The leaf the ray reaches from p heading along d (d.y < 0), or the garden if it heads up. */
vec3 beyond(vec3 p, vec3 d, float b) {
    if (d.y >= -1e-3) return garden(d);
    vec3 x = p + d * (-p.y / d.y);
    return leafAt(x.xz, b, d);
}

vec3 dropColor(vec3 ro, vec3 rd, vec4 drop, float tc, float miss, float b) {
    vec3 c = drop.xyz;
    float r = drop.w;
    // Where the ray meets the drop or, in the soft edge, the rim point nearest it.
    vec3 p;
    if (miss < r) {
        p = ro + rd * (tc - sqrt(r * r - miss * miss));
    } else {
        p = c + normalize(ro + rd * tc - c) * r;
    }
    vec3 n = (p - c) / r;
    // The skin: a shiver on the bass, and the beat's ring running crown to foot.
    float fall = 0.5 - 0.5 * n.y;
    float ring = gRipple * exp(-(fall - uBeatPhase) * (fall - uBeatPhase) * 90.0);
    vec3 shiver = vec3(sin(n.y * 11.0 + uTime * 7.0), 0.0, cos(n.x * 11.0 - uTime * 6.0));
    n = normalize(n + shiver * (0.05 * gWobble + 0.14 * ring));

    float cosi = clamp(dot(-rd, n), 0.0, 1.0);
    float fresnel = 0.02 + 0.98 * pow(1.0 - cosi, 5.0);
    vec3 refl = garden(reflect(rd, n));
    // In through the skin, across the drop, and out.
    vec3 inside = refract(rd, n, 1.0 / DM_IOR);
    float chord = -2.0 * dot(inside, p - c);
    float toLeaf = inside.y < 0.0 ? -p.y / inside.y : 1e9;
    vec3 through;
    if (toLeaf < chord) {
        // Straight down onto the leaf it sits on: the veins, magnified.
        through = leafAt((p + inside * toLeaf).xz, b * 0.5, inside);
    } else {
        vec3 q = p + inside * chord;
        vec3 n2 = (q - c) / r;
        vec3 out_ = refract(inside, -n2, DM_IOR);
        if (dot(out_, out_) < 1e-4) out_ = reflect(inside, -n2);
        through = beyond(q, out_, b);
    }
    through *= mix(vec3(1.0), pal(0.5), 0.12);
    // The bright crescent a drop shows on the side away from the sun: light
    // that entered on the sunward side, focused through the drop toward the lens.
    float crescent = pow(max(dot(n, normalize(vec3(-gSun.x, -0.6, -gSun.z))), 0.0), 3.0);
    through += mix(pal(0.85), vec3(1.0), 0.5) * crescent * 0.55;
    fresnel = max(fresnel, 0.1);
    float glint = pow(max(dot(reflect(rd, n), gSun), 0.0), 140.0) * (0.6 + 0.9 * gTreb);
    return mix(through, refl, fresnel) + mix(pal(0.85), vec3(1.0), 0.6) * glint;
}

// A finger steers the look toward where it is held; on release the look
// glides home as the touch fades. Untouched, the ray comes back as it went in.
vec3 steerLook(vec3 rd) {
    if (touchIdle()) return rd;
    vec2 a = touchAnchor() * touchStrength();
    return rotY(a.x * 0.7) * (rotX(-a.y * 0.45) * rd);
}

void main() {
    vec2 uv = view();
    float swell = clamp(uSwell, 0.0, 1.5);
    gWobble = clamp(uBassSmooth, 0.0, 1.5);
    gRipple = clamp(uBeat, 0.0, 1.0) * clamp(uBeatResponse, 0.0, 2.0);
    gTreb = clamp(uTrebleSmooth, 0.0, 1.5);
    gMid = clamp(uMidSmooth, 0.0, 1.5);
    gSun = normalize(vec3(0.35, 0.32, 1.0));

    // ---- the dolly-zoom -------------------------------------------------------
    //
    // Distance and focal length move together, so the look point keeps its
    // size while everything nearer and farther changes perspective. One cycle
    // is a phrase; louder passages ride through it a little faster.
    float phrase = uTime * (DM_TAU / 28.0) + uFlowPhase * 0.9;
    float dolly = (0.2 + 0.16 * swell) * sin(phrase);
    float dist = DM_DIST * (1.0 + dolly);
    gFocal = DM_FOCAL * (1.0 + dolly);

    // ---- the rig ----------------------------------------------------------------
    vec3 look = vec3(0.35 * sin(uTime * 0.031), 0.12, uTime * 0.05 + uFlowPhase * 0.7 + uCamTravel);
    float yaw = 0.22 * sin(uTime * 0.043);
    float pitch = 0.27 + 0.05 * sin(uTime * 0.037);
    vec3 fwd = vec3(sin(yaw) * cos(pitch), -sin(pitch), cos(yaw) * cos(pitch));
    vec3 right = normalize(cross(vec3(0.0, 1.0, 0.0), fwd));
    vec3 up = cross(fwd, right);
    vec3 rd = mat3(right, up, fwd) * steerLook(normalize(vec3(uv, gFocal)));
    // The Camera orbits and pushes about the look point.
    vec3 rel = -fwd * dist;
    cameraOrbit(rel, rd);
    vec3 ro = look + rel;
    ro.y = max(ro.y, DM_SLAB + 0.05);
    gFocus = length(ro - look);
    gAperture = 0.075;
    gPixel = 1.5 / (uResolution.y * gFocal);

    vec3 col;
    if (rd.y >= -1e-3) {
        col = garden(rd);
    } else {
        float tLeaf = -ro.y / rd.y;
        float tTop = (DM_SLAB - ro.y) / rd.y;
        vec3 leaf = leafAt((ro + rd * tLeaf).xz, blurAt(tLeaf), rd);
        // Far leaf dissolves into the haze of the garden.
        leaf = mix(leaf, sky(vec3(rd.x, 0.02, rd.z)), 1.0 - exp(-tLeaf * 0.045));

        // ---- the grid walk through the drops' slab ------------------------------
        vec2 a = (ro + rd * tTop).xz;
        vec2 span = (ro + rd * tLeaf).xz - a;
        vec2 cell = floor(a / DM_CELL);
        vec2 dirSign = vec2(span.x >= 0.0 ? 1.0 : -1.0, span.y >= 0.0 ? 1.0 : -1.0);
        vec2 inv = 1.0 / max(abs(span), vec2(1e-6));
        vec2 tMax = ((cell + max(dirSign, 0.0)) * DM_CELL - a) * dirSign * inv;
        vec2 tDelta = DM_CELL * inv;
        float cover = 0.0;
        vec3 dropCol = vec3(0.0);
        for (int i = 0; i < DM_MAX_CELLS; i++) {
            vec4 drop = dropOf(cell);
            if (drop.w > 0.0) {
                vec3 oc = ro - drop.xyz;
                float tc = -dot(oc, rd);
                float miss = sqrt(max(dot(oc, oc) - tc * tc, 0.0));
                float b = min(blurAt(tc), DM_MARGIN);
                float edge = max(b, tc * gPixel);
                if (tc > 0.0 && miss < drop.w + edge) {
                    cover = smoothstep(drop.w + edge, drop.w - edge, miss);
                    dropCol = dropColor(ro, rd, drop, tc, miss, b);
                    break;
                }
            }
            if (min(tMax.x, tMax.y) > 1.0) break;
            if (tMax.x < tMax.y) {
                tMax.x += tDelta.x;
                cell.x += dirSign.x;
            } else {
                tMax.y += tDelta.y;
                cell.y += dirSign.y;
            }
        }
        col = mix(leaf, dropCol, cover);
    }

    if (!touchIdle()) {
        col += pal(0.8) * min(touchWake(uv), 3.0) * 0.035;
    }
    col *= 0.74 + 0.26 * smoothstep(2.2, 0.4, length(uv));
    fragColor = vec4(grade(col), 1.0);
}
