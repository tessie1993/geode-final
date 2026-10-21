# App UI design system: water, forest and stone

This is the brief for the new **App UI** (the app's own screens and controls). It never applies to the **Visual**, which the engine draws, and the App UI never reacts to the music (`CONTEXT.md`). Everything here lives in the `:design` module as named tokens and shared components (ADR-0005).

## Direction

"Nature meets life": a brand-new design system with **water as the main element**, set among forest and stone. Frosted stone glass, natural shapes and floating assets.

## Quality bar

Always high end. References come only from top-tier work: Dribbble's featured designers, Behance's featured case studies, shipped apps on Mobbin, Awwwards, and Apple Design Award winners. Assets are art-directed and hand-crafted or professionally rendered, such as rendered stones, water and leaves, photographic or painterly textures, and carefully drawn vector icons. Low-quality procedural art (noise blobs, generated filler shapes, clip-art) is never used.

## Tools and sources

- **Assets are made with top-tier AI image generators**, not Blender. The first choice is Nano Banana (Gemini's image model, free tier); the alternatives are FLUX.1 Krea and Qwen-Image. Each asset is generated on a plain background, edited, given a transparent background, and exported. The owner reviews every asset sheet before anything ships. Each asset's model, prompt and the model's licence terms are recorded in an asset manifest.
- **Textures** may also use CC0 scans from Poly Haven.
- **Post-processing** (background removal, cropping, compositing, export to WebP at each screen density) uses ImageMagick and similar free tools.

## Decided

- **Themes:** three, chosen in Settings. Each theme has a dark version (the default) and a light version.

  | Theme | Dark | Light ("sunlit meadow") |
  |---|---|---|
  | **Deep forest + stone** (default) | near-black pine green base, slate and granite glass, moss accent, warm amber highlights | warm cream and soft green |
  | **Night forest + bioluminescence** | green-black, glowing teal and firefly-yellow accents | its daylight counterpart in the same spirit |
  | **Earth + lichen** | umber and basalt, pale lichen green and rust accents | its daylight counterpart in the same spirit |

- **Mode:** dark by default; light is a choice in Settings.
- **Water is the main element:** rippling surfaces, flowing transitions, and dew on glass. Where you touch, a soft ripple spreads through the glass.
- **Glass:** frosted stone glass. Blurred panels with a faint stone grain and a soft inner glow, like polished river stone you can half see through; their edges catch a little light.
- **Shapes:** pebble shapes. Buttons, cards and sheets have soft, slightly irregular river-stone outlines, never perfect rectangles, with consistent tap sizes.
- **Floating assets:** leaves, pebbles, spores, droplets and light motes drift slowly behind screens, with gentle parallax. Settings offers *drifting* (the default), *still* or *off*, and battery saver switches them to *still*.
- **Icons:** a custom organic set of hand-drawn-feeling line icons, with water, leaf and stone motifs where natural. They are drawn as vectors in `:design`.
- **Motion:** organic and springy, as if things settle onto water and moss: soft springs, gentle overshoot and slow fades. Android's "remove animations" setting is respected.
- **Haptics:** fluid and watery (droplets, ripples), and they can be switched off in Settings.
- **Typography:** Fraunces for display and Figtree for body text, both under the SIL Open Font License and bundled as font files (the app has no internet permission, so Android's downloadable fonts are not used). Fraunces' softness axis rounds its letters like water-worn stone. Each screen has one expressive headline, and everything else stays quiet with generous space.
- **Glass layering:** frosted panels sit at most one or two layers deep over the living backdrop, never glass on glass.
- **Navigation:** a bottom bar with Player, Library, Visuals and Studio; Settings sits behind an icon. Landscape and tablets use a side navigation rail.
- **Player:** the live Visual fills the background, with track info and controls on a soft gradient at the bottom. A tap hides the controls for full screen.
- **Library, per theme** (each one keeps a grid/list toggle and a dense list for big libraries):
  - **Deep forest + stone: Stream.** Albums float down a gently curving stream. It is a flowing single column of large artwork that drifts slightly as you scroll, with water caustics under the cards.
  - **Night forest: Moonlit pond.** Artwork floats in a grid on dark water, with soft mirror reflections beneath. The playing album glows like a lily lantern.
  - **Earth + lichen: Terraced pools.** Stepped horizontal rows, like rice-terrace pools, each holding one category. Water spills between the levels as you scroll.
- **Glass over the Visual:** the Visual is a GL surface that can't be blurred, so the Player uses a soft gradient over it. Frosted glass goes on the bottom bar, sheets and Library.
- **Effects by Android version:**
  - **13 and later:** full glass, with shader ripples.
  - **12:** blur only, and a pre-rendered ring stands in for the ripple.
- **Glass and text:** glass that carries text stays at least about 88% opaque, rising over bright backdrops, so text meets WCAG AA. The research gives contrast-checked colour values for each theme.
- **Live blur:** at most two live blurred surfaces on screen. Library cards use artwork blurred in advance.
- **Floating assets are high-end generated images.** Each is art-directed per theme with consistent lighting, generated with a top-tier image model and finished by hand. They come at three depth layers with focus blur baked in, with at most about 12 on screen. The owner reviews every asset before it ships.
- **Icons are commissioned from an icon designer.** We write the brief and the asset spec.

## Open

- The icon brief for the commissioned designer.

Research and references: `research-high-end-references.md`.
