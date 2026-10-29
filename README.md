# Geode

Android music player and real-time GPU music visualizer: Kotlin and Jetpack
Compose over a C++20 engine built by CMake and the NDK. What the app does is
in [PRODUCT.md](PRODUCT.md); the version history is in
[CHANGELOG.md](CHANGELOG.md). This file covers building, layout and tests.

v1.8.0 (versionCode 32); minSdk 31, targetSdk 36, compileSdk 37, ABIs
arm64-v8a and x86_64.

## Build

```bash
git submodule update --init --recursive   # projectM, kissfft, oboe, taglib
./gradlew assembleDebug                   # debug APK, native core included
./gradlew installDebug                    # install on a connected device
./gradlew ktlintCheck detekt              # style and static analysis
```

Requires JDK 17+, the Android SDK with `platforms;android-37`, NDK
`28.0.13004108` and CMake 3.22.1; `local.properties` must point at the SDK.
`tools/setup-android-sdk.sh` installs those packages. Release signing reads
`keystore.properties` (see `keystore.properties.example`).

CI is being re-added; there are no workflows in the tree right now.

## Layout

Gradle modules, package `dev.geode`, listed in `settings.gradle.kts`:

| Pattern | What it holds |
|---|---|
| `:app` | The application: wiring, services, manifest, JNI files in `app/src/main/cpp` |
| `:design`, `:design:elements:*` | Shared Compose design system and visual elements |
| `:engine:*` | Kotlin side of the native engine: JNI binding, audio plumbing, scenes, live input |
| `:arch:*` | Shared architecture primitives (`kernel`, `audio`) |
| `:feature:<name>:{api,core,handlers,ui}` | One feature per name (playback, library, visuals, studio, …), split into a public API, logic, handlers and Compose UI; `home` has only `api` and `ui` |

Convention plugins live in `build-logic/`; detekt and mutation-test config in
`config/`.

The native core is built by the root `CMakeLists.txt` into `libgeode.so`
(plus `libprojectM-4.so`, LGPL, dynamically linked). It is a set of static
libraries under `core/` — `api` (the `extern "C"` ABI that JNI calls),
`util`, `render`, `analysis`, `viz`, `audio/dsp`, `audio/player`, `library`,
`bridge` — with public headers in `core/<lib>/include/geode/<lib>/` (the two
audio libraries share `core/audio/include/geode/audio/`). Third-party
code is in git submodules under `third_party/`. Shaders and MilkDrop presets
ship as assets under `app/src/main/assets/`.

The app's manifest requests no `INTERNET` permission; Media3 merges in
`ACCESS_NETWORK_STATE`.

## Tests

```bash
./gradlew test                            # JVM unit tests, all modules
./gradlew connectedAndroidTest            # instrumented tests, needs a device
cmake -S core/host -B build-host && cmake --build build-host && ctest --test-dir build-host
```

Kotlin tests sit in each module's `src/test` and `src/androidTest`. The C++
GoogleTest suite is in `core/tests/` and runs on the host through
`core/host/` (no SDK, NDK or GPU; GoogleTest is fetched at configure time;
`-DGEODE_SANITIZE=ON` adds ASan and UBSan). `tools/glslcheck` compiles and
renders the fragment shaders headlessly (`node check.mjs --all`), and
`tools/hostlink/hostlink.sh` links the native renderer on the host with no
undefined symbols allowed.

## Other files

- `tools/build-projectm.md` — how projectM is built from the submodule.
- `THIRD_PARTY_NOTICES` — licences and attributions; the in-app notices asset
  mirrors it.
