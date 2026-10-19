"""Require native submodule declarations to have committed Git revisions."""

from __future__ import annotations

import configparser
from pathlib import PurePosixPath
import subprocess
import sys


def git(*args: str) -> bytes:
    return subprocess.check_output(["git", *args])


def main() -> int:
    # Gitlinks may not have a checked-out directory, so pre-commit's normal file
    # filter cannot reliably detect them. Inspect changed index paths directly.
    changed = git("diff", "--cached", "--name-only", "-z").decode().split("\0")
    if "--all" not in sys.argv and not any(
        name == ".gitmodules" or name.startswith("third_party/") for name in changed
    ):
        return 0
    entries = git("ls-files", "--stage", "-z")
    pins = set()
    for entry in entries.split(b"\0"):
        if not entry:
            continue
        metadata, name = entry.split(b"\t", 1)
        mode, _revision, stage = metadata.split()
        if mode == b"160000" and stage == b"0":
            pins.add(name.decode())
    manifest = subprocess.run(
        ["git", "show", ":.gitmodules"], capture_output=True, check=False,
    )
    if manifest.returncode:
        if pins:
            print("Native submodules need a staged .gitmodules file.", file=sys.stderr)
            return 1
        return 0
    config = configparser.ConfigParser(interpolation=None, strict=True)
    try:
        config.read_string(manifest.stdout.decode())
    except configparser.Error as exc:
        print(f".gitmodules: {exc}", file=sys.stderr)
        return 1
    errors = []
    declared = set()
    for section in config.sections():
        path = config.get(section, "path", fallback="").strip().strip('"')
        if not path or PurePosixPath(path).is_absolute() or ".." in PurePosixPath(path).parts:
            errors.append(f".gitmodules: {section}: invalid submodule path")
            continue
        if path in declared:
            errors.append(f".gitmodules: duplicate submodule path {path}")
        declared.add(path)
        if path not in pins:
            errors.append(f"{path}: no staged Git submodule revision (mode 160000)")
        if not config.get(section, "url", fallback="").strip():
            errors.append(f".gitmodules: {section}: missing URL")
    for path in sorted(pins - declared):
        errors.append(f"{path}: missing .gitmodules declaration")
    for error in errors:
        print(error, file=sys.stderr)
    return bool(errors)


if __name__ == "__main__":
    raise SystemExit(main())
