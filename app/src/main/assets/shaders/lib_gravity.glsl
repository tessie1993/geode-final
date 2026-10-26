// Which way is down, and how far the phone is tilted, in the units a fragment style thinks in (#315).
//
// Self-contained: it declares its own uniforms, so a style includes it after lib_scene_uniforms (or
// on its own) and nothing else has to declare them. Do not declare them anywhere else.
//
// dev.geode.viz GravityField is the model; ShaderScene::uploadGravity() is the upload.
//
// ---- the one guarantee ----------------------------------------------------
//
// With tilt off - the setting, reduce motion, Battery Saver, no sensor, an offline Render - all
// three uniforms are EXACTLY zero and every helper here returns its input untouched (or zero, for
// the ones that return an offset). A style that calls them renders bit-identically to one that does
// not, as with lib_touch.
//
// ---- the limits -------------------------------------------------------------
//
// Tilt moves geometry and forces only: never feed these into brightness, palette or exposure. The
// helpers already hold the amplitude limits (parallax 2% of the screen height, look 3 degrees, never
// a roll), and the field never jumps (GravityField glides, re-centres and fades).

/** Down in screen space: x right, y up, z out of the screen; length is uTiltWeight. 0 when off. */
uniform vec3 uGravity;
/** Tilt away from the resting pose, -1..1 per axis, already scaled by uTiltWeight. 0 when off. */
uniform vec2 uTilt;
/** 0..1: how engaged tilt is, times the person's setting (Subtle is half). 0 when off. */
uniform float uTiltWeight;

/** The largest parallax shift, in view() units at a tilt of 1: 2% of the screen height. */
#define TILT_PARALLAX_MAX 0.04
/** The largest look, in radians at a tilt of 1: about 3 degrees. Matches gravity::kLookRadians. */
#define TILT_LOOK_MAX 0.052

/** True when tilt is off: every helper is then the identity. */
bool tiltIdle() {
    return uTiltWeight <= 0.0;
}

/** Down in screen space (see uGravity); vec3(0) when off. */
vec3 tiltVector() {
    if (tiltIdle()) return vec3(0.0);
    return uGravity;
}

/**
 * In-plane down for a flat style: `fallbackDown` when tilt is off (pass vec2(0) for "no gravity
 * unless tilted"). Lying flat, the in-plane direction means nothing, so it is trusted only from about
 * 5 to 12 degrees away from flat. A force using it fades out and back in; it never flips.
 */
vec2 gravityDir2(vec2 fallbackDown) {
    if (tiltIdle()) return fallbackDown;
    vec2 g = uGravity.xy / uTiltWeight;
    float m = length(g);
    float w = uTiltWeight * smoothstep(0.08, 0.20, m);
    return mix(fallbackDown, g / max(m, 1e-4), w);
}

/**
 * A layer's parallax: p (view() units) shifted with the tilt by up to 2% of the screen height at
 * depth 1 (the farthest layer); depth 0 is the screen plane and never moves.
 */
vec2 tiltParallax(vec2 p, float depth) {
    if (tiltIdle()) return p;
    return p + uTilt * (TILT_PARALLAX_MAX * clamp(depth, 0.0, 1.0));
}

/** The far-layer parallax as an offset for a flat style's domain: add it to uv. vec2(0) when off. */
vec2 tiltDrift() {
    if (tiltIdle()) return vec2(0.0);
    return uTilt * TILT_PARALLAX_MAX;
}

/**
 * The look a tilt gives a 3D style's camera: yaw then pitch, no roll, as a rotation of a camera-space
 * ray (rd = normalize(vec3(uv, focal)) frame). Turning the right edge down looks right; tipping the
 * top away looks down. Rigid, so a raymarch's step bound is unchanged. Identity when off.
 */
mat3 tiltLookMatrix() {
    if (tiltIdle()) return mat3(1.0);
    vec2 a = uTilt * TILT_LOOK_MAX;
    float cy = cos(a.x), sy = sin(a.x), cp = cos(a.y), sp = sin(a.y);
    return mat3(cy, 0.0, -sy, sy * sp, cp, cy * sp, sy * cp, -sp, cy * cp);
}

/** rd turned by tiltLookMatrix(); rd itself when off. */
vec3 tiltLook(vec3 rd) {
    if (tiltIdle()) return rd;
    return tiltLookMatrix() * rd;
}

/** A camera position orbited by the same look about the origin (a subject at the centre). */
vec3 tiltOrbit(vec3 ro) {
    if (tiltIdle()) return ro;
    return tiltLookMatrix() * ro;
}
