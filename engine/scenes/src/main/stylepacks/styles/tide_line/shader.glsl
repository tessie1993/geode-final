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

// Waterline: the camera rides the sea surface and dips under it.
//
// THE MOVE is a split-level shot. The camera floats at the waterline, bobbing
// with the swell, and as it rides down the frame splits along the meniscus:
// sky and rolling swell above, light shafts and caustics below, a bright
// wavering line of surface between them. When the passage swells the camera
// sinks, and the underwater half takes the frame.
//
// ONE WAVE FUNCTION, USED THREE TIMES. The sea is an analytic sum of five
// travelling waves (deep-water dispersion, sharpened crests). It is the
// heightfield the above-water rays march against, the surface the underwater
// rays refract through, and - evaluated once at the camera - the bob: the
// camera's height IS the wave under it, plus an offset, so the horizon tilts
// and lifts with the water it is sitting in and can never cut through it the
// wrong way. Which half a pixel belongs to is decided the same way: is the
// ray, a hand's breadth in front of the lens, above the wave there or below?
//
// THE MUSIC. uSwell lifts the waves and lowers the camera, so a loud passage
// is rough water ridden from underneath; treble sparks glints off the
// facets; a new form (uFormPhase) moves the time of day - sun height and sky
// colour - gliding. The forward drift is integrated loudness (uFlowPhase)
// over a slow drift, never a clock times a level.
//
// NO-FLASH. The two halves are lit to about the same mean (the underwater
// fog is bright where the surface is near), and the camera's height moves on
// a 20 s swing and on uSwell, so a dip takes well over 0.3 s. The glints are
// tiny points.
//
// Every wave's along-track wavenumber is a whole number of cycles per 1000
// units, the Camera's travel period, so the drift wraps without a seam.

#define WL_TAU 6.2831853
#define WL_MAX_STEPS 44
#define WL_FAR 70.0
#define WL_SEABED -7.0

float gAmp;
float gTime;

/** One sharpened wave: crests narrower than troughs, mean near zero. */
float wlWave(vec2 p, vec2 k, float a, float phase) {
    float s = sin(dot(p, k) + phase);
    return a * (exp(s - 1.0) * 1.6 - 0.65);
}

float wlOmega(vec2 k) {
    return 0.55 * sqrt(9.8 * length(k));
}

/** The sea height at p (x, z). */
float wlHeight(vec2 p) {
    const vec2 k0 = vec2(0.2652, WL_TAU * 136.0 / 1000.0);
    const vec2 k1 = vec2(-0.7006, WL_TAU * 204.0 / 1000.0);
    const vec2 k2 = vec2(1.893, WL_TAU * 239.0 / 1000.0);
    const vec2 k3 = vec2(-0.7802, WL_TAU * 612.0 / 1000.0);
    const vec2 k4 = vec2(6.042, WL_TAU * 267.0 / 1000.0);
    float h = wlWave(p, k0, 0.36, gTime * wlOmega(k0));
    h += wlWave(p, k1, 0.19, gTime * wlOmega(k1) + 1.7);
    h += wlWave(p, k2, 0.09, gTime * wlOmega(k2) + 4.1);
    h += 0.045 * sin(dot(p, k3) + gTime * wlOmega(k3) + 2.3);
    h += 0.025 * sin(dot(p, k4) + gTime * wlOmega(k4) + 0.9);
    return h * gAmp;
}

vec3 wlNormal(vec2 p) {
    const float e = 0.03;
    float h = wlHeight(p);
    return normalize(vec3(h - wlHeight(p + vec2(e, 0.0)), e, h - wlHeight(p + vec2(0.0, e))));
}

vec3 gSun;
vec3 gSunCol;
vec3 gZenith;
vec3 gHorizon;

vec3 wlSky(vec3 rd) {
    float up = max(rd.y, -0.05);
    vec3 col = mix(gHorizon, gZenith, pow(smoothstep(-0.05, 0.6, up), 0.7));
    float sd = max(dot(rd, gSun), 0.0);
    col += gSunCol * (pow(sd, 900.0) * 6.0 + pow(sd, 12.0) * 0.25 + pow(sd, 3.0) * 0.08);
    // Clouds on a plane overhead, thinning toward the horizon.
    if (rd.y > 0.01) {
        vec2 cp = rd.xz / rd.y * 3.0 + vec2(0.0, gTime * 0.05);
        float c = smoothstep(0.45, 0.85, motionFbm(cp * 0.5) * 0.6 + motionFbm(cp * 1.7) * 0.4);
        vec3 cloud = mix(gHorizon * 1.1, vec3(1.0), 0.5) + gSunCol * 0.2 * pow(sd, 4.0);
        col = mix(col, cloud, c * smoothstep(0.01, 0.2, rd.y) * 0.8);
    }
    return col;
}

/** Caustics on a plane: interfering ridges of light, moving with the swell. */
float wlCaustic(vec2 p) {
    vec2 q = p * 0.9;
    float c = 0.0;
    c += abs(sin(q.x * 1.7 + gTime * 0.9 + sin(q.y * 1.3 + gTime * 0.7)));
    c += abs(sin(q.y * 1.9 - gTime * 0.8 + sin(q.x * 1.1 - gTime * 0.6)));
    c += abs(sin((q.x + q.y) * 1.3 + gTime * 0.5));
    return pow(max(1.0 - c / 3.0 * 1.6, 0.0), 3.0) * 3.0;
}

vec3 wlTone(vec3 c) {
    return 1.0 - exp(-max(c, 0.0) * 1.3);
}

void main() {
    vec2 uv = cameraView();
    float swell = clamp(uSwell, 0.0, 1.0);
    float glint = min(uTrebleSmooth, 1.3);
    float hue = 0.52 + 0.1 * uFormPhase;
    gTime = uTime;
    gAmp = (0.75 + 0.6 * swell) * xm(uXScale);

    // Time of day from the form: one full day around the phase circle.
    float day = 0.5 + 0.5 * sin(WL_TAU * uFormPhase);
    float sunH = mix(0.04, 0.55, day);
    gSun = normalize(vec3(0.35, sunH, 1.0));
    gSunCol = mix(vec3(1.0, 0.55, 0.3), vec3(1.0, 0.95, 0.85), day);
    gZenith = mix(vec3(0.12, 0.16, 0.32), vec3(0.16, 0.36, 0.7), day);
    gHorizon = mix(vec3(0.85, 0.5, 0.38), vec3(0.65, 0.78, 0.9), day);
    gHorizon = mix(gHorizon, pal(hue + 0.1), 0.15);

    // The drift, wrapped by the waves' 1000-unit period.
    float drift = mod(mod(uFlowPhase, 1000.0) * 1.2 + mod(uTime, 2000.0) * 0.5, 1000.0);
    vec3 ro = vec3(0.0, 0.0, drift);
    // Head motion that follows the water: roll with the slope across, pitch with the slope along.
    vec2 slope = vec2(wlHeight(ro.xz + vec2(0.4, 0.0)) - wlHeight(ro.xz - vec2(0.4, 0.0)),
                      wlHeight(ro.xz + vec2(0.0, 0.4)) - wlHeight(ro.xz - vec2(0.0, 0.4))) / 0.8;
    float roll = -0.25 * slope.x;
    float pitch = -0.04 - 0.15 * slope.y;
    vec2 suv = rot2(roll) * uv + touchAnchor() * 0.4;
    vec3 rd = normalize(vec3(suv.x, suv.y + pitch * 1.4, 1.4));
    cameraFlight(ro, rd);
    // The bob: the wave under the camera, plus a slow swing, minus the swell's dip.
    float offset = 0.3 + 0.32 * sin(uTime * WL_TAU / 20.0) - 0.8 * swell;
    ro.y = wlHeight(ro.xz) + offset;

    // Which half: the ray a hand's breadth ahead, against the wave there.
    vec3 pn = ro + rd * 0.15;
    float side = pn.y - wlHeight(pn.xz);
    bool above = side > 0.0;

    vec3 col;
    if (above) {
        col = wlSky(rd);
        float t = 0.15;
        float hitT = -1.0;
        if (rd.y < 0.3) {
            for (int i = 0; i < WL_MAX_STEPS; i++) {
                if (float(i) >= uSteps) break;
                vec3 p = ro + rd * t;
                float d = p.y - wlHeight(p.xz);
                if (d < 0.002 * t) { hitT = t; break; }
                t += max(d * 0.55, 0.01 + 0.012 * t);
                if (t > WL_FAR) break;
            }
        }
        if (hitT > 0.0) {
            vec3 p = ro + rd * hitT;
            vec3 n = wlNormal(p.xz);
            // Distant water flattens toward the mean normal instead of aliasing.
            n = normalize(mix(n, vec3(0.0, 1.0, 0.0), smoothstep(10.0, WL_FAR, hitT)));
            float cosI = max(dot(n, -rd), 0.0);
            float fres = 0.02 + 0.98 * pow(1.0 - cosI, 5.0);
            vec3 refl = wlSky(reflect(rd, n));
            vec3 deep = mix(vec3(0.03, 0.12, 0.16), pal(hue + 0.45) * 0.15, 0.3);
            // Light through the thin crests: the green of a wave about to break.
            float crest = clamp((p.y / max(gAmp, 0.1)) * 1.6, 0.0, 1.0);
            float sss = pow(max(dot(rd, gSun), 0.0), 2.0) * 0.5 + 0.5;
            vec3 body = deep + vec3(0.05, 0.25, 0.22) * crest * sss * (0.4 + 0.6 * day);
            col = mix(body, refl, fres);
            float spec = pow(max(dot(reflect(rd, n), gSun), 0.0), 300.0);
            col += gSunCol * spec * (1.5 + 2.5 * glint);
            // Foam lacing the highest crests.
            float foam = smoothstep(0.55, 0.9, crest) * smoothstep(0.3, 0.7, motionNoise(p.xz * 3.0 + gTime * 0.3));
            col = mix(col, vec3(0.85, 0.9, 0.92) * (0.5 + 0.5 * day), foam * 0.6);
            col = mix(col, wlSky(vec3(rd.x, 0.02, rd.z)), smoothstep(15.0, WL_FAR, hitT));
        }
    } else {
        // Underwater: fog brightest toward the surface, the seabed's caustics, light shafts.
        vec3 waterTop = mix(vec3(0.24, 0.62, 0.64), pal(hue + 0.4) * 0.6, 0.25) * (0.8 + 0.4 * day);
        vec3 waterDeep = vec3(0.05, 0.19, 0.25);
        vec3 fogCol = mix(waterDeep, waterTop, smoothstep(-0.6, 0.5, rd.y));
        col = fogCol;
        float tEnd = 30.0;
        if (rd.y > 0.0) {
            // The underside of the surface: Snell's window, total reflection outside it.
            float t = (wlHeight(ro.xz) - ro.y) / rd.y;
            vec3 p = ro + rd * t;
            t = (wlHeight(p.xz) - ro.y) / rd.y;
            p = ro + rd * t;
            vec3 n = -wlNormal(p.xz);
            vec3 tr = refract(rd, n, 1.33);
            vec3 surf = dot(tr, tr) > 0.0 ? wlSky(tr) * 0.85 : waterTop * (0.5 + 0.3 * wlCaustic(p.xz * 1.5));
            float fade = exp(-t * 0.08);
            col = mix(fogCol, surf, fade);
            tEnd = t;
        } else {
            float t = (WL_SEABED - ro.y) / rd.y;
            vec3 p = ro + rd * t;
            vec3 sand = vec3(0.55, 0.5, 0.38) * (0.75 + 0.3 * motionNoise(p.xz * 2.0));
            vec3 lit = sand * (0.35 + 0.65 * wlCaustic(p.xz)) * (0.5 + 0.6 * day);
            col = mix(fogCol, lit, exp(-t * 0.14 / xm(uXFog)));
            tEnd = t;
        }
        // Shafts: the surface's caustic, projected along the sun, sampled down the ray.
        float shafts = 0.0;
        float stepT = min(tEnd, 14.0) / 8.0;
        for (int i = 0; i < 8; i++) {
            vec3 p = ro + rd * (stepT * (float(i) + 0.5));
            vec2 s = p.xz - gSun.xz / max(gSun.y, 0.2) * p.y;
            shafts += wlCaustic(s * 0.5) * exp(p.y * 0.25);
        }
        col += waterTop * shafts / 8.0 * 0.5 * (0.6 + 0.4 * day);
        // Drifting motes catching the light.
        col += waterTop * fluidMotes(uv, 9.0, 0.09) * 0.35;
    }

    // The meniscus: a bright, wavering line where the lens crosses the surface.
    float men = exp(-abs(side) * 70.0);
    col = mix(col, vec3(0.75, 0.88, 0.9) * (0.5 + 0.4 * day), men * 0.5);

    if (!touchIdle()) {
        col += pal(hue + 0.5) * min(touchWake(uv), 2.0) * 0.05;
    }
    col = wlTone(col);
    col *= 0.78 + 0.22 * smoothstep(2.0, 0.4, length(uv));
    fragColor = vec4(grade(col), 1.0);
}
