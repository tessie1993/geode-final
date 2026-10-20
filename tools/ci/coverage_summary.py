#!/usr/bin/env python3
"""Writes per-module line coverage from Kover XML reports to the job summary (docs/ci.md).

  coverage_summary.py <report.xml>...
"""
import os
import sys
import xml.etree.ElementTree as ET


def line_counter(path):
    root = ET.parse(path).getroot()
    for counter in root.findall("counter"):
        if counter.get("type") == "LINE":
            return int(counter.get("covered")), int(counter.get("missed"))
    return 0, 0


def main(paths):
    rows = ["### Line coverage", "", "| Module | Covered | Lines | % |", "|---|---:|---:|---:|"]
    for path in sorted(paths):
        module = path.split("/build/")[0] or "."
        covered, missed = line_counter(path)
        total = covered + missed
        pct = 100.0 * covered / total if total else 0.0
        rows.append(f"| {module} | {covered} | {total} | {pct:.1f} |")
    text = "\n".join(rows)
    print(text)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write(text + "\n")


if __name__ == "__main__":
    main(sys.argv[1:])
