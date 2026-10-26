#version 300 es
precision highp float;
// GLSL ES 3.00 defaults fragment sampler2D to LOWP; uAudioTex and the inputs need full precision.
precision highp sampler2D;

in vec2 vUv;
out vec4 fragColor;

//#include lib_scene_uniforms
//#include lib_style
//#include lib_scene_motion
//#include lib_palette
//#include lib_scene_grade
//#include lib_sdf3
//#include lib_touch
//#include lib_camera
// The shared inputs (manifest "inputs"): the noise volume for the shells, the blue-noise mask that
// jitters where each ray starts its march, and the visit's random values.
//#include lib_inputs

// VORTEX FORGE - fire and smoke shells spiralling into a white-hot core, seen from above the funnel.
//
// THE VOLUME is a whirlpool of thin sheets around the vertical axis. Its midline sinks toward the core
// (vfFunnel), its thickness grows outward (vfThickness), and inside that envelope several sheets lie
// stacked, so a ray crossing the funnel passes through shells rather than one slab.
//
// THE SPIRAL never winds up. A differentially rotating texture shears into ever-thinner rings the
// longer it turns, so nothing here is advected by its true orbit. The noise is read in a log-polar
// frame instead: angle wrapped onto a circle (so it is seamless), log radius as the third axis. Sliding
// that axis with time moves every feature inward along a fixed logarithmic spiral at a pace that scales
// with its own radius - it reaches the core in a finite time, self-similar at every scale - while the
// arm pattern turns rigidly like a density wave and the material streams through it a little faster.
// The map is the same every frame; only the time offset moves, so the picture can run forever.
//
// THE LIGHT is integrated in closed form per slab, as Nebula does: each step absorbs
// 1 - exp(-sigma * ds) and adds that share of the medium's own radiance, so brightness does not move
// with the Detail control. The inner shells burn (a temperature ramp from ember red to white), the
// outer ones are smoke that the core lights from inside, and the core itself is an analytic glow along
// the ray, dimmed by whatever the march has passed in front of it.
//
// THE SPARKS stream out of the core in the picture plane: lanes of a spiral around the core's
// position on screen, each carrying one spark on a decelerating flight. They are cheap (a few cells a
// pixel) and always there, thinly.
//
// ---- the music ----------------------------------------------------------------
//
// The passage's loudness (uSwell) swells the core and thickens the fire; uEnergyPhase speeds the
// inflow and the sparks without ever jumping them. Drops and arrivals reach the shader as the FORCE
// Extra (the Surge and Change Reactions in BuiltInStyles push it above its neutral 1): FORCE above 1 is
// the spark burst - many more sparks, longer and hotter - and it blows the shells outward, a motion
// that rises over the Reaction's rise and settles over its long fall. Nothing in it brightens the
// frame as a whole: the sparks cover a few hundredths of it and the engine's limiter bounds the rest.
//
// Clean-room: written from the ideas above, no code taken from anywhere.

#define VF_TAU 6.2831853
/** Compile-time ceiling on the volume march; the runtime budget is a share of uSteps under it. */
#define VF_MAX_STEPS 64
/** The share of the Detail budget the fixed-step volume march spends (it pays for every step). */
#define VF_STEP_SHARE 0.5
#define VF_MIN_STEPS 28.0
/** Below this transmittance nothing behind can show; the march stops. */
#define VF_MIN_TRANS 0.02

/** The core: where the funnel drains. The camera looks at it. */
const vec3 VF_CORE = vec3(0.0, -0.32, 0.0);
/** The volume's outer radius and its vertical bounds. */
#define VF_OUTER 2.3
#define VF_TOP 0.5
#define VF_BOTTOM -0.7

/** How tightly the arms wind: angle gained per unit of log radius. */
#define VF_WIND 1.7
/** Noise units around one ring: about this many features fit on a circle. */
#define VF_RING 1.9
/** The arm pattern's turn rate, radians a second at SPIN 1. */
#define VF_PATTERN_RATE 0.22
/** The inflow along the log-radius axis, per second and per unit of energy phase. */
#define VF_INFLOW 0.24
#define VF_INFLOW_ENERGY 0.3

#define VF_FOCAL 1.65
#define VF_CAM_DIST 2.5

/** How far the burst blows the shells outward, as a share of radius. */
#define VF_BURST_PUSH 0.14

// ---- the funnel ---------------------------------------------------------------

/** The sheets' midline height at radius r: it sinks into the core. */
float vfFunnel(float r) {
    return VF_CORE.y * exp(-r * 1.7);
}

/** The envelope's half thickness at radius r: thin at the throat, fuller outside. */
float vfThickness(float r) {
    return 0.08 + 0.1 * r;
}

/**
 * The temperature ramp: 0 is cold smoke, 1 an ember, 2 white-hot. A piecewise blend through the
 * colours of heated metal and flame, tinted a little toward the palette so Customize can turn it.
 */
vec3 vfFire(float heat) {
    vec3 ember = vec3(0.55, 0.05, 0.02);
    vec3 flame = vec3(1.0, 0.36, 0.06);
    vec3 yellow = vec3(1.0, 0.74, 0.3);
    vec3 white = vec3(1.0, 0.95, 0.86);
    vec3 c = mix(vec3(0.0), ember, smoothstep(0.0, 0.45, heat));
    c = mix(c, flame, smoothstep(0.35, 0.95, heat));
    c = mix(c, yellow, smoothstep(0.85, 1.4, heat));
    c = mix(c, white, smoothstep(1.3, 2.0, heat));
    return mix(c, c * (0.6 + 0.8 * pal(0.05 + 0.1 * heat)), 0.25);
}

/** Everything one point of the volume needs: its density, its temperature and how smoky it is. */
struct VfSample {
    float density;
    float heat;
    float smoke;
};

/**
 * The medium at p. `inflow` is the log-radius time offset, `spin` the arm pattern's angle, `arms` the
 * whole number of arms, `wind` the winding, `push` the burst's outward displacement and `detail` the
 * weight of the fine octave (0 switches it off).
 */
VfSample vfMedium(vec3 p, float inflow, float spin, float arms, float wind, float push, float detail) {
    VfSample s;
    float r = length(p.xz);
    // The burst blows the field outward: sample it where it came from.
    float rr = r / (1.0 + push);

    float yn = (p.y - vfFunnel(rr)) / vfThickness(rr);
    float envelope = exp(-yn * yn * 1.3);
    float radial = smoothstep(0.07, 0.3, rr) * (1.0 - smoothstep(1.35, VF_OUTER, rr));
    if (envelope * radial < 0.01) {
        s.density = 0.0;
        s.heat = 0.0;
        s.smoke = 1.0;
        return s;
    }
    // Only a point inside the envelope pays for its angle, log radius and noise.
    float lr = log(max(rr, 0.02));
    float theta = atan(p.z, p.x);

    // The arm pattern turns rigidly; the material streams through it a little faster.
    float armPhase = theta + wind * lr - spin;
    float flowPhase = theta + wind * lr - spin * 1.35;
    vec3 q = vec3(cos(flowPhase) * VF_RING, sin(flowPhase) * VF_RING, lr * 1.6 + inflow);
    q.x += yn * 0.55;
    // The log-polar frame packs ever more features into each pixel toward the throat; the fine octave
    // and then the turbulence calm there, so the core reads as a smooth glow instead of grain.
    float calmThroat = smoothstep(0.1, 0.55, rr);
    float turb = mix(0.5, fbmVol(q, 2), 0.35 + 0.65 * calmThroat);
    float fine = detail > 0.0 ? detail * calmThroat * (vnoiseVol(q * 4.3 + vec3(17.0, 3.0, 29.0)) - 0.5) : 0.0;

    float arm = 0.5 + 0.5 * cos(arms * armPhase + 2.2 * turb);
    arm *= arm;
    // Stacked sheets inside the envelope, rippled by the turbulence so they read as shells, not planes.
    float sheets = 0.55 + 0.45 * sin(yn * 4.6 + turb * 6.0 - lr * 2.5);
    float body = smoothstep(0.3, 0.72, turb * 0.85 + arm * 0.5 + fine * 0.6 - 0.12);

    s.density = envelope * radial * sheets * body;
    s.heat = clamp(0.5 / (rr + 0.1), 0.0, 1.45) * (0.5 + 0.9 * turb + fine);
    s.smoke = smoothstep(0.45, 1.25, rr);
    return s;
}

// ---- the sky ------------------------------------------------------------------

/** Far stars and a faint dust band behind the vortex, read on the ray's direction. */
vec3 vfSky(vec3 d, bool stars, int variant) {
    vec3 band = normalize(vec3(0.2, 1.0, -0.3));
    float lat = dot(d, band);
    vec3 tint = variant == 1 ? vec3(0.35, 0.4, 0.9) : vec3(0.5, 0.25, 0.2);
    vec3 col = mix(tint, pal(0.6), 0.35) * 0.035 * (0.4 + exp(-lat * lat * 10.0));
    if (!stars) return col;
    for (int layer = 0; layer < 2; layer++) {
        float scale = layer == 0 ? 55.0 : 120.0;
        vec3 q = d * scale;
        ivec3 cell = ivec3(floor(q));
        uint h = uhash3(uvec3(cell + ivec3(layer * 7919)));
        float pick = uhashUnit(h);
        if (pick > 0.9) {
            vec3 at = vec3(cell) + 0.2 + 0.6 * vec3(uhashUnit(uhash(h + 1u)), uhashUnit(uhash(h + 2u)), uhashUnit(uhash(h + 3u)));
            float dist = length(q - at) / scale;
            float size = layer == 0 ? 0.0022 : 0.0016;
            float mag = (pick - 0.9) * 10.0;
            col += mix(vec3(0.7, 0.8, 1.0), vec3(1.0, 0.8, 0.6), uhashUnit(uhash(h + 4u))) * mag * mag * 0.9 *
                   exp(-dist * dist / (size * size));
        }
    }
    return col;
}

// ---- the sparks ---------------------------------------------------------------

/**
 * The spark field at `v`, a picture-plane offset from the core's image (lens units). `burst` 0..1 is
 * how much of a burst is on, `calm` 0..1 how many sparks stream when there is none, `px` one pixel.
 */
vec3 vfSparks(vec2 v, float burst, float calm, float px) {
    float rad = length(v);
    if (rad < 0.02) return vec3(0.0);
    float ang = atan(v.y, v.x);
    float clock = uTime * 0.42 + uEnergyPhase * 0.25;
    // A burst lights lanes that were dark; each fades in over a band of the threshold, never pops.
    float lit = mix(0.1 * calm, 0.55, burst);
    vec3 col = vec3(0.0);
    for (int layer = 0; layer < 3; layer++) {
        float lanes = 40.0 + 17.0 * float(layer);
        // The lanes curl as they leave, so the sparks spiral out with the vortex.
        float curl = 0.55 + 0.2 * float(layer);
        float a = (ang - curl * rad) / VF_TAU * lanes;
        float laneF = floor(a);
        uint lane = uint(int(mod(laneF, lanes))) + uint(layer) * 131u;
        uint h0 = uhash3(uvec3(lane, 0x51u, 0x9du));
        float offset = uhashUnit(h0);
        float pace = 0.55 + 0.7 * uhashUnit(uhash(h0 + 1u));
        float life = offset + clock * pace;
        float ph = fract(life);
        uint h1 = uhash3(uvec3(lane, uint(int(floor(life)) & 0xffff), 0x2bu));
        float pick = uhashUnit(h1);
        float on = smoothstep(pick, pick + 0.08, lit);
        if (on <= 0.0) continue;
        // A decelerating flight: thrown fast out of the core, slowed by drag.
        float reach = 1.35 + 0.5 * uhashUnit(uhash(h1 + 1u));
        float head = 0.03 + reach * (1.0 - pow(1.0 - ph, 1.8));
        float speed = reach * 1.8 * pow(1.0 - ph, 0.8);
        float tail = (0.012 + 0.05 * speed) * (0.6 + 0.7 * burst);
        float jitter = (uhashUnit(uhash(h1 + 2u)) - 0.5) * 0.5;
        float across = (fract(a) - 0.5 - jitter) * VF_TAU * rad / lanes;
        float along = rad - head;
        float s = clamp(along, -tail, 0.0);
        float width = max(px * 1.3, 0.004 * (0.7 + 0.6 * burst));
        float d = length(vec2(across, along - s));
        float shape = exp(-d * d / (width * width));
        // Brighter at the head, fading in out of the core and out at the end of its flight.
        float glowAlong = 0.35 + 0.65 * (1.0 + s / max(tail, 1e-4));
        float fade = smoothstep(0.0, 0.06, ph) * pow(1.0 - ph, 1.4) * smoothstep(0.03, 0.12, rad);
        col += vfFire(2.0 - 1.6 * ph) * shape * glowAlong * fade * on * (0.9 + 1.3 * burst);
    }
    return col;
}

// ---- the picture --------------------------------------------------------------

/** A soft shoulder into 0..1: linear near black, never clipping, hue kept by working on the peak. */
vec3 vfToneMap(vec3 c) {
    c = max(c, 0.0);
    float peak = max(max(c.r, c.g), max(c.b, 1e-5));
    float mapped = 1.0 - exp(-peak * 1.15);
    vec3 hueKept = c * (mapped / peak);
    // Very bright light drifts toward white, as film does, so the core reads as hot rather than flat.
    return mix(hueKept, vec3(mapped), smoothstep(0.75, 1.0, mapped) * 0.5);
}

void main() {
    vec2 uv = view();
    int variant = xvariant(2);
    // FORCE above its neutral 1 is the burst; below it the calm stream thins.
    float burst = clamp(uXForce - 1.0, 0.0, 1.0);
    float calm = clamp(uXForce, 0.0, 1.0);
    float swell = clamp(uSwell, 0.0, 1.2);

    // ---- the camera: above the funnel, circling it slowly, looking at the core ----
    float around = uTime * 0.035 + uVisitRand.x * VF_TAU;
    float elev = 0.78 + 0.12 * sin(uTime * 0.047);
    vec3 ro = VF_CORE + VF_CAM_DIST * vec3(cos(around) * cos(elev), sin(elev), sin(around) * cos(elev));
    vec3 fwd = normalize(VF_CORE - ro);
    vec3 right = normalize(cross(vec3(0.0, 1.0, 0.0), fwd));
    vec3 up = cross(fwd, right);
    vec2 lens = rot2(0.1 * sin(uTime * 0.031)) * uv;
    vec2 shift = vec2(0.0);
    if (!touchIdle()) shift = touchAnchor() * (0.35 * touchStrength());
    lens -= shift;
    // The Camera orbits and pushes about the core: move the eye relative to it, turn the frame with it.
    vec3 eye = ro - VF_CORE;
    vec3 rd = normalize(lens.x * right + lens.y * up + VF_FOCAL * fwd);
    cameraOrbit(eye, rd);
    ro = VF_CORE + eye;
    right = uCamRot * right;
    up = uCamRot * up;
    fwd = uCamRot * fwd;
    // Where the core lands in the lens plane, for the sparks.
    vec3 toCore = VF_CORE - ro;
    vec2 coreLens = vec2(dot(toCore, right), dot(toCore, up)) * (VF_FOCAL / max(dot(toCore, fwd), 0.1));

    // ---- the march bounds: the slab, cut by the volume's sphere ----
    float tIn = 0.0;
    float tOut = -1.0;
    if (abs(rd.y) > 1e-4) {
        float t0 = (VF_TOP - ro.y) / rd.y;
        float t1 = (VF_BOTTOM - ro.y) / rd.y;
        tIn = max(min(t0, t1), 0.0);
        tOut = max(t0, t1);
    }
    vec3 oc = ro - vec3(0.0, VF_CORE.y * 0.5, 0.0);
    float b = dot(oc, rd);
    float disc = b * b - (dot(oc, oc) - VF_OUTER * VF_OUTER);
    if (disc > 0.0) {
        float root = sqrt(disc);
        tIn = max(tIn, -b - root);
        tOut = min(tOut, -b + root);
    } else {
        tOut = -1.0;
    }

    // The core's closest approach along this ray, for its glow and for what hides it.
    float tCore = max(dot(VF_CORE - ro, rd), 0.0);
    float dCore = length(ro + rd * tCore - VF_CORE);

    float wind = VF_WIND * xm(uXSwirl);
    float arms = xcount(3.0);
    float spin = uXSpinTime * VF_PATTERN_RATE + uVisitRand.y * VF_TAU;
    float inflow = uTime * VF_INFLOW + uEnergyPhase * VF_INFLOW_ENERGY;
    float push = VF_BURST_PUSH * burst;
    float detail = fxOn(1) ? 0.35 * uXComplexity : 0.0;
    float fog = xm(uXFog);
    float fireGain = 1.0 + 0.35 * swell;

    vec3 coreTint = mix(vec3(1.0, 0.82, 0.55), pal(0.1), 0.2);
    vec3 smokeTint = variant == 1 ? mix(vec3(0.3, 0.38, 0.95), pal(0.62), 0.35) : mix(vec3(0.32, 0.22, 0.2), pal(0.85), 0.3);

    vec3 col = vec3(0.0);
    float trans = 1.0;
    float transAtCore = 1.0;
    if (tOut > tIn) {
        float budget = clamp(uSteps * VF_STEP_SHARE, VF_MIN_STEPS, float(VF_MAX_STEPS));
        float ds = (tOut - tIn) / budget;
        // Blue-noise jitter of the first step hides the slab spacing as fine grain instead of banding.
        float jitter = texelFetch(uBlueNoise, ivec2(mod(gl_FragCoord.xy, 64.0)), 0).r;
        float t = tIn + ds * jitter;
        for (int i = 0; i < VF_MAX_STEPS; i++) {
            if (float(i) >= budget || trans < VF_MIN_TRANS) break;
            vec3 p = ro + rd * t;
            VfSample m = vfMedium(p, inflow, spin, arms, wind, push, detail);
            if (m.density > 0.0) {
                float sigma = m.density * mix(9.0, 6.0 * fog, m.smoke);
                float absorbed = 1.0 - exp(-sigma * ds);
                // The fire's own light; the smoke is lit by the core from inside (inverse square, softened).
                vec3 toC = p - VF_CORE;
                float lit = 0.22 / (dot(toC, toC) + 0.12);
                vec3 fire = vfFire(m.heat) * m.heat * m.heat * 0.9 * fireGain;
                vec3 smoke = smokeTint * (0.05 + lit * 0.6) + coreTint * lit * 0.25;
                if (variant == 1) smoke += smokeTint * 0.25 * smoothstep(0.55, 1.4, length(p.xz)) * (0.5 + m.heat);
                vec3 emit = mix(fire, smoke, m.smoke);
                col += trans * emit * absorbed;
                trans *= 1.0 - absorbed;
            }
            if (t < tCore) transAtCore = trans;
            t += ds;
        }
        if (t < tCore) transAtCore = trans;
    }

    // ---- the core: an analytic glow along the ray, hidden by what lies before it ----
    float size = 0.11 * xm(uXSize);
    float k = size * size;
    float core = k / (dCore * dCore + k * 0.09) * 0.16 + 0.32 * exp(-dCore / (size * 3.2));
    core *= 0.9 + 0.15 * swell + 0.06 * burst;
    vec3 coreCol = mix(coreTint, vec3(1.0), smoothstep(1.0, 6.0, core));
    col += coreCol * core * mix(1.0, transAtCore, 0.4);

    // ---- what is left of the ray sees the sky ----
    col += trans * vfSky(rd, fxOn(0), variant);

    // ---- the sparks, in front of everything ----
    float px = 2.0 / uResolution.y;
    col += vfSparks(lens - coreLens, burst, calm, px);

    col *= mix(1.0, 0.75, smoothstep(0.7, 1.8, length(uv)));
    col = vfToneMap(col);
    col += (uhashUnit(uhash3(uvec3(gl_FragCoord.xy, 23u))) - 0.5) / 255.0;
    fragColor = vec4(grade(col), 1.0);
}
