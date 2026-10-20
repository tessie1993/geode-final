# Features are Gradle modules, each split into interface, state machine, handlers and screens (supersedes ADR-0003's package-per-feature)

The owner wants the parts of the app not interwoven: the state machine, the handlers and the screens are separate, with the reducer in between, so that editing one part cannot break the rest and the app runs as well as it can. Features are therefore **Gradle modules**, and each feature is split in four. Everything else in ADR-0003 stands: the pure reducer, effects as data, timers as effects, stateless screens and the CI bar.

## Shared foundation

- **`:arch:kernel`** (pure JVM): `Next`, `Reducer`, `Effect`, the feature store, timers and the app-wide error channel (ADR-0004). Its test fixtures include a fake clock.
- **`:arch:audio`** (pure JVM): the audio adapter interface and its announcements (ADR-0004). Its test fixtures include a fake adapter.

## Per feature: four modules, one direction

| Module | Kind | Holds | May depend on |
|---|---|---|---|
| `:feature:x:api` | pure JVM | the **public interface**: the state the outside may read, its Commands and its events | `:arch:kernel` |
| `:feature:x:core` | pure JVM | the **state machine and reducer**: sealed states, internal events, effects as data | its own `:api`, `:arch:kernel`, other features' `:api` |
| `:feature:x:handlers` | Android library | the **effect handlers**: they carry out effects (IO, database, the audio adapter, the system) and report results back as events | its own `:core` and `:api`, `:arch:*`, other features' `:api` (to send them Commands, e.g. Capture asking Playback to pause) |
| `:feature:x:ui` | Android library, Compose | the **stateless screens** `XScreen(model, onAction)`, with previews and screenshots | its own `:api` and `:design` |

- **The reducer sits in between.** The UI sends Commands and renders state. Handlers only receive effects and report events. Neither the UI nor the handlers know about each other or about the reducer's internals.
- **Features talk only through each other's `:api`.** No module may depend on another feature's `:core`, `:handlers` or `:ui`. The compiler enforces this. The Konsist architecture test plus a module-graph check fail CI on any other break: Android code in `:api` or `:core`, a cycle, or a wrong-direction dependency.
- **Contracts are tracked.** Every `:api` module and both `:arch` modules have a checked-in dump of their public types and functions. CI fails when a public interface changes without its dump changing in the same PR, so every contract change is visible in review.
- **Failures are typed.** Handlers catch everything at their edge and report a named failure as an event; the reducer decides what happens and the app-wide error channel shows it. A failure reported while nothing shows the banner is logged, and the latest one is shown once when the banner next appears.
- **Data ownership.** Playback holds the playlists and the play history in its own Room database, and the history can be saved as a playlist. Other features read them only through Playback's `:api`.
- **Diagnostics stay on the phone** (there is no internet permission): a "Share diagnostics" button in Settings writes a file with the log, settings and phone model, with no personal data, and a hidden developer overlay shows frame time, buffer sizes, audio crackles and the engine state.
- **`:design`** (Android library, Compose) holds the App UI's design tokens, theme and common components, never anything of the Visual. Each feature's `:ui` keeps its own strings.
- **`:app`** only wires: Hilt bindings, routes, navigation, and the real adapters over the native engine.

## Running as well as possible

- **Reducers** run on the main thread (`Main.immediate`). They are tiny and pure, so a tap shows in the same frame. **Handlers** run on injected background dispatchers, so IO never blocks the App UI.
- **State is immutable** and published through `StateFlow` with distinct values only. Each screen observes the slice it shows, and screen models are stable (`@Immutable`), so Compose recomposes only what changed.
- **The per-frame path stays in C++** (ADR-0002). Kotlin sends a compact snapshot only when something changes (ADR-0004).
- **Separate modules build in parallel and from cache**, and CI tests each one on its own. A change rebuilds only the modules that depend on it.
- The release build keeps R8 full mode and avoids reflection in the hot paths.

## Native code

The C++ is split the same way. Audio, analysis, the Visual and rendering become separate libraries, each with one small public header as its contract, and a build check stops any library including another's insides. The "who moves what" table (Macros, LFOs and other Modulators, Reactions, Automations) is applied per frame in C++; Kotlin sends it as part of the Customization snapshot only when it changes.

- **Hand-off without locks.** Kotlin writes a new snapshot into a spare slot and the drawing and audio threads pick it up at the start of their next frame, so they never wait on a lock.
- **One schema for both sides.** The snapshot is a binary format generated from one schema file (e.g. FlatBuffers) into both Kotlin and C++ types, so the two cannot drift. Shared preset files stay versioned JSON.
- **No exceptions across the bridge.** Every native call returns a typed status that Kotlin turns into a named failure. A crash inside C++ is caught at the bridge, reported as an engine failure, and the audio restarts by itself.
- **C++ is mutation tested too.** The pure native libraries (analysis, the mod matrix, audio maths) get a mutation score in CI with a floor that only ratchets upward.
- **The live wallpaper runs in the app's process** and shares its engine code.

## Numbers and buffers

- No loose numbers: each module keeps its sizes, limits and timeouts as named, typed constants in one place; the App UI uses design tokens only. Anything a person might want to change belongs in Settings.
- Buffers are sized dynamically wherever that is better. The audio buffer tunes itself: it starts at the phone's best size, grows a step after a crackle and eases back once stable, with no setting.
- **Visual buffers follow load and never go black.** The scene and trail buffers scale with load, with hysteresis so they don't flip back and forth. A resize copies the old picture into the new buffer and never clears to black. Simulations (Life, Myco, Ripple…) keep their own declared grid and reset only when that grid really changes. Heavy Styles may declare their own render scale in their manifest. Renders always draw at the exact export size, and export supersampling is a named setting.
- **MilkDrop adapts in two steps.** Under load it lowers its warp-mesh detail first, which is safe at any time. Its buffer size changes only at the next preset change, which already blends from the old picture. It gets the real paced frame rate, not a fixed 60.
- **Renders are frame-exact.** Our projectM copy is driven by the render clock during a render, so the same song and settings always give the same video.

## Code shape

SOLID principles and size limits (function and file length, parameter count, nesting) are reported as warnings, never failures: inline annotations on the changed lines of each PR, from detekt for Kotlin and lizard for C++. The thresholds sit near the current code's 95th–99th percentile (functions of about 60 lines, complexity 15, 8 parameters, nesting depth 4). The existing blocking detekt ratchet stays as it is, and its stale baseline entries are removed.

## Considered options

- **Packages plus an architecture test (ADR-0003):** a weaker wall, caught only after compiling.
- **Two modules per feature (core + ui):** handlers would sit in the core module or in `:app`, mixing IO with the state machine or scattering it.
