# App UI design system: frosted glass

This is the brief for the **App UI** (the app's own screens and controls). It never applies to the **Visual**, which the engine draws, and the App UI never reacts to the music (`CONTEXT.md`). Everything here lives in the `:design` module, package `dev.geode.design.glass`, as named tokens and shared components (ADR-0005).

## Direction

The owner's words (#265, 2026-10-25): "not 3D but simple: classic frosted, translucent, matte glass — thoughtful and meaningful", then "classic, working, user friendly" and "nice and friendly". Frosted glass replaced the Element skins (#333); this reverses #102/#180's "no glass, no live blur" rule. The technique, cost and legibility numbers come from the #270 research.

## Decided

- **One look, light or dark.** Settings > Theme: Match phone (the default), Dark or Light. Night glass is slate over blue-black; day glass is white over pale mist (`GlassPalettes`).
- **The material.** A backdrop, clamped toward the theme's base so only 35% of it shows, then a glass tint, a 4% grain (overlay-blended, so contrast is unchanged) and a 1 dp edge that catches the light at the top (`glassSurface`).

  | Kind | Blur (live) | Tint | Matte |
  |---|---|---|---|
  | Card | 24 dp | 0.60 | 0.94 |
  | Bar | 32 dp | 0.60 (or the person's Bar opacity, never less) | 0.94 |
  | Sheet | 40 dp | 0.65 | 0.96 |

- **What sits behind the glass.** The playing song's art, blurred once and clamped, behind Home and the plain player (`ArtworkBackdrop`). A calm, still backdrop of soft colour fields behind Music, Studio and Settings (`CalmBackdrop`). The live Visual behind the Visuals tab and full-screen mode. Settings > Theme > Backdrop (#398) swaps the art and the fields for one calm backdrop everywhere the Visual is not: Mist, Dusk, Aurora or Plain, the base and grain alone (`GlassBackdropChoice`).
- **Accent tint.** Settings > Theme > Accent (#398) picks the glass highlights' tint: Sky (the palette's own), Teal, Moss, Violet or Rose, each with a night and a day form (`GlassAccent`). The warm second accent stays.
- **Live blur only where it earns it.** The Bar (with the mini-player on the same slab) and sheets frost the page live through Haze 2.0.1 (`GlassSource`); everything still is baked. At most two live blurs per screen. Glass never sits inside the content it frosts, and the live Visual is never a Compose source.
- **Glass over the live Visual.** Compose cannot see the Visual (a GL surface), so glass over it is matte until the engine frosts it (#349). Such glass reports where it is through `VisualFrost` (geometry only, never audio) and fades in and out, never slides: panels cross-fade (`GlassCrossfade`) and a sheet over the Visual is drawn in place and fades (`GlassVisualSheet`). Nothing is drawn straight on the Visual: titles, paths and warnings over it sit on glass or a filled container too.
- **Tiers.** Full (live and baked blur), Calm (baked only: battery saver, thermal status moderate or worse, or Visuals off) and Matte (no translucency: Reduce transparency, Android's high-contrast text or medium/high contrast, or a low-RAM device). Android gives apps no reduce-transparency setting, so Geode has its own (`GlassTiers`).
- **Legibility.** Every text role keeps WCAG AA (4.5:1) on every ground it can sit on: the base, each kind of glass over the worst clamped backdrop (white in dark, black in light), matte glass over the worst raw backdrop, and the calm backdrop's fields; control outlines keep 3:1. The person's accent tint, backdrop, accent intensity, text colour, background dim and bar opacity are adjusted so this always holds in every tier (`GlassContrastRules`, `GlassStyle`, `GlassColorSchemeTest`).
- **Classic controls.** Material 3 shapes and behaviour: a bottom navigation bar (a rail on wide screens) with every place labelled, plain top bars, lists, chips, switches, sliders, sheets and dialogs, at least 48 dp to touch. Icons come from Material's one rounded set. Type is Inter on Material's own scale; the person's text size scales it. Corners follow Settings' corner choice.
- **Friendly states.** Empty, loading and error states show a short title, one helpful line and at most one action (`GlassMessage`, `GlassLoading`, `GlassNotice`). Notices float over anything and are read out politely.
- **Haptics, never sounds.** One light set (#398): a press on every button, card, chip, list row and place on the Bar, a toggle on every switch and checkbox, and gentle ticks while a slider is dragged, at each stop or every twentieth of its range (`GlassHaptics`, `GlassTicks`). Screens use the glass components, or `rememberPressing`, `rememberToggling`, `rememberTicking` and `rememberRangeTicking` where a Material control stays (#417; `HapticsKonsistTest` checks the sliders, switches and checkboxes). They go through the view's own touch feedback, so Android's touch-feedback setting turns them off, and need no permission. The App UI plays no sounds.
- **Motion.** Calm fades and Material's own motion: places cross-fade, except into and out of Visuals, whose live Visual cannot fade with the page. Reduced motion (the app's switch or Android's "remove animations") snaps everything to its end.
- **Never** glass on glass more than two layers deep, refraction or 3D ("not 3D").

## Open

- The engine frost of the live Visual (#349).

Research and references: the #270 research note.
