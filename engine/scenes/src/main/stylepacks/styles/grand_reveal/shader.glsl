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

// Grand Reveal: start on a few stones in the desert, then pull back and up
// until they turn out to be part of a geoglyph half a kilometre across.
//
// The camera's height is on a LOG scale. One cycle: hover low over a detail,
// rise (reverse dolly, boom up and tilt down together) until the view is
// straight down on the whole figure, hover there while its lines light, then
// glide down onto a different detail of the same figure. Height, ground
// target, pitch and yaw are all eased functions of uTime, so every leg starts
// and lands softly.
//
// The ground is one analytic plane, so the cost is the shading, not the
// geometry: a distance field to the figure's lines (rings, spokes, petals, a
// spiral and a zig-zag border), sand ripples and pebbles that exist only while
// the camera is low enough to resolve them (their contrast follows the pixel
// footprint, so they fade out instead of shimmering), and a haze that thickens
// with distance. Every line is anti-aliased against its own footprint and
// keeps its average brightness when it is thinner than a pixel.
//
// ---- the music ---------------------------------------------------------------
//
// The reveal lights the lines in spectrum order, ring by ring, as the camera
// rises, and each lit line breathes with its band. The passage's loudness
// lifts the hover a little higher. Lines light over seconds, never in a frame.

#define GR_TAU 6.2831853
#define GR_PI 3.1415927
/** One cycle, seconds. */
#define GR_CYCLE 46.0
/** Log10 of the hover heights, low and high. */
#define GR_LOW 0.18
#define GR_HIGH 2.78
#define GR_FOCAL 1.3
/** The figure: ring spacing, ring count, line half-width in metres. */
#define GR_RING 30.0
#define GR_RINGS 9.0
#define GR_LINE 1.5

vec3 gSun;
vec3 gSkyLow;
vec3 gSkyHigh;

float ease(float x) {
    x = clamp(x, 0.0, 1.0);
    return x * x * x * (x * (x * 6.0 - 15.0) + 10.0);
}

/** The detail the camera visits on cycle n: a point on one of the rings. */
vec2 detailPoint(float n) {
    float k = 3.0 + floor(hash11(n * 1.37 + 0.2) * 6.0);
    float a = hash11(n * 2.91 + 5.0) * GR_TAU;
    return GR_RING * k * vec2(cos(a), sin(a));
}

/**
 * Distance in metres to the nearest line of the figure, and which line: .y is
 * that line's place in the spectrum (0..1), the order in which it lights.
 */
vec2 glyph(vec2 q) {
    float r = length(q);
    float a = atan(q.y, q.x);
    // Rings.
    float k = clamp(floor(r / GR_RING + 0.5), 1.0, GR_RINGS);
    vec2 best = vec2(abs(r - GR_RING * k), (k - 1.0) / (GR_RINGS - 1.0) * 0.8);
    // Twelve spokes between the first and last ring.
    float sector = GR_TAU / 12.0;
    float sa = mod(a + 0.5 * sector, sector) - 0.5 * sector;
    float spoke = r * abs(sin(sa));
    spoke = max(spoke, max(GR_RING - r, r - GR_RING * GR_RINGS));
    if (spoke < best.x) best = vec2(spoke, 0.45);
    // Eight petals: r = 150 + 75 cos(8a), its distance divided by the gradient.
    float pr = 150.0 + 75.0 * cos(8.0 * a);
    float dpr = -600.0 * sin(8.0 * a);
    float petal = abs(r - pr) / sqrt(1.0 + (dpr / max(r, 1.0)) * (dpr / max(r, 1.0)));
    if (petal < best.x) best = vec2(petal, 0.6);
    // A spiral at the heart.
    if (r < GR_RING - 2.0) {
        float pitch = 3.6;
        float turns = (r / pitch - a) / GR_TAU;
        float spiral = pitch * GR_TAU * abs(fract(turns + 0.5) - 0.5);
        if (spiral < best.x) best = vec2(spiral, 0.95);
    }
    // A zig-zag border just outside the last ring.
    float zr = GR_RING * GR_RINGS + 12.0;
    float zz = abs(fract(a / GR_TAU * 72.0) - 0.5) * 2.0;
    float zline = zr + 8.0 * (zz - 0.5);
    float zslope = 8.0 * 2.0 * 72.0 / GR_TAU;
    float zig = abs(r - zline) / sqrt(1.0 + (zslope / max(r, 1.0)) * (zslope / max(r, 1.0)));
    if (zig < best.x) best = vec2(zig, 1.0);
    return best;
}

/** Sand: ripples and a slow mottle, as a height in metres, for the low passes. */
float sandHeight(vec2 p) {
    float dir = p.x * 0.8 + p.y * 0.6;
    float ripple = sin(dir * 5.0 + 1.5 * vnoise3(vec3(p * 0.4, 1.0))) * 0.012;
    return ripple + 0.05 * vnoise3(vec3(p * 0.15, 4.0));
}

vec3 skyColour(vec3 d) {
    float up = clamp(d.y, 0.0, 1.0);
    vec3 col = mix(gSkyLow, gSkyHigh, pow(up, 0.6));
    float sc = max(dot(d, gSun), 0.0);
    col += mix(vec3(1.0, 0.8, 0.55), pal(0.06), 0.2) * (0.25 * pow(sc, 8.0) + 0.8 * smoothstep(0.9985, 0.9995, sc));
    return col;
}

vec3 groundColour(vec3 ro, vec3 rd, float reveal, out float hitDist) {
    float t = -ro.y / rd.y;
    hitDist = t;
    vec2 p = ro.xz + rd.xz * t;
    // The pixel's footprint on the ground, in metres.
    float fp = max(length(fwidth(p)), 1e-3);

    // Desert albedo: broad patches that read from altitude.
    float patchN = fbm3(vec3(p * 0.012, 2.0), 3);
    float varnish = smoothstep(0.45, 0.7, fbm3(vec3(p * 0.05, 7.0), 3));
    vec3 sand = mix(vec3(0.66, 0.48, 0.32), vec3(0.5, 0.33, 0.22), patchN);
    // Darker desert varnish on the stony ground: the contrast the cleared lines cut through.
    sand = mix(sand, vec3(0.3, 0.2, 0.15), varnish * 0.55);
    sand = mix(sand, pal(0.08) * 0.6, 0.18);

    // Low-altitude detail, faded by footprint: ripples light the normal,
    // pebbles darken and shadow.
    float near = 1.0 - smoothstep(0.04, 0.25, fp);
    vec3 n = vec3(0.0, 1.0, 0.0);
    float pebbleShade = 1.0;
    if (near > 0.0) {
        float e = 0.05;
        float h0 = sandHeight(p);
        float hx = sandHeight(p + vec2(e, 0.0));
        float hz = sandHeight(p + vec2(0.0, e));
        n = normalize(mix(vec3(0.0, 1.0, 0.0), vec3(-(hx - h0) / e, 1.0, -(hz - h0) / e), near));
        vec2 pc = p / 0.35;
        vec2 cell = floor(pc);
        vec2 f = fract(pc) - 0.5;
        float ph = hashCell(ivec3(ivec2(cell), 21));
        vec2 off = vec2(hashCell(ivec3(ivec2(cell), 22)), hashCell(ivec3(ivec2(cell), 23))) - 0.5;
        float rad = 0.08 + 0.14 * ph;
        float stone = length(f - off * 0.4) - rad;
        // The shadow falls away from the sun.
        float shadow = length(f - off * 0.4 + gSun.xz * 0.12) - rad;
        float fpc = fp / 0.35;
        if (ph > 0.55) {
            pebbleShade = mix(1.0, 0.75, (1.0 - smoothstep(-fpc, fpc, shadow)) * near);
            float body = (1.0 - smoothstep(-fpc, fpc, stone)) * near;
            // A rounded stone: lit on the sun side, darker basalt or pale quartz by its hash.
            vec2 rel = (f - off * 0.4) / rad;
            float lit = clamp(0.85 + 0.6 * dot(rel, gSun.xz / max(length(gSun.xz), 1e-3)), 0.45, 1.5);
            float tone = ph > 0.85 ? 1.25 : 0.9;
            pebbleShade = mix(pebbleShade, tone * lit, body);
        }
    }

    // The figure: cleared, paler stone in the lines, a darker ridge of the
    // stones pushed aside along each edge.
    vec2 g = glyph(p);
    float w = GR_LINE * (g.y > 0.9 ? 0.7 : 1.0);
    float thin = min(1.0, w / fp);
    float line = (1.0 - smoothstep(w - fp, w + fp, g.x)) * max(thin, 0.6);
    float ridge = (1.0 - smoothstep(w + 0.3 - fp, w + 0.5 + fp, g.x)) * smoothstep(w - fp, w + fp, g.x) * thin;
    vec3 albedo = sand * pebbleShade;
    albedo = mix(albedo, albedo * 0.7, ridge * 0.6);
    albedo = mix(albedo, mix(vec3(0.86, 0.76, 0.62), pal(g.y) * 0.9, 0.25), line);

    // Key, sky fill and a bounce: a low sun rakes the ripples.
    float key = max(dot(n, gSun), 0.0);
    vec3 sunCol = mix(vec3(1.0, 0.85, 0.65), pal(0.06), 0.2);
    vec3 col = albedo * (sunCol * key * 1.05 + gSkyHigh * 0.35 + vec3(0.08, 0.05, 0.03));

    // The reveal: lines glow in spectrum order, each with its band.
    float lit = smoothstep(g.y * 0.75, g.y * 0.75 + 0.25, reveal);
    float band = aband(0.04 + 0.85 * g.y);
    vec3 glow = mix(pal(0.15 + 0.7 * g.y), vec3(1.0, 0.85, 0.6), 0.25);
    col += glow * line * lit * (0.22 + 0.18 * clamp(band, 0.0, 1.2));

    // Aerial haze: thick along the horizon, thin straight down.
    float haze = 1.0 - exp(-t * 0.00045 - max(0.0, t - 400.0) * 0.002 * (1.0 + rd.y));
    return mix(col, gSkyLow * 0.95, haze);
}

vec3 tonemap(vec3 c) {
    c = max(c, 0.0);
    return clamp(c * (2.51 * c + 0.03) / (c * (2.43 * c + 0.59) + 0.14), 0.0, 1.0);
}

void main() {
    vec2 uv = cameraView();
    gSun = normalize(vec3(0.55, 0.32, 0.4));
    gSkyLow = mix(vec3(0.95, 0.75, 0.58), pal(0.08), 0.2);
    gSkyHigh = mix(vec3(0.35, 0.55, 0.85), pal(0.6), 0.25);

    // ---- the cycle -----------------------------------------------------------------
    float n = floor(uTime / GR_CYCLE);
    float ph = uTime / GR_CYCLE - n;
    float rise = ease((ph - 0.16) / 0.38);
    float fall = ease((ph - 0.72) / 0.28);
    // 0 low, 1 high: up on the rise, back down on the fall.
    float altitude = rise * (1.0 - fall);
    vec2 from = detailPoint(n);
    vec2 to = detailPoint(n + 1.0);
    vec2 target = ph < 0.72 ? mix(from, vec2(0.0), rise) : mix(vec2(0.0), to, fall);
    float logH = mix(GR_LOW, GR_HIGH, altitude);
    float height = pow(10.0, logH) * (1.0 + 0.2 * clamp(uSwell, 0.0, 1.2) * altitude) * (1.0 - 0.5 * uCamPush);
    // The lines dim while the camera is low and light again as it rises.
    float reveal = ph < 0.16 ? 1.0 - ease(ph / 0.16) : (ph < 0.72 ? rise : 1.0);

    // Pitch tips from a raking view to straight down as the camera climbs.
    float pitch = mix(-0.4, -0.5 * GR_PI + 0.001, ease(altitude * 1.15));
    float yaw = uTime * 0.02 + 0.6 * sin(uTime * 0.011);
    if (!touchIdle()) {
        vec2 aim = touchAnchor() * touchStrength();
        yaw += 0.5 * aim.x;
        pitch = clamp(pitch + 0.3 * aim.y, -0.5 * GR_PI + 0.001, -0.1);
    }
    vec3 fwd = vec3(cos(pitch) * sin(yaw), sin(pitch), cos(pitch) * cos(yaw));
    vec3 right = vec3(cos(yaw), 0.0, -sin(yaw));
    vec3 up = cross(fwd, right);
    // Stand back from the target so it sits in the middle of the frame.
    vec2 back = vec2(sin(yaw), cos(yaw)) * height / tan(-pitch);
    vec3 ro = vec3(target.x - back.x, height, target.y - back.y);
    vec3 rd = normalize(uv.x * right + uv.y * up + GR_FOCAL * fwd);
    rd = uCamRot * rd;

    vec3 col;
    float dist = 0.0;
    float grazing = rd.y;
    // Ground and sky are blended across the horizon over a pixel, so the
    // horizon line never stair-steps.
    float hw = max(fwidth(grazing), 1e-4);
    vec3 skyCol = skyColour(rd);
    if (grazing < hw) {
        vec3 rdG = rd;
        rdG.y = min(rdG.y, -1e-4);
        vec3 ground = groundColour(ro, rdG, reveal, dist);
        col = mix(ground, skyCol, smoothstep(-hw, hw, grazing));
    } else {
        col = skyCol;
    }

    col *= mix(1.0, 0.75, smoothstep(0.7, 2.1, length(uv)));
    col = tonemap(col * 1.05);
    col += (uhashUnit(uhash3(uvec3(gl_FragCoord.xy, 17u))) - 0.5) / 255.0;
    fragColor = vec4(grade(col), 1.0);
}
