#!/usr/bin/env python3
"""Mutation-score gate for one Gradle module (docs/ci.md).

Reads the PIT mutations.xml the module's pitest task wrote, prints the score and the surviving
mutants, and fails when:
  - the module's score is below its floor in config/mutation/thresholds.properties, or
  - any feature core package (dev.geode.feature.<name>.core) scores below the `feature-core`
    floor (ADR-0003).

The score is detected / all mutants, where NO_COVERAGE counts as not detected: untested code
cannot score well by being skipped.

  mutation_gate.py <module path, e.g. :app> <mutations.xml> <thresholds.properties>
"""
import collections
import os
import re
import sys
import xml.etree.ElementTree as ET

DETECTED = {"KILLED", "TIMED_OUT", "MEMORY_ERROR", "RUN_ERROR"}
FEATURE_CORE = re.compile(r"^dev\.geode\.feature\.([a-z0-9_]+)\.core\.")


def load_thresholds(path):
    out = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            key, _, value = line.partition("=")
            out[key.strip()] = float(value)
    return out


def score(detected, total):
    return 100.0 * detected / total if total else 100.0


def summary(text):
    print(text)
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8") as f:
            f.write(text + "\n")


def main(module, report, thresholds_path):
    thresholds = load_thresholds(thresholds_path)
    if module not in thresholds:
        sys.exit(f"::error::{module} has no floor in {thresholds_path}")
    floor = thresholds[module]
    feature_floor = thresholds.get("feature-core", 80.0)

    mutations = ET.parse(report).getroot().findall("mutation")
    total = len(mutations)
    detected = sum(1 for m in mutations if m.get("status") in DETECTED)
    features = collections.defaultdict(lambda: [0, 0])
    survivors = []
    for m in mutations:
        cls = m.findtext("mutatedClass", "")
        hit = m.get("status") in DETECTED
        match = FEATURE_CORE.match(cls)
        if match:
            features[match.group(1)][0] += hit
            features[match.group(1)][1] += 1
        if not hit:
            survivors.append(
                f"{m.get('status')}: {m.findtext('sourceFile')}:{m.findtext('lineNumber')} "
                f"{m.findtext('mutator', '').rsplit('.', 1)[-1]} in {cls}.{m.findtext('mutatedMethod')}"
            )

    module_score = score(detected, total)
    summary(f"### Mutation score {module}: {module_score:.1f}% ({detected}/{total}), floor {floor:g}%")
    failures = []
    if module_score < floor:
        failures.append(f"{module} scored {module_score:.1f}%, below its floor of {floor:g}%")
    for name, (hit, count) in sorted(features.items()):
        s = score(hit, count)
        summary(f"- feature `{name}` core: {s:.1f}% ({hit}/{count}), floor {feature_floor:g}%")
        if s < feature_floor:
            failures.append(f"feature {name} core scored {s:.1f}%, below {feature_floor:g}% (ADR-0003)")

    if survivors:
        print(f"::group::{len(survivors)} surviving or uncovered mutants")
        for line in survivors:
            print(line)
        print("::endgroup::")
    for failure in failures:
        print(f"::error::{failure}")
    return 1 if failures else 0


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    sys.exit(main(*sys.argv[1:]))
