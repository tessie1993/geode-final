#version 300 es
precision highp float;
precision highp sampler2D;

in vec2 vUv;
out vec4 fragColor;

//#include lib_scene_uniforms
//#include lib_scene_motion
//#include lib_palette
//#include lib_scene_grade
//#include lib_buffers

// Reaction Garden (#387): a reaction-diffusion garden drawn from its two buffers. Buffer A grows the forms
// (reaction-diffusion), buffer B remembers where they grew and in which section's hue; this image pass
// lights A's catalyst as a soft relief and colours it from B. The light follows the passage's energy, slew
// limited, a little brighter on the highlights only: nothing on screen jumps with a beat (No-flash rule).

#define RG_TAU 6.2831853

// The buffers on a torus: they wrap, so the zoomed-out garden tiles without a seam in the forms.
vec4 garden(highp sampler2D buf, vec2 q) {
    return texture(buf, fract(q));
}

void main() {
    vec2 p = view();
    float aspect = uResolution.x / max(uResolution.y, 1.0);
    vec2 q = p / vec2(aspect, 1.0) * 0.5 + 0.5;
    vec2 texel = 1.0 / vec2(textureSize(uBufA, 0));

    float v = garden(uBufA, q).g;
    float dx = garden(uBufA, q + vec2(texel.x, 0.0)).g - garden(uBufA, q - vec2(texel.x, 0.0)).g;
    float dy = garden(uBufA, q + vec2(0.0, texel.y)).g - garden(uBufA, q - vec2(0.0, texel.y)).g;
    vec3 n = normalize(vec3(-6.0 * dx, -6.0 * dy, 1.0));
    vec3 l = normalize(vec3(-0.45, 0.55, 0.75));
    float diffuse = max(dot(n, l), 0.0);
    float shine = pow(max(dot(reflect(-l, n), vec3(0.0, 0.0, 1.0)), 0.0), 24.0);

    vec4 pigment = garden(uBufB, q);
    float hue = atan(pigment.b * 2.0 - 1.0, pigment.g * 2.0 - 1.0) / RG_TAU;
    float body = smoothstep(0.08, 0.34, v);
    vec3 base = pal(hue + 0.12 * v);

    vec3 ground = pal(hue + 0.5) * 0.04 + base * 0.14 * pigment.r;
    vec3 col = mix(ground, base * (0.3 + 0.6 * diffuse), body);
    float energy = clamp(uEnergySmooth, 0.0, 1.2);
    col += vec3(0.9, 0.95, 1.0) * shine * body * (0.18 + 0.1 * energy);
    col *= 0.7 + 0.3 * smoothstep(2.0, 0.4, length(p));
    fragColor = vec4(grade(clamp(col, 0.0, 1.0)), 1.0);
}
