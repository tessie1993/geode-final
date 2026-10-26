---
paths:
  - "core/**"
  - "app/src/main/cpp/**"
---

# Native core

The contract is `docs/native-libraries.md`: one static library per `core/<lib>/`, public headers only under `core/<lib>/include/geode/<lib>/`, and a one-way layered dependency graph that `tools/ci/check_native_includes.py` enforces on every PR. A new dependency between libraries goes into `core/CMakeLists.txt` and `LIBRARY_DEPS` in that script together, then `python3 tools/ci/native_graph_doc.py` regenerates the diagram.

- Only sources listed in `core/pure_sources.cmake` run on the host, so only they are unit tested and mutation tested. Code that needs Android, GLES, projectM, Oboe or TagLib stays out of those lists. How to add a test: `docs/ci.md`, "Adding tests".
- The hand-off from Kotlin to the drawing and audio threads is lock-free (`SnapshotExchange`, `TripleBuffer`): a reader never waits and a second publisher is told `GEODE_STATUS_BUSY`. Keep it that way, and keep the audio callback (`Mixer::render`) free of locks and allocation; it retires a swapped DSP chain through its own counter rather than a mutex.
- `app/src/main/cpp` holds marshalling only. Every entry point is `Java_dev_geode_engine_bridge_GeodeNative_<name>` for `GeodeNative.<name>` in `:engine:audio-core` (the one exception is the debug-only Style hot-reload attach for `StyleLab`), runs its body through `jni_guard.hpp`, and calls one `geode_*` function of the C ABI (`core/api/include/geode/api/geode_api.h`).
- The Customization snapshot's wire format is `core/bridge/schema/native_bridge.json`. To change it, edit the schema, run `python3 tools/bridge_schema/generate.py`, and commit the generated C, C++ and Kotlin with it.
