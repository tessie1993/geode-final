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

The graph reflects the last indexed commit, which can lag main by hundreds of commits. Call `graph_stats` once per session and compare its `freshness.commit` with `git log -1 --format=%h origin/main`: when they differ, treat graph answers as leads only, confirm every one in the actual file, and use Grep/Read for anything touched since (`git diff --stat <indexed commit> origin/main`). Always confirm with the actual file before editing.

## Builds and tests

Never run Gradle, CMake or tests locally. Push to the PR; GitHub Actions is the only judge, and a ticket is done when CI is green. Two exceptions, both Gradle-free:

- **Before every push, run `tools/precheck.sh`** (the `precheck` skill). It runs CI's fast checks over what the branch changes, in seconds: whitespace, native include boundaries, the bridge schema, the notices, the Style packs, and by what changed clang-format, the host link check, cmake-lint, ktlint and glslang. `pre-commit run --files <changed>` is allowed too. In cloud sessions the SessionStart hook installs both and runs precheck on push.
- **For a change to C++ only, you may build and run the host GoogleTest suite**: `cmake -S core/host -B build-host -DCMAKE_BUILD_TYPE=Debug -DGEODE_SANITIZE=ON && cmake --build build-host --parallel && ctest --test-dir build-host --output-on-failure -R <test>`. Plain CMake, no NDK, no Gradle. CI still decides.

Driving a PR to green (the `[api-dump]` flow, mutation floors, reading failed jobs): the `steward` skill. Mutation tests are one of the suite's test types: a pull request runs them for the modules it changes, and every night they all run on main. What each check gates, which mutation jobs run and the mutation floors: `docs/ci.md`.
