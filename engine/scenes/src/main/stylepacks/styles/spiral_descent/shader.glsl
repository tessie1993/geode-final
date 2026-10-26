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

// Spiral Descent: walking down a spiral staircase and looking over the rail
// into the stairwell. Turn after turn of steps, rail and wall lamps wind down
// into a glow at the bottom, and the whole well turns slowly round its axis
// because the camera is walking round it. Once a phrase the lens makes the
// Vertigo move: it leans in toward the depths while the zoom pulls back to
// keep the next turn down the same size, so the well stretches and relaxes.
//
// ---- the helix, unwound ----------------------------------------------------------
//
// One block step is modelled. A point is taken to cylindrical coordinates;
// its angle picks which of the turn's steps it is over and its height is
// taken relative to that step's tread, wrapped by one turn's pitch, so every
// step on every turn is that one block. The step and its two neighbours are
// evaluated (the neighbour one rise up is what a ray grazing a riser actually
// meets), in a frame of radius, arc length and height whose arc term has a
// gradient within 2% of 1. The handrail is the same idea on the smooth helix.
//
// The staircase is exactly periodic under one turn and one pitch, so the
// camera's walk is wrapped to a single turn and never loses precision.
//
// ---- audio ---------------------------------------------------------------------------
//
// Energy eases the descent through uFlowPhase (integrated, so it never
// jumps); uSwell sets how deep the Vertigo move goes. uBassSmooth swells the
// wall lamps, uMidSmooth lights the strip under the rail and turns its hue,
// and uTrebleSmooth lights the dust in the shaft of light down the axis. A
// beat drops a ring of light down the well (uBeatPhase, gated by the beat
// envelope); a spike turns the lamps' colour (uFormPhase, which glides).

#define SD_MAX_STEPS 96
#define SD_FAR 26.0
#define SD_TAU 6.2831853
/** Steps per turn, rise per step and the pitch of one turn. */
#define SD_STEPS 16.0
#define SD_RISE 0.15
#define SD_PITCH 2.4
/** The open well's radius, the wall's, and the block's thickness. */
#define SD_RI 1.0
#define SD_RW 3.0
#define SD_THICK 0.32
#define SD_RAIL_H 0.95
/** Lamps on the wall every this many steps. */
#define SD_LAMP_EVERY 4.0

float gMat;
float gNearLamp;
float gLampLevel;

// One block of the stair in (radius, arc, height-above-its-tread) coordinates.
float block(float r, float arc, float y, float halfArc) {
    vec3 q = vec3(r - 0.5 * (SD_RI + SD_RW), arc, y + 0.5 * SD_THICK);
    return sdBox(q, vec3(0.5 * (SD_RW - SD_RI), halfArc, 0.5 * SD_THICK));
}

/** Wraps a height into the nearest turn: -pitch/2 .. pitch/2. */
float wrapTurn(float y) {
    return y - SD_PITCH * floor(y / SD_PITCH + 0.5);
}

float map(vec3 p) {
    float r = length(p.xz);
    // atan(0, 0) is undefined; the axis is open air, so nudge it.
    float turn = atan(p.z, p.x + (r < 1e-5 ? 1e-5 : 0.0)) / SD_TAU;
    float j = floor(turn * SD_STEPS);
    float sector = SD_TAU / SD_STEPS;
    float y = wrapTurn(p.y - j * SD_RISE);
    float arc = (turn * SD_STEPS - j - 0.5) * sector * r;
    float halfArc = 0.5 * sector * r + 0.002;
    float steps = min(block(r, arc, y, halfArc),
                      min(block(r, arc - sector * r, y - SD_RISE, halfArc),
                          block(r, arc + sector * r, y + SD_RISE, halfArc)));
    float wall = SD_RW - r + 0.02;
    // The handrail rides the smooth helix; 0.93 is the cosine of its slope at the well's edge.
    float yr = wrapTurn(p.y - turn * SD_PITCH - SD_RAIL_H);
    float rail = length(vec2(r - SD_RI - 0.05, yr * 0.93)) - 0.035;
    // A baluster at the inner end of every step, from tread to rail.
    float post = max(length(vec2(r - SD_RI - 0.06, arc)) - 0.016, abs(y - 0.5 * SD_RAIL_H) - 0.5 * SD_RAIL_H);
    rail = min(rail, post);
    // The wall lamps: one every few steps, a little above head height.
    float lampSector = sector * SD_LAMP_EVERY;
    float lj = floor(turn * SD_STEPS / SD_LAMP_EVERY);
    float lampArc = (turn * SD_STEPS / SD_LAMP_EVERY - lj - 0.5) * lampSector * r;
    float ly = wrapTurn(p.y - (lj + 0.5) * SD_LAMP_EVERY * SD_RISE - 1.75);
    float lamp = length(vec3(r - (SD_RW - 0.16), lampArc, ly)) - 0.075 * gLampLevel;
    gNearLamp = min(gNearLamp, lamp);

    float d = steps;
    gMat = 0.0;
    if (wall < d) {
        d = wall;
        gMat = 1.0;
    }
    if (rail < d) {
        d = rail;
        gMat = 2.0;
    }
    if (lamp < d) {
        d = lamp;
        gMat = 3.0;
    }
    return d;
}

vec3 normalAt(vec3 p, float eps) {
    vec2 k = vec2(1.0, -1.0);
    return normalize(k.xyy * map(p + k.xyy * eps) + k.yyx * map(p + k.yyx * eps)
                   + k.yxy * map(p + k.yxy * eps) + k.xxx * map(p + k.xxx * eps));
}

/** The nearest wall lamp to p: where it hangs. */
vec3 lampNear(vec3 p) {
    float turn = atan(p.z, p.x + 1e-5) / SD_TAU;
    float lj = floor(turn * SD_STEPS / SD_LAMP_EVERY);
    float a = (lj + 0.5) * SD_LAMP_EVERY / SD_STEPS * SD_TAU;
    float h = (lj + 0.5) * SD_LAMP_EVERY * SD_RISE + 1.75;
    h += SD_PITCH * floor((p.y - h) / SD_PITCH + 0.5);
    return vec3(cos(a), 0.0, sin(a)) * (SD_RW - 0.16) + vec3(0.0, h, 0.0);
}

/** Soft rolloff toward white, applied before grade() so its controls keep their meaning. */
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
    float ringGate = clamp(uBeat, 0.0, 1.0) * clamp(uBeatResponse, 0.0, 2.0);
    gLampLevel = 1.0 + 0.35 * bass;

    // ---- the walk down ---------------------------------------------------------------
    //
    // Clockwise and down, one turn per pitch, wrapped to a single turn: the
    // stair is identical one turn lower, so the wrap cannot be seen.
    float walk = mod(uTime * 0.16 + uFlowPhase * 1.3 + uCamTravel * 0.4, SD_TAU);
    float angle = -walk;
    vec3 ro = vec3(cos(angle), 0.0, sin(angle)) * 0.8 + vec3(0.0, angle / SD_TAU * SD_PITCH + 1.5, 0.0);
    // Leaning out over the rail, the head is over the void: look down the
    // well, tipped a little toward the axis, so its depths sit near the middle
    // of the frame and the turns below ring them.
    vec3 target = vec3(0.0, ro.y - 5.0, 0.0) + vec3(cos(angle - 0.6), 0.0, sin(angle - 0.6)) * 0.15;
    vec3 fwd = normalize(target - ro);
    vec3 right = normalize(cross(vec3(0.0, 1.0, 0.0), fwd));
    vec3 up = cross(fwd, right);

    // ---- the Vertigo move ----------------------------------------------------------------
    //
    // Lean in along the view while the focal length shortens in proportion,
    // so the subject a turn and a half down keeps its size.
    float subject = length(target - ro);
    // One-sided: from the resting place toward the depths and back, so the
    // camera never backs into the turn of stair above its head.
    float lean = (0.3 + 0.25 * swell) * (0.5 - 0.5 * cos(uTime * (SD_TAU / 32.0) + uFlowPhase * 0.8));
    float focal = 1.05 * (subject - lean) / subject;
    lean += uCamPush * 0.7;
    ro += fwd * lean;

    vec3 rd = mat3(right, up, fwd) * steerLook(normalize(vec3(uv, focal)));
    rd = uCamRot * rd;

    // ---- the march ---------------------------------------------------------------------------
    float budget = clamp(uSteps * 0.75, 24.0, float(SD_MAX_STEPS));
    gNearLamp = 1e9;
    float t = 0.02;
    float hitT = -1.0;
    float steps = 0.0;
    for (int i = 0; i < SD_MAX_STEPS; i++) {
        if (float(i) >= budget || t > SD_FAR) break;
        float d = map(ro + rd * t);
        float eps = 0.0012 * t + 0.0006;
        if (d < eps) {
            hitT = t;
            break;
        }
        t += d * 0.85;
        steps += 1.0;
    }
    float nearLamp = gNearLamp;

    vec3 lampCol = mix(pal(0.9 + 0.08 * sin(SD_TAU * uFormPhase)), vec3(1.0), 0.3);
    vec3 stripCol = mix(pal(0.5 + 0.1 * mid), vec3(1.0), 0.2);
    // The deep: the well fades into a glow far below.
    float down = max(-rd.y, 0.0);
    vec3 deep = mix(pal(0.32), vec3(0.05, 0.06, 0.09), 0.5) * 0.3 + lampCol * 0.55 * pow(down, 14.0);

    vec3 col;
    if (hitT > 0.0) {
        vec3 p = ro + rd * hitT;
        map(p);
        float mat = gMat;
        vec3 n = normalAt(p, max(0.0015 * hitT, 0.0008));
        vec3 lp = lampNear(p);
        vec3 toLamp = lp - p;
        float dl = length(toLamp);
        float lamp = max(dot(n, toLamp / dl), 0.0) * gLampLevel * 2.4 / (1.0 + 0.35 * dl * dl);
        // Daylight falling down the well from far above.
        float sky = 0.3 + 0.35 * max(n.y, 0.0);
        float ao = clamp(1.0 - steps / budget * 1.1, 0.25, 1.0);
        vec3 albedo = mix(vec3(0.62, 0.57, 0.5), pal(0.9), 0.2);
        if (mat == 1.0) {
            // Coursed masonry on the wall: eight courses to a turn (an even
            // number, so the staggered joints also repeat exactly one turn down).
            float a = atan(p.z, p.x) * SD_RW;
            float rows = p.y * (8.0 / SD_PITCH);
            float course = smoothstep(0.02, 0.05, abs(fract(rows) - 0.5));
            float joint = smoothstep(0.02, 0.06, abs(fract(a * 1.5 + 0.5 * floor(rows)) - 0.5));
            albedo *= 0.55 + 0.45 * course * joint;
        } else if (mat == 2.0) {
            albedo = vec3(0.12, 0.1, 0.09);
        }
        col = albedo * (sky * mix(pal(0.55), vec3(1.0), 0.75) * 0.6 + lampCol * lamp) * ao;
        // A rim of daylight on edges turned away from the lens, and the worn
        // stone's sheen toward the nearest lamp.
        float rim = pow(clamp(1.0 + dot(rd, n), 0.0, 1.0), 4.0);
        col += mix(pal(0.55), vec3(1.0), 0.7) * rim * 0.12 * ao;
        col += lampCol * pow(max(dot(reflect(rd, n), toLamp / dl), 0.0), 24.0) * 0.18 * gLampLevel / (1.0 + 0.35 * dl * dl);
        if (mat == 0.0) {
            // The steps' lit nosings and the light strip along the well's edge:
            // the lines that draw the spiral all the way down.
            float r = length(p.xz);
            float turn = atan(p.z, p.x + 1e-5) / SD_TAU;
            float j = floor(turn * SD_STEPS);
            float y = wrapTurn(p.y - j * SD_RISE);
            float edge = abs(fract(turn * SD_STEPS) - 0.5) * (SD_TAU / SD_STEPS) * r;
            float nosing = exp(-abs(0.5 * (SD_TAU / SD_STEPS) * r - edge) * 60.0) * exp(-abs(y) * 40.0);
            float rim = exp(-max(r - SD_RI, 0.0) * 30.0) * smoothstep(-0.12, 0.0, y);
            col += stripCol * (0.25 * nosing + 0.5 * rim) * (0.6 + 0.6 * mid);
        }
        if (mat == 2.0) {
            // The light strip along the rail's inner face, so every turn below
            // draws a glowing helix down the well; and the brass catching the lamps.
            float inward = -dot(n.xz, normalize(p.xz + vec2(1e-5)));
            col += stripCol * (0.3 + 0.5 * mid) * smoothstep(0.1, 0.8, inward);
            col += lampCol * 0.25 * pow(max(dot(reflect(rd, n), toLamp / dl), 0.0), 16.0);
        }
        if (mat == 3.0) col = lampCol * (1.3 + 0.3 * bass);
        col = mix(col, deep, 1.0 - exp(-hitT * 0.075));
    } else {
        col = deep;
    }
    // Lamp halos, from the nearest the ray passed.
    col += lampCol * 0.35 * gLampLevel * exp(-max(nearLamp, 0.0) * 9.0);

    // ---- light in the well ---------------------------------------------------------------
    //
    // The shaft of daylight down the axis: the ray's closest approach to the
    // axis, in closed form, with dust that catches it on the treble.
    float tEnd = hitT > 0.0 ? hitT : SD_FAR;
    vec2 o2 = ro.xz;
    vec2 d2 = rd.xz;
    float tAxis = clamp(-dot(o2, d2) / max(dot(d2, d2), 1e-5), 0.0, tEnd);
    float axisDist = length(o2 + d2 * tAxis);
    vec3 shaftPoint = ro + rd * tAxis;
    float shaft = exp(-axisDist * axisDist * 2.5) * exp(-tAxis * 0.05);
    float dust = motionNoise(vec2(atan(shaftPoint.z, shaftPoint.x + 1e-5) * 3.0, (shaftPoint.y - ro.y) * 4.0 + uTime * 0.3));
    col += mix(pal(0.6), vec3(1.0), 0.6) * shaft * (0.08 + 0.12 * treb * smoothstep(0.6, 0.95, dust));
    // The beat's ring, dropping down the well: a band of light at the well's
    // edge whose depth below the camera follows uBeatPhase.
    float ringY = ro.y - 0.8 - uBeatPhase * 9.0;
    float tRing = abs(rd.y) > 1e-3 ? (ringY - ro.y) / rd.y : -1.0;
    if (tRing > 0.0 && tRing < tEnd) {
        float rr = length((ro + rd * tRing).xz);
        float band = exp(-(rr - SD_RI) * (rr - SD_RI) * 40.0);
        col += stripCol * band * ringGate * 0.7 * exp(-tRing * 0.06);
    }

    if (!touchIdle()) {
        col += stripCol * min(touchWake(uv), 3.0) * 0.04;
    }
    col *= 0.72 + 0.28 * smoothstep(2.2, 0.4, length(uv));
    fragColor = vec4(grade(filmic(col)), 1.0);
}
