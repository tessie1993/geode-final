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

// Vertigo Well: looking straight down a spiral stairwell while it telescopes.
//
// THE MOVE is the dolly zoom. The landing floor at the bottom of the well is
// the subject, and the camera keeps (distance to it) * tan(FOV / 2) constant:
// as it pulls back up the shaft the lens narrows by the same factor, so the
// floor holds its size on screen while the stair helix between them
// compresses into a tight telephoto stack, and as it drops back the lens opens
// and the walls stretch away toward the edges of the frame. The two numbers
// are one: the focal length is proportional to the distance, so they cannot
// drift apart.
//
// The stretch rides the slowest signals there are. A long breath on uTime
// keeps it moving in silence, uSwell (a passage getting loud) deepens it, and
// bass adds a small push that moves the camera WITHOUT the lens, which is the
// one place the floor is allowed to grow. Nothing here oscillates the lens
// fast: the FOV is a function of uTime at a 26 s period and of uSwell, which
// takes about a second to move.
//
// THE WELL is a cylinder down +z with the floor at the origin. The steps are a
// polar repeat whose depth slides with the angle, so they climb as a helix; the
// banister is the same helix as a tube, and it glows. A landing every turn
// carries a light strip; landing k is lit by spectrum band k, so the music
// climbs the shaft. The floor is a mosaic of rings, lit from beneath: it is
// the key light for the whole well, which is why the stairs read warm at the
// bottom and cool where they rise toward the camera.
//
// NO-FLASH. Nothing passes the camera: the dolly moves along the axis at a
// breath's pace and every repeated feature stays where it is on screen, only
// spreading or gathering. The spectrum lights are thin strips scaled well
// under the floor's own brightness.

#define VW_TAU 6.2831853
/** Compile-time ceiling on the march; uSteps is the runtime budget. */
#define VW_MAX_STEPS 96
/** Wall radius, rise per turn, steps per turn. */
#define VW_RW 2.2
#define VW_RISE 2.6
#define VW_STEPS 18.0
/** Radial depth and half-thickness of a step. */
#define VW_STEP_DEPTH 0.95
#define VW_STEP_T 0.055
/** Banister: radius from the axis, height above the treads, tube radius. */
#define VW_RAIL_R 1.28
#define VW_RAIL_H 0.42
#define VW_RAIL_T 0.03
/** A landing every turn. */
#define VW_LANDING VW_RISE
/** The subject distance and focal length at rest: the dolly-zoom pair. */
#define VW_D0 5.6
#define VW_F0 0.86
/** Largest stretch; keeps the widest lens under ~100 degrees and the narrowest sane. */
#define VW_STRETCH_MAX 2.6

int gMat;
float gRail;

/** One step of the helix in sector i: a box whose depth is set by the sector's angle. */
float vwStep(vec3 p, float r, float a, float i) {
    float sector = VW_TAU / VW_STEPS;
    float zc = (i + 0.5) / VW_STEPS * VW_RISE;
    float zl = mod(p.z - zc, VW_RISE) - 0.5 * VW_RISE;
    float al = a - (i + 0.5) * sector;
    float midR = VW_RW - 0.5 * VW_STEP_DEPTH;
    vec3 q = vec3(r - midR, al * r, zl);
    return sdBox(q, vec3(0.5 * VW_STEP_DEPTH, 0.5 * sector * midR * 0.96, VW_STEP_T)) - 0.012;
}

float map(vec3 p) {
    float r = length(p.xy);
    float a = atan(p.y, p.x);
    float sector = VW_TAU / VW_STEPS;
    float i = floor(a / sector);
    float al = a / sector - i - 0.5;
    // The neighbour on the near side as well: the steps are staggered in depth,
    // so the closest tread is not always the one in this sector.
    float steps = min(vwStep(p, r, a, i), vwStep(p, r, a, i + sign(al)));
    steps = max(steps, p.z + 0.25);

    // The banister: the helix itself as a tube, riding above the treads' inner
    // edge. The angle's wrap shifts the helical depth by exactly one rise, which
    // the mod absorbs, so the tube has no seam.
    float zr = mod(p.z - VW_RISE * (a / VW_TAU) - 0.5 * VW_RISE / VW_STEPS + VW_RAIL_H, VW_RISE) - 0.5 * VW_RISE;
    float rail = length(vec2(r - VW_RAIL_R, zr)) - VW_RAIL_T * xm(uXThickness);
    rail = max(rail, p.z + 0.25);

    // Landings: an annulus every turn, above the floor only.
    float zk = p.z - VW_LANDING * round(p.z / VW_LANDING);
    float land = max(abs(zk) - 0.045, max(VW_RW - VW_STEP_DEPTH - 0.12 - r, r - VW_RW));
    land = max(land, p.z + 1.0);

    float wall = VW_RW - r;
    float floorD = -p.z;

    float d = wall;
    gMat = 0;
    if (steps < d) { d = steps; gMat = 1; }
    if (land < d) { d = land; gMat = 4; }
    if (floorD < d) { d = floorD; gMat = 3; }
    if (rail < d) { d = rail; gMat = 2; }
    gRail = rail;
    return d;
}

vec3 normalAt(vec3 p, float eps) {
    vec2 k = vec2(1.0, -1.0);
    return normalize(k.xyy * map(p + k.xyy * eps) + k.yyx * map(p + k.yyx * eps)
                   + k.yxy * map(p + k.yxy * eps) + k.xxx * map(p + k.xxx * eps));
}

/** The floor's mosaic: rings of palette tiles, the spectrum as a slow bloom through them. */
vec3 vwFloor(vec3 p, float hue) {
    float r = length(p.xy) / VW_RW;
    float a = atan(p.y, p.x);
    float ring = floor(r * 9.0);
    float tiles = 6.0 + ring * 6.0;
    float cell = floor((a / VW_TAU + 0.5) * tiles);
    vec2 f = vec2(fract(r * 9.0), fract((a / VW_TAU + 0.5) * tiles));
    float grout = smoothstep(0.0, 0.08, f.x) * smoothstep(1.0, 0.92, f.x)
                * smoothstep(0.0, 0.06, f.y) * smoothstep(1.0, 0.94, f.y);
    float h = hash11(ring * 17.0 + cell);
    float band = aband(ring / 9.0);
    vec3 tile = pal(hue + ring * 0.07 + h * 0.08) * (0.55 + 0.3 * h);
    tile *= 0.75 + 0.5 * band;
    // The centre stone, the brightest thing in the well and the thing the dolly zoom holds.
    tile += pal(hue + 0.5) * 0.6 * smoothstep(0.16, 0.05, r);
    return tile * mix(0.18, 1.0, grout);
}

vec3 vwTone(vec3 c) {
    return 1.0 - exp(-max(c, 0.0) * 1.35);
}

void main() {
    vec2 uv = cameraView();
    float swell = clamp(uSwell, 0.0, 1.0);
    float bassA = min(uBassSmooth, 1.3);
    float hue = 0.06 + 0.1 * uFormPhase;

    // The dolly-zoom pair. stretch >= 1 is how far up the shaft the camera has
    // pulled; the lens narrows by exactly that factor.
    float breath = 0.5 - 0.5 * cos(uTime * VW_TAU / 26.0);
    float stretch = clamp(1.0 + 0.9 * breath + 0.7 * swell, 1.0, VW_STRETCH_MAX);
    float dist = VW_D0 * stretch;
    float focal = VW_F0 * stretch;
    // The push: camera only, no lens - the floor swells a little on the bass.
    dist -= 0.45 * bassA;

    // A slow roll of the whole view, so the helix turns as it telescopes.
    float roll = uTime * 0.035 + 0.3 * sin(uTime * 0.021);
    vec2 suv = rot2(roll) * uv;
    // A finger tilts the look off the axis, toward where it is.
    suv += touchAnchor() * 0.3;

    vec3 ro = vec3(0.0, 0.0, -dist);
    vec3 rd = normalize(vec3(suv, focal));
    cameraOrbit(ro, rd);

    float t = 0.05;
    float hitT = -1.0;
    float glow = 0.0;
    float steps = 0.0;
    float far = dist + 3.0;
    for (int i = 0; i < VW_MAX_STEPS; i++) {
        if (float(i) >= uSteps) break;
        vec3 p = ro + rd * t;
        float d = map(p);
        float eps = 0.0007 * t + 0.0006;
        // The banister's halo, weighted by the distance the ray actually moved
        // so a finer march does not make it brighter.
        float stepLen = max(d * 0.8, eps);
        glow += exp(-gRail * 18.0) * stepLen;
        steps += 1.0;
        if (d < eps) { hitT = t; break; }
        t += stepLen;
        if (t > far) break;
    }

    vec3 fogCol = mix(vec3(0.015, 0.02, 0.045), pal(hue + 0.55) * 0.05, 0.5);
    vec3 col = fogCol;
    if (hitT > 0.0) {
        vec3 p = ro + rd * hitT;
        map(p);
        int mat = gMat;
        vec3 n = normalAt(p, max(0.0012 * hitT, 0.0008));
        float depthUp = -p.z;
        // Cheap AO: two taps along the normal.
        float ao = clamp(0.5 + 0.5 * (map(p + n * 0.08) / 0.08), 0.0, 1.0)
                 * clamp(0.6 + 0.4 * (map(p + n * 0.25) / 0.25), 0.0, 1.0);
        ao *= 1.0 - 0.35 * steps / max(uSteps, 1.0);

        // Key: the glowing floor, from below, falling off up the shaft.
        vec3 keyDir = normalize(vec3(-p.xy * 0.25, 1.0));
        vec3 keyCol = (pal(hue + 0.02) * 0.6 + vec3(0.9, 0.75, 0.55)) * 1.6 * exp(-depthUp * 0.08 / xm(uXFog));
        // Fill: the cool daylight from the top of the well, behind the camera.
        vec3 fillCol = pal(hue + 0.55) * 0.2 + vec3(0.26, 0.28, 0.34);
        float diff = max(dot(n, keyDir), 0.0);
        float fill = 0.5 + 0.5 * dot(n, vec3(0.0, 0.0, -1.0));
        vec3 h = normalize(keyDir - rd);
        float spec = pow(max(dot(n, h), 0.0), 40.0);
        float fres = pow(1.0 - max(dot(n, -rd), 0.0), 4.0);

        vec3 albedo;
        if (mat == 1) {
            albedo = mix(vec3(0.78, 0.7, 0.62), pal(hue + 0.1), 0.18);
        } else if (mat == 2) {
            albedo = pal(hue + 0.35);
        } else if (mat == 3) {
            albedo = vwFloor(p, hue);
        } else if (mat == 4) {
            albedo = vec3(0.5, 0.48, 0.46);
        } else {
            // Plaster panels, darker between the turns.
            float a = atan(p.y, p.x);
            float panel = smoothstep(0.0, 0.05, abs(fract(a / VW_TAU * 12.0) - 0.5) - 0.02);
            albedo = vec3(0.46, 0.43, 0.47) * (0.8 + 0.2 * panel);
        }

        if (mat == 3) {
            col = albedo * (0.85 + 0.25 * fill) + pal(hue) * spec * 0.2;
        } else {
            col = albedo * (keyCol * diff + fillCol * fill) * ao;
            col += keyCol * spec * 0.35 * ao;
            col += fillCol * fres * 0.6;
        }
        if (mat == 2) col += pal(hue + 0.35) * 0.35;
        if (mat == 4) {
            // The landing's light strip on its inner edge, lit by its spectrum band.
            float k = round(-p.z / VW_LANDING);
            float edge = smoothstep(0.08, 0.0, length(p.xy) - (VW_RW - VW_STEP_DEPTH - 0.12));
            float band = aband(fract(k / 10.0));
            col += pal(hue + 0.12 * k) * edge * (0.15 + 0.5 * band);
        }
        col = mix(col, fogCol, 1.0 - exp(-max(hitT - 2.0, 0.0) * 0.03 * xm(uXFog)));
    }
    col += pal(hue + 0.35) * glow * 0.06;

    if (!touchIdle()) {
        col += pal(hue + 0.5) * min(touchWake(uv), 2.0) * 0.05;
    }
    col = vwTone(col);
    col *= 0.78 + 0.22 * smoothstep(1.9, 0.4, length(uv));
    fragColor = vec4(grade(col), 1.0);
}
