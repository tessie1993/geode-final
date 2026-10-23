#!/usr/bin/env bash
# Commits generated files back to the pull request's branch (docs/ci.md). Used by the lint job's
# public API step when the head commit's message contains [api-dump].
#
# A newer push can start another run while one is still writing. Instead of rebasing, each attempt
# resets to the branch's latest commit and lays this run's generated files on top of it.
#
#   commit_goldens.sh <branch> <message> <path>...
set -euo pipefail
branch="$1"; message="$2"; shift 2

stash="$(mktemp -d)"
for path in "$@"; do
  mkdir -p "$stash/$(dirname "$path")"
  cp -r "$path" "$stash/$path"
done

git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
for attempt in 1 2 3 4 5; do
  git fetch -q origin "$branch"
  git reset -q --hard "origin/$branch"
  for path in "$@"; do
    rm -rf "$path"
    mkdir -p "$(dirname "$path")"
    cp -r "$stash/$path" "$path"
  done
  git add -A -- "$@"
  if git diff --cached --quiet; then
    echo "Nothing changed; nothing to commit."
    exit 0
  fi
  git commit -q -m "$message"
  if git push -q origin "HEAD:refs/heads/$branch"; then
    echo "Pushed the generated files to $branch. Push any new commit to have CI check against them."
    exit 0
  fi
  sleep $((attempt * 3))
done
echo "::error::could not push the generated files to $branch"
exit 1
