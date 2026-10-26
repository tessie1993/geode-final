#!/usr/bin/env python3
"""Mull's configuration for one part of a native library (docs/ci.md, "C++ mutation tests").

A library with more mutants than one job can run within its time limit runs in parts: part k of n
mutates its share of the library's files, and the parts' reports are scored together as one
library (tools/ci/merge_mutation_reports.py). This writes config/mutation/mull.yml with its
includePaths replaced by one pattern per file of the part, so Mull's compiler plugin builds, and
mull-runner runs, only that part's mutants.

The files are every file under the library's directory, shared out by line count so the parts take
about as long as each other. The split depends only on the files and their line counts, so every
part of one run sees the same split, and the parts together cover every file exactly once.

  mull_part.py <mull.yml> <library dir> <k>/<n> <output>
  mull_part.py --self-test
"""
import os
import re
import sys
import tempfile

PLAIN_PATH = re.compile(r"^[A-Za-z0-9_./-]+$")
EXIT_OK = 0
EXIT_ERROR = 2


def library_files(library_dir):
    """Every file under the library directory, as (path relative to it, line count), sorted by path."""
    out = []
    for root, dirs, names in os.walk(library_dir):
        dirs.sort()
        for name in sorted(names):
            path = os.path.join(root, name)
            with open(path, "rb") as f:
                lines = sum(1 for _ in f)
            out.append((os.path.relpath(path, library_dir).replace(os.sep, "/"), lines))
    return sorted(out)


def split(files, parts):
    """files shared into `parts` lists by line count: largest first, each to the lightest part."""
    bins = [[] for _ in range(parts)]
    loads = [0] * parts
    for path, lines in sorted(files, key=lambda f: (-f[1], f[0])):
        lightest = min(range(parts), key=lambda i: (loads[i], i))
        bins[lightest].append(path)
        loads[lightest] += lines
    return [sorted(b) for b in bins]


def pattern(library_dir, path):
    """A Mull includePaths pattern (a regular expression) for one file, with the repo above it free."""
    full = f"{library_dir.strip('/')}/{path}"
    if not PLAIN_PATH.match(full):
        raise ValueError(f"cannot write a pattern for {full!r}: unexpected characters")
    return ".*/" + full.replace(".", r"\.")


def narrowed(config_text, patterns, title):
    """config_text with its includePaths list replaced by `patterns`, everything else unchanged."""
    lines = config_text.split("\n")
    try:
        start = next(i for i, line in enumerate(lines) if line.rstrip() == "includePaths:")
    except StopIteration:
        raise ValueError("no includePaths: list in the Mull config") from None
    end = start + 1
    while end < len(lines) and lines[end].startswith("  -"):
        end += 1
    if end == start + 1:
        raise ValueError("the Mull config's includePaths list is empty")
    # Single-quoted: a backslash (the escaped dot) is literal there, and no escape in a double-quoted
    # scalar.
    replacement = [f"# {title}", "includePaths:"] + [f"  - '{p}'" for p in patterns]
    return "\n".join(lines[:start] + replacement + lines[end:])


def parse_part(text):
    m = re.fullmatch(r"([1-9][0-9]*)/([1-9][0-9]*)", text)
    if not m or int(m.group(1)) > int(m.group(2)):
        raise ValueError(f"part must be k/n with 1 <= k <= n, not {text!r}")
    return int(m.group(1)), int(m.group(2))


def write_part(config_path, library_dir, part_text, output_path):
    k, n = parse_part(part_text)
    files = library_files(library_dir)
    if len(files) < n:
        raise ValueError(f"{library_dir} has {len(files)} files, fewer than {n} parts")
    mine = split(files, n)[k - 1]
    with open(config_path, encoding="utf-8") as f:
        text = f.read()
    title = f"Part {k}/{n} of {library_dir}: {len(mine)} of {len(files)} files (tools/ci/mull_part.py)"
    out = narrowed(text, [pattern(library_dir, p) for p in mine], title)
    os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as f:
        f.write(out)
    print(title)
    for p in mine:
        print(f"  {p}")


SAMPLE_CONFIG = """mutators:
  - cxx_all
# Only the library's own code.
includePaths:
  - ".*/core/(util|audio|analysis|viz)/.*"
excludePaths:
  - ".*/core/tests/.*"
timeout: 60000
"""


def self_test():
    ok = True

    def check(name, condition):
        nonlocal ok
        print(f"  {'ok ' if condition else 'FAIL'} {name}")
        ok = ok and condition

    with tempfile.TemporaryDirectory() as tmp:
        lib = os.path.join(tmp, "core", "part")
        sizes = {"Big.cpp": 100, "Mid.cpp": 60, "Small.cpp": 30, "include/geode/part/Big.hpp": 10, "Tiny.hpp": 5}
        for name, lines in sizes.items():
            os.makedirs(os.path.dirname(os.path.join(lib, name)), exist_ok=True)
            with open(os.path.join(lib, name), "w") as f:
                f.write("x\n" * lines)
        files = library_files(lib)
        check("every file is listed with its line count", dict(files) == sizes)
        parts = split(files, 3)
        flat = sorted(p for part in parts for p in part)
        check("the parts cover every file exactly once", flat == sorted(sizes))
        check("the parts are balanced by line count", parts == [["Big.cpp"], ["Mid.cpp"], ["Small.cpp", "Tiny.hpp", "include/geode/part/Big.hpp"]])
        check("the split is the same every time", split(list(reversed(files)), 3) == parts)
        check("a part of one is the whole library", split(files, 1) == [sorted(sizes)])

        pat = pattern("core/part", "include/geode/part/Big.hpp")
        check("a pattern matches the file's absolute path", re.fullmatch(pat, "/home/runner/work/x/x/core/part/include/geode/part/Big.hpp") is not None)
        check("a pattern does not match another file", re.fullmatch(pat, "/home/runner/work/x/x/core/part/include/geode/part/Bigxhpp") is None)
        try:
            pattern("core/part", "odd name.cpp")
            check("an unexpected file name is refused", False)
        except ValueError:
            check("an unexpected file name is refused", True)

        out = narrowed(SAMPLE_CONFIG, [pat], "Part 1/3")
        check("includePaths is replaced", '  - ".*/core/(util|audio|analysis|viz)/.*"' not in out and f"  - '{pat}'" in out)
        check("the rest of the config is kept", out.startswith("mutators:\n  - cxx_all\n# Only") and out.endswith('excludePaths:\n  - ".*/core/tests/.*"\ntimeout: 60000\n'))
        check("the part is named in the config", "# Part 1/3\nincludePaths:\n" in out)
        for bad in ("mutators:\n  - cxx_all\n", "includePaths:\nexcludePaths:\n"):
            try:
                narrowed(bad, [pat], "t")
                check("a config without an includePaths list is refused", False)
            except ValueError:
                check("a config without an includePaths list is refused", True)

        for bad in ("0/3", "4/3", "1/0", "a/b", "1", "1/3/5"):
            try:
                parse_part(bad)
                check(f"{bad!r} is refused as a part", False)
            except ValueError:
                check(f"{bad!r} is refused as a part", True)
        check("k/n is parsed", parse_part("2/3") == (2, 3))

        config = os.path.join(tmp, "mull.yml")
        with open(config, "w") as f:
            f.write(SAMPLE_CONFIG)
        here = os.getcwd()
        os.chdir(tmp)
        try:
            written = []
            for k in (1, 2, 3):
                outp = os.path.join(tmp, f"part{k}.yml")
                write_part(config, "core/part", f"{k}/3", outp)
                with open(outp) as f:
                    written.append(f.read())
            check("each written part lists its own files only", "'.*/core/part/Big\\.cpp'" in written[0] and "Big\\.cpp" not in written[1])
            try:
                write_part(config, "core/part", "1/9", os.path.join(tmp, "x.yml"))
                check("more parts than files is refused", False)
            except ValueError:
                check("more parts than files is refused", True)
        finally:
            os.chdir(here)

    print("self-test " + ("passed" if ok else "FAILED"))
    return EXIT_OK if ok else EXIT_ERROR


def main(argv):
    if argv == ["--self-test"]:
        return self_test()
    if len(argv) != 4:
        print(__doc__, file=sys.stderr)
        return EXIT_ERROR
    try:
        write_part(*argv)
    except (OSError, ValueError) as e:
        print(f"::error::{e}")
        return EXIT_ERROR
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
