# Element motion components

How every motion component of the Element skins works in the App UI: what triggers it, how long it runs, how it is interrupted, what it sounds and feels like, what it does under reduced motion, what it may cost, how it is built, and what each of the 12 Elements does with it.

**Sources, in order of precedence.** Where two disagree, the earlier one wins:

1. The "Decisions on proposals (2026-10-22)" section of each Element ticket and of #126.
2. The "Grilled decisions" sections of #105–#108, #124 and #125.
3. The "Skin design (agent, 2026-10-22)" sections of all 12 tickets: #105–#108, #124, #125, #128–#130 and #132–#134.
4. The research note on PR #149 (`docs/design/research-element-ui-effects.md`), PR #139 (the Water ThemePack) and PR #140 (`LocalReducedMotion`).

**Terms** are those of `CONTEXT.md`, with the **Element**, **Hero scene** and **Ambient** entries from PR #127. Ambient is Drifting, Still or Off.

**Rules that hold for every row below:**

- **The App UI never reacts to audio.** The only inputs are a finger position, a press or release, a selection or toggle state, a slider value, a scroll delta or overscroll, a navigation destination, a loading, empty or error state, an idle timer, and time. No component takes a Sound signal, a level, a beat or a spectrum, and no component runs in time with the music.
- **No glass, no live blur** (#102, PR #117). Nothing samples what lies behind it. That rules out `RenderEffect.createRuntimeShaderEffect(…, "content")`, `BlurMaskFilter` and any helper named `blur`. Glows are radial gradients. Translucency comes from the art (the Blender or gflow material), never from code.
- **Motion decorates state; it is never the state.** With every animation off, each control reaches the same end state on the first frame. A state change is never carried by motion alone: the art, the colour and the semantics change too.
- **Every animation is interruptible, and none blocks input.** A tap during a flourish or a transition resolves it to its end state and acts on the tap.
- **One haptic per committed event, on the commit frame.** Never from an animation callback, never per frame, never once per input of a fling. Haptics come from the pack's `haptics` map (PR #139's `StoneHapticPattern`), go through one central gate (the haptics switch), and are subtle. A missing entry is a declared silence. `heavyImpact`-strength patterns are never used, and an error is never stronger than a tap.
- **Sounds are the third channel.** They are short, use the pack's `StoneSounds` slots, go through the UI-sounds switch (on by default, off by default in Silence), respect the ringer's silent mode, and are never the only signal.
- **Reduced motion** is `LocalReducedMotion`, which is the app's Reduce motion toggle **or** Android's animator duration scale of 0 (PR #140, `ProvideReducedMotion`). Haptics and sounds still play under reduced motion, because they are the channels that remain.
- **Ambient** affects only the Hero scene's ambient loop and the other self-running layers listed in its row. Touch, selection and value-driven motion ignore it.

---

## 1. The shared model

Each kind of motion component is defined once here. Section 3 gives each Element's concrete values. "Frequency" is the [frequency gate](#frequency-and-duration-caps): the more often a component fires, the shorter and quieter it must be.

| Kind | Trigger and input | Duration and easing | Interruption and cancel | Haptic cue | Sound cue | Reduced-motion fallback | Performance budget |
|---|---|---|---|---|---|---|---|
| **Touch** | `PressInteraction.Press`, with the finger position (`pressPosition`) as the origin. Only on the touched control. | Spread 450–600 ms, `LinearOutSlowInEasing` (fast start, soft end). Alpha fades linearly to 0. A flare before the spread is 90–120 ms. | A new press restarts the effect from the new origin (`snapTo(0)`), with at most one effect per control. Release does not cancel: the effect finishes. `PressInteraction.Cancel` (the finger slides off, or a scroll starts) fades the effect out in 120 ms. | `TAP` on press-down, the same frame as the visual press. Stone: none. | `click` on the committed click (release inside the control), never on press-down. Nothing on cancel. | No spread or travel. The effect appears at full size and fades its opacity in place over 150 ms (`ForestMotion.REDUCED_PULSE_MILLIS`). | 0 allocations per frame. Draw ≤ 0.3 ms per frame on a mid-range phone (Snapdragon 7-class). The node draws nothing once `p ≥ 1`. |
| **Selection** | The rising edge of `selected` on a selectable control: a bar tab, chip, segmented item, list row or album tile. | 360–560 ms, ease-out. Frame sequences carry their own easing and are played linearly in time. | Deselecting mid-flight sends the old control to its deselected art at once (a 120 ms crossfade) while the new one plays. Reselecting an already-selected control does nothing. A selection never delays navigation. | `SELECT` (the pack's pattern), once on the commit frame. Its Composition timing (for example `SLOW_RISE` then `LOW_TICK` at about 400 ms) lands the settle; nothing is fired at the animation's end. | `confirm`. | The selected art and glow crossfade in over 150 ms, with no rotation, lift, growth or travel. Elements whose selection is a pure colour change (Bioluminescence) keep it. | One `drawImage` per frame. Frames are decoded before the first play (section 4), ≤ 5 MB decoded per sequence. No recomposition during play. |
| **Idle and active glow** | The control's `StoneState`: `DEFAULT`, `FOCUSED`, `SELECTED` or `DISABLED`. It is a level, not a loop. | It follows the state change: 150–300 ms ease-in-out (`StoneMotion.focusDurationMs` and `selectedDurationMs`). | A new state retargets the running animation from its current value (`animateTo`), with no jump. | None. The glow rides on the selection or toggle that caused it. | None. | It snaps to the new level. | The glow is read in the draw phase through a `() -> Float` lambda, so a state change never recomposes per frame. 1 gradient draw. |
| **Press and release** | Press-down and release or cancel on any control. | Press 60–120 ms, then release 220–520 ms, both `FastOutSlowInEasing`, using `StoneMotion.pressDurationMs`, `releaseDurationMs` and `pressScale` (0.94–0.99). A sink is at most 2 dp. | Release or cancel reverses from the current value. A press always shows on the frame it happens. | `TAP` on press-down, as for touch (one haptic for both). | None on press; `click` on commit, as for touch. | Instant state change: the pressed art and colour, with no scale or sink. | `graphicsLayer { }` lambda only: no layout, no recomposition. |
| **Toggle** | The `checked` value changing, after the commit (the click), never on press. | On 220–400 ms, off 200–600 ms, ease-out. Each Element's on and off times are in section 3. | Toggling again mid-flight reverses from the current frame or value, with no restart. | `TOGGLE_ON` or `TOGGLE_OFF` (PR #139 cues), once on the commit frame. Stone: none. | The toggle sound (W-A3 and its siblings), and an "off" variant where the Element has one (Fire's cooling ping). | The end state is drawn at once, with a 150 ms crossfade. | ≤ 2 MB decoded per sequence. Same rules as selection. |
| **Slider fill** | The slider's value, which follows the finger 1:1 while dragging. Also the seek line, the knobs and the text-field underline where the Element puts the same fill there. | No easing while dragging: the fill is drawn at the value on the same frame. After release, the thumb settles in 100–220 ms. A self-running flow inside the fill (Water's channel) loops at the period in section 3. | Always live. Programmatic changes (seek during playback) snap and never animate. | `SLIDER_TICK` at detents or 10 % steps only, never continuous. Stone and Silence: none, or detents only. | None, except an Element's own optional step tick, which plays at most once per detent. | The fill still follows the value (it is the state). Any self-running flow inside it freezes. | Drawn inside the slider's existing `Canvas`, with no extra layer. ≤ 0.2 ms per frame. Textures are an `ImageShader` with `TileMode.Repeated`, not a `drawImage` loop. |
| **Scroll response** | The scroll delta of a list (`NestedScrollConnection.onPostScroll`), or its overscroll at the ends. | It builds with the distance scrolled (`wakeAfter`) and settles with `LinearOutSlowInEasing` over the Element's settle time (Water 800 ms). | It accumulates and is never restarted. A new fling adds to the current value. A touch that stops a fling does not cancel it: it keeps settling. | None. | None. | None. The list scrolls with no wake or bend. Overscroll uses a plain clamp with no stretch. | One `Animatable` written from one long-lived coroutine, with no `launch` per delta. Paths are built once per size in `drawWithCache`. ≤ 0.5 ms per frame. Nothing recomposes per delta. |
| **Tab transition** | A navigation destination change: a bar tab, or Settings opened from Home. Back follows reading direction. | 150–450 ms in total, decelerating on the way in and accelerating on the way out. The overlay sweep and the new screen's entry overlap. | A new destination during a transition jumps the running one to its end, then starts the next. Input reaches the new screen as soon as it is composed. | None by default. Moon has a "NAV change" pattern, which fires once on the commit. | `swoop`, once per destination change, never on Back-stack restores. | A 150 ms crossfade (Silence: instant), with no slide, scale, sweep or zoom. | Both screens are composed only during the transition. The overlay is ≤ 1 full-screen gradient or `ShaderBrush` pass. No `RenderEffect`, and the old screen is never distorted. |
| **Hero scene ambient loop** | Time only, while Home is the current destination and the app is started. It follows Ambient: Drifting runs it, Still freezes it on its rest frame, Off shows the static base plate. | Long, seamless loops, 5–40 s per layer, linear or sine. Layers are out of phase so no beat appears. Never in time with music. | It stops when Home is not current, when the app is backgrounded (`Lifecycle.State.STARTED`), under Still or Off, and under reduced motion. It resumes from its phase, not from 0. | None. | The Element's Hero scene sound loop (the A4 slot) plays under Drifting only, if UI sounds are on. | Frozen on the rest frame (the same frame as Still). | ≤ 4 image layers plus ≤ 40 particles. ≤ 3 ms of GPU time per frame at 1080×2400. Layers are decoded once at screen size. |
| **Loading** | A loading state that lasts longer than 400 ms. Shorter loads show nothing, so there is no flash. | The loop period in section 3 (1200–4000 ms). Determinate progress follows the value. | When loading ends, the result crossfades in over 150 ms at once. The loop never holds the result back to finish its cycle. | None. | None. | Static. Indeterminate progress shows a lit, still mark with an opacity pulse at most every 1200 ms. Determinate progress still follows the value. | One small loop. It stops when the state changes or the component leaves composition. |
| **Empty state** | A list or screen with no content. | It appears with a 150–300 ms fade. It has no loop. | None. | None. | None. | The same, without the fade. | One static image and one line of text. |
| **Error state** | An error for one control or screen (the banner of the error channel stays as it is). | A one-shot of ≤ 400 ms (a flicker, a dim, an eclipse) that settles into the static error look. | A fix ends it at once. Stone plays its kintsugi seam on the fix. | `DESTRUCTIVE`, which is never stronger than the Element's tap. Stone: none. | `error` (Stone needs the new `error` slot, as #107 accepted). | The static error look at once. | The same as touch. |
| **Element-specific extras** | Only the extras the owner accepted (#126 table). Each lists its own input in section 3. | Per extra. | The rules of the nearest kind above apply. | Only where section 3 says so. | Only where section 3 says so. | Per extra, in section 3. | The budget of the nearest kind above. |

### Frequency and duration caps

The Mobile-app weighting of the motion principles applies: polish first, restraint for anything frequent.

| Frequency | Components | Cap |
|---|---|---|
| Hundreds of times a day | press, touch, slider fill | Press shows on the same frame. The effect trails after the action and never delays it. |
| Tens of times a day | selection, toggle, tab transition | ≤ 600 ms, input never blocked. A transition over 450 ms is flagged in section 7. |
| Once per visit | Hero scene loop, empty, error | Slow and calm, but always stopped by Ambient and reduced motion. |
| Rare | Night sky's meteor, Meadow's grasshopper, Stone's growing geode | Expressive is fine, but they play once, start from the point of action or the scene, and never loop. |

---

## 2. Implementation choice per kind

There are four techniques:

- **Procedural Compose:** `graphicsLayer`, `drawWithCache`, `Animatable`, `IndicationNodeFactory` and gradients.
- **AGSL `ShaderBrush`:** API 33 and up only. It draws its own pixels, with no `uniform shader` input, no backdrop sampling and no blur.
- **A pre-rendered RGBA frame sequence from Blender**, following the contract in section 4.
- **An image plus a transform:** static art (`StoneStateArt` or Hero scene layers) moved by `graphicsLayer` and crossfaded.

| Kind | Choice | Why |
|---|---|---|
| Touch | **Procedural Compose**: one `IndicationNodeFactory` with a `DrawModifierNode` (PR #139's `InkBloomNode`, fixed) | It must start at the finger, clip to the control's real outline and run on every API. Radial gradients and strokes do that for almost nothing. A frame sequence cannot follow the origin or the shape. AGSL is an optional crest on API 33 and up, never required. |
| Selection | **Blender frame sequence** for Elements whose body changes in 3D (Water, Wood, Stone, Fire, Meadow, Bubble). **Procedural Compose** for Elements whose selection is light only (Bioluminescence, Sun, Moon, Silence, Light, Night sky). Section 3 marks each one. | A real turn, growth or wobble of a translucent 3D body can't be faked with `rotationY` on flat art: a 360° turn shows the mirrored back face (research §3.5), and code-drawn translucency risks the no-glass review. Blender renders it with the same rig as the state art (the Water spin already exists, 24 frames). A light-only flourish (rays, a halo, a ring or a hue shift) is cheaper and sharper as gradients and paths. |
| Idle and active glow | **Procedural Compose**: light only over the art, in `drawWithCache` | The body comes from the art. Code adds only the emissive core, the specular and the rim, which is research §3.1 and passes the no-glass review. The level is a draw-phase lambda, so no frames are needed. |
| Press and release | **Image plus transform**: the pressed-state art crossfade plus `graphicsLayer` scale and translation (`stonePress`) | It must show on the press frame, with nothing to decode, and reverse from any point. Art already exists for the pressed state (`StoneStateArt.pressed`). |
| Toggle | **Blender frame sequence**, played forward for on and backward for off | Every Element's toggle changes shape (a pod opens, a flower opens, a bubble inflates, a tuft stands up, a lantern lights). Toggles are small (≤ 56 dp), so sequences are cheap, and reversing from the current frame is how an interruption stays smooth. Stone, Moon, Silence and Night sky use procedural fills, as section 3 marks, because their toggles are a vein of light, a phase disc, a sliding dot and a star. |
| Slider fill | **Procedural Compose**: a fill clipped to the value, with an `ImageShader` tile for texture | The fill is continuous and exact at the finger. A tiled texture keeps it at art quality in one draw call. Bioluminescence's fiddlehead knob is the one value-scrubbed frame sequence (section 3). |
| Scroll response | **Procedural Compose**: a `NestedScrollConnection` (or an `OverscrollEffect` for Meadow) feeding one `Animatable`, drawn with cached paths | It is driven by a delta, not by time, has no fixed length, and must cost nothing while idle. |
| Tab transition | **Procedural Compose**: `AnimatedContent` with alpha and translation for the screens, plus a `drawWithCache` overlay for the sweep (vines, a beam, a shadow, a glow wave, a film). Optional **AGSL** on API 33 and up for an organic edge. | The sweep crosses the whole screen at any size, so a full-screen frame sequence would be far too heavy (one 1080×2400 RGBA frame decodes to 10 MB). The overlay draws over the screen and never samples it, so Fire's heat haze is an additive shimmer band, not a distortion of the old screen. |
| Hero scene ambient loop | **Image plus transform**: the H0–H3 plates moving in slow parallax, plus procedural particles. Optional **AGSL** on API 33 and up for moving light (caustics, lava glow, beams). | A full-screen loop as frames is ruled out by memory: 8 s at 24 fps is 192 frames, and even at half size each frame decodes to 2.5 MB. The Veo videos (W-V1 and its siblings) are motion references only and do not ship (#105). |
| Loading | **Blender frame sequence** loop where the mark is a 3D body (Wood, Stone, Bubble), **procedural** where it is light (Water, Bioluminescence, Fire, Sun, Moon, Silence, Light, Night sky, Meadow) | The same rule as selection: shape in 3D comes from frames, light comes from code. |
| Empty and error states | **Image plus transform**: one static state image with a fade or a one-shot transform | They are static by design. The error one-shot (a flicker, a dim or a slide) is an alpha or translation on the image. |
| Element-specific extras | **Per extra**, as section 3 marks | Each is the nearest kind above. |

Cell tags in section 3: **[P]** procedural Compose, **[A]** AGSL on API 33 and up (always with a [P] fallback), **[F]** Blender frame sequence, **[I]** image plus transform.

---

## 3. Per-Element matrix

Times are in ms. "RM" is the reduced-motion fallback where it differs from the shared default in section 1.

### 3.1 Touch, selection, glow, press

| Element | Touch | Selection | Idle and active glow | Press and release |
|---|---|---|---|---|
| **Water** | [P] An aqua ink bloom under the surface from the finger (600), with a caustic ripple ring (450) ahead of it. The pebble brightens from within. [A] an optional ring crest. | [F] A wet stone-spin, 400 ease-out, shedding droplets as the inner light swells. 24 frames at 60 fps. RM: a 200 glow swell. | [P] A milky-aqua core `#A8F0E6` when selected; the selected bar pebble sits 2 dp higher. | [I] Sinks 1–2 dp with the pressed art (ink under the water). |
| **Wood** | [P] A soft life-magic glow bloom from the finger, with the resin glow brightening, then fading (600, ease-out). | [F] The pebble turns slowly (400, ease-out) and a glowing moss sprout grows (about 350, `Bloom` spring), staying while active. RM: a glow swell. | [P] An amber resin glow seeping from the wood; the active row has a moss glow. | [I] A 2 dp settle sink into the moss over 320. |
| **Stone** | [P] The nearby crystals glow from the touch point (bloom 120, fade 600), and [F] one violet smoke wisp curls off (900). RM: a 150 opacity pulse, no wisp. | [F] Crystal growth, no spin: the point grows (0–300), the inner glow ignites (200–400), the shard lifts and settles (300–500), then a wisp follows. RM: a 150 glow swell. | [P] The inner crystal glow; the active row's crystal marker lights. | [I] `pressScale`, no sink. |
| **Bioluminescence** | [P] A cyan glow wave spreads from the finger through the nearby moss (600), and [P] a small puff of spores rises from the pod. RM: the wave fades in place, no puff. | [P] The glow shifts from cyan to violet (400), with no spin or lift. RM: kept, as it does not move. | [P] Cyan at rest, violet when active; the bar's active cap turns violet. | [I] A soft squash (`pressScale`). |
| **Fire** | [P] 4–7 sparks burst up from the finger (500), and the ember flares (90) and cools (600). | [F] Ember ignite: cracks spread and flare orange (400), and a few sparks lift. RM: a 120 glow swell. Deselect: [P] ember cool, dimming back over 600, ease-out. | [P] The active ember burns bright through its cracks. | [I] `pressScale`. |
| **Sun** | [P] A warm light bloom with 6–8 soft rays (110 in, 600 fade). RM: glow only, 150. | [P] A sun-ray halo: 12 short rays fan open around the control (400, ease-out). RM: a glow swell. | [P] The active tab's flower holds a glowing amber drop. | [I] `pressScale`. |
| **Moon** | [P] A fine silver regolith dust puff that rises and settles (600). Icon pebbles and Play add a crater ring with faint rays (450). The moonstone sheen glides across on press (120 in, 520 out). | [P] MoonPhase: a crescent-to-full wax (560, ease-in-out), with a lift of at most 2 dp on the low-gravity spring (damping 0.9, stiffness 120). | [P] A full Moon in ivory when active; a 22° halo for focus (300). | [I] Press 120, release 520 on the low-gravity spring. |
| **Silence** | [P] An opacity step only (alpha 0.88–0.92 over 60, release 120). | [P] An ensō ring: an unfinished 1.5 dp brush circle draws to about 92 % (160, linear-out). The list-row sage tick fades in over 160. RM: the ring appears drawn. | [P] A bone hairline ring; no inner glow (matte slate). | [I] `pressScale` 0.99, the only scale Silence uses. |
| **Light** | [P] A light ripple: a thin white ring runs out and frays into a faint spectrum at its edge (220). | [P] Beam focus: a beam narrows onto the control from above and the pebble brightens, with a thin spectral rim flare (360–400), and no spin. With the spectral bar, the beam takes the tab's hue. | [P] A full beam, the pebble lit, with an active-blue rim flare. | [I] Press dim 90, then a release flash 220. |
| **Night sky** | [P] StarTwinkle: a 4-point diffraction glint at the finger plus 2–3 rose specks (90 flare, 220 fade). | [P] ConstellationLink: a gold line draws from the old selection to the new one, the new star flares, and the line fades (520 in total). RM: the new star is lit, with no line. | [P] The active star is gold with spikes, and the bar lines next to it brighten. | [I] `pressScale`. |
| **Meadow** | [P] A wind ring: grass bends outward in a ring from the finger and springs back (520). RM: a 120 tone flash. | [F] A grass bow: the blades around the control bow away and rebound with a small overshoot (450). RM: a rim-light fade. | [P] Chartreuse rim light; the grass around the active bar stone stands taller. | [I] `pressScale`. |
| **Bubble** | [P] A dimple at the finger (press scale 0.94, then a spring back with one overshoot, 320), plus [P] a ring of 5–7 micro-beads off the rim (400). | [F] Film swirl: the bands swirl once and settle with a gold band on top, with a 6 % wobble (420), and no spin. RM: the gold band crossfades in. | [P] FilmRim: an interference sweep gradient turning once every 8 s on the active bubble only. It stops under reduced motion and when off-screen. | [P] Part of the dimple. |

### 3.2 Toggle, slider fill, scroll response, tab transition

| Element | Toggle | Slider fill | Scroll response | Tab transition |
|---|---|---|---|---|
| **Water** | [F] A small pebble rolls across a short channel (220). On: the aqua core lights and water flows through. Off: dry and dark. | [P] Aqua water flowing toward the thumb in a carved channel (an `ImageShader` tile scrolled by phase, an 1800 loop) that speeds up slightly while dragging. RM: the flow freezes. | [P] **Library scroll wake** (accepted): flinging leaves current lines behind the slabs, which settle over 800. | [P] Downstream: the current swings, and the new screen slides in from upstream (320). RM: a 150 crossfade. |
| **Wood** | [F] A seed pod opens to show a glowing seed (about 350, `Bloom` spring). | [P] Moss fills a branch track, following the value, with a pebble knob. | None. | [P] Vines grow across the screen (about 450, `PathMeasure` plus leaf sprites) and part (350). RM: a 150 crossfade. |
| **Stone** | [P] A vein of light grows end to end (300) and retracts (200); when on, the end crystal ignites. | [P] A vein of light grows with the value; the unfilled part is a dark crack, and steps are mica flecks. | None. | [P] Deeper into the cave: the scene scales 1.0→1.06 under a dark vignette, and the new page rises out of the dark (420). **Tinted descent** (accepted): lit by the destination tab's crystal. RM: a 150 crossfade. |
| **Bioluminescence** | [F] A pod that glows when on. | [P] A vine that lights along its length with the value. | None. | [P] A glow wave washes across (280), then the new screen fades up (240), with an 80 ms overlap (440 in total). [A] An optional organic band edge. RM: a 150 crossfade. |
| **Fire** | [F] An ember ignites when on (400) with a small spark puff; off cools (600). | [P] A glowing lava crack grows along the basalt, following the thumb, with no idle motion. | None. | [P] A heat-haze band: the old screen fades under an additive shimmer band (240), then the new one emerges (240). It never distorts the old screen. [A] An optional shimmer. RM: a 120 crossfade. |
| **Sun** | [F] A bud opens (320) and closes (240). | [P] A grass stem fills with sunlight, tracking the finger with no lag, with an amber knob. | None. | [P] A soft diagonal sunbeam sweeps from upper left to lower right (450), and the new screen warms in (warm tint 12 %→0, alpha 0→1, 300). RM: a 150 crossfade. |
| **Moon** | [P] A phase disc waxes from new Moon (off) to full Moon (on) or wanes back (560). | [P] A terminator line: the filled side is lit in moonstone sheen, following the thumb. | None. | [P] Moonrise: the new screen rises 8 dp and brightens from 0.6 to 1.0 (600, see section 7). RM: a 150 crossfade. |
| **Silence** | [P] A flat stadium; the slate dot slides (120). | [P] A 2 dp sage hairline fill that follows the finger with no lag. | None. | [P] An alpha crossfade of 150, with no slide. RM: instant. |
| **Light** | [F] A lantern pebble lights from the centre (on 260, off 200). | [P] A light thread that brightens with the value, with an alabaster bead knob that grows 1.15× while dragged (grow 120, settle 220). | None. | [P] Aperture: an iris closes (320) and opens (320) on the new screen. RM: a 150 crossfade. |
| **Night sky** | [P] StarIgnite: a star lights inside the iron pebble and slides to the end with a short trail (220). | [P] StarDotFill: the dots turn gold and a line joins them behind the thumb, 1:1. | None. | [P] SkyRotate: the stars arc by a few degrees as short trails while the new screen fades in (360). RM: a 150 crossfade. |
| **Meadow** | [F] A grass tuft stands up (280, with overshoot) or lies down (200). | [P] A mown path: the filled side is cut short and lighter, following the value; it settles in 180. | [P] **Overscroll bends the grass** (accepted): at a list end, the field edge bends like grass under a hand and springs back, in place of the stock stretch. | [P] A cloud-shadow pass: a soft cloud shadow sweeps from left to right, with the new screen in the light behind it (420). RM: a 150 crossfade. |
| **Bubble** | [F] Inflate from a flat disc to a sphere (360) and deflate back (240). | [P] BeadThread: the beads light up as the value passes, each in 80. | None. | [P] FilmSheet: a film sweeps across and pops into droplets (450). RM: a 120 crossfade. |

### 3.3 Hero scene, loading, empty, error, accepted extras

The Hero scene layers are always [I] plates plus [P] particles, with [A] light where noted. Every loop follows Ambient (Drifting runs it, Still freezes it, Off shows the static plate) and stops under reduced motion.

| Element | Hero scene ambient loop | Loading | Empty | Error | Accepted extras |
|---|---|---|---|---|---|
| **Water** | A slow caustic river flow ([A] caustics) and layered mist drifting across. The current field runs under every screen: 100 % on Home, 60 % on Player, 30 % and slowed on text screens. | [P] A tide line: water rises in a ring; indeterminate is a slow swirl of 2400 per lap. | [I] One pebble resting on wet stone, with one line of text (a default; the tickets don't specify it). | [I] The pebble's core dims to the danger tint (a 150 crossfade). | Library scroll wake (section 3.2). |
| **Wood** | [P] Fireflies wander between the trunks on the `Drift` spring. Fewer on Player. | [F] A seed sprouts a leaf, a 1600 loop. A growth ring fills the render pebble. | [I] A bare clearing with one seedling. | [I] A withered-leaf icon that curls once (300). | **Fireflies to your touch:** [P] on Home, fireflies drift to the last touch point (about 2000), then scatter. Drifting only. **Amber fossil cover:** [I] the cover sits in a resin drop on the mini player and album tiles. It is static art, plus the glow light. |
| **Stone** | Violet smoke drifting in a 40 s parallax loop, a refraction shimmer sweep of 18 s ([A]), fine crystal dust on the `Drift` spring, and the crystals breathing a soft glow in turn (6 s each, staggered). | [F] A crystal grows on a 1200 loop, also used by `PROGRESS_RING`. RM: a static lit ring and a percentage. | [I] One small geode. | [I] A cracked, dull crystal. | **Kintsugi healing:** [P] when the error is fixed, a gold seam draws along the crack (`PathMeasure`, 600). RM: the sealed art appears. **Growing geode:** [F] a crystal point grows on the Home geode per album played through. It plays once (500) on the next Home visit and is static after that. RM: it appears grown. This is the only usage-driven feature, and it is Stone only. **Tinted descent:** section 3.2. |
| **Bioluminescence** | Spores rising at 6–14 dp/s with sway (20–40 of them), and plants breathing light on 6–9 s sines out of phase (70–100 %). | [P] A spore pulsing softly. Determinate progress lights spores one by one. | [I] One dim sprout. | [I] A cap gone dark grey. | **Sleeping jungle:** [P] after 8 s with no touch, the Home pods dim to 70 % (600), and the next touch wakes them with a glow wave. Drifting only. RM: the dim and wake are 150 opacity fades. **Fiddlehead knobs:** [F] the macro knobs uncurl with the value. This is a scrubbed sequence (frame = value), with no time playback. |
| **Fire** | Slow lava flow with the crust cracking and glowing ([A] the glow), and embers and ash drifting up. | [P] One ember brightens and dims on a 1600 loop. | [I] One cold ember. | [I] An ember gone grey ash. | **Cooling-crust render ring:** [P] the crust closes in around the ring with render progress, and the finish plays an ignite. **Toggle-off cooling ping:** the F-A6 sound on toggle off, with the ember cooling (600). |
| **Sun** | The meadow flowers sway in a slow, irregular breeze (two sine layers of 5 s and 8.3 s). Softer on Player. | [P] Sun rays rotating at 6000 per turn. RM: static rays with an alpha pulse every 1200. | [I] One bud in the grass. | [I] A wilted flower. | **Amber inclusions:** [I] a seed or petal inside each control (static art). **A flower per bar tab:** [I] the active flower forms an amber drop (400) and the old drop fades (240). **Sundial-shadow render ring:** [P] a shadow sweeps around with progress. |
| **Moon** | HeroMoonglade: slow parallax of sky, Moon, dunes and moonglade, plus AmbientDust (Home only). | [P] MoonPhaseLoader, one phase cycle every 1600. | [I] A single new Moon (a dark disc with a hairline rim). | [P] EclipseError: a shadow slides over the control (400), leaving a copper rim. | **Earthshine disabled:** [I] disabled controls are lit only by dim ashen earthshine (45 %), not greyed out, with a 150 crossfade into it. **Dust footprints:** [P] taps on Home leave faint dust imprints that fill back in (about 3000, at most 5 at once). RM: they fade by opacity only. |
| **Silence** | A fog layer in parallax at ≤ 2 dp/s. | [P] Breath loader: a single 8 dp bone dot fades 20 %→100 %→20 % over 4000. It never spins. | [I] A single raked line in the sand (a default from idea 9). | [I] The muted clay danger tint, with a 150 crossfade. | **Ensō selection ring** (section 3.1). There is no idle quiet. |
| **Light** | Dust drifting through the beams, and the beams breathing on a 12 s cycle. | [P] An orbiting light point with a fading long-exposure trail (a 1200 loop). Progress is a lighthouse sweep. | [I] One small dim point of light on the dark floor. | [P] The lantern flickers twice over 300, then settles dim with a warm red tint. | **Spectral bar:** [P] a hue per tab, used by the beam focus. **Darkroom safelight:** a red-only palette variant in Settings > Theme. Motion is unchanged, and every glow takes the variant palette. |
| **Night sky** | AmbientSky: a slow sky drift, gentle random twinkle and a rare meteor (one every 25–40 s). | [P] ConstellationLoader: a small constellation draws point by point and fades (a 1600 loop). Progress is 12 star points; indeterminate is a comet (1600). | [I] One faint star over the ridge. | [I] A star gone dark: a grey point with a broken line. | **Personal constellation:** [P] favourites form a constellation; a new favourite links in with a ConstellationLink (520). RM: the star appears linked. **Meteor on finish:** [P] one meteor crosses Home when a render finishes (about 1200, once per finish). RM: none (Studio already shows that the render finished). |
| **Meadow** | Wind waves rolling through the grass and drifting cloud shadows. Very soft on Player. | [P] One seed circles slowly on the wind (a 2400 loop). Progress is a mown spiral. | [I] A mown fairy-ring clearing. | [I] A patch of dry straw-coloured grass with a danger glyph. | **Overscroll bends the grass** (section 3.2). **A rare grasshopper:** [F] a grasshopper hops between the blades near a stone on Home, at most once every 60–120 s. Drifting only, and never on text screens. RM: none. |
| **Bubble** | AmbientRise: bubbles rise and drift at 12–20 s per crossing, with a rare pop. Still stops the drift but keeps the FilmRim; Off stops everything. | [F] InflateLoop: inflates, wobbles and resets (1600), never popping. | [I] One small bubble resting on the dark pool. | [P] PopMist: a popped bubble as a broken droplet ring (500), settling on a dull grey film. | **Double-bubble merge:** [P] two selected chips next to each other merge into a double bubble with a shared wall (360). RM: the merged art appears. **Film-thinning render progress:** [P] the film thins to black with progress, then pops (PopMist) at the finish. |

### 3.4 Haptics and sounds per Element

These are the pack's `haptics` entries (Composition primitive first, waveform fallback as in each ticket) and `StoneSounds` slots. "—" is a declared silence.

| Element | Tap | Select | Toggle on / off | Slider | Error | Sounds (click / confirm / toggle / swoop / error) |
|---|---|---|---|---|---|---|
| Water | `TICK` 0.4 | `SLOW_RISE` 0.3 → `LOW_TICK` 0.5 at about 400 | `CLICK` 0.5 / `TICK` 0.3 | `SLIDER_TICK` | `LOW_TICK` 0.5 → 0.35 at 120 | W-A1 / W-A2 / W-A3 / W-A4 / W-A5 |
| Wood | `LOW_TICK` 0.4 | `LOW_TICK` 0.4 → 0.3 at about 120 | `LOW_TICK` 0.5 / 0.25 | `[0,4]/[0,40]` every 10 % | `[0,18,70,26]` | WD-A1 to WD-A5 |
| Stone | — | `QUICK_RISE` 0.3 → `LOW_TICK` 0.4 at +450 | — | — | — | S-A1 to S-A5 (add the `error` slot) |
| Bioluminescence | `LOW_TICK` 0.4 | `SLOW_RISE` 0.25 → `LOW_TICK` 0.2 at about 300 | `LOW_TICK` 0.5 / 0.3 | `LOW_TICK` 0.15 | `LOW_TICK` 0.4 → 0.3 at 120 | B-A1 to B-A5 |
| Fire | `TICK` 0.45 | `QUICK_RISE` 0.35 → `TICK` 0.3 at about 300 | `CLICK` 0.5 / `LOW_TICK` 0.3 | `SLIDER_TICK` | `DESTRUCTIVE` waveform | F-A1 to F-A5; toggle off is F-A6 (the cooling ping) |
| Sun | `TICK` 0.3 | `SLOW_RISE` 0.3 | `TICK` 0.35 / 0.2 | `LOW_TICK` 0.2 | `TICK` 0.3, 90 gap, `TICK` 0.2 | SU-A1 to SU-A5 |
| Moon | `[0,14]/[0,60]` | `[0,30,40,60]/[0,40,80,30]` | the select pattern | `[0,5]/[0,40]` | `[0,40,60,40]/[0,120,0,60]` | click / confirm / toggle / swoop / error (#128); NAV change `[0,20,120,10]` |
| Silence | `[0,5]` | — (TAP only) | `[0,8]` / — | `[0,3]` at detents only | `[0,10,40,10]` | felt tap / breath settle / — / paper slide / soft drop, **all off by default** |
| Light | `[0,6]` 60 | `[0,8,40,24]` | `[0,6,20,10]` / — | `[0,3]` 40 | `[0,10,25,6]` | A1 / A2 / — / A3 / A5 |
| Night sky | `[0,6]` | `[0,8,60,14]` | `[0,5,30,9]` / `[0,5]` | `[0,3]` per star point | `[0,20,40,10]` | click / confirm / toggle on / swoop / error (#132) |
| Meadow | `TICK` 0.35 | `QUICK_FALL` 0.3 → `QUICK_RISE` 0.4 at 40 | `CLICK` 0.4 / `TICK` 0.25 | `LOW_TICK` 0.2 | `TICK` 0.4 → 0.3 at 70 | ME-A1 to ME-A5 |
| Bubble | `[0,6]` | `[0,6,40,10]` | `[0,4,20,4,20,8]` / — | `[0,3]` per bead | `[0,14,30,6]` | A1 / A2 / A6 / A3 / A5 |

The Hero scene sound loop (the A4 slot, under each ticket's own id) plays under Ambient Drifting only.

---

## 4. The asset contract with Blender

Frame sequences are the [F] cells above. A Blender render that follows this contract drops into a ThemePack with no code change.

### 4.1 Where the files go

| Step | Path |
|---|---|
| The Blender agent writes | `scratchpad\<element>\blender\motion.json`, with the frames in `scratchpad\<element>\blender\frames\` |
| The drop copies them to | `design/src/main/assets/tp/<element>/motion.json` and `design/src/main/assets/tp/<element>/frames/*.webp` |

The frames go in `assets/`, not `res/`, because a sequence is loaded by name from `motion.json` and must not depend on resource ids staying in order. Static state art stays in `res/drawable-nodpi` as `StoneStateArt`, as it is today.

### 4.2 File naming

`tp_<element>_<component>_<state>_<frame>.webp`

- **`<element>`** is the pack's `slug` with `-` removed: `water`, `wood`, `stone`, `bioluminescence`, `fire`, `sun`, `moon`, `silence`, `light`, `nightsky`, `meadow` or `bubble`.
- **`<component>`** is a `StoneComponent` name, lower case with `_` removed (`chip`, `toggle`, `progressring`, `navigationbar`), or a body name shared by several components (`pebble`, `shard`, `cap`, `knob`). A shared body lists its components in `motion.json`.
- **`<state>`** is one of `select`, `on`, `loop` or `scrub`, or the name of an accepted extra (`geode`, `grasshopper`, `fiddlehead`).
- **`<frame>`** is three digits counting from `000`.

No token contains `_`, so a name always splits into exactly five parts: `^tp_[a-z]+_[a-z]+_[a-z]+_\d{3}\.webp$`. An example is `tp_water_pebble_select_000.webp` to `tp_water_pebble_select_023.webp`. The existing Water spin (`spin_0001–0024.png`) is renamed this way at export.

### 4.3 Frame rate, size, density and encoding

| Property | Rule |
|---|---|
| fps | **60** for one-shots of 600 ms or less (`select`, `on`, extras). **30** for loops and scrubbed sequences. The player shows each frame for as many display refreshes as its time covers, so it is never sped up to the display rate and never interpolates between frames, which would ghost a rotation. |
| Density | One set at **3 px per dp** (xxhdpi), with no density folders. Lower-density devices downsample at decode (`ImageDecoder.setTargetSize`). |
| Size | The canvas is the component's largest drawn size in dp plus its bleed (droplets, sparks, wisps), times 3, rounded up to an even number of pixels. The long side is at most **384 px** for controls and **512 px** for Home objects. The component's layout box inside the canvas is recorded as `boundsPx`. |
| Colour | 8-bit sRGB with straight (unassociated) alpha, rendered with `film_transparent = True`. Use the same light rig, camera and view transform as that Element's static state art. |
| Encoding | WebP, lossy at quality 85 with lossless alpha (`cwebp -q 85 -alpha_q 100 -exact`). Fully transparent pixels keep their colour so the edges do not fringe when scaled. |
| Budget | Decoded size ≤ 5 MB for a selection sequence and ≤ 2 MB for a toggle. The WebP files for all of one Element's sequences total ≤ 1.5 MB. |

### 4.4 Seams

- **One-shot (`select`, `on`, extras):** frame `000` matches the `from` state art and the last frame matches the `to` state art, both within the contract test's tolerance (section 6). That way the switch from static art to the sequence and back is invisible. Toggle off plays `on` backwards, so there is no `off` sequence unless an Element needs an asymmetric one.
- **Loop:** frame *N* would be frame `000` again, so it is **not** rendered: the last file is the frame before the wrap. Every cyclic property (rotation, noise offset, emission phase) completes a whole number of cycles over *N* frames. Particles are pre-rolled so frame `000` is mid-flow, not empty.
- **Scrub:** frame `000` is the value 0 and the last frame is the value 1, with the value spread evenly over the frames. There are no seams.

### 4.5 `motion.json`

One file per Element, written by the Blender agent and read by the pack's loader with `org.json` (it is part of Android, so no new dependency).

```json
{
  "schema": 1,
  "element": "water",
  "pxPerDp": 3,
  "source": {
    "blender": "5.2",
    "engine": "CYCLES",
    "script": "scene_components.py",
    "viewTransform": "AgX"
  },
  "sequences": [
    {
      "component": "pebble",
      "appliesTo": ["ALBUM_TILE", "CHIP", "COMPACT_BUTTON", "ICON_BUTTON", "PRIMARY_BUTTON", "SECONDARY_BUTTON", "NAVIGATION_BAR"],
      "state": "select",
      "playback": "once",
      "fps": 60,
      "frames": 24,
      "sizePx": [384, 384],
      "boundsPx": [72, 72, 240, 240],
      "from": "default",
      "to": "selected"
    },
    {
      "component": "toggle",
      "appliesTo": ["TOGGLE"],
      "state": "on",
      "playback": "once",
      "fps": 60,
      "frames": 14,
      "sizePx": [192, 128],
      "boundsPx": [24, 28, 144, 72],
      "from": "default",
      "to": "selected"
    }
  ]
}
```

| Field | Type | Rule |
|---|---|---|
| `schema` | int | `1`. |
| `element` | string | The `<element>` token. It must match the folder name. |
| `pxPerDp` | number | `3`. |
| `source` | object | Provenance only: the Blender version, engine, rebuild script and view transform. The loader ignores it. |
| `sequences[].component` | string | The `<component>` token in the file names. |
| `sequences[].appliesTo` | string[] | `StoneComponent` names that play this sequence. Each component appears at most once per `state`. |
| `sequences[].state` | string | The `<state>` token. |
| `sequences[].playback` | `"once"` \| `"loop"` \| `"scrub"` | How the player walks the frames. It must agree with `state` (`loop` with `loop`, `scrub` with `scrub`). |
| `sequences[].fps` | 30 \| 60 | As in section 4.3. |
| `sequences[].frames` | int | The file count. Files `000` to `frames − 1` must all exist. |
| `sequences[].sizePx` | [w, h] | The canvas size of every frame. It must be even, and at most 384 (controls) or 512 (Home objects). |
| `sequences[].boundsPx` | [x, y, w, h] | The component's layout box inside the canvas. The player scales this box to the component's bounds, so the bleed falls outside them. |
| `sequences[].from`, `to` | `StoneState`, lower case | For `once` only: the state art that the first and last frames match. |

Nothing else goes in the file. Haptic and sound timing are not in it: a haptic fires once on the commit and its Composition timing lands the settle (section 1). The reduced-motion behaviour is in the code.

---

## 5. API sketch

There is one interface on `ThemePack`. It replaces PR #139's `drawn` lambda for effects (not the body art) and the four motion fields #139 added to `StoneMotion` (`inkBloomMs`, `rippleRingMs`, `selectSpinMs`, `scrollWakeMs`). `haptics` stays on `ThemePack`, where #139 put it.

```kotlin
/** How an Element moves. Pure draws of progress, value and phase; never fed audio. */
interface ElementEffects {
    val timings: ElementTimings

    /** Light over a part's art: core, specular, rim. [level] is the animated state glow, 0..1. */
    fun DrawScope.light(component: StoneComponent, state: StoneState, level: Float)

    /** Touch at [origin]; [p] 0..1; [reduced] = fade in place, no spread. */
    fun DrawScope.touch(origin: Offset, p: Float, reduced: Boolean)

    /** What selecting or turning on [component] plays. */
    fun flourish(component: StoneComponent): Flourish

    /** A self-running or scroll-driven layer: a channel, the Library wake, a Hero scene layer, a loader. */
    fun DrawScope.flow(target: FlowTarget, phase: Float, wake: Float)

    /** A slider, seek or knob fill at [fraction] 0..1. */
    fun DrawScope.fill(component: StoneComponent, fraction: Float)

    /** The page-change overlay at [p] 0..1, drawn over both screens. */
    fun DrawScope.transition(p: Float)
}

data class ElementTimings(val touchMs: Int, val selectMs: Int, val toggleOnMs: Int, val toggleOffMs: Int,
                          val transitionMs: Int, val wakeMs: Int)

sealed interface Flourish {
    /** A Blender sequence from motion.json; toggles play it backwards for off. */
    data class Frames(val sequence: FrameSequence) : Flourish
    /** A procedural flourish drawn over the art at progress p. */
    data class Overlay(val draw: DrawScope.(p: Float) -> Unit) : Flourish
    data object None : Flourish
}

enum class FlowTarget { CHANNEL, LIST, HERO, LOADER }

/** One motion.json entry, frames decoded lazily off the main thread into a shared LRU (≤ 24 MB). */
class FrameSequence(val name: String, val fps: Int, val frameCount: Int, val playback: Playback,
                    val sizePx: IntSize, val boundsPx: IntRect) {
    fun frameAt(elapsedMs: Long): Int   // pure: once clamps, loop wraps; unit-tested
    fun frameFor(value: Float): Int     // scrub
}
```

`ThemePack` gains `val effects: ElementEffects`. `CrystalTheme` provides `LocalReducedMotion` (from PR #140's `ProvideReducedMotion`; #139's copy of the same local is dropped when the two land) and a `LocalAmbient` from the Ambient setting.

### The three modifiers

| Modifier | Consumes | Behaviour |
|---|---|---|
| `Modifier.elementTouch(interactionSource)` | `effects.touch`, `timings.touchMs`, `LocalReducedMotion` | It is `Modifier.indication(interactionSource, ElementTouch(effects))`, one `IndicationNodeFactory` with `equals` and `hashCode`, replacing #139's `InkBloomIndication`. The node owns one `Animatable`, collects `Press` (`snapTo(0)` then `animateTo(1)`) and `Cancel` (a 120 ms fade), and draws `touch(origin, p, reduced)` clipped to the control's cached outline `Path`. It draws nothing at `p ≥ 1`. |
| `Modifier.elementSelect(selected, component)` | `effects.flourish`, `effects.light`, `timings.selectMs`, `LocalReducedMotion` | It is one `Animatable` started on the rising edge of `selected` (or of `checked`, for toggles; going back down plays toggles backwards from the current frame). `Frames` draws `frameAt(elapsed)` mapped from `boundsPx` onto the bounds. `Overlay` draws over the content. In both cases `light(…, level)` reads the level in the draw phase. Under reduced motion it skips to `p = 1` and crossfades the glow over 150 ms. It replaces #139's `stoneSpin`. |
| `Modifier.elementFlow(target, scroll = null)` | `effects.flow`, `timings.wakeMs`, `LocalReducedMotion`, `LocalAmbient` | The phase comes from one `rememberInfiniteTransition`, which the system's "Remove animations" already freezes. Its draw is gated on: the lifecycle being `STARTED`; `LocalAmbient` (Still freezes the phase, Off skips the draw), for `HERO` only; and reduced motion (frozen). With `scroll`, a `NestedScrollConnection` writes the wake into one `Animatable` from one coroutine. It replaces #139's `stoneScrollWake`. |

Press and release stays `stonePress`. Slider fill is a call to `effects.fill` inside `CrystalSlider`'s existing `Canvas`. The tab transition is `effects.transition(p)` drawn over `AnimatedContent` in `AppShell`, with `timings.transitionMs`, or a 150 ms fade under reduced motion. None of these needs a new modifier.

**Left out on purpose:** a particle system (each Element's particles are a few dozen sprites in its own `flow`), per-effect shader classes, a `RenderEffect` path, a generic timeline or keyframe format, and haptic or sound cues inside `motion.json`. Add one only when a reviewed design can't be met without it.

---

## 6. Testing

All of this runs in CI only (`docs/ci.md`). Goldens are re-recorded with `[record-goldens]`, and every new golden also goes through the no-glass visual review.

### 6.1 Screenshot goldens at key frames

- **One sheet per Element:** `ElementMotion_<element>.png`, a Roborazzi test in `app/src/test` named `*ScreenshotTest` so that mutation runs skip it. Each row is a kind. Its columns are `p = 0`, `0.5` and `1`. Procedural draws are called directly at those values, because they are pure functions of `p`. Frame sequences are drawn at frame `000`, the middle frame and the last frame. That is 12 goldens rather than hundreds.
- **Animated composables** (`elementSelect`, `elementTouch`, the transition) are checked with the Compose test clock: `mainClock.autoAdvance = false`, then `advanceTimeBy(selectMs / 2)` and capture.
- **AGSL:** Robolectric's `ShadowNativeRuntimeShader` stops at SDK 34 (research finding 5). The [A] layers are shot at `@Config(sdk = [34])`, and the [P] fallbacks at the repo's usual SDK 36. A shader's compilation is also covered by one instrumented test on the emulator job.
- **The asset contract test** is a unit test in `design/src/test`, which counts towards the mutation floor. For every `assets/tp/*/motion.json` it checks:
  - the schema fields and enums;
  - that every file name matches the section 4.2 regex;
  - that each sequence has exactly `frames` files;
  - that each frame's size is `sizePx`, within the caps;
  - for `once`, that frame `000` and the last frame match the `from` and `to` state art within a mean per-pixel ΔE of 2;
  - for `loop`, that the last frame is not a byte copy of frame `000` (a duplicated seam).

### 6.2 Reduced motion

- **Reduced-motion goldens:** the same sheet rendered with `LocalReducedMotion provides true`. Every column must equal the `p = 1` end state, apart from the 150 ms fades, which are captured at their end.
- **No loop keeps running:** with reduced motion **on**, Ambient Drifting, Home composed and a loader visible, `composeRule.waitForIdle()` must return. An infinite transition that ignores reduced motion keeps the clock busy and fails the test. The same test is run with Ambient Off and reduced motion off, to prove the Hero scene gate.
- **End state on the first frame:** with reduced motion on, click a bar tab and a toggle, advance one frame (`mainClock.advanceTimeByFrame()`), and compare against the selected and on end-state images.
- **Channels survive:** a fake haptics and sound seam records that the `SELECT` and `confirm` cues still fire once with reduced motion on, and not at all when the switches are off.
- **Unit tests:** `isMotionReduced` (PR #140), `FrameSequence.frameAt` and `frameFor` (clamp, wrap and scrub edges), and `wakeAfter`.

### 6.3 Frame-time checks

- **Macrobenchmark** on the CI emulator job, as new CI work, ideally together with the Baseline Profile the research note recommends. It uses `FrameTimingMetric` over three journeys:
  1. Home idle for 10 s with Ambient Drifting (the Hero scene loop);
  2. a Library fling up and down, five times (the scroll wake and the list);
  3. tapping through all five bar tabs twice (touch, selection and the tab transition).
- **The gate**, per Element pack: `frameDurationCpuMs` P50 ≤ 6 ms and P95 ≤ 12 ms, and `frameOverrunMs` P95 ≤ 0. Emulator times are noisy, so the first green run records each journey's baseline and later runs fail if they get more than 20 % worse. That makes the gate a ratchet, like the mutation floors.
- **Allocation:** one Robolectric test draws each effect for 60 frames inside `drawWithCache` and asserts that the cache block ran once. That proves no `Path`, `Brush` or `RuntimeShader` is rebuilt per frame.

---

## 7. Open points for the owner

| Point | Default in this spec |
|---|---|
| Moon's Moonrise transition is 600 ms, over the 450 ms flag for a tens-a-day transition. | Kept at 600 as designed. It never blocks input, and a tap jumps it to the end. |
| The research note (§3.10) says Blender renders are not in the pipeline. #105 now ships Blender cutouts and the spin, and this spec uses Blender frame sequences. | Blender is in the pipeline for [F] cells. The research line is superseded. |
| Water's, Silence's and Meadow's empty and error looks are only partly specified in their tickets. | The defaults in section 3.3, marked there. |
| Fire's heat haze "ripples" the old screen, which would need content sampling (banned). | It is an additive shimmer band over the fading old screen, never a distortion. |
| PR #140 reads the animator duration scale once, so changing it while the app runs is missed. | `ProvideReducedMotion` should observe the setting (a `ContentObserver` on `ANIMATOR_DURATION_SCALE`) when the effects land. |
