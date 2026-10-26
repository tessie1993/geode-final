#version 300 es
precision highp float;
precision highp sampler2D;

in vec2 vUv;
out vec4 fragColor;

//#include lib_scene_uniforms
//#include lib_style
//#include lib_scene_features
//#include lib_inputs
//#include lib_buffers

// Reaction Garden, buffer B: the garden's pigment (#387). Red is a slow memory of where the catalyst has
// been (buffer A's green, as this frame left it), fading with the decay Extra; green and blue hold a hue as
// a direction (cos, sin) mapped into 0..1, so it blends without wrapping. Fresh growth takes the hue of the
// song's section, and what grew earlier keeps its own: the garden keeps rings of the passages it grew
// through. Everything here moves at a per-second rate, so nothing changes in one frame.

#define RG_TAU 6.2831853

vec3 hueDirection(float hue) {
    return vec3(0.0, 0.5 + 0.5 * cos(RG_TAU * hue), 0.5 + 0.5 * sin(RG_TAU * hue));
}

void main() {
    // The section's phase, read through the hue it sets, and a slow drift so a long section still moves.
    vec3 target = hueDirection(0.5 * uSection + 0.004 * uTime);
    if (bufFirstFrame()) {
        fragColor = vec4(target, 1.0);
        return;
    }
    vec4 kept = texture(uBufB, vUv);
    float v = texture(uBufA, vUv).g;
    float grown = smoothstep(0.08, 0.32, v);
    float dt = max(uFrameDt, 0.0);
    // The memory's rate: a second or so at the neutral decay, slower as the Extra rises.
    float keep = 1.0 - exp(-dt * 0.9 / xm(uXDecay));
    float density = mix(kept.r, grown, keep);
    // Only what is newly growing takes the section's hue, over about a second.
    float fresh = clamp(grown - kept.r, 0.0, 1.0);
    vec2 hue = mix(kept.gb, target.yz, clamp(fresh * (1.0 - exp(-dt * 2.5)) * 4.0, 0.0, 1.0));
    fragColor = vec4(density, hue, 1.0);
}
