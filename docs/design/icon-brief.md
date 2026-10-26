# Geode icon brief

This is the brief and asset specification for a commissioned set of custom icons for Geode's **App UI**. It is written to be sent as it is to an icon designer.

Background reading for the designer: the design direction in `app-ui-design-system.md` and the references in `research-high-end-references.md`, both in this folder.

## 1. About Geode

Geode is an offline, private music player for Android. Alongside the music it draws a **Visual**: a real-time, audio-reactive picture made by the app's engine. People can also turn the Visual into video files in the **Studio**.

The icons are for the **App UI** only: the app's own screens and controls (tabs, buttons, panels, sheets, the mini-player, the widget and notifications). They never appear in the Visual, and never in a Render or Recording made from it. They never react to the music.

The new App UI is called "Nature meets life". **Water is the main element**, set among forest and stone. Panels are frosted stone glass, buttons and cards have soft river-pebble outlines, and leaves, pebbles and droplets drift slowly behind the screens. There are three colour themes (Deep forest + stone, Night forest + bioluminescence, Earth + lichen), each with a dark version (the default) and a light version.

## 2. What we are asking for

A complete, original set of about 125 line icons (section 7), each in an outline and a filled state, drawn on a strict 24dp grid. They should feel organic and drawn by hand, with water, leaf and stone motifs where they come naturally, while staying instantly readable at small sizes.

The commission also includes the **home-screen app icon** (section 9), drawn in the same style family.

## 3. Style

**In one sentence:** calm, hand-tuned line icons that look like they were drawn with a good pen on smooth river stone.

- **Line icons first.** The default state is an outline. Shapes are open and airy, and never crowded.
- **Hand-drawn feel, made with care.** The feel comes from considered choices, not from noise: gentle stroke tapering towards the ends of open strokes, slightly varied terminals (one end a touch rounder than the other), soft optical corrections, and curves that are slightly asymmetric like a pebble or a leaf. Every icon must still look precise and deliberate.
- **Organic geometry.** Prefer smooth, continuous curves to straight lines and perfect circles. Rectangles become softly irregular "pebble" rectangles. Circles may be very slightly egg- or pebble-shaped.
- **Motifs where natural.** Use water (a droplet, a ripple, a wave line), leaf (a leaf blade, a vein, a sprout) and stone (a pebble, a stacked cairn, a smooth slab) where the meaning allows it. Section 7 suggests a motif for some icons. **Recognition always wins over motif:** if a motif makes an icon slower to read, drop it. Play, pause, next, close, back and search must look like what people already know.
- **One family.** All icons share the same stroke weight, corner treatment, terminals and level of detail. Placed side by side in a bottom bar or a toolbar, they should look like one set.
- **Single colour.** Each icon is one flat colour that the app tints. No gradients, shading, textures, shadows or multiple colours inside the art. The app adds any glow or relief effect itself.

### Good and bad, from the references

| Good (do this) | Why | Bad (avoid) | Why |
|---|---|---|---|
| **Gentler Streak** (Apple Design Award 2024): hand-drawn character art and soft charts | Its hand-drawn feel comes from drawing carefully, not from jittered paths | Jitter or "sketchy" line filters, rough-pen brushes | They fake a hand-drawn look, blur at small sizes and look cheap |
| **Material 3 Expressive** shapes (cookie, pill, gem outlines) | Irregular, organic shapes that still sit on a strict grid and read clearly | Shapes drawn freehand with no grid or keylines | Optical sizes drift and the set stops looking like one family |
| **Opal**: art-directed objects used sparingly | Few, considered motifs with a clear meaning | Clip-art leaves, emoji-style nature icons, flat vector pebbles with gradient fills | Clichés that make the app look like a template |
| **Apple Liquid Glass** controls: crisp glyphs that stay legible on glass | Icons stay readable over a busy, blurred backdrop | Thin hairline strokes, tiny interior details | They disappear over frosted glass and fail contrast |
| **Endel** and **Oak**: very few elements per screen, quiet glyphs | Restraint makes a dark theme feel premium | Free icon packs or traced stock icons as a starting point | Mixed licences and visual clichés (section 11) |

The full list of references, with links, is in `research-high-end-references.md`, section 1.

## 4. Grid and keylines

All icons are drawn on a **24 × 24dp grid** (one unit = 1dp) and exported with a 24 × 24 viewport.

| Area | Size | Use |
|---|---|---|
| Canvas | 24 × 24 | The whole icon, including padding |
| Padding | 2 on every side | Kept empty, except for small optical overshoots |
| Live area | 20 × 20 | Where the drawing sits |
| Circle keyline | 20 diameter | Round icons (search, settings, record) |
| Square keyline | 18 × 18 | Square-ish icons (library, folder, file) |
| Portrait keyline | 16 wide × 20 tall | Tall icons (file, phone, droplet) |
| Landscape keyline | 20 wide × 16 tall | Wide icons (video, cast, layers) |

- The keylines are a guide to **equal visual weight**, not a hard border. Pebble-shaped or leaf-shaped icons may overshoot a keyline by up to 0.5dp where that makes them look the same size as their neighbours.
- Snap the main anchor points to the 0.25dp sub-grid, so that edges render cleanly at every screen density.
- Keep at least **1.5dp of clear space** between separate strokes inside an icon, so they do not merge at small sizes.
- Centre each icon optically, not mathematically (a play triangle sits slightly right of centre).

## 5. Strokes and corners

| Property | Value |
|---|---|
| Nominal stroke | **1.75dp** at 24dp |
| Taper | Open strokes may taper to no less than **1.25dp** at their ends. Closed shapes keep the nominal weight. |
| Caps | Round |
| Joins | Round |
| Outside corners | Radius at least **1dp**. No sharp corners, except the pointed tip of a leaf or droplet, where the point is the motif. |
| Inside corners | Radius at least **0.5dp** |
| Smallest detail | **1.5dp** (a dot, a gap or a counter). Anything smaller disappears on a phone. |

The final vectors are delivered **outlined**: every stroke converted to a filled shape. This keeps the taper and varied terminals exactly as drawn, because Android vector strokes cannot change width along their length. Keep the stroked version in the source file (section 10).

## 6. Sizes and states

### Sizes

| Token | Size | Where | What to draw |
|---|---|---|---|
| `icon.size.standard` | 24dp | Almost everywhere: bottom bar, navigation rail, toolbars, player controls, list actions | The master drawing |
| `icon.size.small` | 16dp | Inside chips, list rows and the Studio timeline lanes | A separately tuned **16dp version** of each icon marked "S" in section 7: fewer details, the stroke kept at about 1.5dp, and the 16 × 16 grid with 1.5dp padding |
| `icon.size.hero` | 40dp | The large play/pause, previous and next buttons on the Player | A separately tuned **40dp version** of these five transport icons (play, pause, previous, next, record), with a 2.5dp stroke on a 40 × 40 grid |

The app never scales a 24dp drawing up to 40dp or down to 16dp. Line icons that are scaled change their stroke weight and lose their balance.

### States

| State | Look | Used for |
|---|---|---|
| **Outline** | The line drawing, no filled areas | Default for every icon |
| **Filled** | The same silhouette with its main shape filled in. Interior lines become cut-outs (knocked out of the fill) that are at least 1.5dp wide. | **Selected** items: the active tab in the bottom bar and navigation rail, and "on" toggles (favourite on, shuffle on, a selected lane) |
| **Selected** | Filled art, plus a colour and a soft glow added by the app | The designer supplies the filled art only |
| **Disabled, pressed, focused** | Made by the app with opacity, colour and the pebble-shaped button behind the icon | No extra art needed |

Some controls switch between **two different icons** rather than outline and filled. These are listed as separate icons in section 7 (for example play and pause, record and stop, repeat and repeat one).

The filled state must keep the **same outer silhouette** as the outline, so that an icon does not jump in size when it is selected.

## 7. Icon inventory

**Today** means the icon is in the app now, with the file that uses it (under `app/src/main/java/dev/geode/`) and where it comes from now:

- **Stone** = our own placeholder glyph in `ui/theme/StoneIconPaths.kt`, drawn by `StoneIconArt`.
- **Material** = the stock `Icons.Filled.*` icon from Google's Material icon library.
- **Drawable** = an XML vector in `app/src/main/res/drawable/`.
- **New** = needed for the new design and not in the app yet.

The new set replaces all three of today's sources, so the app can stop using the Material icon library.

**States:** O = outline, F = filled. **S** = also draw a 16dp version. **H** = also draw a 40dp hero version.

Motif ideas are suggestions for the designer, not requirements.

### 7.1 Navigation (bottom bar, navigation rail, top bar)

The new bottom bar holds Player, Library, Visuals and Studio. Settings sits behind an icon in the top bar. On landscape phones and tablets the same icons sit in a side navigation rail.

| Name | Meaning | Today | States | Motif idea |
|---|---|---|---|---|
| `player` | The Player tab: what is playing now | Stone `PLAY` (`ui/GeodeAppState.kt`) | O F | A play shape inside a pebble, so it differs from the play button |
| `library` | The Library tab: all the music on the phone | Stone `LIBRARY` (`ui/GeodeAppState.kt`) | O F | A small stack of flat stones (a cairn) or records |
| `visuals` | The Visuals tab: choose and customize the Visual | Stone `VISUALIZER` (`ui/GeodeAppState.kt`) | O F | Ripple rings spreading on water |
| `studio` | The Studio tab: make and edit videos | Stone `STUDIO` (`ui/GeodeAppState.kt`) | O F | A film frame with a leaf or a droplet |
| `settings` | Open Settings | Stone `SETTINGS` (`ui/GeodeAppState.kt`, `ui/VisualizerScreen.kt`) | O F | A gear made of rounded pebble teeth |
| `back` | Go back one screen | New (today a text button, `R.string.library_back`) | O | |
| `close` | Close a sheet, dialog or panel | Stone `CLOSE` (`ui/AppShell.kt`, `ui/FolderSettings.kt`, `ui/LibraryScreen.kt`, `ui/PlayerPanels.kt`, `ui/SmartPlaylistEditor.kt`) | O S | |
| `more` | More actions (overflow menu) | Material `MoreVert` (`ui/LibraryScreen.kt`) | O S | Three small pebbles in a column |
| `chevron_up` | Expand, or move an item up | Material `KeyboardArrowUp` (`ui/PlayerPanels.kt`, `ui/LibraryScreen.kt`) | O S | |
| `chevron_down` | Collapse, or move an item down | Material `KeyboardArrowDown` (`ui/VisualizerScreen.kt`, `ui/LibraryScreen.kt`) | O S | |
| `chevron_right` | Opens a further page (Settings rows) | New | O S | |
| `drag_handle` | Drag to reorder | Material `DragHandle` (`ui/LibraryScreen.kt`) | O S | Two wave lines |

### 7.2 Player and mini-player

| Name | Meaning | Today | States | Motif idea |
|---|---|---|---|---|
| `play` | Start playback | Stone `PLAY` (`ui/AppShell.kt`, `ui/PlayerScreen.kt`, `ui/VisualizerScreen.kt`, `ui/LibraryScreen.kt`, `ui/VisualsHub.kt`, `ui/TemplatesSheet.kt`, `ui/SmartPlaylistEditor.kt`); Material `PlayArrow` (`ui/StudioPreview.kt`) | O F S H | A softly rounded, pebble-like triangle |
| `pause` | Pause playback | Stone `PAUSE` (`ui/AppShell.kt`, `ui/PlayerScreen.kt`, `ui/VisualizerScreen.kt`) | O F S H | Two upright river stones |
| `previous` | Previous track, or back to the start | Stone `PREVIOUS` (same files as `pause`) | O F H | |
| `next` | Next track | Stone `NEXT` (same files as `pause`) | O F H | |
| `shuffle` | Shuffle on or off (filled when on) | Stone `SHUFFLE` (`ui/PlayerScreen.kt`, `ui/VisualizerScreen.kt`) | O F | Two crossing streams |
| `repeat` | Repeat the queue (filled when on) | Stone `REPEAT` (`ui/PlayerScreen.kt`, `ui/VisualizerScreen.kt`) | O F | A loop like a water eddy |
| `repeat_one` | Repeat the current track | New (today `repeat` is reused) | O F | The eddy with a small "1" |
| `favorite` | Mark as a favourite (filled when on) | Stone `FAVORITE` (`ui/PlayerScreen.kt`, `ui/VisualizerScreen.kt`, `ui/VisualsHub.kt`) | O F S | A heart drawn like a leaf, with a central vein |
| `queue` | Show the play queue | Stone `QUEUE` (`ui/AppShell.kt`, `ui/VisualizerScreen.kt`) | O F | Stepping stones in a row |
| `lyrics` | Show the lyrics | Stone `LYRICS` (defined, not used yet) | O F | A speech shape with a wave line |
| `cast` | Show the Visual on a second screen | Material `Cast` (`ui/PlayerScreen.kt`) | O F | Keep the familiar cast shape; ripples instead of arcs |
| `sleep_timer` | Stop playback after a set time | Material `Bedtime` (`ui/PlayerScreen.kt`) | O F | A crescent moon over still water |
| `microphone` | The Visual reacts to the microphone instead of the music | Stone `MICROPHONE` (`ui/PlayerScreen.kt`) | O F | |
| `microphone_off` | Microphone reaction is off | New | O | |
| `fullscreen` | Hide the controls and show only the Visual | Stone `FULLSCREEN` (defined, not used yet) | O | |
| `fullscreen_exit` | Show the controls again | New | O | |
| `volume` | Volume | New | O | Sound drawn as ripples |
| `equalizer` | Open the equalizer | New | O | Bars as standing stones of different heights |
| `album_placeholder` | Shown where a track has no artwork | Material `MusicNote` (`ui/Artwork.kt`, `ui/AppShell.kt`) | O | A music note on a pebble |

### 7.3 Library

| Name | Meaning | Today | States | Motif idea |
|---|---|---|---|---|
| `search` | Search the library | Stone `SEARCH` (`ui/LibraryScreen.kt`, `ui/PlayerScreen.kt`) | O S | A lens like a drop of water |
| `filter` | Filter the list | New | O F S | |
| `sort` | Change the sort order (title, artist, album, added, duration) | New (today a text menu) | O S | |
| `view_grid` | Show the library as a grid | New | O F | Pebbles in a grid |
| `view_list` | Show the library as a dense list | New | O F | |
| `album` | Albums | New (today a text tab, `library_tab_albums`) | O F S | A record with a leaf-vein groove |
| `artist` | Artists | New (today a text tab) | O F S | |
| `track` | Tracks (songs) | New (today a text tab) | O F S | A single music note |
| `playlist` | Playlists | New (today a text tab) | O F S | |
| `smart_playlist` | A playlist that fills itself from rules | New (`ui/SmartPlaylistEditor.kt`) | O F S | A playlist with a sprout |
| `folder` | Folders on the phone | Stone `FOLDER` (`ui/VisualsHub.kt`); text tab in Library | O F S | |
| `duplicates` | Find duplicate tracks | New (today a text tab, `library_tab_duplicates`) | O F S | Two overlapping pebbles |
| `play_all` | Play everything in this list | New (today a text button) | O | |
| `shuffle_all` | Shuffle everything in this list | New | O | |
| `play_next` | Play this track next | New | O S | |
| `add_to_queue` | Add to the end of the queue | New | O S | |
| `add_to_playlist` | Add to a playlist | New | O S | |
| `edit` | Edit (track info, a name, a template) | Stone `EDIT` (`ui/LibraryScreen.kt`, `ui/VisualsHub.kt`) | O S | A pen with a leaf-shaped nib |
| `delete` | Delete | Stone `DELETE` (`ui/TemplatesSheet.kt`, `ui/VisualsHub.kt`) | O S | |
| `file` | A single file | Stone `FILE` (defined, not used yet) | O S | |
| `lock` | Permission needed to read the music | New (library permission screen) | O | |

### 7.4 Visuals tab and Customize

| Name | Meaning | Today | States | Motif idea |
|---|---|---|---|---|
| `style` | A Style: one look for the Visual | New | O F S | A faceted geode stone |
| `customize` | Open Customize (how a Style looks and reacts) | New | O F | Sliders as reeds |
| `macro` | A Macro: one large control | New | O F S | A round knob like a smooth pebble |
| `reaction` | A Reaction: link a Sound signal to a target | New | O F S | Two pebbles joined by a ripple line |
| `modulator` | A Modulator: a generated wave that moves targets | New | O F S | A single gentle wave |
| `automation` | Automation: changes the look by itself over the music | New | O F S | A wave with a small arrow |
| `camera` | The Camera: zoom, rotation, travel | New | O F S | |
| `palette` | Colours and palette | New (`ui/PaletteMaker.kt`) | O F S | Leaves of different shades |
| `randomize` | Pick something at random | Material `Casino` (`ui/VisualsHub.kt`) | O S | Scattered seeds, not a dice |
| `templates` | Saved Customizations to reuse | New (`ui/TemplatesSheet.kt`) | O F | |
| `record` | Start a Recording of the Visual | Material `FiberManualRecord` (`ui/VisualsHub.kt`) | O F S H | A single droplet seen from above |
| `stop` | Stop a Recording or a replay | Material `StopCircle`, `Stop` (`ui/VisualsHub.kt`) | O F S | A square pebble |
| `layers` | Show the live Visual behind the App UI (filled when on) | Material `Layers` (`ui/VisualsHub.kt`) | O F | Stacked flat stones |
| `layers_off` | Hide the live Visual behind the App UI | Material `LayersClear` (`ui/VisualsHub.kt`) | O | |
| `import` | Import a MilkDrop preset, a texture or a file | New | O S | |
| `texture` | An image used inside a Style | New | O S | |
| `add` | Add (a chip, a Reaction, a lane) | Stone `ADD` (defined); a "+" text label (`ui/CustomizeTabs.kt`) | O S | |
| `check` | Confirm, or an item is chosen | Stone `CHECK` (defined, not used yet) | O S | |
| `share` | Share | Stone `SHARE` (`ui/TemplatesSheet.kt`, `ui/VisualsHub.kt`) | O S | |

### 7.5 Studio

| Name | Meaning | Today | States | Motif idea |
|---|---|---|---|---|
| `project` | A Project: a folder of videos | New | O F S | A folder with a pebble |
| `render` | Make a Render: a video made offline from a song | New | O F | A film frame with a droplet falling into it |
| `recording` | A Recording: video captured live | New (today `record`) | O F S | |
| `edit_video` | An Edit: cut and combine videos | New | O F S | |
| `short` | A Short: a vertical video for social feeds | New (`editor_add_short`) | O F S | A tall pebble frame |
| `export` | Export the video | New (`editor_export`) | O | |
| `undo` | Undo | New (today text, `editor_undo`) | O S | A curved arrow like a wave turning back |
| `redo` | Redo | New (today text, `editor_redo`) | O S | |
| `split` | Split a clip at the playhead | New (`editor_split`) | O S | A leaf split along its vein |
| `ripple_delete` | Delete a clip and close the gap | New (`editor_ripple_delete`) | O S | |
| `duplicate` | Duplicate a clip | New (`editor_duplicate`) | O S | |
| `zoom_in` | Zoom the timeline in | New (`editor_zoom_in`) | O S | |
| `zoom_out` | Zoom the timeline out | New | O S | |
| `add_lane` | Add a timeline lane | New (`editor_add_lane`) | O S | |
| `marker` | Add or show a marker | New (`editor_add_marker`, `editor_markers`) | O F S | A small pebble on a stick |
| `transition` | A transition between clips | New (`editor_transition`) | O S | Two waves overlapping |
| `auto_cut` | Cut automatically to the beat | New (`editor_auto_cut`) | O S | |
| `text` | A text or caption clip | New (`editor_text_title`) | O S | |
| `captions` | Lyric captions, subtitles (SRT) and chapters | New (`editor_lyric_captions`, `editor_import_srt`) | O S | |
| `still` | Add a still image | New (`editor_add_still`) | O S | |
| `video` | Add a video clip | New (`editor_add_video`) | O S | |
| `mute` | Mute a lane (filled when muted) | New (`editor_mute_short`) | O F S | |
| `lane_lock` | Lock a lane (filled when locked) | New (`editor_lock_short`) | O F S | |
| `visible` | Lane enabled | New (`editor_enable`) | O S | |
| `hidden` | Lane disabled | New (`editor_disable`) | O S | |
| `keyframe` | A keyframe on a lane | New | O F S | A small diamond-shaped stone |
| `tap_tempo` | Tap along to the beat | New (`editor_tap`) | O S | A droplet hitting water |
| `audio_lane` | The audio lane | New (`editor_lane_audio`) | O S | |

### 7.6 Settings

| Name | Meaning | Today | States | Motif idea |
|---|---|---|---|---|
| `theme` | Theme and light or dark mode | New | O | Half a pebble lit, half in shade |
| `floating_assets` | Floating leaves and pebbles: drifting, still or off | New | O | A drifting leaf |
| `haptics` | Haptics on or off | New | O | A phone with ripples at its side |
| `motion` | Reduce motion | New | O | |
| `audio` | Audio and playback settings | New (`ui/AudioSettings.kt`, `ui/PlaybackSettings.kt`) | O | |
| `music_folders` | Which folders hold music | New (`ui/FolderSettings.kt`) | O | |
| `export_settings` | Video export settings | New (`ui/ExportSettings.kt`) | O | |
| `help` | Help and the tutorial | New (`ui/HelpSettings.kt`) | O | |
| `about` | About Geode, licences | New (`ui/AboutSettings.kt`) | O | |
| `open_external` | Opens Android's own settings | New (`ui/AppSettingsLink.kt`) | O S | |

### 7.7 Diagnostics and messages

Diagnostics stay on the phone: a "Share diagnostics" button in Settings and a hidden developer overlay (ADR-0005).

| Name | Meaning | Today | States | Motif idea |
|---|---|---|---|---|
| `diagnostics` | Share diagnostics: write a log file to share | New | O | A leaf with a magnifier, or a pulse line |
| `developer_overlay` | Show the developer overlay | New | O F | |
| `frame_time` | Frame time (how smoothly the screen draws) | New | O S | |
| `audio_glitch` | Audio crackles or dropouts | New | O S | A broken wave line |
| `engine` | The engine's state | New | O S | |
| `info` | Information | New | O S | |
| `warning` | Something needs attention | New | O F S | |
| `error` | Something went wrong | New | O F S | A cracked pebble |
| `success` | Done, saved | New | O F S | |

### 7.8 System surfaces (widget and notifications)

These follow Android's own rules, so they are simpler than the in-app icons.

| Name | Meaning | Today | Rules |
|---|---|---|---|
| `widget_play`, `widget_pause`, `widget_previous`, `widget_next` | Transport buttons on the home-screen widget | Drawable `ic_widget_play`, `ic_widget_pause`, `ic_widget_prev`, `ic_widget_next` (`widget/NowPlayingWidget.kt`) | Same drawings as the in-app transport icons, filled, at 24dp |
| `widget_art_placeholder` | Widget artwork when a track has none | Drawable `ic_widget_art` | As `album_placeholder` |
| `stat_capture` | Status-bar icon while capturing audio or exporting video | Drawable `ic_stat_capture` (`audio/PlaybackCaptureService.kt`, `studio/RenderService.kt`) | White on transparent, a single flat silhouette with no fine detail, 24dp with a 22dp live area. Android shows only its shape. |
| `stat_playing` | Status-bar icon while music plays | New | As `stat_capture` |

The home-screen app icon is part of this commission, with its own rules in section 9.

## 8. Contrast

Icons and control outlines must reach at least **3:1** contrast against the worst plausible backdrop in every theme, both dark and light (WCAG 2.x, as worked out in `research-high-end-references.md`, section 2). Because the art is a single tinted colour, this is mostly the app's job. The designer's part is to keep strokes at the weights in section 5, so that the icons stay solid over frosted glass.

## 9. App icon

The app icon is the first thing people see of Geode, on the home screen and in the Play Store. Today's icon (`app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` and its three layers in `app/src/main/res/drawable/`) is replaced.

### Style

- **Same family as the in-app icons.** It uses the same drawing language: organic curves, soft pebble geometry, round terminals and the hand-tuned line. It may be bolder and more detailed than a 24dp glyph, because it is shown larger.
- **One clear idea.** A single motif that reads at a glance, such as a geode, a river stone or a ripple on water. It should not be a collage of every motif. It must still read at 48dp on a busy wallpaper.
- **Colour.** Unlike the in-app icons, the app icon is in colour, taken from the default theme (Deep forest + stone): the pine-black base, moss and a small amber highlight (`research-high-end-references.md`, section 2.1). No text and no letters.

### Adaptive icon (Android 8 and later)

Geode's lowest supported Android version already uses adaptive icons, so no older-style square icons are needed. The icon is built from three layers, each on a **108 × 108dp canvas**:

| Layer | What it holds | Rules |
|---|---|---|
| **Foreground** | The motif | Keep everything that matters inside the central **72 × 72dp safe zone**. Android masks the icon into a circle, a squircle or a rounded square depending on the phone, and can clip the corners of the safe zone, so keep the key shape inside a 66dp circle at its centre. The 18dp margin around the safe zone is used for the parallax and pulse effects some launchers add. |
| **Background** | A plain colour or a calm surface (for example a dark stone or deep water) | It fills the whole 108dp canvas, with no important detail near the edges. A vector is preferred. If it needs a texture, deliver a bitmap at 432 × 432px. |
| **Monochrome** | A single-colour silhouette of the motif, for themed icons on Android 13 and later | One flat shape, with no colour, gradients or fine detail. The system tints it to match the wallpaper. Same canvas and safe zone as the foreground. |

Check the icon in every mask shape (circle, squircle, rounded square and teardrop), on light and dark wallpapers, and with themed icons switched on.

### Play Store icon

- **512 × 512px PNG**, 32-bit with alpha, at most 1024KB.
- A full square with no rounded corners and no drop shadow. Google Play adds the corner mask and the shadow itself.
- The same art as the adaptive icon: the foreground over the background, at the same proportions.

### Concept rounds

The app icon is developed in its own track, alongside the in-app set:

1. **Concepts.** Three distinct concepts, each shown at Play Store size, at 48dp on a home screen in two mask shapes, and as a monochrome themed icon. The owner chooses one.
2. **Refinement.** The chosen concept is refined. The number of refinement rounds is set in the contract (section 13).
3. **Final.** Delivered as in section 10.

## 10. Deliverables

For every icon and every state it has:

1. **SVG**, 24 × 24 viewBox (16 × 16 or 40 × 40 for the extra sizes), outlined paths only, a single black fill (`#000000`), no strokes, no transforms, no clip paths, no masks, no embedded images, no hidden layers, and no editor metadata.
2. **Android vector drawable** (`.xml`), with `android:width` and `android:height` in dp, a matching viewport, one or a few `<path>` elements with `android:fillColor="#FF000000"`, and no groups unless needed. They must import cleanly with Android Studio's Vector Asset tool without warnings.

And for the set as a whole:

3. **Source file** (Figma preferred, or Illustrator) with the grid and keylines, the **stroked** master of every icon next to its outlined export, and the 16dp and 40dp versions on their own pages.
4. **Contact sheet** (PDF and PNG) showing every icon in both states at 16, 24 and 40dp, in dark and light on the default theme.
5. **Index** (a CSV) with one row per file: name, state, size, meaning (copied from section 7).

For the app icon (section 9):

6. **Adaptive icon layers** as Android vector drawables on a 108 × 108dp canvas: `ic_launcher_foreground.xml`, `ic_launcher_background.xml` (or `ic_launcher_background.png` at 432 × 432px if it needs a texture) and `ic_launcher_monochrome.xml`, plus an SVG of each.
7. **Play Store icon:** `play_store_icon_512.png`.
8. **App icon source** in the same source file as the in-app icons, with the 108dp canvas, the 72dp safe zone and the 66dp circle shown as guides.
9. **App icon preview sheet** showing it in every mask shape, on light and dark wallpapers, and as a themed (monochrome) icon.

### Naming

Lowercase `snake_case`, as Android resource names require:

```
ic_<name>_<state>[_<size>]
```

- `<name>` is the name from section 7, for example `play`, `repeat_one`, `add_to_queue`.
- `<state>` is `outline` or `filled`.
- `<size>` is left out for 24dp, and is `16` or `40` for the extra sizes.

Examples: `ic_play_outline.xml`, `ic_play_filled_40.xml`, `ic_close_outline_16.xml`, `ic_favorite_filled.svg`. The system icons keep their prefixes: `ic_widget_play.xml`, `ic_stat_capture.xml`.

### Where they go

The vector drawables live in the App UI's shared `:design` module (ADR-0005), never in an engine module, because the icons belong to the App UI and never to the Visual. Engineering adds them to the app; the designer only delivers files.

## 11. Licensing and ownership

- **Original work.** Every icon is drawn from scratch for Geode. No free or paid icon packs, fonts, traced stock icons, or AI-generated drawings may be used as a starting point or inside the final art. Reference boards and mood images are fine for inspiration.
- **Ownership.** On payment, the designer assigns to the owner all copyright and other rights in the icons, the app icon, the source files and the exports, worldwide and for the full term, for any use: the app on every platform, its store listings, marketing, and future versions and derived works. Where an assignment is not possible, the designer grants an exclusive, perpetual, irrevocable, worldwide, royalty-free licence with the right to sublicense and change the work.
- **Moral rights.** The designer waives moral rights (or agrees not to assert them) where the law allows, so the icons can be changed, recoloured, combined and resized without approval.
- **Credit.** The designer may be credited on the About screen, if they want, and may show the set in their portfolio once the app version using it is released.
- **Warranty.** The designer confirms the work is original and does not infringe anyone else's rights.
- **Records.** The signed agreement and the final source files are kept with the owner's records, next to the asset manifest for the other App UI art.

## 12. Review process

The owner reviews and approves every stage. Nothing ships without the owner's approval.

1. **Style tiles.** Six test icons: `player`, `library`, `visuals`, `studio`, `settings` and `favorite`, in outline and filled, at 16, 24 and 40dp, shown on all three themes in dark and light, plus over a frosted glass panel. The owner chooses a direction, with rounds of changes as set in the contract (section 13).
2. **Full set, first pass.** Every icon at 24dp in outline, as a contact sheet. The owner checks meaning and consistency, with rounds of changes as set in the contract.
3. **Full set, final.** All states and sizes, delivered as in section 10.
4. **Engineering check.** The files import into `:design` (and the app icon layers into the app) without warnings; every name, state and size in the index is present; the art tints correctly; nothing breaks the grid or the minimum detail size.
5. **App icon.** In parallel with steps 1 to 3, the app icon goes through its concept rounds (section 9) and is reviewed on a real phone: in two or more mask shapes, on light and dark wallpapers, with themed icons on, and as the Play Store image.
6. **In the app.** The icons and the app icon are wired into the app. The owner reviews them on a phone in every theme, dark and light, and at the largest system font size. Any fixes go back to the designer as one consolidated list.
7. **Sign-off.** The owner signs off, the final payment is made, and the rights transfer (section 11) takes effect.

### What to send for each review

- PNG contact sheets, not loose files, at real size (1x) and at 3x.
- Each icon labelled with its name from section 7.
- Any icon whose meaning the designer thinks is unclear, flagged with a question.

## 13. Questions for the owner before sending

- Budget, timeline and number of change rounds in the contract.
