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

// Glass Vines: weaving through a tangle of glass tubes with liquid light
// running inside them. The camera threads the gap between the vines, banking
// into each turn of its path, while pulses of light race along the tubes
// beside it, each tube carrying its own band of the music.
//
// ---- curved tubes ------------------------------------------------------------------
//
// Each vine is a tube round a curve x, y = c(z). The distance to it is
// |p.xy - c(p.z)| minus the radius, divided by sqrt(1 + |c'|^2) at its largest
// (GV_LIP), which is what keeps the estimate below the true distance where
// the tube leans. The normal is the same expression's gradient, in closed
// form: no taps. Every frequency along z is a whole multiple of 2*pi over
// GV_PERIOD, so the tangle repeats exactly every GV_PERIOD units and the
// camera's flight is wrapped to that period without a seam.
//
// ---- glass, not solids ----------------------------------------------------------------
//
// The march steps by |d|, so it passes straight through the tubes. Entering
// one, it shades the glass (Fresnel reflection, key highlight, rim), takes
// some light out of the ray and BENDS it (refraction, so the tangle behind
// swims through every tube); inside, it gathers the liquid core's emission
// over the step; leaving, it bends back. Up to several tubes deep, front to
// back, until the ray's transmittance runs out.
//
// ---- audio ---------------------------------------------------------------------------------
//
// Energy eases the flight through uFlowPhase (integrated, never a jump). The
// pulses ride the same phase. Tubes take the bands in turn - bass, mid,
// treble - and each band brightens its tubes' liquid (slewed, so it swells).
// A beat fires a bright slug of light down every tube (uBeatPhase, gated by the
// beat envelope); a spike shifts the liquid's colours (uFormPhase, which
// glides). The camera's bank and the light never key off a raw transient.

#define GV_MAX_STEPS 96
#define GV_FAR 22.0
#define GV_CELL 4.0
#define GV_PERIOD 40.0
#define GV_W 0.15707963
#define GV_AMP 0.3
#define GV_LIP 1.07
#define GV_IOR 1.18
#define GV_TAU 6.2831853

float gTube;
float gRadius;
vec2 gOff;
vec2 gSlope;

// The vines of the cell holding p: signed distance, and which vine won.
float map(vec3 p) {
    vec2 cid = floor(p.xy / GV_CELL + 0.5);
    vec2 local = p.xy - cid * GV_CELL;
    float seed = hashCell(ivec3(ivec2(cid), 9));
    float best = 1e9;
    for (int i = 0; i < 5; i++) {
        float fi = float(i);
        float ang = fi * 1.2566371 + seed * GV_TAU;
        vec2 o = vec2(cos(ang), sin(ang)) * 1.0;
        float fx = GV_W * (2.0 + mod(fi * 3.0, 4.0));
        float fy = GV_W * (3.0 + mod(fi * 2.0, 3.0));
        float ph = fi * 1.7 + seed * 6.0;
        vec2 c = o + GV_AMP * vec2(sin(fx * p.z + ph), cos(fy * p.z + ph * 1.3));
        vec2 off = local - c;
        float r = 0.11 + 0.03 * sin(fi * 2.3 + seed * 5.0);
        float d = length(off) - r;
        if (d < best) {
            best = d;
            gTube = fi + 5.0 * seed;
            gRadius = r;
            gOff = off;
            gSlope = GV_AMP * vec2(fx * cos(fx * p.z + ph), -fy * sin(fy * p.z + ph * 1.3));
        }
    }
    return best / GV_LIP;
}

/** The won vine's outward normal: the gradient of |p.xy - c(z)|, in closed form. */
vec3 tubeNormal() {
    vec2 u = gOff / max(length(gOff), 1e-5);
    return normalize(vec3(u, -dot(u, gSlope)));
}

vec3 camPath(float z) {
    return vec3(0.28 * sin(2.0 * GV_W * z), 0.24 * cos(3.0 * GV_W * z + 1.0), z);
}

vec3 environment(vec3 d) {
    vec3 col = mix(pal(0.62), vec3(0.02, 0.03, 0.05), 0.65) * (0.10 + 0.08 * d.y);
    // The light at the far end of the tangle, which the flight heads into.
    col += mix(pal(0.08), vec3(1.0), 0.4) * 0.22 * pow(max(d.z, 0.0), 10.0);
    return col;
}

/** Soft rolloff toward white, applied before grade() so its controls keep their meaning. */
vec3 filmic(vec3 c) {
    c = max(c, 0.0) * 1.25;
    return c * (1.0 + c * 0.25) / (1.0 + c);
}

// A finger steers the look toward where it is held; on release the look
// glides home as the touch fades. Untouched, the ray comes back as it went in.
vec3 steerLook(vec3 rd) {
    if (touchIdle()) return rd;
    vec2 a = touchAnchor() * touchStrength();
    return rotY(a.x * 0.8) * (rotX(-a.y * 0.55) * rd);
}

void main() {
    vec2 uv = view();
    vec3 bands = vec3(clamp(uBassSmooth, 0.0, 1.5), clamp(uMidSmooth, 0.0, 1.5), clamp(uTrebleSmooth, 0.0, 1.5));
    float beatGate = clamp(uBeat, 0.0, 1.0) * clamp(uBeatResponse, 0.0, 2.0);
    float flow = uFlowPhase;

    // ---- the weave -------------------------------------------------------------------
    float travel = uTime * 0.3 + flow * 2.4 + uCamTravel + uCamPush * 1.5;
    float z = mod(travel, GV_PERIOD);
    vec3 ro = camPath(z);
    vec3 ahead = camPath(z + 2.5);
    vec3 fwd = normalize(ahead - ro);
    // Bank into the turn: roll with the path's sideways acceleration.
    float accel = -0.28 * (2.0 * GV_W) * (2.0 * GV_W) * sin(2.0 * GV_W * (z + 1.2));
    float roll = clamp(-accel * 9.0, -0.35, 0.35);
    vec3 upHint = vec3(sin(roll), cos(roll), 0.0);
    vec3 right = normalize(cross(upHint, fwd));
    vec3 up = cross(fwd, right);
    vec3 rd = mat3(right, up, fwd) * steerLook(normalize(vec3(uv, 1.15)));
    rd = uCamRot * rd;

    vec3 key = normalize(vec3(-0.5, 0.7, 0.4));
    vec3 keyCol = mix(pal(0.88), vec3(1.0), 0.6);
    vec3 rimCol = mix(pal(0.55), vec3(1.0), 0.6);
    vec3 fillCol = mix(pal(0.3), vec3(0.2), 0.4);

    // ---- the march through glass --------------------------------------------------------
    float budget = clamp(uSteps * 0.75, 24.0, float(GV_MAX_STEPS));
    vec3 col = vec3(0.0);
    float trans = 1.0;
    float travelled = 0.0;
    float t = 0.03;
    bool inside = false;
    float walls = 0.0;
    vec3 pos = ro;
    for (int i = 0; i < GV_MAX_STEPS; i++) {
        if (float(i) >= budget || travelled > GV_FAR || trans < 0.04 || walls > 7.0) break;
        vec3 p = pos + rd * t;
        float d = map(p);
        float fog = exp(-(travelled + t) * 0.11);
        float eps = 0.002 + 0.0006 * (travelled + t);
        // A crossing is a change of sign, not a near miss: testing |d| < eps
        // on both sides would flip in and out of the same wall every step.
        if (!inside && d < 0.0) {
            // Into the glass: reflect, highlight, rim, then bend inward.
            vec3 n = tubeNormal();
            float facing = clamp(dot(-rd, n), 0.0, 1.0);
            float fres = 0.04 + 0.96 * pow(1.0 - facing, 5.0);
            vec3 refl = reflect(rd, n);
            float spec = pow(max(dot(refl, key), 0.0), 70.0);
            float rim = pow(1.0 - facing, 3.0);
            float fill = 0.5 + 0.5 * dot(n, vec3(0.0, -1.0, 0.0));
            vec3 glass = environment(refl) * fres * 1.5 + keyCol * spec * 1.1 + rimCol * rim * 0.35
                       + fillCol * fill * 0.03;
            col += trans * fog * glass;
            // Far glass fades into the haze rather than drawing dark outlines on it.
            trans *= mix(1.0, 0.94 - 0.4 * fres, fog);
            travelled += t;
            pos = p;
            vec3 bent = refract(rd, n, 1.0 / GV_IOR);
            // Bend less with distance: far tubes are a haze, and a ray thrown
            // sideways out of the haze would land on the dark side of the sky.
            if (dot(bent, bent) > 1e-4) rd = normalize(mix(rd, bent, fog));
            t = 0.01;
            walls += 1.0;
            inside = true;
            continue;
        }
        if (inside && d > 0.0) {
            // Out of the glass, bending back.
            vec3 n = tubeNormal();
            vec3 bent = refract(rd, -n, GV_IOR);
            rd = normalize(mix(rd, dot(bent, bent) > 1e-4 ? bent : reflect(rd, -n), fog));
            col += trans * fog * rimCol * 0.04;
            trans *= mix(1.0, 0.94, fog);
            travelled += t;
            pos = p;
            t = 0.01;
            walls += 1.0;
            inside = false;
            continue;
        }
        float h = max(abs(d) * 0.85, eps);
        if (inside) {
            // The liquid core: emission gathered over the step, brightest on
            // the tube's axis, travelling along it in pulses.
            float core = exp(-dot(gOff, gOff) / (gRadius * gRadius * 0.22));
            float which = mod(floor(gTube), 3.0);
            float band = which < 0.5 ? bands.x : (which < 1.5 ? bands.y : bands.z);
            float u = p.z * 0.55 - flow * 3.2 + fract(gTube * 0.37) * 7.0;
            float pulse = pow(0.5 + 0.5 * sin(u * GV_TAU), 6.0);
            float slug = beatGate * exp(-pow(fract(p.z * 0.12 - uBeatPhase) - 0.5, 2.0) * 120.0);
            vec3 liquid = pal(0.05 + 0.21 * mod(gTube, 5.0) + 0.1 * sin(GV_TAU * uFormPhase));
            float glow = core * (0.35 + (0.9 + 1.0 * band) * pulse + 1.4 * slug);
            col += trans * fog * liquid * glow * h * 6.0;
            trans *= exp(-h * 0.6 * fog);
        } else {
            // A faint halo off the glass the ray slides past.
            col += trans * fog * rimCol * exp(-d * 14.0) * h * 0.08;
        }
        t += h;
    }
    col += trans * environment(rd);

    if (!touchIdle()) {
        col += rimCol * min(touchWake(uv), 3.0) * 0.04;
    }
    col *= 0.7 + 0.3 * smoothstep(2.2, 0.4, length(uv));
    fragColor = vec4(grade(filmic(col)), 1.0);
}
