#version 300 es
precision highp float;

// Scope's warp stage (manifest "feedback", lib_feedback.glsl): the shared warp, with the echoes'
// colours parting as they fly out. Red reads from a little further out than blue, a quarter of the way
// to the frame's edge each second, so every echo of the line fans into a thin spectrum. It only moves
// the channels apart, never brightens them, and fbSettle() keeps and rounds what is left.

out vec4 fragColor;

//#include lib_feedback

void main() {
    vec2 apart = (vSrc - 0.5) * (0.25 * uFbDt);
    vec3 echo = vec3(fbFetch(vSrc + apart).r, fbFetch(vSrc).g, fbFetch(vSrc - apart).b);
    fragColor = vec4(fbSettle(echo), 1.0);
}
