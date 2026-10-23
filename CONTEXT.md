# Geode

An offline, private music player for Android that draws a real-time Visual reacting to the music, plus video export of that Visual.

## Language

### The Visual and the App UI

**Visual**:
The audio-reactive picture the engine draws every frame from the music: in full-screen visual mode, behind the Visuals tab, and inside Renders and Recordings. Only the Visual reacts to sound.
_Avoid_: visuals (for the App UI), graphics, animation, visualizer

**App UI**:
The app's own screens and controls: tabs, buttons, panels, sheets, the mini-player and the Element. It is never audio reactive and never appears in a Render or Recording. The "Visuals tab" is a place in the App UI where the Visual is chosen and customized.
_Avoid_: visuals, visual design, interface (an architecture term)

**Bar**:
The App UI's bottom navigation, the same in every Element and for every user (no page is ever hidden), in this order: Music, Visuals, Home, Studio, Settings. Music opens the music Library. Music and Visuals each have their own search, and they are not connected: there is no search across the whole app. Back from any page returns to Home, and Back on Home sends the app to the background.
_Avoid_: tabs, nav bar, menu

**Home**:
The middle page of the Bar: the Element's Hero scene with a now-playing card on it.
_Avoid_: dashboard, start screen

**First-run setup**:
What a new install shows before the app opens: a tour of the main features and basic use that can be skipped, the music, notification and microphone permissions, and the Save folder, which cannot be skipped.
_Avoid_: onboarding wizard, welcome flow

**Mini-player**:
The small player above the Bar. Tapping it always opens the full-screen Visual, unless Visuals off is on, when it opens the plain player.
_Avoid_: now-playing bar, player bar

**Player controls**:
Play/pause, skip, shuffle, repeat and the seek bar with its A-B loop. On the full-screen Visual every other touch belongs to the Style's touch interaction, so the controls appear only on a double tap and fade 3 seconds after the last touch on them. In Music they are always shown, at the bottom or the top as the user sets. Music also holds the queue. Geode shows no lyrics.
_Avoid_: transport, media controls

**Visuals off**:
The battery-saving setting, in Settings > Battery beside Visual quality and the CPU/GPU save mode, that stops the Visual entirely: nothing draws it, so the player shows the Element's Hero scene as a still backdrop image, and no Render or Recording can be made. The Element and its Hero scene still run, and Studio still works with videos already saved.
_Avoid_: low power mode, disable visualizer

**Element**:
One selectable skin for the App UI, named after a piece of nature (Water, Wood, Stone, Bioluminescence and others). It sets the material, palette, scene, motion, sounds and haptics. It never changes navigation, layout, which controls exist, their labels or what they do: every Element has the same screens.
_Avoid_: theme, style (a Style is a look for the Visual), skin pack, Theme pack

**Hero scene**:
The Element's full-screen nature scene behind Home. It moves only as a slow ambient loop and never reacts to sound.
_Avoid_: background visual, wallpaper, live visual

**Ambient**:
The setting for how much an Element's scene moves on its own: Drifting, Still or Off. It is never driven by the music.
_Avoid_: animation level, reactivity

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
A value measured live from the audio that is playing, such as level, a band, a drum hit, the beat or rising energy. It is purely reactive: it never assumes anything about the track beyond what has already been heard.
_Avoid_: Feature, analysis, track structure

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
Behaviour the Customization performs by itself over the music, each off until the person turns it on: switching Style, changing the palette, Camera moves on the beat, and sequence lanes. It acts only on what the live analysis reports and never assumes a tempo. There is no autopilot: a Macro drifts by itself only when a Modulator is linked to it. Automations are kept across Style changes and are not saved in presets.
_Avoid_: Auto visuals, scripts, autopilot

**No-flash rule**:
The Visual never flashes. An engine-wide limit on how fast overall brightness may change bounds every Style, Reaction, Modulator and Automation, live and in every Render and Recording. It has no switch.
_Avoid_: Strobe safety, epilepsy mode

### Music

**Track**:
One piece of music on the device, as the Library lists it and the player plays it.
_Avoid_: song, file

**Live input**:
The microphone as the sound source in place of a Track: the Visual reacts to the room instead of the music. It is a choice of sound source, not a Recording. There is no line or USB input.
_Avoid_: capture, mic mode

### Studio

**Studio**:
The place where people turn music into video files: they make Renders and Recordings, edit them, and share the results.
_Avoid_: Editor, export screen

**Project**:
A named group of Renders, Recordings, Edits and Shorts, kept as its own folder inside the Save folder. Every video belongs to exactly one Project, chosen when a Render is set up, when a live Recording stops, or when a video is exported. Only the user deletes videos: deleting a Project, after a confirmation, deletes its folder and every video in it, and nothing is ever deleted automatically.
_Avoid_: album, collection, folder (for the concept)

**Preset folder**:
The optional folder where saved Customization presets go, chosen in Settings > Default paths and separate from the Save folder. Until one is chosen, presets stay inside the app.
_Avoid_: preset path, library folder

**Save folder**:
The one folder on the device where every Project lives, chosen by the user during the first-run setup. The app does not finish starting until a Save folder is chosen, and if it is lost later (deleted, or access withdrawn) the app asks again before going on.
_Avoid_: export path, output directory

**Render**:
A video file made offline from a Track or playlist with a Customization. It remembers both, and its own render settings, so it can be made again at another shape or quality. Those settings belong to the Render, never to the song: the next Render of the same song starts from the user's chosen starting point.
_Avoid_: Export, take, clip

**Recording**:
A video file captured in real time from the live player, holding the Visual (never the App UI) and the sound that was playing. Making one is Capture.
_Avoid_: Take, screen capture

**Capture**:
Recording the Visual and the sound together, in real time, as a video: what makes a Recording. Nothing else is called capture.
_Avoid_: screen grab, record (for the microphone)

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

**Design system**:
The App UI's design tokens (colours, type, spacing, sizes, corners), its theme and its common components, in the `:design` module (ADR-0005). Every feature's screens may use it; it holds nothing of the Visual and depends on no feature. Each feature keeps its own strings.
_Avoid_: UI kit, common UI, theme module

**Error channel**:
The one app-wide channel every feature reports its failures to, shown in one App UI banner, over the full-screen Visual too, and written to the local log (ADR-0004). Reporting never stops the audio or the Visual.
_Avoid_: event bus, crash reporter

**Audio adapter**:
The one interface features use to reach the shared native audio pipeline (`:arch:audio`): the player, live capture from the microphone or a USB or line input, and the analysis that feeds the Visual. It announces what happens rather than being polled. It sits beneath Playback and Capture and is not a feature.
_Avoid_: audio service, player session

**Announcement**:
A change the audio adapter reports as it happens, such as playback started, paused, seeked, track changed, ended or failed, or a capture running, gone quiet or lost.
_Avoid_: callback, status poll
