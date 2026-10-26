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
| `tiers` | no | What each hot thermal tier lets the Style spend (#286): `eased`, `reduced` and `minimal`, each optionally with `steps` (0.25–1, the share of the Detail step budget it marches) and `off` (names from `effects`). A tier left out keeps the share the default ladder gives it (1, 0.85, 0.7, 0.55); a hotter tier never gets more steps, nor any effect a cooler one switched off. |

The packs are the one source of every Style's Extras. The engine reads them from the packs, and the
Kotlin side lists them in `PackExtras.kt` (`:feature:customization:core`), generated by
`python3 tools/ci/check_style_packs.py --write-kotlin` and held to the packs by CI. No Style ships Macros or
Reactions yet; the default Reactions are still in `BuiltInStyles.kt`.

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
