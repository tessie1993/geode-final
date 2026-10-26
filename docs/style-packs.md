# Style packs

Every Style is a data pack (ADR-0004, "Styles are data packs"). Adding a Style, or removing one, is a
change to the packs alone: no Kotlin or C++ names a Style except the default Style, chosen in one place
(`SceneIds.DEFAULT`).

The packs live once, in `engine/scenes/src/main/stylepacks/styles/`, and ship twice: as APK assets,
which the native renderer reads (`core/viz/StylePacks.cpp`, `SceneRegistry`), and as Java resources,
which the Kotlin side reads (`StylePacks.kt`). Both sides build one registry from the same files.
CI validates every pack (`tools/ci/check_style_packs.py`, in the Visual golden frames job), reads them
in the Kotlin and C++ unit tests, and renders every shader Style against its golden frame
(`tools/glslcheck`).

```
styles/
  index.json            the Styles, in picker order
  families.json         each family's parameters: type, range, default
  <id>/
    manifest.json       the Style
    shader.glsl         its fragment shader (shader Styles only)
```

## `index.json`

```json
{ "version": 1, "styles": ["life_orbium", "…", "kundt_tube"] }
```

Every folder is listed exactly once, and every listed id has a folder. The order is the order the
pickers show. (Native code cannot list an asset folder, so the index is what both sides read.)

## `families.json`

A family is a renderer, and so code: `shader`, `life`, `mycelium`, `acid`, `milkdrop`, `fluid`,
`curl_flow`, `water` and `cymatics` (`SceneKind` in Kotlin, `geode/viz/StyleProfiles.hpp` in C++). Each
declares the parameters its renderer reads from the Style it draws:

```json
{ "name": "cymatics", "parameters": [
  { "key": "shaderStyle", "type": "int", "min": 0, "max": 10 },
  { "key": "modeCap", "type": "int", "min": 1, "max": 8, "default": 8 }
] }
```

A parameter without a `default` must be set by every Style of the family. The C++ profiles read exactly
these keys; a host test fails if a key and a profile field drift apart.

A family may also list, under `ignores`, the Shared core properties its renderer never reads, named as
presets name them: MilkDrop paces itself and takes raw sound, so it has
`"ignores": ["SPEED", "AUDIO_DRIVE", …]`. Customize greys those controls out on the family's Styles,
and any Macro that moves only them (#365). Only the Kotlin side reads it; the Customization tests
check every name is a real property.

## `manifest.json`

```json
{
  "version": 1,
  "id": "kifs",
  "label": "Kifs",
  "family": "shader",
  "shader": "shader.glsl",
  "renderScale": 0.75,
  "detail": "march",
  "parameters": {},
  "extras": [ { "name": "scale", "min": 0, "max": 2, "default": 1 } ],
  "macros": [ { "name": "energy", "links": [ { "target": "scale", "amount": 0.5 } ] } ],
  "reactions": [ { "signal": "bass", "target": "scale", "amount": 0.4, "riseMs": 20, "fallMs": 300, "curve": "smooth" } ]
}
```

| Key | Required | Meaning |
|---|---|---|
| `version` | yes | The pack format, `1`. |
| `id` | yes | The Style id; the folder's name. Saved presets and settings store it. |
| `label` | yes | The name the App UI shows. |
| `family` | yes | The renderer that draws it, declared in `families.json`. |
| `shader` | shader Styles only | The fragment shader, a file in the Style's own folder. Its `//#include` lines resolve against `shaders/lib_*.glsl`. The sound reaches it through `uAudioTex` (`lib_scene_uniforms`, `geode/viz/AudioInputs.hpp`): `aband(x)` the spectrum, 30 Hz–16 kHz on a log scale and one level scale, `apeak(x)` its peak hold, `awave(x)` a steady oscilloscope of the mid, and `awaveL(x)`/`awaveR(x)` its left and right channels. |
| `renderScale` | no | 0.5–1, default 1: the Style draws at this fraction of the live render size (ADR-0005, "Visual buffers follow load"). |
| `detail` | no | What Detail drives beyond the shared quality ladder: `"none"` (default) or `"march"` (the Style breaks its raymarch on the Detail step budget). |
| `parameters` | no | Values for the family's parameters, each within the family's range; the rest take the family default. |
| `extras` | no | The Style's own controls (Extras): `name`, `min`, `max`, `default`. The name is one of the Extras (`ExtraKind`: `scale`, `count`, … `center_y`), and one name is one control with one range on every Style: a multiplier 0–2 of the Style's own amount, except the centres (offsets −1–1) and the variant (a whole number 0–9). The default is the neutral value (1, or 0), so the Style draws exactly as its pack tunes it until it is Customized. A shader Style reads every Extra it declares (`//#include lib_style`; `uX<Name>`, a rate Extra through its clock `uX<Name>Time`, or for `flight` lib_dmt's `uFlight`); every other family maps them onto its own parameters (`geode/viz/StyleExtras.hpp`). CI checks all of it. |
| `macros` | no | Default Macros, at most 6 (the shared set): `name` and `links`, each a `target` and an `amount` in −1…1. |
| `reactions` | no | Default Reactions: a Sound `signal` (0–1) moving a `target` by an `amount` in −1…1, with `riseMs`, `fallMs` (≥ 0) and a `curve` (`linear`, `ease_in`, `ease_out`, `smooth`). |
| `effects` | no | The Style's costly effects the hot thermal tiers may switch off, at most 8 lower-case names. The shader reads effect *i* (its position in the list) with `fxOn(i)` from `lib_scene_uniforms`. |
| `inputs` | no | The shared Style inputs the shader reads ("Shared inputs" below), each named once: `noise_lq`, `noise_mq`, `noise_hq`, `noise_vol`, `blue_noise`, `blur1`, `blur2`, `blur3`, `previous_frame`, `clocks`, `bar`. Shader Styles only; the shader includes `lib_inputs` and reads every input it declares and no other (CI checks both, the render check on the linked program). |
| `tiers` | no | What each hot thermal tier lets the Style spend (#286): `eased`, `reduced` and `minimal`, each optionally with `steps` (0.25–1, the share of the Detail step budget it marches) and `off` (names from `effects`). A tier left out keeps the share the default ladder gives it (1, 0.85, 0.7, 0.55); a hotter tier never gets more steps, nor any effect a cooler one switched off. |
| `lens` | no | The shared lens effects (#313, `geode/viz/LensFx.hpp`), each part optional: `threshold` (0.5–1, the light the glare gathers from), `flare` (`amount`, `ghosts` 1–8, `halo`, `dispersion`), `godRays` (`amount`, `source` [x, y] in 0–1 from the bottom left, `length` 0.1–1, `decay` 0.8–0.99), `streaks` (`amount`, `shape` `star`, `zoom` or `anamorphic`, `spokes` 2–8, `length` 0.1–1) and `depthOfField` (`aperture`, `focus` 0.05–0.95). Amounts, halo, dispersion and aperture are 0–1, and each part needs its `amount` (`aperture` for depth of field) above 0. Depth of field is for shader Styles only: the shader writes its depth into alpha as t / (t + the subject's distance), 1 for nothing hit. |
| `particles3d` | no | The shared 3D particle layer (#314): `amount` (above 0, up to 1), `kind` (`streaks` or `sparks`), and multipliers `count` (0.25–2), `speed` (0.1–3) and `size` (0.3–3), and a `tint` [r, g, b] in 0–2. |

Every lens effect and the particles are light: they run inside the HDR frame graph, before the
No-flash limiter, follow the music no faster than a swell, fade in and out with the Style, and the
hot thermal tiers thin them (fewer particles) or switch them off (the glare and depth of field go
with the bloom when a tier drops the optional passes).

The packs are the one source of every Style's Extras. The engine reads them from the packs, and the
Kotlin side lists them in `PackExtras.kt` (`:feature:customization:core`), generated by
`python3 tools/ci/check_style_packs.py --write-kotlin` and held to the packs by CI. No Style ships Macros or
Reactions yet; the default Reactions are still in `BuiltInStyles.kt`.

## Shared inputs

What MilkDrop's shared include gives every preset, a shader Style gets by declaring it under `inputs`
(`geode/viz/StyleInputs.hpp`; `//#include lib_inputs`). The engine makes and binds only what the drawing
Style declares, so an input no Style declares is never made and one a Style does not declare costs it
nothing.

| Input | Reads as | What it is |
|---|---|---|
| `noise_lq`, `noise_mq`, `noise_hq` | `uNoiseLq`, `uNoiseMq`, `uNoiseHq`; `fbmTex()` | 256×256 RGBA noise that tiles, four independent channels: white noise, then smooth value noise with a lattice point every 4 and every 8 texels. |
| `noise_vol` | `uNoiseVol`; `vnoiseVol()`, `fbmVol()` | A 32³ RGBA volume of white noise. `vnoiseVol` and `fbmVol` are `lib_sdf3`'s `vnoise3` and `fbm3` at one fetch per octave. |
| `blue_noise` | `uBlueNoise` | The 64×64 blue-noise mask the tonemap dithers with. |
| `previous_frame` | `uPrevFrame` | The last frame this Style drew, at its size, kept across a resize. |
| `blur1`, `blur2`, `blur3` | `uBlur1`, `uBlur2`, `uBlur3` | That frame blurred at 1/4, 1/8 and 1/16 of its size by the bloom chain's own down filter (`HdrPass::downsample`), only as deep as the deepest one declared. |
| `clocks` | `uFrame`, `uFrameDt`, `uVisitRand`, `uStyleAge` | Frames drawn this visit, the frame's seconds, four random values drawn once per visit, and seconds into the visit. A visit starts when the Style starts to be drawn, as Layer 1 or Layer 2. |
| `bar` | `uBarPhase`, `uBarConfidence` | Where the music is in its bar (0–1, four beats), a clock locked to the heard downbeat that never runs backwards or jumps, and how sure it is. |

The noise is drawn from the Render seed, and so is `uVisitRand` in a Render, so a Render gives the same
frames every time. The previous frame and the blur levels are the Style's own picture: what feeds back
must stay below 1, or the loop grows into a flash. A Style's kept frames start black at each visit.

## Adding a Style

1. Make `styles/<id>/` with `manifest.json` (and `shader.glsl` for a shader Style).
2. Add the id to `index.json` where it should appear in the picker.
3. If it declares Extras, run `python3 tools/ci/check_style_packs.py --write-kotlin` and commit `PackExtras.kt`.
4. Push with `[record-goldens]` in the commit message to record its golden frame, then push again
   (`docs/ci.md`).

Removing a Style is the reverse. Saved presets, templates and Studio projects that name a removed
Style load the default Style instead (`SceneCapabilities.storedOrDefault`); built-in presets and
suggestions for it drop out. Removing the default Style also means changing `SceneIds.DEFAULT`.

A pack that cannot be read is left out on the device, with a typed `PackError` logged, and every other
Style keeps working; CI never lets one ship.

## Authoring loop

Shaders are assembled with `#line` directives (`core/util/ShaderAssembly.cpp`): each file is its own
source string and the assembled source ends with a comment naming them, so a compile error on a phone,
in the render check or in glslang names the file and line the author wrote
(`styles/kifs/shader.glsl:12: 'x' : undeclared identifier`), not a line of the assembled source.

A debug build reloads a Style without a restart from its **Style lab**, a folder on the device:
`/sdcard/Android/data/dev.geode.debug/files/stylelab/<id>/`, holding the Style's `manifest.json`, its
shader, or both. The renderer looks at it four times a second and reloads a Style whose files there
changed: a shader Style compiles the new shader at its next frame and keeps drawing the last one that
compiled if it fails (the error reaches logcat and the app's log), and a new manifest replaces the
Style's pack (render scale, Detail, parameters, Extras' ranges). Only Styles that ship can be reloaded,
the App UI keeps the label and controls it started with, and pushed packs stay in force, across
restarts too, until the folder is removed and the app restarted. A release build has none of this: the
native hot-reload path is compiled only with `GEODE_STYLE_HOT_RELOAD`, which the debug build type alone
sets, and the Kotlin side lives in `:engine:scenes`' debug source set.

`tools/glslcheck/check.mjs` drives the loop from the repository:

```sh
node tools/glslcheck/check.mjs --shader kifs --glslang --watch --push
```

Every save of the pack or of a shared `shaders/lib_*.glsl` checks the Style with glslang and, when it
passes, pushes its folder to the debug app with `adb`, which shows it within about a second. Drop
`--glslang` to render a frame with ANGLE instead; `--package` names another application id.
