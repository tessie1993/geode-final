# Geode

## Agent skills

### Issue tracker

Issues live in GitHub Issues on `tessie1993/geode-final`, worked through the GitHub MCP tools or the `gh` CLI (both allowed). See `docs/agents/issue-tracker.md`.

### Triage labels

Default vocabulary: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.

## Navigating the code

Use the Graphify MCP tools (`mcp__graphify__*`, repository `tessie1993/geode-final`) before reading or grepping files:

- Where a symbol is defined: `graphify_find`.
- How something works or where a behaviour lives: `query_graph` (use `skeleton: true` to locate cheaply), or `graphify_rank_files`.
- Who calls X / what X calls: `graphify_callers` / `graphify_callees`.
- A file's dependencies and dependents: `graphify_file_neighbors`.
- Blast radius of a change: `graphify_impact` or `impact_and_risk`; linked tests: `graphify_tests_for`.

The graph reflects the last indexed commit (check `graph_stats` freshness). Confirm with the actual file before editing, and fall back to Grep/Read for files changed since indexing.

What the graph misses:

- The JNI hop. `GeodeNative.foo` (Kotlin, `:engine:audio-core`) is implemented by `Java_dev_geode_engine_bridge_GeodeNative_foo` in `app/src/main/cpp/`, a shim around one `geode_*` C function. There is no edge across that hop: look up the JNI name with `graphify_find` and continue with `graphify_callees` from there.
- Shaders. `app/src/main/assets/shaders/*.glsl` are not indexed. C++ names a shader by its file name, so `grep -rn "<name>.glsl" core/viz core/render` finds the pass that owns it; `docs/visual-frames.md` says what each pass draws.

Memory: `recall` before editing `core/bridge`, `core/audio` or `app/src/main/cpp`; `remember` a gotcha or a mid-ticket decision no document holds. A decision that lasts goes in `docs/adr/`, not in memory.

Rules scoped to a part of the tree live in `.claude/rules/` and load when a matching file is touched.

## Builds and tests

Tests first. Before writing a feature or a fix, write the tests that prove it: a reducer as a transition table in the feature's `:core` tests, C++ in `core/tests/*_test.cpp`, a shader property in `tools/glslcheck` (`docs/ci.md`, "Adding tests"). Push them as the PR's first commit, so CI shows them failing for the right reason; then push the code that makes them pass. A PR that adds behaviour without a test that failed before it is not done.

Never run Gradle, CMake or tests locally. One PR per issue; read `git diff --stat` before pushing and drop anything the issue did not ask for. Push to the PR; GitHub Actions is the only judge, and a ticket is done when CI is green. Which jobs a PR runs is chosen from the files it changes (`docs/ci.md`, "Which checks run"); a job that runs, runs in full, mutation tests included. `[ci-full]` in the head commit's message runs everything. What each check gates and the mutation floors: `docs/ci.md`.
