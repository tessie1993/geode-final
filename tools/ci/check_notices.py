#!/usr/bin/env python3
"""Fail when the third-party notices drift from what the app ships.

THIRD_PARTY_NOTICES at the repository root is the notices file; the app ships a copy of it as
app/src/main/assets/third_party_notices.txt. This check fails when:

- the shipped copy is not byte for byte the repository file;
- the projectM patches shipped in the app (app/src/main/assets/third_party/projectm/, LGPL-2.1's
  corresponding source of Geode's modification) are not exactly tools/projectm-*.patch, the
  patches the build applies;
- a git submodule (.gitmodules) is not named, by its URL, in the notices;
- a library the app ships (an implementation/api/runtimeOnly dependency in any build file, read
  through the version catalog) belongs to a group GROUP_NOTICES does not know, or the notices
  lack the entry GROUP_NOTICES names for that group. Test, KSP and debug-only dependencies are
  not shipped and are skipped.

A new shipped dependency group therefore needs a notices entry (with its licence text) and a line
in GROUP_NOTICES; the full audit is the issue that adds it.

    python3 tools/ci/check_notices.py               # check the repository
    python3 tools/ci/check_notices.py --self-test   # prove the check catches drift
"""

from __future__ import annotations

import re
import sys
import tempfile
import tomllib
from pathlib import Path

NOTICES = "THIRD_PARTY_NOTICES"
SHIPPED_NOTICES = "app/src/main/assets/third_party_notices.txt"
PATCH_GLOB = "projectm-*.patch"
PATCH_SOURCE_DIR = "tools"
PATCH_SHIPPED_DIR = "app/src/main/assets/third_party/projectm"

# Shipped dependency group (prefix match, longest first) -> text its notices entry must contain.
GROUP_NOTICES: dict[str, str] = {
    "androidx.": "AndroidX / Jetpack",
    "org.jetbrains.kotlinx": "kotlinx",
    "org.jetbrains.kotlin": "Kotlin Standard Library",
    "com.google.dagger": "Dagger and Hilt",
    "dev.chrisbanes.haze": "https://github.com/chrisbanes/haze",
}
# Configurations whose dependencies end up in the APK.
SHIPPED_CONFIGURATIONS = {
    "implementation", "api", "runtimeOnly", "releaseImplementation", "releaseApi", "releaseRuntimeOnly",
}

CATALOG_ACCESSOR = re.compile(r'\b"?(\w+)"?\(\s*(?:platform\()?\s*libs\.([\w.]+)')
CATALOG_LOOKUP = re.compile(r'\b"?(\w+)"?\(\s*(?:platform\()?\s*\w+\.findLibrary\("([\w.-]+)"\)')
COORDINATES = re.compile(r'\b"?(\w+)"?\(\s*(?:platform\()?\s*"([\w.-]+):([\w.-]+)(?::[^"]*)?"\s*\)')
SUBMODULE_URL = re.compile(r"^\s*url\s*=\s*(\S+)\s*$", re.M)


def accessor_key(alias: str) -> str:
    """A catalog alias as its generated accessor spells it: media3-exoplayer -> media3.exoplayer."""
    return re.sub(r"[-_.]", ".", alias)


def catalog_modules(root: Path) -> dict[str, tuple[str, str]]:
    path = root / "gradle" / "libs.versions.toml"
    if not path.is_file():
        return {}
    libraries = tomllib.loads(path.read_text()).get("libraries", {})
    out: dict[str, tuple[str, str]] = {}
    for alias, spec in libraries.items():
        if isinstance(spec, str):
            group, name = spec.split(":")[:2]
        elif "module" in spec:
            group, name = spec["module"].split(":")[:2]
        else:
            group, name = spec["group"], spec["name"]
        out[accessor_key(alias)] = (group, name)
    return out


# The convention plugins' own build: Gradle plugins on the build's classpath, never in the APK. The
# convention plugins themselves (build-logic/src) do add app dependencies and are read.
BUILD_TOOLING = {"build-logic/build.gradle.kts", "build-logic/settings.gradle.kts"}


def build_files(root: Path) -> list[Path]:
    skip = {"build", ".gradle", ".git", "third_party", "node_modules"}
    return sorted(
        p for p in root.rglob("*.gradle.kts")
        if not skip.intersection(p.relative_to(root).parts) and p.relative_to(root).as_posix() not in BUILD_TOOLING
    )


def shipped_dependencies(root: Path) -> tuple[dict[tuple[str, str], str], list[str]]:
    """{(group, name): first build file using it} for every shipped dependency, plus errors."""
    catalog = catalog_modules(root)
    found: dict[tuple[str, str], str] = {}
    errors: list[str] = []
    for path in build_files(root):
        rel = path.relative_to(root).as_posix()
        text = path.read_text()
        for pattern in (CATALOG_ACCESSOR, CATALOG_LOOKUP):
            for configuration, alias in pattern.findall(text):
                if configuration not in SHIPPED_CONFIGURATIONS:
                    continue
                module = catalog.get(accessor_key(alias))
                if module is None:
                    errors.append(f"{rel}: libs alias '{alias}' is not in gradle/libs.versions.toml")
                    continue
                found.setdefault(module, rel)
        for configuration, group, name in COORDINATES.findall(text):
            if configuration in SHIPPED_CONFIGURATIONS:
                found.setdefault((group, name), rel)
    return found, errors


def notice_for(group: str) -> str | None:
    """The notices text a group needs: a key ending in "." covers every group under it, any other key
    that group and its subgroups (org.jetbrains.kotlin covers org.jetbrains.kotlin.foo, not kotlinx)."""
    for prefix in sorted(GROUP_NOTICES, key=len, reverse=True):
        base = prefix.rstrip(".")
        if group == base or group.startswith(base + "."):
            return GROUP_NOTICES[prefix]
    return None


def normalise_url(url: str) -> str:
    return url.split("://", 1)[-1].rstrip("/").removesuffix(".git").lower()


def check(root: Path) -> list[str]:
    errors: list[str] = []
    notices_path = root / NOTICES
    if not notices_path.is_file():
        return [f"{NOTICES} is missing"]
    notices = notices_path.read_bytes()
    notices_text = notices.decode("utf-8")
    shipped = root / SHIPPED_NOTICES
    if not shipped.is_file():
        errors.append(f"{SHIPPED_NOTICES} is missing: the app must ship a copy of {NOTICES}")
    elif shipped.read_bytes() != notices:
        errors.append(f"{SHIPPED_NOTICES} differs from {NOTICES}: copy the repository file over it")

    sources = {p.name: p for p in (root / PATCH_SOURCE_DIR).glob(PATCH_GLOB)}
    copies = {p.name: p for p in (root / PATCH_SHIPPED_DIR).glob(PATCH_GLOB)}
    for name in sorted(sources.keys() - copies.keys()):
        errors.append(f"{PATCH_SOURCE_DIR}/{name} is not shipped: copy it to {PATCH_SHIPPED_DIR}/")
    for name in sorted(copies.keys() - sources.keys()):
        errors.append(f"{PATCH_SHIPPED_DIR}/{name} has no {PATCH_SOURCE_DIR}/{name}: the build does not apply it")
    for name in sorted(sources.keys() & copies.keys()):
        if sources[name].read_bytes() != copies[name].read_bytes():
            errors.append(f"{PATCH_SHIPPED_DIR}/{name} differs from {PATCH_SOURCE_DIR}/{name}")
    if sources and "assets/third_party/projectm" not in notices_text:
        errors.append(f"{NOTICES} does not say where the app ships the projectM patches")

    gitmodules = root / ".gitmodules"
    lowered = notices_text.lower()
    if gitmodules.is_file():
        for url in SUBMODULE_URL.findall(gitmodules.read_text()):
            if normalise_url(url) not in lowered:
                errors.append(f"submodule {url} is not named in {NOTICES}")

    dependencies, catalog_errors = shipped_dependencies(root)
    errors += catalog_errors
    for (group, name), where in sorted(dependencies.items()):
        notice = notice_for(group)
        if notice is None:
            errors.append(f"{where}: {group}:{name} ships in the app but its group has no notices entry "
                          f"(add one to {NOTICES} and map the group in GROUP_NOTICES)")
        elif notice not in notices_text:
            errors.append(f"{where}: {group}:{name} ships in the app but {NOTICES} lacks '{notice}'")
    return errors


def self_test() -> int:
    """Builds a clean tree, then plants one kind of drift at a time and checks each is caught."""
    notices = (
        "AndroidX / Jetpack\n------------------\n"
        "projectM https://github.com/projectM-visualizer/projectm patches in assets/third_party/projectm\n"
    )
    clean = {
        NOTICES: notices,
        SHIPPED_NOTICES: notices,
        f"{PATCH_SOURCE_DIR}/projectm-a.patch": "diff a\n",
        f"{PATCH_SHIPPED_DIR}/projectm-a.patch": "diff a\n",
        ".gitmodules": '[submodule "p"]\n\tpath = third_party/projectm\n'
                       "\turl = https://github.com/projectM-visualizer/projectm.git\n",
        "gradle/libs.versions.toml": '[libraries]\nmedia3-common = { module = "androidx.media3:media3-common", '
                                     'version = "1" }\nweird-lib = { module = "com.example:weird", version = "1" }\n',
        "app/build.gradle.kts": "dependencies {\n    implementation(libs.media3.common)\n"
                                "    testImplementation(libs.weird.lib)\n}\n",
    }
    plants = {
        "shipped copy drifts": {SHIPPED_NOTICES: notices + "extra\n"},
        "patch not shipped": {f"{PATCH_SOURCE_DIR}/projectm-b.patch": "diff b\n"},
        "shipped patch differs": {f"{PATCH_SHIPPED_DIR}/projectm-a.patch": "diff changed\n"},
        "stray shipped patch": {f"{PATCH_SHIPPED_DIR}/projectm-c.patch": "diff c\n"},
        "submodule not named": {".gitmodules": clean[".gitmodules"]
                                + '[submodule "k"]\n\turl = https://github.com/mborgerding/kissfft\n'},
        "unknown shipped group": {"app/build.gradle.kts": "dependencies {\n    implementation(libs.weird.lib)\n}\n"},
        "unknown coordinates": {"app/build.gradle.kts": 'dependencies {\n    "api"("org.example:thing:1.0")\n}\n'},
        "known group, entry missing": {"feature/x/build.gradle.kts":
                                       'dependencies {\n    implementation("dev.chrisbanes.haze:haze:2.0.1")\n}\n'},
        "alias not in catalog": {"app/build.gradle.kts": "dependencies {\n    implementation(libs.nope)\n}\n"},
    }

    def run(overrides: dict[str, str]) -> list[str]:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for name, body in {**clean, **overrides}.items():
                (root / name).parent.mkdir(parents=True, exist_ok=True)
                (root / name).write_text(body)
            return check(root)

    ok = True
    baseline = run({})
    if baseline:
        print("clean tree reported:\n  " + "\n  ".join(baseline))
        ok = False
    for label, overrides in plants.items():
        errors = run(overrides)
        caught = len(errors) == 1
        print(f"{'caught' if caught else 'MISSED'}: {label}: {errors}")
        ok = ok and caught
    print("self-test " + ("passed" if ok else "FAILED"))
    return 0 if ok else 1


def main(argv: list[str]) -> int:
    if "--self-test" in argv:
        return self_test()
    root = Path(__file__).resolve().parents[2]
    errors = check(root)
    for error in errors:
        print(f"::error::{error}")
    if errors:
        print(f"{len(errors)} notices problem(s); see {NOTICES}", file=sys.stderr)
        return 1
    print("notices: the shipped copy and patches match, every submodule and shipped library group is credited")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
