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
//#include lib_style

// Endless Stacks: walking the corridors of a library that never ends, the
// book spines glowing with the music.
//
// THE LIBRARY is one bookcase block per cell of a square grid, repeated in
// both directions, so the aisles between them run along every grid line and
// cross at every corner. Each block is lined on its faces with shelves:
// boards at a fixed pitch and, on each board, a row of books in slots, every
// book with its own height, depth and colour from its slot's hash. The block
// is folded onto whichever face is nearest, so one face's shelving dresses
// all four. A pendant lamp hangs over every crossing.
//
// THE WALK follows the aisles: straight down one, a quarter turn right at the
// crossing, straight down the next, a quarter turn left, and so on, so the
// path never closes and is written in closed form (esWalk) rather than
// accumulated. The turns are arcs, so heading and position are smooth, and
// the eye looks a little ahead along the path, so it turns into a corner
// before the feet do. The head bobs with the stride: the bob's phase is the
// distance walked, not the time, so it slows and quickens with the walk.
//
// AUDIO. Energy sets the walking pace (uFlowPhase, integrated: it eases,
// never jumps), and with it the bob. Every spine's gilt band glows with one
// band of the spectrum (uAudioTex), chosen by the book's hash, so the shelves
// shimmer with what is playing; each glow is a small patch, never the frame.
// A beat swells the lamps (uSpike, with a rise) and sends a wave of glowing
// spines down the aisle away from the reader (uBeatPhase is how far it has
// gone). Treble catches the gilt.
//
// WHY THE ESTIMATE IS SAFE. The face fold and the books' slot repetition are
// not exact at a block's corners or between slots, so the ray steps 0.6 of
// the estimate; the books stay inside their slots and their shelf.

#define ES_MAX_STEPS 96
#define ES_FOCAL 1.2
#define ES_FAR 34.0

/** The grid's pitch, the bookcase's half width, its height, and the ceiling. */
const float ES_CELL = 6.0;
const float ES_HALF = 1.9;
const float ES_HEIGHT = 3.0;
const float ES_CEILING = 3.7;
const float ES_SHELF = 0.55;
const float ES_SLOT = 0.1;
const float ES_DEPTH = 0.28;
const float ES_TURN = 0.9;

float gSpine;
float gBook;
float gFront;

float esBlock(vec3 p) {
    vec2 q = mod(p.xz, ES_CELL) - 0.5 * ES_CELL;
    vec2 cell = floor(p.xz / ES_CELL);
    // Fold onto the nearest face: n is the distance out from the block's
    // centre, u runs along the face.
    bool xFace = abs(q.x) > abs(q.y);
    float n = xFace ? abs(q.x) : abs(q.y);
    float u = xFace ? q.y : q.x;
    float faceId = xFace ? (q.x > 0.0 ? 0.0 : 1.0) : (q.y > 0.0 ? 2.0 : 3.0);
    float outer = sdBox(vec3(q.x, p.y - 0.5 * ES_HEIGHT, q.y), vec3(ES_HALF, 0.5 * ES_HEIGHT, ES_HALF));
    float back = n - (ES_HALF - ES_DEPTH);
    float yy = mod(p.y, ES_SHELF);
    float board = max(abs(yy - 0.02) - 0.02, n - ES_HALF);
    // The book in this slot on this shelf.
    float slot = floor(u / ES_SLOT);
    float level = floor(p.y / ES_SHELF);
    float h = motionHash(vec2(slot + faceId * 57.0 + cell.x * 131.0, level + cell.y * 71.0));
    float height = (0.55 + 0.35 * fract(h * 7.3)) * (ES_SHELF - 0.05);
    float front = ES_HALF - 0.015 - 0.06 * fract(h * 3.1);
    float halfW = 0.5 * ES_SLOT - 0.006 - 0.012 * fract(h * 11.7);
    vec3 bc = vec3(0.5 * (front + ES_HALF - ES_DEPTH), level * ES_SHELF + 0.04 + 0.5 * height, (slot + 0.5) * ES_SLOT);
    vec3 bh = vec3(0.5 * (front - (ES_HALF - ES_DEPTH)), 0.5 * height, halfW);
    float book = h < 0.06 ? 1e3 : sdBox(vec3(n, p.y, u) - bc, bh);
    float d = max(min(min(back, board), book), outer);
    if (book <= d + 1e-4) {
        gBook = h;
        gSpine = n - front;
        gFront = (p.y - level * ES_SHELF - 0.04) / max(height, 0.01);
    } else {
        gBook = -1.0;
    }
    return d;
}

float esMap(vec3 p) {
    return min(min(esBlock(p), p.y), ES_CEILING - p.y);
}

vec3 esNormal(vec3 p, float eps) {
    vec2 k = vec2(1.0, -1.0);
    return normalize(k.xyy * esMap(p + k.xyy * eps) + k.yyx * esMap(p + k.yyx * eps)
                   + k.yxy * esMap(p + k.yxy * eps) + k.xxx * esMap(p + k.xxx * eps));
}

/**
 * The walk at distance d: position in xz and heading (0 along +z, pi/2 along
 * +x). Two aisles and two arcs make one period, which moves one cell along
 * each axis.
 */
vec3 esWalk(float d) {
    const float straight = ES_CELL - 2.0 * ES_TURN;
    const float arc = 1.5707963 * ES_TURN;
    const float period = 2.0 * straight + 2.0 * arc;
    float k = floor(d / period);
    float s = d - k * period;
    vec2 base = vec2(k * ES_CELL);
    vec2 pos;
    float heading;
    if (s < straight) {
        pos = vec2(0.0, ES_TURN + s);
        heading = 0.0;
    } else if (s < straight + arc) {
        // Right, from +z to +x, about (r, C - r).
        float a = (s - straight) / ES_TURN;
        pos = vec2(ES_TURN, ES_CELL - ES_TURN) + ES_TURN * vec2(-cos(a), sin(a));
        heading = a;
    } else if (s < 2.0 * straight + arc) {
        pos = vec2(ES_TURN + (s - straight - arc), ES_CELL);
        heading = 1.5707963;
    } else {
        // Left, from +x back to +z, about (C - r, C + r).
        float a = (s - 2.0 * straight - arc) / ES_TURN;
        pos = vec2(ES_CELL - ES_TURN, ES_CELL + ES_TURN) + ES_TURN * vec2(sin(a), -cos(a));
        heading = 1.5707963 - a;
    }
    return vec3(base + pos, heading);
}

/**
 * A filmic shoulder before the grade: below the knee the picture is untouched,
 * above it highlights roll off smoothly toward white instead of clipping, so a
 * swell of light stays a swell. Continuous in value and slope at the knee.
 */
vec3 shoulder(vec3 c) {
    vec3 over = max(c - 0.6, 0.0);
    return min(c, 0.6) + over / (1.0 + over * 2.5);
}

/** A fraction of one 8-bit step of noise, so dark gradients do not band. */
float dither() {
    return (motionHash(gl_FragCoord.xy + 0.5) - 0.5) / 255.0;
}

/**
 * Where a finger rests the head turns toward it: x yaws, y pitches, scaled by
 * how firmly it is held, so a release glides the view home. Exactly zero with
 * nothing touched, which leaves the frame bit-identical.
 */
vec2 touchLook() {
    if (touchIdle()) return vec2(0.0);
    return touchAnchor() * touchStrength();
}

void main() {
    vec2 uv = view();
    float mid = min(uMidSmooth, 1.3);
    float treb = min(uTrebleSmooth, 1.3);
    float beatEnv = clamp(uBeat, 0.0, 1.0) * clamp(uBeatResponse, 0.0, 2.0);

    vec3 rdl = normalize(vec3(uv, ES_FOCAL));
    vec2 look = touchLook();
    if (look != vec2(0.0)) rdl = rotY(look.x * 0.6) * rotX(-look.y * 0.4) * rdl;
    vec3 camOff = vec3(0.0);
    cameraFlight(camOff, rdl);

    // Distance walked, INTEGRATED: the Speed control through uTime, loudness through uFlowPhase.
    float walked = uTime * 0.9 + uFlowPhase * 3.5 + camOff.z;
    vec3 w = esWalk(walked);
    // The eye leads the feet: the heading a little further on, blended in.
    float heading = mix(w.z, esWalk(walked + 1.2).z, 0.6);
    // The stride: one bob per step, phased by distance so it follows the pace.
    float stride = walked * 4.4;
    vec2 side = vec2(cos(w.z), -sin(w.z));
    vec3 ro = vec3(w.x, 1.62 + 0.03 * sin(stride * 2.0), w.y);
    ro.xz += side * 0.025 * sin(stride);
    float roll = 0.012 * sin(stride);
    float pitch = 0.04 + 0.01 * sin(stride * 2.0 + 0.6);
    vec3 rd = rotY(heading) * rotX(pitch) * rotZ(roll) * rdl;

    float budget = uSteps * (float(ES_MAX_STEPS) / 128.0);
    float t = 0.05;
    bool hit = false;
    for (int i = 0; i < ES_MAX_STEPS; i++) {
        if (float(i) >= budget || t > ES_FAR) break;
        float d = esMap(ro + rd * t);
        float eps = 0.0012 + 0.001 * t;
        if (d < eps) {
            hit = true;
            break;
        }
        t += max(d * 0.6, eps);
    }

    vec3 dark = pal(0.9) * 0.01 + vec3(0.006, 0.005, 0.008);
    vec3 col = dark;
    if (hit) {
        vec3 p = ro + rd * t;
        esBlock(p);
        // The floor and ceiling are not shelving, whatever the fold reports there.
        bool plain = p.y < 0.035 || p.y > ES_CEILING - 0.05;
        float book = plain ? -1.0 : gBook;
        float spine = gSpine;
        float up = gFront;
        vec3 n = esNormal(p, 0.0015 + 0.001 * t);
        // The pendant over the nearest crossing.
        vec2 crossing = floor(p.xz / ES_CELL + 0.5) * ES_CELL;
        vec3 lamp = vec3(crossing.x, ES_CEILING - 0.5, crossing.y);
        vec3 tl = lamp - p;
        float dl = length(tl);
        vec3 lampCol = mix(pal(0.9), vec3(1.0, 0.82, 0.55), 0.6) * (1.0 + 0.4 * uSpike);
        float lit = max(dot(n, tl / dl), 0.0) * 9.0 / (1.0 + dl * dl * 0.35);
        // Deep in the shelving there is less light.
        vec2 q = mod(p.xz, ES_CELL) - 0.5 * ES_CELL;
        float inset = ES_HALF - max(abs(q.x), abs(q.y));
        float probe = clamp(0.5 * (esMap(p + n * 0.06) / 0.06 + esMap(p + n * 0.2) / 0.2), 0.0, 1.0);
        float ao = (plain ? 1.0 : clamp(1.0 - inset * 2.5, 0.25, 1.0)) * (0.45 + 0.55 * probe);
        vec3 base;
        if (book >= 0.0) {
            base = mix(pal(fract(book * 5.3)), vec3(0.45, 0.32, 0.22), 0.35) * (0.45 + 0.3 * fract(book * 17.0));
        } else if (p.y < 0.035) {
            // Floorboards along the aisle.
            float plank = fract((abs(q.x) < abs(q.y) ? p.x : p.z) * 3.0);
            base = vec3(0.22, 0.14, 0.08) * (0.8 + 0.2 * smoothstep(0.0, 0.05, plank) * smoothstep(1.0, 0.95, plank));
        } else if (p.y > ES_CEILING - 0.05) {
            base = vec3(0.05, 0.04, 0.035);
        } else {
            base = vec3(0.16, 0.1, 0.06);
        }
        float fres = pow(1.0 - max(dot(n, -rd), 0.0), 4.0);
        col = base * (0.08 + lit * lampCol) * ao + lampCol * fres * 0.03 * ao;
        if (book >= 0.0 && spine > -0.004) {
            // The spine's gilt band, glowing with its own band of the spectrum,
            // and the beat's wave of glow running down the aisle.
            float bandAt = fract(book * 9.7);
            float gilt = smoothstep(0.06, 0.0, abs(up - 0.78)) + 0.6 * smoothstep(0.03, 0.0, abs(up - 0.18));
            float wave = t * 0.08 - uBeatPhase * 2.0;
            float swell = beatEnv * beatEnv * exp(-wave * wave * 10.0);
            float level = 0.2 + 0.9 * min(aband(bandAt), 1.2) + 0.9 * swell;
            vec3 glowCol = mix(pal(fract(book * 5.3 + 0.5)), vec3(1.0, 0.85, 0.5), 0.4);
            col += glowCol * gilt * level * (0.7 + 0.5 * treb);
            col += glowCol * 0.04 * level;
        }
        col = mix(col, dark, 1.0 - exp(-t * 0.09 * xm(uXFog)));
    }
    // The pendants themselves, and their halo in the dusty air.
    for (int j = 0; j < 3; j++) {
        vec2 c = floor((ro.xz + vec2(sin(heading), cos(heading)) * float(j) * ES_CELL) / ES_CELL + 0.5) * ES_CELL;
        vec3 lp = vec3(c.x, ES_CEILING - 0.5, c.y) - ro;
        float along = dot(lp, rd);
        if (along > 0.0 && (!hit || along < t)) {
            float miss = length(lp - rd * along);
            col += mix(pal(0.9), vec3(1.0, 0.82, 0.55), 0.6) * (1.0 + 0.4 * uSpike)
                * (0.25 / (1.0 + miss * miss * 60.0) + 0.04 / (1.0 + miss * miss * 2.0)) * exp(-along * 0.07);
        }
    }

    if (!touchIdle()) {
        col += pal(0.5 + 0.15 * sin(uTime * 0.05)) * min(touchWake(uv), 3.0) * 0.05;
    }
    col *= 0.7 + 0.3 * smoothstep(2.2, 0.5, length(uv));
    fragColor = vec4(grade(shoulder(col)) + dither(), 1.0);
}
