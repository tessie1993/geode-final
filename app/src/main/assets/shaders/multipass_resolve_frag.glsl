#version 300 es
// The resolve of a multi-pass Style (geode/viz/MultiPass.hpp): its march, drawn at a share of the
// target and offset by this frame's jitter, brought up to the whole target and accumulated with last
// frame's resolved picture, reprojected through the two cameras the Style handed over.
//
//   - DEPTH-AWARE UPSCALE: the four march samples around the pixel, weighted by distance and by how
//     close each one's depth is to the nearest sample's, so a near edge never smears into the far
//     side (or the far side into it).
//   - REPROJECTION: this pixel's point in the world (its ray from this frame's camera, out to its
//     depth) seen from last frame's camera is where last frame's picture of it lies. The cameras
//     arrive as two 5 x 1 textures (lib_multipass's mpCameraTexel); positions as a whole part modulo
//     MP_POSITION_WRAP and a fraction, so only their difference, which is small, is ever worked out.
//   - NO GHOSTS: the history is dropped where the camera cut, where the point was off screen or behind
//     last frame's camera, and where the depth last frame kept there is not the point's distance from
//     that camera (something has come out from behind); what is kept is clamped to the colours of the
//     march samples around the pixel this frame, and the faster the pixel moved, the more of this frame
//     it takes. Every output is a mix of this frame's own colours: it never brightens past them.
//
// Depth stays in alpha (t / (t + MP_DEPTH_K)), for the next frame and for depth of field.

precision highp float;
precision highp sampler2D;

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uMpMarch;    // the march: colour, depth in alpha
uniform sampler2D uMpHistory;  // last frame's resolve, the target's size
uniform sampler2D uMpCamNow;   // this frame's camera, 5 x 1
uniform sampler2D uMpCamPrev;  // last frame's
uniform float uMpHistoryOn;    // 1 when the history and last frame's camera are last frame's
uniform vec2 uMpJitter;        // this frame's march offset, in march pixels
uniform float uMpAspect;       // the target's width over its height
uniform vec2 uMpTexel;         // one target pixel in uv

// MultiPass.hpp's kDepthK, kPositionWrap, kHistoryWeight and kMaxCameraStep.
#define MP_DEPTH_K 4.0
#define MP_POSITION_WRAP 1024.0
#define MP_HISTORY_WEIGHT 0.9
#define MP_MAX_CAMERA_STEP 4.0
// Nothing hit reads as this far away.
#define MP_FAR 10000.0
// How sharply the upscale tells depths apart, in units of the alpha encoding.
#define MP_DEPTH_EDGE 40.0
// The history agrees with the point within this share of its distance, plus a little.
#define MP_DEPTH_TOLERANCE 0.1
#define MP_DEPTH_SLACK 0.05

float depthOf(float a) {
    a = clamp(a, 0.0, 0.9999);
    return min(MP_DEPTH_K * a / (1.0 - a), MP_FAR);
}

vec4 camNow(int i) { return texelFetch(uMpCamNow, ivec2(i, 0), 0); }
vec4 camPrev(int i) { return texelFetch(uMpCamPrev, ivec2(i, 0), 0); }

vec4 marchAt(ivec2 p, ivec2 top) { return texelFetch(uMpMarch, clamp(p, ivec2(0), top), 0); }

void main() {
    ivec2 msize = textureSize(uMpMarch, 0);
    ivec2 top = msize - 1;
    // March sample (i, j) was drawn at ((i, j) + 0.5 + jitter) / msize.
    vec2 at = vUv * vec2(msize) - 0.5 - uMpJitter;
    ivec2 base = ivec2(floor(at));
    vec2 f = at - vec2(base);
    vec4 s00 = marchAt(base, top);
    vec4 s10 = marchAt(base + ivec2(1, 0), top);
    vec4 s01 = marchAt(base + ivec2(0, 1), top);
    vec4 s11 = marchAt(base + ivec2(1, 1), top);
    vec2 corner = step(0.5, f);
    vec4 near = corner.x < 0.5 ? (corner.y < 0.5 ? s00 : s01) : (corner.y < 0.5 ? s10 : s11);

    // ---- the depth-aware upscale ----------------------------------------------------
    vec4 w = vec4((1.0 - f.x) * (1.0 - f.y), f.x * (1.0 - f.y), (1.0 - f.x) * f.y, f.x * f.y);
    w *= exp(-abs(vec4(s00.a, s10.a, s01.a, s11.a) - near.a) * MP_DEPTH_EDGE);
    w += 1e-5;
    vec3 up = (s00.rgb * w.x + s10.rgb * w.y + s01.rgb * w.z + s11.rgb * w.w) / dot(w, vec4(1.0));
    float depthA = near.a;
    if (uMpHistoryOn < 0.5) {
        fragColor = vec4(up, depthA);
        return;
    }

    // How squarely the nearest sample sits on this pixel this frame: a target pixel is `scale` march
    // pixels across.
    float scale = float(msize.y) * uMpTexel.y;
    float off = length(f - corner) / max(scale, 1e-3);
    float onPixel = exp(-2.0 * off * off);
    vec3 current = mix(up, near.rgb, onPixel);

    // The colours around it this frame, which the history is held to.
    ivec2 centre = base + ivec2(corner);
    vec3 lo = near.rgb;
    vec3 hi = near.rgb;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec3 c = marchAt(centre + ivec2(x, y), top).rgb;
            lo = min(lo, c);
            hi = max(hi, c);
        }
    }

    // ---- reprojection ------------------------------------------------------------------
    vec4 n0 = camNow(0);
    vec4 n2 = camNow(2);
    vec4 p0 = camPrev(0);
    vec4 p2 = camPrev(2);
    vec3 whole = mod(n0.xyz - p0.xyz + 0.5 * MP_POSITION_WRAP, MP_POSITION_WRAP) - 0.5 * MP_POSITION_WRAP;
    // This frame's camera seen from last frame's.
    vec3 moved = whole + (camNow(1).xyz - camPrev(1).xyz);
    mat3 frameNow = mat3(n2.xyz, camNow(3).xyz, camNow(4).xyz);
    mat3 framePrev = mat3(p2.xyz, camPrev(3).xyz, camPrev(4).xyz);
    vec2 view = (vUv * 2.0 - 1.0) * vec2(uMpAspect, 1.0);
    vec3 rd = normalize(frameNow * vec3(view, max(n2.w, 1e-3)));
    vec3 v = moved + rd * depthOf(depthA);
    // The frames are rotations, so last frame's inverse is its transpose.
    vec3 q = transpose(framePrev) * v;
    bool valid = length(moved) <= MP_MAX_CAMERA_STEP && n2.w > 0.0 && p2.w > 0.0 && q.z > 1e-4;
    vec2 back = q.xy / max(q.z, 1e-4) * p2.w;
    vec2 prevUv = back / vec2(uMpAspect, 1.0) * 0.5 + 0.5;
    valid = valid && all(greaterThanEqual(prevUv, vec2(0.0))) && all(lessThanEqual(prevUv, vec2(1.0)));
    vec4 history = texture(uMpHistory, prevUv);
    float expected = length(v);
    valid = valid && abs(depthOf(history.a) - expected) <= MP_DEPTH_TOLERANCE * expected + MP_DEPTH_SLACK;

    // ---- accumulation --------------------------------------------------------------------
    vec3 kept = clamp(max(history.rgb, vec3(0.0)), lo, hi);
    float motion = length((prevUv - vUv) / uMpTexel);
    float keep = valid ? MP_HISTORY_WEIGHT * (1.0 - 0.4 * onPixel) * mix(1.0, 0.6, smoothstep(2.0, 16.0, motion)) : 0.0;
    fragColor = vec4(mix(current, kept, keep), depthA);
}
