# Local pre-commit checks

We use [pre-commit](https://pre-commit.com/) because it checks the staged
snapshot, temporarily hides unstaged tracked edits, and restores them afterwards.
This also handles partially staged files. Hook versions are pinned in
[the configuration](../.pre-commit-config.yaml).

## Install once per clone

Python 3.10+ and Git are required. Kotlin checks also need the project's normal
Gradle/Android SDK/JDK environment. Git for Windows provides the hook shell.

Windows PowerShell:

```powershell
python -m venv .venv-hooks
.venv-hooks/Scripts/python.exe -m pip install -r tools/hooks/requirements.txt
.venv-hooks/Scripts/python.exe -m pre_commit install --install-hooks --allow-missing-config
```

macOS / Linux:

```sh
python3 -m venv .venv-hooks
.venv-hooks/bin/python -m pip install -r tools/hooks/requirements.txt
.venv-hooks/bin/python -m pre_commit install --install-hooks --allow-missing-config
```

Installation downloads the hook environments. Subsequent generic/native checks
use the cache; Gradle may still download missing build dependencies or toolchains.
Keep the virtual environment: the installed Git hook records its Python path.
Reinstall if you move or delete it. No activation is required.

Git worktrees share the installed hook. `--allow-missing-config` lets older
branches without the configuration continue to commit; branches containing the
configuration run these checks. The hook reads each worktree's own configuration.
An existing custom `core.hooksPath` must be reconciled before installing;
pre-commit deliberately refuses to overwrite that configuration.

## What runs

| Check | Scope |
| --- | --- |
| Whitespace | The staged diff, including partially staged files |
| Merge markers, filename portability, mixed line endings, private keys | Changed files |
| YAML, TOML, JSON, XML parsing | Changed matching files |
| clang-format 21.1.8 | Changed first-party C/C++ sources and headers, using [.clang-format](../.clang-format) |
| Cppcheck (Python wheel 1.5.3) | Changed C/C++ files: bug, performance, and portability diagnostics; C++20 and each native library's public `core/<lib>/include` directory |
| cmake-lint 0.6.13 | Changed CMake files, using [.cmake-format.yaml](../.cmake-format.yaml) |
| Kotlin ktlint | Changed Kotlin sources/scripts under `app/`, `engine/`, `arch/`, `design/` and `feature/`, using the existing Gradle plugin and Android rules |
| Gradle dependencies | Catalog syntax, exact release versions, version/bundle references, and literal Gradle dependency/plugin versions |
| Native dependencies | When `.gitmodules` or `third_party/` gitlinks change, check that declarations and staged submodule revisions agree |

Third-party source, generated output, and IDE state are excluded. These hooks
only check: they do not format files, run `git add`, or create commits. Correct
failures and stage the intended fixes before trying again. Formatting checks
inspect entire changed files, so editing an older file may expose existing style
violations. There is no repository-wide reformat as part of installation.

The Kotlin bridge uses the same staged-file filter as
[ktlint-gradle's own hook](https://github.com/JLLeitschuh/ktlint-gradle/blob/v14.2.0/plugin/src/main/kotlin/org/jlleitschuh/gradle/ktlint/GitHook.kt).
Root/build-logic Kotlin scripts are outside the module lint scope;
their dependency literals are still checked. Keep the bridge compatible when
upgrading the ktlint Gradle plugin.

Dependency checks are offline declaration checks, not vulnerability scanning or
resolution of the transitive dependency graph. Computed Kotlin versions are
left to Gradle. Versionless library declarations are allowed for BOM-managed
dependencies. Native pins use the Git index; they need no submodule checkout.

Full Android lint, Detekt, unit tests, and native compilation remain in the
existing build/CI workflow. Cppcheck, clang-format, and cmake-lint do not replace a C++
compiler or clang-tidy with an Android compilation database.

## Tool selection

- [pre-commit](https://pre-commit.com/) isolates staged content and manages pinned
  tool environments across Windows, Linux, and macOS. Lefthook is another capable
  manager; pre-commit fits the mixed Python/Kotlin/C++ tooling here directly.
- [clang-format's pre-commit mirror](https://github.com/pre-commit/mirrors-clang-format)
  supplies portable binaries; `--dry-run --Werror` makes formatting check-only.
- [Cppcheck](https://cppcheck.sourceforge.io/manual.html) catches C/C++ defects
  without requiring a full Android build. The pinned
  [Python wheel](https://pypi.org/project/cppcheck/1.5.3/) supplies the executable.
- [cmake-format-precommit](https://github.com/cheshirekow/cmake-format-precommit)
  supplies the CMake linter.
- [OSV-Scanner](https://google.github.io/osv-scanner/supported-languages-and-lockfiles/)
  can scan dependencies for known vulnerabilities. It is not enabled here:
  this checkout lacks resolved Gradle lockfiles and native submodule revisions.
  Restore those inputs before using its result as a dependency security gate.

## Run manually

Replace the Windows Python path below with `.venv-hooks/bin/python` on POSIX:

```powershell
# Check the staged snapshot, just as a commit does.
.venv-hooks/Scripts/python.exe -m pre_commit run

# Check selected working-tree files (does not isolate staged content).
.venv-hooks/Scripts/python.exe -m pre_commit run --files core/analysis/WindowTable.cpp

# Broad working-tree audit; existing style violations may be reported.
.venv-hooks/Scripts/python.exe -m pre_commit run --all-files

# Inspect native pins even if no native dependency change is staged.
.venv-hooks/Scripts/python.exe tools/hooks/check_native_dependencies.py --all

# Test the local dependency validator without network access.
python -m unittest discover -s tools/hooks -p "test_*.py"
```

The whitespace and native-pin hooks intentionally inspect the index even during
`--all-files`; use the explicit native command above for a full pin audit.

## CI smoke validation

[Pre-commit smoke](../.github/workflows/hooks-smoke.yml) runs on pull requests
that change hook tooling, its configuration, or this document. It can also be
started manually with **Run workflow** after the workflow exists on the default
branch. It needs no hook installation on a contributor's machine. The existing
Android workflow continues to run its build and quality checks independently.

The smoke job's runner image, Python version and setup actions are pinned in the
workflow file; pre-commit and PyYAML in `tools/hooks/requirements.txt` and
`requirements-smoke.txt`. It validates the configuration and Python syntax, runs
the offline regression tests, and installs the actual environments specified by
`.pre-commit-config.yaml`. A fresh, job-local cache makes environment
installation part of each run. These are tool/version pins, not a hash-locked
transitive dependency or runner image lock.

`smoke_pre_commit.py` copies the unchanged hook configuration and checker scripts
into disposable Git repositories. It exercises:

- Passing staged fixtures through every configured hook, plus expected failures
  for file hygiene, C/C++ formatting and analysis, and CMake linting.
- Valid staged dependency declarations with invalid unstaged edits, and invalid
  staged declarations with valid unstaged edits, for catalogs and Gradle files.
- Partially staged native manifests, gitlink-only changes without a checkout,
  and unrelated commits with the checkout's existing missing native gitlinks.
- Kotlin filename filtering (including spaces), staged content, and Gradle exit
  status propagation using an explicitly substituted **fixture Gradle wrapper**.
  This verifies the Python bridge; it does **not** execute Gradle or ktlint rules.
- Installation of a real Git pre-commit hook inside a fixture repository, with
  rejection of a bad commit followed by acceptance after an explicitly staged fix.

The tests compare index entries and working-tree bytes before and after hook
runs, including failure cases. They never install a Git hook in the source clone,
reformat source files, initialize native submodules, or run a full native pin audit.

This is smoke coverage of representative fixtures, **not** repository-wide lint,
an Android/native build, dependency resolution, cross-platform certification, or
a complete end-to-end Kotlin check. Full Kotlin checks remain in `ci.yml`.

To reproduce in a disposable environment (POSIX; use `Scripts/python.exe` on
Windows for the offline tests; the smoke fixtures target the Linux CI runner):

```sh
python3.12 -m venv .venv-hooks
.venv-hooks/bin/python -m pip install -r tools/hooks/requirements-smoke.txt
.venv-hooks/bin/python -m pip check
.venv-hooks/bin/python -m pre_commit validate-config
.venv-hooks/bin/python -m unittest discover -s tools/hooks -p 'test_*.py' -v
.venv-hooks/bin/python -m pre_commit install-hooks
.venv-hooks/bin/python tools/hooks/smoke_pre_commit.py -v
```

`install-hooks` prepares environments only; it does not activate a Git hook in
this clone. Cold-cache execution downloads packages and hook repositories.

Update pinned hook versions deliberately with `pre_commit autoupdate`, review
the configuration diff, and rerun the checks. To uninstall the local hook:

```powershell
.venv-hooks/Scripts/python.exe -m pre_commit uninstall
```
