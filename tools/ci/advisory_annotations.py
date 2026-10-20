#!/usr/bin/env python3
"""Turns advisory code-size findings into PR warnings on changed lines (docs/ci.md).

  advisory_annotations.py [--base SHA] [--lizard FILE] [--max N] [SARIF...]

Reads detekt SARIF reports (the `detektAdvisory` task) and lizard's clang-style warnings, keeps
the ones whose code the pull request touched, and prints a `::warning` workflow command for each,
on a changed line. Every finding, touched or not, goes to $GITHUB_STEP_SUMMARY.

Advisory by design: this script always exits 0, whatever it finds and whatever goes wrong.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import traceback
from urllib.parse import unquote, urlparse

# The rules config/detekt/detekt-advisory.yml configures. Anything else the default config
# turns on in that run is dropped: the blocking gate already owns it.
DETEKT_RULES = {
    "LongMethod",
    "CyclomaticComplexMethod",
    "LongParameterList",
    "NestedBlockDepth",
    "LargeClass",
    "TooManyFunctions",
    "ComplexInterface",
    "MagicNumber",
}

LIZARD_LINE = re.compile(r"^(?P<path>.+?):(?P<line>\d+): warning: (?P<msg>.*)$")
LIZARD_LENGTH = re.compile(r"(\d+) length")
HUNK = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")
SUMMARY_ROWS = 400


class Finding:
    def __init__(self, tool, rule, path, start, end, message):
        self.tool = tool
        self.rule = rule
        self.path = path
        self.start = start
        self.end = max(end, start)
        self.message = message


def repo_root():
    try:
        out = subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True, check=True)
        return out.stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return os.getcwd()


def relative(path, root):
    if path.startswith("file:"):
        path = unquote(urlparse(path).path)
    if os.path.isabs(path):
        path = os.path.relpath(path, root)
    path = path.replace(os.sep, "/")
    return path[2:] if path.startswith("./") else path


def changed_lines(base):
    """{path: set(line numbers)} added or modified between the merge base and HEAD."""
    out = subprocess.run(
        ["git", "diff", "--unified=0", "--no-color", "--no-ext-diff", "--diff-filter=AMR", f"{base}...HEAD"],
        capture_output=True,
        text=True,
        check=True,
    ).stdout
    changes, current = {}, None
    for line in out.splitlines():
        if line.startswith("+++ "):
            target = line[4:]
            current = target[2:] if target.startswith("b/") else None
            if current is not None:
                changes.setdefault(current, set())
        elif current is not None and line.startswith("@@"):
            m = HUNK.match(line)
            if m:
                start, count = int(m.group(1)), int(m.group(2) or "1")
                changes[current].update(range(start, start + count))
    return changes


def read_sarif(path, root):
    with open(path, encoding="utf-8") as f:
        doc = json.load(f)
    findings = []
    for run in doc.get("runs", []):
        bases = {k: v.get("uri", "") for k, v in (run.get("originalUriBaseIds") or {}).items()}
        for result in run.get("results", []):
            rule = (result.get("ruleId") or "").split(".")[-1]
            if rule not in DETEKT_RULES:
                continue
            for loc in result.get("locations", [])[:1]:
                phys = loc.get("physicalLocation", {})
                art = phys.get("artifactLocation", {})
                uri = art.get("uri", "")
                base = bases.get(art.get("uriBaseId", ""), "")
                if base and not uri.startswith("file:") and not os.path.isabs(uri):
                    uri = base.rstrip("/") + "/" + uri
                region = phys.get("region", {})
                start = int(region.get("startLine", 1))
                end = int(region.get("endLine", start))
                text = (result.get("message") or {}).get("text", rule)
                findings.append(Finding("detekt", rule, relative(uri, root), start, end, text))
    return findings


def read_lizard(path, root):
    findings = []
    with open(path, encoding="utf-8", errors="replace") as f:
        for raw in f:
            m = LIZARD_LINE.match(raw.strip())
            if not m:
                continue
            start = int(m.group("line"))
            length = LIZARD_LENGTH.search(m.group("msg"))
            end = start + int(length.group(1)) - 1 if length else start
            findings.append(Finding("lizard", "lizard", relative(m.group("path"), root), start, end, m.group("msg")))
    return findings


def escape_data(s):
    return s.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def escape_prop(s):
    return escape_data(s).replace(":", "%3A").replace(",", "%2C")


def anchor(finding, changes):
    """A changed line to annotate the finding on, or None if the PR did not touch its code."""
    lines = changes.get(finding.path)
    if not lines:
        return None
    if finding.start in lines:
        return finding.start
    touched = [n for n in range(finding.start, finding.end + 1) if n in lines]
    return touched[0] if touched else None


def write_summary(findings, annotated, base, limit_hit):
    by_rule = {}
    for f in findings:
        by_rule[f.rule] = by_rule.get(f.rule, 0) + 1
    rows = [
        "### Code-size advisories (never fail the build)",
        "",
        "Thresholds: docs/ci.md. Annotated: findings in code this pull request changed."
        if base
        else "No pull request base: nothing is annotated, this is the full list.",
        "",
        f"**{len(annotated)}** on changed code, **{len(findings)}** in total.",
        "",
        "| Rule | Findings |",
        "|---|---:|",
    ]
    rows += [f"| {rule} | {n} |" for rule, n in sorted(by_rule.items(), key=lambda kv: (-kv[1], kv[0]))]
    if limit_hit:
        rows += ["", f"Only the first {limit_hit} annotations were emitted; the rest are listed below."]
    if annotated:
        rows += ["", "#### On changed code", "", "| Tool | Rule | Location | Finding |", "|---|---|---|---|"]
        for f, line in annotated:
            rows.append(f"| {f.tool} | {f.rule} | `{f.path}:{line}` | {f.message.replace('|', '/')} |")
    rows += ["", "<details><summary>Every finding</summary>", "", "| Tool | Rule | Location | Finding |", "|---|---|---|---|"]
    for f in findings[:SUMMARY_ROWS]:
        rows.append(f"| {f.tool} | {f.rule} | `{f.path}:{f.start}` | {f.message.replace('|', '/')} |")
    if len(findings) > SUMMARY_ROWS:
        rows.append(f"| | | | … and {len(findings) - SUMMARY_ROWS} more |")
    rows += ["", "</details>"]
    text = "\n".join(rows) + "\n"
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as out:
            out.write(text)
    else:
        print(text)


def run(args):
    root = repo_root()
    findings = []
    for path in args.sarif:
        try:
            findings += read_sarif(path, root)
        except (OSError, ValueError) as e:
            print(f"advisory: skipping {path}: {e}")
    if args.lizard:
        try:
            findings += read_lizard(args.lizard, root)
        except OSError as e:
            print(f"advisory: skipping {args.lizard}: {e}")
    findings.sort(key=lambda f: (f.path, f.start, f.rule))

    changes = {}
    if args.base:
        try:
            changes = changed_lines(args.base)
        except (OSError, subprocess.CalledProcessError) as e:
            print(f"advisory: no diff against {args.base}, annotating nothing: {e}")

    annotated = []
    for f in findings:
        line = anchor(f, changes)
        if line is not None:
            annotated.append((f, line))

    emitted = 0
    for f, line in annotated:
        if emitted >= args.max:
            break
        title = f"{f.tool} {f.rule}" if f.tool == "detekt" else "lizard"
        print(f"::warning file={escape_prop(f.path)},line={line},title={escape_prop(title)}::{escape_data(f.message)}")
        emitted += 1
    write_summary(findings, annotated, args.base, args.max if len(annotated) > args.max else 0)
    print(f"advisory: {len(findings)} findings, {len(annotated)} on changed lines, {emitted} annotated")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--base", help="pull request base commit; omit to annotate nothing")
    parser.add_argument("--lizard", help="lizard --warnings_only output")
    # GitHub shows at most 10 warning annotations per step; past that they are dropped silently.
    parser.add_argument("--max", type=int, default=10, help="annotation cap")
    parser.add_argument("sarif", nargs="*", help="detekt SARIF reports")
    try:
        run(parser.parse_args())
    except (Exception, SystemExit):  # noqa: BLE001 - advisory: report, never fail
        traceback.print_exc()
    return 0


if __name__ == "__main__":
    sys.exit(main())
