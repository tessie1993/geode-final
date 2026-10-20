#!/usr/bin/env bash
# Commits re-recorded goldens back to the pull request's branch (docs/ci.md). Used only when the
# head commit's message contains [record-goldens]. Two jobs may record at once (App UI screenshots
# and Visual frames), so the push rebases and retries.
#
#   commit_goldens.sh <branch> <message> <path>...
set -euo pipefail
branch="$1"; message="$2"; shift 2

git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git add -A -- "$@"
if git diff --cached --quiet; then
  echo "Goldens unchanged; nothing to commit."
  exit 0
fi
git commit -q -m "$message"
for attempt in 1 2 3 4 5; do
  git fetch -q origin "$branch"
  git rebase -q "origin/$branch"
  if git push -q origin "HEAD:refs/heads/$branch"; then
    echo "Pushed re-recorded goldens to $branch. Push any new commit to have CI verify against them."
    exit 0
  fi
  sleep $((attempt * 3))
done
echo "::error::could not push the recorded goldens to $branch"
exit 1
