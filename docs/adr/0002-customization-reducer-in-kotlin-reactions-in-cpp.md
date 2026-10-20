# Customization state lives in a Kotlin reducer; Reactions are evaluated in C++

The user's Customization is owned by a pure Kotlin reducer (state + intent → new state), which is unit- and mutation-tested in CI. The native renderer receives the resulting Customization and evaluates Reactions every frame in C++, where smoothing and the safety clamp already run for live view, export and wallpaper alike.

## Considered options

- All in C++: one language for the hot path, but the state logic would need a C++ test setup that does not exist yet, and Kotlin UI would still need its own copy of the state.
- All in Kotlin: easiest to test, but per-frame values would cross JNI every frame, and export and wallpaper would have to reimplement the evaluation.
