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
One large control in the basic Customize view. It is a single value: changing it changes every property linked to it. Each Style has 6, shipped as suggestions that the user can clear, rebuild, rename or remap.
_Avoid_: Preset knob, master slider

**Shared core**:
The controls every Style has, MilkDrop included: Camera, colours and palette, speed and energy, detail and glow, kaleidoscope and mirror, trails and echo, lens effects, warp and ripple, glitch and pixelate, particles overlay, and colour grading.
_Avoid_: Global params, common FX

**Extras**:
A Style's own controls on top of the Shared core, taken from that Style's real shader variables.
_Avoid_: Scene params, custom params

**Advanced view**:
The detailed layer of Customize under the Macros, opened on request.
_Avoid_: Expert mode, pro tab

**Reaction**:
A link from one Sound signal or Modulator to one target: a property of the Visual or Camera, a Macro, another Modulator, or an on/off switch. It has an amount and direction, rise and fall, a range and a curve.
_Avoid_: Modulation, route, mapping, LFO target

**Modulator**:
A generated value that moves targets the way a Sound signal does: an LFO wave, a hit envelope, a step sequencer or a smooth random drift. Each one has its own start setting, and it does not run until it is linked to something. Not a Sound signal, because it is not measured from the audio.
_Avoid_: LFO (only one kind), oscillator

**Automation**:
Behaviour the Customization performs by itself over the music: switching Style, changing the palette, Camera moves on the beat, sequenced effects, and autopilot Macros.
_Avoid_: Auto visuals, scripts

**No-flash rule**:
The Visual never flashes. An engine-wide limit on how fast overall brightness may change bounds every Style, Reaction, Modulator and Automation, live and in every Render and Recording. It has no switch.
_Avoid_: Strobe safety, epilepsy mode

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

### Architecture

**Feature**:
One part of the app (Playback, Library, Customization, Capture, Studio, Settings), built as four Gradle modules: its interface, its state machine, its handlers and its screens (ADR-0005). Other features and screens reach it only through its interface: its read-only state, `send(Command)` and its `events`.
_Avoid_: slice, controller

**Feature interface**:
A feature's `:api` module: the state the outside may read, its Commands and its events. The only part of a feature that anything outside it may depend on.
_Avoid_: contract, facade

**Feature store**:
The one app-scoped holder of a feature's state. It runs every command and result through the feature's reducer on the main thread and carries out the effects the reducer decides.
_Avoid_: ViewModel, session, controller

**Effect**:
Something a reducer asks to have done: work for the feature's handlers, starting or cancelling a timer, publishing one of the feature's events, or reporting a failure. What the work finds out comes back to the reducer as an event.
_Avoid_: side effect, action

**Handlers**:
A feature's `:handlers` module: the code that carries out its effects (IO, the database, the audio adapter, the system) on a background thread and reports the results back as events.
_Avoid_: repository, use case, interactor

**Kernel**:
The shared base every feature store is built from (`:arch:kernel`): the store, effects, timers and the error channel. It is not a feature.
_Avoid_: framework, base feature

**Error channel**:
The one app-wide channel every feature reports its failures to, shown in one App UI banner and written to the local log (ADR-0004). Reporting never stops the audio or the Visual.
_Avoid_: event bus, crash reporter

**Audio adapter**:
The one interface features use to reach the shared native audio pipeline (`:arch:audio`): the player, live capture from the microphone or a USB or line input, and the analysis that feeds the Visual. It announces what happens rather than being polled. It sits beneath Playback and Capture and is not a feature.
_Avoid_: audio service, player session

**Announcement**:
A change the audio adapter reports as it happens, such as playback started, paused, seeked, track changed, ended or failed, or a capture running, gone quiet or lost.
_Avoid_: callback, status poll
