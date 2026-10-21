# App UI design system: water, forest and stone

This is the brief for the new **App UI** (the app's own screens and controls). It never applies to the **Visual**, which the engine draws, and the App UI never reacts to the music (`CONTEXT.md`). Everything here lives in the `:design` module as named tokens and shared components (ADR-0005).

## Direction

"Nature meets life": a brand-new design system with **water as the main element**, set among forest and stone. Frosted stone glass, natural shapes and floating assets.

## Quality bar

Always high end. References come only from top-tier work: Dribbble's featured designers, Behance's featured case studies, shipped apps on Mobbin, Awwwards, and Apple Design Award winners. Assets are art-directed and hand-crafted or professionally rendered, such as rendered stones, water and leaves, photographic or painterly textures, and carefully drawn vector icons. Low-quality procedural art (noise blobs, generated filler shapes, clip-art) is never used.

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
- **Library:** its layout is designed per theme, and every theme keeps a quick list toggle for big libraries. The per-theme layouts are proposed in the design spec for the owner to review.

## Open

- **Library layout per theme:** to be proposed.
