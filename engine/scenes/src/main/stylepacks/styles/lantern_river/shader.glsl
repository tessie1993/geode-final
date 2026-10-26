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

// Lantern River: drifting down a dark river at night among floating paper
// lanterns.
//
// THE WORLD. The river is the plane y = 0, met analytically; its banks are two
// walls at x = +-LR_BANK whose tops are a line of hills and trees, also met
// analytically. The only marched geometry is the lanterns: small paper boxes
// floating on the water, one per cell of a repeated grid, each cell with its
// own offset, turn, bob and colour (some cells are empty), and a sparser layer
// of sky lanterns hanging higher up. Every lantern is a light: the march sums
// a glow off the paper as it passes, so each one sits in its own halo.
//
// THE WATER. Where the ray meets the river it is turned by a rippled normal
// and marched again, shorter, so the lanterns, the moon and the banks are
// mirrored. The ripples tilt the normal far more along the river than across
// it, which is what stretches each reflection into a long wavering column,
// the way lights on dark water look. Each lantern also casts a warm pool of
// light on the water around it.
//
// THE DRIFT. The camera floats a little above the water, carried downstream
// along a gentle meander. It bobs, pitches and rolls a few hundredths of a
// radian on slow incommensurate clocks, the way a small boat does.
//
// AUDIO. Energy sets the drift speed (uFlowPhase, integrated: it eases). A
// beat sends a swell of glow rolling out through the lanterns from the boat:
// uBeatPhase is how far it has travelled, the beat envelope how strong it is,
// and uSpike lifts every lantern a little with a rise, so glow swells and
// never flashes. Bass deepens the ripples, mids warm the paper, treble makes
// the stars twinkle.
//
// WHY THE ESTIMATE IS SAFE. A lantern is not centred in its cell, so the
// nearest one may be a neighbour's. Every lantern stays within LR_EXTENT of
// its cell's centre, so a neighbour's lantern is at least the distance to the
// cell border plus the gap between that extent and the border; the estimate
// takes the smaller of that and the own lantern's distance, which can never
// overestimate.

#define LR_MAX_STEPS 72
#define LR_REFL_STEPS 32
#define LR_FOCAL 1.35
#define LR_FAR 48.0
#define LR_BANK 10.5

const vec2 LR_CELL = vec2(2.4, 2.8);
/** How far a water lantern's surface can reach from its cell's centre (offset plus a full-size lantern). */
const float LR_EXTENT = 0.62 + 0.45;
const vec2 LR_SKY_CELL = vec2(5.0, 6.0);
const float LR_SKY_EXTENT = 1.2 + 0.9;

float gSeed;
float gMat;
float gEmit;
vec3 gCentre;

float lrSize;

/** Distance to the cell's border in xz, plus the gap a neighbour's lantern keeps from it. */
float lrNeighbour(vec2 local, vec2 cell, float extent) {
    vec2 border = 0.5 * cell - abs(local);
    return min(border.x, border.y) + (0.5 * min(cell.x, cell.y) - extent);
}

/** The water lanterns: paper boxes on a dark base, floating on y = 0. */
float lrWater(vec3 p) {
    vec2 cell = floor(p.xz / LR_CELL);
    vec2 local = p.xz - (cell + 0.5) * LR_CELL;
    float h = motionHash(cell + 0.37);
    float centreX = (cell.x + 0.5) * LR_CELL.x;
    float d = 1e3;
    if (h > 0.2 && abs(centreX) < LR_BANK - 1.8) {
        vec2 off = (vec2(motionHash(cell + 4.1), motionHash(cell + 8.3)) - 0.5) * vec2(1.0, 1.24);
        float bob = 0.035 * sin(uTime * 1.3 + h * 40.0);
        vec3 q = vec3(local.x - off.x, p.y - bob, local.y - off.y);
        q.xz = rot2(h * 6.2831 + uTime * 0.12 * (h - 0.5)) * q.xz;
        float s = lrSize;
        float body = sdBox(q - vec3(0.0, 0.25 * s, 0.0), vec3(0.15, 0.19, 0.15) * s) - 0.008;
        float base = sdBox(q - vec3(0.0, 0.03 * s, 0.0), vec3(0.2, 0.03, 0.2) * s);
        d = min(body, base);
        if (d < gEmit) {
            gEmit = body;
            gSeed = h;
            gMat = body < base ? 0.0 : 1.0;
            gCentre = vec3(centreX + off.x, 0.25 * s + bob, (cell.y + 0.5) * LR_CELL.y + off.y);
        }
    }
    return min(d, lrNeighbour(local, LR_CELL, LR_EXTENT));
}

/** The sky lanterns: taller paper shades hanging in the air above the river. */
float lrSky(vec3 p) {
    vec2 cell = floor(p.xz / LR_SKY_CELL);
    vec2 local = p.xz - (cell + 0.5) * LR_SKY_CELL;
    float h = motionHash(cell + 21.7);
    float centreX = (cell.x + 0.5) * LR_SKY_CELL.x;
    float d = 1e3;
    if (h > 0.5 && abs(centreX) < LR_BANK) {
        vec2 off = (vec2(motionHash(cell + 2.9), motionHash(cell + 6.6)) - 0.5) * 2.4;
        float y = 2.2 + 3.5 * motionHash(cell + 13.1) + 0.12 * sin(uTime * 0.6 + h * 30.0);
        vec3 q = vec3(local.x - off.x, p.y - y, local.y - off.y);
        float s = lrSize;
        // Wider at the top than the bottom, like a paper sky lantern.
        float taper = 1.0 + 0.25 * clamp(q.y / (0.4 * s), -1.0, 1.0);
        d = sdBox(q, vec3(0.24 * taper, 0.4, 0.24 * taper) * s) - 0.04 * s;
        if (d < gEmit) {
            gEmit = d;
            gSeed = h;
            gMat = 2.0;
            gCentre = vec3(centreX + off.x, y, (cell.y + 0.5) * LR_SKY_CELL.y + off.y);
        }
    }
    return min(d, lrNeighbour(local, LR_SKY_CELL, LR_SKY_EXTENT));
}

float lrMap(vec3 p) {
    gEmit = 1e3;
    return min(lrWater(p), lrSky(p));
}

vec3 lrNormal(vec3 p, float eps) {
    vec2 k = vec2(1.0, -1.0);
    return normalize(k.xyy * lrMap(p + k.xyy * eps) + k.yyx * lrMap(p + k.yyx * eps)
                   + k.yxy * lrMap(p + k.yxy * eps) + k.xxx * lrMap(p + k.xxx * eps));
}

/** A lantern's colour: mostly the warm end of the palette, now and then a cooler one. */
vec3 lrColour(float seed, float mid) {
    float hue = 0.88 + 0.12 * fract(seed * 7.3);
    if (fract(seed * 13.7) > 0.82) hue = 0.12 + 0.35 * fract(seed * 3.1);
    return mix(pal(hue), vec3(1.0, 0.85, 0.6), 0.25 + 0.15 * mid);
}

/**
 * How brightly a lantern burns. The beat's swell rolls outward from the boat
 * (uBeatPhase is how far it has gone, the envelope how strong it is) and uSpike
 * lifts every lantern a little; both rise over a tenth of a second or more.
 */
float lrGlow(vec3 centre, vec3 ro, float beatEnv) {
    float far = length(centre - ro) * 0.07 - uBeatPhase * 1.8;
    float wave = beatEnv * beatEnv * exp(-far * far * 8.0);
    return 0.9 + 0.25 * min(uSwell, 1.2) + 0.6 * wave + 0.3 * uSpike;
}

float lrBankHeight(float z) {
    return 0.9 + 1.5 * motionNoise(vec2(z * 0.07, 1.0)) + 0.5 * motionNoise(vec2(z * 0.8, 5.0));
}

/** The banks along the ray: returns the distance, or -1 if the ray passes over them. */
float lrBank(vec3 ro, vec3 rd) {
    if (abs(rd.x) < 1e-4) return -1.0;
    float side = sign(rd.x);
    float tb = (side * LR_BANK - ro.x) / rd.x;
    if (tb <= 0.0) return -1.0;
    vec3 p = ro + rd * tb;
    return (p.y > -0.02 && p.y < lrBankHeight(p.z + side * 31.0)) ? tb : -1.0;
}

vec3 lrBankColour(vec3 ro, vec3 rd, float tb) {
    vec3 p = ro + rd * tb;
    float side = sign(rd.x);
    float top = lrBankHeight(p.z + side * 31.0);
    vec3 col = vec3(0.012, 0.018, 0.03) + pal(0.4) * 0.012;
    // Moonlight catching the crest of the tree line.
    col += pal(0.42) * 0.05 * smoothstep(top - 0.25, top, p.y);
    // Now and then a lit window on the shore.
    vec2 w = vec2(p.z * 0.8, p.y * 3.0);
    vec2 wc = floor(w);
    float lit = step(0.93, motionHash(wc + side * 5.0)) * step(p.y, top - 0.3);
    vec2 wf = abs(fract(w) - 0.5);
    col += pal(0.92) * lit * smoothstep(0.3, 0.1, max(wf.x, wf.y * 0.7)) * 0.6;
    return col;
}

vec3 lrNight(vec3 rd, vec3 moon, float treb) {
    float h = max(rd.y, 0.0);
    vec3 col = mix(pal(0.4) * 0.06 + vec3(0.01, 0.015, 0.03), vec3(0.004, 0.006, 0.02), smoothstep(0.0, 0.5, h));
    // Stars on a grid in direction space, twinkling with the treble.
    vec2 sp = vec2(atan(rd.x, rd.z) * 60.0, rd.y * 60.0);
    vec2 sc = floor(sp);
    float star = motionHash(sc + 3.3);
    vec2 sf = fract(sp) - 0.5 - (vec2(motionHash(sc + 1.1), motionHash(sc + 7.7)) - 0.5) * 0.6;
    float twinkle = 0.7 + 0.3 * sin(uTime * (2.0 + 5.0 * star) + star * 50.0) * (0.4 + 0.6 * treb);
    col += vec3(0.9, 0.92, 1.0) * step(0.9, star) * smoothstep(0.12, 0.0, length(sf)) * twinkle * smoothstep(0.0, 0.08, h);
    float m = max(dot(rd, moon), 0.0);
    col += mix(pal(0.42), vec3(0.9), 0.6) * (smoothstep(0.9993, 0.9997, m) * 1.2 + pow(m, 80.0) * 0.12 + pow(m, 8.0) * 0.03);
    return col;
}

/** The river's surface slope at xz: long ripples running mostly across the flow, so reflections stretch. */
vec2 lrRipple(vec2 xz, float amp) {
    float t = uTime;
    float gz = 3.1 * 0.6 * cos(xz.y * 3.1 + t * 1.7) + 5.3 * 0.3 * cos(xz.y * 5.3 - xz.x * 1.1 + t * 2.3)
             + 1.7 * 0.2 * cos(xz.x * 2.0 + xz.y * 1.7 - t * 1.1);
    float gx = -1.1 * 0.3 * cos(xz.y * 5.3 - xz.x * 1.1 + t * 2.3) + 2.0 * 0.2 * cos(xz.x * 2.0 + xz.y * 1.7 - t * 1.1);
    float n = motionNoise(xz * vec2(1.5, 4.0) + vec2(0.0, t * 0.6));
    return vec2(gx * 0.35, gz + 2.0 * (n - 0.5)) * amp;
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
    float bass = min(uBassSmooth, 1.3);
    float mid = min(uMidSmooth, 1.3);
    float treb = min(uTrebleSmooth, 1.3);
    float beatEnv = clamp(uBeat, 0.0, 1.0) * clamp(uBeatResponse, 0.0, 2.0);
    lrSize = clamp(xm(uXSize), 0.5, 1.6);

    vec3 rdl = normalize(vec3(uv, LR_FOCAL));
    vec2 look = touchLook();
    if (look != vec2(0.0)) rdl = rotY(look.x * 0.6) * rotX(-look.y * 0.4) * rdl;
    vec3 camOff = vec3(0.0);
    cameraFlight(camOff, rdl);

    // Downstream, INTEGRATED: the Speed control through uTime, loudness through uFlowPhase.
    float z = uTime * 0.55 + uFlowPhase * 2.4 + camOff.z;
    vec3 ro = vec3(1.6 * sin(z * 0.045), 0.85 + 0.05 * sin(uTime * 0.9) + 0.03 * sin(uTime * 1.7), z);
    // The boat: heading follows the meander, with a slow look around, a pitch and a roll.
    float yaw = atan(0.072 * cos(z * 0.045)) + 0.14 * sin(uTime * 0.07);
    float pitch = 0.11 + 0.02 * sin(uTime * 0.73);
    float roll = 0.035 * sin(uTime * 0.61) + 0.02 * sin(uTime * 1.13);
    vec3 rd = rotY(yaw) * rotX(pitch) * rotZ(roll) * rdl;

    vec3 moon = normalize(vec3(-0.16, 0.24, 1.0));
    float tWater = rd.y < 0.0 ? -ro.y / rd.y : 1e4;
    float tBank = lrBank(ro, rd);
    float tEnd = min(min(tWater, LR_FAR), tBank > 0.0 ? tBank : 1e4);

    // ---- the lanterns, and the glow off them along the ray --------------
    float budget = uSteps * (float(LR_MAX_STEPS) / 128.0);
    float t = 0.05;
    float hitT = -1.0;
    vec3 glow = vec3(0.0);
    for (int i = 0; i < LR_MAX_STEPS; i++) {
        if (float(i) >= budget || t > tEnd) break;
        vec3 p = ro + rd * t;
        float d = lrMap(p);
        float eps = 0.0015 + 0.001 * t;
        if (d < eps) {
            hitT = t;
            break;
        }
        float adv = max(d * 0.9, eps);
        // Summed in proportion to the step taken, so the halo does not change with Detail.
        glow += lrColour(gSeed, mid) * lrGlow(gCentre, ro, beatEnv) * exp(-gEmit * 5.0) * min(adv, 0.6) * 0.5;
        t += adv;
    }

    vec3 col;
    if (hitT > 0.0) {
        vec3 p = ro + rd * hitT;
        lrMap(p);
        float seed = gSeed;
        float mat = gMat;
        vec3 centre = gCentre;
        vec3 n = lrNormal(p, 0.002 + 0.001 * hitT);
        vec3 lc = lrColour(seed, mid);
        float burn = lrGlow(centre, ro, beatEnv);
        if (mat < 0.5 || mat > 1.5) {
            // Paper, lit from inside: brightest where it faces the eye, a
            // little darker at the frame, the ribs of the frame darker still.
            vec3 q = p - centre;
            float face = 0.55 + 0.45 * abs(dot(n, rd));
            float frame;
            if (mat > 1.5) {
                // A sky lantern's paper is ribbed in rings.
                frame = smoothstep(0.06, 0.0, abs(fract(q.y * 5.0 / lrSize) - 0.5) - 0.42);
            } else {
                // A water lantern's wooden frame: its four uprights, its top
                // and bottom rails and a rail round the middle, in the
                // lantern's own turned frame.
                vec2 qa = abs(rot2(seed * 6.2831 + uTime * 0.12 * (seed - 0.5)) * q.xz) / lrSize;
                float upright = smoothstep(0.018, 0.0, 0.15 - min(qa.x, qa.y));
                float ay = abs(q.y / lrSize);
                float rail = smoothstep(0.018, 0.0, 0.19 - ay) + smoothstep(0.012, 0.0, ay - 0.004);
                frame = max(upright, rail);
            }
            col = lc * burn * (0.45 + 0.6 * face) * (1.0 - 0.6 * frame);
            col += vec3(1.0, 0.95, 0.85) * pow(face, 6.0) * 0.25 * burn * (1.0 - frame);
        } else {
            // The dark wooden base, lit by its own lantern from above.
            col = lc * 0.12 * burn * (0.4 + 0.6 * max(n.y, 0.0));
        }
        col = mix(col, vec3(0.01, 0.015, 0.03), 1.0 - exp(-hitT * 0.03 * xm(uXFog)));
    } else if (tBank > 0.0 && tBank < tWater) {
        col = lrBankColour(ro, rd, tBank);
    } else if (tWater < LR_FAR) {
        // ---- the river: a rippled mirror, and the lantern light on it ----
        vec3 pw = ro + rd * tWater;
        vec2 slope = lrRipple(pw.xz, 0.035 + 0.04 * bass);
        vec3 n = normalize(vec3(-slope.x, 1.0, -slope.y));
        vec3 rr = reflect(rd, n);
        rr.y = max(rr.y, 0.003);
        float fres = 0.04 + 0.96 * pow(1.0 - max(dot(-rd, n), 0.0), 5.0);

        vec3 refl;
        float tb = lrBank(pw, rr);
        float rBudget = uSteps * (float(LR_REFL_STEPS) / 128.0);
        float tr = 0.03;
        float rHit = -1.0;
        vec3 rGlow = vec3(0.0);
        float rEnd = min(LR_FAR - tWater, tb > 0.0 ? tb : 1e4);
        for (int i = 0; i < LR_REFL_STEPS; i++) {
            if (float(i) >= rBudget || tr > rEnd) break;
            float d = lrMap(pw + rr * tr);
            if (d < 0.004 + 0.002 * tr) {
                rHit = tr;
                break;
            }
            float adv = max(d, 0.02);
            rGlow += lrColour(gSeed, mid) * lrGlow(gCentre, ro, beatEnv) * exp(-gEmit * 4.0) * min(adv, 0.8) * 0.4;
            tr += adv;
        }
        if (rHit > 0.0) {
            refl = lrColour(gSeed, mid) * lrGlow(gCentre, ro, beatEnv) * 1.1;
        } else if (tb > 0.0) {
            refl = lrBankColour(pw, rr, tb);
        } else {
            refl = lrNight(rr, moon, treb);
        }
        refl += rGlow;
        col = vec3(0.004, 0.008, 0.016) + refl * fres * 1.6;

        // Warm pools of light on the water round the nearest lanterns.
        vec2 near = floor(pw.xz / LR_CELL - 0.5);
        vec3 pool = vec3(0.0);
        for (int j = 0; j < 4; j++) {
            vec2 c = near + vec2(float(j % 2), float(j / 2));
            float h = motionHash(c + 0.37);
            float cx = (c.x + 0.5) * LR_CELL.x;
            if (h > 0.2 && abs(cx) < LR_BANK - 1.8) {
                vec2 off = (vec2(motionHash(c + 4.1), motionHash(c + 8.3)) - 0.5) * vec2(1.0, 1.24);
                vec2 lp = (c + 0.5) * LR_CELL + off;
                float r = length(pw.xz - lp);
                // A ring of ripples off each lantern's bob.
                float ring = 0.75 + 0.25 * sin(r * 16.0 - uTime * 3.0 + h * 20.0) * (0.5 + bass);
                vec3 lcp = vec3(lp.x, 0.25 * lrSize, lp.y);
                pool += lrColour(h, mid) * lrGlow(lcp, ro, beatEnv) * ring * 0.3 / (1.0 + r * r * 4.0);
            }
        }
        col += pool;
        col = mix(col, vec3(0.01, 0.015, 0.03), 1.0 - exp(-tWater * 0.025 * xm(uXFog)));
    } else {
        col = lrNight(rd, moon, treb);
    }
    col += glow;
    // A thin mist over the water, faintly lit.
    col += pal(0.9) * 0.02 * exp(-max(rd.y, 0.0) * 12.0);

    if (!touchIdle()) {
        col += pal(0.5 + 0.15 * sin(uTime * 0.05)) * min(touchWake(uv), 3.0) * 0.05;
    }
    col *= 0.75 + 0.25 * smoothstep(2.2, 0.5, length(uv));
    fragColor = vec4(grade(shoulder(col)) + dither(), 1.0);
}
