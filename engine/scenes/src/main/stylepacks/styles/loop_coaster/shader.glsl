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

// Loop Coaster: riding a rollercoaster through a neon landscape, loops and
// corkscrews included.
//
// THE TRACK is one closed-form curve, built a section at a time. Each section
// is one turn of a trochoid,
//
//     z = a u + bz sin u,   y = b (1 - cos u),   x = bx sin u + c (1 - cos u) / 2,
//
// and a section's numbers pick what it is: bz > a makes the curve run
// backwards over its top - a vertical loop, ridden upside down; bx = b makes
// it a helix about the line y = b - a corkscrew; small b and nothing else is a
// camel-back hill. Every section starts and ends at the bottom of its turn,
// where all three agree (y = 0, heading +z), and the numbers cross-fade there,
// so the track is smooth wherever one element hands over to the next. A slow
// sideways meander on top keeps it from ever running dead straight.
//
// THE CAMERA rides the track: the eye on the curve, looking slightly ahead
// along it. A fixed world-up flips in a loop, so up comes from the element
// instead: toward the axis the loop or helix turns about (which IS the
// rotation-minimising frame for a circle or helix), crossfaded to the world's
// up on the hills. The pose is the same for every pixel and costs a handful of
// path evaluations.
//
// THE RAILS are capsules along a window of the track ahead of the camera,
// precomputed once per pixel into arrays so the march only measures them, with
// a fat spine capsule as the bound that skips the rails when the ray is far.
// The landscape is analytic: a grid floor of thin glowing lines, a ridge line
// of mountains and a banded sun, all looked up by direction or plane hit, so
// they cost nothing per step.
//
// THE MUSIC. Speed along the track is integrated loudness (uFlowPhase) over a
// steady crawl - a loud passage is the drop, a quiet one the lift hill - and
// never a clock times a level. Bass brightens the rails' neon through uSwell,
// treble the sun's shimmer, a new form (uFormPhase) slides the palette.
//
// COMFORT AND NO-FLASH. The parameter speed is held so a loop or corkscrew
// takes 5 to 9 seconds: peak rotation stays near 60 degrees a second. The
// grid lines are 4 units apart and thin, so even at the bottom of a loop they
// pass a region under twice a second.

#define LC_TAU 6.2831853
/** a * 2 pi = 8: one section advances 8 units, which the 4-unit grid divides. */
#define LC_A 1.2732395
#define LC_WIN 20
#define LC_DU 0.36
#define LC_GAUGE 0.27
#define LC_MAX_STEPS 80
#define LC_FAR 45.0
#define LC_GROUND -3.2

/** A section's numbers: (bz, bx, b, c) and its inversion weight. */
vec4 lcSection(float k, out float inv) {
    float h = hash11(mod(k, 1024.0) * 0.731 + 11.0);
    if (h < 0.38) {
        inv = 1.0;
        return vec4(1.95, 0.0, 1.95, 0.7);
    }
    if (h < 0.68) {
        inv = 1.0;
        return vec4(0.0, 1.35, 1.35, 0.0);
    }
    inv = 0.0;
    return vec4(0.0, 0.0, 0.55 + 0.6 * hash11(k * 1.3 + 2.0), 0.0);
}

/** The section numbers at local parameter u of section k, cross-faded at the joins. */
vec4 lcParams(float k, float u, out float inv) {
    float invA;
    float invB;
    vec4 a;
    vec4 b;
    float w;
    if (u < 0.5 * LC_TAU) {
        a = lcSection(k - 1.0, invA);
        b = lcSection(k, invB);
        w = smoothstep(-0.9, 0.9, u);
    } else {
        a = lcSection(k, invA);
        b = lcSection(k + 1.0, invB);
        w = smoothstep(LC_TAU - 0.9, LC_TAU + 0.9, u);
    }
    inv = mix(invA, invB, w);
    return mix(a, b, w);
}

/**
 * The track at section k0 + kRel, local parameter u, in coordinates relative
 * to the start of section k0 - so the numbers stay small however far the ride
 * has gone. upInv is the element's own up: toward its turning axis.
 */
vec3 lcPath(float k0, float kRel, float u, out vec3 up) {
    float k = k0 + kRel;
    float inv;
    vec4 s = lcParams(k, u, inv);
    float su = sin(u);
    float cu = cos(u);
    vec3 p = vec3(s.y * su + s.w * 0.5 * (1.0 - cu), s.z * (1.0 - cu), LC_A * (LC_TAU * kRel + u) + s.x * su);
    // The meander: five sections to a swing.
    float mph = mod(k, 5.0) * LC_TAU + u;
    p.x += 2.4 * sin(mph / 5.0);
    vec3 upInv = vec3(-s.y * su, s.z * cu, -s.x * su);
    up = normalize(mix(vec3(0.0, 1.0, 0.0), normalize(upInv + vec3(0.0, 1e-4, 0.0)), inv));
    return p;
}

/** The track at parameter offset du from the camera's section start. */
vec3 lcAt(float k0, float u, out vec3 up) {
    float kr = floor(u / LC_TAU);
    return lcPath(k0, kr, u - kr * LC_TAU, up);
}

vec3 gRailL[LC_WIN];
vec3 gRailR[LC_WIN];
vec3 gSpine[LC_WIN];
float gGlow;

float lcCapsule(vec3 p, vec3 a, vec3 b, float r) {
    vec3 pa = p - a;
    vec3 ba = b - a;
    float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
    return length(pa - ba * h) - r;
}

float map(vec3 p) {
    float d = 1e9;
    float rails = 1e9;
    for (int i = 0; i < LC_WIN - 1; i++) {
        float spine = lcCapsule(p, gSpine[i], gSpine[i + 1], 0.07);
        // The fat spine bounds both rails: skip them while the ray is far.
        if (spine - 0.38 < d) {
            d = min(d, spine);
            rails = min(rails, lcCapsule(p, gRailL[i], gRailL[i + 1], 0.032));
            rails = min(rails, lcCapsule(p, gRailR[i], gRailR[i + 1], 0.032));
        } else {
            d = min(d, spine);
        }
    }
    gGlow = rails;
    return min(d, rails);
}

vec3 normalAt(vec3 p, float eps) {
    vec2 k = vec2(1.0, -1.0);
    return normalize(k.xyy * map(p + k.xyy * eps) + k.yyx * map(p + k.yyx * eps)
                   + k.yxy * map(p + k.yxy * eps) + k.xxx * map(p + k.xxx * eps));
}

/** Sky by direction: a banded synth sun, a ridge of mountains, a deep gradient. */
vec3 lcSky(vec3 rd, float hue) {
    float up = rd.y;
    vec3 col = mix(pal(hue + 0.65) * 0.22 + vec3(0.06, 0.02, 0.1), vec3(0.01, 0.01, 0.04), smoothstep(0.0, 0.7, up));
    vec2 sunD = vec2(atan(rd.x, rd.z), up - 0.12);
    float sr = length(sunD);
    // The lower half of the sun is cut into bands that widen toward the horizon.
    float bands = mix(1.0, step(0.0, sin(sunD.y * 70.0 - 0.6)), smoothstep(0.02, -0.06, sunD.y));
    vec3 sunCol = mix(pal(hue + 0.08), vec3(1.0, 0.8, 0.4), 0.4) * mix(1.0, 0.55, smoothstep(0.15, -0.15, sunD.y));
    col = mix(col, sunCol * 0.9, smoothstep(0.32, 0.3, sr) * bands);
    col += sunCol * 0.18 * exp(-sr * 4.0);
    // Mountains: a ridge line over azimuth, dark against the sky and sun.
    float az = atan(rd.x, rd.z);
    float ridge = 0.05 + 0.07 * abs(sin(az * 3.0 + 1.0)) + 0.05 * abs(sin(az * 7.3 + 2.0)) + 0.02 * sin(az * 17.0);
    float mtn = smoothstep(ridge + 0.004, ridge - 0.004, up) * step(-0.02, up);
    vec3 mtnCol = vec3(0.02, 0.01, 0.04) + pal(hue + 0.6) * 0.05 * smoothstep(-0.02, ridge, up);
    col = mix(col, mtnCol, mtn);
    // The mountains' edge catches the light.
    col += pal(hue + 0.55) * 0.25 * exp(-abs(up - ridge) * 260.0) * step(-0.02, up);
    return col;
}

vec3 lcTone(vec3 c) {
    return 1.0 - exp(-max(c, 0.0) * 1.35);
}

void main() {
    vec2 uv = view();
    float hue = 0.78 + 0.25 * uFormPhase;
    float swell = clamp(uSwell, 0.0, 1.0);

    // Where the car is: integrated loudness over a crawl, plus the Camera's push.
    float s = uTime * 0.55 + uFlowPhase * 1.6 + uCamPush * 0.6;
    float k0 = floor(s / LC_TAU);
    float u0 = s - k0 * LC_TAU;

    vec3 upC;
    vec3 pos = lcAt(k0, u0, upC);
    vec3 upAhead;
    vec3 ahead = lcAt(k0, u0 + 0.06, upAhead);
    vec3 upLook;
    vec3 look = lcAt(k0, u0 + 0.55, upLook);
    vec3 tangent = normalize(ahead - pos);
    vec3 fw = normalize(mix(tangent, normalize(look - pos), 0.3));
    vec3 upv = normalize(upC - fw * dot(upC, fw));
    vec3 rt = cross(upv, fw);
    vec3 ro = pos + upv * 0.32;

    // The rails ahead, once per pixel.
    for (int i = 0; i < LC_WIN; i++) {
        float u = u0 - 0.4 + float(i) * LC_DU;
        vec3 up;
        vec3 p = lcAt(k0, u, up);
        vec3 up2;
        vec3 p2 = lcAt(k0, u + 0.05, up2);
        vec3 tg = normalize(p2 - p);
        vec3 side = normalize(cross(up, tg));
        gRailL[i] = p + side * LC_GAUGE;
        gRailR[i] = p - side * LC_GAUGE;
        gSpine[i] = p - up * 0.14;
    }

    // A finger looks around the car.
    vec2 suv = uv + touchAnchor() * 0.45;
    vec3 local = uCamRot * normalize(vec3(suv, 1.05));
    vec3 rd = normalize(rt * local.x + upv * local.y + fw * local.z);

    float t = 0.02;
    float hitT = -1.0;
    float glow = 0.0;
    for (int i = 0; i < LC_MAX_STEPS; i++) {
        if (float(i) >= uSteps) break;
        vec3 p = ro + rd * t;
        float d = map(p);
        glow += exp(-gGlow * 25.0) * min(d, 0.5);
        float eps = 0.0008 * t + 0.0005;
        if (d < eps) { hitT = t; break; }
        t += d * 0.9;
        if (t > LC_FAR) break;
    }

    vec3 sky = lcSky(rd, hue);
    vec3 col = sky;
    // The grid floor.
    float tg = rd.y < 0.0 ? (LC_GROUND - ro.y) / rd.y : 1e9;
    float tScene = hitT > 0.0 ? hitT : 1e9;
    if (tg < tScene && tg < 200.0) {
        vec3 p = ro + rd * tg;
        // World z: the section base is a whole number of 8-unit sections, so the 4-unit grid is seamless.
        vec2 g = vec2(p.x, p.z) / 4.0;
        vec2 f = abs(fract(g) - 0.5);
        float px = tg * 0.004 + 0.01;
        float lines = max(smoothstep(0.5 - px, 0.5, f.x), smoothstep(0.5 - px, 0.5, f.y));
        vec3 floorCol = vec3(0.01, 0.005, 0.02) + pal(hue + 0.55) * lines * (0.5 + 0.3 * swell) * exp(-tg * 0.02);
        floorCol += pal(hue + 0.1) * 0.06 * exp(-abs(p.x) * 0.3);
        float horizonFade = smoothstep(120.0, 40.0, tg);
        col = mix(sky, floorCol, horizonFade);
    }
    if (hitT > 0.0) {
        vec3 p = ro + rd * hitT;
        map(p);
        bool isRail = gGlow < 0.004;
        vec3 n = normalAt(p, 0.0015 + 0.0008 * hitT);
        vec3 sunDir = normalize(vec3(0.0, 0.25, 1.0));
        vec3 v = -rd;
        float fres = pow(1.0 - max(dot(n, v), 0.0), 4.0);
        float spec = pow(max(dot(reflect(-sunDir, n), v), 0.0), 50.0);
        vec3 refl = lcSky(reflect(rd, n), hue);
        if (isRail) {
            vec3 neon = pal(hue + 0.02) * (1.2 + 0.6 * swell);
            col = neon + refl * 0.2;
        } else {
            vec3 metal = vec3(0.12, 0.12, 0.15);
            float diff = max(dot(n, sunDir), 0.0);
            col = metal * (0.25 + 0.9 * diff) + refl * (0.25 + 0.6 * fres) + vec3(1.0, 0.8, 0.6) * spec * 0.8;
            col += pal(hue + 0.55) * max(dot(n, -sunDir), 0.0) * 0.12;
        }
        col = mix(col, lcSky(rd, hue) * 0.7, 1.0 - exp(-hitT * 0.025 * xm(uXFog)));
    }
    col += pal(hue + 0.02) * glow * 0.22 * (0.8 + 0.4 * swell);

    if (!touchIdle()) {
        col += pal(hue + 0.5) * min(touchWake(uv), 2.0) * 0.05;
    }
    col = lcTone(col);
    col *= 0.75 + 0.25 * smoothstep(2.0, 0.4, length(uv));
    fragColor = vec4(grade(col), 1.0);
}
