#!/bin/bash
# SessionStart hook for Claude Code cloud sessions. It gets the checkout ready to read and to run
# the fast in-session checks (tools/precheck.sh); it never runs Gradle. Idempotent: the container
# state is cached once it finishes, so later sessions skip the downloads.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

cd "${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel)}"

# Native sources agents read and the host checks build: projectM (with its own submodules), Oboe,
# TagLib and kissfft. Shallow, as CI checks them out.
git submodule update --quiet --init --recursive --depth 1 --jobs 4 >&2

# pre-commit, pinned in tools/hooks/requirements.txt, in the venv the hooks config already ignores.
if [ ! -x .venv-hooks/bin/pre-commit ]; then
  python3 -m venv .venv-hooks
fi
.venv-hooks/bin/pip install --quiet --disable-pip-version-check -r tools/hooks/requirements.txt
.venv-hooks/bin/pre-commit install-hooks >/dev/null
# Only the pre-push hook: tools/precheck.sh on every push. The commit-time hooks stay off in
# sessions, since the ktlint one runs Gradle and agents never run Gradle (CLAUDE.md).
.venv-hooks/bin/pre-commit install --hook-type pre-push --allow-missing-config >/dev/null

# The standalone ktlint the Gradle plugin uses, for checking Kotlin without Gradle.
KTLINT_VERSION=1.7.1
KTLINT="$HOME/.cache/geode/ktlint-$KTLINT_VERSION"
if [ ! -x "$KTLINT" ]; then
  mkdir -p "$(dirname "$KTLINT")"
  curl -sSfL -o "$KTLINT.tmp" "https://github.com/pinterest/ktlint/releases/download/$KTLINT_VERSION/ktlint"
  chmod +x "$KTLINT.tmp"
  mv "$KTLINT.tmp" "$KTLINT"
fi

if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  {
    echo "export GEODE_KTLINT=\"$KTLINT\""
    echo "export PATH=\"$PWD/.venv-hooks/bin:\$PATH\""
  } >> "$CLAUDE_ENV_FILE"
fi
