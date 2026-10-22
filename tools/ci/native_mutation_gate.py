#!/usr/bin/env python3
"""Mutation-score gate for one pure native library (docs/ci.md, "C++ mutation tests").

Reads the Mutation Testing Elements JSON that mull-runner's Elements reporter wrote, keeps the
mutants in the library's own directory, prints the score and every surviving mutant, and fails when
the score is below the library's floor in config/mutation/native-thresholds.properties, or when the
library has no floor.

The score is detected / all mutants, where a mutant no test reaches (NoCoverage) counts as not
detected, the same rule as the Kotlin gate (tools/ci/mutation_gate.py): untested code cannot score
well by being skipped.

  native_mutation_gate.py [--report-only] <library> <elements.json> <library dir> <thresholds>

--report-only prints the same report and never fails; it is for measuring a library before it has
a floor, and must not stay in ci.yml.
"""
import argparse
import json
import os
import sys

from mutation_gate import load_thresholds, score, summary

# Mutation Testing Elements statuses (schema 1.7), as Mull's Elements reporter maps them: a mutant
# the tests failed on, timed out on or crashed on is detected.
DETECTED = frozenset({"Killed", "Timeout", "RuntimeError"})
NOT_DETECTED = frozenset({"Survived", "NoCoverage"})
# Mutants that never ran as a test of the suite; they count neither way.
NOT_COUNTED = frozenset({"CompileError", "Ignored"})

EXIT_PASSED = 0
EXIT_BELOW_FLOOR = 1
EXIT_BAD_REPORT = 2


class BadReport(Exception):
    """The report cannot be scored: missing, unreadable, or a status this gate does not know."""


def library_mutants(report_path, library_dir):
    """Every (path, mutant) in the report whose file lies under library_dir."""
    try:
        with open(report_path, encoding="utf-8") as f:
            files = json.load(f)["files"]
    except (OSError, ValueError, KeyError) as e:
        raise BadReport(f"cannot read {report_path}: {e}") from e
    root = os.path.realpath(library_dir) + os.sep
    out = []
    for path, result in sorted(files.items()):
        if not os.path.realpath(path).startswith(root):
            continue
        rel = os.path.relpath(os.path.realpath(path))
        for mutant in result.get("mutants", []):
            status = mutant.get("status")
            if status not in DETECTED | NOT_DETECTED | NOT_COUNTED:
                raise BadReport(f"{rel}: unknown mutant status {status!r}")
            out.append((rel, mutant))
    if not out:
        # Every pure library has code to mutate: an empty set means the build or the paths are wrong,
        # and must not pass as a perfect score.
        raise BadReport(f"no mutants under {library_dir} in {report_path}")
    return out


def describe(path, mutant):
    start = mutant.get("location", {}).get("start", {})
    return (
        f"{mutant['status']}: {path}:{start.get('line', '?')}:{start.get('column', '?')} "
        f"{mutant.get('mutatorName', '?')} -> {mutant.get('replacement', '?')}"
    )


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--report-only", action="store_true")
    parser.add_argument("library")
    parser.add_argument("report")
    parser.add_argument("library_dir")
    parser.add_argument("thresholds")
    args = parser.parse_args(argv)

    try:
        mutants = library_mutants(args.report, args.library_dir)
    except BadReport as e:
        print(f"::error::{e}")
        return EXIT_BAD_REPORT
    counted = [(p, m) for p, m in mutants if m["status"] not in NOT_COUNTED]
    detected = sum(1 for _, m in counted if m["status"] in DETECTED)
    survivors = [describe(p, m) for p, m in counted if m["status"] in NOT_DETECTED]
    library_score = score(detected, len(counted))

    floor = load_thresholds(args.thresholds).get(args.library)
    floor_text = "no floor" if floor is None else f"floor {floor:g}%"
    mode_text = " (report only)" if args.report_only else ""
    summary(
        f"### C++ mutation score {args.library}: {library_score:.1f}% "
        f"({detected}/{len(counted)}), {floor_text}{mode_text}"
    )
    if survivors:
        print(f"::group::{len(survivors)} surviving or uncovered mutants")
        for line in survivors:
            print(line)
        print("::endgroup::")

    failures = []
    if floor is None:
        failures.append(f"{args.library} has no floor in {args.thresholds}")
    elif library_score < floor:
        failures.append(f"{args.library} scored {library_score:.1f}%, below its floor of {floor:g}%")
    level = "warning" if args.report_only else "error"
    for failure in failures:
        print(f"::{level}::{failure}")
    return EXIT_BELOW_FLOOR if failures and not args.report_only else EXIT_PASSED


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
