---
name: steward
description: Geode's conventions for driving a pull request to green - the [api-dump] flow, mutation floors and which mutation jobs run, stacked branches, and how to read each failing CI job. Read before acting on CI or review events on a Geode PR.
---

# Driving a Geode PR to green

CI (GitHub Actions) is the only judge; never run Gradle locally (CLAUDE.md). Run `tools/precheck.sh`
before every push: one validated push beats three speculative ones, and every push cancels the
running CI (the workflow's concurrency group).

## Public API dumps (`[api-dump]`)

- A change to the public API of `:arch:*` or any feature's `:api` needs new dumps. Put `[api-dump]` in
  the message of the **head** commit you push; the lint job then runs `updateKotlinAbi` and a bot
  pushes "Dump the public API" to the branch.
- Fetch and merge that bot commit before pushing anything else, or your push races it.
- Review the dump diff: it is the API change being approved. The bot's push starts a new CI run that
  checks against the new dumps.
- "Public API" failing on a commit without `[api-dump]` means a dump is stale: push again with the tag.

## Mutation tests

- Floors only ratchet up (`config/mutation/thresholds.properties`, `native-thresholds.properties`).
  Never lower one to get a PR through; add tests for the surviving mutants the job log lists.
- Feature cores, `:arch:kernel` and `:arch:audio` stay at 80 or above.
- A pull request runs the mutation jobs for the modules it changes (`config/mutation/modules.json`,
  docs/ci.md "Which mutation jobs run"). Put `[full-mutation]` in the head commit message when the
  change could move a score elsewhere. A renamed module needs its `modules.json` entry renamed too.
- The Kotlin mutation jobs wait for lint and unit tests, the C++ ones for the C++ unit tests: when they
  show as skipped, fix the gate job first.

## Reading a failed job

- **Lint**: detekt prints its findings in "Show detekt findings". Usual limits: at most 3 boolean
  operators per condition, nesting depth (extract functions), at most 2 throws per function, a file
  named after its only top-level class. ktlint findings: `$GEODE_KTLINT -F <files>`.
- **Kotlin unit tests**: the report is attached as `unit-test-reports-<run>`. A compile error in a test
  source set shows here, not in Build.
- **C++ unit tests**: include boundaries and the bridge schema run first; then ASan/UBSan GoogleTest,
  then `tools/hostlink/hostlink.sh` (a missing definition or a new `.cpp` not in the CMake lists).
- **Shaders compile and render**: the Style pack check, then each Style's frame, then glslang. Every
  manifest's `inputs` and `reactivity` must match exactly what its shader reads.
- **MilkDrop frame-exact**: projectM patches (`tools/projectm-*.patch`) must apply in
  `tools/projectm-patches.cmake` order and their shipped copies in
  `app/src/main/assets/third_party/projectm/` must match (the notices job checks that).
- **Play Store gates**: never add INTERNET or any other uses-permission.
- **Instrumented tests**: one run of the job may be re-run if it died before any test body ran
  (emulator boot, runner loss); a second failure is real.

## Stacked branches and merging

- `pull_request` CI runs for any base, so a PR stacked on another gets CI; `Closes #N` only acts when
  the PR merges into main. After the base merges, retarget the PR to main.
- Never rewrite pushed history: merge the base in, never rebase or force-push.
- No new markdown docs in the repository (owner rule); notes belong in the scratchpad.
