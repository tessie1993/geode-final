# Geode

## Agent skills

### Issue tracker

Issues live in GitHub Issues on `tessie1993/geode-final`, worked through the GitHub MCP tools (no `gh` CLI). See `docs/agents/issue-tracker.md`.

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

## Builds and tests

Never run Gradle, CMake or tests locally. Push to the PR; GitHub Actions is the only judge, and a ticket is done when CI is green. Mutation tests are one of the suite's test types and run in full with the rest of it. What each check gates, the mutation floors, and how to re-record screenshot and Visual goldens (`[record-goldens]` in a commit message): `docs/ci.md`.
