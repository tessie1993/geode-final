#version 300 es
// The feedback warp's coarse motion grid (core/viz FeedbackWarp.hpp, #321): uFbGrid.x x uFbGrid.y
// cells over the frame, two triangles each, built from gl_VertexID with no vertex buffer. The motion is
// worked out at each vertex and interpolated across its cell, as MilkDrop's per-vertex warp is: where
// in the kept picture (vSrc) each point of the warped one reads from.
//
// The point is taken relative to uFbCenter in frame heights, divided by the frame's stretch, carried
// through the frame's exact source map (zoom, rotation and flow, FeedbackWarp's sourceMap) and pushed
// along a slow swirl field by uFbWarp frame heights. Every amount is already this frame's share of a
// second, so the motion is the same at any frame rate.

uniform vec2 uFbGrid;
// The source map, column by column: mat2(uFbMap.xy, uFbMap.zw).
uniform vec4 uFbMap;
uniform vec2 uFbShift;
uniform vec2 uFbStretch;
uniform vec2 uFbCenter;
uniform float uFbAspect;
uniform float uFbWarp;
uniform float uFbPhase;

out vec2 vUv;
out vec2 vSrc;

const ivec2 kCorners[6] = ivec2[6](ivec2(0, 0), ivec2(1, 0), ivec2(0, 1), ivec2(0, 1), ivec2(1, 0), ivec2(1, 1));

// Two slow rolling vortex rows across each other, unit strength: the warp's swirl. Smooth and
// bounded, so neighbouring vertices move alike and the picture bends rather than tears.
vec2 swirl(vec2 p, float t) {
    vec2 broad = vec2(sin(p.y * 2.7 + t), cos(p.x * 2.3 - t * 0.8));
    vec2 fine = vec2(cos(p.y * 4.1 + p.x * 0.9 - t * 1.3), sin(p.x * 3.7 - p.y * 0.7 + t * 1.1));
    return (broad + 0.5 * fine) / 1.5;
}

void main() {
    int columns = int(uFbGrid.x + 0.5);
    int cell = gl_VertexID / 6;
    int row = cell / columns;
    ivec2 corner = kCorners[gl_VertexID - cell * 6];
    vec2 uv = (vec2(float(cell - row * columns), float(row)) + vec2(corner)) / uFbGrid;
    vec2 frame = vec2(uFbAspect, 1.0);
    vec2 p = (uv - uFbCenter) * frame / uFbStretch;
    vec2 src = mat2(uFbMap.xy, uFbMap.zw) * p + uFbShift + uFbWarp * swirl(p, uFbPhase);
    vUv = uv;
    vSrc = src / frame + uFbCenter;
    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
}
