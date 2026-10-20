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
| `:feature:x:ui` | Android library, Compose | the **stateless screens** `XScreen(model, onAction)`, with previews and screenshots | its own `:api` only |

- **The reducer sits in between.** The UI sends Commands and renders state. Handlers only receive effects and report events. Neither the UI nor the handlers know about each other or about the reducer's internals.
- **Features talk only through each other's `:api`.** No module may depend on another feature's `:core`, `:handlers` or `:ui`. The compiler enforces this. The Konsist architecture test plus a module-graph check fail CI on any other break: Android code in `:api` or `:core`, a cycle, or a wrong-direction dependency.
- **Contracts are tracked.** Every `:api` module and both `:arch` modules have a checked-in dump of their public types and functions. CI fails when a public interface changes without its dump changing in the same PR, so every contract change is visible in review.
- **Failures are typed.** Handlers catch everything at their edge and report a named failure as an event; the reducer decides what happens and the app-wide error channel shows it. A failure reported while nothing shows the banner is logged, and the latest one is shown once when the banner next appears.
- **`:app`** only wires: Hilt bindings, routes, navigation, and the real adapters over the native engine.

## Running as well as possible

- **Reducers** run on the main thread (`Main.immediate`). They are tiny and pure, so a tap shows in the same frame. **Handlers** run on injected background dispatchers, so IO never blocks the App UI.
- **State is immutable** and published through `StateFlow` with distinct values only. Each screen observes the slice it shows, and screen models are stable (`@Immutable`), so Compose recomposes only what changed.
- **The per-frame path stays in C++** (ADR-0002). Kotlin sends a compact snapshot only when something changes (ADR-0004).
- **Separate modules build in parallel and from cache**, and CI tests each one on its own. A change rebuilds only the modules that depend on it.
- The release build keeps R8 full mode and avoids reflection in the hot paths.

## Native code

The C++ is split the same way. Audio, analysis, the Visual and rendering become separate libraries, each with one small public header as its contract, and a build check stops any library including another's insides. The "who moves what" table (Macros, LFOs and other Modulators, Reactions, Automations) is applied per frame in C++; Kotlin sends it as part of the Customization snapshot only when it changes.

## Numbers and buffers

- No loose numbers: each module keeps its sizes, limits and timeouts as named, typed constants in one place; the App UI uses design tokens only. Anything a person might want to change belongs in Settings.
- Buffers are sized dynamically wherever that is better. The audio buffer tunes itself: it starts at the phone's best size, grows a step after a crackle and eases back once stable, with no setting. How each Visual buffer is sized (MilkDrop's and the Styles') follows from an investigation of the shaders and the native Visual.

## Code shape

SOLID principles and size limits (function and file length, parameter count, nesting) are reported on each PR as warnings, not failures, with thresholds set after reading the current code.

## Considered options

- **Packages plus an architecture test (ADR-0003):** a weaker wall, caught only after compiling.
- **Two modules per feature (core + ui):** handlers would sit in the core module or in `:app`, mixing IO with the state machine or scattering it.
