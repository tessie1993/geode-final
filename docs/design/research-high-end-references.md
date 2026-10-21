# Geode App UI: high-end design research

Research for the brief in `docs/design/app-ui-design-system.md` (water as the main element, forest and stone, frosted stone glass, pebble shapes, floating assets, three themes, dark default with a sunlit-meadow light mode).

The owner's rule applies throughout: **always high end, with no low-quality procedural art.** Every recommendation below assumes art-directed, hand-made or professionally rendered assets. Runtime code is used only to *present* those assets (blur, refraction, parallax, springs). Code never invents the art.

> **How the references were checked.** Awards, platform guidance and library facts were checked against search results on 2026-10-21; the sources are listed in each entry. Dribbble and Behance block reliable search indexing, so I give designer and studio profile URLs rather than made-up shot IDs. Open each profile and pick the current top shots. The Mobbin entries are shipped apps, so search Mobbin by app name; deep links to Mobbin need a login.

---

## 0. Two findings from the codebase that shape everything below

1. **The Visual is a `GLSurfaceView`** (`engine/scenes/src/main/kotlin/dev/geode/render/VisualizerView.kt`). SurfaceView content is composited at the window layer, so **no Compose backdrop blur (Haze, RenderEffect) can sample it**. Frosted glass over the live Visual is therefore not achievable as real blur. The brief's choice of a "soft gradient at the bottom" of the Player is the right one, and it is also what Apple Music and Endel do. Keep glass for the bottom bar, sheets and Library, where the backdrop is Compose-drawn art.
2. **minSdk is 26** (`app/build.gradle.kts`). `RenderEffect` blur needs API 31 and `RuntimeShader` (AGSL) needs API 33. Every glass and ripple effect needs a designed fallback tier (section 3). Note that a worktree under `.claude/worktrees/` shows minSdk 31, so a raise may be in flight. If minSdk becomes 31, only the AGSL tier still needs a fallback.

---

## 1. References (18)

| # | Reference | URL | Why it is high end | Informs |
|---|---|---|---|---|
| 1 | **Apple Liquid Glass** (iOS 26, WWDC25 "Meet Liquid Glass") | https://developer.apple.com/videos/play/wwdc2025/219/ · HIG Materials: https://developer.apple.com/design/human-interface-guidelines/materials | The material adapts its tint, shadow and light/dark state to what is behind it. Larger panels get "thicker" glass with deeper shadows. Glass is reserved for the **controls/navigation layer, never content**. It honours Reduce Transparency, Increase Contrast and Reduce Motion. Apple had to raise opacity after beta legibility complaints, which is a lesson in itself. | Glass, legibility rules, bottom bar |
| 2 | **Material 3 Expressive** (Android 16) | https://m3.material.io/ · `MaterialShapes`: https://developer.android.com/reference/kotlin/androidx/compose/material3/MaterialShapes · `androidx.graphics.shapes` | Shape morphing between normalised `RoundedPolygon`s (cookie, pill, gem, and so on), **spring-based motion schemes** (standard vs expressive) in place of bezier easing, and emphasised type. It is the native Android vocabulary for "springy" motion and irregular shapes. | Shapes (pebble outlines as custom `RoundedPolygon`s + `Morph`), motion |
| 3 | **Apple Music on iOS 26** (shipped; on Mobbin) | https://www.apple.com/apple-music/ | A floating Liquid Glass tab bar with the mini-player docked above it. Full-screen animated artwork sits behind the Now Playing controls, which sit on a soft dark gradient, not on glass. This is the exact pattern Geode's Player needs. | Player, bottom bar |
| 4 | **Endel** | https://endel.io | A generative-sound app with a strict, quiet UI: soft white type on a dark field, with the moving visual as the hero and controls kept minimal. It is an App Store Editors' Choice and a model for "Visual behind controls" with nothing competing. | Player, restraint, typography |
| 5 | **Calm** (Google Play Best of 2025 – Best for XR) | https://www.calm.com | Its backgrounds are **real filmed nature** (the lake, the rain) rather than illustrations. That is the shipped proof that photographic/filmic water reads as premium and procedural water does not. | Water, floating assets (filmed/rendered over generated) |
| 6 | **Oak** (Kevin Rose) | https://www.oakmeditation.com | A near-black UI with one calm accent, big generous type and very few elements per screen. It shows that dark "forest" themes need restraint more than decoration. | Dark theme discipline, light-touch Library |
| 7 | **Opal** (ADA 2025 Social Impact finalist) | https://www.opal.so | Its brand is built on **art-directed rendered gems**: physically lit 3D objects with real refraction, used sparingly as hero art. This is the direct analogue for Geode's rendered pebbles, droplets and stones. | Floating assets, icons/badges, premium dark |
| 8 | **Gentler Streak** (ADA 2024 winner, Social Impact) | https://gentler.app | Hand-drawn character art and soft organic charts, with warm light-mode palettes that still meet contrast. Its "hand-drawn feel" is achieved by drawing carefully, not with jittered paths. | Icons (hand-drawn-feeling line), light mode |
| 9 | **Lumy** (ADA 2025 Delight & Fun finalist) | Apple newsroom: https://www.apple.com/newsroom/2025/06/apple-unveils-winners-and-finalists-of-the-2025-apple-design-awards/ | A sun/daylight app whose palettes shift with the light. A good model for a "sunlit meadow" light mode that is warm rather than clinical white. | Light mode |
| 10 | **Rooms** by Things, Inc. (ADA 2024 Visuals & Graphics) | https://rooms.xyz | Tactile, toy-like 3D with consistent lighting and a single art direction across thousands of objects. It shows how to keep a *set* of 3D assets coherent. | Floating assets coherence, materials |
| 11 | **(Not Boring) apps** (Andy Allen / Andy Works) | https://www.notboring.software | Art-directed 3D UI materials, plus haptics and sound designed as a craft (each control has a designed haptic). It is the best reference for "watery haptics" being authored rather than default clicks. | Haptics, materials, delight |
| 12 | **Igloo Inc** (Awwwards Site of the Year 2024) | https://www.igloo.inc | Ice and glass rendered with real refraction, caustics and edge light, plus particles that are clearly art-directed (sized, lit and placed by hand). It is the high bar for the "frosted stone glass" look and for motes. | Glass art direction, floating assets |
| 13 | **Lusion** (studio; many Awwwards SOTD) | https://lusion.co | Fluid/water WebGL work in which motion is damped and physically plausible, never wobbly. Use it as art direction for ripple timing and refraction strength. | Water, ripples, motion |
| 14 | **Milkinside / Gleb Kuznetsov** (one of Dribbble's most-followed designers) | https://dribbble.com/glebich · https://milkinside.com | Rendered liquid-glass forms, organic shapes and soft lighting at a product-render level. Look for the "glass / liquid / organic" shots. | Glass, pebble shapes, floating assets |
| 15 | **Sky: Children of the Light** (thatgamecompany; Apple Design Award 2020) | https://www.thatskygame.com | Painterly light, drifting light motes and glowing creatures, all hand-directed. It shows bioluminescence done with restraint: few lights, soft falloff, darkness preserved. | Night forest + bioluminescence, motes, depth of field |
| 16 | **Ori and the Will of the Wisps** (Moon Studios) — art direction only | https://www.orithegame.com | The gold standard for glowing-forest art: layered painted parallax planes, atmospheric depth, teal/yellow glow against green-black. | Night forest palette, parallax layering |
| 17 | **Spotify "Your Library"** (shipped; on Mobbin) | https://open.spotify.com | Filter chips (Playlists / Artists / Albums), sort, and a **grid ↔ list toggle** at the top of the Library. This is the proven pattern for the brief's "quick list toggle for big libraries". | Library |
| 18 | **Stoic** (App Store Editors' Choice) | https://www.getstoic.com | Calm journaling UI with warm paper-like light mode and confident serif headings. A reference for an earthy, literate light theme. | Earth + lichen light, typography |

Curation sources for continued browsing: Godly (https://godly.website), Land-book (https://land-book.com), Awwwards collections tagged WebGL/3D (https://www.awwwards.com), Mobbin (https://mobbin.com: search Endel, Calm, Apple Music, Spotify, Opal, Oak, Gentler Streak), and the Apple Design Awards pages for 2024 and 2025 (newsroom links above).

---

## 2. Art direction per theme

Contrast was computed with the WCAG 2.x formula (script at `/tmp/claude-0/-home-user-geode-final/cc133685-cf7e-5e72-8be4-c06c600e242c/scratchpad/contrast.py`). "Glass" means the glass tint at 78% over the theme base. "Worst" means that glass over a pure-white backdrop in dark mode or a pure-black one in light mode, which is the case when a bright mote or artwork sits right behind a panel. **Rule derived from the numbers: glass that carries text needs an effective opacity floor of 88%** (blur + tint + scrim combined). At 78% secondary text drops to about 3.6:1 over bright backdrops, which fails AA. Haze/Liquid Glass-style "adaptive tint" (raise opacity when the backdrop luminance rises) is the high-end way to meet this. A fixed thin glass is the cheap way.

### 2.1 Deep forest + stone (default)

- **Mood:** standing in an old pine forest at dusk beside a slate stream. Quiet, cool and grounded, with a single warm lantern.
- **Materials:** slate and granite glass (cool grey-green with fine mineral grain), wet moss, polished river stone, amber resin highlights.
- **Lighting:** low, soft, top-left key, with a cool ambient fill. Edges catch a thin cool rim. Amber is used only as a "lit" highlight (active state, now-playing).

| Role | Dark | Light ("sunlit meadow": cream + soft green) |
|---|---|---|
| Base | `#0D1A14` pine-black | `#F4EFE2` warm cream |
| Glass tint | `#24302B` slate (≥88% effective) | `#FBF8F0` milk-stone (≥88%) |
| Text primary | `#EEF1EA` (15.7 base / 12.9 glass / 8.2 worst@88%) | `#1C2A20` (13.1 / 13.9 / 10.7) |
| Text secondary | `#B9C4B8` (9.9 / 8.1 / 5.2) | `#4A5A4C` (6.4 / 6.8 / 5.3) |
| Accent moss | `#A9C77F` (9.5 / 7.8 / 5.0) | `#4B6828` (5.5 base / 4.5 worst) |
| Highlight amber | `#E8B15A` (9.3 / 7.6 / 4.9) | `#8A5212` (5.6 / 5.9 / 4.6) |
| On-accent text | `#0D1A14` on moss = 9.5 | `#F4EFE2` on `#4B6828` = 5.5 |

### 2.2 Night forest + bioluminescence

- **Mood:** a forest after midnight. Near-total darkness, with glowing fungi, teal water and fireflies. Wonder, not rave.
- **Materials:** wet black bark, dark obsidian-like glass, luminous moss, fungi gills, droplets that hold light.
- **Lighting:** the light *comes from within* (accents emit). The glass gets a soft teal inner glow and no white rim. Glow is used rarely; the darkness has to be preserved (see Sky and Ori). Glow halos are rendered sprites, not runtime blur stacks.

| Role | Dark | Light ("dawn mist" counterpart) |
|---|---|---|
| Base | `#060D0B` green-black | `#E9F1EC` misty pale green |
| Glass tint | `#122019` | `#F7FAF7` |
| Text primary | `#E6F4EF` (17.3 / 15.5 / 10.4 worst@88%) | `#10241F` (14.1 / 15.1 / 11.7) |
| Text secondary | `#A7C2B9` (10.3 / 9.2 / 6.2) | `#3F5952` (6.6 / 7.1 / 5.5) |
| Accent teal glow | `#5CE6CF` (12.8 / 11.4 / 7.6) | `#08655A` (5.0 worst) |
| Accent firefly | `#F2E36B` (14.9 / 13.3 / 8.9) | `#6E5A00` (5.8 / 6.3 / 4.9) |
| On-accent text | `#06231D` on teal = 10.8 | white on `#08655A` ≈ 6.6 |

Note: limit firefly yellow to small points (now-playing dot, motes). Large yellow fields look cheap.

### 2.3 Earth + lichen

- **Mood:** a basalt outcrop in late autumn, with umber soil, pale lichen on stone and rust leaves. Warm, tactile, slow.
- **Materials:** basalt and sandstone glass (warmer, coarser grain), lichen crusts, rusted leaf, clay.
- **Lighting:** warm low sun from the side, with longer soft shadows than the other themes. The glass edge catches a warm rim.

| Role | Dark | Light ("sandstone noon" counterpart) |
|---|---|---|
| Base | `#18120E` umber-black | `#F3ECE0` sandstone |
| Glass tint | `#2C2622` basalt | `#FBF7F0` |
| Text primary | `#F1EBE2` (15.7 / 13.3 / 8.6 worst@88%) | `#2A1F17` (13.7 / 14.8 / 11.4) |
| Text secondary | `#C7BBAA` (9.8 / 8.3 / 5.4) | `#5A4A3C` (7.2 / 7.8 / 6.0) |
| Accent lichen | `#C9D99E` (12.3 / 10.4 / 6.8) | `#4F6024` (4.9 worst) |
| Accent rust (text use) | `#F0A57C` (9.2 / 7.8 / 5.1). **Do not** use `#E08A5C` for text: it drops to 2.75 over bright glass | `#9A4720` (5.4 / 5.9 / 4.5) |
| On-accent text | `#18120E` on lichen = 12.3 | `#FBF7F0` on `#9A4720` ≈ 6 |

**Contrast rules for every theme:** body text ≥4.5:1 against the *worst plausible* glass composite, not just the base. Icons and control outlines ≥3:1. Honour the system "high contrast text" and an in-app "reduce transparency" setting by going to opaque glass (Haze 2 betas add reduced-transparency and higher-contrast handling).

---

## 3. Performant frosted glass and water ripples on Android

### 3.1 Frosted stone glass: how top teams build it

The look is built as a **stack of layers, most of them pre-made**:

1. **Backdrop blur.** Use **Haze** (Chris Banes, `dev.chrisbanes.haze`, https://chrisbanes.github.io/haze/), the de facto Compose backdrop-blur library. `hazeSource` goes on the background art and `hazeEffect` on each glass panel. It uses `RenderEffect` on API 31+ and degrades to a translucent scrim below that. Haze 2.x adds `haze-glass` (`GlassStyle`), plus reduced-transparency and higher-contrast accessibility handling. Pin a stable version, since 2.0 was in beta as of this research. Alternatives: skydoves **Cloudy** and Kyant's **AndroidLiquidGlass** (AGSL lens refraction on API 33+).
2. **Tint + adaptive opacity.** This is the theme's glass colour at 78–92%. Raise it when the content behind is bright (sample a downscaled luminance once per scroll-settle, not per frame).
3. **Stone grain.** This is a **baked, tileable texture from a scanned or photographed stone** (or one authored in Substance 3D Designer by an artist) at 512–1024px, overlaid at 3–6% opacity with a soft-light/overlay blend. One texture per theme: slate, obsidian, basalt. Never runtime noise.
4. **Inner glow + edge light.** Use a 1dp inner stroke with a gradient (about 16–20% light at the top-left edge fading to 0 by mid-edge), plus a faint inner shadow at the bottom. The shape follows the pebble outline. An optional, very subtle highlight shift on device tilt is the Liquid Glass "light catching" cue; keep it within a few degrees.
5. **Shadow.** Use a soft coloured shadow (tinted with the theme base, never pure black), large radius and low opacity.

**Budget and performance practice:**
- Blur the backdrop at reduced resolution (Haze does this). Use a blur radius of about 20–32dp. Higher radii turn the backdrop into mud and cost more.
- Keep at most about two live blurred surfaces on screen (bottom bar + one sheet). Library cards should *not* each blur live. Give cards a **pre-blurred copy of the static background art** (baked offline per theme) clipped to the card. This looks identical and costs nothing.
- For static screen backgrounds, ship pre-blurred variants of the art at build time instead of blurring at runtime.
- **The Visual (GLSurfaceView) cannot be blurred by Compose.** Over the Player, use the gradient scrim the brief already specifies. If glass over the Visual is ever wanted, the engine would have to render a downsampled blurred copy into its own surface. That is engine work, outside the App UI.
- Fallback tiers: API 33+ gets full glass + AGSL ripples; API 31–32 gets glass blur without refraction ripple; API 26–30 gets an opaque-ish tinted stone panel (about 94%) with the baked grain and edge light. Designed this way, the fallback still looks premium; it just has no blur.

### 3.2 Water ripples on touch

- **Technique:** an AGSL `RuntimeShader` applied with `RenderEffect.createRuntimeShaderEffect` (API 33+) to the touched glass layer only. The shader **displaces the sampled content** with a radially damped sine (one or two rings), adds a faint highlight on the crest, and decays over about 600–900ms. Set the amplitude to 2–4dp of displacement, with no colour fringing.
- Drive time from the Compose frame clock. Animate only while a ripple is alive, then remove the effect so the layer is cached again.
- Reference implementations to read (not copy blindly): the `RuntimeShader` ripple gist by samoylenkodmitry, `mejdi14/android-agsl-shader-playground`, and Kyant's AndroidLiquidGlass for refraction.
- **Fallback (API < 33):** a **pre-rendered ripple ring** (a rendered refraction-ring sprite, art-directed) that is scaled and faded on the touched surface. It still looks like water.
- **Dew on glass:** use **rendered droplet sprites** (macro-photographed or 3D-rendered with real refraction), placed by the designer at fixed anchor points per component type. Do not scatter them randomly.
- **Accessibility:** with "remove animations" on, show a 150ms opacity pulse instead of the ripple. Haptic ripples follow the haptics setting independently.

### 3.3 What makes glass and water look cheap (avoid)

- The "glassmorphism template" look: 20% white fill, 1px solid white border, blur over a rainbow gradient blob.
- Glass stacked on glass, and blur used on content rather than controls (Apple's own rule).
- Thin glass that fails contrast over bright art. Thin glass with drop shadows in pure black.
- Chromatic aberration or rainbow edges, and over-strong refraction that warps text.
- Gradient banding in dark themes. Ship a **baked dither/grain texture** in the background art and avoid long 8-bit gradients across large areas.
- Ripples on every scroll or every frame. Wobbly, underdamped motion that keeps oscillating ("jelly").
- Jank from per-frame blur invalidation. Profile with Macrobenchmark/JankStats in CI.

---

## 4. Floating assets (leaves, pebbles, spores, droplets, light motes)

### 4.1 How high-end products do ambient decoration

- **Pre-rendered, art-directed sprites.** Opal's gems, Igloo's ice shards and Calm's filmed scenes are all authored by artists. The app only moves them.
- **Few, large, slow.** About 5–12 assets on screen, at most, with deliberate composition (a mostly empty centre and assets near edges and corners). Motion is slow (tens of seconds per traverse) with eased drift and gentle rotation.
- **Depth of field.** Each asset is rendered at **three depth tiers**: a sharp mid-ground; a softly defocused, larger, more transparent foreground; and a small, desaturated, hazy background. Defocus is **baked into the render** (real lens bokeh from Blender/Cycles, Redshift or Octane), not blurred at runtime.
- **Parallax.** Assign 3–4 depth planes, each with its own drift speed and scroll response (Ori's layered planes). Optional tilt parallax via the rotation-vector sensor, a few dp at most and damped. It switches off with *still/off*, battery saver or reduced motion.
- **Lighting consistency.** All assets for a theme are rendered under **the same HDRI and key-light direction** as the theme's glass edge light (top-left key; warm side light for Earth). That is what makes Rooms-style sets look like one world.
- **Night theme glow.** Glow halos are part of the rendered sprite (emissive + bloom in render) with an additive blend. No runtime glow stacks.
- **Asset format.** WebP (lossy with alpha, quality 85–90) at 1×/2×/3×, with sprite atlases per theme. Pre-multiplied alpha avoids dark fringes. Budget about 1.5–3 MB per theme. Optionally ship short **AVIF/WebP animated loops** for a fluttering leaf, but static sprites with code-driven drift are usually better.
- **Still mode** shows a composed, art-directed still arrangement (a designed "plate"), not a frozen random frame.

### 4.2 Sourcing and production

| Route | Use for | License notes |
|---|---|---|
| **Commissioned 3D renders** (recommended primary) — an artist in Blender with the briefed HDRI, rendering pebbles, droplets, leaves, spores and motes in three DOF tiers per theme | The whole floating set, plus hero stones and app-icon art | Contract must assign copyright or grant an exclusive, perpetual, worldwide licence including app distribution and marketing. Get source `.blend` files too. |
| **Poly Haven** (https://polyhaven.com) | HDRIs for lighting; stone, bark and moss textures as *inputs* to the artist's renders | **CC0**: no attribution, commercial use allowed. Safest licence. |
| **Fab / Quixel Megascans** (https://www.fab.com) | Photoscanned rocks, pebbles, leaves and moss as source models for the renders | Fab Standard Licence (Professional tier for a company of this size) allows commercial use. Megascans stopped being free after 2024. **Rendering them into 2D sprites is the cleanest use.** Confirm in the licence text that redistributing derived 2D renders inside an APK is permitted; it typically is for "incorporated" content, but check. Legacy Quixel claims fall under the separate Epic Content License, so keep records of which licence each asset came under. |
| **Macro photography** (commissioned, or stock with an extended licence) | Dew droplets, moss and stone grain textures | Stock needs a licence that covers "use in a software application/app UI" (often an *extended* licence on Adobe Stock/Getty). Prefer commissioned shots. |
| **Substance 3D Designer/Painter** (artist-authored) | Tileable stone grain for glass, per theme | Output belongs to the author or commissioner. Substance-shipped materials are covered by Adobe's terms for use in products. Verify for the current Adobe licence. |
| **Icons** — commissioned vector set (an illustrator draws them in Figma/Illustrator and exports to Android VectorDrawable / Compose `ImageVector` in `:design`) | Custom organic line icons with water, leaf and stone motifs | Original work, owned via contract. Do **not** start from free icon packs, which carry visual clichés and mixed licences. Hand-drawn feel comes from considered stroke tapering, slightly varied terminals and optical corrections on a strict 24dp grid with a 1.75–2dp stroke, not from random path jitter. |

### 4.3 Explicit "do not" list (low-quality procedural looks)

- No Perlin/simplex-noise blobs, metaballs or "organic" SVG blob generators (e.g. blobmaker-style shapes) for backgrounds or pebble outlines. Pebble outlines are drawn by a designer: a small family of 6–10 hand-tuned outlines as `RoundedPolygon`/paths, reused consistently.
- No runtime particle emitters spawning random circles or squares as "spores" or "motes".
- No CSS/Compose radial-gradient "glow orbs" standing in for bioluminescence.
- No procedural water (sine-wave lines, noise-displaced gradients) as background art.
- No jittered or "sketchy" line filters on icons to fake hand-drawn.
- No stock clip-art leaves, emoji-style nature icons or flat vector pebbles with gradient fills.
- No AI-generated imagery shipped as final art without an artist's paintover, cleanup and licence clearance (it also tends to fail lighting consistency).
- No mixing of renders lit from different directions or HDRIs within a theme.
- No more than about 12 floating assets on screen at once, no fast motion, and nothing crossing text.

---

## 5. Proposed Library layout per theme

Common to all three: a top area with search plus filter chips (Albums, Artists, Tracks, Playlists, Folders) and a **grid ↔ list toggle** in the trailing corner (the Spotify "Your Library" pattern, ref 17). The list mode is identical across themes: dense rows, 56–64dp with 48dp minimum tap targets, square-ish pebble artwork thumbnails, and alphabet fast-scroll. Only the "browse" mode differs per theme. Glass is used for the chip bar and section headers. Cards use pre-blurred backdrops (section 3.1), not live blur.

### 5.1 Deep forest + stone: the "River stones" grid

- A **2-column staggered grid of pebble-shaped album cards** (6–8 outline variants rotated through, all with the same tap area). Artwork is clipped to the pebble with a stone-glass caption plate below.
- A "Recently played" row at the top as a horizontal **stream**: cards sit slightly overlapped as if laid in a riverbed, and snap-scroll with a spring.
- *Rationale:* the default theme should be the clearest expression of the brief (pebbles, stone glass). The staggered grid carries the irregular river-stone rhythm without hurting scan-ability. This draws on Milkinside's organic cards, Opal's object-led composition and Apple Music's artwork-first grid.

### 5.2 Night forest + bioluminescence: the "Lantern list"

- Default browse is an **artwork-led vertical list with large rows (88–96dp)**: the artwork at the left as a pebble, the title and artist in high-contrast type, and a **glowing teal edge only on the now-playing or recently played items**. Sections ("Tonight", "Recent", "All albums") are separated by soft glass headers.
- An optional "constellation" header: the 6 most-played albums as small glowing pebbles across a dark plate (rendered art plus artwork thumbnails), tapping through to each album.
- *Rationale:* a dark, glowing theme reads best when most of the screen stays dark and light marks meaning (Sky, Ori, Oak). A dense bright grid of artwork would flood the darkness and kill the bioluminescent mood, so the list keeps artwork smaller and lets glow carry state.

### 5.3 Earth + lichen: the "Strata shelves"

- **Horizontal shelves grouped by meaning** (By artist, By decade/era, Genres, Folders), each a horizontal rail of **square-ish stone tiles** (a basalt glass frame with a slightly irregular edge). Shelf headers are set in the warm serif.
- At the top, a large **featured album slab** (last played) that fills the width, like a hero stone.
- *Rationale:* earth tones and strata suggest layered, curated, slower browsing, like a record shelf. The shelf model (Apple Music Browse, Stoic's calm sections) favours exploration of mid-size libraries, and the list toggle covers large ones.

All three layouts are proposals for the owner's review, as the brief specifies.

---

## 6. Typography observed in the references (for the typography agent)

What I observed; not a recommendation. Fonts must be **bundled and open-licensed** (no internet permission).

| Seen in | Face style | Open-licensed candidate |
|---|---|---|
| Apple Liquid Glass / Apple Music | SF Pro (proprietary, Apple platforms only, **cannot bundle**) | Inter (OFL) is the closest open neo-grotesque |
| Material 3 Expressive / Android 16 | Google Sans Flex, a variable font with weight, width, optical size and roundness axes | **Google Sans Flex: OFL 1.1, released Nov 2025** on Google Fonts. Its roundness axis suits "pebble" softness. |
| Endel, Oak | Clean geometric/grotesque sans, light weights at large sizes | Inter, Manrope, DM Sans (all OFL) |
| Stoic, Gentler Streak, premium wellness shots on Dribbble | A warm serif for headings + a humanist sans for UI | Fraunces (OFL; variable with "soft" and "wonk" axes, very organic), Instrument Serif (OFL), Newsreader (OFL) |
| Dribbble glass/organic shots generally | Rounded or soft geometric sans | Outfit, Sora, Plus Jakarta Sans, Nunito (all OFL) |
| Frequently seen but **not** OFL | Satoshi and General Sans (Fontshare ITF Free Font Licence), Söhne, GT Walsheim, Neue Montreal (commercial) | Check the licence before bundling. ITF FFL is free to use, but it is not OFL and bundling terms must be verified. |

A pairing worth testing (observation only): Google Sans Flex (with roundness) for UI, plus Fraunces with "soft" for Library and Player titles in the Earth theme.

---

## Sources

- WWDC25 "Meet Liquid Glass": https://developer.apple.com/videos/play/wwdc2025/219/ ; legibility coverage: https://mobilesyrup.com/2025/06/10/how-apple-is-making-sure-liquid-glass-as-legible-as-possible/ ; https://en.wikipedia.org/wiki/Liquid_Glass
- ADA 2025 winners/finalists: https://www.apple.com/newsroom/2025/06/apple-unveils-winners-and-finalists-of-the-2025-apple-design-awards/ ; https://appdevelopermagazine.com/2025-apple-design-award-winners/
- ADA 2024 winners: https://www.apple.com/newsroom/2024/06/apple-announces-winners-of-the-2024-apple-design-awards ; https://bgr.com/tech/2024-apple-design-awards-winners-announced-procreate-dreams-gentler-streak-more/
- Google Play Best of 2025: https://www.androidauthority.com/google-play-best-apps-games-2025-3616640 ; https://www.droid-life.com/2025/11/18/google-play-top-apps-games-2025/
- MaterialShapes: https://composables.com/docs/androidx.compose.material3/material3/classes/MaterialShapes
- Haze: https://chrisbanes.github.io/haze/ ; https://newreleases.io/project/github/chrisbanes/haze/release/2.0.0-beta03 ; SurfaceView limitation discussion: https://redlib.hbubli.cc/r/androiddev/comments/1komua8/backdrop_blur_how
- Cloudy: https://klibs.io/project/skydoves/Cloudy ; KMPLiquidGlass (based on Kyant AndroidLiquidGlass): https://github.com/Kashif-E/KMPLiquidGlass ; AGSL playground: https://github.com/mejdi14/android-agsl-shader-playground ; ripple gist: https://gist.github.com/samoylenkodmitry/9c07c9dc506e052091f6de3461570748
- Igloo Inc SOTY 2024: https://www.awwwards.com/du-haihang/collections/webgl-animations/
- Megascans/Fab licensing: https://www.cgchannel.com/2024/10/epic-games-has-made-megascans-free-to-all-but-only-until-the-end-of-2024/ ; https://80.lv/articles/megascans-no-longer-free-after-2024/
- Google Sans Flex OFL: https://fontsource.org/fonts/google-sans-flex/about ; https://www.omgubuntu.co.uk/2025/11/google-sans-flex-font-ubuntu
- Endel / Stoic Editors' Choice: https://mobilesyrup.com/2019/06/09/stoic-self-reflection-journaling-app-review/ ; https://basicappleguy.com/basicappleblog/new-years-apps
