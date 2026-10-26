#!/usr/bin/env python3
"""Choose the CI jobs a pull request needs from the files it changes (docs/ci.md, "Which checks run").

A job runs when one of the files it reads changed; a module's tests run when the module or anything
it depends on changed. Nothing here is a list of jobs per directory: the Gradle module graph is read
from settings.gradle.kts, each module's build file and the convention plugins in build-logic, the
native graph from LIBRARY_DEPS in check_native_includes.py (the table the include check enforces),
the mutation matrices from the floor files in config/mutation/. What stays in this file is which
loose files each job reads (a tool, a config, an asset root).

    python3 tools/ci/select_jobs.py --changed changed.txt                 # print the selection as JSON
    python3 tools/ci/select_jobs.py --changed changed.txt --explain       # a Markdown table, job by job
    python3 tools/ci/select_jobs.py --all                                 # everything (workflow_dispatch)
    python3 tools/ci/select_jobs.py --changed f --also static-analysis    # one job more ([api-dump])
    python3 tools/ci/select_jobs.py --self-test                           # prove the selection on known cases

`--github-output FILE` appends one `name=value` line per output for a workflow step. A change to the
workflow, the composite action or this script runs everything, as does `--all` and a head commit
whose message contains `[ci-full]` (ci.yml).
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_native_includes import LIBRARY_DEPS  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]

# ---- what each job reads, beyond the module graphs ------------------------------------------------

# A change here changes how everything is run: run everything.
HARNESS = [".github/workflows/ci.yml", ".github/actions/**", "tools/ci/select_jobs.py"]
# The build itself: every Gradle job, every module.
GRADLE = [
    "build-logic/**", "gradle/**", "gradlew", "gradlew.bat", "build.gradle.kts", "settings.gradle.kts",
    "gradle.properties",
]
# What the device build compiles into libgeode.so (the root CMakeLists.txt): the APK jobs.
NATIVE_DEVICE = [
    "core/**", "app/src/main/cpp/**", "CMakeLists.txt", "third_party/**", "tools/projectm-patches.cmake",
    "tools/*.patch",
]
# What the host test suite reads besides the libraries: a change here can change any library's score.
NATIVE_TEST_INPUTS = [
    "core/tests/**", "core/host/**", "core/pure_sources.cmake", "core/native_libraries.cmake",
    "third_party/kissfft/**", "app/src/main/assets/shaders/**", "engine/scenes/src/main/stylepacks/**",
]
# Third-party code a native library links beyond LIBRARY_DEPS.
NATIVE_EXTERNAL = {"analysis": ["third_party/kissfft/**"]}
# The module directory that is native, not Kotlin: a change there does not touch the module's Kotlin.
NOT_KOTLIN = ["app/src/main/cpp/**"]

JOB_INPUTS: dict[str, list[str]] = {
    "build": [*GRADLE, *NATIVE_DEVICE],
    "static-analysis": [
        *GRADLE, "config/detekt/**", ".editorconfig", "docs/visualizer-v2/provenance.json", "tools/ci/commit_goldens.sh",
    ],
    "unit-tests": [*GRADLE, "tools/ci/coverage_summary.py"],
    "mutation": [*GRADLE, "config/mutation/thresholds.properties", "tools/ci/mutation_gate.py"],
    "native-mutation": [
        *NATIVE_TEST_INPUTS, "config/mutation/mull.yml", "config/mutation/native-thresholds.properties",
        "tools/ci/mull_part.py", "tools/ci/native_mutation_gate.py", "tools/ci/merge_mutation_reports.py",
        "tools/ci/check_native_includes.py",  # LIBRARY_DEPS: which libraries a change reaches
    ],
    "native-tests": [
        "core/**", "app/src/main/cpp/**", "third_party/**", "tools/hostlink/**", "tools/bridge_schema/**",
        "tools/ci/check_native_includes.py", "tools/ci/native_graph_doc.py", "docs/native-libraries.md",
        "app/src/main/assets/shaders/**", "engine/scenes/src/main/stylepacks/**",
    ],
    "native-concurrency": ["core/**", "third_party/kissfft/**"],
    "milkdrop-frame-exact": [
        "third_party/projectm/**", "tools/milkdrop-determinism/**", "tools/projectm-patches.cmake",
        "tools/*.patch", "app/src/main/assets/milk/**",
    ],
    "visual-frames": [
        "app/src/main/assets/shaders/**", "app/src/main/assets/gl_transitions.json",
        "engine/scenes/src/main/stylepacks/**", "tools/glslcheck/**", "tools/ci/check_style_packs.py",
        "core/render/ShaderSource.cpp", "core/viz/**",
        "feature/customization/core/src/main/kotlin/dev/geode/feature/customization/core/PackExtras.kt",
    ],
    "play-gates": [*GRADLE, *NATIVE_DEVICE, "tools/ci/play_gates.sh", "config/play/**"],
    "secrets": [],  # every pull request: any file can leak a secret
    "notices": [
        "THIRD_PARTY_NOTICES", "app/src/main/assets/third_party_notices.txt", "app/src/main/assets/third_party/**",
        "tools/*.patch", "tools/ci/check_notices.py", "gradle/libs.versions.toml", ".gitmodules",
        "**/build.gradle.kts", "build-logic/**",
    ],
    "dependency-review": [
        "gradle/libs.versions.toml", "**/*.gradle.kts", "gradle/**", "build-logic/**", ".github/**",
        "**/package.json", "**/package-lock.json", ".gitmodules",
    ],
    "instrumented-tests": [*GRADLE, *NATIVE_DEVICE],
}
# The jobs that also run for a Kotlin module change: all modules, or the touched modules and their dependents.
MODULE_JOBS = {"build", "static-analysis", "unit-tests", "mutation", "play-gates", "instrumented-tests"}
JOBS = list(JOB_INPUTS)

# Mull runs a library whole, or in parts when its mutants outgrow one job (docs/ci.md, "Parts").
# geode_analysis has about 1 800 mutants, four times geode_util's, and one run of them all outgrew the
# time limit on every pull request (2026-10-26). geode_viz is paused (owner, 2026-10-23): its run
# outgrows the job's time limit on most pull requests; its floor stays for when it returns.
NATIVE_MUTATION_PARTS = {"geode_util": 1, "geode_audio": 1, "geode_analysis": 4}


# ---- matching ---------------------------------------------------------------------------------------

def compile_pattern(pattern: str) -> re.Pattern[str]:
    """A path glob: `*` within one segment, `**` across segments, `dir/**` also the directory itself
    (a submodule bump changes the path `third_party/projectm`)."""
    out = ""
    i = 0
    while i < len(pattern):
        if pattern.startswith("/**", i) and i + 3 == len(pattern):
            out += "(/.*)?"
            i += 3
        elif pattern.startswith("**/", i):
            out += "(.*/)?"
            i += 3
        elif pattern.startswith("**", i):
            out += ".*"
            i += 2
        elif pattern[i] == "*":
            out += "[^/]*"
            i += 1
        else:
            out += re.escape(pattern[i])
            i += 1
    return re.compile("^" + out + "$")


def first_match(paths: list[str], patterns: list[str]) -> str | None:
    compiled = [compile_pattern(p) for p in patterns]
    for path in paths:
        if any(c.match(path) for c in compiled):
            return path
    return None


# ---- the Gradle module graph, from the build files -------------------------------------------------

MODULE_RE = re.compile(r'"(:[a-z0-9-]+(?::[a-z0-9-]+)*)"')
PLUGIN_RE = re.compile(r'id\("(geode\.[a-z-]+)"\)')
PROJECT_RE = re.compile(r'project\("([^"]+)"\)')


def read_modules(root: Path = ROOT) -> list[str]:
    text = (root / "settings.gradle.kts").read_text(encoding="utf-8")
    return [m for m in MODULE_RE.findall(text) if (root / module_dir(m) / "build.gradle.kts").is_file()]


def module_dir(module: str) -> str:
    return module.strip(":").replace(":", "/")


def convention_deps(plugin: str, module: str, root: Path, seen: set[str]) -> tuple[set[str], set[str]]:
    """The project dependencies and the (transitive) convention plugins one geode.* plugin brings."""
    if plugin in seen:
        return set(), set()
    seen.add(plugin)
    text = (root / "build-logic" / "src" / "main" / "kotlin" / f"{plugin}.gradle.kts").read_text(encoding="utf-8")
    deps, plugins = set(), {plugin}
    feature = module.rsplit(":", 1)[0]
    for ref in PROJECT_RE.findall(text):
        deps.add(ref.replace("$feature", feature))
    for inner in PLUGIN_RE.findall(text):
        d, p = convention_deps(inner, module, root, seen)
        deps |= d
        plugins |= p
    return deps, plugins


def module_graph(root: Path = ROOT) -> tuple[dict[str, set[str]], dict[str, set[str]]]:
    """Each module's direct project dependencies (test fixtures included) and its convention plugins."""
    deps: dict[str, set[str]] = {}
    plugins: dict[str, set[str]] = {}
    for module in read_modules(root):
        text = (root / module_dir(module) / "build.gradle.kts").read_text(encoding="utf-8")
        deps[module] = set(PROJECT_RE.findall(text))
        plugins[module] = set()
        for plugin in PLUGIN_RE.findall(text):
            d, p = convention_deps(plugin, module, root, set())
            deps[module] |= d
            plugins[module] |= p
    unknown = {d for ds in deps.values() for d in ds} - set(deps)
    if unknown:
        raise ValueError(f"build files depend on modules settings.gradle.kts does not include: {sorted(unknown)}")
    return deps, plugins


def dependents_closure(deps: dict[str, set[str]], touched: set[str]) -> set[str]:
    """The touched modules and everything that depends on one of them, transitively."""
    affected = set(touched)
    changed = True
    while changed:
        changed = False
        for module, ds in deps.items():
            if module not in affected and ds & affected:
                affected.add(module)
                changed = True
    return affected


def touched_modules(paths: list[str], modules: list[str]) -> dict[str, str]:
    """Module -> the first changed path under its directory (native directories excepted)."""
    not_kotlin = [compile_pattern(p) for p in NOT_KOTLIN]
    out: dict[str, str] = {}
    for module in modules:
        prefix = module_dir(module) + "/"
        for path in paths:
            if path.startswith(prefix) and not any(c.match(path) for c in not_kotlin):
                out.setdefault(module, path)
                break
    return out


# ---- the mutation matrices, from the floor files ---------------------------------------------------

def floor_keys(file: Path) -> list[str]:
    return [line.split("=", 1)[0].strip() for line in file.read_text(encoding="utf-8").splitlines()
            if "=" in line and not line.lstrip().startswith("#")]


def mutation_entry(module: str, plugins: set[str]) -> dict[str, str]:
    if "geode.mutation-android" in plugins:
        task = f"{module}:pitestDebug"
    elif "geode.mutation-jvm" in plugins:
        task = f"{module}:pitest"
    else:
        raise ValueError(f"{module} has a floor in thresholds.properties but applies no geode.mutation-* plugin")
    return {"module": module, "task": task, "dir": module_dir(module), "name": module_dir(module).replace("/", "-")}


def native_mutation_entries(libraries: list[str]) -> list[dict[str, str]]:
    out = []
    for library in libraries:
        parts = NATIVE_MUTATION_PARTS[library]
        short = library.removeprefix("geode_")
        for part in range(1, parts + 1):
            entry = {"library": library, "dir": f"core/{short}", "name": library}
            if parts > 1:
                entry["name"] = f"{library}-{part}"
                entry["part"] = f"{part}/{parts}"
            out.append(entry)
    return out


def native_library_touched(paths: list[str], library: str) -> str | None:
    """The first changed path under the library or one of its (transitive) dependencies."""
    short = library.removeprefix("geode_")
    wanted, queue = set(), [short]
    while queue:
        lib = queue.pop()
        if lib not in wanted:
            wanted.add(lib)
            queue.extend(LIBRARY_DEPS[lib])
    patterns = [f"core/{lib}/**" for lib in sorted(wanted)]
    for lib in sorted(wanted):
        patterns += NATIVE_EXTERNAL.get(lib, [])
    return first_match(paths, patterns)


# ---- the selection ----------------------------------------------------------------------------------

def select(paths: list[str], everything: bool = False, also: set[str] | None = None, root: Path = ROOT) -> dict:
    """Which jobs run and why: {job: reason or None}, plus the matrices and the unit-test tasks."""
    paths = sorted(set(paths))
    deps, plugins = module_graph(root)
    modules = sorted(deps)
    reasons: dict[str, str | None] = {}
    harness = first_match(paths, HARNESS)
    if everything:
        reasons = {job: "everything was asked for" for job in JOBS}
    elif harness:
        reasons = {job: f"everything: {harness} changed" for job in JOBS}
    else:
        for job, inputs in JOB_INPUTS.items():
            reasons[job] = first_match(paths, inputs)
    reasons["secrets"] = reasons["secrets"] or "every pull request"
    for job in also or set():
        if job not in JOBS:
            raise ValueError(f"--also {job}: not a job ({', '.join(JOBS)})")
        reasons[job] = reasons[job] or "asked for by the commit message"

    # Kotlin modules: all of them when the build changed, else the touched ones and their dependents.
    touched = touched_modules(paths, modules)
    if everything or harness or first_match(paths, GRADLE):
        affected = set(modules)
        affected_reason = reasons["build"]
    else:
        affected = dependents_closure(deps, set(touched))
        affected_reason = ", ".join(f"{m} ({p})" for m, p in sorted(touched.items())) or None
        if affected_reason and len(touched) < len(affected):
            affected_reason += f" and {len(affected) - len(touched)} dependent module(s)"
    if affected_reason:
        for job in MODULE_JOBS:
            reasons[job] = reasons[job] or affected_reason
    # Unit tests: every module when the job's own inputs changed, else the affected ones.
    tested = modules if (everything or harness or first_match(paths, JOB_INPUTS["unit-tests"])) else sorted(affected)
    unit_test_tasks = [f"{m}:testDebugUnitTest" for m in tested] if reasons["unit-tests"] else []

    # Mutation: every module with a floor when the job's own inputs changed, else the affected ones.
    floors = [k for k in floor_keys(root / "config" / "mutation" / "thresholds.properties") if k.startswith(":")]
    missing = set(floors) - set(modules)
    if missing:
        raise ValueError(f"thresholds.properties names modules settings.gradle.kts does not include: {sorted(missing)}")
    mutated = floors if (everything or harness or first_match(paths, JOB_INPUTS["mutation"])) else [m for m in floors if m in affected]
    mutation = [mutation_entry(m, plugins[m]) for m in mutated]
    reasons["mutation"] = reasons["mutation"] if mutation else None

    # Native mutation: every library when the suite's inputs changed, else the touched ones and their dependents.
    native_floors = floor_keys(root / "config" / "mutation" / "native-thresholds.properties")
    libraries = [lib for lib in native_floors if lib in NATIVE_MUTATION_PARTS]
    if everything or harness or first_match(paths, JOB_INPUTS["native-mutation"]):
        native_libraries = libraries
    else:
        native_libraries = [lib for lib in libraries if native_library_touched(paths, lib)]
        if native_libraries:
            reasons["native-mutation"] = ", ".join(f"{lib} ({native_library_touched(paths, lib)})" for lib in native_libraries)
    native_mutation = native_mutation_entries(native_libraries)
    reasons["native-mutation"] = reasons["native-mutation"] if native_mutation else None

    return {
        "jobs": reasons,
        "unit_test_tasks": unit_test_tasks,
        "mutation": mutation,
        "native_mutation": native_mutation,
        "analysis_parts": NATIVE_MUTATION_PARTS["geode_analysis"] if "geode_analysis" in native_libraries else 0,
    }


def outputs(selection: dict) -> dict[str, str]:
    """The workflow outputs: `<job>` as true/false (dashes as underscores), `<job>_matrix` as JSON."""
    out = {job.replace("-", "_"): ("true" if reason else "false") for job, reason in selection["jobs"].items()}
    out["unit_test_tasks"] = " ".join(selection["unit_test_tasks"])
    out["mutation_matrix"] = json.dumps(selection["mutation"], separators=(",", ":"))
    out["native_mutation_matrix"] = json.dumps(selection["native_mutation"], separators=(",", ":"))
    out["analysis_parts"] = str(selection["analysis_parts"])
    return out


def explain(selection: dict) -> str:
    lines = ["| Job | Runs | Because |", "|---|---|---|"]
    for job, reason in selection["jobs"].items():
        detail = reason or "nothing it reads changed"
        if job == "mutation" and reason:
            detail += ": " + ", ".join(e["module"] for e in selection["mutation"])
        if job == "native-mutation" and reason:
            detail += ": " + ", ".join(e["name"] for e in selection["native_mutation"])
        if job == "unit-tests" and reason:
            detail += f": {len(selection['unit_test_tasks'])} module(s)"
        lines.append(f"| {job} | {'yes' if reason else 'no'} | {detail} |")
    return "\n".join(lines)


# ---- self-test --------------------------------------------------------------------------------------

def self_test() -> bool:
    deps, plugins = module_graph()
    floors = [k for k in floor_keys(ROOT / "config" / "mutation" / "thresholds.properties") if k.startswith(":")]
    failures: list[str] = []

    def expect(name: str, condition: bool) -> None:
        print(f"  {'ok' if condition else 'FAILED'}: {name}")
        if not condition:
            failures.append(name)

    def runs(selection: dict) -> set[str]:
        return {job for job, reason in selection["jobs"].items() if reason}

    def mutated(selection: dict) -> set[str]:
        return {e["module"] for e in selection["mutation"]}

    def native(selection: dict) -> set[str]:
        return {e["library"] for e in selection["native_mutation"]}

    # The module graph read from the build files.
    expect("app depends on every other module, directly or through another",
           all(":app" in dependents_closure(deps, {m}) for m in deps))
    expect("a feature core gets its :api and :arch:kernel from the convention plugin",
           {":feature:playback:api", ":arch:kernel"} <= deps[":feature:playback:core"])
    expect("a feature's handlers depend on its own core", ":feature:playback:core" in deps[":feature:playback:handlers"])
    expect("a feature's :ui depends on :design", ":design" in deps[":feature:playback:ui"])
    expect("the JVM and Android mutation tasks are told apart",
           mutation_entry(":arch:kernel", plugins[":arch:kernel"])["task"] == ":arch:kernel:pitest"
           and mutation_entry(":engine:live", plugins[":engine:live"])["task"] == ":engine:live:pitestDebug")

    nothing = select([])
    expect("no change: only the secret scan", runs(nothing) == {"secrets"})
    docs = select(["docs/style-packs.md", "README.md"])
    expect("docs only: only the secret scan", runs(docs) == {"secrets"})
    diagram = select(["docs/native-libraries.md"])
    expect("the generated diagram: the C++ job that checks it", runs(diagram) == {"secrets", "native-tests"})

    core = select(["feature/playback/core/src/main/kotlin/dev/geode/feature/playback/core/Reducer.kt"])
    expect("a feature core: the Gradle jobs and the APK jobs, nothing native",
           runs(core) == {"secrets", "build", "static-analysis", "unit-tests", "mutation", "play-gates", "instrumented-tests"})
    expect("a feature core: its own mutants, its dependents' and :app's, not another feature's",
           {":feature:playback:core", ":app"} <= mutated(core) and ":feature:settings:core" not in mutated(core)
           and ":arch:kernel" not in mutated(core))
    expect("a feature core: unit tests for it, its handlers and :app, not for its :api",
           {":feature:playback:core:testDebugUnitTest", ":feature:playback:handlers:testDebugUnitTest", ":app:testDebugUnitTest"}
           <= set(core["unit_test_tasks"]) and ":feature:playback:api:testDebugUnitTest" not in core["unit_test_tasks"])
    kernel = select(["arch/kernel/src/main/kotlin/dev/geode/arch/kernel/Store.kt"])
    expect("the kernel: every feature core's mutants, not the engine's",
           {":feature:playback:core", ":feature:studio:core", ":arch:kernel", ":app"} <= mutated(kernel)
           and ":engine:audio-core" not in mutated(kernel))

    analysis = select(["core/analysis/Spectrum.cpp"])
    expect("a native library: the host jobs and the APK jobs, no Kotlin tests",
           runs(analysis) == {"secrets", "native-tests", "native-concurrency", "native-mutation", "build", "play-gates",
                              "instrumented-tests"})
    expect("geode_analysis: its four parts and the gate", native(analysis) == {"geode_analysis"}
           and len(analysis["native_mutation"]) == 4 and analysis["analysis_parts"] == 4)
    util = select(["core/util/Json.cpp"])
    expect("geode_util: its mutants and geode_audio's (which links it), not geode_analysis's",
           native(util) == {"geode_util", "geode_audio"} and util["analysis_parts"] == 0)
    tests = select(["core/tests/json_test.cpp"])
    expect("a host test: every library's mutants", native(tests) == set(NATIVE_MUTATION_PARTS))
    jni = select(["app/src/main/cpp/geode_jni.cpp"])
    expect("a JNI shim: the include check and the APK, no Kotlin tests and no mutants",
           runs(jni) == {"secrets", "native-tests", "build", "play-gates", "instrumented-tests"} and not jni["mutation"])

    shader = select(["app/src/main/assets/shaders/composite_frag.glsl"])
    expect("a shader: the render check and the host tests that read shaders",
           {"visual-frames", "native-tests", "native-mutation"} <= runs(shader) and "milkdrop-frame-exact" not in runs(shader))
    preset = select(["app/src/main/assets/milk/x.milk"])
    expect("a MilkDrop preset: the frame-exact check", "milkdrop-frame-exact" in runs(preset))
    bump = select(["third_party/projectm"])
    expect("a submodule bump: the frame-exact check, the host link check and the APK",
           {"milkdrop-frame-exact", "native-tests", "build"} <= runs(bump))

    gradle = select(["gradle/libs.versions.toml"])
    expect("the version catalog: every Gradle job on every module, the notices and the dependency review",
           {"build", "static-analysis", "unit-tests", "mutation", "notices", "dependency-review"} <= runs(gradle)
           and len(gradle["unit_test_tasks"]) == len(deps) and mutated(gradle) == set(floors))
    gate = select(["tools/ci/mutation_gate.py"])
    expect("the mutation gate: every module's mutants, nothing else", runs(gate) == {"secrets", "mutation"}
           and mutated(gate) == set(floors))
    summary = select(["tools/ci/coverage_summary.py"])
    expect("the coverage summary: every module's unit tests, no mutants",
           runs(summary) == {"secrets", "unit-tests"} and len(summary["unit_test_tasks"]) == len(deps))
    harness = select([".github/workflows/ci.yml"])
    expect("the workflow: everything",
           runs(harness) == set(JOBS) and len(harness["native_mutation"]) == sum(NATIVE_MUTATION_PARTS.values()))
    everything = select([], everything=True)
    expect("--all: everything", runs(everything) == set(JOBS) and mutated(everything) == set(floors))
    dump = select(["docs/x.md"], also={"static-analysis"})
    expect("[api-dump]: the lint job as well", runs(dump) == {"secrets", "static-analysis"})

    out = outputs(core)
    expect("outputs are true/false and one-line JSON", out["build"] == "true" and out["native_tests"] == "false"
           and out["mutation"] == "true" and "\n" not in out["mutation_matrix"]
           and out["native_mutation"] == "false" and json.loads(out["native_mutation_matrix"]) == [])
    print("self-test " + ("passed" if not failures else "FAILED"))
    return not failures


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--changed", type=Path, help="file with one changed path per line")
    parser.add_argument("--all", action="store_true", help="run everything")
    parser.add_argument("--also", action="append", default=[], help="a job to run whatever changed")
    parser.add_argument("--explain", action="store_true", help="print a Markdown table instead of JSON")
    parser.add_argument("--github-output", type=Path, help="append the outputs to this file ($GITHUB_OUTPUT)")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    if args.self_test:
        return 0 if self_test() else 1
    if not args.all and args.changed is None:
        parser.error("--changed FILE or --all")
    paths = [p.strip() for p in args.changed.read_text(encoding="utf-8").splitlines() if p.strip()] if args.changed else []
    selection = select(paths, everything=args.all, also=set(args.also))
    if args.github_output:
        with args.github_output.open("a", encoding="utf-8") as f:
            for name, value in outputs(selection).items():
                f.write(f"{name}={value}\n")
    print(explain(selection) if args.explain else json.dumps({**selection, "outputs": outputs(selection)}, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
