#version 300 es
precision highp float;
precision highp sampler2D;

in vec2 vUv;
out vec4 fragColor;

//#include lib_scene_uniforms
//#include lib_scene_motion
//#include lib_palette
//#include lib_scene_grade
//#include lib_sdf3
//#include lib_touch
//#include lib_camera

// Little Planet: a meadow that curls up into a tiny planet and unrolls again.
//
// The camera never moves. What changes is the LENS: every pixel's ray comes
// from one family of azimuthal projections,
//
//     theta = (1 + k) * atan(r / (f * (1 + k)))
//
// which is the rectilinear (gnomonic) lens at k = 0 and the stereographic one
// at k = 1, while the view axis tilts from just below the horizon to straight
// down. At the far end of that walk the whole sphere of directions around the
// eye fits in the frame with the ground in the middle - the "tiny planet". The
// same walk continued the other way tilts the axis straight UP, and the
// stereographic lens then turns the meadow inside out into a tunnel of sky.
// Every number in the lens is continuous in the morph, so the world bends and
// never cuts.
//
// Because the eye is fixed, the world is a function of direction alone: a sky,
// two rings of hills and a ring of trees around the eye, and the ground plane
// under it. That is the cheap part, and it is why this Style needs no march.
//
// ---- the music ---------------------------------------------------------------
//
// The morph runs on its own slow clock (uTime). The passage's loudness
// (uSwell, the slowest signal) leans it toward the planet: a build curls the
// world up and a breakdown lets it unroll. The ring of trees is the spectrum,
// band k at tree k. Nothing the music does changes how bright the frame is.

#define LP_TAU 6.2831853
#define LP_PI 3.1415927
/** One full lens walk, seconds: rectilinear -> planet -> rectilinear -> tunnel -> back. */
#define LP_MORPH_PERIOD 56.0
/** Eye height above the meadow. */
#define LP_EYE 1.6
/** Trees in the ring, and the ring's radius. */
#define LP_TREES 28.0
#define LP_TREE_RING 7.0
/** The sun's elevation (sine) and azimuth. */
#define LP_SUN_EL 0.2
#define LP_SUN_AZ 0.9
/** Fields per repeat of the ground pattern: 500 fields of 2 m, so the Camera's travel wraps (1000 m) without a seam. */
#define LP_FIELD_PERIOD 500.0

vec3 gSunDir;
vec3 gSkyLow;
vec3 gSkyHigh;
vec3 gSunCol;

/** Smooth noise on the circle of azimuths: seamless at the wrap by construction. */
float ringNoise(float az, float freq, float seed) {
    vec3 q = vec3(cos(az) * freq, sin(az) * freq, seed);
    return 0.6 * vnoise3(q) + 0.4 * vnoise3(q * 2.03 + 7.1);
}

vec3 skyColour(vec3 d) {
    float up = clamp(d.y, -0.2, 1.0);
    vec3 col = mix(gSkyLow, gSkyHigh, smoothstep(-0.02, 0.75, up));
    float sunCos = max(dot(d, gSunDir), 0.0);
    // A wide warm glow and a soft disc; never a hard point, so the sun cannot
    // alias into a twinkle while the lens bends it around.
    col += gSunCol * (0.28 * pow(sunCos, 6.0) + 0.9 * smoothstep(0.9975, 0.9993, sunCos));
    // Thin cloud bands, drifting slowly across the dome.
    vec3 cq = d / max(d.y + 0.25, 0.05);
    float cloud = smoothstep(0.55, 0.85, vnoise3(vec3(cq.xz * 0.9 + vec2(uTime * 0.02, 0.0), 2.0)));
    col = mix(col, mix(gSkyLow, vec3(1.0), 0.5) * 0.95, cloud * 0.35 * smoothstep(0.02, 0.25, up));
    return col;
}

/** Key from the sun, fill from the sky, rim from behind: the lighting every solid here shares. */
vec3 light(vec3 albedo, vec3 n, vec3 d) {
    float key = max(dot(n, gSunDir), 0.0);
    float fill = 0.5 + 0.5 * n.y;
    float rim = pow(1.0 - max(dot(n, -d), 0.0), 3.0) * max(dot(d, gSunDir), 0.0);
    return albedo * (gSunCol * key * 0.9 + gSkyHigh * fill * 0.45) + gSunCol * rim * 0.35;
}

vec3 groundColour(vec3 d, float travel) {
    float t = LP_EYE / max(-d.y, 1e-3);
    vec2 p = d.xz * t + vec2(0.0, travel);
    // A patchwork of 2 m fields. Not turned off the axes: a rotation would
    // break the 1000 m period the Camera's travel wraps on (the spin of the
    // planet hides the grid's alignment anyway).
    vec2 q = p * 0.5;
    vec2 cell = floor(q);
    vec2 f = fract(q);
    float h = hashCell(ivec3(ivec2(mod(cell, LP_FIELD_PERIOD)), 3));
    // A natural ramp (greens, wheat, ploughed earth) tinted toward the palette,
    // so a Customization recolours the meadow without turning it into candy.
    vec3 natural = h < 0.45 ? mix(vec3(0.16, 0.32, 0.08), vec3(0.3, 0.42, 0.12), h / 0.45)
                 : h < 0.8 ? mix(vec3(0.62, 0.5, 0.2), vec3(0.72, 0.6, 0.3), (h - 0.45) / 0.35)
                 : vec3(0.32, 0.2, 0.12);
    vec3 albedo = mix(natural, pal(0.2 + 0.25 * h) * 0.6, 0.3);
    // Crop rows: anti-aliased by their own footprint so the far field fades to
    // its average instead of shimmering.
    float rows = q.x * (10.0 + 8.0 * h);
    float fw = fwidth(rows);
    float stripe = 0.5 + 0.5 * cos(LP_TAU * rows) * clamp(1.0 - fw * 1.5, 0.0, 1.0);
    albedo *= 0.85 + 0.2 * stripe;
    // Hedges between the fields.
    vec2 edge = min(f, 1.0 - f);
    float hedgeW = max(0.04, 1.2 * fwidth(q.x));
    float hedge = 1.0 - smoothstep(0.0, hedgeW, min(edge.x, edge.y));
    albedo = mix(albedo, vec3(0.08, 0.14, 0.05), hedge * 0.8);
    vec3 col = light(albedo, vec3(0.0, 1.0, 0.0), d);
    float haze = 1.0 - exp(-t * 0.018);
    return mix(col, gSkyLow, haze);
}

vec3 world(vec3 d, float travel) {
    float horiz = length(d.xz);
    float az = atan(d.z, d.x);
    // The tangent of the elevation: a height at distance R appears at R * tan.
    float tanEl = d.y / max(horiz, 1e-4);

    vec3 col = d.y > 0.0 ? skyColour(d) : groundColour(d, travel);

    // Far hills, then near hills: silhouettes against the sky, hazed by depth.
    float far = 0.07 + 0.09 * ringNoise(az, 2.5, 1.0);
    if (tanEl < far && tanEl > -0.03) {
        float shade = 0.75 + 0.25 * cos(az - LP_SUN_AZ);
        vec3 hill = mix(mix(vec3(0.2, 0.3, 0.38), pal(0.58) * 0.4, 0.3), gSkyLow, 0.55) * shade;
        col = hill;
    }
    float near = 0.04 + 0.08 * ringNoise(az, 4.0, 9.0);
    if (tanEl < near && tanEl > -0.08) {
        float shade = 0.7 + 0.3 * cos(az - LP_SUN_AZ);
        vec3 hill = mix(mix(vec3(0.14, 0.26, 0.1), pal(0.32) * 0.4, 0.3), gSkyLow, 0.25) * shade;
        col = mix(hill, col, smoothstep(near - 0.004, near, tanEl));
    }

    // The ring of trees: one per slot, its height the spectrum band of its slot.
    float slots = az / LP_TAU * LP_TREES;
    float k = floor(slots);
    float u = (fract(slots) - 0.5) * (LP_TAU / LP_TREES) * LP_TREE_RING;
    float y = LP_EYE + LP_TREE_RING * tanEl;
    float kk = mod(k + LP_TREES, LP_TREES);
    float band = aband(0.04 + 0.8 * kk / LP_TREES);
    float top = 1.3 + 1.7 * clamp(band, 0.0, 1.2) + 0.35 * hash11(kk + 0.5);
    // A conifer in tiers: the taper with a saw on it, each tier flaring out.
    float taper = clamp((top - y) / (top - 0.45), 0.0, 1.0);
    float halfW = 0.62 * taper * (0.72 + 0.28 * fract((top - y) * 2.4));
    float crown = abs(u) - halfW;
    float trunk = max(abs(u) - 0.07, y - 0.6);
    float crownD = (y > 0.45 && y < top) ? crown : 1.0;
    float trunkD = y > 0.0 ? trunk : 1.0;
    float tree = min(crownD, trunkD);
    // The edge width from the angular footprint of the pixel, not fwidth(u):
    // u is cut at every slot boundary, and its derivative there is a spike.
    float aa = min(length(fwidth(d)) / max(horiz, 1e-3) * LP_TREE_RING * 1.2, 0.2) + 1e-4;
    // Straight up or down the ring has no width to draw; skip it there rather
    // than smear a whole ring into one pixel.
    float cover = horiz > 0.02 ? 1.0 - smoothstep(-aa, aa, tree) : 0.0;
    if (cover > 0.0) {
        // A conifer's normal: outward from the ring, leaning with the crown slope.
        vec3 outward = vec3(cos(az), 0.0, sin(az));
        vec3 side = vec3(-sin(az), 0.0, cos(az));
        float slope = clamp(u / max(halfW, 0.05), -1.0, 1.0);
        vec3 n = normalize(-outward + side * slope * 0.8 + vec3(0.0, 0.45, 0.0));
        vec3 albedo = trunkD < crownD ? vec3(0.22, 0.13, 0.07) : mix(vec3(0.08, 0.24, 0.1), pal(0.36) * 0.5, 0.3);
        vec3 lit = light(albedo, n, d);
        col = mix(col, mix(lit, gSkyLow, 0.12), cover);
    }
    return col;
}

vec3 tonemap(vec3 c) {
    c = max(c, 0.0);
    return clamp(c * (2.51 * c + 0.03) / (c * (2.43 * c + 0.59) + 0.14), 0.0, 1.0);
}

void main() {
    vec2 uv = view();

    gSunDir = normalize(vec3(cos(LP_SUN_AZ), LP_SUN_EL, sin(LP_SUN_AZ)));
    gSkyLow = mix(vec3(1.0, 0.76, 0.55), pal(0.08), 0.3) * 0.8;
    gSkyHigh = mix(vec3(0.26, 0.44, 0.8), pal(0.62), 0.3) * 0.75;
    gSunCol = mix(vec3(1.0, 0.86, 0.66), pal(0.05), 0.25);

    // ---- the lens walk -------------------------------------------------------
    // A sine clipped into plateaus: it rests on the planet and on the tunnel
    // and eases through the rectilinear view between them.
    float phase = uTime * (LP_TAU / LP_MORPH_PERIOD);
    float s = clamp(1.3 * sin(phase) + 0.35 * clamp(uSwell, 0.0, 1.2) - 0.1, -1.0, 1.0);
    float k = abs(s);
    float e = k * k * (3.0 - 2.0 * k);
    // Tilt: down to the nadir for the planet, up to the zenith for the tunnel.
    float tilt = s >= 0.0 ? mix(0.16, 0.5 * LP_PI, e) : mix(0.16, -0.5 * LP_PI, e);
    float focal = mix(1.25, 0.36, k) * (1.0 + 0.6 * uCamPush);

    float r = length(uv);
    float theta = (1.0 + k) * atan(r / (focal * (1.0 + k)));
    vec2 around = r > 1e-6 ? uv / r : vec2(0.0);
    vec3 local = vec3(sin(theta) * around, cos(theta));

    // The camera basis: forward tipped down by `tilt`.
    vec3 fwd = vec3(0.0, -sin(tilt), cos(tilt));
    vec3 up = vec3(0.0, cos(tilt), sin(tilt));
    vec3 d = normalize(vec3(1.0, 0.0, 0.0) * local.x + up * local.y + fwd * local.z);

    // The planet spins: a turn about the vertical, which on the planet is a
    // roll of the whole globe in the frame.
    float spin = uTime * 0.05 + 0.4 * sin(uTime * 0.021);
    d = rotY(spin) * d;

    // The fingers turn the head: a yaw and pitch toward the touch. Gated, so an
    // untouched frame never multiplies by anything.
    if (!touchIdle()) {
        vec2 aim = touchAnchor() * touchStrength();
        d = rotY(-0.6 * aim.x) * (rotX(0.4 * aim.y) * d);
    }
    d = uCamRot * d;

    vec3 col = world(d, uCamTravel);
    // A soft lens vignette that tightens as the lens widens.
    col *= mix(1.0, 0.72, smoothstep(0.7, 2.2, r) * (0.5 + 0.5 * k));
    col = tonemap(col * 1.1);
    // Static dither on the integer pixel: breaks sky banding, never shimmers.
    col += (uhashUnit(uhash3(uvec3(gl_FragCoord.xy, 7u))) - 0.5) / 255.0;
    fragColor = vec4(grade(col), 1.0);
}
