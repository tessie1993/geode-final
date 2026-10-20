# CI: the test suite

GitHub Actions is the only place Geode is built and tested. Agents never run Gradle, CMake or tests locally (see `CLAUDE.md`). A pull request is done when every check below is green.

## The checks

All of these run on every pull request, in `.github/workflows/ci.yml` unless noted.

| Check | What it gates | Where it is configured |
|---|---|---|
| Build debug APK | The app assembles, native code included. The APK is attached to the run for device testing (not a gate). | `ci.yml` |
| Lint | detekt (every module, with per-module baselines), ktlint, and Android lint (every Android module). | `config/detekt/detekt.yml`, `build-logic/…/geode.kotlin-common.gradle.kts` |
| Kotlin unit tests and coverage | Every module's unit tests, including the JVM module `:engine:audio-core`. Line coverage per module is written to the job summary. | Kover, applied in `geode.kotlin-common` |
| Mutation tests | One job per module runs **every** mutant (Pitest), then `tools/ci/mutation_gate.py` checks the score. | `config/mutation/thresholds.properties`, `geode.mutation-jvm` / `geode.mutation-android` |
| App UI screenshots | Roborazzi screenshots of App UI composables match the goldens in `app/src/test/screenshots`. | `geode.screenshots` |
| C++ unit tests | GoogleTest suite in `core/tests`, built on the host under AddressSanitizer and UndefinedBehaviorSanitizer, plus the `tools/hostlink` link check. | `core/host/CMakeLists.txt`, `core/pure_sources.cmake` |
| Visual golden frames | Every Style's shader compiles, renders a non-black frame, and matches its golden in `tools/glslcheck/goldens`. | `tools/glslcheck`, `tools/ci/compare_frames.py` |
| Play Store gates | The release APK: targetSdk ≥ Play's floor, permissions exactly as allowed (never INTERNET), 16 KB page alignment of zip entries and native libraries. | `tools/ci/play_gates.sh`, `config/play/` |
| Secret scan | gitleaks over the pull request's commits and the working tree. | `ci.yml` |
| Dependency review | No new dependency with a known high-severity vulnerability. | `ci.yml`, `dependency-graph.yml` |
| CodeQL | Code-safety analysis of the Kotlin and C++ code. | `codeql.yml` |
| Instrumented tests | Tests that need a real Android runtime, on an emulator. | `ci.yml` |

**Visual vs App UI.** The screenshot check covers the App UI (screens and controls). The golden-frame check covers the Visual (the audio-reactive picture). See `CONTEXT.md`.

## Mutation score floors

The mutation score counts every mutant. A mutant no test reaches counts as surviving, so untested code cannot score well by being skipped.

- **Module floors.** Each module has a floor in `config/mutation/thresholds.properties`. The floors only ratchet upwards: raise one when a module's score improves, and never lower one to get a PR through.
- **Feature cores.** Every feature core package (`dev.geode.feature.<name>.core`) must score at least `feature-core` (80%), whatever its module scores overall (ADR-0003). The shared kernel the stores are built from, `dev.geode.feature.kernel.core`, matches the same pattern and is held to the same floor; the job summary lists it as feature `kernel`.

The job log lists the surviving mutants, and the HTML report is attached to the run.

## Re-recording goldens

The screenshot and golden-frame goldens are recorded in CI, on the same runner image that verifies them, because rendering differs between machines. To re-record:

1. Push a commit whose message contains `[record-goldens]`. On that run, both jobs record instead of verifying, and push a commit with the new goldens to the PR branch.
2. Review the new images in the PR diff. They are the change being approved.
3. Push any further commit. Commits pushed by the CI bot do not start CI, so this push is what verifies against the new goldens.

## Adding tests

- **Kotlin:** tests go in `src/test` of the module. Feature cores are tested through their reducer, as transition tables (ADR-0003).
- **Architecture:** `FeatureArchitectureKonsistTest` checks `:app` against the rules of ADR-0003 with Konsist, in the unit-test job: feature cores import no Android or Compose, no feature uses another's internals (only its root package, which is its public interface), and the feature graph has no cycles. `FeatureRulesTest` proves each rule reports a planted violation. Tests named `*KonsistTest` read source files rather than run code, so they stay out of mutation runs.
- **App UI:** screenshot tests are named `*ScreenshotTest`, which keeps them out of mutation runs. They render under Robolectric with `@GraphicsMode(NATIVE)` and save to `app/src/test/screenshots`.
- **C++:** tests are `core/tests/*_test.cpp`. Only code listed in `core/pure_sources.cmake` can be tested on the host. Code that needs Android, GLES, projectM, Oboe or TagLib stays out of that list.
