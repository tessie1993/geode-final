# Issue tracker: GitHub

Issues and specs for this repo live as GitHub issues on `tessie1993/geode-final`. Use the GitHub MCP tools (`mcp__github__*`) or the `gh` CLI (`gh issue`, `gh pr`, `gh api`), whichever is available. The conventions below name the MCP tools; the `gh` equivalents work the same way. In cloud sessions `gh` goes through a proxy that blocks repository settings writes, so settings (security features, branch protection) are changed by the owner in the GitHub UI.

## Conventions

- **Create an issue**: `issue_write` with `method: create`. Labels that don't exist yet are only created when no `parent_issue_number` is passed, so create first, then attach as a sub-issue.
- **Read an issue**: `issue_read` (`get`, `get_comments`, `get_labels`, `get_sub_issues`).
- **List issues**: `list_issues` with `labels` / `state` filters.
- **Comment on an issue**: `add_issue_comment`.
- **Apply / remove labels**: `issue_write` with `method: update` and the full `labels` list.
- **Close**: comment, then `issue_write` with `method: update`, `state: closed` and a `state_reason`.

## Pull requests as a triage surface

**PRs as a request surface: no.** _(Set to `yes` if this repo treats external PRs as feature requests; the triage skill reads this flag.)_

When set to `yes`, PRs run through the same labels and states as issues, read with `pull_request_read`.

GitHub shares one number space across issues and PRs, so a bare `#42` may be either.

## When a skill says "publish to the issue tracker"

Create a GitHub issue.

## When a skill says "fetch the relevant ticket"

Read the issue with its comments.

## Wayfinding operations

Used by the wayfinder skill, where installed. The **map** is a single issue with **child** issues as tickets.

- **Map**: a single issue labelled `wayfinder:map`, holding the Destination / Notes / Decisions-so-far / Not-yet-specified / Out-of-scope body.
- **Child ticket**: a GitHub sub-issue of the map: create it, then `sub_issue_write` with `method: add` and the child's numeric id. Labels: `wayfinder:<type>` (`research`/`prototype`/`grilling`/`task`). Once claimed, the ticket is assigned to the driving dev.
- **Blocking**: the MCP tools cannot set native issue dependencies, so a `Blocked by: #<n>, #<n>` line goes at the top of the child body. A ticket is unblocked when every blocker is closed.
- **Frontier query**: `issue_read get_sub_issues` on the map, keep open children, drop any with an open issue in its `Blocked by` line or an assignee; first in map order wins.
- **Claim**: assign the ticket to the driving dev (`tessie1993`) as the session's first write.
- **Resolve**: comment the answer, close the issue, then append a context pointer (gist + link) to the map's Decisions-so-far.
