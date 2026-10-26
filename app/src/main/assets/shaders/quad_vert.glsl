#version 300 es
// Fullscreen triangle, no vertex buffer needed.
out vec2 vUv;
// A multi-pass Style's sub-pixel jitter for this frame's march, in uv (geode/viz/MultiPass.hpp); 0 for
// every other draw, which never sets it.
uniform vec2 uMpJitter;

void main() {
    vec2 pos = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vUv = pos + uMpJitter;
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
}
