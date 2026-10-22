# App-UI-only state is owned by one App UI reducer in `:app` (amends ADR-0003)

ADR-0003 left App-UI-only state, such as whether a panel is open, in Compose `remember`. The owner changed that on 2026-10-22 (#17, comment 6345182026): the App UI's logic is **one App UI reducer**, a state machine in `:app`, built like a feature's core. #182 builds it; the switch-over (#17) wires the new shell to it.

- **What it owns.** Everything the App UI shows that is not a feature's:
  - the back stack over the five places (Home, Player, Library, Visuals, Studio, always all five) and Settings opened from Home (#136);
  - the Visuals tab's sub-tab (Style, Customize, Modulators & Automation);
  - the open sheet, one at a time, including Customize as a bottom sheet over full-screen visual mode, during a Recording and from the player;
  - full-screen visual mode, and the Search overlay;
  - the one app-wide error banner (ADR-0004), which hears the shared error channel and shows one failure at a time;
  - the front-end events features and screens raise, sent out as the plain app signals the Elements turn into their own effects (#180 decision 5).
- **What stays in Compose `remember`.** Screen-local, short-lived state that nothing outside the screen needs: a text field's draft, a scroll position, an open menu, a press or an animation in progress.
- **How it is built.** As ADR-0003 and ADR-0005 say for a feature's core: a pure `AppUiReducer` with a sealed `AppUiState`, commands and events, returning `Next(state, effects)`. Collecting the error channel is its start effect, the banner's eight seconds are a timer effect, and the signals go out as `Emit`. It runs in one app-scoped `FeatureStore` over the app's `FeatureRuntime`, like the features.
- **Where it lives.** In `:app`, package `dev.geode.appui`, since ADR-0005 gives `:app` navigation and has no module kind for App UI logic. Its reducer and state are plain Kotlin with no Android or Compose, so it can move into a module of its own later without change.
- **The same bar as a feature core.** Transition-table tests of every command and event, a store test on the fake clock, and a mutation score of at least 80 on its package (the `app-ui` floor, checked inside `:app`'s run).
- **Elements never change it.** The state machine is the same in every Element (#180 decisions 2 and 4): an Element draws the transitions navigation decides, and never adds a route or a rule.

## Considered options

- **App-UI-only state in Compose `remember`, as ADR-0003 had it:** simple, but the back stack, sheets and banner then live in composables, untested by transition tables, and an Element's effects would have no single source of events.
- **A separate `:app-ui` module:** a stronger wall, but ADR-0005 has no such module kind and the owner placed the reducer in `:app`. The package keeps it pure so the move stays open.
- **The banner in Playback:** rejected by the owner (#147): the banner is the App UI's.
