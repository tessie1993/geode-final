#version 300 es
precision highp float;
precision highp sampler2D;

// The bloom's upsample (core/viz/HdrPass): the dual filter's tent, four taps one source texel out on
// the axes and four diagonal taps half a texel out at twice the weight, from the next smaller level.
// It is drawn additively over the level it lands on, so each level gathers every smaller one and
// the glow spreads wide while staying smooth. Linear light throughout; alpha (the bloom's share of the
// light, bloom_down_frag.glsl) is filtered and added with it.

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uSource;
uniform vec2 uTexel;

void main() {
    vec2 d = uTexel;
    vec4 sum = texture(uSource, vUv + vec2(-d.x, 0.0));
    sum += texture(uSource, vUv + vec2(d.x, 0.0));
    sum += texture(uSource, vUv + vec2(0.0, -d.y));
    sum += texture(uSource, vUv + vec2(0.0, d.y));
    vec2 h = d * 0.5;
    sum += texture(uSource, vUv + vec2(-h.x, -h.y)) * 2.0;
    sum += texture(uSource, vUv + vec2(h.x, -h.y)) * 2.0;
    sum += texture(uSource, vUv + vec2(-h.x, h.y)) * 2.0;
    sum += texture(uSource, vUv + vec2(h.x, h.y)) * 2.0;
    fragColor = sum / 12.0;
}
