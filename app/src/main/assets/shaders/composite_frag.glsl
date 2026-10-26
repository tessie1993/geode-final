#version 300 es
precision highp float;
// GLSL ES 3.00 defaults fragment sampler2D to LOWP (range [-2,2), ~8
// fraction bits). uTexA/uTexB hold HDR scene colour; on GPUs honoring
// sampler precision (Mali) every read is clamped and quantized. (uFlow
// and uRipple below keep their own explicit highp qualifiers.)
precision highp sampler2D;

in vec2 vUv;
out vec4 fragColor;

//#include lib_layers

uniform sampler2D uTexA;
uniform sampler2D uTexB;
// Blue-noise dither mask, sampled 1:1 with output pixels. See BlueNoise.kt for
// why the tile is blue rather than white noise and why it must not animate.
uniform sampler2D uNoise;
uniform float uDither;
/** width / height of the output, the `ratio` a spliced gl-transition reads. */
uniform float uRatio;
uniform float uProgress;
uniform int uStyle;
// Layer 2 (core/viz/include/geode/viz/Layers.hpp, lib_layers.glsl): a second Style's picture, laid
// over everything above (Layer 1, transitions included) in linear light. uTexL is its picture, with its
// own gate and Camera pair (x its flat moves, y its flat endless zoom), as uTexA and uTexB have theirs.
// uLayerPresence is how far it is faded in: 0 is no Layer 2, and then nothing of it runs. uLayerBlend is
// layer::Blend's ordinal, uLayerOpacity the source opacity (already held to the mode's No-flash cap),
// uLayerInfluence layer::Influence's ordinal (how Layer 2 acts on Layer 1) at uLayerAmount, and
// uLayerSwap crossfades towards Layer 1 laid over Layer 2 instead.
uniform sampler2D uTexL;
uniform vec4 uGateL;
uniform vec2 uCamGateL;
uniform float uLayerPresence;
uniform int uLayerBlend;
uniform float uLayerOpacity;
uniform int uLayerInfluence;
uniform float uLayerAmount;
uniform float uLayerSwap;
uniform float uTime;
uniform float uBeat;
uniform float uChroma;
uniform float uVignette;
uniform float uScanline;
uniform float uGrain;
uniform float uGlitch;
uniform float uFisheye;
// PER-TEXTURE GATES. Which of the uPost* groups below the composite must
// apply is a property of the SCENE, not of the frame: shader scenes already
// warp/grade in view()/grade(), particle scenes in particle_vert/frag,
// milkdrop in pm_post_frag, and the fluid family (Fluid, Curl Flow, Water)
// applies nothing of its own. main() routes BOTH uTexA (incoming) and uTexB
// (outgoing) through postFx, and during a transition those two textures can
// come from DIFFERENT families - so one gate per texture, never one shared
// gate taken from the incoming scene. With a single gate a julia -> fluid
// fade graded the already-graded julia frame a second time (a white,
// over-zoomed flash for the whole fade) and the reverse dropped the fluid
// grade entirely.
//   x = geometry/stylize group   (warp, ripple, kaleido, pixelate, tile,
//                                 twist, bloom, posterize, drift, sway,
//                                 shake, temp, solarize)
//   y = mirror + invert
//   z = colour grade + zoom/rotation  (the uPostZoom..uPostHue block below)
//   w = beat pulse
// A component is 1.0 when the COMPOSITE owns that group for that texture and
// 0.0 when the scene already applied it. Uploaded by VisualizerRenderer and
// FxCompositor from CompositeGrade.gateFor(); the export path uploads the
// same value in both slots (it never transitions).
uniform vec4 uGateA;
uniform vec4 uGateB;
// Universal geometric + color post effects, applied here so they work on
// EVERY scene type - including particle scenes, whose vertex pipeline can't
// honor screen-space shape params on its own.
uniform float uPostWarp;
uniform float uPostRipple;
uniform float uPostSymmetry;
uniform float uPostKaleido;
uniform float uPostPixelate;
uniform float uPostTile;
uniform float uPostTwist;
uniform float uPostMirror;
uniform float uPostBloom;
uniform float uPostPosterize;
uniform float uPostInvert;
// Motion + color params that particle/milkdrop pipelines can't honor natively
// (shader scenes do these in-shader, so the gate's x is 0 for them and every
// block below is skipped - the values themselves are uploaded raw).
uniform float uPostDriftX;
uniform float uPostDriftY;
uniform float uPostSway;
uniform float uPostShake;
uniform float uPostTemp;
uniform float uPostSolarize;
// Universal grading + zoom/rotation for scenes that grade NOTHING themselves
// (the fluid family: Fluid, Curl Flow, Water). Shader scenes grade in
// view()/grade(), particle scenes in particle_vert/particle_frag and milkdrop
// in pm_post_frag, so for those the gate's z is 0 and the whole block below is
// skipped - a bitwise no-op, never a double grade.
// The gate is required (not just neutral values) because the neutral value of
// these uniforms is 1.0, not 0.0: a program that never uploads them would
// otherwise read the GL default 0 and render black at 20x zoom.
uniform float uPostZoom;
uniform float uPostRotation; // already-integrated angle (renderer sums rot*dt)
uniform float uPostSat;
uniform float uPostBright;   // brightness * intensity, as everywhere else
uniform float uPostContrast;
uniform float uPostGamma;
uniform float uPostHue;
// Beat pulse for scenes that don't swell on the beat themselves (the fluid
// family AND milkdrop - a DIFFERENT set from the grading block above, which
// milkdrop is excluded from because pm_post_frag grades but never pulses).
// This is the pulse AMOUNT with the beat envelope already folded in on the
// CPU (CompositeGrade.pulseAmount), because the composite pass has no BPM
// phase clock of its own. Its gate component (w) is separate from the grade's
// (z) for that reason; the VALUE is still neutral at 0.0, which is also GL's
// default, so a program that never uploads it renders identically.
uniform float uPostPulse;
// FlowField fluidWarp: the shared fluid velocity field bends the sampling
// coordinate of ANY scene's output (particles, shaders, milkdrop) before the
// scene-texture fetch. A 1x1 zero texture is bound when disabled so the
// sampler is always valid.
uniform highp sampler2D uFlow;   // half-float velocities exceed lowp range
uniform float uFlowStrength;
// Ripple overlay (F2): the shared RippleSim heightfield refracts ANY scene's
// output and adds a specular glint on the wave crests, so the water style's
// surface physics can ride on top of particles, shaders and MilkDrop. Lives
// inside postFx() so transitions apply it to BOTH images. A 1x1 zero texture
// is bound when disabled (or when WATER is active - its own display already
// refracts) so the sampler is always valid.
uniform highp sampler2D uRipple; // half-float heights exceed lowp range
uniform vec2 uRippleTexel;
uniform float uRippleStrength;
uniform float uRippleSpecular;
// Underlay (W00): a full-frame RGBA8 background layer, blended into the finished composite
// colour in main() below, just before the dither - so it applies once, identically, regardless
// of transitions or Layer 2. uUnderlayBlend selects the function (0 = screen: shows through where
// the scene is black while leaving bright scene pixels alone; 1 = multiply; 2 = add) and
// uUnderlayAmount (0..1) is how much of that blend result replaces the plain scene colour.
// uUnderlayAmount is 0 by default (GL's uniform default for a value never uploaded), so a program
// that never sets it renders identically to one with no underlay pass at all. See
// CompositePass.hpp for the full pass description.
uniform sampler2D uUnderlay;
uniform int uUnderlayBlend;
uniform float uUnderlayAmount;
// The Camera (SharedCamera.hpp), drawn here the flat way for every picture whose Style does not move a
// camera of its own. uCamGate: x/y = the flat moves for uTexA/uTexB, z/w = the flat endless zoom for
// uTexA/uTexB (only the Styles whose renderer has none). Push-in magnifies, the orbit tilts the picture
// plane in perspective (radians, x yaw, y pitch), uCamDive holds the 0..1 phases of a dive through the
// picture (x travel, y endless zoom), and the shake (xy offset, z roll) moves every picture alike.
// Every one of them is 0 at the Camera's starting values, and then nothing here runs.
uniform vec4 uCamGate;
uniform float uCamPush;
uniform vec2 uCamTilt;
uniform vec3 uCamShake;
uniform vec2 uCamDive;

float compHash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

// Hue rotation about the grey axis, byte-for-byte the same formula as
// pm_post_frag's hueRotate (and mirrored by CompositeGrade.hueRotate).
vec3 hueRotate(vec3 c, float a) {
    const vec3 w = vec3(0.299, 0.587, 0.114);
    float angle = a * 6.2831;
    float cs = cos(angle);
    float sn = sin(angle);
    return vec3(dot(c, w)) + (c - vec3(dot(c, w))) * cs + cross(vec3(0.57735), c) * sn;
}

// Applies geometric transforms to a [0,1] uv, operating in centered space.
// [gate] is this texture's uGateA/uGateB (see their declaration).
// [cam] says whether the Camera's flat moves apply to this picture; [scale] is this sample's share of a
// dive (1 when there is none).
vec2 geo(vec2 uv, vec4 gate, bool cam, float scale) {
    bool geoOn = gate.x > 0.5;
    bool mirrorOn = gate.y > 0.5;
    bool gradeOn = gate.z > 0.5;
    bool pulseOn = gate.w > 0.5;
    vec2 c = uv - 0.5;
    // The Camera first, so everything below happens in the moved picture. The shake is a slow, small,
    // bounded wobble (CameraRig): never a jump.
    if (abs(uCamShake.x) + abs(uCamShake.y) + abs(uCamShake.z) > 0.000001) {
        c = mat2(cos(uCamShake.z), -sin(uCamShake.z), sin(uCamShake.z), cos(uCamShake.z)) * c - uCamShake.xy;
    }
    if (cam && uCamPush > 0.0001) c /= 1.0 + uCamPush * 0.6;
    if (cam && abs(uCamTilt.x) + abs(uCamTilt.y) > 0.0001) {
        // A ray from the eye through this point of the picture plane, turned by the orbit, meets the
        // plane again here: the picture seen from the side, in true perspective.
        const float eye = 1.2;
        vec3 r = vec3(c.x * uRatio, c.y, eye);
        float cy = cos(uCamTilt.x);
        float sy = sin(uCamTilt.x);
        float cp = cos(uCamTilt.y);
        float sp = sin(uCamTilt.y);
        r = vec3(cy * r.x + sy * r.z, r.y, -sy * r.x + cy * r.z);
        r = vec3(r.x, cp * r.y - sp * r.z, sp * r.y + cp * r.z);
        c = r.xy * (eye / max(r.z, 0.2));
        c.x /= uRatio;
    }
    if (scale != 1.0) c *= scale;
    // Rotation and sway share one angle, exactly like plasma_frag's view()
    // (a = uRotation + uSway * 0.35 * sin(uTime * 0.7)). uPostRotation is an
    // angle, not a rate: the renderer integrates rotation * dt so the slider
    // stays a SPEED here, matching ShaderScene/ParticleSceneBase/MilkdropScene.
    float sa = gradeOn ? uPostRotation : 0.0;
    if (geoOn && abs(uPostSway) > 0.001) sa += uPostSway * 0.35 * sin(uTime * 0.7);
    if (abs(sa) > 0.0001) {
        c = mat2(cos(sa), -sin(sa), sin(sa), cos(sa)) * c;
    }
    if (geoOn && uPostShake > 0.001) {
        c += uPostShake * uBeat * 0.03 * vec2(sin(uTime * 91.7), cos(uTime * 77.3));
    }
    // Zoom about the centre, same form as the shader scenes and the milkdrop
    // post pass (uv /= max(z, 0.05)), so a given slider value magnifies by the
    // same amount on a fluid style as on julia/mandel.
    if (gradeOn && abs(uPostZoom - 1.0) > 0.0001) {
        c /= max(uPostZoom, 0.05);
    }
    // Beat pulse: a swell about the centre, the same geometric form and 0.22
    // magnitude the shader scenes give it (plasma_frag: pulse = 1.0 + uPulse *
    // 0.22 * bump; z = uZoom * pulse; uv /= z), so one slider value swells the
    // image by the same amount on a fluid or milkdrop style as on julia. Kept
    // on its OWN gate component on purpose: milkdrop grades itself but does
    // not pulse, so it is graded elsewhere yet pulsed here.
    if (pulseOn && uPostPulse > 0.0001) {
        c /= 1.0 + uPostPulse * 0.22;
    }
    if (mirrorOn && uPostMirror > 0.5) c.x = abs(c.x);
    if (geoOn && uPostKaleido > 0.5 && uPostSymmetry >= 2.0) {
        float ang = atan(c.y, c.x);
        float rad = length(c);
        float seg = 6.2831853 / uPostSymmetry;
        ang = abs(mod(ang, seg) - seg * 0.5);
        c = vec2(cos(ang), sin(ang)) * rad;
    }
    if (geoOn && abs(uPostTwist) > 0.001) {
        float tr = length(c) * uPostTwist * 2.0;
        c = mat2(cos(tr), -sin(tr), sin(tr), cos(tr)) * c;
    }
    if (geoOn && uPostWarp > 0.001) {
        c += uPostWarp * 0.06 * vec2(sin(c.y * 8.0 + uTime), cos(c.x * 8.0 + uTime * 1.2));
    }
    if (geoOn && uPostRipple > 0.001) {
        float r = length(c);
        c *= 1.0 + uPostRipple * 0.12 * sin(r * 16.0 - uTime * 3.0);
    }
    vec2 p = c + 0.5;
    if (geoOn && abs(uPostDriftX) + abs(uPostDriftY) > 0.001) {
        // Wrap so the image scrolls instead of smearing at the clamped edge.
        p = fract(p + vec2(uPostDriftX, uPostDriftY) * uTime * 0.1);
    }
    if (geoOn && uPostTile > 1.01) {
        p = fract(p * uPostTile);
    }
    if (geoOn && uPostPixelate > 0.001) {
        float px = mix(512.0, 40.0, uPostPixelate);
        p = (floor(p * px) + 0.5) / px;
    }
    // A dive looks past the picture's edge: mirror it there rather than smear the clamped border.
    if (scale > 1.0) p = 1.0 - abs(1.0 - mod(p, 2.0));
    return p;
}

// Screen-space FX chain applied to the final composited frame, so it works
// identically for shader, particle and milkdrop scenes and during
// transitions. Order: geometry -> distortion (fisheye, glitch) -> sample
// (chromatic aberration) -> shading (posterize, scanlines, grain, vignette,
// bloom, invert).
vec3 postFxAt(sampler2D tex, vec2 uv, vec4 gate, bool cam, float scale) {
    bool geoOn = gate.x > 0.5;
    bool invertOn = gate.y > 0.5;
    bool gradeOn = gate.z > 0.5;
    vec2 p = geo(uv, gate, cam, scale);
    if (uFlowStrength > 0.001) {
        // Soft-limit the field to the +-6 range the 0.015 scale was tuned
        // for: emitter velocities reach ~36 (splat force x audio), and an
        // unbounded warp displaces the fetch by half the screen per frame -
        // perceived as full-screen colour flashing, not a fluid bend.
        vec2 flow = texture(uFlow, p).xy;
        flow *= 6.0 / (6.0 + length(flow));
        p -= flow * uFlowStrength * 0.015;
    }
    // Ripple overlay refraction: the height gradient bends the scene fetch,
    // soft-capped with the same idiom as the uFlow block above (and lockstep
    // with RippleMath.refractionOffset / water_display_frag: cap 0.08 UV).
    vec2 rippleGrad = vec2(0.0);
    if (uRippleStrength > 0.001) {
        float hL = texture(uRipple, p - vec2(uRippleTexel.x, 0.0)).x;
        float hR = texture(uRipple, p + vec2(uRippleTexel.x, 0.0)).x;
        float hT = texture(uRipple, p + vec2(0.0, uRippleTexel.y)).x;
        float hB = texture(uRipple, p - vec2(0.0, uRippleTexel.y)).x;
        rippleGrad = vec2(hR - hL, hT - hB);
        vec2 off = rippleGrad * uRippleStrength * 0.05;
        off *= 0.08 / (0.08 + length(off));
        p -= off;
    }
    if (abs(uFisheye) > 0.001) {
        vec2 c = p - 0.5;
        float r = length(c) * 2.0;
        p = 0.5 + c * (1.0 + uFisheye * 0.6 * (r * r - 0.5));
    }
    if (uGlitch > 0.001) {
        float band = floor(p.y * 24.0);
        float jump = compHash12(vec2(band, floor(uTime * 12.0)));
        if (jump > 1.0 - uGlitch * 0.35 * (0.4 + uBeat)) {
            p.x += (compHash12(vec2(band, uTime)) - 0.5) * uGlitch * 0.3;
        }
    }
    vec3 col;
    if (uChroma > 0.001) {
        vec2 dir = (p - 0.5) * uChroma * 0.03;
        col.r = texture(tex, clamp(p + dir, 0.0, 1.0)).r;
        col.g = texture(tex, clamp(p, 0.0, 1.0)).g;
        col.b = texture(tex, clamp(p - dir, 0.0, 1.0)).b;
    } else {
        col = texture(tex, clamp(p, 0.0, 1.0)).rgb;
    }
    // Ripple overlay glint: Blinn specular from the wave normal (same light
    // rig as water_display_frag) plus a small gradient-magnitude shimmer so
    // crests catch light on any scene underneath.
    if (uRippleStrength > 0.001 && uRippleSpecular > 0.001) {
        vec3 rn = normalize(vec3(-rippleGrad * 24.0, 1.0));
        vec3 lightDir = normalize(vec3(-0.4, 0.6, 0.8));
        vec3 halfVec = normalize(lightDir + vec3(0.0, 0.0, 1.0));
        // Fade in with gradient magnitude: a calm (flat) surface must add
        // nothing, or the overlay would wash every frame with constant spec.
        float wave = smoothstep(0.003, 0.03, length(rippleGrad));
        float spec = pow(max(dot(rn, halfVec), 0.0), 24.0) * wave;
        float shimmer = min(length(rippleGrad) * 0.6, 0.35);
        col += uRippleSpecular * (spec + shimmer) * vec3(1.0, 0.98, 0.92);
    }
    if (geoOn && uPostBloom > 0.001) col += uPostBloom * col * col;
    if (geoOn && uPostPosterize > 0.001) {
        float levels = mix(24.0, 3.0, uPostPosterize);
        col = floor(col * levels + 0.5) / levels;
    }
    // Colour grade: identical chain and order to plasma_frag's grade() and
    // pm_post_frag (hue -> saturation -> contrast -> gamma, brightness last,
    // just before invert), so a slider value lands the same on every style.
    // Mirrored on the CPU by CompositeGrade for the headless test.
    if (gradeOn) {
        if (abs(uPostHue) > 0.0001) col = hueRotate(col, uPostHue);
        if (abs(uPostSat - 1.0) > 0.0001) {
            float lum = dot(col, vec3(0.299, 0.587, 0.114));
            col = mix(vec3(lum), col, uPostSat);
        }
        if (abs(uPostContrast - 1.0) > 0.0001) col = (col - 0.5) * uPostContrast + 0.5;
        if (abs(uPostGamma - 1.0) > 0.0001) col = pow(max(col, 0.0), vec3(1.0 / max(uPostGamma, 0.05)));
    }
    if (uScanline > 0.001) {
        col *= 1.0 - uScanline * 0.35 * (0.5 + 0.5 * sin(p.y * 900.0));
    }
    if (uGrain > 0.001) {
        col += (compHash12(p * 1913.0 + uTime) - 0.5) * uGrain * 0.25;
    }
    if (uVignette > 0.001) {
        float d = length(uv - 0.5) * 1.4142;
        col *= 1.0 - uVignette * smoothstep(0.5, 1.05, d);
    }
    if (geoOn) {
        col.r += uPostTemp * 0.12;
        col.b -= uPostTemp * 0.12;
        if (uPostSolarize > 0.5) col = abs(1.0 - 2.0 * col);
    }
    if (gradeOn) col *= uPostBright;
    if (invertOn && uPostInvert > 0.5) col = max(vec3(1.0) - col, 0.0);
    return col;
}

// [cam] is this picture's uCamGate pair: x its flat Camera moves, y its flat endless zoom. A dive
// (travel, endless zoom) draws the picture at two scales an octave apart and crossfades between them
// over each cycle, so it flies on without end and never jumps where the cycle starts again.
vec3 postFx(sampler2D tex, vec2 uv, vec4 gate, vec2 cam) {
    bool camOn = cam.x > 0.5;
    float phase = fract((camOn ? uCamDive.x : 0.0) + (cam.y > 0.5 ? uCamDive.y : 0.0));
    if (phase < 0.0001) return postFxAt(tex, uv, gate, camOn, 1.0);
    float near = exp2(-phase);
    vec3 inner = postFxAt(tex, uv, gate, camOn, near);
    vec3 outer = postFxAt(tex, uv, gate, camOn, near * 2.0);
    return mix(inner, outer, smoothstep(0.0, 1.0, phase));
}

// Every sample below goes through postFx, INCLUDING the outgoing texture:
// sampling uTexB raw made the whole FX chain (vignette, chroma, scanlines,
// kaleido, bloom...) pop off on the old scene for the entire transition and
// snap back at the end - perceived as a flash on every preset/scene switch.
// SLIDE/ZOOM previously resampled BOTH textures raw, dropping FX everywhere.
// Each texture carries its OWN gate: uTexA is the incoming scene, uTexB the
// outgoing one, and a cross-family switch (julia -> fluid) must not grade the
// outgoing frame under the incoming scene's rule.
// ---------------------------------------------------------------------------
// gl-transitions splice point. VisualizerRenderer/FxCompositor build a variant
// of this shader per selected transition: `#define MV_TRANSITION 1` after the
// #version line, and the transition's own source substituted for the marker
// below. The base program (built-in styles only) compiles with neither, so
// nothing here costs anything until a library transition is chosen.
//
// The gl-transitions contract is `vec4 transition(vec2 uv)` reading `progress`,
// `ratio`, `getFromColor()` and `getToColor()`. Those two samplers are wired to
// postFx rather than to raw texture fetches ON PURPOSE: every transition then
// blends frames that already carry the FX chain and the per-texture grade
// gates, exactly as the built-in styles do. Sampling raw would make the whole
// FX chain pop off for the length of every switch.
//
// NOTE the direction. In gl-transitions, `from` is the OUTGOING image and `to`
// the incoming one; here uTexA is the ACTIVE (incoming) scene and uTexB the
// outgoing one, so from -> B and to -> A. Getting this backwards plays every
// transition in reverse, which reads as "the new scene wipes away to reveal
// the old one".
#ifdef MV_TRANSITION
#define progress uProgress
#define ratio uRatio
vec4 getFromColor(vec2 uv) { return vec4(postFx(uTexB, clamp(uv, 0.0, 1.0), uGateB, uCamGate.yw), 1.0); }
vec4 getToColor(vec2 uv) { return vec4(postFx(uTexA, clamp(uv, 0.0, 1.0), uGateA, uCamGate.xz), 1.0); }
// __GL_TRANSITION_SOURCE__
#endif

/**
 * The transition stage: the incoming scene, or a blend of it with the outgoing
 * one, sampled at [uv] (vUv, or where Layer 2's Displace has bent it). Split
 * out of main() so every path returns a colour rather than writing
 * fragColor itself - the dither below has to be the last thing that touches the
 * output, and an early `return` in main() used to skip it.
 */
vec3 blended(vec2 uv) {
#ifdef MV_TRANSITION
    // uStyle 5 is "a spliced library transition is running". The variant stays
    // bound outside transitions too - swapping programs per frame would cost
    // more than the branch - so it must still render a plain CUT at uStyle 0.
    if (uStyle == 5 && uProgress < 1.0) {
        return transition(uv).rgb;
    }
#endif
    vec3 a = postFx(uTexA, uv, uGateA, uCamGate.xz);
    if (uStyle == 0 || uProgress >= 1.0) {
        return a;
    }
    if (uStyle == 1) {
        vec3 b = postFx(uTexB, uv, uGateB, uCamGate.yw);
        return mix(b, a, uProgress);
    } else if (uStyle == 3) {
        float sh = smoothstep(0.0, 1.0, uProgress);
        vec3 aSlide = postFx(uTexA, clamp(uv + vec2(1.0 - sh, 0.0), 0.0, 1.0), uGateA, uCamGate.xz);
        vec3 bSlide = postFx(uTexB, clamp(uv - vec2(sh, 0.0), 0.0, 1.0), uGateB, uCamGate.yw);
        return uv.x < sh ? aSlide : bSlide;
    } else if (uStyle == 4) {
        float sh = smoothstep(0.0, 1.0, uProgress);
        vec2 zUv = (uv - 0.5) / (1.0 + sh * 2.5) + 0.5;
        vec3 bZoom = postFx(uTexB, clamp(zUv, 0.0, 1.0), uGateB, uCamGate.yw);
        vec2 aUv = (uv - 0.5) * (1.0 + (1.0 - sh) * 0.35) + 0.5;
        vec3 aZoom = postFx(uTexA, clamp(aUv, 0.0, 1.0), uGateA, uCamGate.xz);
        return mix(bZoom, aZoom, sh);
    } else {
        float lumB = dot(texture(uTexB, uv).rgb, vec3(0.299, 0.587, 0.114));
        vec2 melted = uv + vec2(0.0, uProgress * (0.25 + lumB * 0.6));
        vec3 bMelt = postFx(uTexB, clamp(melted, 0.0, 1.0), uGateB, uCamGate.yw);
        float reveal = smoothstep(0.0, 1.0, uProgress * 1.4 - lumB * 0.4);
        return mix(bMelt, a, clamp(reveal, 0.0, 1.0));
    }
}

// Layer 2's Displace: its luminance gradient (central differences a few texels apart, where its picture
// is drawn) bends where Layer 1 is sampled, like a refraction, soft-capped the way the ripple overlay's
// refraction is. Fades with Layer 2.
vec2 layerDisplaced(vec2 uv) {
    if (uLayerPresence <= 0.0 || uLayerInfluence != 1 || uLayerAmount <= 0.0001) return uv;
    vec2 p = geo(uv, uGateL, uCamGateL.x > 0.5, 1.0);
    vec2 texel = 2.5 / vec2(textureSize(uTexL, 0));
    float lL = layerLum(texture(uTexL, p - vec2(texel.x, 0.0)).rgb);
    float lR = layerLum(texture(uTexL, p + vec2(texel.x, 0.0)).rgb);
    float lB = layerLum(texture(uTexL, p - vec2(0.0, texel.y)).rgb);
    float lT = layerLum(texture(uTexL, p + vec2(0.0, texel.y)).rgb);
    vec2 off = vec2(lR - lL, lT - lB) * uLayerAmount * uLayerPresence * 0.08;
    off *= 0.06 / (0.06 + length(off));
    return clamp(uv - off, 0.0, 1.0);
}

// Layer 2 over Layer 1 ([base], everything above it, transitions included), both as postFx gives them:
// Layer 1 is first acted on by Layer 2 (Mask: its luminance gates Layer 1, a luma key; Tint: Layer 1 at
// its own luminance in Layer 2's colour), then Layer 2 is laid over it, or with the swap Layer 1 over
// Layer 2, and the whole fades with uLayerPresence.
vec3 layered(vec3 base, vec3 top) {
    vec3 b = layerToLinear(base);
    vec3 t = layerToLinear(top);
    vec3 l1 = b;
    if (uLayerInfluence == 2) {
        l1 *= mix(1.0, smoothstep(0.02, 0.5, layerLum(t)), uLayerAmount);
    } else if (uLayerInfluence == 3) {
        l1 = mix(l1, layerSetLum(t, layerLum(l1)), uLayerAmount);
    }
    vec3 col = layerOver(l1, t, uLayerBlend, uLayerOpacity);
    if (uLayerSwap > 0.0001) col = mix(col, layerOver(t, l1, uLayerBlend, uLayerOpacity), uLayerSwap);
    return layerToSrgb(mix(b, col, uLayerPresence));
}

void main() {
    vec3 col = blended(layerDisplaced(vUv));
    if (uLayerPresence > 0.0) col = layered(col, postFx(uTexL, vUv, uGateL, uCamGateL));
    // Underlay (W00): folded in here, after every transition/Layer 2/postFx blend above and
    // before the dither, so it is the one place both the live composite and the offscreen export
    // apply it - see the uUnderlay* declarations above and CompositePass.hpp.
    if (uUnderlayAmount > 0.0001) {
        vec3 u = texture(uUnderlay, vUv).rgb;
        vec3 underlayBlended;
        if (uUnderlayBlend == 1) {
            underlayBlended = col * u;
        } else if (uUnderlayBlend == 2) {
            underlayBlended = col + u;
        } else {
            underlayBlended = vec3(1.0) - (vec3(1.0) - col) * (vec3(1.0) - u);
        }
        col = mix(col, clamp(underlayBlended, 0.0, 1.0), clamp(uUnderlayAmount, 0.0, 1.0));
    }
    // Dither LAST, after every grade and effect, sampled one texel per output
    // pixel: this is fighting the 8-bit quantization of the surface being
    // written, so it has to be measured in that surface's own steps. Every
    // smooth ramp in the app - plasma, aurora, solar, glow falloffs, the fluid
    // pressure field - banded visibly on an OLED panel in a dark room without
    // it. uDither is 0 when the mask could not be loaded, making this an exact
    // no-op rather than a failure.
    col += (texture(uNoise, gl_FragCoord.xy / 64.0).r - 0.5) * uDither;
    fragColor = vec4(col, 1.0);
}
