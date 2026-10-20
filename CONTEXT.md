# Geode

An offline, private music player for Android with a real-time visualizer that reacts to the music, plus video export of what it shows.

## Language

### Visuals

**Style**:
One selectable visual look, such as a shader look, a Fluid look, a Cymatics profile or a MilkDrop preset. There are about 100 of them.
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
A link from one Sound signal to one visual or Camera property, with an amount.
_Avoid_: Modulation, route, mapping, LFO target

### Studio

**Studio**:
The place where people turn music into video files: they make Renders and Recordings, edit them, and share the results.
_Avoid_: Editor, export screen

**Render**:
A video file made offline from a song or playlist with a Customization. It remembers both, so it can be made again at another shape or quality.
_Avoid_: Export, take, clip

**Recording**:
A video file captured in real time from the live player, holding what was on screen and the sound that was playing.
_Avoid_: Take, screen capture

**Edit**:
A video made by cutting, looping and adding effects to one or more Renders or Recordings.
_Avoid_: Project, timeline, sequence

**Short**:
An Edit made for social feeds, typically vertical and under a minute.
_Avoid_: Reel, clip
