#!/usr/bin/env python3
"""One Mutation Testing Elements report from a library's parts (docs/ci.md, "C++ mutation tests").

A library that runs in parts (tools/ci/mull_part.py) writes one report per part. This joins them
into the report tools/ci/native_mutation_gate.py scores: the first part's report with every part's
files. A file in more than one part is an error (the parts split the library and never overlap),
and fewer reports than parts is an error, so a part that did not finish fails the gate by its
absence rather than passing by it. mutationScore is recomputed over the joined mutants.

  merge_mutation_reports.py --parts <n> <output.json> <part.json>...
  merge_mutation_reports.py --self-test
"""
import argparse
import json
import os
import sys
import tempfile

DETECTED = frozenset({"Killed", "Timeout", "RuntimeError"})
NOT_COUNTED = frozenset({"CompileError", "Ignored"})
EXIT_OK = 0
EXIT_ERROR = 2


class BadReports(Exception):
    """The parts cannot be joined: one is missing, unreadable or overlaps another."""


def load(path):
    try:
        with open(path, encoding="utf-8") as f:
            report = json.load(f)
        if not isinstance(report.get("files"), dict):
            raise ValueError("no files object")
    except (OSError, ValueError) as e:
        raise BadReports(f"cannot read {path}: {e}") from e
    return report


def merge(reports, parts):
    if len(reports) != parts:
        raise BadReports(f"expected {parts} part reports, found {len(reports)}: a part did not finish")
    merged = dict(reports[0])
    files = {}
    for report in reports:
        for path, result in report["files"].items():
            if path in files:
                raise BadReports(f"{path} is in more than one part; the parts must not overlap")
            files[path] = result
    merged["files"] = dict(sorted(files.items()))
    counted = [m for r in files.values() for m in r.get("mutants", []) if m.get("status") not in NOT_COUNTED]
    detected = sum(1 for m in counted if m.get("status") in DETECTED)
    merged["mutationScore"] = round(100 * detected / len(counted), 2) if counted else 0
    return merged


def write(output, merged):
    os.makedirs(os.path.dirname(os.path.abspath(output)) or ".", exist_ok=True)
    with open(output, "w", encoding="utf-8") as f:
        json.dump(merged, f, indent=2)
        f.write("\n")


def report(files):
    return {"schemaVersion": "1.7", "thresholds": {"high": 80, "low": 60}, "mutationScore": 0, "files": files}


def file_result(*statuses):
    return {"language": "cpp", "source": "", "mutants": [{"id": str(i), "status": s} for i, s in enumerate(statuses)]}


def self_test():
    ok = True

    def check(name, condition):
        nonlocal ok
        print(f"  {'ok ' if condition else 'FAIL'} {name}")
        ok = ok and condition

    a = report({"/r/core/x/A.cpp": file_result("Killed", "Survived"), "/r/core/x/B.hpp": file_result("Timeout")})
    b = report({"/r/core/x/C.cpp": file_result("Survived", "Ignored", "RuntimeError")})
    merged = merge([a, b], 2)
    check("every part's files are in the joined report", sorted(merged["files"]) == ["/r/core/x/A.cpp", "/r/core/x/B.hpp", "/r/core/x/C.cpp"])
    check("the mutants are kept as they were", merged["files"]["/r/core/x/C.cpp"] == b["files"]["/r/core/x/C.cpp"])
    check("the score is recomputed over the joined mutants, ignored ones aside", merged["mutationScore"] == 60.0)
    check("the first part's metadata is kept", merged["schemaVersion"] == "1.7" and merged["thresholds"] == {"high": 80, "low": 60})
    check("the parts are not changed", "/r/core/x/C.cpp" not in a["files"])
    for reports, parts, name in (
        ([a], 2, "a missing part is refused"),
        ([a, b, b], 2, "an extra part is refused"),
        ([a, a], 2, "overlapping parts are refused"),
    ):
        try:
            merge(reports, parts)
            check(name, False)
        except BadReports:
            check(name, True)

    with tempfile.TemporaryDirectory() as tmp:
        paths = []
        for i, r in enumerate((a, b)):
            paths.append(os.path.join(tmp, f"part{i}", "lib.json"))
            write(paths[-1], r)
        out = os.path.join(tmp, "merged", "lib.json")
        check("a run over files writes the joined report", main(["--parts", "2", out] + paths) == EXIT_OK and load(out)["mutationScore"] == 60.0)
        check("a missing file fails the run", main(["--parts", "2", out, paths[0], os.path.join(tmp, "none.json")]) == EXIT_ERROR)
        with open(os.path.join(tmp, "bad.json"), "w") as f:
            f.write("{}")
        check("a report without files fails the run", main(["--parts", "2", out, paths[0], os.path.join(tmp, "bad.json")]) == EXIT_ERROR)

    print("self-test " + ("passed" if ok else "FAILED"))
    return EXIT_OK if ok else EXIT_ERROR


def main(argv):
    if argv == ["--self-test"]:
        return self_test()
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--parts", type=int, required=True, help="how many parts the library ran in")
    parser.add_argument("output")
    parser.add_argument("reports", nargs="+")
    args = parser.parse_args(argv)
    try:
        merged = merge([load(p) for p in args.reports], args.parts)
    except BadReports as e:
        print(f"::error::{e}")
        return EXIT_ERROR
    write(args.output, merged)
    total = sum(len(r.get("mutants", [])) for r in merged["files"].values())
    print(f"Joined {len(args.reports)} parts: {len(merged['files'])} files, {total} mutants, into {args.output}")
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
