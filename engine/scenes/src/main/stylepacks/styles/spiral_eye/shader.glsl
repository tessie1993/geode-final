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
//#include lib_path3
//#include lib_touch
//#include lib_camera
// The noise volume (manifest "inputs": noise_vol): one texture fetch per octave of cloud.
//#include lib_inputs

// Spiral Eye: a galaxy that is also an eye, seen from a camera that circles it. The cloud is a real
// volume: a thin disc of glowing gas wound into logarithmic spiral arms, with dark dust in its lanes and
// lace-thin filaments along the cloud's edges, thickening into a bulge at the middle. In the middle sits
// the eye, a small sphere whose face is an iridescent iris of rings and spokes around a dark pupil.
//
// ---- the volume ---------------------------------------------------------------------------------------
//
// A ray is marched only where it can meet gas: between where it enters and leaves the slab |y| < SLAB
// and the sphere of radius OUTER, cut short at the eye. Inside, each step adds the gas's light dimmed by
// what lies in front of it (emission and absorption), so near arms hide far ones and the dust lanes read
// dark against the glow behind them. The step count follows Detail (uSteps), with the start jittered per
// pixel so the slices do not show as rings.
//
// The arms are a logarithmic spiral: in log radius a constant step is a constant ratio, so the arms keep
// their shape all the way in. The cloud noise is read in a frame turned by the same spiral (its angle
// grows with log radius), so the clouds are drawn out along the arms instead of lying across them.
//
// ---- the camera ------------------------------------------------------------------------------------
//
// The camera circles the galaxy above its plane, looking at the eye. The Camera's orbit turns it around
// the eye and push-in closes on it (cameraOrbit), travel flies the circuit round the galaxy, and the lens
// and the roll come through cameraView().
//
// ---- the music ----------------------------------------------------------------------------------------
//
// The arms wind on uFlowPhase, which only ever moves forward, so a loud passage spins the galaxy faster
// and never backwards; bass tightens the wind, uSwell thickens the gas over seconds, treble lights the
// filaments through a smoothed band. A spike never brightens the frame: it steps the iris to a new turn
// (uFormPhase), leans the galaxy's plane toward a new heading (uMoveDir) and swells the small halo round
// the eye (uSpike, which rises over a tenth of a second).

#define SE_TAU 6.2831853
/** The gas: the disc's half-height bound, the outer radius it is marched in, and the eye's radius. */
#define SE_SLAB 0.7
#define SE_OUTER 3.4
#define SE_EYE 0.2
/** Compile-time cap on the volume march; the runtime budget follows uSteps. */
#define SE_MAX_STEPS 48
#define SE_FOCAL 1.6
/** The circuit round the galaxy, radians a second: 2 pi m / 7100, so it closes at uTime's wrap. */
#define SE_CIRCUIT_W 0.03539961
/** Radians of circuit per unit of the Camera's travel: 13 turns in its 1000-unit wrap. */
#define SE_TRAVEL_W 0.08168141
/** How far the camera sits from the eye, and how high above the disc it looks down (radians). */
#define SE_DIST 3.3
#define SE_ELEVATION 0.5

// Uniform along a ray: set once in main(), read by the gas.
float gArms;
float gPitch;
float gSpin;
mat2 gLean;

/** The gas at p: x its density, y the filament light along the cloud's edges. */
vec2 gas(vec3 p) {
    // The plane leans toward the heading the last spike chose.
    p.yz = gLean * p.yz;
    float r = max(length(p.xz), 0.02);
    float lr = log(r);
    float a = atan(p.z, p.x);
    float theta = a * gArms + lr * gPitch * gArms - gSpin;
    float arm = pow(0.5 + 0.5 * cos(theta), 3.0);
    // The disc thins outward and swells into the bulge.
    float h = (0.05 + 0.24 * exp(-r * 1.7)) * (0.8 + 0.4 * xm(uXThickness));
    float vertical = exp(-p.y * p.y / (h * h));
    // Cloud noise in the spiral's own frame, so it streams along the arms.
    vec2 q = rot2(lr * gPitch - gSpin * 0.25) * p.xz;
    float n = fbmVol(vec3(q * 1.7, p.y * 4.0 + 3.1), 3);
    float cloud = smoothstep(0.3, 0.75, 0.75 * n + 0.6 * arm);
    float density = cloud * vertical * exp(-r * 0.85) * (0.12 + 1.1 * arm) * smoothstep(SE_OUTER, SE_OUTER - 0.8, r);
    // The filaments: a thin band of one level of the noise, thicker with the Thickness Extra.
    float band = 0.035 * xm(uXThickness);
    float filament = smoothstep(0.6 - band, 0.6, n) * (1.0 - smoothstep(0.6, 0.6 + band, n));
    return vec2(density, filament * vertical * exp(-r * 0.6) * (0.4 + 0.6 * arm));
}

/** A starfield on the sky, one star in some of the cells of a grid on the direction. */
vec3 stars(vec3 d) {
    vec3 col = vec3(0.0);
    for (int layer = 0; layer < 2; layer++) {
        float scale = layer == 0 ? 90.0 : 190.0;
        vec3 q = d * scale;
        vec3 cell = floor(q);
        ivec3 ic = ivec3(cell) + ivec3(layer * 977);
        float h = hashCell(ic);
        if (h < 0.975) continue;
        vec3 at = cell + 0.2 + 0.6 * vec3(hashCell(ic + ivec3(7, 0, 0)), hashCell(ic + ivec3(0, 13, 0)), hashCell(ic + ivec3(0, 0, 29)));
        float dist = length(q - at);
        float mag = (h - 0.975) / 0.025;
        col += mix(vec3(0.7, 0.8, 1.0), vec3(1.0, 0.85, 0.7), fract(h * 37.0)) * mag * mag * exp(-dist * dist * 14.0);
    }
    return col;
}

/** The eye's face where the ray meets it at `n` (its normal), looked at along rd. */
vec3 iris(vec3 n, vec3 rd) {
    float facing = clamp(dot(n, -rd), 0.0, 1.0);
    float rho = sqrt(1.0 - facing * facing);
    // Round the view axis: a frame on the ray, so the iris always faces the camera.
    vec3 side = normalize(cross(rd, abs(rd.y) > 0.95 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0)));
    vec3 up = cross(side, rd);
    float around = atan(dot(n, up), dot(n, side));
    float rings = 0.5 + 0.5 * sin(rho * 34.0 - uFlowPhase * 3.0);
    float spokes = 0.5 + 0.5 * cos(around * 12.0 + uFlowPhase * 2.0 + uFormPhase * SE_TAU);
    vec3 film = pal(fract(0.15 + rho * 0.9 + uFormPhase * 0.2));
    vec3 col = film * (0.45 + 0.85 * rings * spokes) * 1.6;
    // The pupil: a deep violet well with a bright rim.
    float pupil = smoothstep(0.34, 0.28, rho);
    col = mix(col, vec3(0.03, 0.0, 0.06), pupil);
    col += vec3(1.0, 0.95, 0.85) * exp(-pow((rho - 0.33) * 28.0, 2.0)) * 1.4;
    // A glint off the cornea.
    col += vec3(1.0) * pow(max(dot(reflect(rd, n), normalize(vec3(-0.4, 0.7, -0.5))), 0.0), 40.0) * 0.8;
    return col;
}

void main() {
    vec2 uv = cameraView();

    float bass = clamp(uBassSmooth, 0.0, 1.5);
    float treb = clamp(uTrebleSmooth, 0.0, 1.5);
    float swell = clamp(uSwell, 0.0, 1.5);

    gArms = 2.0 + floor(uFormPhase * 3.0);
    gPitch = 1.35 + 0.3 * bass;
    gSpin = uFlowPhase * 1.6;
    float lean = 0.14 * uMoveDir.x;
    gLean = mat2(cos(lean), sin(lean), -sin(lean), cos(lean));

    // ---- the camera --------------------------------------------------------------------------------
    vec2 lens = uv;
    if (!touchIdle()) lens -= touchAnchor() * (0.25 * touchStrength());
    vec3 ro = vec3(0.0, 0.0, -SE_DIST * (1.0 - 0.08 * sin(uTime * 0.05309942)));
    vec3 rd = normalize(vec3(lens, SE_FOCAL));
    cameraOrbit(ro, rd);
    float circuit = uTime * SE_CIRCUIT_W + uCamTravel * SE_TRAVEL_W;
    vec3 eye = vec3(sin(circuit) * cos(SE_ELEVATION), sin(SE_ELEVATION), -cos(circuit) * cos(SE_ELEVATION));
    mat3 frame = pathFrame(-eye, 0.0);
    ro = frame * ro;
    rd = normalize(frame * rd);

    // ---- where the ray can meet gas ------------------------------------------------------------------
    float b = dot(ro, rd);
    float c = dot(ro, ro) - SE_OUTER * SE_OUTER;
    float disc = b * b - c;
    float tIn = 0.0;
    float tOut = -1.0;
    if (disc > 0.0) {
        float s = sqrt(disc);
        tIn = max(-b - s, 0.0);
        tOut = -b + s;
        if (abs(rd.y) > 1e-4) {
            float ta = (SE_SLAB - ro.y) / rd.y;
            float tb = (-SE_SLAB - ro.y) / rd.y;
            tIn = max(tIn, min(ta, tb));
            tOut = min(tOut, max(ta, tb));
        } else if (abs(ro.y) > SE_SLAB) {
            tOut = -1.0;
        }
    }
    // The eye cuts the march short where the ray meets it.
    float ce = dot(ro, ro) - SE_EYE * SE_EYE;
    float de = b * b - ce;
    float tEye = de > 0.0 ? -b - sqrt(de) : -1.0;
    if (tEye > 0.0) tOut = min(tOut, tEye);

    // ---- the march ----------------------------------------------------------------------------------
    vec3 warm = pal(0.10);
    vec3 cool = pal(0.55);
    vec3 col = vec3(0.0);
    float transmit = 1.0;
    if (tOut > tIn) {
        float steps = clamp(uSteps * 0.36, 16.0, float(SE_MAX_STEPS));
        float dt = (tOut - tIn) / steps;
        float jitter = uhashUnit(uhash3(uvec3(gl_FragCoord.xy, 71u)));
        float t = tIn + dt * jitter;
        float glowGain = 0.55 + 0.6 * swell;
        for (int i = 0; i < SE_MAX_STEPS; i++) {
            if (float(i) >= steps || transmit < 0.02) break;
            vec3 p = ro + rd * t;
            vec2 g = gas(p);
            float r = length(p.xz);
            vec3 tint = mix(cool, warm, smoothstep(1.8, 0.2, r) * 0.8 + 0.2 * g.x);
            vec3 emit = tint * g.x * 7.0 * glowGain;
            emit += vec3(1.0, 0.86, 0.66) * g.x * exp(-r * 2.5) * 9.0;
            emit += mix(vec3(1.0), warm, 0.35) * g.y * (1.5 + 3.0 * treb);
            col += transmit * emit * dt;
            // Dust absorbs more than the glowing gas emits, so the lanes read dark.
            transmit *= exp(-g.x * 4.0 * dt);
            t += dt;
        }
    }

    // ---- the eye, the light round it, and the sky behind ------------------------------------------------
    if (tEye > 0.0) {
        vec3 n = normalize(ro + rd * tEye);
        col += transmit * iris(n, rd);
        transmit = 0.0;
    }
    // The halo: light from the eye scattered toward the ray, by how close the ray passes it.
    float tc = max(-b, 0.0);
    float miss = length(ro + rd * tc);
    col += warm * exp(-miss * 5.5) * (0.22 + 0.3 * uSpike);
    col += vec3(1.0, 0.95, 0.88) * exp(-miss * miss * 260.0) * 0.6;
    col += transmit * stars(rd) * 0.9;

    // Sparks torn off the arms, on the glass.
    col += mix(vec3(1.0), warm, 0.4) * fluidMotes(uv * 1.2, 6.0, 0.14) * 0.16;
    if (!touchIdle()) {
        col += pal(0.5 + 0.15 * sin(uTime * 0.05)) * min(touchWake(uv), 3.0) * 0.06;
    }
    // A soft shoulder instead of a clip, and the corners fall away.
    col = col / (1.0 + 0.35 * col);
    vec2 sv = (vUv - 0.5) * vec2(uResolution.x / max(uResolution.y, 1.0), 1.0) * 2.0;
    col *= mix(1.0, smoothstep(2.1, 0.4, length(sv)), 0.45);
    fragColor = vec4(grade(col), 1.0);
}
