#version 300 es
precision highp float;
precision highp sampler2D;

in vec2 vUv;
out vec4 fragColor;

//#include lib_scene_uniforms
//#include lib_scene_motion
//#include lib_palette
//#include lib_scene_grade
//#include lib_style
//#include lib_camera

// Iris Droste: an endless fall through nested shells, each opening onto the
// next through a shaped hole - petals, a polygon, a star - each in its own
// colour, down to a bright eye that never arrives.
//
// It is flat and cheap: one log-polar map and two shape lookups a pixel.
// In s = log(r) / P every shell is one unit wide and the same shape at every
// depth, so zooming in is a constant added to s, forever. Shell k owns the
// band from its opening, at s = k + b_k(angle), out to the next shell's
// opening. The depth reads from the lighting rather than from geometry: a
// lit and a shaded side across each shell's bowl, a bright lip at every
// opening, and the shadow each lip throws on the shell beneath it.
//
// Toward the centre the shells shrink below a pixel; there each one gives
// way, over its own footprint, to the eye's glow, so the middle never
// shimmers. Every edge is anti-aliased against log(r)'s footprint, which is
// the same along and across a conformal map.
//
// ---- what the music moves ---------------------------------------------------
//
// The fall is uFlight plus the Camera's travel, both integrated on the CPU;
// the default Reaction lets the bass push the travel, so the bass drives the
// speed and it only ever eases. A hit dilates every opening at once (its
// Reaction on Size) and sends a glint ring out from the eye (uSpawnAge). A
// spike walks the palette to a new plateau. Nothing brightens the frame.
//
// ---- the No-flash budget ----------------------------------------------------
//
// A screen point sees one shell edge pass per shell fallen. At Speed 1 the
// fall is at most about 0.8 shells a second of flight at full loudness and
// 0.8 of Camera travel: well under 2.5 a second.
//
// ---- periods ----------------------------------------------------------------
//
// Shells repeat their shape and colour every IR_SHELLS. uFlight wraps at 2000 pi
// (ShaderScene::kFlightWrap) and uCamTravel at 1000 (camera::kTravelWrap);
// IR_FLIGHT_RATE puts 1600 shells in the first and IR_TRAVEL_RATE 400 in the
// second, both multiples of IR_SHELLS, so neither wrap moves anything.

#define IR_TAU 6.2831853
#define IR_PI 3.14159265
#define IR_FLIGHT_WRAP 6283.1853
/** Shells before the shapes and colours repeat. */
#define IR_SHELLS 8.0
/** One shell's width in log(r): each shell is e^0.875 = 2.4 times the next. */
#define IR_SHELL_LOG 0.875
#define IR_FOCAL 1.4
/** The manifest's "effects", by position: what the thermal tiers may switch off (uFxOff). */
#define IR_FX_FIBRES 0
#define IR_FX_SHADOWS 1

/**
 * The camera's distance down a flight path, integrated on the CPU (ShaderScene, #280). Declared here
 * rather than taken from lib_dmt, whose path and shading this Style does not use.
 */
uniform float uFlight;

const float IR_FLIGHT_RATE = 1600.0 / IR_FLIGHT_WRAP;
const float IR_TRAVEL_RATE = 0.4;
/** The light, as a direction across the screen: from the upper left. */
const vec2 IR_LIGHT = vec2(-0.6, 0.8);

float gScale;
float gOpen;
int gVariant;
/**
 * 1 on the spiral variant. There one shell runs on into the next across the seam, so every shell
 * must be the same shell: one shape, one turn, and a colour that varies continuously along it.
 */
float gSpiral;

/** Value noise on a lattice that repeats every `period` cells across x. */
float irNoise(vec2 p, float period) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float x0 = mod(i.x, period);
    float x1 = mod(i.x + 1.0, period);
    float a = motionHash(vec2(x0, i.y));
    float b = motionHash(vec2(x1, i.y));
    float c = motionHash(vec2(x0, i.y + 1.0));
    float d = motionHash(vec2(x1, i.y + 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

/** Shell `k`'s opening at angle `a`, in shell units above the shell's own start. */
float irOpening(float k, float a) {
    float km = mod(k, IR_SHELLS) * (1.0 - gSpiral);
    float h = motionHash(vec2(km, 5.0));
    float h2 = motionHash(vec2(km, 11.0));
    // At least a triangle: a polygon of fewer sides has no inside to open onto.
    float n = max(xcount(5.0 + floor(h2 * 3.0)), 3.0);
    // Neighbouring shells turn opposite ways; Swirl twists each against the last.
    float turn = (mod(km, 2.0) < 0.5 ? 1.0 : -1.0) * uTime * 0.035 + km * 0.6 * uXSwirl;
    float x = a + turn;
    int kind = gVariant >= 2 ? gVariant - 2 : int(h * 3.0);
    float radius;
    if (kind == 0) {
        // Petals.
        radius = 0.8 + 0.2 * cos(n * x);
    } else if (kind == 1) {
        // A regular polygon: the distance to its edge, at most 1 at a corner.
        float sector = IR_TAU / n;
        float y = mod(x, sector) - 0.5 * sector;
        radius = cos(IR_PI / n) / cos(y);
    } else {
        // A star, its points sharpened.
        radius = mix(0.6, 1.0, pow(abs(cos(0.5 * n * x)), 3.0));
    }
    return clamp(gOpen + log(radius) / (IR_SHELL_LOG * gScale), 0.03, 0.97);
}

void main() {
    vec2 uv = view();
    float bassA = min(uBassSmooth, 1.3);
    float trebA = min(uTrebleSmooth, 1.3);
    gScale = 0.25 + 0.75 * clamp(uXScale, 0.0, 2.0);
    gOpen = 0.35 + 0.25 * clamp(uXSize, 0.0, 2.0);
    gVariant = xvariant(5);
    gSpiral = gVariant == 1 ? 1.0 : 0.0;

    // The Camera turns the gaze (the eye slides off centre) and pushes in.
    vec3 d = uCamRot * normalize(vec3(uv, IR_FOCAL));
    vec2 q = d.xy / max(d.z, 0.02) * IR_FOCAL - 0.6 * vec2(uXCenterX, uXCenterY);
    float r = max(length(q), 1e-6);
    float a = atan(q.y, q.x);
    float logR = log(r);
    float period = IR_SHELL_LOG * gScale;

    float fall = mod(mod(uFlight * IR_FLIGHT_RATE, IR_SHELLS) + uCamTravel * IR_TRAVEL_RATE + uCamPush * 1.2, IR_SHELLS);
    // The spiral variant winds one shell per turn, which joins seamlessly at the seam.
    float s = logR / period - fall + gSpiral * a / IR_TAU;
    // log(r) is continuous everywhere but the eye, and the map is conformal,
    // so its footprint is the footprint across the shells and around them.
    float aa = fwidth(logR) / period + 1e-5;

    float k0 = floor(s);
    float b0 = irOpening(k0, a);
    float shell;
    float own;
    float upper;
    if (s - k0 >= b0) {
        shell = k0;
        own = k0 + b0;
        upper = k0 + 1.0 + irOpening(k0 + 1.0, a);
    } else {
        shell = k0 - 1.0;
        own = k0 - 1.0 + irOpening(k0 - 1.0, a);
        upper = k0 + b0;
    }
    float km = mod(shell, IR_SHELLS) * (1.0 - gSpiral);
    // Where across its band this pixel sits: 0 at the shell's own opening, 1 under the next lip.
    float eOwn = s - own;
    float eUp = upper - s;
    float across = clamp(eOwn / max(eOwn + eUp, 1e-3), 0.0, 1.0);

    // ---- the shell's surface ----------------------------------------------------
    float hueBase = 0.08 + 0.4 * (1.0 - abs(2.0 * uFormPhase - 1.0));
    // A palette per shell. On the spiral the shell number is unwound by the
    // angle, so the colour is continuous across the seam; 8 shells make a
    // whole turn of the palette, so the fall's wrap changes nothing.
    float hue = gSpiral > 0.5 ? hueBase + 0.125 * (shell - a / IR_TAU) : hueBase + 0.12 * km + 0.25 * mod(km, 2.0);
    vec3 base = pal(hue);
    vec3 deep = pal(hue + 0.18) * 0.35;
    // The bowl: darker deep in, brighter where it rises to the next lip.
    vec3 col = mix(deep, base, smoothstep(0.0, 0.9, across));
    if (fxOn(IR_FX_FIBRES)) {
        // Iris fibres: fine streaks running out from the eye, faded as they
        // close in on a pixel.
        float fibres = 84.0;
        float perPixel = aa * period * fibres / IR_TAU;
        float keep = 1.0 - smoothstep(0.25, 0.7, perPixel);
        float f1 = irNoise(vec2(a / IR_TAU * fibres + km * 13.0, across * 2.5 + km * 7.0), fibres);
        float f2 = irNoise(vec2(a / IR_TAU * fibres * 0.5 + km * 3.0, across * 1.2), fibres * 0.5);
        col *= mix(1.0, 0.55 + 0.9 * f1 * f2 * 1.6, keep);
        // Crypts: a few darker pits in the band.
        col *= 1.0 - 0.35 * smoothstep(0.62, 0.8, f2) * keep;
    }
    // Light across the bowl: the side facing the light is lit, the far side shaded.
    vec2 radial = q / r;
    float facing = dot(radial, IR_LIGHT);
    col *= 0.62 + 0.38 * facing * (1.0 - 2.0 * across) + 0.25;
    // The lip at the shell's opening catches the light.
    float lipW = max(0.035, aa);
    float lip = exp(-eOwn / lipW) * (0.6 + 0.4 * max(-facing, 0.0));
    col += mix(base, vec3(1.0), 0.6) * lip * (0.5 + 0.5 * clamp(uXEdge, 0.0, 2.0) + 0.15 * trebA);
    // The next shell's lip overhangs this one: occlusion right under it, and
    // a shadow thrown away from the light.
    if (fxOn(IR_FX_SHADOWS)) {
        float shade = smoothstep(0.0, 0.3, eUp + 0.12 * facing);
        col *= 0.35 + 0.65 * shade;
    } else {
        col *= 0.6 + 0.4 * smoothstep(0.0, 0.2, eUp);
    }
    // The edge itself, anti-aliased: a thin dark seam where one shell meets the next.
    float seam = smoothstep(0.0, aa * 1.5, eUp) * smoothstep(0.0, aa * 1.5, eOwn);
    col *= 0.25 + 0.75 * seam;

    // ---- depth and the eye ---------------------------------------------------
    // The eye lights the funnel: brighter toward it, dark at the rim of the screen.
    float eyeLight = 0.45 + 1.1 / (1.0 + 6.0 * r * r);
    col *= eyeLight;
    vec3 eyeTint = pal(hueBase + 0.3);
    // Where the shells shrink below a pixel they give way to the eye's glow.
    float resolved = 1.0 - smoothstep(0.18, 0.45, aa);
    vec3 eye = mix(eyeTint, vec3(1.0, 0.97, 0.92), 0.55) * 1.4;
    col = mix(eye, col, resolved);
    float coreSize = 0.03 * (1.0 + 0.15 * uSwell + 0.06 * bassA);
    col += mix(eyeTint, vec3(1.0), 0.7) * exp(-r / coreSize) * 1.2;
    col += eyeTint * exp(-r / (coreSize * 5.0)) * 0.25;

    // A hit's glint: a ring that leaves the eye and grows out through the shells.
    float fresh = spawnFresh(1.5);
    if (fresh > 0.001) {
        float ringLog = log(0.015) + uSpawnAge * 3.2;
        float glint = exp(-pow((logR - ringLog) / max(0.06, aa * period), 2.0));
        col += mix(pal(hueBase + 0.5), vec3(1.0), 0.5) * glint * fresh * 0.6 * clamp(uBeatResponse, 0.0, 2.0);
    }

    // ---- finish ------------------------------------------------------------------
    col = vec3(1.0) - exp(-col * 1.3);
    col *= 0.72 + 0.28 * smoothstep(1.9, 0.35, length(vUv * 2.0 - 1.0));
    col += (motionHash(gl_FragCoord.xy + fract(uTime) * 61.0) - 0.5) / 255.0;
    fragColor = vec4(grade(col), 1.0);
}
