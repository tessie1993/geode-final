# Domain Docs

How agents consume this repo's domain documentation when exploring the codebase.

## Before exploring, read these

- **`CONTEXT.md`** at the repo root: the glossary of the Visual, the App UI and the rest of Geode's language.
- **`docs/adr/`**: read the ADRs that touch the area you're about to work in, not all of them. Each is named by its decision; ADR-0005 amends ADR-0003.

Geode is a single-context repo: there is no `CONTEXT-MAP.md` and no per-context `CONTEXT.md`. New terms and decisions are added through the `domain-modeling` skill when they actually get resolved, never upfront.

## Use the glossary's vocabulary

When your output names a domain concept (in an issue title, a refactor proposal, a hypothesis, a test name), use the term as defined in `CONTEXT.md`, and never a synonym its _Avoid_ line rules out.

If the concept you need isn't in the glossary yet, that's a signal: either you're inventing language the project doesn't use (reconsider) or there's a real gap (note it for `domain-modeling`).

## Flag ADR conflicts

If your output contradicts an existing ADR, surface it explicitly rather than silently overriding:

> _Contradicts ADR-0001 (live stays purely reactive), but worth reopening because…_
