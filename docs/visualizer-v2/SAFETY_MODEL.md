# Safety model

What the app does about flashing and motion, why each limit exists, and what is still missing.
Required by the V2 master plan (not in this tree) §2.2; the policy is §11.

**Status:** the parameter-level clamp is production, tested, and **unconditional** — there is no
setting, no off switch and no tier. The *measured* global limiter of §11.2 is still not built, so
three render paths remain outside it. See "What this does not cover".

---

## 1. What the app is protecting against

Two different harms, with different controls, deliberately not merged:

| Harm | Control | Governed by |
|---|---|---|
| Photosensitive seizure | flash rate and full-frame luminance depth | WCAG 2.3.1, three flashes per second |
| Vestibular discomfort | speed, drift, shake, rotation, endless zoom | reduced motion |

The flash clamp is not "turn the music response off". Colour, texture, density and slow
structural movement keep reacting; what is bounded is the impulsive full-frame event.

## 2. There is no user choice

The flash clamp is not configurable. `VisualSafety` holds the numbers as constants —
`WCAG_FLASHES_PER_SECOND = 3`, `MAX_FLASH_DEPTH = 0.25` — and every render path applies them the
same way. There is no `SafetyConfig`, no `enabled` flag and no way to construct an unclamped
render. A preview that is safe therefore cannot produce an unsafe file: same clamp, same code,
same numbers, on screen and in export alike.

The first-run notice reflects that. It takes an acknowledgement, not a decision: it says the
visuals are intense and that the rate is limited in the engine by design.

**Reduced motion stays a choice.** It is a vestibular comfort preference, not a photosensitivity
guard, so it remains a switch in Settings while the flash clamp does not.

Migration: anyone who answered the old three-way question has already seen the notice and is not
asked again, and a stored `REDUCED_MOTION` answer carries over to the reduced-motion switch.

## 3. Where the limits are applied

`VisualSafety.apply` runs **last**, after `LfoEngine.apply` and `AdsrEngine.apply`, on the params
a scene is about to receive. Order is the whole design: a safe stored value is worth nothing if a
modulator can push it back into the hazardous range.

| Lever | What it bounds |
|---|---|
| `apply` | glitch, bloom, brightness, intensity, contrast |
| `limitLfoRate` | an LFO aimed at a luminance target; the rate is the hazard, not the amplitude |
| `beatMinIntervalMs` | the floor between beat-driven hits |
| `layerMix` | ADD and DIFFERENCE blends, which reach the screen after `apply` |
| `transitionStyle` / `transitionId` | a hard cut becomes a crossfade |
| `FlashLimiter` | the finished frame itself — see below |
| `BeatEnvelope` | the beat pulse every Style reads rises over 0.1 s instead of jumping |

There is no Strobe and no Flash parameter any more (#89); presets that carry them still load and
the values are ignored.

**The No-flash limiter.** `core/viz/FlashLimiter` (pure, host-tested in
`core/tests/flash_limiter_test.cpp`) bounds what reaches the screen, whatever produced it: every
shader and field Style, MilkDrop, Layers, transitions and the composite's effects.
`NoFlashPass` draws the composite into a candidate target, averages it on the GPU into a 4x4
grid of linear relative luminance and saturated-red value (`noflash_measure_frag.glsl`), reads
those 16 texels back, and asks the limiter how much of the new frame may be shown. The limiter
follows WCAG 2.3.1: a transition is a change of at least 0.1 in relative luminance with the
darker state below 0.8, or of at least 20 in (R - G - B) * 320 over saturated red; a flash is a
pair of them. It tracks the whole frame and the nine overlapping quarter-of-the-frame regions of
the grid, each with its own budget of six transitions in any one second. While a budget is
spent, the frame is crossfaded in linear light from the one shown before toward the new one by
just enough that no spent area completes another transition (`noflash_mix_frag.glsl`). Normal
motion changes an area a few times a second at most and never reaches a budget, so it passes
unchanged. The readback is synchronous (16 texels), which costs the CPU/GPU overlap of one frame.

Inversion and solarize are deliberately **not** forced off. A statically inverted picture is not a
flash; the hazard is toggling it quickly, which the rate limits above already bound. Forcing them
off would only produce a dead control.

## 4. What this does not cover

The limiter measures region means on a coarse grid, not WCAG's per-pixel area rule: a flash
confined to well under a sixteenth of the frame, or a fine pattern, is not seen. Its red measure
is exact for the mean but the crossfade's effect on it is only approximately linear. The overlay
layer (cover art, text) is drawn after the limiter and is not measured. Also absent: the
alternating-stripe vectors, safety telemetry in debug builds, and the thermal half of §11.4.
