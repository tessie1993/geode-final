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
//#include lib_camera

// Toy Town: a city seen from a high crane through a tilt-shift lens, so it
// reads as a model on a table.
//
// THE MOVE is a slow high-angle crane and truck that follows a tram down the
// avenue. The lens is long (a narrow field of view from far up) and the focus
// band is a strip across the frame that follows the tram's row on screen, so
// the rack happens along the band as the crane drifts: whatever the tram is
// passing is sharp, the streets above and below fall away into haze.
//
// TILT-SHIFT IN ONE PASS. There is no blur pass to borrow, so out-of-focus
// rows are made to look the way blurred rows look: their fine detail (window
// grids, road markings, the shading normal's sharpness) fades out, colour
// softens toward the haze and lifts a touch, while the in-focus band gets the
// miniature's over-saturated, glossy-plastic grade. A real blur can replace
// this once a blur pyramid exists; nothing else here would change.
//
// THE CITY is boxes by domain repetition. Each cell holds one building of a
// hashed height or a park; every fourth row is an avenue with tram rails.
// The march is bounded by the tallest roof, so it starts at y = 1.3 and is
// short; the cell-boundary clamp (every neighbouring box is at least a
// street's half-width beyond this cell's edge) keeps the estimate a lower
// bound across cells. A sun with a short soft-shadow march, a cool sky fill
// and two-tap AO light it.
//
// THE MUSIC. The tram's travel is integrated loudness (uFlowPhase) over a
// crawl, never a clock times a level; bass bobs the crane; a spike's new
// direction (uMoveDir) cranes the view over to a different block, gliding;
// the traffic lights change on their own slow cycle; treble lights windows.
//
// The city repeats every 800 cells (1000 units) along the avenue, the
// Camera's travel period, so the long truck wraps without a seam. Buildings
// pass a screen region at well under one a second.

#define TT_CELL 1.25
#define TT_CELLS 800.0
#define TT_MAX_STEPS 80
#define TT_TOP 1.3
#define TT_FOCAL 2.4
#define TT_TRAM_Y 0.12

float gTramX;
int gMat;
vec2 gCell;

float ttHash(vec2 c, float k) {
    return hashCell(ivec3(int(mod(c.x, TT_CELLS)), int(c.y), int(k)));
}

bool ttAvenue(float j) {
    return mod(j, 4.0) < 0.5;
}

float ttHeight(vec2 c) {
    if (ttAvenue(c.y)) return 0.0;
    float h = ttHash(c, 1.0);
    if (h < 0.14) return 0.0;
    return (0.25 + 0.95 * h * h) * xm(uXScale);
}

float map(vec3 p) {
    vec2 c = floor(p.xz / TT_CELL);
    vec2 l = p.xz - (c + 0.5) * TT_CELL;
    gCell = c;
    float d = p.y;
    gMat = 0;
    float h = ttHeight(c);
    float edge = 0.5 * TT_CELL - max(abs(l.x), abs(l.y));
    if (h > 0.0) {
        float half_ = 0.4 + 0.06 * ttHash(c, 2.0);
        float b = sdBox(vec3(l.x, p.y - 0.5 * h, l.y), vec3(half_, 0.5 * h, half_ * (0.85 + 0.15 * ttHash(c, 3.0)))) - 0.015;
        // A rooftop plant room on the taller ones.
        if (h > 0.6) b = min(b, sdBox(vec3(l.x - 0.1, p.y - h - 0.06, l.y + 0.08), vec3(0.14, 0.06, 0.12)) - 0.01);
        if (b < d) { d = b; gMat = 1; }
    } else if (!ttAvenue(c.y)) {
        // A park: a round tree.
        float tr = length(vec3(l.x, p.y - 0.32, l.y)) - 0.28;
        if (tr < d) { d = tr; gMat = 2; }
    }
    // Every neighbour's geometry is at least a street's half-width past this cell's edge.
    d = min(d, max(edge, 0.0) + 0.18);
    // The tram, on the avenue at row 0.
    vec3 q = p - vec3(gTramX, TT_TRAM_Y, 0.5 * TT_CELL);
    float tram = sdBox(q, vec3(0.42, 0.1, 0.11)) - 0.025;
    if (tram < d) { d = tram; gMat = 3; }
    return d;
}

vec3 normalAt(vec3 p, float eps) {
    vec2 k = vec2(1.0, -1.0);
    return normalize(k.xyy * map(p + k.xyy * eps) + k.yyx * map(p + k.yyx * eps)
                   + k.yxy * map(p + k.yxy * eps) + k.xxx * map(p + k.xxx * eps));
}

float softShadow(vec3 ro, vec3 rd) {
    float res = 1.0;
    float t = 0.03;
    for (int i = 0; i < 20; i++) {
        vec3 p = ro + rd * t;
        if (p.y > TT_TOP) break;
        float h = map(p);
        res = min(res, 9.0 * h / t);
        if (res < 0.02) break;
        t += clamp(h, 0.03, 0.3);
    }
    return clamp(res, 0.0, 1.0);
}

/** The streets: asphalt, kerbs, crossings, the tram rails on the avenue. */
vec3 ttGround(vec3 p, float detail, float hue) {
    vec2 c = floor(p.xz / TT_CELL);
    vec2 l = p.xz - (c + 0.5) * TT_CELL;
    float a = max(abs(l.x), abs(l.y));
    vec3 asphalt = vec3(0.23, 0.23, 0.25);
    vec3 pavement = vec3(0.62, 0.6, 0.56);
    vec3 col = asphalt;
    if (!ttAvenue(c.y)) {
        float h = ttHeight(c);
        col = mix(asphalt, pavement, smoothstep(0.52, 0.5, a));
        if (h <= 0.0) col = mix(col, vec3(0.2, 0.42, 0.16), smoothstep(0.48, 0.46, a));
    } else {
        // Rails and their sleepers, and the dashed centre line, faded by the tilt-shift.
        float rails = smoothstep(0.012, 0.0, abs(abs(l.y) - 0.07));
        col = mix(col, vec3(0.65, 0.66, 0.7), rails * detail);
    }
    // Zebra crossings at the corners.
    float zebra = step(0.5, fract(p.x * 10.0)) * smoothstep(0.62, 0.6, a) * step(0.5, a);
    col = mix(col, vec3(0.85), zebra * 0.5 * detail * step(abs(l.y), 0.3));
    return col;
}

vec3 ttTone(vec3 c) {
    return 1.0 - exp(-max(c, 0.0) * 1.3);
}

void main() {
    vec2 uv = view();
    float hue = 0.02 + 0.25 * uFormPhase;

    // The tram's travel along the avenue, wrapped by the city's 1000-unit period.
    gTramX = mod(mod(uFlowPhase, 500.0) * 2.0 + mod(uTime, 4000.0) * 0.25, 1000.0);

    // The crane: a little behind the tram, drifting, cranes to a new block on a spike.
    vec3 target = vec3(gTramX + 0.6 + 0.8 * sin(uTime * 0.05), 0.0, 0.5 * TT_CELL)
                + vec3(uMoveDir.x, 0.0, uMoveDir.y) * 1.4;
    float yaw = 0.55 + 0.18 * sin(uTime * 0.031) + touchAnchor().x * 0.6;
    float elev = 0.9 + 0.06 * sin(uTime * 0.023) + touchAnchor().y * 0.3;
    float dist = 9.0;
    vec3 eye = target + dist * vec3(sin(yaw) * cos(elev), sin(elev), -cos(yaw) * cos(elev));
    eye.y += 0.12 * min(uBassSmooth, 1.3);
    vec3 fw = normalize(target - eye);
    vec3 rt = normalize(cross(vec3(0.0, 1.0, 0.0), fw));
    vec3 upv = cross(fw, rt);
    vec3 rel = eye - target;
    vec3 rd = normalize(rt * uv.x + upv * uv.y + fw * TT_FOCAL);
    cameraOrbit(rel, rd);
    vec3 ro = target + rel;

    // The focus band: the tram's row on screen.
    vec3 tramRel = vec3(gTramX, TT_TRAM_Y, 0.5 * TT_CELL) - eye;
    float focusY = dot(tramRel, upv) / max(dot(tramRel, fw), 0.1) * TT_FOCAL;
    float blur = smoothstep(0.1, 0.75, abs(uv.y - focusY) / xm(uXSize));
    float detail = 1.0 - blur;

    // March from the roof plane down to the ground plane.
    float tTop = rd.y < 0.0 ? max((TT_TOP - ro.y) / rd.y, 0.0) : 0.0;
    float tGround = rd.y < 0.0 ? -ro.y / rd.y : 60.0;
    float t = tTop;
    bool hit = false;
    for (int i = 0; i < TT_MAX_STEPS; i++) {
        if (float(i) >= uSteps) break;
        vec3 p = ro + rd * t;
        float d = map(p);
        float eps = 0.0012 * t;
        if (d < eps) { hit = true; break; }
        t += d;
        if (t >= tGround) { t = tGround; hit = true; break; }
    }

    vec3 skyCol = mix(vec3(0.62, 0.72, 0.85), pal(hue + 0.55) * 0.6 + 0.3, 0.2);
    vec3 col = skyCol;
    if (hit) {
        vec3 p = ro + rd * t;
        map(p);
        int mat = gMat;
        vec2 cell = gCell;
        // A softer normal off the focus band.
        vec3 n = normalAt(p, 0.002 + 0.02 * blur);
        vec3 sun = normalize(vec3(0.55, 0.75, 0.38));
        float sh = softShadow(p + n * 0.01, sun);
        float ao = clamp(0.45 + 0.55 * map(p + n * 0.1) / 0.1, 0.0, 1.0)
                 * clamp(0.7 + 0.3 * map(p + n * 0.3) / 0.3, 0.0, 1.0);
        vec3 albedo;
        float gloss = 0.3;
        if (mat == 1) {
            float h = ttHash(cell, 4.0);
            albedo = mix(pal(hue + h * 0.7), vec3(0.92, 0.88, 0.8), 0.45);
            // Window grid on the walls, faded off the focus band.
            vec2 c2 = floor(p.xz / TT_CELL);
            vec2 l = p.xz - (c2 + 0.5) * TT_CELL;
            float wallUV = abs(n.x) > abs(n.z) ? l.y : l.x;
            vec2 wg = fract(vec2(wallUV * 9.0, p.y * 9.0));
            float win = step(0.3, wg.x) * step(0.35, wg.y) * step(0.12, p.y) * (1.0 - abs(n.y));
            float lit = step(0.7, ttHash(floor(vec2(wallUV * 9.0 + c2.x * 13.0, p.y * 9.0)), 5.0));
            albedo = mix(albedo, vec3(0.15, 0.18, 0.24), win * 0.7 * detail);
            albedo += vec3(1.0, 0.8, 0.45) * win * lit * detail * 0.25 * (0.6 + 0.4 * min(uTrebleSmooth, 1.3));
            gloss = 0.5;
        } else if (mat == 2) {
            albedo = vec3(0.18, 0.45, 0.16) * (0.85 + 0.3 * ttHash(cell, 6.0));
            gloss = 0.1;
        } else if (mat == 3) {
            albedo = mix(vec3(0.95, 0.2, 0.15), pal(hue), 0.25);
            vec3 q = p - vec3(gTramX, TT_TRAM_Y, 0.5 * TT_CELL);
            float band = smoothstep(0.02, 0.0, abs(q.y - 0.03)) * (1.0 - abs(n.y));
            albedo = mix(albedo, vec3(0.12, 0.15, 0.2), band);
            gloss = 0.8;
        } else {
            albedo = ttGround(p, detail, hue);
            gloss = 0.05;
        }
        vec3 sunCol = vec3(1.25, 1.1, 0.9);
        vec3 skyFill = skyCol * 0.45;
        float diff = max(dot(n, sun), 0.0) * sh;
        float sky = 0.5 + 0.5 * n.y;
        float bounce = max(dot(n, vec3(-sun.x, 0.0, -sun.z)), 0.0) * 0.15;
        vec3 v = -rd;
        float spec = pow(max(dot(reflect(-sun, n), v), 0.0), 24.0) * sh * gloss;
        float fres = pow(1.0 - max(dot(n, v), 0.0), 5.0) * gloss;
        col = albedo * (sunCol * diff + skyFill * sky * ao + vec3(0.25, 0.22, 0.18) * bounce * ao);
        col += sunCol * spec * 0.5 + skyCol * fres * 0.3 * ao;

        // Traffic lights on the avenue corners: red and green swapping on a slow cycle.
        vec2 lc = floor(p.xz / TT_CELL + 0.5);
        vec2 lp = p.xz - lc * TT_CELL;
        if (ttAvenue(lc.y) || ttAvenue(lc.y - 1.0)) {
            float phase = step(0.5, fract(uTime / 8.0 + ttHash(lc, 7.0)));
            vec3 lamp = mix(vec3(1.0, 0.15, 0.1), vec3(0.2, 1.0, 0.35), phase);
            col += lamp * smoothstep(0.05, 0.0, length(lp)) * 0.6 * detail;
        }
    }
    // Haze: depth, and the tilt-shift's out-of-focus wash.
    float haze = 1.0 - exp(-max(t - 6.0, 0.0) * 0.04 * xm(uXFog));
    col = mix(col, skyCol, haze);
    vec3 soft = mix(col, skyCol * 0.8, 0.38);
    col = mix(col, soft, blur);
    float g = dot(col, vec3(0.299, 0.587, 0.114));
    col = mix(vec3(g), col, 1.0 + 0.3 * detail - 0.15 * blur);

    if (!touchIdle()) {
        col += pal(hue + 0.5) * min(touchWake(uv), 2.0) * 0.05;
    }
    col = ttTone(col);
    col *= 0.8 + 0.2 * smoothstep(2.0, 0.4, length(uv));
    fragColor = vec4(grade(col), 1.0);
}
