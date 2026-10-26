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

// Gyro Core: the nested rings of a giant gyroscope turning round a glowing
// core, with the camera looping slowly in through them and out again.
//
// THE GIMBAL. Four flat rings, each a rounded rectangle swept round a circle,
// nested inside one another and hinged the way a real gimbal is: the outer
// ring turns about the vertical, the next about the outer one's horizontal
// hinge, the next about that one's vertical, the innermost about its
// horizontal. Each ring is therefore evaluated in its own frame, the product
// of every turn outside it (gFrame), and the pins that join each ring to the
// one outside lie along its hinge. The core at the centre is a light: it lights
// the rings' inner faces, and its halo is the brightest thing in the frame.
//
// THE MATERIAL. Brass and steel, alternating, lit by a key, a fill and the
// core itself as a point light from inside; a Blinn highlight from the key,
// a Fresnel reflection of the dark room, and occlusion from two probes of the
// field along the normal. Round each ring's inner face runs an inlay of light
// marked off in ticks, and the march sums a glow off the inlays, so the rings
// trail neon haze.
//
// THE CAMERA loops on a tilted circle that passes just beside the core, so it
// swings out to see the whole gyroscope, dives in through the turning rings,
// passes the core and swings out again, looking at the core from out on the
// loop and more along its path close in. A ring can turn through the
// camera's path, so the field is cut away within GC_CLIP of the lens: a ring
// passing the camera opens round it rather than blacking out the frame. The
// core dims a little as the camera nears it, so passing it is a glow, not a
// glare.
//
// AUDIO. Each ring has its own band: bass the outer, mids the next, treble
// the third, the passage's swell the innermost. A ring's band lights its
// inlay and leans it a little off its spin (a bounded offset on top of the
// spin, so nothing jumps). Energy speeds every spin and the loop together
// (uFlowPhase, integrated). A beat sends a bright comet round every inlay
// (uBeatPhase is how far round it is) and swells the core (uSpike, with a
// rise): swells, never flashes.

#define GC_MAX_STEPS 80
#define GC_FOCAL 1.25
#define GC_FAR 32.0
#define GC_CLIP 0.6
#define GC_TAU 6.2831853

const vec4 GC_RADIUS = vec4(5.0, 3.9, 2.9, 2.0);
const vec4 GC_HALF_W = vec4(0.34, 0.28, 0.23, 0.19);
const vec4 GC_HALF_T = vec4(0.12, 0.1, 0.09, 0.08);
const vec3 GC_KEY = vec3(0.5698, 0.6648, -0.4827);
const vec3 GC_FILL = vec3(-0.9113, -0.2604, 0.3189);

mat3 gFrame[4];
vec4 gBand;
vec3 gCam;
float gRing;
float gInlay;
float gInlayRing;
vec3 gLocal;

float gcMap(vec3 p) {
    float d = length(p) - 0.26;
    gRing = -1.0;
    gInlay = 1e3;
    for (int k = 0; k < 4; k++) {
        vec3 q = gFrame[k] * p;
        float rad = GC_RADIUS[k];
        vec2 c = vec2(length(q.xy) - rad, q.z);
        vec2 hb = vec2(GC_HALF_T[k], GC_HALF_W[k]);
        vec2 b = abs(c) - hb;
        float ring = length(max(b, 0.0)) + min(max(b.x, b.y), 0.0) - 0.02;
        // The pins along this ring's hinge, out to the ring that holds it
        // (the outer ring's run up and down to the mount).
        float outer = k == 0 ? rad + 2.5 : GC_RADIUS[max(k - 1, 0)];
        float along = (k == 2) ? q.y : q.x;
        vec2 across = (k == 2) ? q.xz : q.yz;
        if (k == 0) {
            along = q.y;
            across = q.xz;
        }
        float mid = 0.5 * (rad + outer);
        float pin = max(length(across) - 0.07, abs(abs(along) - mid) - 0.5 * (outer - rad));
        ring = min(ring, pin);
        if (ring < d) {
            d = ring;
            gRing = float(k);
            gLocal = q;
        }
        // The inlay: a thin line of light round the ring's inner face.
        float inlay = length(vec2(c.x + hb.x + 0.01, c.y)) - 0.045;
        if (inlay < gInlay) {
            gInlay = inlay;
            gInlayRing = float(k);
        }
    }
    // Cut away round the lens, so a ring turning through the camera opens round it.
    return max(d, GC_CLIP - length(p - gCam));
}

vec3 gcNormal(vec3 p, float eps) {
    vec2 k = vec2(1.0, -1.0);
    return normalize(k.xyy * gcMap(p + k.xyy * eps) + k.yyx * gcMap(p + k.yyx * eps)
                   + k.yxy * gcMap(p + k.yxy * eps) + k.xxx * gcMap(p + k.xxx * eps));
}

vec3 gcInlayColour(float k) {
    return pal(0.95 + 0.27 * k);
}

float gcBandOf(float k) {
    return k < 0.5 ? gBand.x : (k < 1.5 ? gBand.y : (k < 2.5 ? gBand.z : gBand.w));
}

/** The dark room round the gyroscope: a faint dust of light, warmer toward the core. */
vec3 gcRoom(vec3 rd) {
    vec3 col = pal(0.36) * 0.025 + vec3(0.005, 0.006, 0.012);
    col += pal(0.36) * 0.03 * motionNoise(rd.xy * 3.0 + rd.z * 2.0);
    return col;
}

/**
 * A filmic shoulder before the grade: below the knee the picture is untouched,
 * above it highlights roll off smoothly toward white instead of clipping, so a
 * swell of light stays a swell. Continuous in value and slope at the knee.
 */
vec3 shoulder(vec3 c) {
    vec3 over = max(c - 0.6, 0.0);
    return min(c, 0.6) + over / (1.0 + over * 2.5);
}

/** A fraction of one 8-bit step of noise, so dark gradients do not band. */
float dither() {
    return (motionHash(gl_FragCoord.xy + 0.5) - 0.5) / 255.0;
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

/** The camera on its loop, at angle a: a tilted circle passing just beside the core. */
vec3 gcLoop(float a) {
    vec3 p = vec3(5.4 - 6.2 * cos(a), 0.9 * sin(a * 0.5 + 0.6), 6.2 * sin(a));
    return rotZ(0.32) * p;
}

void main() {
    vec2 uv = view();
    float bass = min(uBassSmooth, 1.3);
    float mid = min(uMidSmooth, 1.3);
    float treb = min(uTrebleSmooth, 1.3);
    float swell = min(uSwell, 1.3);
    float beatEnv = clamp(uBeat, 0.0, 1.0) * clamp(uBeatResponse, 0.0, 2.0);
    gBand = vec4(bass, mid, treb, swell);

    // The gimbal: every spin INTEGRATED (uTime for Speed, uFlowPhase for loudness),
    // and each ring leaned by its own band, a bounded offset that glides with it.
    float a0 = 0.13 * uTime + uFlowPhase * 0.5 + 0.3 * bass;
    float a1 = 0.21 * uTime + uFlowPhase * 0.8 + 0.45 * mid;
    float a2 = -0.29 * uTime - uFlowPhase * 1.1 + 0.45 * treb;
    float a3 = 0.37 * uTime + uFlowPhase * 1.4 + 0.45 * swell;
    float spin = xm(uXSpin);
    gFrame[0] = rotY(a0 * spin);
    gFrame[1] = rotX(a1 * spin) * gFrame[0];
    gFrame[2] = rotY(a2 * spin) * gFrame[1];
    gFrame[3] = rotX(a3 * spin) * gFrame[2];

    vec3 rdl = normalize(vec3(uv, GC_FOCAL));
    vec2 look = touchLook();
    if (look != vec2(0.0)) rdl = rotY(look.x * 0.6) * rotX(-look.y * 0.4) * rdl;
    vec3 camOff = vec3(0.0);
    cameraFlight(camOff, rdl);

    float loop = uTime * 0.055 + uFlowPhase * 0.22 + camOff.z / 6.2;
    vec3 ro = gcLoop(loop);
    gCam = ro;
    vec3 tangent = normalize(gcLoop(loop + 0.05) - ro);
    vec3 toCore = normalize(-ro);
    // Out on the loop the camera faces the gyroscope; close in it looks more along its path.
    vec3 fw = normalize(mix(tangent, toCore, mix(0.4, 0.92, smoothstep(2.5, 9.0, length(ro)))));
    vec3 rt = normalize(cross(vec3(0.0, 1.0, 0.0), fw));
    vec3 up = cross(fw, rt);
    vec3 rd = mat3(rt, up, fw) * rdl;

    float budget = uSteps * (float(GC_MAX_STEPS) / 128.0);
    float t = 0.05;
    bool hit = false;
    float steps = 0.0;
    vec3 glow = vec3(0.0);
    for (int i = 0; i < GC_MAX_STEPS; i++) {
        if (float(i) >= budget || t > GC_FAR) break;
        float d = gcMap(ro + rd * t);
        float eps = 0.0015 + 0.001 * t;
        if (d < eps) {
            hit = true;
            break;
        }
        float adv = max(d * 0.9, eps);
        float k = gInlayRing;
        // In proportion to the step taken, so the haze does not change with Detail.
        glow += gcInlayColour(k) * exp(-max(gInlay, 0.0) * 7.0) * min(adv, 0.5) * (0.06 + 0.3 * gcBandOf(k));
        t += adv;
        steps += 1.0;
    }

    vec3 coreCol = mix(pal(0.95), vec3(1.0, 0.9, 0.75), 0.4);
    float coreBurn = (1.0 + 0.6 * bass + 0.5 * uSpike) * (0.55 + 0.45 * smoothstep(1.0, 4.0, length(ro)));
    vec3 col = gcRoom(rd);
    if (hit) {
        vec3 p = ro + rd * t;
        gcMap(p);
        // Read before the normal and the probes evaluate the field again.
        float ring = gRing;
        vec3 q = gLocal;
        float inlay = gInlay;
        float k = gInlayRing;
        if (ring < 0.0) {
            // The core.
            col = coreCol * 2.2 * coreBurn;
        } else {
            vec3 n = gcNormal(p, 0.0015 + 0.001 * t);
            bool brass = mod(ring, 2.0) < 0.5;
            vec3 metal = brass ? mix(pal(0.88), vec3(0.85, 0.68, 0.4), 0.6) : mix(pal(0.4), vec3(0.62, 0.66, 0.72), 0.7);
            // Occlusion from two probes of the field along the normal.
            float ao = clamp(0.5 + 0.5 * (gcMap(p + n * 0.12) / 0.12 + gcMap(p + n * 0.35) / 0.35) * 0.5, 0.25, 1.0);
            vec3 toC = -p;
            float dc = length(toC);
            float coreLight = max(dot(n, toC / dc), 0.0) * 5.0 / (1.0 + dc * dc) * coreBurn;
            float key = max(dot(n, GC_KEY), 0.0);
            float fill = max(dot(n, GC_FILL), 0.0);
            vec3 h = normalize(GC_KEY - rd);
            float spec = pow(max(dot(n, h), 0.0), brass ? 40.0 : 80.0);
            float fres = pow(1.0 - max(dot(n, -rd), 0.0), 4.0);
            col = metal * (0.04 + key * 0.55 + fill * 0.15 * pal(0.4) + coreLight * coreCol) * ao;
            col += mix(metal, vec3(1.0), 0.5) * spec * 0.8 * ao;
            col += gcRoom(reflect(rd, n)) * 3.0 * fres + coreCol * fres * 0.1;

            // The inlay, ticked, lit by the ring's band, with the beat's comet running round it.
            float ang = atan(q.y, q.x) / GC_TAU + 0.5;
            float tick = 0.55 + 0.45 * step(0.3, fract(ang * 48.0));
            float comet = fract(ang - uBeatPhase + 0.5) - 0.5;
            float run = beatEnv * beatEnv * exp(-comet * comet * 90.0);
            col += gcInlayColour(k) * smoothstep(0.02, 0.0, inlay) * tick * (0.6 + 1.6 * gcBandOf(k) + 2.0 * run);
        }
        // The far side of the room swallows depth a little.
        col = mix(col, gcRoom(rd), 1.0 - exp(-t * 0.02 * xm(uXFog)));
    }
    col += glow;
    // The core's halo, wherever the ray passes it unoccluded.
    float along = -dot(ro, rd);
    if (!hit || along < t) {
        float miss = length(ro + rd * max(along, 0.0));
        col += coreCol * coreBurn * (0.5 / (1.0 + miss * miss * 6.0) + 0.06 / (1.0 + miss * miss * 0.3));
    }

    if (!touchIdle()) {
        col += pal(0.5 + 0.15 * sin(uTime * 0.05)) * min(touchWake(uv), 3.0) * 0.05;
    }
    col *= 0.72 + 0.28 * smoothstep(2.2, 0.5, length(uv));
    fragColor = vec4(grade(shoulder(col)) + dither(), 1.0);
}
