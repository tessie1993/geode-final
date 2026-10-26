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

// Sea Cave: pushing out of a sea cave toward its bright mouth, low over the
// water, the way a small boat noses out on the swell. Waves wash in and out
// across the cave floor, light off the water ripples over the ceiling, and
// shafts of daylight stand in the spray between the camera and the mouth.
//
// ---- the cave ----------------------------------------------------------------------
//
// The air is the union of a rough tunnel and the open sea beyond the cliff,
// so its distance is the max of the two (tunnel radius minus distance from the
// axis, plus a rock displacement whose gradient bound sets the step scale; and
// the distance past the cliff plane). The rock's frequencies along z divide
// SC_PERIOD, so the cave repeats and the camera's push is wrapped to that
// period. The mouth rides a phrase-long tide ahead of the camera: walls stream
// past steadily while the mouth draws closer and eases away.
//
// The water is not marched: its plane is hit in closed form and its waves are
// a normal field there (Fresnel mix of the mouth's light reflected and the
// green depth beneath, with foam on the crests). The shafts are a few samples
// along the ray of a pattern that is constant along lines from the mouth.
//
// ---- the no-flash rule ------------------------------------------------------------------
//
// The mouth is the brightest thing on screen and nothing musical touches it:
// its radiance is fixed and passes through a soft rolloff, so no beat can
// push it toward white. The music moves the water, the spray and the shafts.
//
// ---- audio --------------------------------------------------------------------------------
//
// Energy eases the push through uFlowPhase. uBassSmooth raises the swell that
// washes over the floor; a beat rolls a surge in from the mouth (uBeatPhase,
// gated by the beat envelope); uMidSmooth sways the shafts; uTrebleSmooth
// glints the spray. uSwell draws the mouth a little nearer on loud passages.

#define SC_MAX_STEPS 96
#define SC_FAR 30.0
#define SC_PERIOD 40.0
#define SC_W 0.15707963
#define SC_TAU 6.2831853

float gMouthZ;
float gWater;
float gWaveAmp;
float gSurge;

float rock(vec3 p) {
    return 0.16 * sin(p.x * 1.3 + p.z * SC_W * 5.0) * sin(p.y * 1.7 - p.z * SC_W * 3.0)
         + 0.08 * sin(p.z * SC_W * 15.0 + p.y * 3.1 + p.x * 2.0)
         + 0.05 * sin(p.x * 4.3 - p.z * SC_W * 22.0);
}

float map(vec3 p) {
    vec2 c = vec2(p.x - 0.3 * sin(p.z * SC_W * 2.0), (p.y - 0.5) * 1.15);
    float tunnel = (2.0 + 0.35 * sin(p.z * SC_W * 4.0 + 1.0)) - length(c) - rock(p);
    float cliff = p.z - gMouthZ + 0.25 * sin(p.y * 2.0 + p.x * 1.5);
    return max(tunnel, cliff);
}

vec3 normalAt(vec3 p, float eps) {
    vec2 k = vec2(1.0, -1.0);
    // The field grows into the air, so its gradient is the rock's outward normal.
    return normalize(k.xyy * map(p + k.xyy * eps) + k.yyx * map(p + k.yyx * eps)
                    + k.yxy * map(p + k.yxy * eps) + k.xxx * map(p + k.xxx * eps));
}

/** Daylight beyond the mouth: sky over a bright sea, with the sun low ahead. */
vec3 outside(vec3 d) {
    vec3 sky = mix(mix(pal(0.52), vec3(0.75, 0.9, 1.0), 0.6), mix(pal(0.58), vec3(0.4, 0.6, 0.9), 0.5), smoothstep(0.0, 0.6, d.y));
    vec3 sea = mix(pal(0.5), vec3(0.2, 0.45, 0.55), 0.5) * 0.8;
    vec3 col = mix(sea, sky, smoothstep(-0.03, 0.03, d.y)) * 1.1;
    col += mix(pal(0.88), vec3(1.0), 0.6) * 0.4 * pow(max(dot(d, normalize(vec3(0.2, 0.15, 1.0))), 0.0), 30.0);
    return col;
}

/** Water level at xz: the swell washing in and out, and the beat's surge rolling in from the mouth. */
float waterHeight(vec2 xz) {
    // Frequencies along z divide the period, like the rock's.
    float wash = gWaveAmp * (sin(xz.y * SC_W * 7.0 + uTime * 1.3) * 0.6 + sin(xz.y * SC_W * 15.0 - xz.x * 1.7 + uTime * 2.1) * 0.25
                           + sin(xz.x * 3.1 + xz.y * SC_W * 18.0 + uTime * 2.7) * 0.15);
    float crest = gMouthZ - uBeatPhase * 14.0;
    float surge = gSurge * 0.18 * exp(-pow(xz.y - crest, 2.0) * 0.6);
    return gWater + wash + surge;
}

vec3 filmic(vec3 c) {
    c = max(c, 0.0) * 1.15;
    return c * (1.0 + c * 0.2) / (1.0 + c);
}

// A finger steers the look toward where it is held; on release the look
// glides home as the touch fades. Untouched, the ray comes back as it went in.
vec3 steerLook(vec3 rd) {
    if (touchIdle()) return rd;
    vec2 a = touchAnchor() * touchStrength();
    return rotY(a.x * 0.8) * (rotX(-a.y * 0.5) * rd);
}

void main() {
    vec2 uv = view();
    float bass = clamp(uBassSmooth, 0.0, 1.5);
    float mid = clamp(uMidSmooth, 0.0, 1.5);
    float treb = clamp(uTrebleSmooth, 0.0, 1.5);
    float swell = clamp(uSwell, 0.0, 1.5);
    gSurge = clamp(uBeat, 0.0, 1.0) * clamp(uBeatResponse, 0.0, 2.0);
    gWaveAmp = 0.05 + 0.07 * bass;
    gWater = -1.05 + 0.08 * sin(uTime * 0.37);

    // ---- the push --------------------------------------------------------------------
    float camZ = mod(uTime * 0.25 + uFlowPhase * 2.2 + uCamTravel, SC_PERIOD);
    gMouthZ = camZ + 7.8 + 2.4 * sin(uTime * (SC_TAU / 40.0)) - 1.2 * swell;
    // Riding the swell: a slow pitch and roll, and a rise and fall with the water.
    float bob = 0.06 * sin(uTime * 0.9) + 0.03 * sin(uTime * 1.7);
    vec3 ro = vec3(0.3 * sin(camZ * SC_W * 2.0) + 0.1 * sin(uTime * 0.21), -0.35 + bob, camZ);
    vec3 rd = normalize(vec3(uv, 1.2));
    rd = rotZ(0.05 * sin(uTime * 0.7)) * (rotX(-0.04 + 0.04 * sin(uTime * 0.9 + 1.0)) * (rotY(0.08 * sin(uTime * 0.13)) * rd));
    rd = steerLook(rd);
    cameraFlight(ro, rd);
    ro.z = min(ro.z, gMouthZ - 1.0);

    // ---- the march --------------------------------------------------------------------
    float budget = clamp(uSteps * 0.75, 24.0, float(SC_MAX_STEPS));
    float t = 0.05;
    float hitT = -1.0;
    float steps = 0.0;
    float tWater = rd.y < 0.0 ? (gWater - ro.y) / rd.y : 1e9;
    for (int i = 0; i < SC_MAX_STEPS; i++) {
        if (float(i) >= budget || t > SC_FAR || t > tWater) break;
        float d = map(ro + rd * t);
        if (d < 0.002 * t + 0.001) {
            hitT = t;
            break;
        }
        // The rock displacement's gradient is bounded near 0.9: step at 0.6.
        t += d * 0.6;
        steps += 1.0;
    }

    vec3 mouth = vec3(0.3 * sin(gMouthZ * SC_W * 2.0), 0.5, gMouthZ);
    vec3 light = mix(pal(0.52), vec3(0.85, 0.95, 1.0), 0.65);
    vec3 deep = mix(pal(0.48), vec3(0.02, 0.06, 0.07), 0.7) * 0.25;
    vec3 col;
    float tEnd;
    if (hitT > 0.0 && hitT < tWater) {
        // ---- rock: lit by the mouth, rippled by light off the water ----------
        vec3 p = ro + rd * hitT;
        vec3 n = normalAt(p, 0.003 * hitT + 0.002);
        vec3 toMouth = mouth - p;
        float dm = length(toMouth);
        float falloff = 26.0 / (dm * dm + 6.0);
        float key = max(dot(n, toMouth / dm), 0.0) * falloff;
        // Bounce off the water lights the rock from below, greener and dimmer.
        float fill = 0.12 + 0.08 * n.y;
        float bounce = max(-n.y, 0.0) * falloff * 0.35;
        float rim = pow(clamp(1.0 + dot(rd, n), 0.0, 1.0), 3.0) * 0.3 * falloff;
        // Caustics: light off the moving water, strongest on the ceiling.
        vec2 cq = vec2(p.x * 2.2, p.z * SC_W * 14.0) + vec2(uTime * 0.4, uTime * 0.3);
        float caustic = pow(abs(sin(cq.x + sin(cq.y * 1.3)) * sin(cq.y + sin(cq.x * 1.7 + uTime))), 3.0);
        caustic *= smoothstep(-0.2, 0.7, -n.y) * falloff;
        float ao = clamp(1.0 - steps / budget, 0.35, 1.0);
        vec3 albedo = mix(vec3(0.36, 0.32, 0.27), pal(0.55), 0.18) * (0.8 + 0.4 * motionNoise(p.yz * vec2(3.0, 1.0) + p.x));
        // Wet rock near the waterline is darker and glossier.
        float wet = smoothstep(gWater + 0.6, gWater, p.y);
        albedo *= 1.0 - 0.45 * wet;
        vec3 seaLight = mix(pal(0.5), vec3(0.3, 0.7, 0.65), 0.5);
        col = albedo * (light * (key * ao + caustic * 0.8) + seaLight * bounce + deep * fill * 2.0 + light * 0.035) + light * rim * (0.3 + 0.6 * wet);
        float facing = clamp(dot(-rd, n), 0.0, 1.0);
        col += light * wet * pow(1.0 - facing, 4.0) * 0.3 * falloff;
        col = mix(col, deep, 1.0 - exp(-hitT * 0.05));
        tEnd = hitT;
    } else if (tWater < 1e8 && tWater < SC_FAR) {
        // ---- water ----------------------------------------------------------------
        vec3 p = ro + rd * tWater;
        float e = 0.05;
        float h0 = waterHeight(p.xz);
        vec3 n = normalize(vec3(h0 - waterHeight(p.xz + vec2(e, 0.0)), e, h0 - waterHeight(p.xz + vec2(0.0, e))));
        vec3 refl = reflect(rd, n);
        float facing = clamp(dot(-rd, n), 0.0, 1.0);
        float fres = 0.02 + 0.98 * pow(1.0 - facing, 5.0);
        // What the water mirrors: the mouth's daylight if the bounced ray heads
        // out through it, the dark rock if it heads into the walls.
        float toward = smoothstep(0.75, 0.97, dot(refl, normalize(mouth - p)));
        vec3 mirrored = mix(deep * 1.5, outside(refl) * 0.9, toward);
        float dm = length(mouth - p);
        vec3 below = mix(pal(0.48), vec3(0.05, 0.25, 0.25), 0.5) * (0.06 + 0.5 / (1.0 + dm * dm * 0.05));
        col = mix(below, mirrored, fres);
        // Foam on the crests and along the beat's surge.
        float foam = smoothstep(gWater + 0.08, gWater + 0.18, h0) * (0.5 + 0.5 * sin(p.x * 7.0 + 2.0 * sin(p.z * SC_W * 38.0 + uTime) + uTime));
        col += light * foam * 0.35 * (0.4 + 2.0 / (1.0 + dm * dm * 0.1));
        col = mix(col, deep, 1.0 - exp(-tWater * 0.04));
        tEnd = tWater;
    } else {
        col = outside(rd);
        tEnd = SC_FAR;
    }

    // ---- shafts in the spray ------------------------------------------------------------
    //
    // A pattern that is constant along lines from the mouth, sampled a few
    // times along the ray, thicker low over the water and near the mouth.
    vec3 shafts = vec3(0.0);
    float span = min(tEnd, gMouthZ - ro.z + 2.0);
    for (int k = 0; k < 8; k++) {
        float s = span * (float(k) + 0.5) / 8.0;
        vec3 q = ro + rd * s;
        vec3 fromMouth = q - mouth;
        vec2 beam = fromMouth.xy / max(1.0, -fromMouth.z * 0.25 + 1.0);
        float pattern = motionNoise(beam * 2.6 + vec2(0.4 * sin(uTime * 0.2) * (0.5 + mid), 0.0));
        float spray = exp(-(q.y - gWater) * 1.6) * 0.6 + 0.25;
        float near = 1.0 / (1.0 + dot(fromMouth, fromMouth) * 0.03);
        shafts += light * smoothstep(0.45, 0.85, pattern) * spray * near;
    }
    col += shafts * span / 8.0 * 0.12;
    // Spray glints on the treble.
    float glint = step(0.993, motionHash(floor(uv * 90.0) + floor(uTime * 6.0)));
    col += light * glint * 0.25 * treb * smoothstep(0.6, -0.2, uv.y);

    if (!touchIdle()) {
        col += light * min(touchWake(uv), 3.0) * 0.035;
    }
    col *= 0.7 + 0.3 * smoothstep(2.2, 0.4, length(uv));
    fragColor = vec4(grade(filmic(col)), 1.0);
}
