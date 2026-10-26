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
//#include lib_style
//#include lib_camera

// Star Gate: the slit-scan corridor - a fast dolly between planes of
// streaming light.
//
// THE MOVE is a constant fast dolly down +z between four infinite planes,
// floor, ceiling and two walls. Each plane is ruled into lanes along the
// travel axis and every lane carries a long exposure of a light: bands of
// colour smeared along z, so the corridor reads like the slit-scan pass in
// 2001, the screen pouring out of a vanishing point.
//
// HOW. No march: a ray meets each plane analytically and the nearest hit wins.
// The pattern coordinate is (lateral, depth + travel). The SMEAR follows the
// speed the way a real long exposure would: fine streak detail is there when
// the dolly is slow and is averaged away as it speeds up, so the faster the
// flight the longer and smoother the streaks.
//
// THE MUSIC. Spectrum band k drives lane k on every plane, so the spectrum
// literally streams past. Travel is integrated loudness (uFlowPhase) on top of
// a steady cruise - never a clock times a level. uSwell parts the planes, so a
// loud passage opens the corridor out; a new form (uFormPhase) slides every
// lane's hue at constant luminance.
//
// NO-FLASH. A bright repeat passing a screen region is the risk here, and the
// numbers keep it under the limit: the streak pattern varies over cells 8
// units long, and the fastest dolly (about 16 units a second) passes them at
// about 2 per second. The fine detail is held to a contrast under the
// limiter's threshold and fades out as the speed climbs.
//
// The pattern is periodic over 1000 units along z (125 cells), the Camera's
// own travel period, so the flight wraps without a seam.

#define SG_CELL 8.0
#define SG_CELLS 125.0
#define SG_LANES 8.0
#define SG_FOCAL 1.15
#define SG_H 1.0
#define SG_W 1.55
/** World units per second of uFlowPhase, and the steady cruise underneath it. */
#define SG_FLOW 30.0
#define SG_CRUISE 4.0

/** Smooth 1D noise along z, periodic over SG_CELLS cells, one stream per lane seed. */
float sgStream(float z, float seed) {
    float c = floor(z);
    float f = z - c;
    float a = hash11(mod(c, SG_CELLS) + seed * 131.0);
    float b = hash11(mod(c + 1.0, SG_CELLS) + seed * 131.0);
    f = f * f * (3.0 - 2.0 * f);
    return mix(a, b, f);
}

/** One plane: lanes across `lat` (in lane units), streams along `z`. */
vec3 sgPlane(float lat, float z, float face, float hue, float smear) {
    float lane = floor(lat);
    float fl = lat - lane;
    float seed = lane + face * 17.0;
    float band = aband(fract((lane + 0.5) / SG_LANES + face * 0.13));
    float coarse = sgStream(z / SG_CELL + hash11(seed) * 40.0, seed);
    // Fine streak detail, smeared away as the dolly speeds up.
    float fine = sgStream(z * 0.5 + hash11(seed + 3.0) * 90.0, seed + 7.0) - 0.5;
    float v = 0.18 + 0.82 * smoothstep(0.2, 0.85, coarse) + fine * 0.24 * smear;
    // Lane gaps, antialiased on their own footprint.
    float aa = fwidth(lat) + 1e-4;
    float gap = smoothstep(0.0, 0.06 + aa, fl) * smoothstep(1.0, 0.94 - aa, fl);
    // The gaps blur shut in the distance instead of shimmering.
    gap = mix(gap, 0.85, smoothstep(0.08, 0.5, aa));
    vec3 c = pal(hue + lane * 0.085 + face * 0.25) * v * (0.45 + 0.75 * band);
    // A hot core along the centre of each lane: the slit itself.
    c += pal(hue + lane * 0.085 + face * 0.25 + 0.05) * exp(-pow((fl - 0.5) * 7.0, 2.0)) * 0.35 * v;
    return c * gap;
}

vec3 sgTone(vec3 c) {
    return 1.0 - exp(-max(c, 0.0) * 1.3);
}

void main() {
    vec2 uv = view();
    float swell = clamp(uSwell, 0.0, 1.0);
    float hue = 0.58 + 0.35 * uFormPhase;

    // Travel, wrapped by the pattern's 1000-unit period on each term so the
    // float never grows.
    float travel = mod(mod(uFlowPhase, 1000.0 / SG_FLOW) * SG_FLOW + mod(uTime, 1000.0 / SG_CRUISE) * SG_CRUISE, 1000.0);
    float speed = SG_FLOW * (0.08 + 0.22 * clamp(uEnergySmooth, 0.0, 1.5)) + SG_CRUISE;
    float smear = exp(-speed * 0.18);

    float h = SG_H * (1.0 + 0.45 * swell) * xm(uXSize);
    float w = SG_W * (1.0 + 0.3 * swell) * xm(uXSize);

    // A slow drift of the head: a little roll and yaw, so the corridor breathes.
    float roll = 0.12 * sin(uTime * 0.071) + 0.06 * sin(uTime * 0.043);
    vec2 look = vec2(0.06 * sin(uTime * 0.05), 0.04 * sin(uTime * 0.037));
    vec2 suv = rot2(roll) * uv + look + touchAnchor() * 0.35;
    vec3 ro = vec3(0.0, 0.0, travel);
    vec3 rd = normalize(vec3(suv, SG_FOCAL));
    cameraFlight(ro, rd);

    // The four planes; the nearest hit wins.
    float tFloor = rd.y < 0.0 ? (-h - ro.y) / rd.y : 1e9;
    float tCeil = rd.y > 0.0 ? (h - ro.y) / rd.y : 1e9;
    float tLeft = rd.x < 0.0 ? (-w - ro.x) / rd.x : 1e9;
    float tRight = rd.x > 0.0 ? (w - ro.x) / rd.x : 1e9;
    float tY = min(tFloor, tCeil);
    float tX = min(tLeft, tRight);
    float t = min(tY, tX);

    // One call outside any branch, so the lane antialiasing's fwidth() is taken in uniform control flow.
    vec3 p = ro + rd * t;
    bool onY = tY < tX;
    float face = onY ? (rd.y < 0.0 ? 0.0 : 1.0) : (rd.x < 0.0 ? 2.0 : 3.0);
    float lat = onY ? (p.x / (2.0 * w) + 0.5) * SG_LANES : (p.y / (2.0 * h) + 0.5) * SG_LANES;
    vec3 col = sgPlane(lat, p.z, face, hue, smear);
    // The seams where the planes meet: four continuous rails of light.
    float seam = min(abs(abs(p.x) - w), abs(abs(p.y) - h));
    col += pal(hue + 0.5) * exp(-seam * 28.0 / (1.0 + t * 0.05)) * 0.35;

    // Depth: the corridor dissolves into the light at the vanishing point.
    vec3 vanish = pal(hue + 0.5) * 0.5 + vec3(0.25);
    float depth = 1.0 - exp(-t * 0.035 / xm(uXFog));
    col = mix(col, vanish * 0.45, depth);
    float centre = exp(-dot(suv - look, suv - look) * 9.0);
    col += vanish * centre * 0.25;

    if (!touchIdle()) {
        col += pal(hue + 0.25) * min(touchWake(uv), 2.0) * 0.05;
    }
    col = sgTone(col);
    col *= 0.75 + 0.25 * smoothstep(2.0, 0.4, length(uv));
    fragColor = vec4(grade(col), 1.0);
}
