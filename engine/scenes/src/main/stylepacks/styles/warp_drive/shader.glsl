#version 300 es
precision highp float;
precision highp sampler2D;

in vec2 vUv;
out vec4 fragColor;

//#include lib_scene_uniforms
//#include lib_scene_motion
//#include lib_palette
//#include lib_scene_grade
//#include lib_style
//#include lib_camera

// Warp Drive: down a spiral nebula toward a white-hot core, with stars
// stretched into streaks by the speed and a roll that winds up when the music
// is loud.
//
// The picture is three layers, back to front in what the eye reads:
//
//   - THE CORE at the vanishing point: a tight white centre in a wide tinted
//     halo. It breathes with uSwell, the slowest signal there is, so the
//     brightest thing on screen never moves faster than a passage does.
//   - THE NEBULA, nested cylinders of glowing gas around the flight axis,
//     composited front to back. A direction meets cylinder i at depth R_i/rho,
//     so every shell costs a noise lookup and nothing else: no march. The gas
//     is lit by the core (brighter, then white, toward the end of the tunnel)
//     and its lit rims face the core. The shell count is the Detail budget.
//   - THE STREAKS: one star per angular sector per layer, recycled down the
//     tube. Each is the segment between its projection now and its projection
//     a shutter's worth of travel later, so it lengthens with the speed.
//
// ---- what the music moves ---------------------------------------------------
//
// Speed is never `clock x loudness`, which teleports the camera. It is the
// sum of two integrals: uFlight (time plus loudness, summed on the CPU) and
// the Camera's travel (uCamTravel), which the default Reaction lets the bass
// push - so the bass drives the speed, and the speed only ever eases.
// A hit stretches the streaks (its Reaction on Flight), widens the lens a
// little and throws a burst of sparks out of the core (uSpawnAge). None of
// it brightens the frame: the burst is thin lines moving outward, and a lens
// punch keeps the mean where it was. A spike also walks the palette to a new
// plateau (uFormPhase), which glides there over a second.
//
// ---- periods ----------------------------------------------------------------
//
// Everything along the tube repeats over WD_PERIOD units: the noise lattice,
// the star recycling and its re-rolls. uFlight wraps at 2000 pi
// (ShaderScene::kFlightWrap) and uCamTravel at 1000 (camera::kTravelWrap);
// WD_FLIGHT_RATE puts a whole number of periods in the first and the period
// divides the second, so neither wrap moves anything on screen, and the
// travel is folded into one period before any float has to resolve it.

#define WD_TAU 6.2831853
#define WD_FLIGHT_WRAP 6283.1853
/** The repeat along the tube, in world units. Divides 1000 and 18800. */
#define WD_PERIOD 200.0
/** Compile-time ceiling on the nebula shells; uSteps / WD_STEPS_PER_SHELL is the runtime count. */
#define WD_MAX_SHELLS 8
#define WD_STEPS_PER_SHELL 16.0
#define WD_STAR_LAYERS 4
/** How far a star recycles: it is born WD_STAR_SPAN units ahead. Divides WD_PERIOD. */
#define WD_STAR_SPAN 20.0
/** WD_PERIOD / WD_STAR_SPAN: the star cycles that fit one period, so their re-rolls wrap with it. */
#define WD_STAR_CYCLES 10.0
#define WD_FOCAL 1.3
#define WD_CORE_Z 26.0
#define WD_FAR 30.0
/** The manifest's "effects", by position: what the thermal tiers may switch off (uFxOff). */
#define WD_FX_RIMS 0
#define WD_FX_SPARKS 1

/**
 * The camera's distance down a flight path, integrated on the CPU (ShaderScene, #280). Declared here
 * rather than taken from lib_dmt, whose path and shading this Style does not use.
 */
uniform float uFlight;

/** 94 periods per wrap of uFlight: about 6 units a second at rest, 12 when loud. */
const float WD_FLIGHT_RATE = 18800.0 / WD_FLIGHT_WRAP;
/** Lattice cells around the tube, and along it per unit (period 200 units = 200 cells). */
const float WD_NOISE_AROUND = 12.0;
const float WD_NOISE_ALONG = 1.0;

/** Value noise on a lattice that repeats every `period` cells on each axis. */
float wdNoise(vec2 p, vec2 period) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    vec2 i0 = mod(i, period);
    vec2 i1 = mod(i + 1.0, period);
    float a = motionHash(i0);
    float b = motionHash(vec2(i1.x, i0.y));
    float c = motionHash(vec2(i0.x, i1.y));
    float d = motionHash(i1);
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

/**
 * Three octaves of the gas, each faded out as its cells shrink toward a pixel
 * (`footprint` is the lattice cells one pixel spans along the tube). Near the
 * screen edge a shell is seen at a grazing angle and its texture would
 * shimmer; there the fine octaves give way to their mean instead.
 */
float wdGas(vec2 p, float footprint) {
    vec2 period = vec2(WD_NOISE_AROUND, WD_PERIOD * WD_NOISE_ALONG);
    float sum = 0.0;
    float amp = 0.55;
    float scale = 1.0;
    for (int o = 0; o < 3; o++) {
        float keep = 1.0 - smoothstep(0.25, 0.6, footprint * scale);
        sum += amp * mix(0.5, wdNoise(p * scale, period * scale), keep);
        scale *= 2.0;
        amp *= 0.5;
        p += vec2(0.37, 0.71);
    }
    return sum / 0.9625;
}

mat2 wdRot(float a) {
    float c = cos(a);
    float s = sin(a);
    return mat2(c, -s, s, c);
}

/** Pixel-wide gaussian line at `dist` from a segment `width` wide, energy kept as it is widened. */
float wdLine(float dist, float width, float pixel) {
    float w = max(width, pixel);
    return exp(-dist * dist / (w * w)) * (width / w);
}

void main() {
    vec2 uv = view();
    float enA = min(uEnergySmooth, 1.3);
    float bassA = min(uBassSmooth, 1.3);
    float trebA = min(uTrebleSmooth, 1.3);

    // The roll winds up with the music: a slow constant turn plus the
    // integral of loudness, so a loud passage spins faster and a quiet one
    // coasts, and the angle never jumps. At most ~40 degrees a second.
    float roll = 0.12 * uTime + 0.3 * uEnergyPhase + 0.18 * sin(uTime * 0.07);
    // A hit opens the lens a little (a punch, not a flash: the mean stays).
    float focal = WD_FOCAL / (1.0 + 0.18 * uSpike + 0.08 * uSwell);
    vec3 rd = normalize(vec3(wdRot(roll) * uv, focal));
    vec3 ro = vec3(0.0, 0.0, mod(uFlight * WD_FLIGHT_RATE, WD_PERIOD));
    cameraFlight(ro, rd);
    float travel = mod(ro.z, WD_PERIOD);

    // The default palette's blue through violet and red to amber, walked by
    // uFormPhase as a triangle so its wrap from 1 back to 0 is seamless.
    float hueBase = 0.33 - 0.42 * (1.0 - abs(2.0 * uFormPhase - 1.0));
    float swirl = 0.28 * uXSwirl;
    float fog = clamp(uXFog, 0.0, 2.0);
    float rxy = max(length(rd.xy), 1e-4);
    float rho = rxy / max(abs(rd.z), 1e-4);
    float ang = atan(rd.y, rd.x);
    // One pixel in the units of rho, at the centre of the frame.
    float pixel = 2.0 / (uResolution.y * focal);

    // ---- the nebula: nested shells, front to back ----------------------------
    vec3 acc = vec3(0.0);
    float trans = 1.0;
    float shells = clamp(floor(uSteps / WD_STEPS_PER_SHELL), 3.0, float(WD_MAX_SHELLS));
    for (int i = 0; i < WD_MAX_SHELLS; i++) {
        if (float(i) >= shells) break;
        float fi = (float(i) + 0.5) / shells;
        float radius = mix(0.9, 3.4, fi);
        // Signed depth along the axis where this direction meets the shell.
        float z = radius / rho * sign(rd.z);
        if (abs(z) > WD_FAR) break;
        float along = (z + travel) * WD_NOISE_ALONG;
        // The swirl turns the gas with depth, measured from the camera, so the
        // arms wind toward the core and a change of Swirl turns only what is
        // ahead, by a bounded amount.
        float around = ang / WD_TAU * WD_NOISE_AROUND + swirl * z + float(i) * 1.7;
        // d(along)/d(pixel): the shell's depth changes as radius / rho^2 per unit of rho.
        float footprint = radius / (rho * rho) * pixel * WD_NOISE_ALONG;
        vec2 p = vec2(around, along);
        // A slow warp of one octave by another, so the gas folds like smoke.
        p.x += 0.8 * wdNoise(p * vec2(0.5, 0.5) + vec2(3.1, 7.7), vec2(WD_NOISE_AROUND * 0.5, WD_PERIOD * WD_NOISE_ALONG * 0.5));
        float gas = wdGas(p, footprint);
        float dens = smoothstep(0.5, 0.85, gas) * (0.55 + 0.45 * fog);
        // Lit by the core: nothing much near the camera, strong toward the end.
        float toCore = max(WD_CORE_Z - abs(z), 0.0);
        float light = 0.1 + 2.6 / (1.0 + 0.012 * toCore * toCore);
        float rim = 0.0;
        if (fxOn(WD_FX_RIMS)) {
            // The side of a wisp that faces the core catches its light.
            float ahead = wdGas(p + vec2(0.0, 0.35), footprint);
            rim = clamp((gas - ahead) * 4.0, 0.0, 1.0) * light;
        }
        float hot = smoothstep(0.7, 1.0, gas) * smoothstep(0.6, 2.2, light);
        vec3 tint = pal(hueBase + 0.025 * float(i) + 0.07 * gas);
        vec3 emis = mix(tint, vec3(1.0, 0.94, 0.84), hot * 0.7) * (0.05 + 1.7 * dens * dens) * light;
        emis += mix(tint, vec3(1.0), 0.5) * rim * 0.45;
        // A shell right against the lens is soft and dim: nothing slams past it.
        float near = smoothstep(0.8, 3.0, abs(z));
        float alpha = clamp(dens * 0.7, 0.0, 0.75) * near;
        acc += trans * alpha * emis;
        trans *= 1.0 - alpha;
    }

    // ---- the core: what the gas lets through ----------------------------------
    float ahead = step(0.0, rd.z);
    float size = 0.055 * xm(uXSize) * (0.9 + 0.2 * uSwell + 0.06 * bassA);
    vec3 coreTint = pal(hueBase + 0.04);
    float coreHot = exp(-rho / size) * ahead;
    float coreHalo = exp(-rho / (size * 6.0)) * ahead;
    vec3 haze = coreTint * (0.015 + 0.025 * fog);
    vec3 col = acc + trans * (haze + coreTint * coreHalo * 0.55 + vec3(1.0, 0.96, 0.9) * coreHot * 1.8);
    // The core shines through the gas, a little, wherever it is thick.
    col += coreTint * coreHalo * 0.18 * (1.0 - trans);

    // ---- streaks -------------------------------------------------------------
    if (rd.z > 0.05) {
        float th = ang;
        float len = (0.35 + 0.45 * uSpeed + 0.7 * enA + 0.35 * bassA) * xm(uXFlight);
        float presence = clamp(0.55 * uXCount, 0.0, 1.0);
        vec3 starTint = mix(pal(hueBase + 0.5), vec3(1.0), 0.65);
        for (int j = 0; j < WD_STAR_LAYERS; j++) {
            float fj = float(j);
            float sectors = 36.0 + 14.0 * fj;
            float sx = th / WD_TAU * sectors + 0.37 * fj;
            float s = mod(floor(sx), sectors);
            float seed = motionHash(vec2(s, fj * 17.0 + 3.0));
            // Whether this sector has a star: faded through a band rather than
            // switched, so a Count that moves never pops stars in or out.
            float lit = smoothstep(seed - 0.08, seed + 0.08, presence);
            float phase = motionHash(vec2(s + 41.0, fj)) - travel / WD_STAR_SPAN;
            float cycle = floor(phase);
            phase -= cycle;
            float roll2 = mod(cycle, WD_STAR_CYCLES);
            float hA = motionHash(vec2(s * 1.3 + roll2 * 7.0, fj + 11.0));
            float hR = motionHash(vec2(s * 0.7 + roll2 * 3.0, fj + 29.0));
            float starA = (floor(sx) + 0.15 + 0.7 * hA - 0.37 * fj) / sectors * WD_TAU;
            float radius = mix(0.5, 3.2, hR);
            float z = 0.35 + phase * WD_STAR_SPAN;
            float head = radius / z;
            float tail = radius / (z + len);
            float dA = th - starA;
            dA -= WD_TAU * floor(dA / WD_TAU + 0.5);
            float across = rho * dA;
            float alongR = rho - clamp(rho, tail, head);
            float dist = length(vec2(across, alongR));
            float width = 0.004 * (0.6 + 0.8 * trebA) / z + 0.0015;
            float shape = wdLine(dist, width, pixel);
            // Brighter toward the head; born dim at the far end, gone at the lens.
            float grad = clamp((rho - tail) / max(head - tail, 1e-4), 0.0, 1.0);
            float fade = smoothstep(WD_STAR_SPAN, WD_STAR_SPAN * 0.55, z) * smoothstep(0.6, 2.0, z);
            col += starTint * shape * lit * fade * (0.3 + 0.7 * grad) * (0.6 + 1.6 / z);
        }
        // A burst out of the core on a hit: sparks born at the far end that fly
        // past within a second, fading as the spawn ages.
        if (fxOn(WD_FX_SPARKS)) {
            float fresh = spawnFresh(1.3);
            if (fresh > 0.001) {
                float sectors = 60.0;
                float sx = th / WD_TAU * sectors;
                float s = mod(floor(sx), sectors);
                float pick = motionHash(vec2(s, floor(uSpawnSeed * 977.0)));
                float hA = motionHash(vec2(s + 5.0, floor(uSpawnSeed * 613.0)));
                float starA = (floor(sx) + 0.2 + 0.6 * hA) / sectors * WD_TAU;
                float radius = mix(0.6, 2.6, pick);
                float z = max(WD_CORE_Z * (1.0 - spawnGrow(1.1)) * (0.6 + 0.4 * pick), 0.35);
                float head = radius / z;
                float tail = radius / (z + 2.5);
                float dA = th - starA;
                dA -= WD_TAU * floor(dA / WD_TAU + 0.5);
                float dist = length(vec2(rho * dA, rho - clamp(rho, tail, head)));
                float shape = wdLine(dist, 0.004 / z + 0.0015, pixel);
                vec3 spark = mix(pal(hueBase + 0.12), vec3(1.0, 0.85, 0.55), 0.6);
                col += spark * shape * step(0.45, pick) * fresh * (0.5 + 1.2 / z) * clamp(uBeatResponse, 0.0, 2.0);
            }
        }
    }

    // ---- finish --------------------------------------------------------------
    // A filmic shoulder: the core rolls off to white instead of clipping, and
    // the gas keeps its colour through the bright end.
    col = vec3(1.0) - exp(-col * 1.25);
    col *= 0.72 + 0.28 * smoothstep(1.9, 0.35, length(vUv * 2.0 - 1.0));
    // Sub-LSB dither, so the long halo gradients never band.
    col += (motionHash(gl_FragCoord.xy + fract(uTime) * 61.0) - 0.5) / 255.0;
    fragColor = vec4(grade(col), 1.0);
}
