# Features are app-scoped reducer stores behind stateless screens; PlayerSession is replaced in one switch-over

> **Amended by ADR-0005:** features are Gradle modules (`:feature:<name>` + `:feature:<name>:ui`), not packages in `:app`.

Every feature (Playback, Library, Customization, Capture, Studio, Settings) is one package in `:app`, `feature/<name>/core` and `feature/<name>/ui`. Its core is a pure reducer, `reduce(state, event) → Next(state, effects)`. States are sealed types matched with exhaustive `when` (no `else`). Effects are plain data carried out by the feature's effect runner, and their results come back as events. Timers are effects too: started on entering a state, cancelled on leaving it, and tested with a fake clock. No MVI library.

Each feature runs in one app-scoped `@Singleton` store, so switching tabs or leaving full-screen visual mode never restarts the music, the Visual or a Recording. The per-frame path (audio → Sound signals → Reactions → pixels) stays in C++ (ADR-0002). The reducer only runs when something changes.

Features talk only through each other's interface: a read-only `state`, `send(Command)`, and an optional `events` stream of one-off facts the feature owns. There is no global event bus. Dependencies point one way: screens → features → the audio engine, with Settings and the engine at the bottom. The shared native audio pipeline is an adapter beneath Playback and Capture, not a feature.

Each place in the app (a tab, a sheet, full-screen visual mode) has one `XRoute()`. Only the route touches its thin `@HiltViewModel`, permissions and launchers. It calls `XScreen(model, onAction)`: an immutable screen model and a single action callback. Nothing below a route gets a ViewModel, a store, IO or a service start. App-UI-only state, such as whether a panel is open, stays in Compose `remember`.

A feature is done when CI shows:
- reducer tests written as transition tables, covering every state and event;
- a Pitest mutation score of at least 80% on its `core`;
- Roborazzi screenshots of its screens, taken from their `@Preview`s. These cover the App UI only; the Visual is checked by the shader golden-frame tests;
- the Konsist architecture test passing: `core` imports no Android or Compose, `ui` imports no other feature's internals, and the feature graph has no cycles.

Ids and units (`TrackId`, `StyleId`, `RenderId`, `Millis`, `Hz` …) become `@JvmInline value class`es in one PR, before the feature work.

`PlayerSession` and its controllers are not shrunk gradually. The new features are built alongside the old code on `main`, unused, each in its own CI-checked PR. Capture goes first as the tracer bullet; the rest follow in parallel. One switch-over PR then points the app at them and deletes `PlayerSession`, the `Host` callbacks and the old controllers. Until then, no new code goes into `PlayerSession`.

## Considered options

- **Gradle module per feature:** a stronger wall, but build setup and Hilt wiring for every slice and a slower CI. The packages can still be promoted to modules later.
- **Reducer that returns state only:** simpler, but the IO decisions, where today's bugs live, would stay outside the tested core.
- **Typed app event bus, or one root reducer:** both bring back an untraceable hub.
- **Reducer inside each screen's ViewModel:** capture and playback would need a second holder anyway.
- **One callback per action on screens:** reads naturally, but busy screens end up with 20+ parameters.
- **Shrinking `PlayerSession` slice by slice:** safer per step. The owner chose one switch-over so that the features can be built in parallel. Building alongside on `main` keeps the app working the whole time.

## Consequences

- The old "Player slice dissolves `PlayerSession`" plan is replaced by the switch-over ticket.
- Until the switch-over, two implementations exist side by side. The new code is reachable only from tests and previews.
