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

// Penrose Climb: an endless isometric staircase that only ever goes up.
//
// THE MOVE is an orthographic, isometric camera trucking diagonally upward
// with a little lantern that hops up the steps, one step at a time, forever.
// An orthographic view has no perspective to give away where it is, so the
// world can repeat under the camera and the climb never arrives anywhere.
//
// THE WORLD is a field of square towers, one per grid cell, running down into
// the fog. A tower's height climbs by one rise per cell in x and in z, so the
// whole field is a staircase rising along the diagonal - and shifting it one
// cell along both axes and one double rise up maps it onto itself. That
// shift, the period, has a vertical part: the camera rides it upward and is
// wrapped back by eight periods without a seam. Lanes across the diagonal
// give the field its architecture: a zig-zag stair two cells wide, a wall, a
// gap down into the abyss, a lane of terraced landings.
//
// HOW. No march. An orthographic ray walks the grid cell by cell (a DDA) and
// meets each tower exactly: its side if the ray enters the cell below the
// top, its top if the ray drops below the top inside the cell. Faces are lit
// the iso way, top bright and the two side faces in two steps of shade,
// with ambient occlusion from the taller neighbours and a fog that deepens
// with how far below the lantern a surface lies.
//
// THE MUSIC. The hops are integrated loudness (uFlowPhase) over a slow walk:
// each hop is an eased arc followed by a rest, so it reads as stepping, at
// about one step a second. Bass lifts the hop; the step edges are lit by
// spectrum band, running up the stairs; a new form (uFormPhase) turns the
// view a quarter turn about the vertical - an orbit, in orthographic.
//
// NO-FLASH. Step edges are thin and move at the walk's pace: under 1.2 per
// second past any screen region.

#define PC_RISE 0.4
#define PC_DDA 72
#define PC_LANES 5.0
/** Hops before the world repeats under the camera: eight periods. */
#define PC_WRAP 16.0

float pcHeight(vec2 c) {
    float k = c.x - c.y;
    float s = c.x + c.y;
    float lane = mod(k, PC_LANES);
    if (lane < 1.5) return s * PC_RISE;
    if (lane < 2.5) return s * PC_RISE + 3.0 * PC_RISE;
    if (lane < 3.5) return -1e4;
    return floor(s / 4.0) * 4.0 * PC_RISE + 0.5 * PC_RISE;
}

/** The cell the lantern stands on after hop n: the zig-zag of the two stair lanes. */
vec2 pcHopCell(float n) {
    float half_ = floor(n * 0.5);
    return mod(n, 2.0) < 0.5 ? vec2(half_, half_) : vec2(half_ + 1.0, half_);
}

/** The view's heading for turn m: side-on, up the climb, the other side, up the climb. */
float pcYaw(float m) {
    float i = mod(m, 4.0);
    if (i < 0.5) return -0.7853982;
    if (i < 1.5 || i > 2.5) return 0.7853982;
    return 2.3561945;
}

vec3 pcTone(vec3 c) {
    return 1.0 - exp(-max(c, 0.0) * 1.4);
}

void main() {
    vec2 uv = view();
    float hue = 0.08 + 0.15 * sin(uTime * 0.013);
    vec3 glowCol = mix(vec3(1.0, 0.82, 0.55), pal(hue + 0.05), 0.3);
    float bassA = min(uBassSmooth, 1.3);

    // The walk, wrapped every eight periods of the world.
    float walk = mod(mod(uTime, 1000.0) * 0.45 + mod(uFlowPhase, 1000.0) * 1.6, PC_WRAP);
    float n = floor(walk);
    float fh = smoothstep(0.0, 0.6, walk - n);
    vec2 c0 = pcHopCell(n);
    vec2 c1 = pcHopCell(n + 1.0);
    float h0 = pcHeight(c0);
    float h1 = pcHeight(c1);
    vec3 lantern = vec3(mix(c0, c1, fh) + 0.5, mix(h0, h1, fh)).xzy;
    lantern.y += 0.2 + (0.35 + 0.15 * bassA) * sin(3.14159 * fh);

    // The camera glides with the climb rather than hopping with the lantern.
    vec3 target = vec3(walk * 0.5 + 0.75, walk * PC_RISE + 0.4, walk * 0.5 + 0.25);
    target.y += 0.04 * bassA;

    // The view: isometric elevation, a quarter turn per new form, eased between.
    // The turns swing side-on, up the climb, the other side, up the climb: never
    // down it, where only the treads would show and the climb would read as a floor.
    float q = uFormPhase * 4.0;
    float yaw = mix(pcYaw(floor(q)), pcYaw(floor(q) + 1.0), smoothstep(0.35, 0.65, fract(q)));
    yaw += touchAnchor().x * 0.5;
    float elev = 0.6154797 + touchAnchor().y * 0.2;
    vec3 fw = vec3(cos(elev) * sin(yaw), -sin(elev), cos(elev) * cos(yaw));
    vec3 rt = normalize(cross(vec3(0.0, 1.0, 0.0), fw));
    vec3 upv = cross(fw, rt);
    fw = uCamRot * fw;
    rt = uCamRot * rt;
    upv = uCamRot * upv;
    float scale = 4.0 * (1.0 - 0.4 * uCamPush) / xm(uXScale);
    vec3 ro = target + (rt * uv.x + upv * uv.y) * scale - fw * 24.0;
    vec3 rd = fw;

    vec3 abyss = mix(vec3(0.06, 0.07, 0.1), pal(hue + 0.55) * 0.18, 0.4);
    vec3 col = abyss * (0.8 + 0.4 * uv.y * 0.3);

    // The lantern: an orthographic ray against a sphere.
    float tLantern = 1e9;
    vec3 lo = ro - lantern;
    float lb = dot(lo, rd);
    float lc = dot(lo, lo) - 0.075;
    if (lb * lb - lc > 0.0) tLantern = -lb - sqrt(lb * lb - lc);

    // The DDA through the towers.
    float tHit = 1e9;
    vec3 nHit = vec3(0.0);
    vec2 cellHit = vec2(0.0);
    if (rd.y < -0.05) {
        vec2 cell = floor(ro.xz);
        vec2 stepDir = sign(rd.xz);
        vec2 inv = 1.0 / max(abs(rd.xz), vec2(1e-5));
        vec2 tMax = ((cell + max(stepDir, 0.0)) - ro.xz) * stepDir * inv;
        vec2 tDelta = inv;
        float tEnter = 0.0;
        vec3 nEnter = vec3(0.0, 1.0, 0.0);
        for (int i = 0; i < PC_DDA; i++) {
            float tExit = min(tMax.x, tMax.y);
            float h = pcHeight(cell);
            float yIn = ro.y + rd.y * tEnter;
            float yOut = ro.y + rd.y * tExit;
            if (h >= yIn && i > 0) {
                tHit = tEnter;
                nHit = nEnter;
                cellHit = cell;
                break;
            }
            if (h >= yOut) {
                tHit = (h - ro.y) / rd.y;
                nHit = vec3(0.0, 1.0, 0.0);
                cellHit = cell;
                break;
            }
            if (tMax.x < tMax.y) {
                tEnter = tMax.x;
                tMax.x += tDelta.x;
                cell.x += stepDir.x;
                nEnter = vec3(-stepDir.x, 0.0, 0.0);
            } else {
                tEnter = tMax.y;
                tMax.y += tDelta.y;
                cell.y += stepDir.y;
                nEnter = vec3(0.0, 0.0, -stepDir.y);
            }
            if (ro.y + rd.y * tEnter < target.y - 14.0) break;
        }
    }

    if (tHit < 1e8 && tHit < tLantern) {
        vec3 p = ro + rd * tHit;
        float h = pcHeight(cellHit);
        float k = cellHit.x - cellHit.y;
        float lane = mod(k, PC_LANES);
        float s = cellHit.x + cellHit.y;
        vec3 albedo = mix(vec3(0.78, 0.74, 0.68), pal(hue + lane * 0.12), 0.3);
        if (lane > 1.5 && lane < 2.5) albedo = mix(vec3(0.55, 0.5, 0.52), pal(hue + 0.6), 0.3);
        albedo *= 0.92 + 0.08 * hash11(mod(s, 16.0) + k * 7.0);

        vec3 key = normalize(vec3(-0.45, 0.8, -0.3));
        float diff = 0.35 + 0.65 * max(dot(nHit, key), 0.0);
        if (nHit.y > 0.5) diff = 1.0;
        vec2 f = p.xz - cellHit;
        float ao = 1.0;
        if (nHit.y > 0.5) {
            // Taller neighbours shade the top near their edge.
            float hx = pcHeight(cellHit + vec2(1.0, 0.0));
            float hz = pcHeight(cellHit + vec2(0.0, 1.0));
            float hxm = pcHeight(cellHit - vec2(1.0, 0.0));
            float hzm = pcHeight(cellHit - vec2(0.0, 1.0));
            ao *= 1.0 - 0.35 * step(h + 0.01, hx) * smoothstep(0.55, 1.0, f.x);
            ao *= 1.0 - 0.35 * step(h + 0.01, hz) * smoothstep(0.55, 1.0, f.y);
            ao *= 1.0 - 0.35 * step(h + 0.01, hxm) * smoothstep(0.45, 0.0, f.x);
            ao *= 1.0 - 0.35 * step(h + 0.01, hzm) * smoothstep(0.45, 0.0, f.y);
        } else {
            // A side darkens with depth below its top: the shaft's own shadow.
            ao *= mix(0.35, 1.0, exp(-(h - p.y) * 0.5));
        }
        col = albedo * diff * ao * vec3(1.05, 1.0, 0.95);
        col += mix(abyss, vec3(0.4, 0.45, 0.6), 0.5) * 0.12 * (1.0 - nHit.y);

        // The step edges, lit by spectrum band up the stairs.
        if (nHit.y > 0.5) {
            float e = min(min(f.x, 1.0 - f.x), min(f.y, 1.0 - f.y));
            // The orthographic pixel footprint is the same everywhere: no derivatives needed.
            float aa = scale * 2.0 / max(uResolution.y, 1.0);
            float edge = 1.0 - smoothstep(0.025, 0.025 + 2.0 * aa, e);
            float band = aband(fract(mod(s, 12.0) / 12.0 + 0.02));
            col += pal(hue + mod(s, 12.0) / 12.0 * 0.6) * edge * (0.2 + 0.55 * band);
        }
        // The lantern's warm pool of light on what is near it.
        float ld = length(p - lantern);
        col += glowCol * 0.55 * exp(-ld * ld * 0.6) * max(dot(nHit, normalize(lantern - p)), 0.0);
        // Fog: the deeper below the climb, the further into the abyss.
        float depth = max(target.y - p.y - 1.0, 0.0);
        col = mix(col, abyss, 1.0 - exp(-depth * 0.16 * xm(uXFog)));
    } else if (tLantern < 1e8) {
        vec3 p = ro + rd * tLantern;
        vec3 nl = normalize(p - lantern);
        float rim = pow(1.0 - max(dot(nl, -rd), 0.0), 2.0);
        col = glowCol * (1.0 + 0.4 * rim) + vec3(1.0, 0.9, 0.7) * 0.4 * pow(max(dot(nl, normalize(vec3(-0.4, 0.8, -0.3))), 0.0), 20.0);
    }
    // The lantern's halo over everything.
    vec3 lp = lantern - ro;
    vec3 perp = lp - rd * dot(lp, rd);
    col += glowCol * 0.2 * exp(-dot(perp, perp) * 2.5);

    if (!touchIdle()) {
        col += pal(hue + 0.5) * min(touchWake(uv), 2.0) * 0.05;
    }
    col = pcTone(col);
    col *= 0.8 + 0.2 * smoothstep(2.0, 0.4, length(uv));
    fragColor = vec4(grade(col), 1.0);
}
