// glslcheck - compiles and renders the app's shader assets headlessly.
//
// tools/shaderpreview predates the C++ port of the renderer and reads Kotlin
// files that no longer exist, so it can no longer run. This is the small,
// honest replacement: it answers two questions per shader, "does the GLSL the
// native core would assemble compile under an ES 3.00 grammar" and "does one
// frame of it come out non-black", and it writes the frame to a PNG so the
// answer can be looked at rather than trusted.
//
// Includes are resolved the way core/render/ShaderSource.cpp resolves them: the
// registered names are PARSED OUT OF THAT FILE (kIncludes), the line pattern
// is the same anchored `//#include <name>`, and an unknown name is a hard
// error - so a library added to the app without registering it fails here
// the same way it fails on the phone. The assembly is the one in
// core/util/ShaderAssembly.cpp: `#line` directives number each file as its own
// source string and a closing `// geode-sources:` comment names them, so a
// compile error names the author's file and line, not the assembled source.
//
// Uniform values mirror ShaderScene::uploadParams / uploadMotion / uploadTouch
// with SceneParams defaults and a mid-loudness audio frame. Every uniform the
// resolved GLSL declares must have a value in the table or the run refuses:
// a uniform the harness forgot defaults to zero in GL, and a zeroed feature
// is indistinguishable from a broken one.
//
// What it cannot tell you is what tools/shaderpreview/README.md says under
// the same heading: SwiftShader ignores precision qualifiers, tolerates
// uniform counts real ES 3.0 drivers reject, and is not a Mali or an Adreno.
// This is a lower bound on portability, not a device check.
//
//   node check.mjs --list
//   node check.mjs --shader kifs --out out/kifs.png
//   node check.mjs --all --size 200
//   node check.mjs --fluid-display               # every SHADING/BLOOM/SUNRAYS x LOOK variant
//   node check.mjs --all --resize                # each style's picture across a scene-target resize
//   node check.mjs --effects --out out/effects   # one frame per Shared core effect
//   node check.mjs --layers --out out/layers     # one frame per Layer 2 blend mode and influence
//   node check.mjs --all --glslang               # every Style's GLSL ES through glslangValidator
//   node check.mjs --glslang-self-test           # glslang still fails on planted violations
//   node check.mjs --shader kifs --glslang --watch --push   # the authoring loop (docs/style-packs.md)
//
// --glslang checks the selected Styles with glslang (glslangValidator, a CI-only tool that never ships)
// instead of rendering them: strict GLSL ES 3.00, beside what ANGLE over SwiftShader accepts.
// --watch runs the selected checks again whenever a Style pack or a shared shader changes.
// --push copies each selected Style's pack, once it passes, into the debug app's Style lab folder with
// adb, where the running app reloads it within about a second.
//
// --resize draws one frame into a scene target, resizes the target to RESIZE_SCALE of its size the
// way Framebuffer::resizePreserving does (a linear blit into the new target, never a clear), and
// writes what the resized target holds: the picture the renderer's next frame starts from. It fails
// when that picture is darker than KEEP_FRACTION of the one before the resize.

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { launch } from '../shaderpreview/lib/cdp.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..', '..');
const SHADERS = path.join(ROOT, 'app', 'src', 'main', 'assets', 'shaders');
const SHADER_SOURCE_CPP = path.join(ROOT, 'core', 'render', 'ShaderSource.cpp');
// The Style packs (docs/style-packs.md): styles/index.json lists them, each styles/<id>/manifest.json
// names its family and, for a shader Style, its shader file.
const STYLES = path.join(ROOT, 'engine', 'scenes', 'src', 'main', 'stylepacks', 'styles');
const FLUID_LOOK_HPP = path.join(ROOT, 'core', 'viz', 'fluid', 'FluidLook.hpp');

// One load step down and then some: the renderer's scale moves in 0.05 steps, a Style's own scale
// and a thermal tier move it further.
const RESIZE_SCALE = 0.75;
const KEEP_FRACTION = 0.5;

// ---- includes, as ShaderSource.cpp does them ------------------------------

function parseIncludeRegistry() {
  const src = fs.readFileSync(SHADER_SOURCE_CPP, 'utf8');
  const m = src.match(/kIncludes\s*=\s*\{([\s\S]*?)\};/);
  if (!m) throw new Error(`could not find kIncludes in ${SHADER_SOURCE_CPP}`);
  return [...m[1].matchAll(/"([A-Za-z0-9_]+)"/g)].map((x) => x[1]);
}

const INCLUDE_LINE = /^[ \t]*\/\/#include[ \t]+(\w+)[ \t]*$/;
const VERSION_LINE = /^[ \t]*#[ \t]*version/;
const SOURCES_TAG = '// geode-sources:';

// core/util/ShaderAssembly.cpp's assemble(): `source`, read from `file` (the name a compile error
// gives), with each include replaced by its library, recursively, `#line` directives around each
// library and after the `#version` line, and the sources comment last.
function resolveIncludes(source, registry, file) {
  const files = [file];
  const open = [];
  const expand = (text, index) => {
    const out = [];
    text.split('\n').forEach((rawLine, i) => {
      const line = rawLine.replace(/\r$/, '');
      const m = line.match(INCLUDE_LINE);
      if (!m) {
        out.push(rawLine);
        if (VERSION_LINE.test(line)) out.push(`#line ${i + 2} ${index}`);
        return;
      }
      const where = ` (${files[index]}:${i + 1})`;
      if (!registry.includes(m[1])) throw new Error(`unknown shader include '${m[1]}'${where}`);
      if (open.includes(m[1])) throw new Error(`circular shader include '${m[1]}'${where}`);
      const libFile = `shaders/${m[1]}.glsl`;
      const libPath = path.join(SHADERS, `${m[1]}.glsl`);
      if (!fs.existsSync(libPath)) throw new Error(`missing shader include '${m[1]}'${where}`);
      let libIndex = files.indexOf(libFile);
      if (libIndex < 0) libIndex = files.push(libFile) - 1;
      open.push(m[1]);
      out.push(`#line 1 ${libIndex}`, expand(fs.readFileSync(libPath, 'utf8'), libIndex), `#line ${i + 2} ${index}`);
      open.pop();
    });
    return out.join('\n');
  };
  const body = expand(source, 0);
  return `${body}\n${SOURCES_TAG} ${files.map((f, i) => `${i}=${f}`).join(' ')}\n`;
}

// core/util/ShaderAssembly.cpp's remapLog(): each log line's first "<n>:<line>" location, at the
// start or after a blank, named by the file `assembled` numbers n.
function remapLog(log, assembled) {
  const tag = assembled.lastIndexOf(SOURCES_TAG);
  if (tag < 0) return log;
  const files = assembled
    .slice(tag + SOURCES_TAG.length)
    .split('\n')[0]
    .trim()
    .split(/\s+/)
    .map((entry) => entry.slice(entry.indexOf('=') + 1));
  return log
    .split('\n')
    .map((line) => line.replace(/(^|[ \t])(\d+)(?=:\d)/, (all, lead, n) => (Number(n) < files.length ? lead + files[Number(n)] : all)))
    .join('\n');
}

// id -> shader file of every shader Style, in the packs' index order.
function shaderStyles() {
  return new Map([...shaderPacks()].map(([id, pack]) => [id, pack.file]));
}

// id -> { file, inputs } of every shader Style: its shader and the shared inputs its manifest declares.
function shaderPacks() {
  const index = JSON.parse(fs.readFileSync(path.join(STYLES, 'index.json'), 'utf8'));
  const out = new Map();
  for (const id of index.styles) {
    const manifest = JSON.parse(fs.readFileSync(path.join(STYLES, id, 'manifest.json'), 'utf8'));
    if (manifest.family === 'shader') out.set(id, { file: path.join(STYLES, id, manifest.shader), inputs: manifest.inputs ?? [] });
  }
  return out;
}

// The shared inputs a rendered program read (its uniforms the page did not report dropped) that the
// Style's manifest does not declare.
function undeclaredInputs(job, result) {
  if (!job.inputs) return [];
  const live = job.uniforms.map((u) => u.name).filter((name) => name in INPUT_UNIFORMS && !result.dropped.includes(name));
  return [...new Set(live.map((name) => INPUT_UNIFORMS[name]))].filter((input) => !job.inputs.includes(input));
}

function fluidLookCount() {
  const src = fs.readFileSync(FLUID_LOOK_HPP, 'utf8');
  const m = src.match(/kLookCount\s*=\s*(\d+)/);
  return m ? Number(m[1]) : 1;
}

// ---- uniform declarations --------------------------------------------------

function stripComments(s) {
  return s.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
}

function parseUniforms(glsl) {
  const clean = stripComments(glsl);
  const defines = new Map();
  for (const m of clean.matchAll(/#define\s+(\w+)\s+(\d+)/g)) defines.set(m[1], Number(m[2]));
  const out = [];
  for (const m of clean.matchAll(/uniform\s+(?:(?:highp|mediump|lowp)\s+)?(\w+)\s+(\w+)(?:\s*\[\s*(\w+)\s*\])?\s*;/g)) {
    let count = 1;
    if (m[3] !== undefined) count = /^\d+$/.test(m[3]) ? Number(m[3]) : defines.get(m[3]);
    if (!count) throw new Error(`cannot resolve array length of uniform ${m[2]}`);
    out.push({ type: m[1], name: m[2], count });
  }
  return out;
}

// ---- the values the app uploads --------------------------------------------
//
// ShaderScene::uploadParams with SceneParams defaults (palette 0 = Spectrum,
// base 0, range 1), a mid-loudness frame from the motion layer, no fingers.
// Anything not here is a fatal audit failure, by design.

const SHADER_VALUES = {
  uTime: 37.0, uResolution: [0, 0], uBass: 0.55, uMid: 0.45, uTreble: 0.35, uEnergy: 0.5, uBeat: 0.2,
  uSpeed: 1, uZoom: 1, uRotation: 0, uZoomPhase: 0, uColorShift: 0, uHueRange: 1, uSat: 1, uBright: 1,
  uInvert: 0, uIntensity: 1, uMirrorX: 0, uBeatResponse: 1, uTurbulence: 0, uPalBase: 0, uPalRange: 1,
  uContrast: 1, uGamma: 1, uPal2Base: 0, uPal2Range: 1, uPaletteMix: 0, uDuotone: 0, uBloom: 0, uWarp: 0,
  uRipple: 0, uSymmetry: 6, uKaleido: 0, uMorph: 0, uPixelate: 0, uPosterize: 0, uSway: 0, uPulse: 0,
  uBeatPhase: 0.3, uDriftX: 0, uDriftY: 0, uShake: 0, uTile: 1, uTwist: 0, uTemperature: 0, uSolarize: 0,
  uTouchAnchor: [0, 0, 0, 0], uTouchPoints: [0, 0, 0, 0], uTouchCount: 0, uTouchGesture: 0, uTouchAxis: [0, 0],
  uTouchSpin: 0,
  uGravity: [0, 0, 0], uTilt: [0, 0], uTiltWeight: 0,
  uSteps: 96, uFxOff: 0,
  uPalLutMix: 0, uPalLutRow: 0.5,
  uBassSmooth: 0.5, uMidSmooth: 0.4, uTrebleSmooth: 0.3, uEnergySmooth: 0.45, uSwell: 0.5, uSpike: 0.15,
  uMoveDir: [0.8, 0.6], uSpawnSeed: 0.37, uSpawnAge: 4.0, uFormPhase: 0.62, uFlowPhase: 12.5,
  uEnergyPhase: 16.6, uFlight: 164.3,
  uFlowStrength: 0,
  uXScale: 1, uXCount: 1, uXSize: 1, uXThickness: 1, uXFlight: 1, uXSpin: 1, uXOrbit: 1, uXSwirl: 1, uXDrift: 1,
  uXComplexity: 1, uXEdge: 1, uXFog: 1, uXDecay: 1, uXViscosity: 1, uXGrowth: 1, uXSpread: 1, uXForce: 1, uXReach: 1,
  uXTurn: 1, uXHold: 1, uXVariant: 0, uXCenterX: 0, uXCenterY: 0,
  uXFlightTime: 37.0, uXSpinTime: 37.0, uXDriftTime: 37.0,
  uCamRot: [1, 0, 0, 0, 1, 0, 0, 0, 1], uCamPush: 0, uCamTravel: 0, uCamOrbit: [0, 0],
  // The shared Style inputs' clocks (lib_inputs.glsl): 37 s into a visit at 60 fps.
  uFrame: 2220, uFrameDt: 1 / 60, uVisitRand: [0.31, 0.72, 0.18, 0.55], uStyleAge: 37.0, uBarPhase: 0.4, uBarConfidence: 0.8,
  // The feature bus (lib_scene_features, FeatureBus.hpp) mid-song: a trusted bar, a section and a drop
  // a few seconds back, a fairly bright and tonal sound, a little wide and right of centre.
  uKick: 0.4, uSnare: 0.2, uHat: 0.3, uBeatInBar: 1, uDownbeat: 0.25, uDownbeatConfidence: 0.8,
  uPulseConfidence: 0.85, uTempoStability: 0.9, uNovelty: 0.3, uSection: 0.62, uBuildup: 0.2, uDrop: 0.4,
  uArrival: 0.1, uMacro: 0.5, uSectionHit: 0.05, uDropHit: 0.1, uArrivalHit: 0.0, uCentroid: 0.55,
  uHarmonicity: 0.6, uFlux: 0.35, uOnset: 0.3, uStereoWidth: 0.4, uStereoPan: 0.15, uStereoCorrelation: 0.7,
};

// The shared inputs' textures: the noise as the engine seeds it (page.html mirrors StyleInputs.cpp),
// and the last frame and its blur levels as the same stand-in history.
const SHADER_TEXTURES = {
  uAudioTex: 'audio', uPalLut: 'palette', uFlow: 'flow',
  uNoiseLq: 'noise_lq', uNoiseMq: 'noise_mq', uNoiseHq: 'noise_hq', uNoiseVol: 'noise_vol', uBlueNoise: 'noise',
  uPrevFrame: 'history', uBlur1: 'history_blur', uBlur2: 'history_blur', uBlur3: 'history_blur',
};

// The shared inputs (geode/viz/StyleInputs.hpp): each sampler and clock uniform of lib_inputs.glsl and
// the manifest name that declares it. The engine binds only what a manifest declares, so a Style whose
// linked program still reads an undeclared one fails here (helpers included, which a text search of
// the shader cannot see).
const INPUT_UNIFORMS = {
  uNoiseLq: 'noise_lq', uNoiseMq: 'noise_mq', uNoiseHq: 'noise_hq', uNoiseVol: 'noise_vol', uBlueNoise: 'blue_noise',
  uPrevFrame: 'previous_frame', uBlur1: 'blur1', uBlur2: 'blur2', uBlur3: 'blur3',
  uFrame: 'clocks', uFrameDt: 'clocks', uVisitRand: 'clocks', uStyleAge: 'clocks', uBarPhase: 'bar', uBarConfidence: 'bar',
};

// FluidLook::drawDisplay with mid-loudness audio.
const FLUID_VALUES = {
  uInvRes: [0, 0], uAspect: 1, uDitherScale: [1, 1], uTexelSize: [0, 0],
  uTime: 37.0, uAudio: [0.55, 0.45, 0.35, 0.5], uLookHue: 0.2,
};
const FLUID_TEXTURES = { uDye: 'dye', uBloom: 'dye', uSunrays: 'white', uDither: 'noise' };

function plan(uniforms, values, textures, width, height) {
  const missing = [];
  const out = [];
  for (const u of uniforms) {
    if (u.type.startsWith('sampler')) {
      if (!(u.name in textures)) missing.push(u.name);
      else out.push({ ...u, texture: textures[u.name] });
      continue;
    }
    if (!(u.name in values)) {
      missing.push(u.name);
      continue;
    }
    let value = values[u.name];
    if (u.name === 'uResolution') value = [width, height];
    if (u.name === 'uInvRes' || u.name === 'uTexelSize') value = [1 / width, 1 / height];
    out.push({ ...u, value });
  }
  if (missing.length) throw new Error(`no value for uniform(s): ${missing.join(', ')} - add them to the table`);
  return out;
}

// ---- the Shared core effects ------------------------------------------------
//
// One frame per shared effect (#40), each drawn over the same stand-in picture of a finished Style
// ('scene'), with every other effect at its neutral value. composite_frag.glsl draws most of the
// Shared core with every gate on (as it does for a field Style); shared_fx_frag.glsl draws the
// effects that run before it (SharedFxPass), with 'history' standing in for the last frame.

const COMPOSITE_NEUTRAL = {
  uDither: 0, uRatio: 1, uProgress: 1, uStyle: 0, uTime: 12.5, uBeat: 0.4,
  uChroma: 0, uVignette: 0, uScanline: 0, uGrain: 0, uGlitch: 0, uFisheye: 0,
  uGateA: [1, 1, 1, 1], uGateB: [1, 1, 1, 1],
  uPostWarp: 0, uPostRipple: 0, uPostSymmetry: 6, uPostKaleido: 0, uPostPixelate: 0, uPostTile: 1, uPostTwist: 0,
  uPostMirror: 0, uPostBloom: 0, uPostPosterize: 0, uPostInvert: 0, uPostDriftX: 0, uPostDriftY: 0, uPostSway: 0,
  uPostShake: 0, uPostTemp: 0, uPostSolarize: 0, uPostZoom: 1, uPostRotation: 0, uPostSat: 1, uPostBright: 1,
  uPostContrast: 1, uPostGamma: 1, uPostHue: 0, uPostPulse: 0, uPostAnaglyph: 0,
  uPostPalTint: 0, uPostPalBase: 0, uPostPalSpan: 1,
  uFlowStrength: 0, uRippleTexel: [1 / 64, 1 / 64], uRippleStrength: 0, uRippleSpecular: 0,
  uUnderlayBlend: 0, uUnderlayAmount: 0,
  uCamGate: [1, 1, 1, 1], uCamPush: 0, uCamTilt: [0, 0], uCamShake: [0, 0, 0], uCamDive: [0, 0],
  // Layer 2 off (presence 0); --layers turns it on.
  uGateL: [1, 1, 1, 1], uCamGateL: [1, 1], uLayerPresence: 0, uLayerBlend: 0, uLayerOpacity: 0.6, uLayerInfluence: 0,
  uLayerAmount: 0.7, uLayerSwap: 0,
};
const COMPOSITE_TEXTURES = {
  uTexA: 'scene', uTexB: 'scene', uTexL: 'history', uNoise: 'noise', uFlow: 'flow', uRipple: 'flow', uUnderlay: 'white',
};

const COMPOSITE_EFFECTS = {
  neutral: {},
  kaleidoscope: { uPostKaleido: 1, uPostSymmetry: 6 },
  mirror: { uPostMirror: 1 },
  warp: { uPostWarp: 1 },
  ripple: { uPostRipple: 1 },
  twist: { uPostTwist: 0.8 },
  tile: { uPostTile: 3 },
  glitch: { uGlitch: 0.25, uBeat: 1 },
  pixelate: { uPostPixelate: 0.7 },
  posterize: { uPostPosterize: 0.8 },
  grain: { uGrain: 1 },
  vignette: { uVignette: 1 },
  chromatic: { uChroma: 1 },
  scanlines: { uScanline: 1 },
  fisheye: { uFisheye: 0.8 },
  glow: { uPostBloom: 0.25 },
  grading: { uPostHue: 0.2, uPostSat: 1.4, uPostContrast: 1.2, uPostGamma: 1.3, uPostBright: 0.9 },
  // MilkDrop's palette tint, which the composite applies where the grade gate is 2 (#303).
  palette_tint: { uGateA: [1, 1, 2, 1], uGateB: [1, 1, 2, 1], uPostPalTint: 0.8, uPostPalBase: 0.6, uPostPalSpan: 0.3 },
  temperature: { uPostTemp: 0.8 },
  solarize: { uPostSolarize: 1 },
  invert: { uPostInvert: 1 },
  anaglyph: { uPostAnaglyph: 1 },
  camera_push_in: { uCamPush: 0.8 },
  camera_orbit: { uCamTilt: [0.4, -0.3] },
  camera_travel: { uCamDive: [0.4, 0] },
  camera_endless_zoom: { uCamDive: [0, 0.7] },
  camera_shake: { uCamShake: [0.02, -0.015, 0.02] },
};

// Layer 2 (lib_layers.glsl, Layers.hpp): one frame per blend mode and per influence, the stand-in
// picture of Layer 1 ('scene') with another ('history') laid over it, plus the swap and a transition
// running under Layer 2. The mode names and ordinals are layer::Blend's and layer::Influence's.
const LAYER_BLENDS = ['normal', 'add', 'screen', 'lighten', 'multiply', 'overlay', 'soft_light', 'difference', 'exclusion',
  'luminosity'];
const LAYER_INFLUENCES = ['none', 'bend', 'swirl', 'cut_out', 'tint', 'outline'];

function layerEffects() {
  const on = { uLayerPresence: 1 };
  const out = {};
  // Difference and Exclusion at their No-flash cap, as the engine holds them.
  LAYER_BLENDS.forEach((name, blend) => {
    out[`blend_${name}`] = { ...on, uLayerBlend: blend, uLayerOpacity: blend === 7 || blend === 8 ? 0.25 : 0.6 };
  });
  LAYER_INFLUENCES.forEach((name, influence) => {
    out[`influence_${name}`] = { ...on, uLayerBlend: 2, uLayerInfluence: influence };
  });
  out.swap_hard_light = { ...on, uLayerBlend: 5, uLayerSwap: 1 };
  out.half_faded = { ...on, uLayerBlend: 0, uLayerPresence: 0.5, uLayerSwap: 0.5 };
  out.under_a_fade = { ...on, uLayerBlend: 2, uStyle: 1, uProgress: 0.5, uLayerInfluence: 1 };
  return out;
}

const SHARED_FX_NEUTRAL = {
  uTexel: [0, 0], uAspect: 1, uTime: 12.5, uSoftFocus: 0, uParticles: 0, uParticleKind: 0, uParticleSize: 1,
  uEchoKeep: 0, uEchoZoom: 0, uEchoWarp: 0, uMotionKeep: 0,
};
const SHARED_FX_TEXTURES = { uScene: 'scene', uHistory: 'history' };

const SHARED_FX_EFFECTS = {
  neutral: {},
  soft_focus: { uSoftFocus: 1 },
  motion_blur: { uMotionKeep: 0.6 },
  trails: { uEchoKeep: 0.9 },
  echo: { uEchoKeep: 0.9, uEchoZoom: 0.5, uEchoWarp: 1 },
  particles_motes: { uParticles: 0.8, uParticleKind: 0 },
  particles_sparks: { uParticles: 0.8, uParticleKind: 1 },
  particles_dust: { uParticles: 0.8, uParticleKind: 2 },
  particles_bubbles: { uParticles: 0.8, uParticleKind: 3, uParticleSize: 1.5 },
};

function layerJobs(registry, size) {
  const vert = fs.readFileSync(path.join(SHADERS, 'fade_vert.glsl'), 'utf8');
  const frag = resolveIncludes(fs.readFileSync(path.join(SHADERS, 'composite_frag.glsl'), 'utf8'), registry);
  const uniforms = parseUniforms(frag);
  return Object.entries(layerEffects()).map(([name, values]) => ({
    name: `layer_${name}`,
    vert,
    frag,
    uniforms: plan(uniforms, { ...COMPOSITE_NEUTRAL, ...values }, COMPOSITE_TEXTURES, size, size),
  }));
}
// The HDR pipeline's own passes (core/viz/HdrPass): the bloom's downsample (the thresholded first
// level and a plain one), its upsample, and the final tonemap (highlight roll-off) with and without bloom. The
// stand-in picture is 8-bit, so the threshold is the lowest the Bloom parameter reaches.
const BLOOM_DOWN_NEUTRAL = { uTexel: [0, 0], uPrefilter: 1, uCurve: [0.6, 0.5], uBloomThreshold: 0.6 };
const BLOOM_UP_NEUTRAL = { uTexel: [0, 0] };
const TONEMAP_NEUTRAL = { uBloomStrength: 0, uCeiling: 2.0, uDither: 1 / 255, uLensStrength: 0 };
const HDR_TEXTURES = { uSource: 'scene', uScene: 'scene', uBloom: 'history', uNoise: 'noise', uLens: 'history' };

// The lens effects (core/viz/HdrPass, LensFx.hpp): the glare (lens_frag.glsl) from the bloom chain's
// quarter-size level, one frame per part with the others off; depth of field (dof_frag.glsl) over a
// scene target whose alpha (1 here, "far") is the Style's depth; and the 3D particle layer
// (particles3d_vert.glsl, particles3d_frag.glsl), an instanced draw of motion-stretched streaks.
const LENS_NEUTRAL = {
  uAspect: 1, uFlare: 0, uFlareGhosts: 5, uFlareHalo: 0.5, uFlareDispersion: 0.5,
  uRays: 0, uRaySource: [0.5, 0.5], uRayLength: 0.7, uRayDecay: 0.94, uRaySamples: 24,
  uStreaks: 0, uStreakShape: 0, uStreakSpokes: 6, uStreakLength: 0.6, uStreakSamples: 8,
};
const LENS_TEXTURES = { uLight: 'scene', uLightSoft: 'history' };
const LENS_EFFECTS = {
  lens_flare: { uFlare: 1 },
  lens_god_rays: { uRays: 1 },
  lens_streaks_star: { uStreaks: 1, uStreakShape: 0 },
  lens_streaks_zoom: { uStreaks: 1, uStreakShape: 1 },
  lens_streaks_anamorphic: { uStreaks: 1, uStreakShape: 2 },
};
const DOF_NEUTRAL = { uTexel: [0, 0], uAperture: 0.8, uFocus: 0.3, uMaxRadius: 4, uSamples: 24 };
const PARTICLES_NEUTRAL = {
  uPhase: 12.5, uTail: 0.8, uEmission: 0.9, uSize: 1.5, uKind: 0, uAspect: 1, uPixel: 0,
  uCamRot: [1, 0, 0, 0, 1, 0, 0, 0, 1], uTint: [1, 0.9, 0.8],
};
const PARTICLE_INSTANCES = 900;

// The tile-friendly frame (core/viz/TileFrame.hpp, #311): the outgoing Style of a transition drawn at
// half size and read by the composite's fade, slide, zoom and melt; the tonemap reading a smaller
// scene target directly when the composite is skipped; and the No-flash limiter's last pass
// upscaling a frame drawn below the output, shown as it is and damped.
const COMPOSITE_HALF_TEXTURES = { ...COMPOSITE_TEXTURES, uTexB: 'scene_half' };
const COMPOSITE_FROM_HALF = {
  fade_from_half: { uStyle: 1, uProgress: 0.5 },
  melt_from_half: { uStyle: 2, uProgress: 0.5 },
  slide_from_half: { uStyle: 3, uProgress: 0.5 },
  zoom_from_half: { uStyle: 4, uProgress: 0.5 },
  fade_from_half_under_layer: { uStyle: 1, uProgress: 0.5, uLayerPresence: 1 },
};
const HDR_HALF_TEXTURES = { ...HDR_TEXTURES, uScene: 'scene_half' };
const NOFLASH_MIX_NEUTRAL = { uAmount: 1 };
const NOFLASH_MIX_TEXTURES = { uShown: 'history', uNext: 'scene_half' };

function effectJobs(registry, size) {
  const vert = fs.readFileSync(path.join(SHADERS, 'fade_vert.glsl'), 'utf8');
  const jobs = [];
  const add = (pass, file, neutral, textures, effects) => {
    const frag = resolveIncludes(fs.readFileSync(path.join(SHADERS, file), 'utf8'), registry, `shaders/${file}`);
    const uniforms = parseUniforms(frag);
    for (const [name, values] of Object.entries(effects)) {
      const all = { ...neutral, ...values };
      if ('uTexel' in all) all.uTexel = [1 / size, 1 / size];
      jobs.push({ name: `${pass}_${name}`, vert, frag, uniforms: plan(uniforms, all, textures, size, size) });
    }
  };
  add('composite', 'composite_frag.glsl', COMPOSITE_NEUTRAL, COMPOSITE_TEXTURES, COMPOSITE_EFFECTS);
  add('shared', 'shared_fx_frag.glsl', SHARED_FX_NEUTRAL, SHARED_FX_TEXTURES, SHARED_FX_EFFECTS);
  add('hdr', 'bloom_down_frag.glsl', BLOOM_DOWN_NEUTRAL, HDR_TEXTURES, { bloom_prefilter: {}, bloom_down: { uPrefilter: 0 } });
  add('hdr', 'bloom_up_frag.glsl', BLOOM_UP_NEUTRAL, HDR_TEXTURES, { bloom_up: {} });
  add('hdr', 'tonemap_frag.glsl', TONEMAP_NEUTRAL, HDR_TEXTURES,
    { tonemap: {}, tonemap_bloom: { uBloomStrength: 0.5 }, tonemap_lens: { uBloomStrength: 0.5, uLensStrength: 1 } });
  add('hdr', 'lens_frag.glsl', LENS_NEUTRAL, LENS_TEXTURES, LENS_EFFECTS);
  add('lens', 'dof_frag.glsl', DOF_NEUTRAL, { uScene: 'scene' }, { depth_of_field: {} });
  // The particles bring their own vertex shader and draw instanced.
  const pvert = fs.readFileSync(path.join(SHADERS, 'particles3d_vert.glsl'), 'utf8');
  const pfrag = fs.readFileSync(path.join(SHADERS, 'particles3d_frag.glsl'), 'utf8');
  for (const [name, values] of Object.entries({ particles3d_streaks: { uKind: 0 }, particles3d_sparks: { uKind: 1, uTail: 0.08 } })) {
    const all = { ...PARTICLES_NEUTRAL, ...values, uPixel: 2 / size };
    const uniforms = plan([...parseUniforms(pvert), ...parseUniforms(pfrag)], all, {}, size, size);
    jobs.push({ name: `lens_${name}`, vert: pvert, frag: pfrag, uniforms, instances: PARTICLE_INSTANCES, vertices: 6 });
  }
  add('tile', 'composite_frag.glsl', COMPOSITE_NEUTRAL, COMPOSITE_HALF_TEXTURES, COMPOSITE_FROM_HALF);
  add('tile', 'tonemap_frag.glsl', TONEMAP_NEUTRAL, HDR_HALF_TEXTURES, { tonemap_upscale: {}, tonemap_upscale_bloom: { uBloomStrength: 0.5 } });
  add('tile', 'noflash_mix_frag.glsl', NOFLASH_MIX_NEUTRAL, NOFLASH_MIX_TEXTURES, { noflash_upscale: {}, noflash_upscale_damped: { uAmount: 0.5 } });
  return jobs;
}

// ---- driving the page ------------------------------------------------------

function withKeywords(src, defines) {
  if (!defines.length) return src;
  const nl = src.indexOf('\n', src.indexOf('#version'));
  return `${src.slice(0, nl + 1)}${defines.map((d) => `#define ${d}\n`).join('')}${src.slice(nl + 1)}`;
}

function parseArgs(argv) {
  const a = {
    size: 240, shaders: [], all: false, list: false, fluidDisplay: false, resize: false, effects: false, layers: false, out: null, time: null,
    glslang: false, glslangSelfTest: false, watch: false, push: false, pkg: DEBUG_PACKAGE,
  };
  for (let i = 0; i < argv.length; i++) {
    const v = argv[i];
    if (v === '--list') a.list = true;
    else if (v === '--all') a.all = true;
    else if (v === '--fluid-display') a.fluidDisplay = true;
    else if (v === '--resize') a.resize = true;
    else if (v === '--effects') a.effects = true;
    else if (v === '--layers') a.layers = true;
    else if (v === '--glslang') a.glslang = true;
    else if (v === '--glslang-self-test') a.glslangSelfTest = true;
    else if (v === '--watch') a.watch = true;
    else if (v === '--push') a.push = true;
    else if (v === '--package') a.pkg = argv[++i];
    else if (v === '--shader') a.shaders.push(argv[++i]);
    else if (v === '--size') a.size = Number(argv[++i]);
    else if (v === '--out') a.out = argv[++i];
    else if (v === '--time') a.time = Number(argv[++i]);
    else throw new Error(`unknown flag ${v}`);
  }
  return a;
}

function report(name, r, frag) {
  const status = r.error ? 'FAIL' : 'ok  ';
  const stats = r.error
    ? remapLog(r.error, frag)
    : `mean ${r.meanLuma.toFixed(3)} max ${r.maxLuma.toFixed(3)} black ${r.fracBlack.toFixed(3)} blown ${r.fracBlown.toFixed(3)}` +
      (r.glError ? ` glError ${r.glError}` : '') + (r.dropped.length ? ` dropped[${r.dropped.join(',')}]` : '');
  console.log(`${status} ${name.padEnd(28)} ${stats}`);
  return !r.error;
}

// ---- glslang -----------------------------------------------------------------
//
// glslangValidator reads the assembled source the app would compile (the stage from the .frag
// extension, the profile from `#version 300 es`) and fails on anything the GLSL ES 3.00
// specification does not allow, which ANGLE over SwiftShader may let through. CI installs it
// (glslang-tools); nothing of it ships.

const GLSLANG = process.env.GLSLANG_VALIDATOR || 'glslangValidator';

function glslangCheck(styles, ids, registry) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'glslcheck-'));
  let allOk = true;
  try {
    for (const id of ids) {
      const file = styles.get(id);
      const name = path.relative(path.dirname(STYLES), file).split(path.sep).join('/');
      let ok = false;
      let detail = '';
      try {
        const frag = resolveIncludes(fs.readFileSync(file, 'utf8'), registry, name);
        const tmp = path.join(dir, `${id}.frag`);
        fs.writeFileSync(tmp, frag);
        const run = spawnSync(GLSLANG, [tmp], { encoding: 'utf8' });
        if (run.error) throw new Error(`cannot run ${GLSLANG}: ${run.error.message}`);
        ok = run.status === 0;
        const log = `${run.stdout}${run.stderr}`.split(tmp).join(name).split('\n')
          .filter((line) => line.trim() && line.trim() !== name).join('\n');
        detail = remapLog(log, frag);
      } catch (e) {
        detail = e.message;
      }
      console.log(`${ok ? 'ok  ' : 'FAIL'} ${`${id} glslang`.padEnd(28)} ${ok ? '' : detail.split('\n').join('\n     ')}`.trimEnd());
      allOk = ok && allOk;
    }
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
  return allOk;
}

// Proves the glslang check still fails on what the GLSL ES 3.00 specification forbids, and still
// names the planted file and line: one clean shader must pass and each planted violation must fail.
const GLSLANG_PLANTED = {
  clean: { line: 0, body: 'out vec4 fragColor;\nvoid main() { fragColor = vec4(1.0); }' },
  'int to float': { line: 5, body: 'out vec4 fragColor;\nvoid main() {\n  float x = 1;\n  fragColor = vec4(x);\n}' },
  'ES 2 texture2D': { line: 6, body: 'uniform sampler2D uTex;\nout vec4 fragColor;\nvoid main() {\n  fragColor = texture2D(uTex, vec2(0.5));\n}' },
  'gl_FragColor': { line: 4, body: 'void main() {\n  gl_FragColor = vec4(1.0);\n}' },
};

function glslangSelfTest() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'glslcheck-self-'));
  let allOk = true;
  try {
    for (const [name, planted] of Object.entries(GLSLANG_PLANTED)) {
      const file = `styles/planted/${name.replace(/\W+/g, '_')}.glsl`;
      const frag = resolveIncludes(`#version 300 es\nprecision highp float;\n${planted.body}\n`, [], file);
      const tmp = path.join(dir, 'planted.frag');
      fs.writeFileSync(tmp, frag);
      const run = spawnSync(GLSLANG, [tmp], { encoding: 'utf8' });
      if (run.error) throw new Error(`cannot run ${GLSLANG}: ${run.error.message}`);
      const log = remapLog(`${run.stdout}${run.stderr}`, frag);
      const ok = planted.line === 0 ? run.status === 0 : run.status !== 0 && log.includes(`${file}:${planted.line}:`);
      console.log(`${ok ? 'ok  ' : 'FAIL'} glslang self-test: ${name}${ok ? '' : `\n${log}`}`);
      allOk = ok && allOk;
    }
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
  return allOk;
}

// ---- pushing to the debug app's Style lab ------------------------------------
//
// The debug build watches <external files>/stylelab/<id>/ (DebugStyleLab.kt) and reloads a Style
// whose manifest or shader there changed (core/viz/RendererStyleLab.cpp). The shell user can write
// that folder, so a push needs no permission on the app's side.

const DEBUG_PACKAGE = 'dev.geode.debug';

function adb(args) {
  const run = spawnSync('adb', args, { encoding: 'utf8' });
  if (run.error) throw new Error(`cannot run adb: ${run.error.message}`);
  if (run.status !== 0) throw new Error(`adb ${args.join(' ')}: ${(run.stderr || run.stdout).trim()}`);
}

function pushStyles(ids, pkg) {
  const lab = `/sdcard/Android/data/${pkg}/files/stylelab`;
  for (const id of ids) {
    const folder = path.join(STYLES, id);
    const remote = `${lab}/${id}`;
    adb(['shell', 'mkdir', '-p', remote]);
    for (const entry of fs.readdirSync(folder)) adb(['push', path.join(folder, entry), `${remote}/${entry}`]);
    console.log(`push ${id.padEnd(28)} ${remote}`);
  }
}

// ---- one run -----------------------------------------------------------------

function buildJobs(args, registry, styles, renderIds) {
  const jobs = [];
  const packs = shaderPacks();
  const quadVert = fs.readFileSync(path.join(SHADERS, 'quad_vert.glsl'), 'utf8');
  for (const id of renderIds) {
    const file = styles.get(id);
    const name = path.relative(path.dirname(STYLES), file).split(path.sep).join('/');
    const frag = resolveIncludes(fs.readFileSync(file, 'utf8'), registry, name);
    const values = { ...SHADER_VALUES };
    if (args.time !== null) values.uTime = args.time;
    const job = {
      name: id, vert: quadVert, frag, inputs: packs.get(id).inputs,
      uniforms: plan(parseUniforms(frag), values, SHADER_TEXTURES, args.size, args.size),
    };
    if (args.resize) {
      job.name = `${id} resized`;
      job.resizeWidth = Math.max(1, Math.round(args.size * RESIZE_SCALE));
      job.resizeHeight = job.resizeWidth;
      job.keepFraction = KEEP_FRACTION;
    }
    jobs.push(job);
  }
  if (args.fluidDisplay) {
    const vert = fs.readFileSync(path.join(SHADERS, 'fluid_base_vert.glsl'), 'utf8');
    const src = fs.readFileSync(path.join(SHADERS, 'fluid_display_frag.glsl'), 'utf8');
    const looks = fluidLookCount();
    for (let look = 0; look < looks; look++) {
      for (let flags = 0; flags < 8; flags++) {
        const defines = [`LOOK ${look}`];
        if (flags & 1) defines.push('SHADING');
        if (flags & 2) defines.push('BLOOM');
        if (flags & 4) defines.push('SUNRAYS');
        const frag = withKeywords(src, defines);
        const vertUniforms = parseUniforms(vert);
        jobs.push({
          name: `fluid_display L${look} f${flags}`,
          vert,
          frag,
          uniforms: plan([...vertUniforms, ...parseUniforms(frag)], FLUID_VALUES, FLUID_TEXTURES, args.size, args.size),
        });
      }
    }
  }
  if (args.effects) jobs.push(...effectJobs(registry, args.size));
  if (args.layers) jobs.push(...layerJobs(registry, args.size));
  return jobs;
}

// The selected Style ids, each checked against the packs as they are now.
function selectedIds(args, styles) {
  const ids = args.all ? [...styles.keys()] : args.shaders;
  for (const id of ids) {
    const file = styles.get(id);
    if (!file || !fs.existsSync(file)) throw new Error(`no such fragment style: ${id}`);
  }
  return ids;
}

async function runOnce(args, getPage) {
  const registry = parseIncludeRegistry();
  const styles = shaderStyles();
  const ids = selectedIds(args, styles);
  let allOk = true;
  if (args.glslang) allOk = glslangCheck(styles, ids, registry) && allOk;
  const jobs = buildJobs(args, registry, styles, args.glslang ? [] : ids);
  if (jobs.length) {
    const page = await getPage();
    for (const job of jobs) {
      const r = await page.call(job.resizeWidth ? '__renderResized' : '__render', { ...job, width: args.size, height: args.size });
      const undeclared = r.error ? [] : undeclaredInputs(job, r);
      if (undeclared.length) r.error = `reads shared input(s) ${undeclared.join(', ')} its manifest's "inputs" does not declare`;
      allOk = report(job.name, r, job.frag) && allOk;
      if (args.out && r.png) {
        const dest = jobs.length === 1 ? args.out : path.join(args.out, `${job.name.replace(/[^\w]+/g, '_')}.png`);
        fs.mkdirSync(path.dirname(dest), { recursive: true });
        fs.writeFileSync(dest, Buffer.from(r.png, 'base64'));
      }
    }
    for (const line of page.drainConsole()) console.log(`  [page] ${line}`);
  }
  if (args.push && allOk) pushStyles(ids, args.pkg);
  return allOk;
}

// Resolves on the next change under the packs or the shared shaders, after edits settle.
function nextChange() {
  return new Promise((resolve) => {
    const watchers = [];
    let timer = null;
    const settle = () => {
      clearTimeout(timer);
      timer = setTimeout(() => {
        for (const w of watchers) w.close();
        resolve();
      }, 150);
    };
    for (const dir of [STYLES, SHADERS]) watchers.push(fs.watch(dir, { recursive: true }, settle));
  });
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.list) {
    console.log([...shaderStyles().keys()].join('\n'));
    return;
  }
  if (args.glslangSelfTest) {
    process.exitCode = glslangSelfTest() ? 0 : 1;
    return;
  }
  if (!args.all && !args.shaders.length && !args.fluidDisplay && !args.effects && !args.layers) {
    throw new Error('nothing to do: --shader <id>, --all, --fluid-display, --effects or --layers');
  }
  let browser = null;
  let page = null;
  const getPage = async () => {
    if (!page) {
      browser = await launch();
      page = await browser.openPage(pathToFileURL(path.join(HERE, 'page.html')).href);
    }
    return page;
  };
  try {
    if (!args.watch) {
      const ok = await runOnce(args, getPage);
      process.exitCode = ok ? 0 : 1;
      return;
    }
    for (;;) {
      try {
        const ok = await runOnce(args, getPage);
        console.log(ok ? '-- all passed; watching for changes' : '-- failures above; watching for changes');
      } catch (e) {
        console.log(`-- ${e.message}; watching for changes`);
      }
      await nextChange();
      console.log('');
    }
  } finally {
    if (browser) await browser.close();
  }
}

main()
  .then(() => process.exit(process.exitCode ?? 0))
  .catch((e) => {
    console.error(e.message);
    process.exit(2);
  });
