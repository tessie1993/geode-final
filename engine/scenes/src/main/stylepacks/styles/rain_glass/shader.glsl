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

// Rain Glass: looking out through a rainy window at a city at night. The city
// is a field of out-of-focus lights - windows, street lamps, signs, cars
// crawling along a road - and the glass in front of it is beaded with drops,
// some resting, some sliding down and wiping clean trails through the rest.
// Each drop is a tiny lens holding an upside-down image of the city. Once a
// phrase the lens racks focus: from the drops to the city and back, so the
// glass blurs away and the lights tighten from soft discs to points.
//
// ---- a single pass of layers ----------------------------------------------------------
//
// Nothing is traced. The city is drawn as bokeh: each light a disc whose
// radius is the larger of its own size and the city's blur, with its light
// spread over the disc (energy kept, so a defocused light dims as it grows)
// and a slightly brighter rim, the way a real aperture draws it. The drops are
// drawn on the glass with edges as wide as the glass's blur, and inside each
// the city is drawn again through the drop: inverted and shrunk about the
// drop's centre. The camera's small sway moves the city against the glass
// (parallax: the glass is near, the city far).
//
// ---- audio ------------------------------------------------------------------------------
//
// Energy sets how fast the sliding drops run, through uFlowPhase (integrated,
// so a drop never jumps). uBassSmooth breathes the city's lights within a
// small range; uTrebleSmooth twinkles the far windows; uMidSmooth and a spike
// (uFormPhase, which glides) turn the signs' colours. A beat sends a ripple
// out across the glass from the middle (uBeatPhase, gated by the beat
// envelope) that makes the resting drops shiver. The rack follows the phrase
// clock, never a transient.

#define RG_TAU 6.2831853
#define RG_COLUMNS 14.0

float gCityBlur;
float gGlassBlur;
float gBreath;
float gTwinkle;
float gSignHue;

/**
 * One defocused light: a disc of radius max(size, blur), its light spread
 * over it. `reach` caps the radius at what the caller's cell search covers,
 * so a disc is never cut off at the edge of a cell nobody looked in.
 */
float bokeh(vec2 p, vec2 c, float size, float reach) {
    float r = max(size, min(gCityBlur, reach));
    float d = length(p - c);
    float edge = max(0.0025, r * 0.12);
    float disc = 1.0 - smoothstep(r - edge, r + edge, d);
    float rim = 1.0 + 0.35 * smoothstep(r * 0.55, r, d);
    // Between keeping energy (size^2/r^2) and keeping brightness: a lens
    // spreads a light, but the eye still reads a defocused lamp as a lamp.
    return disc * rim * pow(size / r, 1.2);
}

// The city at city coordinates q (x across, y up; the horizon near y = 0).
vec3 city(vec2 q) {
    float b = gCityBlur;
    // Night sky, and the glow the city throws up into it.
    vec3 col = mix(mix(pal(0.3), vec3(0.03, 0.04, 0.09), 0.7) * 0.5, mix(pal(0.9), vec3(0.4, 0.25, 0.15), 0.5) * 0.45,
                   smoothstep(0.7, -0.3, q.y));
    // The skyline: blocks of dark buildings, their edges as soft as the blur.
    float colX = floor(q.x * 7.0);
    float roof = -0.05 + 0.5 * motionHash(vec2(colX, 3.0)) * motionHash(vec2(colX, 7.0));
    float body = 1.0 - smoothstep(roof - b, roof + b, q.y);
    col = mix(col, vec3(0.015, 0.018, 0.03), body * 0.85);
    vec3 light = vec3(0.0);
    // Windows: many small warm lights inside the blocks.
    for (int j = -1; j <= 1; j++) {
        for (int i = -1; i <= 1; i++) {
            vec2 cell = floor(q * vec2(24.0, 30.0)) + vec2(float(i), float(j));
            float h = motionHash(cell);
            if (h < 0.55) continue;
            vec2 c = (cell + 0.5) / vec2(24.0, 30.0);
            float inside = step(c.y, -0.05 + 0.5 * motionHash(vec2(floor(c.x * 7.0), 3.0)) * motionHash(vec2(floor(c.x * 7.0), 7.0)));
            float tw = 1.0 + gTwinkle * 0.5 * sin(uTime * 3.0 + h * 50.0);
            light += mix(pal(0.88), vec3(1.0, 0.85, 0.6), 0.6) * bokeh(q, c, 0.006, 0.03) * inside * tw * (h - 0.4) * 1.6;
        }
    }
    // Street lamps along the road, and signs above it.
    for (int i = -1; i <= 1; i++) {
        float cell = floor(q.x * 5.0) + float(i);
        float h = motionHash(vec2(cell, 11.0));
        vec2 lamp = vec2((cell + 0.5) / 5.0, -0.42 + 0.03 * h);
        light += mix(pal(0.86), vec3(1.0, 0.8, 0.5), 0.5) * bokeh(q, lamp, 0.014, 0.19) * 1.4;
        if (h > 0.3) {
            vec2 signPos = vec2((cell + 0.3 + 0.4 * h) / 5.0, -0.18 + 0.25 * motionHash(vec2(cell, 23.0)));
            light += pal(gSignHue + 0.6 * h) * bokeh(q, signPos, 0.024, 0.19) * 1.6;
        }
    }
    // Cars on the road: white heads one way, red tails the other.
    for (int lane = 0; lane < 2; lane++) {
        float dir = lane == 0 ? 1.0 : -1.0;
        float x = q.x * 4.0 - dir * uTime * (0.25 + 0.1 * float(lane));
        for (int i = -1; i <= 1; i++) {
            float cell = floor(x) + float(i);
            float h = motionHash(vec2(cell, 31.0 + float(lane)));
            if (h < 0.4) continue;
            float cx = (cell + 0.5 + dir * uTime * (0.25 + 0.1 * float(lane))) / 4.0;
            vec2 c = vec2(cx, -0.55 - 0.05 * float(lane));
            vec3 tint = lane == 0 ? vec3(1.0, 0.95, 0.85) : mix(pal(0.0), vec3(1.0, 0.1, 0.05), 0.6);
            light += tint * (bokeh(q, c - vec2(0.012, 0.0), 0.006, 0.23) + bokeh(q, c + vec2(0.012, 0.0), 0.006, 0.23)) * 1.2;
        }
    }
    col += light * gBreath * 1.6;
    return col;
}

/** A resting drop in a glass cell: xy centre, z radius (0 for none). */
vec3 restingDrop(vec2 cell) {
    float h = motionHash(cell * 1.31 + 5.0);
    if (h < 0.4) return vec3(0.0);
    vec2 c = (cell + 0.3 + 0.4 * vec2(motionHash(cell + 1.7), motionHash(cell + 9.3))) * 0.08;
    return vec3(c, mix(0.006, 0.024, pow(motionHash(cell + 4.4), 1.5)));
}

vec3 filmic(vec3 c) {
    c = max(c, 0.0) * 1.15;
    return c * (1.0 + c * 0.25) / (1.0 + c);
}

void main() {
    vec2 uv = cameraView();
    float bass = clamp(uBassSmooth, 0.0, 1.5);
    float shiver = clamp(uBeat, 0.0, 1.0) * clamp(uBeatResponse, 0.0, 2.0);
    gBreath = 0.9 + 0.12 * bass;
    gTwinkle = clamp(uTrebleSmooth, 0.0, 1.5);
    gSignHue = 0.75 + 0.1 * clamp(uMidSmooth, 0.0, 1.5) + 0.12 * sin(RG_TAU * uFormPhase);

    // ---- the rack ----------------------------------------------------------------------
    //
    // Holds on the glass, racks to the city, holds, racks back: a plateau
    // wave on the phrase clock.
    float phrase = uTime / 20.0 + uFlowPhase * 0.25;
    float focus = smoothstep(0.25, 0.75, 0.5 + 0.5 * sin(RG_TAU * phrase));
    gCityBlur = mix(0.075, 0.006, focus);
    gGlassBlur = mix(0.0012, 0.02, focus);

    // ---- the camera's sway: the city moves against the glass -----------------------------
    vec2 sway = vec2(0.03 * sin(uTime * 0.13) + 0.015 * sin(uTime * 0.31), 0.02 * sin(uTime * 0.17 + 1.0));
    // The Camera's orbit looks across the city, travel slides along it,
    // push-in leans toward the glass.
    vec3 look = uCamRot * vec3(0.0, 0.0, 1.0);
    sway += look.xy * 0.6 + vec2(uCamTravel * 0.05, 0.0);
    vec2 glassUv = uv / (1.0 + 0.5 * uCamPush);
    if (!touchIdle()) {
        // A finger leans the head: the far city slides against the near glass.
        sway += touchAnchor() * touchStrength() * 0.12;
    }
    vec2 cityQ = uv * 0.85 + sway + vec2(0.0, 0.1);
    vec3 col = city(cityQ);

    // ---- the drops ----------------------------------------------------------------------
    //
    // Sliding drops: one per column, running down at their own speed and
    // leaving a clean trail; resting drops everywhere the trails have not wiped.
    float slide = uFlowPhase * 0.9 + uTime * 0.04;
    float colIdx = floor(glassUv.x * RG_COLUMNS * 0.5);
    float seed = motionHash(vec2(colIdx, 51.0));
    float colX = (colIdx + 0.3 + 0.4 * seed) / (RG_COLUMNS * 0.5);
    float runY = 1.2 - fract(slide * (0.25 + 0.35 * seed) + seed) * 2.6;
    // The drop wanders a little as it runs, the way water picks a path.
    colX += 0.01 * sin(runY * 9.0 + seed * 20.0);
    vec2 runner = vec2(colX, runY);
    float runR = 0.026 + 0.012 * seed;
    float trail = step(runY, glassUv.y) * (1.0 - smoothstep(0.0, runR * 0.9, abs(glassUv.x - colX))) * smoothstep(runY + 0.9, runY, glassUv.y);

    vec3 drop = vec3(0.0);
    float dropMask = 0.0;
    vec2 dropC = vec2(0.0);
    float dropR = 1.0;
    // The runner first: it is the biggest thing on the glass.
    vec2 dv = (glassUv - runner) * vec2(1.0, 0.8);
    float dRun = length(dv);
    if (dRun < runR + gGlassBlur) {
        dropC = runner;
        dropR = runR;
        dropMask = 1.0 - smoothstep(runR - gGlassBlur, runR + gGlassBlur, dRun);
    } else {
        vec2 cell = floor(glassUv / 0.08);
        vec3 rest = restingDrop(cell);
        if (rest.z > 0.0) {
            // The beat's ripple, out from the middle of the glass, shivers them.
            float ring = exp(-pow(length(rest.xy) - uBeatPhase * 1.6, 2.0) * 60.0) * shiver;
            vec2 c = rest.xy + 0.003 * ring * vec2(sin(uTime * 40.0 + rest.x * 90.0), cos(uTime * 37.0 + rest.y * 80.0));
            float d = length(glassUv - c);
            // Wiped by a runner's trail.
            float wiped = trail;
            float m = (1.0 - smoothstep(rest.z - gGlassBlur, rest.z + gGlassBlur, d)) * (1.0 - wiped);
            if (m > 0.0) {
                dropC = c;
                dropR = rest.z;
                dropMask = m;
            }
        }
    }
    if (dropMask > 0.0) {
        vec2 local = (glassUv - dropC) / dropR;
        float rr = clamp(dot(local, local), 0.0, 1.0);
        // The city through the drop: upside down and shrunk about its centre,
        // a little sharper than the city itself (the drop is a strong lens).
        float keep = gCityBlur;
        gCityBlur = max(gCityBlur * 0.4, 0.004);
        vec3 inside = city(cityQ - local * dropR * 6.0 * vec2(1.0, -1.0) - vec2(0.0, 0.05));
        gCityBlur = keep;
        // A drop's edge is dark (it refracts the dark room behind the
        // viewer); a highlight sits near its top from the lamps.
        float rim = smoothstep(0.35, 1.0, rr);
        drop = inside * (1.0 - 0.7 * rim) * 1.5 + mix(pal(0.6), vec3(0.5), 0.6) * 0.05;
        vec2 hl = local - vec2(-0.3, 0.4);
        drop += mix(pal(0.88), vec3(1.0), 0.6) * 0.55 * exp(-dot(hl, hl) * 14.0) * (1.0 - smoothstep(0.01, 0.03, gGlassBlur));
        col = mix(col, drop, dropMask);
    }
    // Condensation: the glass outside the trails holds a faint haze.
    col = mix(col, mix(pal(0.6), vec3(0.3), 0.6) * 0.12, 0.12 * (1.0 - trail));

    col *= 0.72 + 0.28 * smoothstep(2.2, 0.4, length(uv));
    fragColor = vec4(grade(filmic(col)), 1.0);
}
