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

// Blacklight Bloom: a flight through a space strung with UV ink, into soap bubbles and out the other
// side. The ink is a folded lattice of thin tubes (a kaleidoscopic fold-and-scale, repeated through
// space), every line drawn three times a hair apart, green at the core and cyan and magenta round it,
// which is where the blacklight look comes from. Spiral discs and wire tetrahedra drift beside the way.
// Every BUBBLE units a soap bubble sits on the path: the camera flies into it, through its iridescent
// skin, and inside the ink is folded another way and tinted another hue, until it leaves by the far
// side and the next bubble grows ahead.
//
// The ink and the floaters are light, not surfaces: the march gathers them along the ray (glowLine) and
// never stops on one, so there are no normals to take. The bubbles are spheres met analytically, and
// their skin adds thin-film colour where the ray crosses it, strongest at a grazing angle (Fresnel), so
// passing through one tints the frame's edge rather than flashing it.
//
// ---- the camera --------------------------------------------------------------------------------------
//
// The flight runs on its own monotonic clock (uFlowPhase) down a gently weaving path; the Camera's
// travel and push-in add to it and the orbit turns the gaze (cameraFlight); the lens and the roll come
// through cameraView(). Everything repeats in 1000 units of z, the Camera's travel wrap.
//
// ---- the music -------------------------------------------------------------------------------------
//
// uSwell breathes the fold scale over seconds, uBassSmooth the ink's weight, uTrebleSmooth how far
// apart its three lines sit. A spike turns the fold to a new angle (uFormPhase, which glides), re-seeds
// which floaters are present (uSpawnSeed, each grown in over its own second), steers the ink's drift
// (uMoveDir) and lights the bubbles' rims on uSpike, which rises over a tenth of a second.

#define BB_TAU 6.2831853
/** The bubbles' spacing along the path and their radius; the ink's cell; the floaters' cell. */
#define BB_BUBBLE 10.0
#define BB_RADIUS 2.3
#define BB_CELL 2.5
#define BB_FLOAT 5.0
#define BB_FOCAL 1.3
#define BB_FAR 24.0
#define BB_MAX_STEPS 72

// Set once per pixel in main().
float gScale;
float gTurn;
vec3 gDrift;

/** The flight path: a weave whose periods divide the travel wrap. */
vec3 path(float z) {
    return vec3(0.55 * sin(z * BB_TAU / 50.0), 0.3 * sin(z * BB_TAU / 25.0 + 1.0), z);
}

/** The bubble whose cell z is in. */
vec3 bubbleAt(float z) {
    return path((floor(z / BB_BUBBLE) + 0.5) * BB_BUBBLE);
}

/**
 * Distance to the ink's lines at p. Each cell of the lattice holds a nested pair of wire solids turned
 * by the fold angle, an icosahedron round a dodecahedron, joined to the next cells by struts down the
 * cell's axes; inside a bubble the pair is a merkaba in a geodesic cage instead.
 */
float ink(vec3 p, float inside) {
    vec3 q = p + gDrift;
    q = mod(q, BB_CELL) - 0.5 * BB_CELL;
    float r = 0.62 * gScale;
    q.xy = rot2(gTurn) * q.xy;
    q.yz = rot2(gTurn * 0.7 + 0.4) * q.yz;
    float d;
    if (inside > 0.5) {
        d = min(wireMerkaba(q, r * 0.8), wireGeodesic(q, r * 1.15, 2.0));
    } else {
        d = min(wireIcosahedron(q, r), wireDodecahedron(q, r * 0.55));
    }
    // The struts: thin lines from cell to cell, so the lattice reads as one tapestry.
    vec3 a = abs(q);
    float strut = min(length(a.xy), min(length(a.yz), length(a.zx)));
    return min(d, max(strut, r * 1.05 - length(q)));
}

/**
 * A tight neon line: the light of a line `w` thick passed at `d`, over a step `dt`. Summed along a ray
 * that passes it at b it comes to w^2 / (w^2 + b^2), 1 on the line and falling as 1 / b^2: sharper than
 * glowLine's 1 / b halo, so a lattice of many lines stays line work instead of fogging over.
 */
float inkLine(float d, float w, float dt) {
    float ww = w * w + d * d;
    return min(dt, 4.0 * max(abs(d), w)) * w * w / (2.0 * ww * sqrt(ww));
}

/** A wire tetrahedron of circumradius r at the origin. */
float wireTetra(vec3 q, float r) {
    float k = r * 0.57735027;
    vec3 a = vec3(k, k, k);
    vec3 b = vec3(k, -k, -k);
    vec3 c = vec3(-k, k, -k);
    vec3 d = vec3(-k, -k, k);
    float e = min(min(wireSegment(q, a, b), wireSegment(q, a, c)), wireSegment(q, a, d));
    return min(e, min(min(wireSegment(q, b, c), wireSegment(q, b, d)), wireSegment(q, c, d)));
}

/** A flat spiral disc of radius r facing down the path: the distance to its wound groove. */
float spiralDisc(vec3 q, float r, float turns) {
    float rr = length(q.xy);
    float a = atan(q.y, q.x);
    float arm = fract((rr / r) * turns - a / BB_TAU);
    float groove = min(arm, 1.0 - arm) * r / turns;
    return length(vec2(max(groove, rr - r), q.z));
}

/** The floater in the cell p is in: x its distance, y its seed (below 0 when the cell is empty). */
vec2 floater(vec3 p) {
    float cell = floor(p.z / BB_FLOAT);
    float seed = hash11(mod(cell, 200.0) * 5.17 + floor(uSpawnSeed * 53.0));
    if (seed < 0.25) return vec2(1e3, -1.0);
    float side = seed < 0.62 ? -1.0 : 1.0;
    vec3 at = path((cell + 0.5) * BB_FLOAT) + vec3(side * (1.3 + 0.9 * fract(seed * 7.0)), 0.7 * (fract(seed * 13.0) - 0.5), 0.0);
    vec3 q = p - at;
    float spin = uFlowPhase * (0.3 + 0.4 * seed);
    q.xz = rot2(spin) * q.xz;
    q.xy = rot2(spin * 0.6) * q.xy;
    float d = fract(seed * 3.0) < 0.5 ? spiralDisc(q, 0.45, 3.0 + floor(seed * 4.0)) : wireTetra(q, 0.5);
    return vec2(d, seed);
}

/** The light a bubble's skin adds where the ray crosses it at `cosine` (the angle to its normal). */
vec3 film(float cosine, vec3 at) {
    float fresnel = 0.05 + 0.95 * pow(1.0 - cosine, 5.0);
    float swirl = 0.15 * sin(at.x * 3.0 + at.y * 2.0 + uFlowPhase * 0.4);
    vec3 band = pal(fract(0.2 + cosine * 0.8 + uFormPhase * 0.3 + swirl));
    float rim = smoothstep(0.35, 0.0, cosine);
    return band * (0.08 + 0.55 * fresnel) + pal(0.50) * rim * (0.15 + 0.25 * uSpike);
}

/** Every crossing of a bubble's skin by the ray within `far`, in the two bubbles nearest ahead. */
vec3 skins(vec3 ro, vec3 rd, float far) {
    vec3 col = vec3(0.0);
    for (int k = 0; k < 2; k++) {
        vec3 c = bubbleAt(ro.z + float(k) * BB_BUBBLE);
        vec3 oc = ro - c;
        float b = dot(oc, rd);
        float h = b * b - (dot(oc, oc) - BB_RADIUS * BB_RADIUS);
        if (h <= 0.0) continue;
        h = sqrt(h);
        for (int j = 0; j < 2; j++) {
            float t = j == 0 ? -b - h : -b + h;
            if (t <= 0.0 || t > far) continue;
            vec3 at = ro + rd * t;
            float cosine = abs(dot(normalize(at - c), rd));
            col += film(cosine, at) * exp(-t * 0.08);
        }
    }
    return col;
}

void main() {
    vec2 uv = cameraView();

    float bass = clamp(uBassSmooth, 0.0, 1.5);
    float treb = clamp(uTrebleSmooth, 0.0, 1.5);
    float swell = clamp(uSwell, 0.0, 1.5);
    gScale = clamp((1.0 + 0.08 * swell) * xm(uXScale), 0.5, 1.6);
    gTurn = 0.42 + 0.6 * uFormPhase;
    gDrift = vec3(uMoveDir * (uFlowPhase * 0.04), 0.0);

    // ---- the flight ------------------------------------------------------------------------------------
    vec2 lens = uv;
    if (!touchIdle()) lens -= touchAnchor() * (0.3 * touchStrength());
    vec3 ro = vec3(0.0, 0.0, mod(uFlowPhase * 0.9, 1000.0));
    vec3 rd = normalize(vec3(lens, BB_FOCAL));
    cameraFlight(ro, rd);
    float z = mod(ro.z, 1000.0);
    vec3 eye = path(z);
    vec3 ahead = path(z + 3.0);
    vec3 forward = normalize(ahead - eye);
    vec3 right = normalize(cross(vec3(0.0, 1.0, 0.0), forward));
    vec3 up = cross(forward, right);
    rd = normalize(mat3(right, up, forward) * rd);
    ro = eye;

    // ---- the ink and the floaters, gathered along the ray -------------------------------------------------
    vec3 core = pal(0.62);
    vec3 cyan = pal(0.46);
    vec3 magenta = pal(0.88);
    float weight = 0.006 + 0.005 * bass;
    float split = 0.014 + 0.016 * clamp(treb, 0.0, 1.0);
    vec3 col = vec3(0.0);
    float t = 0.05 + 0.05 * uhashUnit(uhash3(uvec3(gl_FragCoord.xy, 5u)));
    float steps = min(uSteps * 0.6, float(BB_MAX_STEPS));
    for (int i = 0; i < BB_MAX_STEPS; i++) {
        if (float(i) >= steps || t > BB_FAR) break;
        vec3 p = ro + rd * t;
        vec3 c = bubbleAt(p.z);
        float inside = step(length(p - c), BB_RADIUS);
        float d = ink(p, inside);
        vec2 f = floater(p);
        float dt = clamp(min(d, f.x) * 0.85, 0.01, 0.45);
        float fade = exp(-t * 0.16);
        // Inside a bubble the same three inks walk round the wheel.
        float hue = 0.3 * inside;
        vec3 inkCore = inside > 0.5 ? pal(0.62 + hue) : core;
        vec3 ink2 = inside > 0.5 ? pal(0.46 + hue) : cyan;
        vec3 ink3 = inside > 0.5 ? pal(0.88 + hue) : magenta;
        vec3 light = inkCore * inkLine(d, weight, dt);
        light += ink2 * inkLine(abs(d - split), weight, dt) * 0.55;
        light += ink3 * inkLine(abs(d - 2.0 * split), weight, dt) * 0.45;
        if (f.y >= 0.0) {
            vec3 tint = mix(pal(0.44), pal(0.92), f.y);
            light += mix(vec3(1.0), tint, 0.45) * inkLine(f.x, 0.02, dt) * 0.9 * spawnGrow(0.7 + f.y * 0.8);
        }
        col += light * fade;
        t += dt;
    }
    col *= 0.3;

    // ---- the bubbles' skins, and the violet the dark is never without -----------------------------------------
    col += skins(ro, rd, BB_FAR);
    col += pal(0.70) * 0.05 * (0.5 + 0.5 * swell);

    // ---- on the glass --------------------------------------------------------------------------------------
    col += mix(pal(0.46), vec3(1.0), 0.35) * fluidMotes(uv, 5.0, 0.15) * 0.16;
    if (!touchIdle()) {
        col += pal(0.5 + 0.15 * sin(uTime * 0.05)) * min(touchWake(uv), 3.0) * 0.05;
    }
    col = col / (1.0 + 0.3 * col);
    vec2 sv = (vUv - 0.5) * vec2(uResolution.x / max(uResolution.y, 1.0), 1.0) * 2.0;
    col *= 0.70 + 0.30 * smoothstep(2.2, 0.5, length(sv));
    fragColor = vec4(grade(col), 1.0);
}
