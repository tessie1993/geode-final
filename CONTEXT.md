# Geode

Geode is an Android music player that turns music into moving visuals, which you can shape, record and turn into video. This glossary fixes the words for its domain.

## Language

### Places

**Home**:
The screen every visit starts on. It shows the last song and the last Visual, ready to resume, and leads to the four Places.
_Avoid_: Dashboard, start screen

**Place**:
One of the four main parts of the app: Music Library, Visuals, Studio, Settings.
_Avoid_: Tab, section, module

**Music Library**:
The Place where your music lives and plays.
_Avoid_: Library (alone), player

**Visuals**:
The Place where the music becomes a Visual.
_Avoid_: Visualizer

**Studio**:
The Place where music and Visuals become video. It has two Lounges: the Export Lounge and the Editing Lounge.
_Avoid_: Export suite

**Settings**:
The Place for preferences, tutorials and help.

### Visuals and looks

**Visual**:
The live moving picture that reacts to a song or to the microphone, drawn in real time.
_Avoid_: Visualization, scene

**Style Family**:
A group of Styles that move and react to music in the same way.
_Avoid_: Family type, renderer

**Style**:
One variation within a Style Family, with its own look.
_Avoid_: Scene, theme

**MilkDrop**:
The classic music visualizer, included as one Style Family. Each MilkDrop preset, built-in or imported, is one of its Styles.

**MilkDrop preset**:
MilkDrop's own word for one of its Styles. It is not a Preset.

**Look**:
Everything that decides how the Visual appears right now: the Style Family, the Style and every setting. Geode keeps it even when it is not saved.
_Avoid_: Scene, state

### Making it yours

**Customization**:
Changing how a Visual looks and behaves. It has three layers: Macros, Customization (the middle layer), and Advanced.

**Macro**:
One big control that changes several settings at once.
_Avoid_: Knob, master slider

**Advanced**:
The deepest layer of Customization: a full modulation system.
_Avoid_: Expert mode, pro

**Setting**:
One adjustable property of a Look. It means the same thing in every Style Family.
_Avoid_: Param, parameter

**Preset**:
A saved set of Settings, with no Style Family or Style. It can be loaded onto any Style.
_Avoid_: Scene Preset, MilkDrop preset

**Scene Preset**:
A saved Look: a Style Family, a Style and every Setting. Loading it switches to that Style.
_Avoid_: Preset (alone), scene

### Recording and video

**Recording**:
A video of the live Visual and its sound, captured in real time while you watch.
_Avoid_: Render, capture

**Render**:
Making a video file offline. Either a song or playlist with its Visual, which can look ahead in the song and use better analysis, resolution and frame rate than the live Visual, or an edited clip in the Editing Lounge.
_Avoid_: Export, recording

**Export**:
Moving a finished video out of the app: saving it to the phone, a drive, or uploading it.
_Avoid_: Render, send

**Export Lounge**:
The part of Studio where you start a Render of a song or a playlist.

**Editing Lounge**:
The part of Studio where you cut, add effects to and finish videos, then Render them.
_Avoid_: Editor (alone)

**Clip**:
One piece of video being edited in the Editing Lounge.

### Safety

**Flash Safety**:
Keeping the Visual safe for people sensitive to flashing light, by limiting or turning off flashing.
_Avoid_: Photosensitivity mode, strobe guard

**Safety Consent**:
The warning you must accept once, before the first Visual plays.
_Avoid_: Disclaimer, onboarding

## Open questions

Places where the code or the app uses a different word, or where a term is not settled. Nothing here has been renamed.

1. **Middle layer name.** "Customization" is both the whole thing and its middle layer. Pick a different name for one of them.
2. **Style vs Style Family.** The code makes Style the main thing and gives it a `StyleFamily` (`feature/customization/api/.../Styles.kt`). The glossary makes the Family the group. The meaning is the same, but the order is the other way round.
3. **"Scene" in the code.** Styles are identified by `SceneId`, and legacy code uses `SceneParams`. Scene is not a domain word except inside "Scene Preset".
4. **No Scene Preset in the code.** There are two `Preset` classes. `app/.../data/PresetStore.kt` holds a `sceneId` and a `milkPreset`. `feature/customization/api/.../Presets.kt` holds a `style` and the ticked `parts`. Both tie a Preset to a Style, but the glossary says a Preset loads onto any Style.
5. **No Lounges in the code.** Studio code says `Export*` for the Export Lounge and `Editor*` for the Editing Lounge, so "export" in the code often means Render (e.g. `ExportService`, the "Rendering video" notification).
6. **Where the Export Lounge lives.** One Studio string says to Render from "Settings › Export video", not from Studio.
7. **"Look" in Studio.** The Editing Lounge's colour-grading section is called "Look" (`studio_section_look`), which clashes with the Visual's Look.
8. **"Preset" in the Music Library.** The equalizer has presets (`eq_preset_*`). Should the domain call them something else, e.g. EQ preset?
9. **"Send" vs Export.** Studio's button says "Send" and opens the share sheet. That is one kind of Export.
10. **Flash Safety wording.** The Safety Consent screen says the flash rate is always limited in the engine, while the product promises ways to limit or turn off flashing. Is the limit a fixed floor plus a user setting?
