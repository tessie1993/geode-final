#version 300 es
precision highp float;

// The 3D particle layer's streak (core/viz/ParticlePass, particles3d_vert.glsl): a soft line from the
// particle's tail to its head, brightest at the head, added onto the HDR composite (GL_ONE, GL_ONE)
// before the bloom and the tonemap, so its light glows and rolls off like any other.

in vec2 vAlong;
in float vLength;
in float vWidth;
in vec3 vColour;
out vec4 fragColor;

// How much of the head's light the tail keeps.
const float kTail = 0.3;

void main() {
    float t = clamp(vAlong.x, 0.0, vLength);
    float d = length(vec2(vAlong.x - t, vAlong.y)) / max(vWidth, 1e-6);
    float profile = exp(-3.0 * d * d);
    float toHead = vLength > 0.0 ? t / vLength : 1.0;
    // Alpha 0: the composite's alpha means nothing, and the layer adds light only.
    fragColor = vec4(vColour * profile * mix(kTail, 1.0, toHead), 0.0);
}
