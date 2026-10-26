#version 300 es
precision highp float;

// The shared feedback warp stage (core/viz FeedbackWarp.hpp, lib_feedback.glsl): a Style that declares
// "feedback" without a warp shader of its own keeps its picture through this. The motion grid
// (feedback_warp_vert.glsl) has already said where each point reads from.

out vec4 fragColor;

//#include lib_feedback

void main() {
    fragColor = fbWarp();
}
