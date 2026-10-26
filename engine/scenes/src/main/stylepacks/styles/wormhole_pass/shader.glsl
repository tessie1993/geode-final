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
// The noise volume (manifest "inputs": noise_vol): fbmVol is lib_sdf3's fbm3 at one texture fetch per
// octave instead of eight lattice hashes.
//#include lib_inputs

// Wormhole Pass: fly at a wormhole, through its throat, and out under a
// different sky - then the next one appears far ahead.
//
// There is no march. A wormhole seen from outside is a sphere that shows the
// OTHER universe, with this one's sky lensed into a ring around its rim, and
// both halves of that are a function of one number per ray: its impact
// parameter b, the distance by which the ray would miss the throat's centre.
//
//   b > throat   the ray passes outside. It reads THIS sky, bent toward the
//                throat by an angle that grows as b closes in, so stars pile
//                up into an Einstein ring at the rim.
//   b < throat   the ray goes through. It reads the NEXT sky, and the whole of
//                that sky is squeezed into the disc: straight through at the
//                centre, looking back over your shoulder at the rim.
//
// As the camera closes in, the disc grows to fill the frame, and the inside
// mapping relaxes toward the identity, so the instant the throat is passed the
// new sky is simply the sky - there is nothing to cut. Each sky is one of four
// designs (nebula, galaxy band, aurora cloud, red giant), keyed by the pass
// count, so the journey never repeats two in a row.
//
// ---- the music ---------------------------------------------------------------
//
// The passes run on their own clock (uTime, plus the Camera's travel, which
// slides along the same journey). The rim ring is the spectrum, band by angle,
// and the passage's loudness widens it; the bass lifts the speed streaks a
// little. Every change of sky happens behind a throat that has already filled
// the frame, so the frame's brightness only ever drifts.

#define WH_TAU 6.2831853
#define WH_PI 3.1415927
/** One pass, seconds: approach, through, cruise under the new sky. */
#define WH_PASS 25.0
/** Where in the pass the camera reaches the throat. */
#define WH_ARRIVE 0.8
/** Distance at which a wormhole first appears, and its throat radius (impact parameter). */
#define WH_FAR 48.0
#define WH_THROAT 2.0
#define WH_FOCAL 1.45
/** Sky designs; the travel wrap (1000) covers a whole number of passes times this. */
#define WH_SKIES 4.0

vec3 starLayer(vec3 d, float scale, float density, float footprint, float salt) {
    vec3 q = d * scale;
    vec3 cell = floor(q);
    ivec3 ic = ivec3(cell);
    float h = hashCell(ic + ivec3(int(salt)));
    if (h < 1.0 - density) return vec3(0.0);
    vec3 jitter = vec3(hashCell(ic + ivec3(3, 0, 0)), hashCell(ic + ivec3(0, 7, 0)), hashCell(ic + ivec3(0, 0, 11)));
    vec3 c = cell + 0.15 + 0.7 * jitter;
    float dist = length(q - c) / scale;
    // The star widens with the pixel footprint and dims by the same area: it
    // smears when the lens stretches it rather than twinkling.
    float size = max(0.0018, footprint);
    float energy = (0.0018 * 0.0018) / (size * size);
    float mag = pow((h - 1.0 + density) / density, 2.0);
    vec3 tint = mix(vec3(0.7, 0.82, 1.0), vec3(1.0, 0.82, 0.6), jitter.y);
    return tint * mag * energy * exp(-dist * dist / (size * size));
}

/** Sky design `k` (0..3) in direction d. Each keeps the same overall level so a pass never pumps the frame. */
vec3 sky(vec3 d, float k, float footprint) {
    float drift = uTime * 0.006;
    d = rotY(drift + k * 1.7) * d;
    vec3 col;
    vec3 salt = vec3(k * 13.1, k * 7.7, k * 3.3);
    if (k < 0.5) {
        // Violet nebula: layered clouds with dark lanes.
        float n = fbmVol(d * 2.2 + salt, 4);
        float lane = smoothstep(0.5, 0.75, fbmVol(d * 5.0 + salt + 9.0, 3));
        col = mix(vec3(0.03, 0.02, 0.07), mix(vec3(0.45, 0.25, 0.85), pal(0.7), 0.4), smoothstep(0.35, 0.8, n));
        col += mix(vec3(0.95, 0.4, 0.6), pal(0.9), 0.4) * 0.35 * smoothstep(0.6, 0.9, n) * (1.0 - lane);
    } else if (k < 1.5) {
        // A golden galaxy band across the sky.
        vec3 gn = normalize(vec3(0.3, 1.0, -0.2));
        float lat = dot(d, gn);
        float band = exp(-lat * lat * 10.0);
        float dust = smoothstep(0.45, 0.75, fbmVol(d * 6.0 + salt, 3));
        col = vec3(0.02, 0.025, 0.05) + mix(vec3(1.0, 0.8, 0.5), pal(0.1), 0.35) * band * (0.55 - 0.4 * dust);
        col += mix(vec3(1.0, 0.9, 0.75), pal(0.12), 0.2) * 0.5 * exp(-lat * lat * 60.0) * (1.0 - dust);
    } else if (k < 2.5) {
        // Teal aurora cloud: sheets folded by a slow warp.
        vec3 w = d * 1.8 + salt;
        w += 0.6 * vec3(fbmVol(w + 1.0, 2), fbmVol(w + 5.0, 2), fbmVol(w + 9.0, 2));
        float sheet = pow(1.0 - abs(2.0 * fbmVol(w, 4) - 1.0), 4.0);
        col = vec3(0.01, 0.03, 0.04) + mix(vec3(0.2, 0.9, 0.7), pal(0.4), 0.4) * sheet * 0.55;
        col += mix(vec3(0.3, 0.4, 1.0), pal(0.6), 0.4) * 0.12 * smoothstep(0.3, 0.9, fbmVol(d * 1.2 + salt, 2));
    } else {
        // A red giant with a dust shell.
        vec3 sunDir = normalize(vec3(0.5, 0.25, 0.8));
        float c = max(dot(d, sunDir), 0.0);
        float disc = smoothstep(0.9965, 0.998, c);
        float limb = pow(max((c - 0.9965) / 0.0035, 0.0), 0.4);
        col = vec3(0.04, 0.015, 0.02);
        col += mix(vec3(1.0, 0.35, 0.15), pal(0.02), 0.3) * (0.25 * pow(c, 12.0) + 0.6 * pow(c, 120.0));
        col += mix(vec3(1.0, 0.6, 0.35), pal(0.06), 0.2) * disc * (0.5 + 0.5 * limb) * 1.4;
        float shell = smoothstep(0.4, 0.8, fbmVol(d * 3.0 + salt, 4)) * pow(c, 3.0);
        col += mix(vec3(0.8, 0.3, 0.2), pal(0.95), 0.4) * shell * 0.35;
    }
    col += starLayer(d, 80.0, 0.12, footprint, k * 101.0) * 1.6;
    col += starLayer(d, 170.0, 0.1, footprint, k * 211.0 + 17.0) * 0.8;
    return col;
}

/** Turn v about the axis normal to v and c by angle a (positive turns v toward c). */
vec3 bend(vec3 v, vec3 c, float a) {
    vec3 axis = cross(v, c);
    float len = length(axis);
    if (len < 1e-6) return v;
    return rotAxis(axis / len, -a) * v;
}

vec3 tonemap(vec3 c) {
    c = max(c, 0.0);
    return clamp(c * (2.51 * c + 0.03) / (c * (2.43 * c + 0.59) + 0.14), 0.0, 1.0);
}

void main() {
    vec2 uv = cameraView();

    // ---- where in the journey ----------------------------------------------------
    float journey = uTime + uCamTravel * 0.5;
    float pass = floor(journey / WH_PASS);
    float u = journey / WH_PASS - pass;
    float here = mod(pass, WH_SKIES);
    float next = mod(pass + 1.0, WH_SKIES);
    float v = clamp(u / WH_ARRIVE, 0.0, 1.0);
    // Distance to the throat: slow far out, accelerating in.
    float dist = WH_FAR * pow(1.0 - v, 2.2) * (1.0 - 0.45 * uCamPush);
    // Closing speed, for the streaks: the derivative of dist over time.
    float speed = WH_FAR * 2.2 * pow(1.0 - v, 1.2) / (WH_ARRIVE * WH_PASS);
    speed *= u < WH_ARRIVE ? 1.0 : 0.0;
    // A new wormhole grows out of nothing at the start of each pass.
    float throat = WH_THROAT * smoothstep(0.0, 0.1, u);

    // ---- the camera ----------------------------------------------------------------
    vec2 lens = uv;
    if (!touchIdle()) lens -= touchAnchor() * (0.4 * touchStrength());
    // A gentle weave on the approach, so the throat is never pinned dead centre.
    float weave = 0.05 * sin(uTime * 0.23);
    vec3 rd = normalize(vec3(lens, WH_FOCAL));
    rd = rotY(weave) * (rotX(0.03 * sin(uTime * 0.17)) * rd);
    rd = uCamRot * rd;

    vec3 toThroat = vec3(0.0, 0.0, 1.0);
    float cosPsi = dot(rd, toThroat);
    float psi = acos(clamp(cosPsi, -1.0, 1.0));
    float b = dist * sin(psi);
    bool ahead = cosPsi > 0.0;
    // Width of one pixel in impact-parameter units, for the rim's anti-aliasing.
    float bw = max(fwidth(b), 1e-4);
    float footprint = clamp(length(fwidth(rd)) * 0.6, 0.0, 0.05);

    // Outside: this sky, bent toward the throat. The bend is strongest at the
    // rim and falls as the inverse square of the miss distance.
    float rimFrac = throat / max(b, 1e-3);
    vec3 outDir = bend(rd, toThroat, 1.15 * rimFrac * rimFrac * smoothstep(0.0, 0.1, u));

    // Inside: the next sky, the whole of it squeezed into the disc. The squeeze
    // relaxes to the identity as the camera reaches the throat, so the new sky
    // is unbent the moment it surrounds the eye.
    float s = clamp(b / max(throat, 1e-3), 0.0, 1.0);
    float squeeze = smoothstep(WH_THROAT, 3.0 * WH_THROAT, dist);
    vec3 perp = rd - toThroat * cosPsi;
    float perpLen = length(perp);
    perp = perpLen > 1e-6 ? perp / perpLen : vec3(1.0, 0.0, 0.0);
    float psiB = mix(psi, WH_PI * pow(s, 1.3), squeeze);
    vec3 inDir = cos(psiB) * toThroat + sin(psiB) * perp;

    float inside = (dist <= throat) ? 1.0 : (ahead ? 1.0 - smoothstep(throat - bw, throat + bw, b) : 0.0);
    vec3 col;
    if (inside >= 1.0) {
        col = sky(inDir, next, footprint);
    } else if (inside <= 0.0) {
        col = sky(outDir, here, footprint);
    } else {
        col = mix(sky(outDir, here, footprint), sky(inDir, next, footprint), inside);
    }

    // The rim: a thin ring where the light skims the throat, lit by the
    // spectrum around its circumference (mirrored, so it has no seam).
    if (ahead && dist > throat) {
        // Folded at the atan cut, so neither the band nor the tint has a seam.
        float around = abs(atan(perp.y, perp.x)) / WH_PI;
        float band = aband(0.04 + 0.85 * around);
        float width = throat * (0.035 + 0.03 * clamp(uSwell, 0.0, 1.2)) + bw;
        float ring = exp(-pow((b - throat) / width, 2.0));
        float halo = b > throat ? exp(-(b - throat) / (throat * 0.5)) * 0.18 : 0.0;
        vec3 rimCol = mix(vec3(0.75, 0.85, 1.0), pal(0.5 + 0.25 * around), 0.45);
        col += rimCol * (ring * (0.35 + 0.55 * clamp(band, 0.0, 1.2)) + halo) * smoothstep(0.0, 0.1, u);
    }

    // Speed streaks radiating from the throat: thin, dim, only while closing.
    float rad = length(lens);
    float ang = atan(lens.y, lens.x);
    float lane = floor(ang / WH_TAU * 90.0);
    float laneH = hashCell(ivec3(int(lane), 41, 0));
    float flow = fract(log(max(rad, 0.02)) * 1.2 - uTime * 0.9 * (0.6 + laneH) + laneH * 7.0);
    float streak = smoothstep(0.0, 0.08, flow) * smoothstep(0.35, 0.08, flow) * step(0.7, laneH);
    float pace = clamp(speed / 14.0, 0.0, 1.0) * (0.8 + 0.25 * clamp(uBassSmooth, 0.0, 1.2));
    col += mix(vec3(0.7, 0.8, 1.0), pal(0.55), 0.3) * streak * pace * smoothstep(0.15, 0.9, rad) * 0.12;

    col *= mix(1.0, 0.72, smoothstep(0.7, 2.0, rad));
    col = tonemap(col * 1.25);
    col += (uhashUnit(uhash3(uvec3(gl_FragCoord.xy, 13u))) - 0.5) / 255.0;
    fragColor = vec4(grade(col), 1.0);
}
