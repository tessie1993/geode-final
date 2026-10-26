---
name: precheck
description: Run Geode's fast, Gradle-free CI checks over what the current branch changes, before pushing. Use before every push, after resolving a merge, or when CI failed on lint, format, schema, notices, Style packs or shaders.
---

# precheck

Run from the repository root (or the worktree you are working in):

```sh
tools/precheck.sh                        # changes since the merge base with origin/main
PRECHECK_BASE=<branch> tools/precheck.sh # a stacked branch: compare with its base instead
```

It always runs whitespace, native include boundaries, the bridge schema, the notices and the Style
packs, and by what changed: clang-format (pinned, through pre-commit), the host link check, cmake-lint,
ktlint (`$GEODE_KTLINT`) and glslang. It finishes in seconds unless the host link check runs (about a
minute). It never runs Gradle.

On failure:

- **clang-format**: `clang-format -i --style=file <file>` with the pinned version, or read the diff
  from `pre-commit run clang-format --files <file>`.
- **ktlint**: `$GEODE_KTLINT -F <files>`, then run it again plain to see what it cannot fix.
- **bridge schema**: `python3 tools/bridge_schema/generate.py`, then commit the generated files.
- **Style packs**: `python3 tools/ci/check_style_packs.py --write-kotlin` when it says `PackExtras.kt`
  is stale; otherwise fix the manifest it names (inputs and reactivity must match the shader exactly).
- **notices**: a new shipped dependency or projectM patch needs its notices entry (docs/ci.md).

A clean precheck proves nothing about the build, the unit tests or detekt: CI is still the judge.
For a C++-only change you may also run the host GoogleTest suite (CLAUDE.md, "Builds and tests").
