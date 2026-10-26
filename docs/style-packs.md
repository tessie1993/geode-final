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
  "extras": [ { "name": "density", "min": 0, "max": 2, "default": 1 } ],
  "macros": [ { "name": "energy", "links": [ { "target": "density", "amount": 0.5 } ] } ],
  "reactions": [ { "signal": "bass", "target": "density", "amount": 0.4, "riseMs": 20, "fallMs": 300, "curve": "smooth" } ]
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
| `extras` | no | The Style's own controls, declared generically: `name`, `min`, `max`, `default`. One name is one control with one range on every Style that has it, which CI checks across packs. |
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

No Style ships Extras, Macros or Reactions yet; their shapes are fixed here so the Customization work
(#39, #40, #54) fills them in as data.

## Adding a Style

1. Make `styles/<id>/` with `manifest.json` (and `shader.glsl` for a shader Style).
2. Add the id to `index.json` where it should appear in the picker.
3. Push with `[record-goldens]` in the commit message to record its golden frame, then push again
   (`docs/ci.md`).

Removing a Style is the reverse. Saved presets, templates and Studio projects that name a removed
Style load the default Style instead (`SceneCapabilities.storedOrDefault`); built-in presets and
suggestions for it drop out. Removing the default Style also means changing `SceneIds.DEFAULT`.

A pack that cannot be read is left out on the device, with a typed `PackError` logged, and every other
Style keeps working; CI never lets one ship.
