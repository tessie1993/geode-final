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

// The paper every facet is folded from: an image this Style ships in its own pack (manifest "textures",
// tools/style_textures/generate.py), tiling and mipmapped.
uniform highp sampler2D uImagePaper;

// Origami Peaks: a high flyover of faceted mountains at dawn. The range is a
// triangulated heightfield - every facet flat, every ridge a crease - lit by a
// low pink sun with long soft shadows, with mist lying in the valleys. The
// camera glides high and banks through long curves; the music folds the
// ridges, and the facets hinge like paper as their corners rise and fall.
//
// The paper is laid flat on the ground plane, OP_PAPER_SCALE repeats per cell.
#define OP_PAPER_SCALE 0.35
#define OP_PAPER_TEXELS 256.0

// ---- a piecewise-flat terrain -------------------------------------------------------
//
// Heights live only on the vertices of a square grid split along one
// diagonal. A point's height is the plane through its triangle's three
// corners, so the surface is exactly flat per facet and its normal is that
// triangle's normal, from three vertex heights: no taps, no smoothing. The
// march steps by a fraction of the height above the surface (a heightfield
// has no distance bound, so the fraction is what keeps it from stepping
// through a steep facet) and refines the crossing by bisection.
//
// Vertex heights hash the vertex index modulo OP_N and every macro frequency
// is a whole number of cycles over it, so the range repeats every OP_N cells
// and the camera's flight is wrapped to that period without a seam.
//
// ---- audio ---------------------------------------------------------------------------------
//
// uSwell (slow) and uBassSmooth fold the ridges: they raise the creased
// ridge term on every vertex, so facets hinge rather than jump. Energy eases
// the flight through uFlowPhase. A beat sends a wave of light out across the
// creases from below the camera (uBeatPhase, gated by the beat envelope);
// uMidSmooth drifts and thickens the valley mist; uTrebleSmooth sparkles the
// snow. No transient touches the sun or the sky.

#define OP_MAX_STEPS 96
#define OP_N 128.0
#define OP_W 0.04908739
#define OP_FAR 70.0
#define OP_TAU 6.2831853

vec3 gSun;
float gFold;

/** The height of grid vertex v. */
float vertexHeight(vec2 v) {
    vec2 w = mod(v, OP_N);
    vec2 u = w * OP_W;
    float macro = sin(u.x * 3.0 + 1.3 * sin(u.y * 2.0)) * cos(u.y * 4.0 - 1.1 * sin(u.x * 5.0));
    macro += 0.5 * sin(u.x * 7.0 - u.y * 5.0 + 2.0);
    float ridge = 1.0 - abs(sin(u.x * 9.0 + u.y * 6.0 + 1.5 * sin(u.y * 3.0)));
    float jitter = hashCell(ivec3(ivec2(w), 17));
    return 1.8 + 2.0 * macro + (0.6 + 1.6 * gFold) * ridge * ridge + 0.7 * jitter;
}

/** Terrain height at xz; also the facet's three corners (xyz each) for its normal. */
float terrain(vec2 x, out vec3 a, out vec3 b, out vec3 c) {
    vec2 cell = floor(x);
    vec2 f = x - cell;
    vec2 o = f.x > f.y ? vec2(1.0, 0.0) : vec2(0.0, 1.0);
    float h0 = vertexHeight(cell);
    float h1 = vertexHeight(cell + o);
    float h2 = vertexHeight(cell + 1.0);
    a = vec3(cell.x, h0, cell.y);
    b = vec3(cell.x + o.x, h1, cell.y + o.y);
    c = vec3(cell.x + 1.0, h2, cell.y + 1.0);
    // Barycentric on the half-cell: the plane through the three corners.
    return f.x > f.y ? h0 + (h1 - h0) * (f.x - f.y) + (h2 - h0) * f.y
                     : h0 + (h1 - h0) * (f.y - f.x) + (h2 - h0) * f.x;
}

float heightAt(vec2 x) {
    vec3 a;
    vec3 b;
    vec3 c;
    return terrain(x, a, b, c);
}

/** Soft shadow toward the sun: the narrowest clearance over distance, marched. */
float sunShadow(vec3 p) {
    float lit = 1.0;
    float t = 0.15;
    for (int i = 0; i < 16; i++) {
        vec3 q = p + gSun * t;
        float h = q.y - heightAt(q.xz);
        lit = min(lit, 10.0 * h / t);
        if (lit < 0.02 || q.y > 8.5) break;
        t += clamp(h * 0.6, 0.15, 1.6);
    }
    return clamp(lit, 0.0, 1.0);
}

vec3 skyCol(vec3 d) {
    float s = max(dot(d, gSun), 0.0);
    vec3 low = mix(pal(0.1), vec3(1.0, 0.8, 0.7), 0.45) * 0.75;
    vec3 high = mix(pal(0.32), vec3(0.2, 0.25, 0.45), 0.5) * 0.45;
    vec3 col = mix(low, high, smoothstep(-0.05, 0.5, d.y));
    vec3 sunTint = mix(pal(0.9), vec3(1.0), 0.5);
    col += sunTint * (0.35 * pow(s, 8.0) + 0.5 * pow(s, 120.0));
    return col;
}

vec3 filmic(vec3 c) {
    c = max(c, 0.0) * 1.2;
    return c * (1.0 + c * 0.25) / (1.0 + c);
}

// A finger steers the look toward where it is held; on release the look
// glides home as the touch fades. Untouched, the ray comes back as it went in.
vec3 steerLook(vec3 rd) {
    if (touchIdle()) return rd;
    vec2 a = touchAnchor() * touchStrength();
    return rotY(a.x * 0.8) * (rotX(-a.y * 0.5) * rd);
}

void main() {
    vec2 uv = view();
    float bass = clamp(uBassSmooth, 0.0, 1.5);
    float mid = clamp(uMidSmooth, 0.0, 1.5);
    float treb = clamp(uTrebleSmooth, 0.0, 1.5);
    float swell = clamp(uSwell, 0.0, 1.5);
    float wave = clamp(uBeat, 0.0, 1.0) * clamp(uBeatResponse, 0.0, 2.0);
    gFold = 0.3 + 0.5 * swell + 0.15 * bass;
    gSun = normalize(vec3(0.55, 0.16, 0.82));

    // ---- the flight ----------------------------------------------------------------
    //
    // Long curves whose frequencies divide the period, banked into the turn.
    float z = mod(uTime * 0.7 + uFlowPhase * 5.5 + uCamTravel, OP_N);
    float wz = OP_W * 2.0;
    float x = 9.0 * sin(wz * z) + 3.0 * sin(OP_W * 5.0 * z + 1.0);
    float slope = 9.0 * wz * cos(wz * z) + 3.0 * OP_W * 5.0 * cos(OP_W * 5.0 * z + 1.0);
    float curve = -9.0 * wz * wz * sin(wz * z) - 3.0 * 25.0 * OP_W * OP_W * sin(OP_W * 5.0 * z + 1.0);
    vec3 ro = vec3(x, 10.5 + 0.6 * sin(uTime * 0.09), z);
    vec3 fwd = normalize(vec3(slope, -0.42, 1.0));
    float roll = clamp(curve * 6.0, -0.3, 0.3);
    vec3 right = normalize(cross(vec3(sin(roll), cos(roll), 0.0), fwd));
    vec3 up = cross(fwd, right);
    vec3 rd = mat3(right, up, fwd) * steerLook(normalize(vec3(uv, 1.25)));
    rd = uCamRot * rd;
    ro += fwd * uCamPush * 3.0;

    // ---- the march ---------------------------------------------------------------------
    float budget = clamp(uSteps * 0.75, 24.0, float(OP_MAX_STEPS));
    float t = 0.1;
    float prevT = t;
    float hitT = -1.0;
    for (int i = 0; i < OP_MAX_STEPS; i++) {
        if (float(i) >= budget || t > OP_FAR) break;
        vec3 p = ro + rd * t;
        float h = p.y - heightAt(p.xz);
        if (h < 0.002 * t) {
            // Bisect between the last point above and this one below.
            float lo = prevT;
            float hi = t;
            for (int k = 0; k < 5; k++) {
                float m = 0.5 * (lo + hi);
                vec3 q = ro + rd * m;
                if (q.y - heightAt(q.xz) < 0.0) hi = m; else lo = m;
            }
            hitT = hi;
            break;
        }
        prevT = t;
        t += max(h * 0.42, 0.01 + 0.004 * t);
    }

    vec3 col;
    float tEnd = hitT > 0.0 ? hitT : OP_FAR;
    if (hitT > 0.0) {
        vec3 p = ro + rd * hitT;
        vec3 a;
        vec3 b;
        vec3 c;
        terrain(p.xz, a, b, c);
        vec3 n = normalize(cross(c - a, b - a));
        if (n.y < 0.0) n = -n;
        // Key, fill and rim: the low sun, the cool sky above, and the warm
        // bounce off the sunward slopes onto the faces turned away.
        float key = max(dot(n, gSun), 0.0) * sunShadow(p + n * 0.02);
        float fill = 0.5 + 0.5 * n.y;
        float rim = pow(clamp(1.0 + dot(rd, n), 0.0, 1.0), 3.0);
        float snow = smoothstep(4.6, 5.6, p.y + 0.8 * n.y);
        vec3 rock = mix(mix(pal(0.08), vec3(0.55, 0.5, 0.5), 0.78), mix(pal(0.3), vec3(0.42), 0.78), 0.5 + 0.5 * sin(dot(a.xz, vec2(1.7, 2.3))));
        vec3 albedo = mix(rock * 0.55, vec3(0.92, 0.94, 1.0), snow);
        // The paper's grain, at the mip level this pixel covers: chosen by hand from the hit distance, as
        // the hit is found in a loop where the implicit derivatives are not defined.
        float footprint = hitT * 1.5 / (uResolution.y * 1.25) * OP_PAPER_SCALE * OP_PAPER_TEXELS;
        float grain = textureLod(uImagePaper, p.xz * OP_PAPER_SCALE, log2(max(footprint, 1.0))).r;
        albedo *= 1.0 + 1.6 * (grain - 0.95);
        // Valleys are darker: what the sky can see of them.
        float ao = clamp(0.4 + 0.12 * p.y, 0.4, 1.0);
        vec3 sunTint = mix(pal(0.92), vec3(1.0), 0.45) * 1.9;
        vec3 skyTint = mix(pal(0.36), vec3(0.6, 0.7, 1.0), 0.6) * 0.38;
        col = albedo * (sunTint * key + skyTint * fill * ao) + sunTint * 0.08 * rim * ao;
        // Snow catches the sun in glints on the treble.
        float glint = pow(max(dot(reflect(rd, n), gSun), 0.0), 40.0) * snow;
        col += sunTint * glint * (0.15 + 0.5 * treb);
        // The creases: lines along the facet edges, never thinner than a
        // pixel, and the beat's wave running out across them.
        vec2 f = fract(p.xz);
        float edge = min(min(f.x, 1.0 - f.y), abs(f.x - f.y) * 0.7071);
        edge = min(edge, min(1.0 - f.x, f.y));
        float width = max(0.012, hitT * 1.5 / (uResolution.y * 1.25));
        float crease = 1.0 - smoothstep(0.0, width, edge);
        float dist = length(p.xz - ro.xz);
        float front = exp(-pow((dist - uBeatPhase * 34.0) / 2.5, 2.0)) * wave;
        col += mix(pal(0.9), vec3(1.0), 0.5) * crease * (0.04 + 0.5 * front) * exp(-hitT * 0.03);
    } else {
        col = skyCol(rd);
    }

    // ---- the air ---------------------------------------------------------------------------
    //
    // Height fog in closed form (density falling off with altitude, integrated
    // along the ray), tinted toward the sun; then the valley mist, a thin
    // drifting layer near the valley floors.
    float sunward = pow(max(dot(rd, gSun), 0.0), 4.0);
    vec3 fogCol = mix(mix(pal(0.33), vec3(0.55, 0.6, 0.8), 0.6) * 0.5, mix(pal(0.92), vec3(1.0), 0.5) * 0.85, sunward);
    float fall = 0.32;
    float ry = abs(rd.y) > 1e-4 ? rd.y : 1e-4;
    float fogAmt = 0.09 * exp(-ro.y * fall) * (1.0 - exp(-tEnd * ry * fall)) / ry;
    col = mix(col, fogCol, clamp(fogAmt, 0.0, 1.0));
    if (hitT > 0.0) {
        vec3 p = ro + rd * hitT;
        float mist = smoothstep(2.8, 0.6, p.y) * (0.35 + 0.25 * mid);
        mist *= 0.6 + 0.4 * motionNoise(p.xz * 0.25 + vec2(uTime * 0.05 + uFlowPhase * 0.3 * (1.0 + mid), 0.0));
        col = mix(col, fogCol * 1.05, mist * smoothstep(3.0, 18.0, hitT));
    }

    if (!touchIdle()) {
        col += mix(pal(0.9), vec3(1.0), 0.5) * min(touchWake(uv), 3.0) * 0.035;
    }
    col *= 0.76 + 0.24 * smoothstep(2.2, 0.4, length(uv));
    fragColor = vec4(grade(filmic(col)), 1.0);
}
