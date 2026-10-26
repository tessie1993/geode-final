// Layer 2's maths (core/viz/include/geode/viz/Layers.hpp), included by composite_frag.glsl.
//
// The blend functions are the W3C Compositing and Blending Level 1 ones, B(Cb, Cs) with Cb the backdrop
// (the lower picture) and Cs the source (the one laid over it). Both pictures are opaque, so the result
// is Co = (1 - opacity) * Cb + opacity * B(Cb, Cs). They work in display space, the space the scene
// targets hold and every Style was tuned in, on values kept to 0..1: postFx can lift a picture above 1,
// where Screen, Exclusion and the Lights stop behaving like colours. Add alone works in linear light,
// where two mid greys add up to a brighter grey instead of white, under a soft shoulder that rolls off
// towards 1 instead of clipping. The modes are layer::Blend's ordinals; with the layers swapped Overlay
// gives Hard Light and Luminosity gives Color.

// sRGB's own transfer curve, both ways, on values kept to 0..1 (for Add).
vec3 layerToLinear(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c));
}

vec3 layerToSrgb(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), c));
}

// W3C Lum, ClipColor and SetLum: the non-separable modes' luminance, with the colour brought back into
// range along the line to grey instead of clipping each channel.
float layerLum(vec3 c) {
    return dot(c, vec3(0.3, 0.59, 0.11));
}

vec3 layerClipColor(vec3 c) {
    float l = layerLum(c);
    float n = min(min(c.r, c.g), c.b);
    float x = max(max(c.r, c.g), c.b);
    if (n < 0.0) c = vec3(l) + (c - vec3(l)) * l / max(l - n, 1e-6);
    if (x > 1.0) c = vec3(l) + (c - vec3(l)) * (1.0 - l) / max(x - l, 1e-6);
    return c;
}

vec3 layerSetLum(vec3 c, float l) {
    return layerClipColor(c + vec3(l - layerLum(c)));
}

vec3 layerScreen(vec3 b, vec3 s) {
    return b + s - b * s;
}

// HardLight(Cb, Cs): Multiply(Cb, 2 Cs) where Cs <= 0.5, else Screen(Cb, 2 Cs - 1). Overlay is the same
// with the two pictures' roles swapped.
vec3 layerHardLight(vec3 b, vec3 s) {
    return mix(b * 2.0 * s, layerScreen(b, 2.0 * s - 1.0), step(vec3(0.5), s));
}

// SoftLight(Cb, Cs), with the spec's D(Cb): ((16 Cb - 12) Cb + 4) Cb up to 0.25, the square root above.
vec3 layerSoftLight(vec3 b, vec3 s) {
    vec3 d = mix(((16.0 * b - 12.0) * b + 4.0) * b, sqrt(b), step(vec3(0.25), b));
    vec3 darker = b - (1.0 - 2.0 * s) * b * (1.0 - b);
    vec3 lighter = b + (2.0 * s - 1.0) * (d - b);
    return mix(darker, lighter, step(vec3(0.5), s));
}

// Add in linear light: the sum is left alone up to the knee and rolls off above it towards 1, with no
// kink where it starts, so overlapping light glows instead of burning out.
vec3 layerAdd(vec3 b, vec3 s) {
    const float knee = 0.6;
    vec3 x = layerToLinear(b) + layerToLinear(s);
    vec3 rolled = vec3(knee) + (1.0 - knee) * (vec3(1.0) - exp(-(x - vec3(knee)) / (1.0 - knee)));
    return layerToSrgb(mix(x, rolled, step(vec3(knee), x)));
}

// B(Cb, Cs) for `mode`, on display values 0..1.
vec3 layerBlend(vec3 b, vec3 s, int mode) {
    if (mode == 1) return layerAdd(b, s);
    if (mode == 2) return layerScreen(b, s);
    if (mode == 3) return max(b, s);
    if (mode == 4) return b * s;
    if (mode == 5) return layerHardLight(s, b);
    if (mode == 6) return layerSoftLight(b, s);
    if (mode == 7) return abs(b - s);
    if (mode == 8) return b + s - 2.0 * b * s;
    if (mode == 9) return layerSetLum(b, layerLum(s));
    return s;
}

// Co for an opaque source at `opacity` over an opaque backdrop.
vec3 layerOver(vec3 b, vec3 s, int mode, float opacity) {
    return mix(b, layerBlend(b, s, mode), opacity);
}
