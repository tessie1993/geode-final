# Native libraries

The C++ under `core/` is split into static libraries, one per directory (ADR-0005, "Native code"). The app's JNI library `libgeode.so` links them all through the `geode_core` interface target, so its link line is what the single `geode_core` static library used to give it.

## Layout

```
core/<lib>/
  include/geode/<lib>/   public headers: the library's contract, included as "geode/<lib>/Header.hpp"
  ...                    sources and private headers, included relative to core/<lib>/
```

Each library exports only `core/<lib>/include` (`target_include_directories(... PUBLIC ...)`); its own directory is a private include path. A header becomes public only when another library, the host tests or the JNI layer needs it, together with whatever public headers it includes in turn.

## Libraries and dependencies

| Target | Directory | What it holds | Depends on (public / private) |
|---|---|---|---|
| `geode_api_types` (interface) | `core/api/include` | `geode/api/geode_api.h`: the C ABI's types and functions | — |
| `geode_util` | `core/util` | JSON, SHA-256, logging | — |
| `geode_render` | `core/render` | GL plumbing: capability probing, GL profile, programs and the program binary cache, textures, framebuffers, the fullscreen quad, compute, shader sources | — / `util` |
| `geode_analysis` | `core/analysis` | FFT, bands, onsets, tempo, beats, key, structure, the feature frame | `api_types` / — |
| `geode_audio` | `core/audio` | `dsp/` (equalizer, crossfeed, limiter, and `geode_dsp_*`, the DSP part of the C ABI, which the mixer itself calls) and `player/` (decode, resample, mixer, Oboe output with its self-tuning buffer) | `api_types` / `util` |
| `geode_library` | `core/library` | TagLib tag reading and writing | — |
| `geode_viz` | `core/viz` | The Visual: renderer, frame graph, parameter model, modulators, scenes | `api_types`, `render` / `analysis`, `util` |
| `geode_api` | `core/api` | The rest of the C ABI implementation (analysis, Visual, tags, player) | `api_types` / `analysis`, `audio`, `library`, `viz`, `util` |
| `geode_core` (interface) | — | What `libgeode.so` links: all of the above | everything |

Layered from the bottom up; each library depends only on layers below it:

```
layer 3   geode_api       -> viz, audio, library, analysis, util, api_types
layer 2   geode_viz       -> render, analysis, util, api_types
layer 1   geode_render    -> util
          geode_analysis  -> api_types
          geode_audio     -> util, api_types
          geode_library   -> (TagLib only)
layer 0   geode_util, geode_api_types
```

No library includes or links anything in its own layer or above. The include check cannot see a call made without an include, so a symbol-level cycle would have to be caught in review; the one there was (the mixer calling `geode_dsp_process`, defined under `core/api`) was removed by moving `geode_dsp_api.cpp` into `audio`. The C ABI header is its own interface target because every library speaks its types while `geode_api`, which implements it, depends on every library; folding the two together would make a cycle.

The Visual (`viz`) and the App UI stay apart: nothing native knows about the App UI, and the JNI layer sees only `geode/api/geode_api.h`.

## What keeps it this way

- **CMake.** `core/native_libraries.cmake` defines `geode_core_library()`, which gives each library its public and private include directories. `core/CMakeLists.txt` (device) and `core/host/CMakeLists.txt` (host tests) both use it, so the boundaries are the same in both builds.
- **CI.** `tools/ci/check_native_includes.py` runs first in the C++ job. It resolves every `#include` in `core/` and `app/src/main/cpp/` the way the compiler would and fails when a file reaches another library's private header (by any path, `../` included) or a public header of a library it does not depend on. Its `LIBRARY_DEPS` table mirrors the `target_link_libraries` lines and is checked for cycles.
- **Host tests.** `core/pure_sources.cmake` keeps one list of host-testable sources per library; the host build compiles each list as that library's target and the tests link them through `geode_core_pure`.

## Known gaps

- **Public surfaces are wider than one header.** A library's public headers are the closure of what other libraries include: `AnalysisSession.hpp` holds its analysers by value, so their headers are public too; the same holds for `Renderer.hpp` in `viz`, `Player.hpp` and `DspChain.hpp` in `audio`, and the GL wrappers `viz` uses from `render`. Narrowing each library to one small header (for example by moving those members behind a pointer to an implementation) is a follow-up; the boundary check already holds whatever the surface is.
- **`render` has no host tests.** All of it needs GLES; `tools/hostlink` compiles and links it on the host with Mesa.
