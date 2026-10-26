"""Which mutation jobs a CI run needs, from what it changes (docs/ci.md, "Which mutation jobs run").

A pull request runs a module's Pitest job, or a native library's Mull job, only when it changes one
of that job's paths in config/mutation/modules.json, or one of the shared paths, which run them all.
--full runs every job: nightly and manual runs, and a head commit whose message has [full-mutation].

Prints two lines for $GITHUB_OUTPUT, each a JSON matrix include list (empty list: skip the job):

    kotlin=[{"module": ..., "task": ..., "dir": ..., "name": ...}, ...]
    native=[{"library": ..., "dir": ...}, ...]

    python3 tools/ci/mutation_matrix.py --changed <file listing changed paths> [--full]
    python3 tools/ci/mutation_matrix.py --self-test
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONFIG = ROOT / "config" / "mutation" / "modules.json"


def _touches(path: str, prefixes: list[str], patterns: list[str]) -> bool:
    return any(path == p or path.startswith(p) for p in prefixes) or any(
        re.search(p, path) for p in patterns
    )


def select(config: dict, changed: list[str], full: bool) -> dict[str, list[dict]]:
    """The jobs to run per kind, in config order, without their path lists."""
    shared = config["shared"]
    result: dict[str, list[dict]] = {}
    for kind in ("kotlin", "native"):
        section = config[kind]
        run_all = full or any(
            _touches(p, shared + section["shared"], section["sharedPatterns"]) for p in changed
        )
        result[kind] = [
            {k: v for k, v in job.items() if k != "paths"}
            for job in section["jobs"]
            if run_all or any(_touches(p, job["paths"], []) for p in changed)
        ]
    return result


def check_config(config: dict, root: Path) -> list[str]:
    """Every job's directory exists, so a renamed module cannot silently drop out of the matrix."""
    return [
        f"{job['dir']} (in {CONFIG.name}) does not exist"
        for kind in ("kotlin", "native")
        for job in config[kind]["jobs"]
        if not (root / job["dir"]).is_dir()
    ]


def self_test() -> int:
    config = json.loads(CONFIG.read_text())
    names = lambda sel, kind: [j.get("name") or j["library"] for j in sel[kind]]  # noqa: E731
    cases = [
        (["docs/ci.md"], False, [], []),
        (["feature/studio/core/src/main/kotlin/A.kt"], False, ["studio-core"], []),
        (["feature/studio/ui/src/main/kotlin/A.kt"], False, [], []),
        (["engine/audio-core/src/main/kotlin/A.kt"], False, ["audio-core", "audio-android", "live"], []),
        (["core/analysis/Spectrum.cpp"], False, [], ["geode_analysis"]),
        (["core/tests/spectrum_test.cpp"], False, [], ["geode_util", "geode_audio", "geode_analysis"]),
        (["docs/ci.md"], True, [j["name"] for j in config["kotlin"]["jobs"]],
         [j["library"] for j in config["native"]["jobs"]]),
    ]
    failures = []
    for changed, full, kotlin, native in cases:
        sel = select(config, changed, full)
        if names(sel, "kotlin") != kotlin or names(sel, "native") != native:
            failures.append(f"{changed} full={full}: got {names(sel, 'kotlin')} {names(sel, 'native')}")
    every = select(config, [".github/workflows/ci.yml"], False)
    if len(every["kotlin"]) != len(config["kotlin"]["jobs"]) or len(every["native"]) != len(config["native"]["jobs"]):
        failures.append("a change to ci.yml must run every job")
    if any("paths" in job for kind in every.values() for job in kind):
        failures.append("the printed matrix must not carry path lists")
    for f in failures:
        print(f"self-test FAILED: {f}", file=sys.stderr)
    if not failures:
        print("mutation matrix self-test passed")
    return 1 if failures else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--changed", type=Path, help="file listing one changed path per line")
    parser.add_argument("--full", action="store_true", help="run every job")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    config = json.loads(CONFIG.read_text())
    errors = check_config(config, ROOT)
    for e in errors:
        print(f"::error::{e}", file=sys.stderr)
    if errors:
        return 1
    changed = [l.strip() for l in args.changed.read_text().splitlines() if l.strip()] if args.changed else []
    selected = select(config, changed, args.full or args.changed is None)
    for kind, jobs in selected.items():
        print(f"{kind}={json.dumps(jobs, separators=(',', ':'))}")
        print(f"{kind} mutation jobs: {', '.join(j.get('name') or j['library'] for j in jobs) or 'none'}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
