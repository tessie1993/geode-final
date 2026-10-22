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
// the same way it fails on the phone.
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
//
// --resize draws one frame into a scene target, resizes the target to RESIZE_SCALE of its size the
// way Framebuffer::resizePreserving does (a linear blit into the new target, never a clear), and
// writes what the resized target holds: the picture the renderer's next frame starts from. It fails
// when that picture is darker than KEEP_FRACTION of the one before the resize.

import fs from 'node:fs';
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

function resolveIncludes(source, registry) {
  const out = [];
  for (const rawLine of source.split('\n')) {
    const line = rawLine.replace(/\r$/, '');
    const m = line.match(INCLUDE_LINE);
    if (!m) {
      out.push(rawLine);
      continue;
    }
    if (!registry.includes(m[1])) throw new Error(`unknown shader include '${m[1]}'`);
    const file = path.join(SHADERS, `${m[1]}.glsl`);
    if (!fs.existsSync(file)) throw new Error(`missing shader include '${m[1]}'`);
    out.push(fs.readFileSync(file, 'utf8'));
  }
  return out.join('\n');
}

// id -> shader file of every shader Style, in the packs' index order.
function shaderStyles() {
  const index = JSON.parse(fs.readFileSync(path.join(STYLES, 'index.json'), 'utf8'));
  const out = new Map();
  for (const id of index.styles) {
    const manifest = JSON.parse(fs.readFileSync(path.join(STYLES, id, 'manifest.json'), 'utf8'));
    if (manifest.family === 'shader') out.set(id, path.join(STYLES, id, manifest.shader));
  }
  return out;
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
  uSteps: 96,
  uPalLutMix: 0, uPalLutRow: 0.5,
  uBassSmooth: 0.5, uMidSmooth: 0.4, uTrebleSmooth: 0.3, uEnergySmooth: 0.45, uSwell: 0.5, uSpike: 0.15,
  uMoveDir: [0.8, 0.6], uSpawnSeed: 0.37, uSpawnAge: 4.0, uFormPhase: 0.62, uFlowPhase: 12.5,
  uFlowStrength: 0,
};

const SHADER_TEXTURES = { uAudioTex: 'audio', uPalLut: 'palette', uFlow: 'flow' };

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
    if (u.type === 'sampler2D') {
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
  uDither: 0, uRatio: 1, uProgress: 1, uStyle: 0, uLayerMix: 0.5, uBlendMode: 0, uTime: 12.5, uBeat: 0.4,
  uChroma: 0, uVignette: 0, uScanline: 0, uGrain: 0, uGlitch: 0, uFisheye: 0,
  uGateA: [1, 1, 1, 1], uGateB: [1, 1, 1, 1],
  uPostWarp: 0, uPostRipple: 0, uPostSymmetry: 6, uPostKaleido: 0, uPostPixelate: 0, uPostTile: 1, uPostTwist: 0,
  uPostMirror: 0, uPostBloom: 0, uPostPosterize: 0, uPostInvert: 0, uPostDriftX: 0, uPostDriftY: 0, uPostSway: 0,
  uPostShake: 0, uPostTemp: 0, uPostSolarize: 0, uPostZoom: 1, uPostRotation: 0, uPostSat: 1, uPostBright: 1,
  uPostContrast: 1, uPostGamma: 1, uPostHue: 0, uPostPulse: 0,
  uFlowStrength: 0, uRippleTexel: [1 / 64, 1 / 64], uRippleStrength: 0, uRippleSpecular: 0,
  uUnderlayBlend: 0, uUnderlayAmount: 0,
};
const COMPOSITE_TEXTURES = { uTexA: 'scene', uTexB: 'scene', uNoise: 'noise', uFlow: 'flow', uRipple: 'flow', uUnderlay: 'white' };

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
  temperature: { uPostTemp: 0.8 },
  solarize: { uPostSolarize: 1 },
  invert: { uPostInvert: 1 },
};

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

function effectJobs(registry, size) {
  const vert = fs.readFileSync(path.join(SHADERS, 'fade_vert.glsl'), 'utf8');
  const jobs = [];
  const add = (pass, file, neutral, textures, effects) => {
    const frag = resolveIncludes(fs.readFileSync(path.join(SHADERS, file), 'utf8'), registry);
    const uniforms = parseUniforms(frag);
    for (const [name, values] of Object.entries(effects)) {
      const all = { ...neutral, ...values };
      if (pass === 'shared') all.uTexel = [1 / size, 1 / size];
      jobs.push({ name: `${pass}_${name}`, vert, frag, uniforms: plan(uniforms, all, textures, size, size) });
    }
  };
  add('composite', 'composite_frag.glsl', COMPOSITE_NEUTRAL, COMPOSITE_TEXTURES, COMPOSITE_EFFECTS);
  add('shared', 'shared_fx_frag.glsl', SHARED_FX_NEUTRAL, SHARED_FX_TEXTURES, SHARED_FX_EFFECTS);
  return jobs;
}

// ---- driving the page ------------------------------------------------------

function withKeywords(src, defines) {
  if (!defines.length) return src;
  const nl = src.indexOf('\n', src.indexOf('#version'));
  return `${src.slice(0, nl + 1)}${defines.map((d) => `#define ${d}\n`).join('')}${src.slice(nl + 1)}`;
}

function parseArgs(argv) {
  const a = { size: 240, shaders: [], all: false, list: false, fluidDisplay: false, resize: false, effects: false, out: null, time: null };
  for (let i = 0; i < argv.length; i++) {
    const v = argv[i];
    if (v === '--list') a.list = true;
    else if (v === '--all') a.all = true;
    else if (v === '--fluid-display') a.fluidDisplay = true;
    else if (v === '--resize') a.resize = true;
    else if (v === '--effects') a.effects = true;
    else if (v === '--shader') a.shaders.push(argv[++i]);
    else if (v === '--size') a.size = Number(argv[++i]);
    else if (v === '--out') a.out = argv[++i];
    else if (v === '--time') a.time = Number(argv[++i]);
    else throw new Error(`unknown flag ${v}`);
  }
  return a;
}

function report(name, r) {
  const status = r.error ? 'FAIL' : 'ok  ';
  const stats = r.error
    ? r.error
    : `mean ${r.meanLuma.toFixed(3)} max ${r.maxLuma.toFixed(3)} black ${r.fracBlack.toFixed(3)} blown ${r.fracBlown.toFixed(3)}` +
      (r.glError ? ` glError ${r.glError}` : '') + (r.dropped.length ? ` dropped[${r.dropped.join(',')}]` : '');
  console.log(`${status} ${name.padEnd(28)} ${stats}`);
  return !r.error;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const registry = parseIncludeRegistry();
  const styles = shaderStyles();
  const ids = [...styles.keys()];
  if (args.list) {
    console.log(ids.join('\n'));
    return;
  }
  const jobs = [];
  const quadVert = fs.readFileSync(path.join(SHADERS, 'quad_vert.glsl'), 'utf8');
  const targets = args.all ? ids : args.shaders;
  for (const id of targets) {
    const file = styles.get(id);
    if (!file || !fs.existsSync(file)) throw new Error(`no such fragment style: ${id}`);
    const frag = resolveIncludes(fs.readFileSync(file, 'utf8'), registry);
    const values = { ...SHADER_VALUES };
    if (args.time !== null) values.uTime = args.time;
    const job = { name: id, vert: quadVert, frag, uniforms: plan(parseUniforms(frag), values, SHADER_TEXTURES, args.size, args.size) };
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
  if (!jobs.length) throw new Error('nothing to do: --shader <id>, --all, --fluid-display or --effects');

  const browser = await launch();
  let allOk = true;
  try {
    const page = await browser.openPage(pathToFileURL(path.join(HERE, 'page.html')).href);
    for (const job of jobs) {
      const r = await page.call(job.resizeWidth ? '__renderResized' : '__render', { ...job, width: args.size, height: args.size });
      allOk = report(job.name, r) && allOk;
      if (args.out && r.png) {
        const dest = jobs.length === 1 ? args.out : path.join(args.out, `${job.name.replace(/[^\w]+/g, '_')}.png`);
        fs.mkdirSync(path.dirname(dest), { recursive: true });
        fs.writeFileSync(dest, Buffer.from(r.png, 'base64'));
      }
    }
    for (const line of page.drainConsole()) console.log(`  [page] ${line}`);
  } finally {
    await browser.close();
  }
  process.exit(allOk ? 0 : 1);
}

main().catch((e) => {
  console.error(e.message);
  process.exit(2);
});
