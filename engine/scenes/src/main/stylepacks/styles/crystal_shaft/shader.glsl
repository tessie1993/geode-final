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
//#include lib_style

// Crystal Shaft: climbing a hexagonal shaft lined with crystals, looking up it
// toward the light at the top.
//
// THE WORLD. The shaft is a hexagonal prism standing on +y. Its six faces are
// one face folded six ways round the axis (csFold), and each face is cut into
// two columns of slots, repeated up the shaft. Most slots hold a crystal: a
// hexagonal prism grown out of the wall, tilted upward and leaning a little
// sideways, ending in a six-sided point. The point costs nothing extra: the
// hexagon's own distance is a polyhedral norm, so adding a share of it to the
// distance along the axis is already a six-faced pyramid.
//
// THE CRYSTALS ARE GLASS, faked. Each facet's flat normal gives the hard
// faceted sparkle. The light it shows is three things: the shaft's light
// reflected, by the Fresnel term; the same light refracted through the facet,
// a little differently per channel so the edges disperse; and an inner glow
// that is brightest where the refracted ray runs deep into the crystal (the
// field read a short way along it), so every facet has its own lit core.
//
// THE CLIMB. The camera rises on the shaft's axis, a little off it, looking
// up past the crystals toward the light, and the view turns slowly about the
// axis as it climbs. Far up the shaft, the haze turns to light, so the
// vanishing point is the brightest thing in the frame.
//
// AUDIO. Energy sets the climb rate through uFlowPhase (integrated: it eases,
// never jumps). Mids light the crystals' cores, treble the facet glints, and
// bass the light at the top. A beat sends a ring of light climbing the shaft
// ahead of the camera: uBeatPhase is how far up it has gone, the beat
// envelope how bright, so it swells rather than flashes.
//
// WHY THE ESTIMATE IS SAFE. Crystals tilt upward out of their slot, so one can
// reach into the slot above: each point checks its own slot and the one below.
// The pyramid term's gradient is sqrt(1 + 0.55^2), divided out (0.87), and
// the sideways lean keeps every crystal inside its column; the march steps
// 0.75 of the estimate for what remains.

#define CS_MAX_STEPS 88
#define CS_FOCAL 1.2
#define CS_FAR 40.0

/** The shaft's apothem: the distance from its axis to the middle of a face. */
const float CS_R = 2.3;
const float CS_CELL_Y = 1.3;
/** Half a face's width, and so one slot's: two slots side by side on each face. */
const float CS_SLOT = 1.3279;
const float CS_SECTOR = 1.0471976;

float gHue;
float gRad;
float gCrystal;

float csHex(vec2 p) {
    p = abs(p);
    return max(p.x * 0.8660254 + p.y * 0.5, p.y);
}

/**
 * One crystal in its slot. l is the point relative to the slot's centre on the
 * wall: x inward from the wall, y up, z along the face. h is the slot's hash.
 */
float csCrystal(vec3 l, float h) {
    if (h < 0.16) return 1e3;
    float len = 0.6 + 0.5 * fract(h * 7.13);
    float rad = 0.12 + 0.12 * fract(h * 3.71);
    float tilt = 0.35 + 0.45 * fract(h * 11.3);
    float lean = (fract(h * 5.93) - 0.5) * 0.45;
    vec3 b = l - vec3(0.0, (fract(h * 2.31) - 0.5) * 0.5, (fract(h * 9.71) - 0.5) * 0.4);
    // Turn the crystal's axis (inward, tilted up, leaning) onto +x.
    b.xy = rot2(tilt) * b.xy;
    b.xz = rot2(lean) * b.xz;
    float hx = csHex(b.yz);
    float side = hx - rad;
    float tip = (b.x + hx * 0.55 - len) * 0.87;
    float base = -b.x - 0.45;
    float d = max(max(side, tip), base);
    if (d < gCrystal) {
        gCrystal = d;
        gHue = h;
        gRad = rad;
    }
    return d;
}

float csMap(vec3 p) {
    gCrystal = 1e3;
    float wall = CS_R - csHex(p.xz);
    // Fold onto one face: its normal on +x, the wall at x = CS_R.
    float a = atan(p.z, p.x);
    float k = floor(a / CS_SECTOR);
    vec2 q = rot2((k + 0.5) * CS_SECTOR) * p.xz;
    float slot = q.y > 0.0 ? 1.0 : 0.0;
    vec3 l = vec3(CS_R - q.x, 0.0, q.y - (slot - 0.5) * CS_SLOT);
    float yi = floor(p.y / CS_CELL_Y);
    float d = wall;
    for (int j = 0; j < 2; j++) {
        float cy = yi - float(j);
        l.y = p.y - (cy + 0.5) * CS_CELL_Y;
        d = min(d, csCrystal(l, motionHash(vec2(k * 2.0 + slot + 7.5, cy))));
    }
    return d;
}

vec3 csNormal(vec3 p, float eps) {
    vec2 k = vec2(1.0, -1.0);
    return normalize(k.xyy * csMap(p + k.xyy * eps) + k.yyx * csMap(p + k.yyx * eps)
                   + k.yxy * csMap(p + k.yxy * eps) + k.xxx * csMap(p + k.xxx * eps));
}

/** The light the shaft is full of, seen along d: brightest straight up, toward the top. */
vec3 csLight(vec3 d, float bass) {
    float up = max(d.y, 0.0);
    return pal(0.45) * 0.06 + pal(0.5) * pow(up, 10.0) * 0.9 + vec3(1.0, 0.97, 0.92) * pow(up, 60.0) * (0.6 + 0.5 * bass);
}

/**
 * Where a finger rests the head turns toward it: x yaws, y pitches, scaled by
 * how firmly it is held, so a release glides the view home. Exactly zero with
 * nothing touched, which leaves the frame bit-identical.
 */
vec2 touchLook() {
    if (touchIdle()) return vec2(0.0);
    return touchAnchor() * touchStrength();
}

void main() {
    vec2 uv = view();
    float bass = min(uBassSmooth, 1.3);
    float mid = min(uMidSmooth, 1.3);
    float treb = min(uTrebleSmooth, 1.3);
    float beatEnv = clamp(uBeat, 0.0, 1.0) * clamp(uBeatResponse, 0.0, 2.0);

    vec3 rdl = normalize(vec3(uv, CS_FOCAL));
    vec2 look = touchLook();
    if (look != vec2(0.0)) rdl = rotY(look.x * 0.6) * rotX(-look.y * 0.4) * rdl;
    vec3 camOff = vec3(0.0);
    cameraFlight(camOff, rdl);

    // The climb, INTEGRATED: the Speed control through uTime, loudness through uFlowPhase.
    float y = uTime * 0.8 + uFlowPhase * 4.5 + camOff.z;
    vec3 ro = vec3(0.32 * sin(y * 0.21 + 1.0), y, 0.32 * cos(y * 0.17));
    // Looking up the shaft, a little off vertical, turning slowly about it.
    vec3 fw = normalize(vec3(0.14 * sin(uTime * 0.11), 1.0, 0.14 * cos(uTime * 0.09)));
    vec3 rt = normalize(cross(vec3(0.0, 0.0, 1.0), fw));
    vec3 up = cross(fw, rt);
    float spin = uTime * 0.07 + 0.3 * sin(uTime * 0.05);
    vec3 rgt = cos(spin) * rt + sin(spin) * up;
    vec3 upr = -sin(spin) * rt + cos(spin) * up;
    vec3 rd = mat3(rgt, upr, fw) * rdl;

    float budget = uSteps * (float(CS_MAX_STEPS) / 128.0);
    float t = 0.05;
    bool hit = false;
    for (int i = 0; i < CS_MAX_STEPS; i++) {
        if (float(i) >= budget || t > CS_FAR) break;
        float d = csMap(ro + rd * t);
        float eps = 0.0015 + 0.0012 * t;
        if (d < eps) {
            hit = true;
            break;
        }
        t += max(d * 0.75, eps);
    }

    vec3 haze = mix(pal(0.48), vec3(0.9, 0.95, 1.0), 0.3) * (0.45 + 0.2 * bass);
    vec3 col = csLight(rd, bass);
    if (hit) {
        vec3 p = ro + rd * t;
        float dc = csMap(p);
        bool crystal = gCrystal <= dc + 1e-4;
        float hue = gHue;
        float rad = gRad;
        vec3 n = csNormal(p, 0.0015 + 0.0012 * t);
        float fres = pow(1.0 - abs(dot(n, -rd)), 3.0);
        vec3 refl = csLight(reflect(rd, n), bass);
        if (crystal) {
            // Refracted light, a slightly different index per channel.
            vec3 through = vec3(csLight(refract(rd, n, 0.70), bass).r,
                                csLight(refract(rd, n, 0.67), bass).g,
                                csLight(refract(rd, n, 0.64), bass).b);
            // The inner glow: how deep the refracted ray runs into the crystal.
            vec3 rr = refract(rd, n, 0.67);
            float inside = -csMap(p + rr * 0.3);
            float core = clamp(inside / max(rad, 0.05), 0.0, 1.0);
            vec3 inner = pal(0.15 + 0.5 * hue) * (0.25 + 0.6 * mid);
            // The beat's ring of light, climbing the shaft ahead of the camera.
            float band = p.y - (ro.y + uBeatPhase * 14.0);
            inner += pal(0.25 + 0.5 * hue) * beatEnv * beatEnv * exp(-band * band * 0.6) * 0.9;
            col = inner * (0.35 + 1.6 * core) + through * 0.5;
            col = mix(col, refl * 1.4, fres);
            col += vec3(1.0) * pow(max(dot(reflect(rd, n), vec3(0.0, 1.0, 0.0)), 0.0), 50.0) * (0.3 + 1.2 * treb);
        } else {
            // The shaft's wall: dark glass with faint veins of light.
            float vein = smoothstep(0.03, 0.0, abs(motionNoise(vec2(atan(p.z, p.x) * 3.0, p.y * 0.6)) - 0.5));
            col = pal(0.6) * 0.04 + refl * 0.35 * fres + pal(0.45 + 0.1 * sin(p.y * 0.3)) * vein * (0.08 + 0.2 * mid);
        }
        // Far up the shaft the haze is lit, so depth fades into light.
        col = mix(col, haze, 1.0 - exp(-t * 0.055 * xm(uXFog)));
    } else {
        col = mix(col, haze, 0.6);
    }

    if (!touchIdle()) {
        col += pal(0.5 + 0.15 * sin(uTime * 0.05)) * min(touchWake(uv), 3.0) * 0.05;
    }
    col *= 0.72 + 0.28 * smoothstep(2.2, 0.5, length(uv));
    fragColor = vec4(grade(col), 1.0);
}
