# Geode

## Agent setup

`AGENTS.md` points other agents here. Personal Claude settings go in `.claude/settings.local.json`, which is not tracked.

When the user types `/<skill>`, run that skill. Their direct instructions take priority over any skill's process.

## Agent skills

### Issue tracker

Issues live in GitHub Issues on `tessie1993/geode-final`, worked through the GitHub MCP tools or the `gh` CLI (both allowed). See `docs/agents/issue-tracker.md`.

### Triage labels

Mapped onto existing repo labels: `needs-triage`, `question` (needs-info), `ready-for-agent`, `help wanted` (ready-for-human), `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `GLOSSARY.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.

## Navigating the code

Use the Graphify MCP tools (`mcp__graphify__*`, repository `tessie1993/geode-final`) before reading or grepping files:

- Where a symbol is defined: `graphify_find`.
- How something works or where a behaviour lives: `query_graph` (use `skeleton: true` to locate cheaply), or `graphify_rank_files`.
- Who calls X / what X calls: `graphify_callers` / `graphify_callees`.
- A file's dependencies and dependents: `graphify_file_neighbors`.
- Blast radius of a change: `graphify_impact` or `impact_and_risk`; linked tests: `graphify_tests_for`.

The graph reflects the last indexed commit (check `graph_stats` freshness). Confirm with the actual file before editing, and fall back to Grep/Read for files changed since indexing.
