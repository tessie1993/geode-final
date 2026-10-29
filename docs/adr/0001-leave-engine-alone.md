# 1. Leave the audio engine and the Visual as they are

Date: 2026-10-29
Status: Accepted

## Context

Geode is Kotlin/Compose over a C++20 core (`core/*`). Real logic sits on both sides: `engine/*` (player, mic, ring buffers, presentation clock, analysis cache, renderer, frame pacing, thermal limits), `feature/customization/core` and `feature/visuals/core` are Kotlin over the native `core/*`.

We asked whether C++ should take over the whole audio→Visual core (sound sources, analysis, the data every Style reads, the Visual, Customization) and leave Kotlin as only the Android shell and App UI.

## Decision

We are not moving the core.

- Kotlin is the Android shell (player and mic setup, Service, lifecycle) and the App UI.
- The audio core is C++ (`core/*`).
- The audio engine (`engine/*`) is left alone. It works, and it is not ported, rewritten or restructured.
- The Visual and Customization stay as they are. Customization is part of the Visual (it is a tab in Visuals), not a separate core to move.

## Consequences

- No port is planned. Moving Studio and finishing features build on the current split.
- Changes to `engine/*` need a concrete bug or feature that requires them, and a new ADR if they change who owns what.
