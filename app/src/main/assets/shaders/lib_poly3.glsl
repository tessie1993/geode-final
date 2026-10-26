// Polyhedra, wireframes, geodesic tiling and kaleidoscopic folds, for a Style that marches a world.
//
// Pure geometry like lib_sdf3, which it sits beside: nothing here reads a uniform. Every shape is
// centred on the origin and sized by its circumradius r (centre to vertex), so a dodecahedron and an
// icosahedron of the same r fit the same sphere and can swap without the scene moving.
//
// ---- how the shapes are built ---------------------------------------------
//
// The icosahedron's 12 vertices are (0, +-1, +-phi) and its cyclic turns, phi the golden ratio. Its
// dual dodecahedron has the 20 vertices (+-1, +-1, +-1) and (0, +-phi, +-1/phi) turned; the merkaba
// (the stellated octahedron) is the two tetrahedra inscribed in the cube (+-1, +-1, +-1). All three
// are symmetric under flipping the sign of any coordinate, which is what makes them cheap:
//
//   - SOLIDS are the largest of their face-plane distances. For a convex solid that is exact inside
//     and never more than the true distance outside (it is the distance to the nearest face's plane,
//     and the solid lies behind every plane), so it is safe to march; it rounds nothing.
//   - WIREFRAMES fold the point into the positive octant first. For p in that octant and any q,
//     |p - abs(q)| <= |p - q|, so the nearest edge point can always be taken from the edges clipped
//     to the octant. Clipped, each solid keeps six short segments (the merkaba three), so a full
//     30-edge wireframe costs six segment distances and is exact.
//
// The segment lists were clipped once, offline, and checked against the brute-force distance to all
// edges over random points; the numbers below are the vertices and the points where edges cross the
// coordinate planes.

#define POLY_PHI 1.6180340
#define POLY_IPHI 0.6180340

/** Distance from p to the segment a-b. */
float wireSegment(vec3 p, vec3 a, vec3 b) {
    vec3 pa = p - a;
    vec3 ba = b - a;
    float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
    return length(pa - ba * h);
}

// ---- solids ---------------------------------------------------------------

/** Icosahedron of circumradius r: its face normals are the dodecahedron's vertex directions. */
float sdIcosahedron(vec3 p, float r) {
    p = abs(p);
    // (1,1,1)/sqrt3 and (0, phi, 1/phi)/sqrt3 turned: all eight face planes of the octant.
    float d = max(max(dot(p, vec3(0.57735027)), dot(p, vec3(0.0, 0.93417236, 0.35682209))),
                  max(dot(p, vec3(0.35682209, 0.0, 0.93417236)), dot(p, vec3(0.93417236, 0.35682209, 0.0))));
    // The inradius over the circumradius of an icosahedron.
    return d - r * 0.79465447;
}

/** Dodecahedron of circumradius r (the dual of the one above): face normals (0, 1, phi) turned. */
float sdDodecahedron(vec3 p, float r) {
    p = abs(p);
    float d = max(dot(p, vec3(0.0, 0.52573111, 0.85065081)),
                  max(dot(p, vec3(0.85065081, 0.0, 0.52573111)), dot(p, vec3(0.52573111, 0.85065081, 0.0))));
    return d - r * 0.79465447;
}

/** Tetrahedron of circumradius r with vertices toward (1,1,1), (1,-1,-1), (-1,1,-1), (-1,-1,1). */
float sdTetrahedron(vec3 p, float r) {
    float d = max(max(-p.x - p.y - p.z, -p.x + p.y + p.z), max(p.x - p.y + p.z, p.x + p.y - p.z));
    // A face plane is 1/3 of the circumradius out.
    return d * 0.57735027 - r / 3.0;
}

/** The merkaba: the tetrahedron above and its point reflection, joined. */
float sdMerkaba(vec3 p, float r) {
    return min(sdTetrahedron(p, r), sdTetrahedron(-p, r));
}

// ---- wireframes -----------------------------------------------------------
//
// The distance to the edge skeleton; subtract a radius for a tube.

/** Icosahedron's 30 edges, circumradius r. */
float wireIcosahedron(vec3 p, float r) {
    // The vertices above have circumradius sqrt(1 + phi^2).
    p = abs(p) * (1.9021130 / r);
    vec3 a = vec3(0.0, 1.0, POLY_PHI);
    vec3 b = vec3(1.0, POLY_PHI, 0.0);
    vec3 c = vec3(POLY_PHI, 0.0, 1.0);
    float d = wireSegment(p, vec3(0.0, 0.0, POLY_PHI), a);
    d = min(d, wireSegment(p, a, b));
    d = min(d, wireSegment(p, a, c));
    d = min(d, wireSegment(p, b, c));
    d = min(d, wireSegment(p, vec3(0.0, POLY_PHI, 0.0), b));
    d = min(d, wireSegment(p, vec3(POLY_PHI, 0.0, 0.0), c));
    return d * (r / 1.9021130);
}

/** Dodecahedron's 30 edges, circumradius r: the solid above, vertices (0, phi, 1/phi) turned. */
float wireDodecahedron(vec3 p, float r) {
    // The segments are listed for the mirror image (0, 1/phi, phi), hence the swizzle. The vertices
    // have circumradius sqrt3.
    p = abs(p).xzy * (1.7320508 / r);
    vec3 cube = vec3(1.0);
    vec3 a = vec3(0.0, POLY_IPHI, POLY_PHI);
    vec3 b = vec3(POLY_IPHI, POLY_PHI, 0.0);
    vec3 c = vec3(POLY_PHI, 0.0, POLY_IPHI);
    float d = wireSegment(p, vec3(0.0, 0.0, POLY_PHI), a);
    d = min(d, wireSegment(p, a, cube));
    d = min(d, wireSegment(p, b, cube));
    d = min(d, wireSegment(p, c, cube));
    d = min(d, wireSegment(p, vec3(0.0, POLY_PHI, 0.0), b));
    d = min(d, wireSegment(p, vec3(POLY_PHI, 0.0, 0.0), c));
    return d * (r / 1.7320508);
}

/** The merkaba's 12 edges (the cube's face diagonals), circumradius r. */
float wireMerkaba(vec3 p, float r) {
    p = abs(p) * (1.7320508 / r);
    vec3 cube = vec3(1.0);
    float d = min(wireSegment(p, vec3(1.0, 0.0, 0.0), cube),
                  min(wireSegment(p, vec3(0.0, 1.0, 0.0), cube), wireSegment(p, vec3(0.0, 0.0, 1.0), cube)));
    return d * (r / 1.7320508);
}

// ---- geodesic tiling ------------------------------------------------------
//
// A geodesic sphere of frequency f: each of the icosahedron's faces cut into f x f small triangles,
// pushed out onto the sphere. Projected from the centre (gnomonically), a straight line on a face
// becomes a great circle, so the subdivision's lines are great circles and their distances are exact.
//
// The face a direction u falls in is the one whose normal is nearest u (every face touches the same
// insphere). The barycentric coordinates of u's projection onto that face are linear in u up to one
// shared scale: lambda_i = dot(u, g_i) / dot(u, g_A + g_B + g_C), where g_A = cross(B, C) / dot(A,
// cross(B, C)) and so on (dot(A, g_A) = 1, dot(B, g_A) = dot(C, g_A) = 0). The subdivision's lines are
// lambda_i = k / f, each the plane through the centre with normal g_i - (k / f) * (g_A + g_B + g_C).
// Below are the g vectors of the four faces of the positive octant (the others are their mirror
// images), whose numbers are 1/2, 1/(2 phi), 1/(2 phi^2) and 1/phi.

#define GEO_H 0.5
#define GEO_B 0.30901699
#define GEO_C 0.19098301
#define GEO_E 0.61803399

/** The face of the positive octant u falls in, as its three barycentric duals (columns). */
mat3 geoFace(vec3 u) {
    float f0 = dot(u, vec3(0.57735027));
    float f1 = dot(u, vec3(0.0, 0.93417236, 0.35682209));
    float f2 = dot(u, vec3(0.35682209, 0.0, 0.93417236));
    float f3 = dot(u, vec3(0.93417236, 0.35682209, 0.0));
    if (f0 >= max(f1, max(f2, f3))) {
        return mat3(vec3(-GEO_B, GEO_C, GEO_H), vec3(GEO_H, -GEO_B, GEO_C), vec3(GEO_C, GEO_H, -GEO_B));
    } else if (f1 >= max(f2, f3)) {
        return mat3(vec3(-GEO_H, GEO_B, -GEO_C), vec3(0.0, 0.0, GEO_E), vec3(GEO_H, GEO_B, -GEO_C));
    } else if (f2 >= f3) {
        return mat3(vec3(-GEO_C, GEO_H, GEO_B), vec3(-GEO_C, -GEO_H, GEO_B), vec3(GEO_E, 0.0, 0.0));
    }
    return mat3(vec3(GEO_B, -GEO_C, -GEO_H), vec3(0.0, GEO_E, 0.0), vec3(GEO_B, -GEO_C, GEO_H));
}

/**
 * Distance from p to the lines of a geodesic sphere of radius `radius` and frequency `freq` (a
 * whole number, 1 is the bare icosahedron). Exact to the nearest line's great circle; near a face's
 * border the line chosen can be the second nearest, so march it at a step of 0.8 or so.
 */
float wireGeodesic(vec3 p, float radius, float freq) {
    vec3 q = abs(p);
    float len = max(length(q), 1e-5);
    mat3 g = geoFace(q / len);
    vec3 s = g[0] + g[1] + g[2];
    // The barycentric coordinates, scaled to the subdivision.
    vec3 lam = (q * g) / max(dot(q, s), 1e-6) * freq;
    float d = 1e9;
    for (int i = 0; i < 3; i++) {
        vec3 m = g[i] - (floor(lam[i] + 0.5) / freq) * s;
        float h = dot(q, m) / length(m);
        float rho = sqrt(max(len * len - h * h, 0.0));
        d = min(d, length(vec2(rho - radius, h)));
    }
    return d;
}

/**
 * The flat facet of a geodesic sphere of frequency `freq` that the direction of p falls in, as its
 * outward normal: a sphere shaded with this instead of its own normal reads as cut and faceted.
 */
vec3 geodesicFacet(vec3 p, float freq) {
    vec3 sgn = vec3(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0, p.z < 0.0 ? -1.0 : 1.0);
    vec3 q = abs(p);
    mat3 g = geoFace(normalize(q + 1e-6));
    vec3 s = g[0] + g[1] + g[2];
    vec3 lam = (q * g) / max(dot(q, s), 1e-6) * freq;
    vec3 cell = floor(lam);
    // An upward small triangle has whole corners summing to freq - 1, a downward one freq - 2;
    // the centre is a third or two thirds of the way across the cell.
    vec3 centre = cell + ((cell.x + cell.y + cell.z) > freq - 1.5 ? vec3(1.0 / 3.0) : vec3(2.0 / 3.0));
    // Back from barycentric coordinates to a point: the face's vertices are the inverse of g's columns.
    vec3 c = inverse(transpose(g)) * (centre / freq);
    return normalize(c) * sgn;
}

// ---- kaleidoscopic folds --------------------------------------------------
//
// A fold reflects space onto one side of a mirror, so whatever is modelled on that side appears in
// every reflection for the cost of one copy. Reflections keep distances, so a folded field stays a
// true bound and marches at full step.

/** Reflect p onto the side of the plane (unit normal n, offset o) that n points into. */
vec3 foldPlane(vec3 p, vec3 n, float o) {
    float d = dot(p, n) - o;
    return p - 2.0 * min(d, 0.0) * n;
}

/** Fold the plane into one mirrored wedge of `n` around the origin, centred on +x. */
vec2 foldPolar(vec2 p, float n) {
    float seg = 6.2831853 / max(n, 1.0);
    float a = atan(p.y, p.x);
    a = abs(mod(a + 0.5 * seg, seg) - 0.5 * seg);
    return length(p) * vec2(cos(a), sin(a));
}

/** Octahedral symmetry (48 copies): the octant, then sorted so x >= y >= z >= 0. */
vec3 foldOcta(vec3 p) {
    p = abs(p);
    if (p.x < p.y) p.xy = p.yx;
    if (p.y < p.z) p.yz = p.zy;
    if (p.x < p.y) p.xy = p.yx;
    return p;
}

/**
 * Icosahedral symmetry (120 copies), for the icosahedron above. The chamber is bounded by the
 * planes x = 0 and y = 0 and the mirror with normal (-phi, -1, 1/phi) / 2, which meet at the
 * group's angles pi/2, pi/3 and pi/5; reflecting across whichever wall p is outside of walks it into
 * the chamber, and five rounds reach it from anywhere (checked offline against the whole group).
 */
vec3 foldIcosa(vec3 p) {
    const vec3 n = vec3(-0.80901699, -0.5, 0.30901699);
    for (int i = 0; i < 5; i++) {
        p.xy = abs(p.xy);
        p -= 2.0 * min(dot(p, n), 0.0) * n;
    }
    return p;
}

/** Mirrored repetition of period `period`: like mod, but every other cell flipped, so seams match. */
float repMirror(float x, float period) {
    float c = mod(x + 0.5 * period, 2.0 * period) - period;
    return 0.5 * period - abs(c);
}
