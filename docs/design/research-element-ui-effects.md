# Element UI effects in Compose: research (Water first)

How to build the App UI effects and components for the Element skins in Jetpack Compose, starting with Water. The App UI never reacts to audio (`docs/design/app-ui-design-system.md`, line 3). The owner's #102 rule applies everywhere: **no glass, no live blur, no glassmorphism** (PR #117).

Researched on 2026-10-22 against `main` at `3dbd3c8` (Graphify index `ec9c6d1`), PR #139 (`claude/water-element`) and PR #117 (`no-glass-check`). Sources are numbered **[S#]** and listed at the end. Repository paths are relative to the repo root.

---

## 0. Findings that shape everything else

1. **minSdk is 31, not 26** (`app/build.gradle.kts:47`, `build-logic/src/main/kotlin/geode.android-library.gradle.kts:10`). So "below API 33" means only API 31–32. `RenderEffect` exists from API 31 [S5], but `RuntimeShader` (AGSL) needs API 33 [S4][S1]. Section 0 of `docs/design/research-high-end-references.md` still says minSdk 26, which is out of date.
2. **The existing design docs contradict #102.** The "Decided" section of `docs/design/app-ui-design-system.md` (lines 45–51) and sections 3.1–3.2 of `docs/design/research-high-end-references.md` call for frosted glass, Haze, live blur and a `createRuntimeShaderEffect` ripple that **displaces sampled content**. PR #117 bans all of these: "no live background blur or refraction behind App UI surfaces, no translucent glass orbs" [S20]. Those docs need a superseding note.
3. **PR #139 does not compile.** `app/src/main/java/dev/geode/ui/LibraryScreen.kt:72` on `claude/water-element` reads `import dev.geode.design.theme.accentTextColor dev.geode.design.theme.stoneScrollWake` (two imports on one line). Its CI was still pending when this was checked.
4. **Reduced motion only half works.** Compose already respects the system "Remove animations" setting for `Animatable` and `InfiniteTransition`: the recomposer reads `Settings.Global.ANIMATOR_DURATION_SCALE` [S15], and an infinite transition with scale 0 calls `skipToEnd()` [S16]. The gaps are elsewhere:
   - The app's own `gui.reducedMotion` toggle never reaches `:design` on `main`. `stonePress` and `StoneSurfaceArt` default to `false` (`StoneSurface.kt:27,76`), and `CrystalBackground` suppresses its parameter as unused (`Crystal.kt:200`).
   - `rememberAnimationsRemoved()` (`forest/ForestMotion.kt:58–67`) has no callers.
   - PR #139 adds `LocalReducedMotion`, fed from `gui.reducedMotion` only.
   - Shaders driven by their own frame-time uniform bypass the duration scale entirely.
5. **Robolectric shader support has an SDK ceiling.** Robolectric's `ShadowNativeRuntimeShader` covers `minSdk = R` to `maxSdk = UPSIDE_DOWN_CAKE` (34) [S18]. Every screenshot test here runs `@Config(sdk = [36])` (for example `WaterThemeScreenshotTest.kt` on PR #139). Whether AGSL renders in the Roborazzi goldens at SDK 36 is therefore **unverified**.

---

## 1. Inventory: what exists and how Water skins it

### 1.1 How components are drawn today (`:design`)

| Piece | Where | How it draws | Source |
|---|---|---|---|
| `ThemePack` | `theme/ThemePack.kt` | Data only: palette, `StoneMotion`, `StoneMaterial` (tile, glow and refraction overlays, ambient art), `StoneSounds`, plus `surfaces: Map<StoneComponent, StoneStateArt>` with five state drawables per component (18 components). | `ThemePack.kt:7–125` |
| `StoneSurfaceArt` | `theme/StoneSurface.kt` | Stacks `Image(painterResource)` per state and crossfades them with `animateFloatAsState`, so up to five bitmaps per part. | `StoneSurface.kt:22–71` |
| `stonePress` | `theme/StoneSurface.kt` | `Modifier.scale` from `animateFloatAsState` (read in composition), plus a TAP haptic on press. | `StoneSurface.kt:73–95` |
| `CrystalButton`, `CrystalPlayButton`, `CrystalSegmented`, `CrystalSlider`, `CrystalNavBar`, `CrystalTabs` | `CrystalControls.kt` | `StoneSurfaceArt` behind Material content. The button, play button and segmented control use `indication = null`. The slider's fill is a `Canvas` with `Brush.horizontalGradient`. The nav and tab "gem" is a rotated gradient box. | `CrystalControls.kt:55–347` |
| `softGlow`, `crystalPanel`, `luminousHairline`, `CrystalBackground` | `Crystal.kt` | `softGlow` is a radial-gradient `drawBehind` with no blur. `crystalPanel` tiles `ImageBitmap` with a nested `drawImage` loop on every draw. `CrystalBackground` is a cropped ambient image with a tint. | `Crystal.kt:96–222` |
| `readingPlate`, `glassScrim` | `Glass.kt` | Vertical gradient plates and scrims, with no blur. (The `glassScrim` name survives on `main`; PR #117 dropped the name rule.) | `Glass.kt:16–63` |
| `StoneIconArt` | `theme/StoneIcon.kt` | Vector paths (parsed once, `remember(icon)`) drawn three times with offsets (shadow, highlight, ink) for an etched or relief finish. | `StoneIcon.kt:31–106` |
| Haptics | `theme/StoneHapticCue.kt` | `View.performHapticFeedback` constants with an API floor, falling back to `VibrationEffect.createWaveform`. | `StoneHapticCue.kt:9–37` |
| `forest/*` | `forest/` | A parallel token system: `ForestColors`/`LocalForestColors`, `ForestMotion` springs with reduced-motion snaps, and `PebbleShape` (an uneven-ellipse `Shape`). It is used by several app screens. | `ForestMotion.kt`, `PebbleShape.kt`, `ForestTheme.kt` |

There are **two skin systems** (`LocalThemePack` and `LocalForestColors`). PR #139 notes this too and does not merge them.

### 1.2 How PR #139 skins each piece for Water

| Component (`StoneComponent`) | Water body | Drawn by | Effects |
|---|---|---|---|
| ALBUM_TILE, CHIP, COMPACT/PRIMARY/SECONDARY_BUTTON, ICON_BUTTON, KNOB, SLIDER_THUMB | **Pebble**: domed "sea glass" faked with layered gradients | `WaterSurface.pebble()` | Caustic oval and contact shadow beneath. A "refracted" flipped tint gradient. Thin-edge alpha and a dense centre. Emissive core by state. Top light and bottom shade. Specular rim arc and wet streak. Sinks 1–2 dp when pressed. Aqua rim when selected. |
| BOTTOM_SHEET, CARD, DIALOG, LIST_ROW, MINI_PLAYER, NAVIGATION_BAR | **Slab**: opaque wet slate | `WaterSurface.slab()` | 1 dp meniscus line and a hairline. |
| PROGRESS_RING, SLIDER_TRACK, TEXT_FIELD, TOGGLE | **Recess** cut into stone | `WaterSurface.recess()` | Lip shade and light. The ring has no value-driven fill yet. |
| Touch on pebbles | — | `InkBloomIndication` (`IndicationNodeFactory` + `DrawModifierNode`) | Radial-gradient ink bloom (600 ms) plus a stroked ring (450 ms) from the press point, clipped to a capsule. |
| Selection | — | `Modifier.stoneSpin` | `graphicsLayer { rotationY; cameraDistance = 12 * density }`, a 360° turn over 400 ms (280 ms on chips). Pebbles only. |
| Library lists | — | `Modifier.stoneScrollWake` | `NestedScrollConnection` drives five sine "current" paths that settle over 800 ms. |
| Haptics | — | `StoneHapticPattern` | `VibrationEffect.Composition` primitives (TICK, SLOW_RISE→LOW_TICK, CLICK) with waveform fallbacks via `areAllPrimitivesSupported`. |

Sources: PR #139 body and diff [S21]. Files on that branch: `design/.../theme/WaterPack.kt`, `StoneTouch.kt` and `StoneSurface.kt`.

**PR #139 performance notes** (to fix as part of the primitives below):
- `WaterSurface` reads `animateColorAsState`, `animateFloatAsState` and `animateDpAsState` **in composition**, so every frame of a state change recomposes. The docs recommend reading in the draw phase [S12][S13].
- It allocates `Path` objects inside `Canvas`/`draw()` on every frame: the pebble shape, rim and streak, `InkBloomNode`'s clip and every wake line. `drawWithCache` exists to avoid this [S8].
- `stoneScrollWake` launches a coroutine on every `onPostScroll` delta.

---

## 2. What can trip the no-glass checks

### 2.1 The static check (`tools/ci/check_no_glass.py` on `no-glass-check`) [S20]

- **What it scans:** `.kt`, `.kts` and `.java` files under `design/src/main`, `app/src/main` (except `assets/` and `cpp/`) and `feature/*/ui/src/main`, plus every `*.gradle.kts` and `.toml` file.
- **What fails, in Kotlin code** (comments and string literals, raw strings included, are blanked first):
  - `\.\s*blur\s*\(`: **any** method call named `blur(`, including your own helper.
  - `createBlurEffect`.
  - `BlurEffect(`.
  - `BlurMaskFilter`: this rules out the classic soft-shadow `Paint` trick.
  - Haze, Cloudy, BlurView and Blurry imports or names (`hazeSource`, `hazeEffect`, `cloudy(`).
- **What fails, in build files:** `haze`, `cloudy`, `Dimezis`, `eightbitlab` and `blurry` artifacts, and any text matching `glassmorph`, `liquid-glass` or `realtimeblur` (case-insensitive). This rules out Kyant's AndroidLiquidGlass, which `research-high-end-references.md` §3.2 recommends reading.
- **What passes:** `Shadow(blurRadius = …)` on text, `RenderEffect.createRuntimeShaderEffect`, `RuntimeShader`, `ShaderBrush`, pre-blurred bitmaps and gradient scrims.

**So AGSL does not trip the static check.** The shader source lives in a Kotlin string, which is blanked, or in a `res/raw/*.agsl` file, which is never scanned. That is a **loophole, not a permission**: an AGSL blur or backdrop-refraction shader would pass the regex but break the #102 rule and, probably, the visual review. Rules for this work:

1. **Never sample what lies behind a surface.** Use `ShaderBrush` (it draws its own pixels) rather than `RenderEffect.createRuntimeShaderEffect(shader, "content")`, which evaluates the RenderNode's existing pixels [S1].
2. **Never name a helper `blur`.**
3. **Draw glows with radial gradients** (as `softGlow` already does), never `BlurMaskFilter`.

### 2.2 The visual review (`review_glass_screens.py`) [S20]

Claude reviews every changed screenshot golden.

- **Fail:** "translucent glass orbs", frosted panels, glass-card styling.
- **Pass:** "natural-material art drawn as an image (sea glass, translucent stone pebbles)".

**Risk:** PR #139's code-drawn pebbles, described in its own KDoc as translucent sea glass, are exactly what a reviewer could call a glass orb. Pebbles shipped as **art** (gflow slots N1–N5, L1) fall in the explicit pass category. Translucency should therefore come from the art. Code should add only light (caustic, specular, inner glow), not see-through bodies.

---

## 3. Technique per effect

All sketches assume the `ElementEffects` primitives in section 6. Every draw function takes a **progress or phase float**, so it is a pure function of its inputs (see section 4).

### 3.1 Sea-glass pebbles: depth, refraction-like look, inner glow, specular, caustics

- **Primary: art.** Use gflow N1–N5 and L1 per state as `StoneStateArt` drawables, the existing mechanism (`ThemePack.kt:95–111`). This matches the owner's "no low-quality procedural art" rule (`research-high-end-references.md` line 5) and the visual-review pass category.
- **Code adds light only, in one `drawWithCache` block:**
  - An emissive core: a radial gradient whose alpha follows the state, read in the draw phase.
  - A specular arc and a caustic spot under the pebble.
- **"Refraction" is faked.** Use a flipped, tinted gradient of the pebble's own colour, as PR #139 does. Never sample the backdrop (section 2).
- **API 33+ (optional):** an AGSL `ShaderBrush` for a moving caustic on the pebble (a sum of a few sines, no texture lookups) [S1][S3][S7]. Use one `RuntimeShader` per instance, created in `drawWithCache` and given new uniforms per frame, which is the docs' own pattern [S7].
- **API 31–32:** the same gradient stack without moving caustics, or the static caustic baked into the art.

```kotlin
fun Modifier.pebbleLight(core: () -> Float, phase: () -> Float, tint: Color) = drawWithCache {
    val shader = if (SDK_INT >= 33) RuntimeShader(CAUSTIC_AGSL) else null   // compiled once per size
    val brush = shader?.let(::ShaderBrush)
    val rim = Path().apply { /* specular arc, built once per size */ }
    onDrawWithContent {
        drawContent()                                     // the pebble art
        drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = core()), Color.Transparent),
            center = size.center * 0.9f, radius = size.minDimension * 0.6f))
        if (brush != null) { shader.setFloatUniform("t", phase()); drawRect(brush, blendMode = BlendMode.Plus) }
        drawPath(rim, Color.White.copy(alpha = 0.55f), style = Stroke(1.5.dp.toPx()))
    }
}
```

`core()` and `phase()` are lambdas, so animated reads stay in the draw phase [S12][S13].

### 3.2 Ink bloom on touch

- **Technique:** keep PR #139's `IndicationNodeFactory` with a `DrawModifierNode`, the documented pattern [S9]. `Animatable`s live in the node, it collects `interactionSource.interactions`, and it draws in `ContentDrawScope.draw()`.
- **Fixes:**
  - Clip to the component's real `Shape` outline (`PebbleShape` or art bounds), not a capsule.
  - Cache the clip path.
  - Implement `equals`/`hashCode` on the factory so `clickable` can skip it [S9].
- **Fallback:** none is needed. Radial gradients work on every API.
- **Reduced motion:** the bloom fades in place without spreading (PR #139 already does this).

```kotlin
override fun ContentDrawScope.draw() {
    drawContent()
    val p = bloom.value; if (p >= 1f) return
    clipPath(cachedOutline) { skin.drawTouch(this, origin, p, reduced) }   // skinned per Element
}
```

### 3.3 Ripple rings

- **Technique:** the same indication node. Stroke one or two circles with radius `reach * p` and alpha `1 − p`.
- **API 33+ upgrade:** a `ShaderBrush` ring with a bright crest. It is additive, and still **not** content displacement.
- **API 31–32:** the T1 "touch effect sprite" from #105 (it is listed as the Android 12 fallback), scaled and faded.
- **Reduced motion:** a 150 ms opacity pulse (`ForestMotion.REDUCED_PULSE_MILLIS`, `ForestMotion.kt:40`).
- **Avoid** the `RenderEffect` displacement ripple in `research-high-end-references.md` §3.2. It refracts the surface's content, it is "more expensive than drawing a custom View" [S1], and it is the riskiest effect under #102.

### 3.4 Flowing water: currents in channels, scroll wake

- **Currents in channels** (slider track, Library stream):
  - **Primary, every API:** an `ImageShader` brush over the L2 or B1 tile with `TileMode.Repeated` [S3], with its matrix translated by a phase. That is one draw call, and it replaces `crystalPanel`'s nested `drawImage` loop (`Crystal.kt:136–157`).
  - **API 33+:** an AGSL flow-noise `ShaderBrush` may replace it.
- **Phase source:** `rememberInfiniteTransition().animateFloat(…)`. With duration scale 0 it snaps to the end and suspends [S16], so "Remove animations" freezes it for free. Gate it on `LocalReducedMotion` as well, for the in-app toggle.
- **Scroll wake:** derive it from `LazyListState` (or `NestedScrollConnection` writing one `Animatable` from a single long-lived coroutine), read in `drawBehind`, with paths cached per size. It is driven by scrolling only, never audio.

```kotlin
val phase by rememberInfiniteTransition("flow").animateFloat(0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing)), "p")
Modifier.drawWithCache {
    val shader = ImageShader(tile, TileMode.Repeated, TileMode.Repeated); val m = Matrix()
    onDrawBehind { m.reset(); m.translate(0f, phase * tile.height); shader.setLocalMatrix(m); drawRect(ShaderBrush(shader)) }
}
```

### 3.5 Stone-spin selection (3D rotation)

- **Technique:** `graphicsLayer { rotationY = turn.value; cameraDistance = k * density }` [S8][S19], using the lambda form recommended for animated values [S8]. Drive it with an `Animatable` launched on the rising edge of `selected` (PR #139's `stoneSpin`).
- **Changes:**
  - A full 360° turn shows the art's mirrored back face at 90–270°. Use a **180° turn that swaps to the selected-state art at 90°** (edge-on), or a short ±20° tilt-and-settle, which is cheaper and gentler on the vestibular system.
  - Rotate only the surface layer, never the text.
- **Haptics:** pair with the pack's SELECT cue.
- **Reduced motion:** no rotation. Crossfade to the selected art and swell the glow (#105, as PR #139 does).
- **API level:** `graphicsLayer` works on every API.

### 3.6 Vines growing (Wood)

- **Technique:** a designer-authored vector vine path. Use `PathMeasure.getSegment(0, length * p, dst)` per frame on a cached `PathMeasure`, then stamp leaf **art sprites** at fixed path fractions as `p` passes them.
- **Fallback:** none is needed.
- **Reduced motion:** draw at `p = 1`.

### 3.7 Crystal growth (Stone)

- **Technique:** facet polygons from art-derived vectors, each scaled from its base anchor with `withTransform { scale(sy = easeOut(p_i)) }` and staggered by index, with a `linearGradient` per facet for the lit and shaded faces.
- **Alternative:** a short sprite strip rendered offline.
- **Reduced motion:** grown, with a crossfade.

### 3.8 Glow waves (Bioluminescence)

- **Technique:** an expanding radial band. `Brush.radialGradient` with colour stops at `p − w`, `p` and `p + w`, drawn with `BlendMode.Plus` on the draw call. Do not put the blend mode on the layer, because any non-SrcOver layer blend forces offscreen compositing [S19].
- **API 33+:** a `ShaderBrush` for an organic band edge.
- **Driver:** touch or scroll only, never audio.
- **Reduced motion:** a single opacity pulse.

### 3.9 Haptics per Element

Keep `StoneHapticPattern`. The facts that matter:
- `Composition` has **no automatic fallback**. One unsupported primitive and nothing plays, so check support first [S10]. PR #139 does this with `areAllPrimitivesSupported`.
- Non-zero waveform amplitudes round to 100% without amplitude control [S10].
- `PRIMITIVE_LOW_TICK`, `SPIN` and `THUD` are API 31. `TICK`, `CLICK` and `SLOW_RISE` are API 30 [S11]. Both are fine at minSdk 31.
- The default delay type is `DELAY_TYPE_PAUSE`, counted from the end of the previous primitive. `DELAY_TYPE_RELATIVE_START_OFFSET` (API 36) gives exact timing [S11]. That would remove PR #139's per-device `ponytail` tuning note on API 36+.
- From Android 16, `VibrationEffect.Builder` presets fall back automatically [S10].

### 3.10 Asset vs procedural

| Effect | Image assets (gflow, per #105 slots) | Code |
|---|---|---|
| Sea-glass pebbles | **Yes**: N1–N5, L1, with state variants. The body, translucency and depth live in the art. | Light only: core glow, specular, caustic. |
| Wet slate slabs | S1 tile (optional) | Opaque fill, meniscus line |
| Ink bloom | No | Radial gradient |
| Ripple ring | T1 for the API 31–32 fallback | Stroke, or AGSL on 33+ |
| Channel currents | L2 and B1 tiles | `ImageShader` scroll, or AGSL on 33+ |
| Scroll wake | No | Paths |
| Stone-spin | Uses the pebble art | `graphicsLayer` |
| Vines (Wood) | Leaf sprites, plus an authored vine path | `PathMeasure` |
| Crystal growth (Stone) | Facet art or a sprite strip | Transforms and gradients |
| Glow waves (Bioluminescence) | No | Gradient, or AGSL |
| Hero and parallax backdrops | H0–H3, F1–F9 | Parallax offsets only |

Blender renders are not in the pipeline: the design doc says assets are made "with top-tier AI image generators, not Blender" (`app-ui-design-system.md` line 17). PR #139 mentions Blender pebble renders, which conflicts with that. #105's reference videos V4 and V6 are motion references for the touch and scroll shaders, and they do not ship.

---

## 4. Performance and testing plan

### Performance (mid-range target)

1. **Animate in draw, not composition.** Pass `() -> Float` lambdas and read `Animatable.value` inside `drawWithCache`/`draw()`. A state read in the draw phase reruns only drawing [S12][S13].
2. **Allocate nothing per frame.** Build `Path`, `Brush`, `RuntimeShader` and `PathMeasure` in `drawWithCache`, which keeps them until the size or the states it reads change [S8]. Only uniforms change per frame [S7].
3. **No `RenderEffect`.** It renders the node offscreen and is the "more expensive" path [S1]. `ShaderBrush` adds one function to the fragment shader that is already running [S2].
4. **Animate only while alive.** Indications go idle at `p >= 1`. Gate infinite flows on visibility, cap concurrent blooms at one per component, and keep the wake to a single long-lived coroutine.
5. **AGSL budget:** `half` precision where possible [S6], a few sines, no loops over the screen.
6. **Baseline Profile:** the repo has none (no `profileinstaller` or `baselineprofile` hits outside detekt baselines). Profiles cut jank "from the first launch", by about 30% [S14]. Add a Library-scroll and touch journey once `:design` effects land. This is CI work: Macrobenchmark on the emulator job.

### Testing (Roborazzi, already in use; Paparazzi is not needed)

The repo's screenshot tests use Roborazzi under Robolectric `@GraphicsMode(NATIVE)`, and goldens are recorded in CI with `[record-goldens]` (`docs/ci.md`, "App UI screenshots" and "Re-recording goldens"). The plan:

- **Frame goldens.** Because each effect is a pure `draw(progress)` function, a test renders it at p = 0, 0.5 and 1 without a clock, one row per Element. For animated composables, use the Compose test clock (`mainClock.autoAdvance = false`, `advanceTimeBy`).
- **Reduced-motion goldens.** Render each effect with `LocalReducedMotion provides true`.
- **AGSL in screenshots.** Prove first that it renders at SDK 36 (finding 5). If not, run shader tests at `@Config(sdk = [34])`, or screenshot only the fallback tier, and cover AGSL compilation with an instrumented test on the emulator job.
- **Unit tests** in `design/src/test` for the pure maths (easing and staggers, `wakeAfter`, state→core tables). `:design` has a mutation job, and the floors only ratchet up (`docs/ci.md`, "Mutation score floors").
- **No-glass gate.** Every new golden is sent to the visual review. Land pebble goldens early, in their own PR, to get a verdict before building on them.

---

## 5. Risks

| Risk | Mitigation |
|---|---|
| The visual review fails code-drawn translucent pebbles as "glass orbs" [S20] | Pebble bodies from art; code adds light only; early golden PR |
| An AGSL blur or backdrop-refraction slips past the regex check | Review rule: `ShaderBrush` only, no `uniform shader` sampling content in the App UI |
| PR #139 fails to compile (`LibraryScreen.kt:72`) | Fix the import line before anything builds on it |
| Reduced motion ignored on `main`; the system setting only partly covered | `LocalReducedMotion` = in-app toggle OR `ANIMATOR_DURATION_SCALE == 0`, observed live (API 33 `registerDurationScaleChangeListener` [S17], or Compose's own `MotionDurationScale`) |
| AGSL screenshots unsupported at SDK 36 in Robolectric [S18] | Verify in CI; pin to SDK 34 for shader tests |
| Stale glass and blur guidance in `docs/design/*` | Add a "superseded by #102" note |
| Two skin systems (`forest/*` and `ThemePack`) | Primitives attach to `ThemePack` only; fold forest tokens in later |
| 360° spin shows mirrored art and can cause motion discomfort | 180° swap or a tilt; none under reduced motion |
| Composition haptics silently not playing [S10] | Keep the `areAllPrimitivesSupported` check and waveform fallback |
| APK size: 26 images × densities × 4 Elements | #105 already requires reporting the size; WebP at `drawable-nodpi` as for Lapis |
| Audio coupling creeping in | Effects take only interaction, scroll and time inputs; `:design` depends on nothing in the build (`docs/ci.md` architecture rules) |

---

## 6. Recommended shared primitives (minimal)

One interface, implemented once per Element and carried on `ThemePack` in place of PR #139's ad hoc `drawn` lambda and motion fields.

```kotlin
/** How an Element looks and moves. Pure draw functions of progress; never fed audio. */
interface ElementEffects {
    /** Light over a part's art (or the whole body when no art exists yet). */
    fun DrawScope.body(component: StoneComponent, state: StoneState, light: Float)
    /** Touch at [origin]: bloom, ring, glow wave… p in 0..1; [reduced] = fade in place. */
    fun DrawScope.touch(origin: Offset, p: Float, reduced: Boolean)
    /** Selection flourish: spin, vine, crystal… p in 0..1. */
    fun selection(component: StoneComponent): Flourish   // Spin(degrees) | Overlay(draw: DrawScope.(Float) -> Unit) | None
    /** Ambient flow in channels and lists; phase in 0..1 from time or scroll. */
    fun DrawScope.flow(phase: Float, wake: Float)
    val timings: ElementTimings          // touchMs, selectMs, flowPeriodMs, wakeMs
    val haptics: Map<StoneHapticCue, StoneHapticPattern>
}
```

There are three shared modifiers, one per driver, and every Element reuses them:

- `Modifier.elementTouch()`: one `IndicationNodeFactory` that calls `touch(...)`.
- `Modifier.elementSelect(selected)`: an `Animatable` that applies `Spin` via `graphicsLayer` or `Overlay` via `drawWithContent`.
- `Modifier.elementFlow(scrollState?)`: an infinite-transition or scroll-driven phase that calls `flow(...)`.

All three read `LocalReducedMotion`, which `CrystalTheme` sets from the in-app toggle **or** the system scale. Water, Wood, Stone and Bioluminescence differ only in their `ElementEffects` object.

Skipped: per-effect shader classes, a generic particle system, and a `RenderEffect` path. Add them only if a reviewed art direction cannot be met with brushes.

---

## Sources

- [S1] Using AGSL in your Android app: https://developer.android.com/develop/ui/views/graphics/agsl/using-agsl
- [S2] AGSL overview ("Your AGSL effect contributes a function… to the GPU's fragment shader"): https://developer.android.com/develop/ui/views/graphics/agsl
- [S3] Compose Brush (gradients, `ImageShader`, AGSL `ShaderBrush` with `@RequiresApi(TIRAMISU)`): https://developer.android.com/develop/ui/compose/graphics/draw/brush
- [S4] `RuntimeShader`, added in API 33: https://developer.android.com/reference/android/graphics/RuntimeShader
- [S5] `RenderEffect`, added in API 31 (`createBlurEffect`, `createRuntimeShaderEffect`): https://developer.android.com/reference/android/graphics/RenderEffect
- [S6] AGSL vs GLSL (coordinates, `half` precision): https://developer.android.com/develop/ui/views/graphics/agsl/agsl-vs-glsl
- [S7] `ShaderBrush` in `drawWithCache` with per-draw uniforms (sample in [S3])
- [S8] Graphics modifiers (`drawWithCache` caching rules, `graphicsLayer` rotationX/Y, lambda form for animation): https://developer.android.com/develop/ui/compose/graphics/draw/modifiers
- [S9] Migrate to Indication/Ripple APIs (`IndicationNodeFactory`, `DrawModifierNode`, `equals`/`hashCode`): https://developer.android.com/develop/ui/compose/touch-input/user-interactions/migrate-indication-ripple
- [S10] Create custom haptic effects (fallbacks, Composition support checks, amplitude control): https://developer.android.com/develop/ui/views/haptics/custom-haptic-effects
- [S11] `VibrationEffect.Composition` (primitive API levels, delay types): https://developer.android.com/reference/android/os/VibrationEffect.Composition
- [S12] Compose phases (state reads per phase): https://developer.android.com/develop/ui/compose/phases
- [S13] Compose performance best practices (defer reads, lambda modifiers, backwards writes): https://developer.android.com/develop/ui/compose/performance/bestpractices
- [S14] Baseline Profiles overview: https://developer.android.com/topic/performance/baselineprofiles/overview
- [S15] AndroidX `WindowRecomposer.android.kt` (`ANIMATOR_DURATION_SCALE` → `MotionDurationScale`): https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/platform/WindowRecomposer.android.kt
- [S16] AndroidX `InfiniteTransition.kt` (`durationScale == 0f` → `skipToEnd()`) and `SuspendAnimation.kt`: https://github.com/androidx/androidx/blob/androidx-main/compose/animation/animation-core/src/commonMain/kotlin/androidx/compose/animation/core/InfiniteTransition.kt
- [S17] `Settings.Global.ANIMATOR_DURATION_SCALE` ("0.0f will cause animations to end immediately") and `ValueAnimator` (`areAnimatorsEnabled` API 26, `registerDurationScaleChangeListener` API 33): https://developer.android.com/reference/android/provider/Settings.Global, https://developer.android.com/reference/android/animation/ValueAnimator
- [S18] Robolectric `ShadowNativeRuntimeShader` (`minSdk = R`, `maxSdk = UPSIDE_DOWN_CAKE`): https://github.com/robolectric/robolectric/blob/master/shadows/framework/src/main/java/org/robolectric/shadows/ShadowNativeRuntimeShader.java
- [S19] `GraphicsLayerScope` (`cameraDistance` in pixels; non-SrcOver blend forces offscreen): https://developer.android.com/reference/kotlin/androidx/compose/ui/graphics/GraphicsLayerScope
- [S20] PR #117 and `tools/ci/check_no_glass.py` on branch `no-glass-check`: https://github.com/tessie1993/geode-final/pull/117
- [S21] PR #139, Water Element theme pack (`claude/water-element`): https://github.com/tessie1993/geode-final/pull/139
- Issue #105, the Water asset set and slots: https://github.com/tessie1993/geode-final/issues/105
