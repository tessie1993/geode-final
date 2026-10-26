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
| `shader` | shader Styles only | The fragment shader, a file in the Style's own folder. Its `//#include` lines resolve against `shaders/lib_*.glsl`. |
| `renderScale` | no | 0.5–1, default 1: the Style draws at this fraction of the live render size (ADR-0005, "Visual buffers follow load"). |
| `detail` | no | What Detail drives beyond the shared quality ladder: `"none"` (default) or `"march"` (the Style breaks its raymarch on the Detail step budget). |
| `parameters` | no | Values for the family's parameters, each within the family's range; the rest take the family default. |
| `extras` | no | The Style's own controls (Extras): `name`, `min`, `max`, `default`. The name is one of the Extras (`ExtraKind`: `scale`, `count`, … `center_y`), and one name is one control with one range on every Style: a multiplier 0–2 of the Style's own amount, except the centres (offsets −1–1) and the variant (a whole number 0–9). The default is the neutral value (1, or 0), so the Style draws exactly as its pack tunes it until it is Customized. A shader Style reads every Extra it declares (`//#include lib_style`; `uX<Name>`, a rate Extra through its clock `uX<Name>Time`, or for `flight` lib_dmt's `uFlight`); every other family maps them onto its own parameters (`geode/viz/StyleExtras.hpp`). CI checks all of it. |
| `macros` | no | Default Macros, at most 6 (the shared set): `name` and `links`, each a `target` and an `amount` in −1…1. |
| `reactions` | no | Default Reactions: a Sound `signal` (0–1) moving a `target` by an `amount` in −1…1, with `riseMs`, `fallMs` (≥ 0) and a `curve` (`linear`, `ease_in`, `ease_out`, `smooth`). |
| `effects` | no | The Style's costly effects the hot thermal tiers may switch off, at most 8 lower-case names. The shader reads effect *i* (its position in the list) with `fxOn(i)` from `lib_scene_uniforms`. |
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

## The music in a Style

The analysis hears far more than the bands and the beat, and every family draws from the same reading
of it: one feature bus (`geode/viz/FeatureBus.hpp`), stepped once per frame by the renderer and handed
to every scene with the Camera. Nothing on it is raw. Slow values glide, hits rise over an attack and
decay, timbre is latched at phrase rate (every four trusted bars, at a section boundary, or every 8 s
without either), and the tempo-locked values fall away while the tracker is unsure, so an uncertain
tempo leaves the picture to the energy instead of making it jitter. Silence settles every value and
holds it still, and the same frames give the same values, so a Render repeats.

A shader Style reads it with `//#include lib_scene_features` (the bar's phase is the bar shared input,
`uBarPhase`). The other families read it through `featuremap` in the same header.

| Scale | Feature | Uniform | Drives | Families |
|---|---|---|---|---|
| Structure | novelty | `uNovelty` | churn, branching | Mycelium widens its sensors |
| | section | `uSection` (a phase: read it through a hue or a sine), `uSectionHit` | palette, camera travel, a twist | Water swells every ripple |
| | build-up | `uBuildup` | tightening, force | Curl Flow pushes harder |
| | drop | `uDrop` (a glow over seconds), `uDropHit` | opening out, a global push | Fluid throws the field, Acid clears its loop, MilkDrop's post pass leans in |
| | arrival | `uArrival`, `uArrivalHit` | a turn, a ring sent through | |
| | passage loudness | `uMacro` | density, thickness | |
| Rhythm | downbeat | `uDownbeat` (trusted only), `uBeatInBar` | bar-scale symmetry and accent | Water rings from the centre, Cymatics climbs its modes over the bar, Life seeds on the bar's clock |
| | kick, snare, hat | `uKick`, `uSnare`, `uHat` | each its own layer: low body, mid crack, high sparkle | Fluid's emitters take the kick |
| | trust | `uPulseConfidence`, `uTempoStability`, `uDownbeatConfidence` | how far a tempo-locked use may go | all |
| Timbre | centroid, harmonicity, flux, onset | `uCentroid`, `uHarmonicity`, `uFlux`, `uOnset` | material: rough or smooth, metal or organic, bloom or crisp | Fluid's tint, Life's rule, Acid's feedback |
| Space | width, pan, correlation | `uStereoWidth`, `uStereoPan`, `uStereoCorrelation` | true left/right asymmetry, duplication | stereo-meaningful Styles only |

What not to do:

- **No flicker per frame.** Structure changes the picture over seconds; a hit moves, swells, ripples or
  shifts colour, and never brightens the whole frame (the No-flash rule).
- **Not everything on everything.** A Style picks the few features that mean something for its picture.
  One shared bump for every drum, or every feature on one value, reads as noise, not music.
- **Never multiply a running clock.** A feature scales a width, an offset or a mix, never the factor on
  a phase that has run for minutes, where the smallest change slides the whole picture.
- **Fade tempo-locked uses by the trust**, so a tracker that is unsure falls back to the energy.

The engine uploads every feature uniform to every shader Style; one it never reads costs nothing. A host
test holds the uploaded list to the library and fails when no shipped Style reads one of them, and the
pack check fails a Style that includes the library and reads none of it.

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
