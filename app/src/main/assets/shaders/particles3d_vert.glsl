#version 300 es
precision highp float;

// The 3D particle layer (core/viz/ParticlePass, LensFx.hpp): one instance per particle, six vertices
// (two triangles) each, no vertex buffer. Every particle is placed by a hash of its instance id in a
// camera-space slab from kFar to kNear and flown toward the camera by the integrated flight phase
// (uPhase, never the clock scaled by loudness), wrapping back to kFar at a whole span so the field
// never jumps. The camera's orbit turns the slab (uCamRot, SharedCamera.hpp).
//
// The quad is the particle's motion-stretched streak: from where it was uTail depth units ago to where
// it is now, both projected with a focal length of 1 (lengths in frame half-heights), as wide as its
// nearness makes it and never thinner than a pixel and a bit, so nothing shimmers. A particle fades in
// at the far end and out at the near plane, and is lit only while the emission (0..1) is above its own
// hash, ramping in rather than popping. particles3d_frag.glsl draws the streak's profile.

uniform float uPhase;
uniform float uTail;
uniform float uEmission;
uniform float uSize;
// 0 streaks, 1 sparks (lens::ParticleKind).
uniform float uKind;
uniform float uAspect;
// One output pixel, in frame half-heights (2 / the output height).
uniform float uPixel;
uniform mat3 uCamRot;
uniform vec3 uTint;

out vec2 vAlong;
out float vLength;
out float vWidth;
out vec3 vColour;

// lens::kNearDepth, kFarDepth, kDepthSpan.
const float kNear = 0.5;
const float kFar = 12.5;
const float kSpan = 12.0;
// The slab's half-width, and how far off the flight axis every particle stays (a share of it), so
// none sits still at the vanishing point.
const float kSpread = 7.0;
const float kClearance = 0.12;
// The widths in pixels at size 1, far and near; the thinnest any streak draws.
const float kFarWidth = 1.3;
const float kNearWidth = 3.2;
const float kMinWidth = 1.2;
// The longest streak, in half-heights: past it the tail is cut, not the head.
const float kMaxLength = 0.7;
// The peak brightness (encoded) of a streak and of a spark: above 1, so bloom catches them.
const float kStreakGain = 1.6;
const float kSparkGain = 2.4;
// How far the emission ramps a particle in, as a share of the hash range.
const float kRamp = 0.15;
// The depth over which a particle fades in at the far end and out at the near plane.
const float kFarFade = 2.5;
const float kNearFade = 1.0;

uint mixBits(uint x) {
    x ^= x >> 16;
    x *= 0x7feb352du;
    x ^= x >> 15;
    x *= 0x846ca68bu;
    x ^= x >> 16;
    return x;
}

float rand(uint seed) {
    return float(mixBits(seed) >> 8) / 16777216.0;
}

vec2 project(vec3 p) {
    return p.xy / p.z;
}

void main() {
    uint seed = uint(gl_InstanceID) * 5u + 0x9e37u;
    float h0 = rand(seed);
    float h1 = rand(seed + 1u);
    float h2 = rand(seed + 2u);
    float h3 = rand(seed + 3u);
    float h4 = rand(seed + 4u);

    // Evenly over a disc, kept off the axis.
    float angle = 6.2831853 * h0;
    float radius = kSpread * mix(kClearance, 1.0, sqrt(h1));
    vec2 xy = vec2(cos(angle), sin(angle)) * radius;
    float z = kFar - mod(h2 * kSpan + uPhase, kSpan);
    mat3 view = transpose(uCamRot);
    vec3 head = view * vec3(xy, z);
    vec3 tail = view * vec3(xy, z + uTail);

    float lit = clamp((uEmission - h3) / kRamp, 0.0, 1.0);
    float fade = smoothstep(kFar, kFar - kFarFade, z) * smoothstep(kNear, kNear + kNearFade, z);
    float bright = lit * fade * min(uEmission * 1.5, 1.0);
    vec2 corner = vec2(0.0);
    int v = gl_VertexID;
    corner.x = (v == 1 || v == 2 || v == 4) ? 1.0 : 0.0;
    corner.y = (v == 2 || v == 4 || v == 5) ? 1.0 : 0.0;

    if (bright <= 0.0 || head.z < kNear * 0.5 || tail.z < kNear * 0.5) {
        // Off: every vertex outside the clip volume, so the quad draws nothing.
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        vAlong = vec2(0.0);
        vLength = 0.0;
        vWidth = 1.0;
        vColour = vec3(0.0);
        return;
    }

    vec2 b = project(head);
    vec2 a = project(tail);
    vec2 span = b - a;
    float len = length(span);
    vec2 dir = len > 1e-5 ? span / len : vec2(1.0, 0.0);
    if (len > kMaxLength) {
        a = b - dir * kMaxLength;
        len = kMaxLength;
    }
    float nearness = clamp((kFar - z) / kSpan, 0.0, 1.0);
    float width = uPixel * max(mix(kFarWidth, kNearWidth, nearness * nearness) * uSize, kMinWidth);
    // A long streak spreads its light along its length.
    bright *= mix(1.0, 0.4, clamp(len / (width * 40.0), 0.0, 1.0));

    vec3 kindColour = uKind > 0.5 ? mix(vec3(1.0, 0.72, 0.42), vec3(1.0, 0.95, 0.85), h4) : mix(vec3(0.72, 0.86, 1.0), vec3(1.0), h4);
    vColour = uTint * kindColour * bright * (uKind > 0.5 ? kSparkGain : kStreakGain);

    float along = mix(-width, len + width, corner.x);
    float across = mix(-width, width, corner.y);
    vec2 perp = vec2(-dir.y, dir.x);
    vec2 pos = a + dir * along + perp * across;
    vAlong = vec2(along, across);
    vLength = len;
    vWidth = width;
    gl_Position = vec4(pos.x / uAspect, pos.y, 0.0, 1.0);
}
