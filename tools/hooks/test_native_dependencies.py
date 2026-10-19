"""Exercise native pin checks against real, isolated Git indexes."""

import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("check_native_dependencies.py").resolve()


class NativeDependencyChecksTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.env = os.environ.copy()
        # Do not inherit the real repository's hook/index context.
        for key in list(self.env):
            if key.startswith("GIT_"):
                del self.env[key]
        self.env.update(GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM="1")
        self.git("init", "--quiet")
        self.git("config", "user.name", "Hook test")
        self.git("config", "user.email", "hook-test@example.invalid")
        self.git("config", "core.autocrlf", "false")
        self.git("-c", "commit.gpgsign=false", "commit", "--quiet", "--allow-empty", "-m", "Fixture")
        self.revision = self.git("rev-parse", "HEAD").strip()

    def git(self, *args):
        return subprocess.check_output(
            ["git", *args], cwd=self.root, env=self.env, text=True,
            stderr=subprocess.PIPE,
        )

    def manifest(self):
        path = self.root / ".gitmodules"
        path.write_text(
            '[submodule "third_party/example"]\n'
            '\tpath = third_party/example\n'
            '\turl = https://example.invalid/library.git\n',
            encoding="utf-8",
        )
        self.git("add", "--", ".gitmodules")
        return path

    def pin(self):
        self.git("update-index", "--add", "--cacheinfo", f"160000,{self.revision},third_party/example")

    def run_check(self):
        return subprocess.run(
            [sys.executable, str(SCRIPT)], cwd=self.root, env=self.env,
            text=True, capture_output=True, check=False,
        )

    def test_missing_pin_fails(self):
        self.manifest()
        result = self.run_check()
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertIn("no staged Git submodule revision", result.stderr)

    def test_matching_pin_passes_without_checkout(self):
        self.manifest()
        self.pin()
        self.assertFalse((self.root / "third_party/example").exists())
        result = self.run_check()
        self.assertEqual(0, result.returncode, result.stderr)

    def test_pin_without_declaration_fails(self):
        self.pin()
        result = self.run_check()
        self.assertEqual(1, result.returncode, result.stderr)

    def test_unstaged_manifest_does_not_hide_staged_error(self):
        path = self.manifest()
        path.write_text("", encoding="utf-8")
        before = self.git("ls-files", "--stage")
        result = self.run_check()
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertEqual("", path.read_text(encoding="utf-8"))
        self.assertEqual(before, self.git("ls-files", "--stage"))

    def test_unrelated_commit_does_not_audit_old_declarations(self):
        self.manifest()
        self.git("-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "Old declaration")
        (self.root / "notes.txt").write_text("Notes\n", encoding="utf-8")
        self.git("add", "notes.txt")
        result = self.run_check()
        self.assertEqual(0, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()
