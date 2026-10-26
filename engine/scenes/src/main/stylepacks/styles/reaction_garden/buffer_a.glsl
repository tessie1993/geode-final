#version 300 es
precision highp float;
precision highp sampler2D;

in vec2 vUv;
out vec4 fragColor;

//#include lib_scene_uniforms
//#include lib_style
//#include lib_scene_motion
//#include lib_inputs
//#include lib_buffers
//#include lib_touch

// Reaction Garden, buffer A: a two-chemical reaction-diffusion field on a torus (#387). Red holds the
// substrate u, green the catalyst v. Each frame takes one explicit step of
//   du/dt = Du lap(u) - u v^2 + f (1 - u)
//   dv/dt = Dv lap(v) + u v^2 - (f + k) v
// with a 3x3 Laplacian. The (f, k) pair picks the kind of growth (the variant); the passage's energy leans
// k a little, so a loud stretch grows the forms differently over seconds, never at once. The bass seeds
// fresh catalyst at a slowly wandering point and a finger seeds it where it touches: both grow forms,
// neither adds light (the image pass shades what grows; the No-flash rule holds there).

#define RG_DU 1.0
#define RG_DV 0.5
// The longest step that stays stable with these rates; a slower frame steps no further.
#define RG_MAX_STEP 1.0
#define RG_VARIANTS 6

// (f, k) of each variant: coral, mitosis, worms, solitons, maze, holes.
vec2 feedKill(int variant) {
    if (variant == 1) return vec2(0.0367, 0.0649);
    if (variant == 2) return vec2(0.0780, 0.0610);
    if (variant == 3) return vec2(0.0300, 0.0620);
    if (variant == 4) return vec2(0.0290, 0.0570);
    if (variant == 5) return vec2(0.0390, 0.0580);
    return vec2(0.0545, 0.0620);
}

float rgHash(vec2 p) {
    p = fract(p * vec2(0.1031, 0.1030));
    p += dot(p, p.yx + 33.33);
    return fract((p.x + p.y) * p.x);
}

// The first frame of a visit: substrate everywhere, the catalyst in a scatter of small cells placed by the
// visit's own random draw (the Render seed in a Render, so a Render grows the same garden).
vec4 firstFrame(vec2 cell) {
    float pick = rgHash(floor(cell / 6.0) + 97.0 * uVisitRand.xy);
    float v = pick > 0.965 ? 1.0 : 0.0;
    return vec4(1.0 - 0.5 * v, v, 0.0, 1.0);
}

void main() {
    ivec2 c = ivec2(gl_FragCoord.xy);
    if (bufFirstFrame()) {
        fragColor = firstFrame(gl_FragCoord.xy);
        return;
    }
    vec2 here = bufFetch(uBufA, c).rg;
    vec2 lap = -here;
    lap += 0.2 * (bufFetch(uBufA, c + ivec2(1, 0)).rg + bufFetch(uBufA, c + ivec2(-1, 0)).rg +
                  bufFetch(uBufA, c + ivec2(0, 1)).rg + bufFetch(uBufA, c + ivec2(0, -1)).rg);
    lap += 0.05 * (bufFetch(uBufA, c + ivec2(1, 1)).rg + bufFetch(uBufA, c + ivec2(-1, 1)).rg +
                   bufFetch(uBufA, c + ivec2(1, -1)).rg + bufFetch(uBufA, c + ivec2(-1, -1)).rg);

    vec2 fk = feedKill(xvariant(RG_VARIANTS));
    // The energy leans the kill rate by at most a hair either side: the same regime, other shapes.
    fk.y += 0.0012 * (clamp(uEnergySmooth, 0.0, 1.2) - 0.5);
    float u = here.r;
    float v = here.g;
    float uvv = u * v * v;
    float du = RG_DU * lap.r - uvv + fk.x * (1.0 - u);
    float dv = RG_DV * lap.g + uvv - (fk.x + fk.y) * v;
    // One step a 60 Hz frame: the garden grows at the same pace at 60 and 120 Hz, slower below 60.
    float dtStep = clamp(uFrameDt * 60.0, 0.0, RG_MAX_STEP);

    // Seeds, in the units view() thinks in (y-up, centred, x by the aspect).
    vec2 p = (gl_FragCoord.xy / uResolution * 2.0 - 1.0) * vec2(uResolution.x / uResolution.y, 1.0);
    vec2 wander = 0.55 * vec2(cos(uTime * 0.071), sin(uTime * 0.053));
    vec2 d = (p - wander) / 0.045;
    float seed = min(uBassSmooth, 1.2) * xm(uXGrowth) * exp(-dot(d, d));
    seed += min(touchFalloff(p, 0.06), 1.0) * 1.5;
    dv += 0.25 * seed * (1.0 - v);

    vec2 next = clamp(here + dtStep * vec2(du, dv), 0.0, 1.0);
    fragColor = vec4(next, 0.0, 1.0);
}
