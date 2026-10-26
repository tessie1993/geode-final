---
paths:
  - "feature/**"
  - "arch/**"
  - "design/**"
---

# Features, kernel and design system

ADR-0005 (`docs/adr/0005-features-are-gradle-modules.md`) is the law for module shape and what may depend on what; `FeatureArchitectureKonsistTest` in `:app` fails the PR on any module that breaks its table, with no exceptions list. Vocabulary for states, commands, effects and events is `CONTEXT.md`, "Architecture".

- A feature is `:feature:<name>:api`, `:core`, `:handlers` and `:ui`. Nothing outside the feature touches anything but its `:api`. `:api` and `:core` are pure JVM with no Android or Compose imports.
- A reducer is tested as a transition table through the feature's `:core` tests, using the fakes in the test fixtures of `:arch:kernel` and `:arch:audio` (`docs/ci.md`, "Adding tests").
- Mutation floors in `config/mutation/thresholds.properties` only ratchet upwards; a feature core must hold 80. A new feature adds its `:core` to that file and to the mutation matrix in `.github/workflows/ci.yml`.
- A change to a public interface of `:arch:*` or any `:api` changes its dump under `<module>/api/`. Dumps are written by CI: push a commit whose message contains `[api-dump]`, review the dump diff, then push again (`docs/ci.md`, "Updating the public API dumps").
- `:design` holds the App UI's glass tokens, theme and common components and nothing of the Visual; each feature's `:ui` keeps its own strings.
