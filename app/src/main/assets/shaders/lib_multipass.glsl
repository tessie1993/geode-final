// The engine's passes, for a Style that declares "multipass" (geode/viz/MultiPass.hpp, docs/style-packs.md).
//
// The engine runs the Style's one shader once per pass and says which in uMpPass. A Style's main()
// follows the same shape in every pass, and these helpers make each pass do only its part:
//
//     vec3 ro = ...; mat3 frame = ...;                 // the camera: rd = normalize(frame * vec3(uv, focal))
//     if (mpCameraPass()) { fragColor = mpCameraTexel(ro, frame, focal); return; }
//     float cone = mpCone(2.0 / (uResolution.y * focal));
//     March3 m = marchStart(mpStart(t0), 1.5, 0.02);
//     ... the march ...
//     if (mpCoarsePass()) { fragColor = vec4(0.0, 0.0, 0.0, mpDepth(t)); return; }
//     ... the shading ...
//     fragColor = vec4(grade(col), mpDepth(t));
//
// Drawn without the passes (a phone without half-float targets, the render check), uMpPass is 0 and
// uMpUseCoarse 0: the helpers change nothing and the Style draws as any other.
//
// What is lost by starting a ray at the coarse depth is everything the ray would have passed on the way
// there: a Style that gathers glow along its rays (lib_march3's glowLine) declares "coarse": false.
// The resolve reprojects with the camera the Style hands over, not with view()'s zoom and turn; what
// those move this frame the resolve's neighbourhood clamp takes care of.

uniform float uMpPass;            // 0 the picture (the march), 1 the coarse prepass, 2 the camera
uniform float uMpUseCoarse;       // 1 while the march may start its rays from the coarse depth
uniform highp sampler2D uMpCoarse; // the coarse prepass: its depth in alpha

// MultiPass.hpp's kDepthK: the depth in alpha is t / (t + MP_DEPTH_K).
#define MP_DEPTH_K 4.0
// MultiPass.hpp's kPositionWrap.
#define MP_POSITION_WRAP 1024.0
// A march ray starts this share of the way to the nearest coarse depth around it, less MP_START_MARGIN.
#define MP_START_SHARE 0.85
#define MP_START_MARGIN 0.05
// The coarse prepass widens its cone so a coarse ray stops before anything its neighbours could reach.
#define MP_COARSE_CONE 2.0

bool mpCameraPass() { return uMpPass > 1.5; }
bool mpCoarsePass() { return uMpPass > 0.5 && uMpPass < 1.5; }

/** The depth `t` (world units along a unit ray) as the passes keep it in alpha. */
float mpDepth(float t) {
    t = max(t, 0.0);
    return t / (t + MP_DEPTH_K);
}

/** mpDepth's inverse: a very large distance for 1 (nothing hit). */
float mpDepthDecode(float a) {
    a = clamp(a, 0.0, 0.9999);
    return MP_DEPTH_K * a / (1.0 - a);
}

/** The march's hit cone, widened in the coarse prepass. */
float mpCone(float cone) {
    return mpCoarsePass() ? cone * MP_COARSE_CONE : cone;
}

/**
 * Where this pixel's ray may start: `t0`, or a little short of the nearest coarse depth among the
 * four coarse pixels around it when the prepass ran this frame.
 */
float mpStart(float t0) {
    if (uMpUseCoarse < 0.5 || mpCoarsePass() || mpCameraPass()) return t0;
    ivec2 size = textureSize(uMpCoarse, 0);
    vec2 at = gl_FragCoord.xy / uResolution * vec2(size) - 0.5;
    ivec2 base = ivec2(floor(at));
    ivec2 top = size - 1;
    float a = texelFetch(uMpCoarse, clamp(base, ivec2(0), top), 0).a;
    a = min(a, texelFetch(uMpCoarse, clamp(base + ivec2(1, 0), ivec2(0), top), 0).a);
    a = min(a, texelFetch(uMpCoarse, clamp(base + ivec2(0, 1), ivec2(0), top), 0).a);
    a = min(a, texelFetch(uMpCoarse, clamp(base + ivec2(1, 1), ivec2(0), top), 0).a);
    return max(t0, mpDepthDecode(a) * MP_START_SHARE - MP_START_MARGIN);
}

/**
 * The camera pass's texel at gl_FragCoord.x: the position's whole part (modulo MP_POSITION_WRAP) and
 * its fraction, then the frame's three axes, the focal length beside the first. A ray through view
 * point uv is normalize(frame * vec3(uv, focal)) from ro.
 */
vec4 mpCameraTexel(vec3 ro, mat3 frame, float focal) {
    int i = int(gl_FragCoord.x);
    vec3 whole = floor(ro);
    if (i == 0) return vec4(mod(whole, MP_POSITION_WRAP), 1.0);
    if (i == 1) return vec4(ro - whole, 1.0);
    if (i == 2) return vec4(frame[0], focal);
    if (i == 3) return vec4(frame[1], 1.0);
    return vec4(frame[2], 1.0);
}
