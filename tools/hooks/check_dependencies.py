"""Offline checks for Gradle catalog references and literal dependency versions.

This is declaration validation, not dependency resolution or a vulnerability scan.
pre-commit supplies the staged snapshot; this script never edits files.
"""

from __future__ import annotations

from pathlib import Path
import re
import sys

try:
    import tomllib
except ModuleNotFoundError:
    import tomli as tomllib


def check_version(value: object, label: str, errors: list[str]) -> None:
    if not isinstance(value, str) or not value.strip():
        errors.append(f"{label}: expected a nonempty, fixed version")
    elif re.search(r"[+*\[\](),]|(?i:latest\.|snapshot)", value):
        errors.append(f"{label}: pin an exact release instead of {value!r}")


def check_catalog(path: Path) -> list[str]:
    errors: list[str] = []
    try:
        data = tomllib.loads(path.read_text(encoding="utf-8"))
    except (ValueError, OSError) as exc:
        return [f"{path}: {exc}"]
    versions = data.get("versions", {})

    def version(value: object, label: str) -> None:
        if isinstance(value, dict):
            if "ref" in value:
                if value["ref"] not in versions:
                    errors.append(f"{label}: unknown version.ref {value['ref']!r}")
            elif any(key in value for key in ("strictly", "require", "prefer")):
                for key in ("strictly", "require", "prefer"):
                    if key in value:
                        check_version(value[key], f"{label}.{key}", errors)
            else:
                errors.append(f"{label}: expected a fixed version or version.ref")
        else:
            check_version(value, label, errors)

    for name, value in versions.items():
        version(value, f"{path}: versions.{name}")
    for section in ("libraries", "plugins"):
        for name, declaration in data.get(section, {}).items():
            label = f"{path}: {section}.{name}"
            if isinstance(declaration, str):
                parts = declaration.split(":")
                if len(parts) != (3 if section == "libraries" else 2):
                    errors.append(f"{label}: invalid dependency notation")
                else:
                    version(parts[-1], label)
            elif isinstance(declaration, dict):
                # Versionless libraries can inherit their version from a BOM.
                if "version" in declaration:
                    version(declaration["version"], label)
                elif section == "plugins":
                    errors.append(f"{label}: plugin version is required")
            else:
                errors.append(f"{label}: invalid declaration")
    for name, members in data.get("bundles", {}).items():
        if not isinstance(members, list):
            errors.append(f"{path}: bundles.{name}: expected a list of library aliases")
            continue
        for member in members:
            if member not in data.get("libraries", {}):
                errors.append(f"{path}: bundles.{name}: unknown library {member!r}")
    return errors


# Match comments and Kotlin strings together, so URLs in strings aren't stripped
# as comments and commented-out dependencies don't produce findings.
TOKENS = re.compile(r'/\*.*?\*/|//[^\n]*|"(?:\\.|[^"\\])*"', re.DOTALL)
COORDINATE = re.compile(r"[\w.-]+:[\w.-]+:([^:@]+)(?::[^@]+)?(?:@\w+)?$")


def check_gradle(path: Path) -> list[str]:
    text = path.read_text(encoding="utf-8")
    errors: list[str] = []
    for match in TOKENS.finditer(text):
        token = match.group()
        if not token.startswith('"'):
            continue
        value = token[1:-1]
        coordinate = COORDINATE.fullmatch(value)
        prefix = text[max(0, match.start() - 100):match.start()]
        is_version = re.search(r'\bversion\s*(?:=\s*|\(\s*)?$', prefix)
        if coordinate:
            value = coordinate[1]
        elif not is_version:
            continue
        # Computed versions need Gradle resolution; only literal versions are checked.
        if "$" not in value:
            line = text.count("\n", 0, match.start()) + 1
            check_version(value, f"{path}:{line}", errors)
    return errors


def main(paths: list[str]) -> int:
    errors: list[str] = []
    # Validate the shared catalog even if only a build script is staged.
    catalog = Path("gradle/libs.versions.toml")
    candidates = set(paths)
    if catalog.is_file():
        candidates.add(catalog.as_posix())
    for filename in sorted(candidates):
        path = Path(filename)
        if filename.endswith(".versions.toml"):
            errors.extend(check_catalog(path))
        elif filename.endswith(".gradle.kts"):
            errors.extend(check_gradle(path))
    for error in errors:
        print(error, file=sys.stderr)
    return bool(errors)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
