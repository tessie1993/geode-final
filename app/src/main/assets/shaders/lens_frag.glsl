#version 300 es
precision highp float;
// The chain's light is far above lowp's [-2, 2) range; see composite_frag.glsl.
precision highp sampler2D;

// The lens effects' glare (core/viz/HdrPass, LensFx.hpp): flare ghosts, god rays and streaks, drawn at
// a quarter of the output size from the bloom chain's light after its downsample (bloom_down_frag.glsl:
// the light above the lens threshold, in linear light), so no blur of its own runs. What it writes is
// light: tonemap_frag.glsl adds it to the picture with the bloom, before the highlight roll-off and
// the No-flash limiter. Each part is skipped while its amount is 0.
//
// - Flare: the light mirrored through the frame's centre into a row of ghosts, each strongest near the
//   centre and tinted along a spectrum, with a chromatic fringe and a halo ring.
// - God rays: a march from each pixel toward the source, each step keeping uRayDecay of the last, so
//   light streams out of the source past whatever is dark.
// - Streaks: star (uStreakSpokes rays out of every light), zoom (the light smeared both ways along the
//   line from the centre, as flight smears it) or anamorphic (a long, cool horizontal line).

in vec2 vUv;
out vec4 fragColor;

// The chain at quarter size (level 1) and the next, softer level (level 2, or level 1 again when the
// chain is too short for it).
uniform sampler2D uLight;
uniform sampler2D uLightSoft;
// The output's width over its height: lengths are measured in frame heights so every shape is round.
uniform float uAspect;
uniform float uFlare;
uniform float uFlareGhosts;
uniform float uFlareHalo;
uniform float uFlareDispersion;
uniform float uRays;
uniform vec2 uRaySource;
uniform float uRayLength;
uniform float uRayDecay;
uniform float uRaySamples;
uniform float uStreaks;
// 0 star, 1 zoom, 2 anamorphic (lens::StreakShape).
uniform float uStreakShape;
uniform float uStreakSpokes;
uniform float uStreakLength;
uniform float uStreakSamples;

const float kHdrCeiling = 64.0;
const int kMaxGhosts = 8;
const int kMaxRaySamples = 24;
const int kMaxStreakSamples = 8;
const int kMaxSpokes = 8;
const float kTau = 6.2831853;
// How far apart the ghosts sit, as a share of the way to the centre, and the halo ring's radius in
// frame heights.
const float kGhostSpacing = 0.42;
const float kHaloRadius = 0.32;
// The largest chromatic fringe, in frame heights.
const float kFringe = 0.014;
// How far a streak of length 1 reaches, in frame heights, per shape.
const float kStarReach = 0.3;
const float kZoomReach = 0.22;
const float kAnamorphicReach = 0.75;
// Each part's light at amount 1.
const float kGhostGain = 0.45;
const float kHaloGain = 0.3;
const float kRayGain = 0.9;
const float kStreakGain = 0.7;
const vec3 kAnamorphicTint = vec3(0.55, 0.75, 1.3);

vec3 sanitize(vec3 c) {
    return any(isnan(c)) ? vec3(0.0) : clamp(c, 0.0, kHdrCeiling);
}

// A length in frame heights as a step in uv.
vec2 toUv(vec2 heights) {
    return heights / vec2(uAspect, 1.0);
}

// The light at uv; nothing from outside the frame, so no edge smears inward.
vec3 lightAt(sampler2D source, vec2 uv) {
    vec2 inside = step(vec2(0.0), uv) * step(uv, vec2(1.0));
    return sanitize(texture(source, uv).rgb) * (inside.x * inside.y);
}

// Interleaved gradient noise: a per-pixel offset that turns the rays' steps into fine grain.
float jitter(vec2 pixel) {
    return fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))));
}

// A soft spectrum for the ghosts' tint.
vec3 spectrum(float t) {
    return 0.5 + 0.5 * cos(kTau * (t + vec3(0.0, 0.33, 0.67)));
}

// How far uv is from the centre, 0 there and 1 at a corner.
float fromCentre(vec2 uv) {
    vec2 h = (uv - 0.5) * vec2(uAspect, 1.0);
    return length(h) / length(vec2(uAspect, 1.0) * 0.5);
}

vec3 fringed(vec2 uv, vec2 fringe) {
    return vec3(lightAt(uLightSoft, uv + fringe).r, lightAt(uLightSoft, uv).g, lightAt(uLightSoft, uv - fringe).b);
}

vec3 flare(vec2 uv) {
    vec2 flipped = vec2(1.0) - uv;
    vec2 toCentre = vec2(0.5) - flipped;
    vec2 heights = toCentre * vec2(uAspect, 1.0);
    vec2 dir = heights / max(length(heights), 1e-4);
    vec2 fringe = toUv(dir * kFringe * uFlareDispersion);
    vec3 ghosts = vec3(0.0);
    for (int i = 0; i < kMaxGhosts; ++i) {
        if (float(i) >= uFlareGhosts) break;
        vec2 at = flipped + toCentre * (kGhostSpacing * float(i));
        float r = fromCentre(at);
        float weight = pow(max(1.0 - r, 0.0), 6.0);
        ghosts += fringed(at, fringe) * weight * spectrum(0.15 * float(i) + 0.6 * r);
    }
    vec3 light = ghosts * kGhostGain;
    if (uFlareHalo > 0.0) {
        vec2 at = flipped + toUv(dir * kHaloRadius);
        float weight = pow(max(1.0 - fromCentre(at), 0.0), 4.0);
        light += fringed(at, fringe * 2.0) * weight * uFlareHalo * kHaloGain;
    }
    return light * uFlare;
}

vec3 godRays(vec2 uv, float offset) {
    float samples = max(uRaySamples, 1.0);
    vec2 delta = (uv - uRaySource) * (uRayLength / samples);
    vec2 at = uv - delta * offset;
    float keep = 1.0;
    float total = 0.0;
    vec3 sum = vec3(0.0);
    for (int i = 0; i < kMaxRaySamples; ++i) {
        if (float(i) >= uRaySamples) break;
        sum += lightAt(uLight, at) * keep;
        total += keep;
        keep *= uRayDecay;
        at -= delta;
    }
    return sum / max(total, 1.0) * (uRays * kRayGain);
}

// The light gathered along one direction (in frame heights per step), fading with distance.
vec3 alongLine(vec2 uv, vec2 stride) {
    vec3 sum = vec3(0.0);
    float samples = max(uStreakSamples, 1.0);
    for (int j = 1; j <= kMaxStreakSamples; ++j) {
        if (float(j) > uStreakSamples) break;
        float fade = 1.0 - float(j - 1) / samples;
        sum += lightAt(uLight, uv - toUv(stride * float(j))) * (fade * fade);
    }
    return sum / samples;
}

vec3 streaks(vec2 uv) {
    float samples = max(uStreakSamples, 1.0);
    vec3 sum = vec3(0.0);
    if (uStreakShape < 0.5) {
        float spokes = clamp(uStreakSpokes, 2.0, float(kMaxSpokes));
        float reach = uStreakLength * kStarReach / samples;
        for (int k = 0; k < kMaxSpokes; ++k) {
            if (float(k) >= spokes) break;
            float angle = kTau * (float(k) + 0.5) / spokes;
            sum += alongLine(uv, vec2(cos(angle), sin(angle)) * reach);
        }
        sum *= 2.0 / spokes;
    } else if (uStreakShape < 1.5) {
        float reach = uStreakLength * kZoomReach / samples;
        vec2 radial = uv - 0.5;
        for (int j = 1; j <= kMaxStreakSamples; ++j) {
            if (float(j) > uStreakSamples) break;
            float k = reach * float(j);
            float fade = 1.0 - float(j - 1) / samples;
            sum += (lightAt(uLight, 0.5 + radial * (1.0 - k)) + lightAt(uLight, 0.5 + radial * (1.0 + k))) * (fade * fade);
        }
        sum /= samples;
    } else {
        float reach = uStreakLength * kAnamorphicReach / samples;
        sum = (alongLine(uv, vec2(reach, 0.0)) + alongLine(uv, vec2(-reach, 0.0))) * kAnamorphicTint;
    }
    return sum * (uStreaks * kStreakGain);
}

void main() {
    vec3 light = vec3(0.0);
    if (uFlare > 0.0) light += flare(vUv);
    if (uRays > 0.0) light += godRays(vUv, jitter(gl_FragCoord.xy));
    if (uStreaks > 0.0) light += streaks(vUv);
    fragColor = vec4(sanitize(light), 1.0);
}
