# Features are Gradle modules (supersedes ADR-0003's package-per-feature)

The owner wants each part of the app walled off, so that editing one part cannot break the rest. Features are therefore **Gradle modules**, not packages inside `:app`. Everything else in ADR-0003 stands: the reducer core, effects as data, timers as effects, stateless screens and the CI bar.

- **`:arch:kernel`** (pure JVM) holds the reducer, effects, the feature store, timers and the app-wide error channel. Its test fixtures include a fake clock.
- **`:arch:audio`** (pure JVM) holds the audio adapter interface and its announcements (ADR-0004). Its test fixtures include a fake adapter. The real adapter, which wraps the native pipeline, lives in `:app`.
- **Each feature has two modules:**
  - `:feature:<name>` (pure JVM): the feature's public interface (state, commands, events) plus its reducer. Internals are Kotlin `internal`. Other features may depend only on this module. Its mutation floor is 80%.
  - `:feature:<name>:ui` (Android library, Compose): stateless screens, previews and screenshots.
- **`:app`** wires features with Hilt, owns the routes and navigation, and holds the Android adapters.
- **The compiler enforces the walls.** The Konsist architecture test covers the rest: no Android code in cores, no `:ui` depending on another feature's `:ui`, and no cycles. Breaking either fails CI.
- **Threading:** reducers run on the main thread (they are tiny and pure, so screens update in the same frame). Effects run on injected background dispatchers.

## Considered options

- **Packages plus an architecture test (ADR-0003):** a weaker wall, caught only after compiling.
- **Modules for the cores only:** a middle ground, but screens could still reach across features.
