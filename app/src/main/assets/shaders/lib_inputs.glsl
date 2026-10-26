// The shared Style inputs (core/viz StyleInputs.hpp, docs/style-packs.md "inputs"): what MilkDrop's
// shared include hands every preset, for a shader Style.
//
// A Style lists the inputs it reads in its manifest's "inputs", and ShaderScene makes and binds only
// those: a sampler here that the manifest does not list is bound to nothing, so the check refuses a
// shader that reads one (tools/ci/check_style_packs.py, tools/glslcheck). An input nobody reads costs
// nothing: the textures are made the first time a Style that declares one draws, the previous frame
// and the blur levels are kept only for a Style that declares them, and an unread uniform is linked
// away.
//
//   noise_lq        uNoiseLq     256x256 RGBA, white noise: one value per texel, four channels
//   noise_mq        uNoiseMq     smooth value noise, a lattice point every 4 texels
//   noise_hq        uNoiseHq     smoother, every 8 texels
//   noise_vol       uNoiseVol    32x32x32 RGBA white noise; vnoiseVol and fbmVol below
//   blue_noise      uBlueNoise   the 64x64 blue-noise mask (one channel), for dithering a march
//   previous_frame  uPrevFrame   the last frame this Style drew, at its size (HDR: may exceed 1); with
//                                "feedback", that frame carried through the warp (lib_feedback.glsl)
//   blur1..blur3    uBlur1..3    that frame blurred, at 1/4, 1/8 and 1/16 of its size
//   clocks          uFrame       frames drawn this visit (wraps at 2^24), uFrameDt the frame's
//                                seconds, uVisitRand four random values drawn once per visit, and
//                                uStyleAge seconds into the visit (stops at an hour)
//   bar             uBarPhase    0..1 through the bar, a clock locked to the heard downbeat that never
//                                runs backwards or jumps; uBarConfidence 0..1, how sure it is
//
// Every texture tiles (the noise) or is clamped (the frames). The noise is seeded from the Render seed
// and uVisitRand too in a Render, so a Render gives the same frames every time. The frames are the
// Style's own picture, so reading them back is a feedback loop: keep what feeds back below 1 (the
// No-flash rule: a loop that grows can only end in a flash).

// GLSL ES 3.00 has no default precision for sampler3D, and a lowp sampler2D would clip the HDR frames.
uniform highp sampler2D uNoiseLq;
uniform highp sampler2D uNoiseMq;
uniform highp sampler2D uNoiseHq;
uniform highp sampler3D uNoiseVol;
uniform highp sampler2D uBlueNoise;
uniform highp sampler2D uPrevFrame;
uniform highp sampler2D uBlur1;
uniform highp sampler2D uBlur2;
uniform highp sampler2D uBlur3;
uniform float uFrame;
uniform float uFrameDt;
uniform vec4 uVisitRand;
uniform float uStyleAge;
uniform float uBarPhase;
uniform float uBarConfidence;

const float NOISE_SIZE = 256.0;
const float NOISE_VOL_SIZE = 32.0;
const float BAR_BEATS = 4.0;

/**
 * Value noise from the volume: lib_sdf3's vnoise3 (a lattice point per unit, quintic between them)
 * for one texture fetch instead of eight hashes. The quintic weight moves the sample inside the cell
 * and the texture's linear filter does the blend; the lattice tiles every NOISE_VOL_SIZE units.
 */
float vnoiseVol(vec3 p) {
    vec3 f = floor(p);
    vec3 t = p - f;
    vec3 w = t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    return textureLod(uNoiseVol, (f + w + 0.5) / NOISE_VOL_SIZE, 0.0).r;
}

/** The compile-time ceiling on fbmVol's loop, as lib_sdf3's FBM3_MAX_OCTAVES. */
#define FBM_VOL_MAX_OCTAVES 6

/** lib_sdf3's fbm3 over vnoiseVol: the same octaves, lacunarity and offsets, normalised to [0, 1]. */
float fbmVol(vec3 p, int octaves) {
    float sum = 0.0;
    float amp = 0.5;
    float norm = 0.0;
    for (int i = 0; i < FBM_VOL_MAX_OCTAVES; i++) {
        if (i >= octaves) break;
        sum += amp * vnoiseVol(p);
        norm += amp;
        p = p * 2.02 + vec3(37.13, 11.71, 53.29);
        amp *= 0.5;
    }
    return norm > 0.0 ? sum / norm : 0.0;
}

/**
 * A fractal sum of one of the noise planes (pass uNoiseLq, uNoiseMq or uNoiseHq), `p` in texels,
 * normalised to [0, 1]. Each octave is one fetch.
 */
float fbmTex(highp sampler2D tex, vec2 p, int octaves) {
    float sum = 0.0;
    float amp = 0.5;
    float norm = 0.0;
    for (int i = 0; i < FBM_VOL_MAX_OCTAVES; i++) {
        if (i >= octaves) break;
        sum += amp * textureLod(tex, p / NOISE_SIZE, 0.0).r;
        norm += amp;
        p = p * 2.02 + vec2(37.13, 11.71);
        amp *= 0.5;
    }
    return norm > 0.0 ? sum / norm : 0.0;
}
