# Issue tracker: GitHub

Issues and specs for this repo live as GitHub issues on `tessie1993/geode-final`. Use the `gh` CLI where it is available. In Claude Code cloud sessions there is no `gh`; use the GitHub MCP tools (`mcp__github__*`) named below instead.

## Conventions

- **Create an issue**: `gh issue create --title "..." --body "..."`, or `mcp__github__issue_write` with `method: create`.
- **Read an issue**: `gh issue view <number> --comments`, or `mcp__github__issue_read` (`get`, `get_comments`, `get_labels`, `get_sub_issues`).
- **List issues**: `gh issue list --state open --json number,title,body,labels,comments`, or `mcp__github__list_issues` with `labels` / `state` filters.
- **Comment on an issue**: `gh issue comment <number> --body "..."`, or `mcp__github__add_issue_comment`.
- **Apply / remove labels**: `gh issue edit <number> --add-label "..."` / `--remove-label "..."`, or `mcp__github__issue_write` with `method: update` and the full `labels` list.
- **Close**: `gh issue close <number> --comment "..."`, or comment and then `issue_write` with `state: closed`.

## Pull requests as a triage surface

**PRs as a request surface: no.** _(Set to `yes` if this repo treats external PRs as feature requests; `/triage` reads this flag.)_

When set to `yes`, PRs run through the same labels and states as issues, using the `gh pr` equivalents (or `mcp__github__pull_request_read`).

GitHub shares one number space across issues and PRs, so a bare `#42` may be either.

## When a skill says "publish to the issue tracker"

Create a GitHub issue.

## When a skill says "fetch the relevant ticket"

Read the issue with its comments.

## Wayfinding operations

Used by `/wayfinder`. The **map** is a single issue with **child** issues as tickets.

- **Map**: a single issue labelled `wayfinder:map`, holding the Destination / Notes / Decisions-so-far / Not-yet-specified / Out-of-scope body.
- **Child ticket**: a GitHub sub-issue of the map (`issue_write` with `parent_issue_number`, or `gh api` on the sub-issues endpoint). Labels: `wayfinder:<type>` (`research`/`prototype`/`grilling`/`task`). Once claimed, the ticket is assigned to the driving dev.
- **Blocking**: GitHub's native issue dependencies where the tool can reach them (`gh api --method POST repos/<owner>/<repo>/issues/<child>/dependencies/blocked_by -F issue_id=<blocker-db-id>`). The MCP tools cannot, so in cloud sessions use the fallback: a `Blocked by: #<n>, #<n>` line at the top of the child body. A ticket is unblocked when every blocker is closed.
- **Frontier query**: list the map's open sub-issues, drop any with an open blocker (native dependency or an open issue in the `Blocked by` line) or an assignee; first in map order wins.
- **Claim**: assign the ticket to the driving dev (`tessie1993`) as the session's first write.
- **Resolve**: comment the answer, close the issue, then append a context pointer (gist + link) to the map's Decisions-so-far.
