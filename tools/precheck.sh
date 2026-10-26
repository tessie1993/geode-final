#!/usr/bin/env bash
# precheck - the fast, Gradle-free checks CI runs, over what this branch changes. It catches the
# slips that otherwise cost a full CI round (a format nit, a stale generated file, an include across
# a library boundary). CI stays the only judge: a clean precheck proves nothing about the build.
#
#   tools/precheck.sh               # changes since the merge base with origin/main
#   PRECHECK_BASE=<ref> tools/precheck.sh
#
# Runs on every push through the precheck pre-push hook (.pre-commit-config.yaml) once
# `pre-commit install --hook-type pre-push` has run (the Claude SessionStart hook does).
set -uo pipefail

cd "$(git rev-parse --show-toplevel)"
base=$(git merge-base "${PRECHECK_BASE:-origin/main}" HEAD)
mapfile -t changed < <(git diff --name-only --diff-filter=d "$base" -- . ':!third_party'; git ls-files --others --exclude-standard)

failed=()
step() {
  local name=$1; shift
  echo "== $name"
  if ! "$@"; then failed+=("$name"); fi
}
pick() { printf '%s\n' "${changed[@]}" | grep -E "$1" || true; }
pre_commit() { command -v pre-commit >/dev/null || [ -x .venv-hooks/bin/pre-commit ]; }
run_pre_commit() { if command -v pre-commit >/dev/null; then pre-commit "$@"; else .venv-hooks/bin/pre-commit "$@"; fi; }

echo "precheck: ${#changed[@]} changed files since ${base:0:7}"

step "whitespace" git diff --check "$base" -- . ':!third_party'
step "native include boundaries" python3 tools/ci/check_native_includes.py
step "bridge schema is current" python3 tools/bridge_schema/generate.py --check
step "third-party notices" python3 tools/ci/check_notices.py
step "Style packs" python3 tools/ci/check_style_packs.py

mapfile -t cxx < <(pick '\.(c|cc|cpp|h|hpp)$')
if [ ${#cxx[@]} -gt 0 ]; then
  if pre_commit; then
    step "clang-format" run_pre_commit run clang-format --files "${cxx[@]}"
  else
    step "clang-format (system, may differ from CI's pin)" clang-format --dry-run --Werror --style=file "${cxx[@]}"
  fi
fi
if [ -n "$(pick '^core/(viz|render|util|analysis)/')" ]; then
  step "host link check" tools/hostlink/hostlink.sh
fi

mapfile -t cmake < <(pick '(CMakeLists\.txt|\.cmake)$')
if [ ${#cmake[@]} -gt 0 ] && pre_commit; then
  step "cmake-lint" run_pre_commit run cmake-lint --files "${cmake[@]}"
fi

mapfile -t kotlin < <(pick '\.kts?$')
if [ ${#kotlin[@]} -gt 0 ]; then
  ktlint=${GEODE_KTLINT:-$(command -v ktlint || true)}
  if [ -n "$ktlint" ]; then
    step "ktlint" "$ktlint" --relative "${kotlin[@]}"
  else
    echo "== ktlint skipped: no ktlint (set GEODE_KTLINT; the SessionStart hook downloads it)"
  fi
fi

if [ -n "$(pick '^(app/src/main/assets/shaders/|engine/scenes/src/main/stylepacks/|tools/glslcheck/)')" ]; then
  if command -v glslangValidator >/dev/null; then
    step "glslang (GLSL ES 3.00)" node tools/glslcheck/check.mjs --all --glslang
  else
    echo "== glslang skipped: glslangValidator is not installed"
  fi
fi

if [ ${#failed[@]} -gt 0 ]; then
  echo "precheck FAILED: ${failed[*]}"
  exit 1
fi
echo "precheck passed"
