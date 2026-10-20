# Geode

An offline, private music player for Android that draws a real-time Visual reacting to the music, plus video export of that Visual.

## Language

### The Visual and the App UI

**Visual**:
The audio-reactive picture the engine draws every frame from the music: in full-screen visual mode, behind the Visuals tab, and inside Renders and Recordings. Only the Visual reacts to sound.
_Avoid_: visuals (for the App UI), graphics, animation, visualizer

**App UI**:
The app's own screens and controls: tabs, buttons, panels, sheets, the mini-player and the theme. It is never audio reactive and never appears in a Render or Recording. The "Visuals tab" is a place in the App UI where the Visual is chosen and customized.
_Avoid_: visuals, visual design, interface (an architecture term)

**Style**:
One selectable look for the Visual, such as a shader look, a Fluid look, a Cymatics profile or a MilkDrop preset. There are about 100 of them.
_Avoid_: Scene (an engine term), effect, visualizer

**Customization**:
The user's choices for how a Style looks and how it reacts to sound.
_Avoid_: Params, settings, tweaks

**Camera**:
The single viewpoint every Style is seen through: zoom, push-in, rotation, orbit, shake and travel.
_Avoid_: View params, motion params

**Sound signal**:
A value measured live from the audio that is playing, such as level, a band, a drum hit, the beat or rising energy. It is purely reactive: it never assumes anything about the song beyond what has already been heard.
_Avoid_: Feature, analysis, song structure

**Macro**:
One large control in the basic Customize view that moves several properties of a Style together. Each Style picks its own Macros.
_Avoid_: Preset knob, master slider

**Advanced view**:
The detailed layer of Customize under the Macros, opened on request.
_Avoid_: Expert mode, pro tab

**Reaction**:
A link from one Sound signal to one property of the Visual or the Camera, with an amount.
_Avoid_: Modulation, route, mapping, LFO target

### Studio

**Studio**:
The place where people turn music into video files: they make Renders and Recordings, edit them, and share the results.
_Avoid_: Editor, export screen

**Project**:
A folder of Renders, Recordings, Edits and Shorts that the user creates and names. Every video belongs to exactly one Project, chosen when a Render is set up, when a live Recording stops, or when a video is exported.
_Avoid_: album, collection, folder (for the concept)

**Render**:
A video file made offline from a song or playlist with a Customization. It remembers both, and its own render settings, so it can be made again at another shape or quality. Those settings belong to the Render, never to the song: the next Render of the same song starts from the user's chosen starting point.
_Avoid_: Export, take, clip

**Recording**:
A video file captured in real time from the live player, holding the Visual (never the App UI) and the sound that was playing.
_Avoid_: Take, screen capture

**Edit**:
A video made by cutting, looping and adding effects to one or more Renders or Recordings.
_Avoid_: Project, timeline, sequence

**Short**:
An Edit made for social feeds, typically vertical and under a minute.
_Avoid_: Reel, clip
