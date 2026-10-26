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

// Pulsar Orbit: in orbit round a pulsar. A small white-hot star spins at the
// centre with two jets streaming from its poles, ringed by an accretion disk
// whose far side is bent up over the star and its underside bent down below
// it by the star's gravity, so the disk seems to wrap the star from every
// angle. The orbit's inclination drifts, so the camera rises over the disk
// and sinks to its edge and back.
//
// ---- bent rays -----------------------------------------------------------------------
//
// Each ray is integrated through the star's field rather than cast straight:
// a few dozen steps, each turning the direction toward the star by an
// inverse-square pull and stepping further the farther out it is. It is a
// fake (Newtonian, not a geodesic) but it draws the right picture: the disk's
// far side lensed into an arch over the star, a thin secondary image below,
// and the starfield behind smeared into an Einstein ring. On the way the ray
// gathers the jets' glow; where it crosses the disk plane inside the disk it
// picks up the disk's light, brighter on the side turning toward the camera
// (beaming), and loses most of its transmittance. Detail sets the step count.
//
// ---- audio ------------------------------------------------------------------------------
//
// Energy speeds the orbit and the disk's spin through uFlowPhase (integrated,
// never a jump). uBassSmooth swells the disk's glow within a small range and
// roughens its turbulence; a beat sends a bright ripple out through the disk
// (uBeatPhase, gated by the beat envelope); uMidSmooth warms the disk's
// colour; uTrebleSmooth sparkles in the jets; a spike turns the star's
// hotspots to a new longitude (uFormPhase, which glides).

#define PO_MAX_STEPS 80
#define PO_TAU 6.2831853
#define PO_STAR 0.26
#define PO_DISK_IN 1.15
#define PO_DISK_OUT 5.2
#define PO_PULL 0.2
#define PO_ESCAPE 14.0

float gSpin;
float gBass;
float gRipple;
float gMid;
float gTreb;

/** The disk's emission at radius r and angle a, before beaming. */
vec3 diskLight(float r, float a) {
    float x = (r - PO_DISK_IN) / (PO_DISK_OUT - PO_DISK_IN);
    // Keplerian shear: the inner disk laps the outer one.
    float swirl = a + gSpin * pow(r, -1.5) * 3.0;
    float lanes = motionFbm(vec2(swirl * 2.0, r * 1.4)) * (0.75 + 0.35 * gBass);
    float bands = 0.6 + 0.4 * sin(r * 9.0 + lanes * 4.0);
    float heat = pow(1.0 - x, 1.6);
    vec3 hot = mix(vec3(1.0, 0.95, 0.9), pal(0.55), 0.25);
    vec3 warm = mix(pal(0.95 - 0.06 * gMid), vec3(1.0, 0.45, 0.3), 0.35);
    vec3 col = mix(warm, hot, heat) * (0.25 + 1.4 * heat) * bands * (0.6 + lanes);
    float edge = smoothstep(0.0, 0.08, x) * smoothstep(1.0, 0.75, x);
    // The beat's ripple, running outward through the disk.
    col *= 1.0 + 0.9 * gRipple * exp(-pow(x - uBeatPhase, 2.0) * 120.0);
    return col * edge * (0.9 + 0.12 * gBass);
}

/** The jets' emission density at p: two cones along the spin axis with knots running out. */
vec3 jetLight(vec3 p) {
    float h = abs(p.y);
    float rad = 0.07 + 0.09 * h;
    float rho = length(p.xz);
    float core = exp(-rho * rho / (rad * rad)) * smoothstep(0.25, 0.6, h) * exp(-h * 0.18);
    float spine = exp(-rho * rho / (rad * rad * 0.08));
    // Knots of brighter plasma running out along the jet.
    float knots = 0.55 + 0.45 * pow(0.5 + 0.5 * sin(h * 3.0 - gSpin * 5.0), 3.0);
    // Treble sparkles: sparse flecks riding out with the knots.
    float fleck = motionHash(floor(vec2(h * 10.0 - gSpin * 12.0, atan(p.z, p.x + 1e-5) * 2.0)));
    float sparkle = smoothstep(0.93, 1.0, fleck) * gTreb;
    vec3 tint = mix(pal(0.6), vec3(0.7, 0.85, 1.0), 0.55);
    return tint * core * (knots * (1.0 + 2.0 * spine) + 3.0 * sparkle);
}

vec3 starfield(vec3 d) {
    vec2 q = vec2(atan(d.z, d.x) * 40.0, asin(clamp(d.y, -1.0, 1.0)) * 40.0);
    vec2 cell = floor(q);
    vec2 f = fract(q) - 0.5;
    float h = motionHash(cell);
    vec2 off = (vec2(motionHash(cell + 3.1), motionHash(cell + 7.7)) - 0.5) * 0.6;
    float star = smoothstep(0.12, 0.0, length(f - off)) * step(0.9, h) * (h - 0.85) * 6.0;
    vec3 col = vec3(star) * mix(vec3(1.0), pal(h * 3.0), 0.3);
    float neb = motionFbm(q * 0.03 + 5.0);
    col += mix(pal(0.7), pal(0.3), neb) * 0.05 * smoothstep(0.4, 0.85, neb);
    return col;
}

vec3 filmic(vec3 c) {
    c = max(c, 0.0) * 1.2;
    return c * (1.0 + c * 0.2) / (1.0 + c);
}

// A finger steers the look toward where it is held; on release the look
// glides home as the touch fades. Untouched, the ray comes back as it went in.
vec3 steerLook(vec3 rd) {
    if (touchIdle()) return rd;
    vec2 a = touchAnchor() * touchStrength();
    return rotY(a.x * 0.7) * (rotX(-a.y * 0.5) * rd);
}

void main() {
    vec2 uv = cameraView();
    gBass = clamp(uBassSmooth, 0.0, 1.5);
    gMid = clamp(uMidSmooth, 0.0, 1.5);
    gTreb = clamp(uTrebleSmooth, 0.0, 1.5);
    gRipple = clamp(uBeat, 0.0, 1.0) * clamp(uBeatResponse, 0.0, 2.0);
    gSpin = uTime * 0.25 + uFlowPhase * 1.2;

    // ---- the orbit -------------------------------------------------------------------------
    float around = uTime * 0.05 + uFlowPhase * 0.45;
    float incline = 0.22 + 0.3 * sin(uTime * 0.031) + 0.08 * sin(uTime * 0.077);
    vec3 cam = vec3(cos(around) * cos(incline), sin(incline), sin(around) * cos(incline)) * 9.5;
    vec3 fwd = normalize(-cam);
    vec3 right = normalize(cross(vec3(0.0, 1.0, 0.0), fwd));
    vec3 up = cross(fwd, right);
    vec3 rd = mat3(right, up, fwd) * steerLook(normalize(vec3(uv, 1.6)));
    vec3 ro = cam;
    cameraOrbit(ro, rd);

    // ---- the bent ray ---------------------------------------------------------------------
    float budget = clamp(uSteps * 0.6, 32.0, float(PO_MAX_STEPS));
    vec3 col = vec3(0.0);
    float trans = 1.0;
    vec3 p = ro;
    vec3 d = rd;
    // Start each ray a dithered fraction of a step in, trading banding in the
    // jets' glow for fine grain.
    p += d * 0.25 * motionHash(gl_FragCoord.xy);
    bool onStar = false;
    for (int i = 0; i < PO_MAX_STEPS; i++) {
        if (float(i) >= budget || trans < 0.03) break;
        float r = length(p);
        if (r < PO_STAR) {
            onStar = true;
            break;
        }
        if (r > PO_ESCAPE && dot(p, d) > 0.0) {
            break;
        }
        float ds = clamp(0.06 + 0.12 * (r - PO_STAR), 0.05, 1.2);
        // Shorter steps near the spin axis, so the jets are sampled finely
        // enough not to band.
        ds = min(ds, 0.12 + 0.6 * length(p.xz));
        // The pull: turn toward the star by an inverse-square amount.
        d = normalize(d - p / (r * r * r) * PO_PULL * ds);
        vec3 next = p + d * ds;
        col += trans * jetLight(p) * ds * 1.6;
        if (p.y * next.y < 0.0) {
            vec3 c = mix(p, next, p.y / (p.y - next.y));
            float rc = length(c.xz);
            if (rc > PO_DISK_IN && rc < PO_DISK_OUT) {
                float a = atan(c.z, c.x);
                // Beaming: the side of the disk turning toward the camera is brighter.
                vec3 vel = normalize(vec3(-c.z, 0.0, c.x));
                float beam = 1.0 + 0.6 * dot(vel, -d) * pow(PO_DISK_IN / rc, 0.5);
                col += trans * diskLight(rc, a) * beam;
                float x = (rc - PO_DISK_IN) / (PO_DISK_OUT - PO_DISK_IN);
                trans *= mix(0.15, 0.75, x * x);
            }
        }
        p = next;
    }
    if (onStar) {
        vec3 n = normalize(p);
        float facing = clamp(dot(-d, n), 0.0, 1.0);
        float lon = atan(n.z, n.x) - gSpin * 4.0 + PO_TAU * uFormPhase;
        float spots = exp(-pow(n.y - 0.5, 2.0) * 30.0) * (0.5 + 0.5 * cos(lon)) + exp(-pow(n.y + 0.5, 2.0) * 30.0) * (0.5 + 0.5 * cos(lon + 3.14159));
        vec3 starCol = mix(vec3(0.85, 0.92, 1.0), pal(0.55), 0.2);
        col += trans * starCol * (1.6 + 0.8 * facing + 0.8 * spots);
    } else {
        // Escaped, or out of steps far from the star: the sky in the bent direction.
        col += trans * starfield(d);
    }
    // The star's glare, from the ray's closest approach to it (straight-line approximation).
    float b = length(cross(ro, rd));
    col += mix(vec3(0.8, 0.9, 1.0), pal(0.55), 0.3) * (0.22 / (1.0 + b * b * 14.0) + 0.05 / (1.0 + b * b * 0.8));

    if (!touchIdle()) {
        col += mix(pal(0.6), vec3(1.0), 0.5) * min(touchWake(uv), 3.0) * 0.035;
    }
    col *= 0.72 + 0.28 * smoothstep(2.2, 0.4, length(uv));
    fragColor = vec4(grade(filmic(col)), 1.0);
}
