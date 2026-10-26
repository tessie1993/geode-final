// Spline camera paths, for a Style that flies a camera through its world.
//
// Pure maths, no uniform: the Style decides what drives the distance flown (its flight clock plus the
// Camera's travel, lib_camera) and these turn that distance into a position, a heading and a frame.
// pathWander uses lib_sdf3's hash11, so a Style including this includes lib_sdf3 first.
//
// ---- why Catmull-Rom ------------------------------------------------------
//
// A camera wants a path that passes THROUGH its control points (so a Style can place them where the
// view is good) with a heading that never snaps (so the picture never jerks). The cubic through p1 and
// p2 whose tangents there are half of p2 - p0 and p3 - p1 does both: consecutive pieces share their
// end points and their tangents, so the path is continuous in position and heading. Written out:
//
//     c(t) = p1 + t (-p0 + p2) / 2 + t^2 (2 p0 - 5 p1 + 4 p2 - p3) / 2 + t^3 (-p0 + 3 p1 - 3 p2 + p3) / 2
//
// ---- the wandering path ---------------------------------------------------
//
// pathWander is an endless flight along +z that sways: one control point every `spacing` units of z,
// each pushed sideways and up by a hashed amount, joined by the spline. The push is hashed from the
// point's index modulo `cells`, so the whole path repeats every spacing * cells units and a Style can
// make that its period: the flight clock and the Camera's travel wrap without the picture moving.

/** The Catmull-Rom piece from p1 (t = 0) to p2 (t = 1). */
vec3 splineCR(vec3 p0, vec3 p1, vec3 p2, vec3 p3, float t) {
    vec3 a = 0.5 * (p2 - p0);
    vec3 b = p0 - 2.5 * p1 + 2.0 * p2 - 0.5 * p3;
    vec3 c = 0.5 * (p3 - p0) + 1.5 * (p1 - p2);
    return p1 + t * (a + t * (b + t * c));
}

/** The piece's derivative in t: the heading, scaled by the speed along it. */
vec3 splineCRTangent(vec3 p0, vec3 p1, vec3 p2, vec3 p3, float t) {
    vec3 a = 0.5 * (p2 - p0);
    vec3 b = p0 - 2.5 * p1 + 2.0 * p2 - 0.5 * p3;
    vec3 c = 0.5 * (p3 - p0) + 1.5 * (p1 - p2);
    return a + t * (2.0 * b + t * 3.0 * c);
}

/** The piece's second derivative in t: how it bends, for banking into a turn. */
vec3 splineCRBend(vec3 p0, vec3 p1, vec3 p2, vec3 p3, float t) {
    vec3 b = p0 - 2.5 * p1 + 2.0 * p2 - 0.5 * p3;
    vec3 c = 0.5 * (p3 - p0) + 1.5 * (p1 - p2);
    return 2.0 * b + 6.0 * t * c;
}

/** The wandering path's control point `k`: z at k * spacing, x and y pushed up to +-amp. */
vec3 pathWanderPoint(float k, float spacing, float cells, vec2 amp, float seed) {
    float id = mod(k, cells);
    vec2 push = vec2(hash11(id + seed), hash11(id + seed + 0.5)) * 2.0 - 1.0;
    return vec3(push * amp, k * spacing);
}

/**
 * The wandering path at distance `z` along it: position in .xyz (its z is `z`, give or take the
 * spline's lean between points), and the heading in `tangent` and the bend in `bend`, both per unit
 * of z.
 */
vec3 pathWander(float z, float spacing, float cells, vec2 amp, float seed, out vec3 tangent, out vec3 bend) {
    float u = z / spacing;
    float k = floor(u);
    float t = u - k;
    vec3 p0 = pathWanderPoint(k - 1.0, spacing, cells, amp, seed);
    vec3 p1 = pathWanderPoint(k, spacing, cells, amp, seed);
    vec3 p2 = pathWanderPoint(k + 1.0, spacing, cells, amp, seed);
    vec3 p3 = pathWanderPoint(k + 2.0, spacing, cells, amp, seed);
    tangent = splineCRTangent(p0, p1, p2, p3, t) / spacing;
    bend = splineCRBend(p0, p1, p2, p3, t) / (spacing * spacing);
    return splineCR(p0, p1, p2, p3, t);
}

/**
 * A camera frame looking along `forward`, rolled by `bank` radians about it: right, up and forward
 * as columns, so `pathFrame(f, b) * vec3(uv, focal)` is a ray. Up starts as +y; a forward close to
 * vertical falls back to +z for it rather than crossing two parallel vectors.
 */
mat3 pathFrame(vec3 forward, float bank) {
    vec3 f = normalize(forward);
    vec3 worldUp = abs(f.y) > 0.98 ? vec3(0.0, 0.0, 1.0) : vec3(0.0, 1.0, 0.0);
    vec3 r = normalize(cross(worldUp, f));
    vec3 u = cross(f, r);
    float c = cos(bank);
    float s = sin(bank);
    return mat3(c * r + s * u, c * u - s * r, f);
}

/** How far to bank into a turn: the sideways bend seen from the heading, times `gain`, capped at `limit`. */
float pathBank(vec3 tangent, vec3 bend, float gain, float limit) {
    vec3 f = normalize(tangent);
    vec3 side = normalize(cross(vec3(0.0, 1.0, 0.0), f));
    return clamp(-dot(bend, side) * gain, -limit, limit);
}
