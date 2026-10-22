# CI: the test suite

GitHub Actions is the only place Geode is built and tested. Agents never run Gradle, CMake or tests locally (see `CLAUDE.md`). A pull request is done when every check below is green.

## The checks

All of these run on every pull request, in `.github/workflows/ci.yml` unless noted.

| Check | What it gates | Where it is configured |
|---|---|---|
| Build debug APK | The app assembles, native code included. The APK is attached to the run for device testing (not a gate). | `ci.yml` |
| Lint | detekt (every module, with per-module baselines), ktlint, Android lint (every Android module), and the public API of the interface modules against their dumps. | `config/detekt/detekt.yml`, `build-logic/…/geode.kotlin-common.gradle.kts`, `geode.api-dump` |
| No glass in the App UI (source) | The App UI's source has no live blur and no glassmorphism library (#102). Runs first in the Lint job, after a self-test that proves it still catches them. See [No glass in the App UI](#no-glass-in-the-app-ui). | `tools/ci/check_no_glass.py` |
| No glass in the App UI (screens) | Claude looks at the App UI screenshot goldens and fails on any glassmorphism (#102). Runs after a self-test of its verdict logic with canned responses. Needs the `ANTHROPIC_API_KEY` secret. | `.github/workflows/glass-review.yml`, `tools/ci/review_glass_screens.py` |
| Kotlin unit tests and coverage | Every module's unit tests, including the JVM modules (`:engine:audio-core`, `:arch:*`, and each feature's `:api` and `:core`), and the architecture test. Line coverage per module is written to the job summary. | Kover, applied in `geode.kotlin-common` |
| Mutation tests | One job per module runs **every** mutant (Pitest), then `tools/ci/mutation_gate.py` checks the score. | `config/mutation/thresholds.properties`, `geode.mutation-jvm` / `geode.mutation-android` |
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

## No glass in the App UI

The owner's rule (#102): the App UI has no frosted-glass panels, no glassmorphism, no live blur or refraction behind its surfaces and no translucent glass orbs. "3D" is a depth illusion made of parallax, tilt and lighting. Two checks enforce it, and neither looks at names: what counts is what is drawn.

**Source** (`tools/ci/check_no_glass.py`, in the Lint job). It scans the production Kotlin of `:design` (`design/src/main`), every feature's `:ui` (`feature/*/ui/src/main`) and `app/src/main`, skipping `app/src/main/assets`, `app/src/main/cpp`, `engine/` and `core/`, because the Visual may blur. It fails on live blur APIs (`Modifier.blur`, `RenderEffect.createBlurEffect`, Compose's `BlurEffect`, `BlurMaskFilter`) and on the Haze, Cloudy, BlurView and Blurry libraries, whether imported or declared as a dependency in any Gradle build file or version catalog. It skips comments and string literals. Artwork blurred in advance and shipped as a bitmap, text shadows (`Shadow(blurRadius = …)`) and gradient scrims all pass. There is no allowlist.

**Screens** (`tools/ci/review_glass_screens.py`, the `glass-review.yml` workflow). It sends the App UI screenshot goldens (`app/src/test/screenshots` and `feature/*/ui/src/test/screenshots`, which the screenshots job proves match the code) to Claude (`claude-opus-5-5`), 20 to a request, with a strict rubric, and asks for a structured verdict per image:

- **Fail:** frosted or blurred translucent panels, cards, sheets or bars; a background visibly blurred through a surface; smoked translucent bars; glass-card styling; translucent glass orbs.
- **Pass:** opaque or textured surfaces; gradient scrims; natural-material art drawn as an image, such as sea glass or translucent stone pebbles; the audio-reactive Visual itself.

A pull request reviews only the screenshots it adds or changes against its base. A push to `main` or a manual run reviews all of them. Every verdict and its reason go to the job summary. A failing screenshot gets an annotation on its file, and so does any screenshot left without a verdict (an API error, a refusal, a malformed answer); either one fails the job. A self-test runs the parsing, batching and verdict logic against canned responses first, without the API. Claude Opus 5.5 accepts no temperature, so the review runs at effort `high` with a fixed rubric and a JSON schema. On a safety decline the API retries on a fallback model (`fallbacks: "default"`).

**The API key.** The review reads the repository secret `ANTHROPIC_API_KEY` (Settings > Secrets and variables > Actions). Without it the job fails with a message saying so; it never passes unreviewed. The key is never printed. Pull requests from forks get no secrets, so they fail this check.

**When a screen changes**, its golden changes too (re-recorded with `[record-goldens]`, see below). The review then sees the new picture on the next push.

## Mutation score floors

The mutation score counts every mutant. A mutant no test reaches counts as surviving, so untested code cannot score well by being skipped.

- **Module floors.** Each module has a floor in `config/mutation/thresholds.properties`. The floors only ratchet upwards: raise one when a module's score improves, and never lower one to get a PR through.
- **Feature cores.** Each feature's `:feature:<name>:core` module, and the `:arch:kernel` and `:arch:audio` modules, have a floor of 80 (ADR-0003, ADR-0005). A new feature adds its `:core` to `config/mutation/thresholds.properties` and to the mutation matrix in `ci.yml`, and its JVM modules to the unit-test step. Today these are Capture (`:feature:capture:core`), Playback (`:feature:playback:core`) and Customization (`:feature:customization:core`). The `feature-core` package floor (`dev.geode.feature.<name>.core`) still applies inside any module.

The job log lists the surviving mutants, and the HTML report is attached to the run.

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
