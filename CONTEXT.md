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
A group of Styles that move and react to music in the same way. Each Style belongs to exactly one Family; the Acid Family holds the Acid Styles.
_Avoid_: Family type, renderer

**Style**:
One variation within a Style Family, with its own look.
_Avoid_: Scene, theme

**MilkDrop**:
The classic music visualizer, included as one Style Family. Each MilkDrop preset, built-in or imported, is one of its Styles.

**MilkDrop preset**:
MilkDrop's own word for one of its Styles. It is not a kind of Preset.

**Look**:
A snapshot of every Setting, with no Style Family or Style. Geode keeps the current Look even when it is not saved.
_Avoid_: State

**Scene**:
A Style Family, a Style and a Look together: everything that decides how the Visual appears.
_Avoid_: Visualization

### Making it yours

**Customization**:
Changing how a Visual looks and behaves. It has three layers: Macros, Controls and Advanced.

**Macro**:
One big control that changes several settings at once.
_Avoid_: Knob, master slider

**Controls**:
The middle layer of Customization: clear Settings anyone can understand.
_Avoid_: Basic, Customization (for the layer)

**Advanced**:
The deepest layer of Customization: a full modulation system.
_Avoid_: Expert mode, pro

**Setting**:
One adjustable property of a Look. It means the same thing in every Style Family.
_Avoid_: Param, parameter

### Presets

**Preset**:
A saved snapshot of the values of one part of the app, kept to use later. There are many kinds, so always name the kind: Look Preset, Scene Preset, EQ Preset, Export Preset.
_Avoid_: Preset (alone, without its kind)

**Look Preset**:
A saved Look. It may record the Style it was made on, but it can be loaded onto any Style.

**Scene Preset**:
A saved Scene. Loading it switches to its Style Family and Style.

**EQ Preset**:
A saved set of equalizer values in the Music Library.

### Recording and video

**Recording**:
A video of the live Visual and its sound, captured in real time while you watch.
_Avoid_: Render, capture

**Render**:
Making a video file offline. Either a song or playlist with its Visual, which can look ahead in the song and use better analysis, resolution and frame rate than the live Visual, or an edited clip in the Editing Lounge.
_Avoid_: Export, recording

**Export**:
Moving a finished video out of the app: saving it to the phone, a drive, or uploading it.
_Avoid_: Render

**Export Lounge**:
The part of Studio (and only Studio) where you start a Render of a song or a playlist.

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

Places where the code or the app uses a different word, or where a term is not settled yet. Nothing has been renamed.

1. **Style vs Style Family in the code.** The code makes Style the main thing and gives it a `StyleFamily` (`feature/customization/api/.../Styles.kt`). The domain says a Family holds its own Styles.
2. **"Scene" in the code means Style.** Styles are identified by `SceneId`, and legacy code uses `SceneParams`. In the glossary, a Scene is Family + Style + Look.
3. **No Scene Preset in the code.** The two `Preset` classes (`app/.../data/PresetStore.kt` and `feature/customization/api/.../Presets.kt`) are closest to Look Presets that record their Style.
4. **No Lounges in the code.** Studio code says `Export*` for the Export Lounge and `Editor*` for the Editing Lounge, so "export" in the code often means Render (e.g. `ExportService`, the "Rendering video" notification).
5. **Export Lounge path.** One Studio string still says to Render from "Settings › Export video". The Export Lounge lives in Studio.
6. **Words reused across the app.** "Look" also names the Editing Lounge's colour section (`studio_section_look`), and "Preset" names several kinds. Context decides which is meant; no renames until each case is looked at.
7. **Studio's "Send" button.** It opens the share sheet (`studio_send`). Its place in the domain is unknown; it is probably one way to Export.
8. **Flash Safety rules.** The Safety Consent screen says the flash rate is always limited in the engine, while the product promises ways to limit or turn off flashing. To be decided later.
