// A Style's own buffer passes (#387, core/viz geode/viz/BufferPasses.hpp, docs/style-packs.md "Buffers").
//
// A Style that declares "buffers" in its manifest runs up to four buffer passes, A to D, each its own
// fragment shader in the Style's folder, then its image pass (the manifest's "shader"). The engine keeps
// each buffer from frame to frame, at the share of the scene target its "scale" sets, in half float where
// the device renders it (RGB10_A2 elsewhere: alpha keeps two bits there, and nothing below 0 survives).
// Every pass that reads a buffer includes this file:
//
//   uBufA..uBufD   each buffer's newest picture. The passes run A first, so a buffer drawn earlier this
//                  frame reads as this frame's, the one being drawn and those after it as last frame's,
//                  and the image pass sees them all as this frame left them. Clamped at the edges;
//                  bufFetch() wraps instead, for a simulation on a torus.
//   uBufFrame      frames drawn this visit: 0 on the first, when every buffer is black (wraps at 2^24)
//   uBufPass       the pass being drawn: 0 (A) to 3 (D), 4 the image pass
//   uResolution    the size of what this pass draws: its buffer's, or the scene target's in the image pass
//
// Only the buffers the manifest declares are bound, so the pack check refuses a pass that reads any other.
// A buffer is the Style's state, not light on screen; only the image pass is shown, and the No-flash
// limiter bounds it like any other Style. Each visit starts the buffers black and uBufFrame at 0, and a
// Render draws on its own clock, so a Render gives the same frames every time.

// A lowp sampler would clip the half-float buffers.
uniform highp sampler2D uBufA;
uniform highp sampler2D uBufB;
uniform highp sampler2D uBufC;
uniform highp sampler2D uBufD;
uniform float uBufFrame;
uniform float uBufPass;

/** True on a visit's first frame, while every buffer is still black: the frame to lay down a start. */
bool bufFirstFrame() {
    return uBufFrame < 0.5;
}

/** Texel `p` of `buf`, wrapped around its edges (GLSL ES leaves % of a negative number undefined). */
vec4 bufFetch(highp sampler2D buf, ivec2 p) {
    ivec2 size = textureSize(buf, 0);
    ivec2 q = p - size * ivec2(floor(vec2(p) / vec2(size)));
    return texelFetch(buf, clamp(q, ivec2(0), size - 1), 0);
}
