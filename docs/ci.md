# CI: the test suite

GitHub Actions is the only place Geode is built and tested. Agents never run Gradle, CMake or tests locally (see `CLAUDE.md`). A pull request is done when every check below is green.

## The checks

All of these run on every pull request, in `.github/workflows/ci.yml` unless noted.

| Check | What it gates | Where it is configured |
|---|---|---|
| Build debug APK | The app assembles, native code included. The APK is attached to the run for device testing (not a gate). | `ci.yml` |
| Lint | detekt (every module, with per-module baselines), ktlint, Android lint (every Android module), and the public API of the interface modules against their dumps. | `config/detekt/detekt.yml`, `build-logic/…/geode.kotlin-common.gradle.kts`, `geode.api-dump` |
| Kotlin unit tests and coverage | Every module's unit tests, including the JVM modules (`:engine:audio-core`, `:arch:*`, and each feature's `:api` and `:core`), and the architecture test. Line coverage per module is written to the job summary. | Kover, applied in `geode.kotlin-common` |
| Mutation tests | One job per module runs **every** mutant (Pitest), then `tools/ci/mutation_gate.py` checks the score. | `config/mutation/thresholds.properties`, `geode.mutation-jvm` / `geode.mutation-android` |
| C++ mutation tests | One job per pure native library (`geode_util`, `geode_audio`, `geode_analysis`, `geode_viz`) runs **every** mutant (Mull) over the host GoogleTest suite, then `tools/ci/native_mutation_gate.py` checks the score. | `config/mutation/native-thresholds.properties`, `config/mutation/mull.yml`, `core/host/CMakeLists.txt` (`GEODE_MUTATE`) |
| App UI screenshots | Roborazzi screenshots of App UI composables match the goldens in `app/src/test/screenshots` and in each feature's `:ui` (`feature/<name>/ui/src/test/screenshots`). | `geode.screenshots`, the job's `SCREENSHOT_*` lists in `ci.yml` |
| Native library boundaries | Each native library includes only its own headers and the public headers (`core/<lib>/include/geode/<lib>/`) of the libraries it depends on; the dependency graph has no cycles. Runs first in the C++ job, after a self-test that proves it still catches violations. | `tools/ci/check_native_includes.py`, [native-libraries.md](native-libraries.md) |
| Bridge schema | The C, C++ and Kotlin types generated from `core/bridge/schema/native_bridge.json` (the Customization snapshot and the bridge's status codes) match what is committed. Runs in the C++ job. To change the schema, edit it, run `python3 tools/bridge_schema/generate.py` and commit the generated files with it. | `tools/bridge_schema/generate.py` |
| C++ concurrency tests | The lock-free snapshot hand-off's tests, its concurrent stress tests included, built on the host under ThreadSanitizer (which cannot share a build with AddressSanitizer). | `ci.yml` (`native-concurrency`), `core/host/CMakeLists.txt` (`GEODE_SANITIZE_THREAD`) |
| C++ unit tests | GoogleTest suite in `core/tests`, built on the host under AddressSanitizer and UndefinedBehaviorSanitizer against the pure part of each native library, plus the `tools/hostlink` link check of `viz`, `render` and `util`. | `core/host/CMakeLists.txt`, `core/pure_sources.cmake`, `core/native_libraries.cmake` |
| MilkDrop Renders are frame-exact | Geode's copy of projectM (the submodule plus `tools/projectm-*.patch`) is built on the host and renders the same song twice on the render clock with the Render seed, interleaved with a live-style engine; every frame must match byte for byte, another seed must differ, and the picture must be lit and moving. | `tools/milkdrop-determinism`, `tools/projectm-patches.cmake` |
| Visual golden frames | Every Style's shader compiles, renders a non-black frame, and matches its golden in `tools/glslcheck/goldens`. Each Style's picture also survives a scene-target resize (`Framebuffer::resizePreserving`, ADR-0005 "Visual buffers follow load and never go black") without going dark, and matches its golden in `goldens/resize`. | `tools/glslcheck`, `tools/ci/compare_frames.py` |
| Play Store gates | The release APK: targetSdk ≥ Play's floor, permissions exactly as allowed (never INTERNET), 16 KB page alignment of zip entries and native libraries. | `tools/ci/play_gates.sh`, `config/play/` |
| Secret scan | gitleaks over the pull request's commits and the working tree. | `ci.yml` |
| Dependency review | No new dependency with a known high-severity vulnerability. | `ci.yml`, `dependency-graph.yml` |
| CodeQL | Code-safety analysis of the Kotlin and C++ code. | `codeql.yml` |
| Instrumented tests | Tests that need a real Android runtime, on an emulator. | `ci.yml` |

**Visual vs App UI.** The screenshot check covers the App UI (screens and controls). The golden-frame check covers the Visual (the audio-reactive picture). See `CONTEXT.md`.

## Mutation score floors

The mutation score counts every mutant. A mutant no test reaches counts as surviving, so untested code cannot score well by being skipped.

- **Module floors.** Each module has a floor in `config/mutation/thresholds.properties`. The floors only ratchet upwards: raise one when a module's score improves, and never lower one to get a PR through.
- **Feature cores.** Each feature's `:feature:<name>:core` module, and the `:arch:kernel` and `:arch:audio` modules, have a floor of 80 (ADR-0003, ADR-0005). A new feature adds its `:core` to `config/mutation/thresholds.properties` and to the mutation matrix in `ci.yml`, and its JVM modules to the unit-test step. Today these are Capture (`:feature:capture:core`), Playback (`:feature:playback:core`) and Customization (`:feature:customization:core`). The `feature-core` package floor (`dev.geode.feature.<name>.core`) still applies inside any module.

The job log lists the surviving mutants, and the HTML report is attached to the run.

## C++ mutation tests

The pure part of each native library (the sources in `core/pure_sources.cmake`: audio analysis, the DSP maths, the parameter model and Visual maths, JSON and SHA-256) is mutation tested with [Mull](https://github.com/mull-project/mull), one job per library, in the same way as the Kotlin modules.

- **How it runs.** The host build is configured with `-DGEODE_MUTATE=<library>`: Mull's compiler plugin builds every mutant of that one library into the test binary. `mull-runner` then switches the mutants on one at a time and runs the whole GoogleTest suite against each, every mutant, with no coverage filter: Mull 0.34's filter wrongly reports mutants inside switch cases as not reached (`NoCoverage`) and skips them, so the build has no source coverage. Which operators run, and which paths may be mutated, is in `config/mutation/mull.yml` (all of Mull's operators). Mull and its LLVM are pinned in `ci.yml` (Mull 0.34.1 for LLVM 18, the runner's clang), by package hash.
- **Score.** Killed, timed-out and crashed mutants are detected. A mutant no test reaches survives (and a `NoCoverage` mutant, should one be reported, counts as surviving), as on the Kotlin side. Only mutants in the library's own directory (`core/<lib>/`) count.
- **Floors.** Each library has a floor in `config/mutation/native-thresholds.properties`, keyed by its CMake target. They ratchet the same way as the module floors: raise one when the score improves, never lower one. A library with no floor fails the gate. A new pure library gets a matrix entry in the `native-mutation` job, a `GEODE_PURE_LIBRARIES` entry in `core/host/CMakeLists.txt`, its directory in `includePaths` in `mull.yml`, and a floor set at its first measured score, rounded down.
- **Results.** The job log lists every surviving and uncovered mutant (file, line, operator); the job summary has the score. The Mutation Testing Elements report (`<library>.json` and an `.html` viewer for it) is attached to the run as `native-mutation-<library>-<run>`.
- **Not mutated.** `render` and `library` have no pure part, and the device-only sources of `audio` and `viz` (Oboe, GLES, projectM) are outside the host build.

## Re-recording goldens

The screenshot and golden-frame goldens are recorded in CI, on the same runner image that verifies them, because rendering differs between machines. To re-record:

1. Push a commit whose message contains `[record-goldens]`. On that run, both jobs record instead of verifying, and push a commit with the new goldens to the PR branch.
2. Review the new images in the PR diff. They are the change being approved.
3. Push any further commit. Commits pushed by the CI bot do not start CI, so this push is what verifies against the new goldens.

## Updating the public API dumps

`:arch:kernel`, `:arch:audio` and every feature's `:api` apply `geode.api-dump`: the Kotlin Gradle plugin's ABI validation writes their public API to `<module>/api/`, and the lint job runs `checkKotlinAbi` against it, so any change to a public interface shows in the PR as a change to a dump. The dumps are written in CI, like the goldens, since agents never run Gradle locally:

1. Push a commit whose message contains `[api-dump]`. On that run the lint job runs `updateKotlinAbi` instead of the check, and pushes a commit with the new dumps to the PR branch.
2. Review the dump diff. It is the API change being approved.
3. Push any further commit, so CI checks against the new dumps.

## Adding tests

- **Kotlin:** tests go in `src/test` of the module. Feature cores are tested through their reducer, as transition tables (ADR-0003).
- **Feature modules (ADR-0005):** each feature is `:feature:<name>:api`, `:core`, `:handlers` and `:ui`, built with the `geode.feature-api`, `geode.feature-core`, `geode.feature-handlers` and `geode.feature-ui` convention plugins in `build-logic`. `:api` and `:core` are pure JVM modules (their tests run under `test`); `:handlers` and `:ui` are Android libraries, and `:ui` takes screenshots like the App UI does: a new feature adds its `:ui` to the `SCREENSHOT_*` lists of the screenshots job and its `:handlers` and `:ui` to the Android lint step. Tests use the fakes in the test fixtures of `:arch:kernel` (`FakeClock`, `inPlaceRuntime`) and `:arch:audio` (`FakeAudioAdapter`): `testImplementation(testFixtures(project(":arch:kernel")))`.
- **Architecture:** `FeatureArchitectureKonsistTest` (in `:app`, in the unit-test job) checks what the compiler cannot: `:arch:*`, `:api` and `:core` hold no Android or Compose code (read with Konsist); every module depends only on what the ADR-0005 table allows, read from `settings.gradle.kts` and each module's build file (`:ui` on its own `:api` and on `:design`; `:design` on nothing in the build, and nothing but a `:ui` and `:app` on `:design`; `:core` and `:handlers` may use another feature's `:api` and nothing else of it; nothing outside the feature on its `:core`, `:handlers` or `:ui`; only `:app` wires); and the feature graph has no cycles. There is no exceptions list (ADR-0004). `FeatureRulesTest` proves each rule reports a planted break. Tests named `*KonsistTest` read files rather than run code, so they stay out of mutation runs.
- **`:design`:** the App UI's design tokens (colours, type, spacing, sizes, corners), its theme (`CrystalTheme`) and common components (`CrystalButton` and friends), never anything of the Visual. `geode.feature-ui` adds it to every feature's `:ui`. Its pure parts (colour maths, the theme pack catalog, stone states) have unit tests in `design/src/test`; its components are screenshot-tested from `:app`, so their goldens stay in `app/src/test/screenshots`.
- **App UI:** screenshot tests are named `*ScreenshotTest`, which keeps them out of mutation runs. They render under Robolectric with `@GraphicsMode(NATIVE)` and save to `src/test/screenshots` of their module: `app/` and each feature's `:ui`. A feature's `:ui` adds its directory and its verify and record tasks to the `screenshots` job in `ci.yml`, and its Android modules to the Android lint step.
- **C++:** tests are `core/tests/*_test.cpp`. Only code listed in `core/pure_sources.cmake` can be tested on the host; it keeps one list per native library (`GEODE_UTIL_PURE_SOURCES`, `GEODE_BRIDGE_PURE_SOURCES`, `GEODE_AUDIO_PURE_SOURCES`, `GEODE_ANALYSIS_PURE_SOURCES`, `GEODE_VIZ_PURE_SOURCES`). Code that needs Android, GLES, projectM, Oboe or TagLib stays out of those lists. Tests include only public headers (`geode/<lib>/…`) of the pure libraries.
- **New native code:** put it in the library that owns it (`core/<lib>/`). A header other libraries need goes in `core/<lib>/include/geode/<lib>/`; anything else stays private and is included relative to `core/<lib>/`. A new dependency between libraries goes in `core/CMakeLists.txt` and in `LIBRARY_DEPS` in `tools/ci/check_native_includes.py`, and must keep the graph one-way ([native-libraries.md](native-libraries.md)).
