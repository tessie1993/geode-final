"""Run the project's existing ktlint rules against the filenames from pre-commit."""

from __future__ import annotations

import os
from pathlib import Path
import subprocess
import sys


def main() -> int:
    if not sys.argv[1:]:
        return 0
    root = Path(__file__).resolve().parents[2]
    env = os.environ.copy()
    # The ktlint-gradle plugin's own Git hook uses this newline-delimited property.
    # An environment variable avoids passing filenames through Windows batch syntax.
    env["ORG_GRADLE_PROJECT_internalKtlintGitFilter"] = "\n".join(sys.argv[1:])
    wrapper = root / ("gradlew.bat" if os.name == "nt" else "gradlew")
    command = [str(wrapper)] if os.name == "nt" else ["sh", str(wrapper)]
    return subprocess.call(
        [*command, "--console=plain", "ktlintCheck"], cwd=root, env=env,
    )


if __name__ == "__main__":
    raise SystemExit(main())
