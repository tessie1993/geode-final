"""Run real pre-commit hooks in disposable repos; never alter the source index.

Requires requirements-smoke.txt and network access on a cold hook cache.
Kotlin uses a Gradle wrapper stand-in to test dispatch, not ktlint itself.
Kept outside test_*.py so the existing regression suite stays offline.
"""

from __future__ import annotations

import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import unittest

import yaml


SOURCE = Path(__file__).resolve().parents[2]
CONFIG = yaml.safe_load((SOURCE / '.pre-commit-config.yaml').read_text())
HOOK_IDS = [hook['id'] for repo in CONFIG['repos'] for hook in repo['hooks']]
CATALOG = '[versions]\ncore = "1.2.3"\n'
MANIFEST = (
    '[submodule "third_party/example"]\n'
    '\tpath = third_party/example\n'
    '\turl = https://example.invalid/library.git\n'
)


class PreCommitSmoke(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory(prefix='geode-hooks-')
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.env = {
            key: value for key, value in os.environ.items()
            if not key.startswith('GIT_') and key != 'SKIP'
        }
        self.env.update(GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM='1',
                        PYTHONDONTWRITEBYTECODE='1')
        self.git('init', '--quiet')
        self.git('config', 'user.name', 'Hook smoke')
        self.git('config', 'user.email', 'hook-smoke@example.invalid')
        self.git('config', 'core.autocrlf', 'false')
        for name in ('.pre-commit-config.yaml', '.clang-format', '.cmake-format.yaml',
                     'tools/hooks/check_dependencies.py', 'tools/hooks/check_kotlin.py',
                     'tools/hooks/check_native_dependencies.py'):
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(SOURCE / name, target)
        # A committed old manifest with missing gitlinks must not block unrelated work.
        shutil.copyfile(SOURCE / '.gitmodules', self.root / '.gitmodules')
        self.write('gradle/libs.versions.toml', CATALOG)
        self.write('gradlew', f'exec {shlex.quote(sys.executable)} kotlin_probe.py "$@"\n')
        self.write('kotlin_probe.py',
                   'import json, os, pathlib, sys\n'
                   'files = os.environ["ORG_GRADLE_PROJECT_internalKtlintGitFilter"].splitlines()\n'
                   'print("KOTLIN_BRIDGE=" + json.dumps({"args": sys.argv[1:], '
                   '"cwd": os.getcwd(), "files": sorted(files), '
                   '"contents": {p: pathlib.Path(p).read_text() for p in files}}))\n'
                   'sys.exit(int(os.environ.get("SMOKE_GRADLE_EXIT", "0")))\n')
        self.git('add', '.')
        self.commit('Fixture baseline')

    def command(self, *args):
        return subprocess.run(args, cwd=self.root, env=self.env, text=True,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              timeout=300, check=False)

    def git(self, *args):
        result = self.command('git', *args)
        self.assertEqual(0, result.returncode, result.stdout)
        return result.stdout

    def commit(self, message):
        self.git('-c', 'commit.gpgsign=false', 'commit', '--quiet', '-m', message)

    def write(self, name, content, *, stage=False):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content.encode())
        if stage:
            self.git('add', '--', name)

    def snapshot(self):
        return (
            self.git('ls-files', '--stage', '-z'),
            self.git('status', '--porcelain=v1', '-z'),
            {str(p.relative_to(self.root)): p.read_bytes()
             for p in self.root.rglob('*')
             if p.is_file() and '.git' not in p.relative_to(self.root).parts},
        )

    def run_hook(self, hook=None, *, fails=False, diagnostic=None):
        before = self.snapshot()
        args = ['run', '--verbose', '--color=never']
        if hook:
            args.append(hook)
        result = self.command(sys.executable, '-m', 'pre_commit', *args)
        self.assertEqual(before, self.snapshot(), result.stdout)
        self.assertEqual(1 if fails else 0, result.returncode, result.stdout)
        lines = result.stdout.splitlines()
        for hook_id in [hook] if hook else HOOK_IDS:
            marker = f'- hook id: {hook_id}'
            self.assertIn(marker, lines, result.stdout)
            status = lines[lines.index(marker) - 1]
            # This upstream fail-only hook selects illegal filenames, so good
            # names skip it. Its CON.txt failure is exercised separately.
            expected = 'Skipped' if not hook and hook_id == 'check-illegal-windows-names' else (
                'Failed' if fails else 'Passed'
            )
            self.assertTrue(status.endswith(expected), result.stdout)
        if diagnostic:
            self.assertIn(diagnostic, result.stdout)
        return result.stdout

    def pin(self):
        revision = self.git('rev-parse', 'HEAD').strip()
        self.git('update-index', '--add', '--cacheinfo',
                 f'160000,{revision},third_party/example')

    def test_all_hooks_accept_staged_fixtures_with_legacy_missing_gitlinks(self):
        fixtures = {
            'smoke.yaml': 'answer: 42\n', 'smoke.json': '{"answer": 42}\n',
            'smoke.xml': '<answer>42</answer>\n', 'notes.txt': 'Smoke checks\n',
            'core/smoke.c': 'int main(void) { return 0; }\n',
            'core/smoke.cpp': 'int smoke() { return 0; }\n',
            'CMakeLists.txt': 'cmake_minimum_required(VERSION 3.22)\nproject(HookSmoke LANGUAGES C CXX)\n',
            'gradle/libs.versions.toml': CATALOG + '# staged catalog\n',
            'build.gradle.kts': 'dependencies { implementation("example:core:1.2.3") }\n',
            'app/src/main/kotlin/Smoke.kt': 'val smoke = 42\n',
        }
        for name, content in fixtures.items():
            self.write(name, content, stage=True)
        self.run_hook()

    def reject(self, hook, name, content, diagnostic):
        self.write(name, content, stage=True)
        self.run_hook(hook, fails=True, diagnostic=diagnostic)

    def test_merge_conflict_is_check_only(self):
        self.reject('check-merge-conflict', 'conflict.txt',
                    '<<<<<<< ours\n=======\n>>>>>>> theirs\n', 'Merge conflict string')

    def test_windows_filename_is_check_only(self):
        self.reject('check-illegal-windows-names', 'CON.txt', 'reserved\n', 'CON.txt')

    def test_yaml_failure_is_check_only(self):
        self.reject('check-yaml', 'bad.yaml', 'broken: [\n', 'bad.yaml')

    def test_toml_failure_is_check_only(self):
        self.reject('check-toml', 'bad.toml', '[broken\n', 'bad.toml')

    def test_json_failure_is_check_only(self):
        self.reject('check-json', 'bad.json', '{\n', 'bad.json')

    def test_xml_failure_is_check_only(self):
        self.reject('check-xml', 'bad.xml', '<broken>\n', 'bad.xml')

    def test_private_key_marker_is_check_only(self):
        self.reject('detect-private-key', 'key.txt',
                    '-----BEGIN ' + 'PRIVATE KEY-----\n', 'Private key found')

    def test_line_endings_are_check_only(self):
        self.reject('mixed-line-ending', 'mixed.txt', 'one\r\ntwo\n', 'mixed line endings')

    def test_whitespace_is_check_only(self):
        self.reject('staged-whitespace', 'spaces.txt', 'trailing  \n', 'trailing whitespace')

    def test_case_conflict_is_check_only(self):
        self.write('Case.txt', 'one\n', stage=True)
        self.write('case.txt', 'two\n', stage=True)
        self.run_hook('check-case-conflict', fails=True, diagnostic='Case.txt')

    def test_clang_format_failure_is_check_only(self):
        self.reject('clang-format', 'core/bad.cpp', 'int  bad( ){return 0;}\n',
                    'code should be clang-formatted')

    def test_cppcheck_failure_is_check_only(self):
        self.reject('cppcheck', 'core/bug.cpp', 'int main() { int *p = 0; return *p; }\n',
                    'nullPointer')

    def test_cmake_failure_is_check_only(self):
        self.reject('cmake-lint', 'bad.cmake', 'set(bad-variable 1)\n', 'C0103')

    def test_partial_catalog_uses_index_in_both_directions(self):
        path = 'gradle/libs.versions.toml'
        bad = '[versions]\ncore = "1.+"\n'
        for staged, working, fails in ((CATALOG, bad, False), (bad, CATALOG, True)):
            with self.subTest(fails=fails):
                self.write(path, staged + '# staged\n', stage=True)
                self.write(path, working)
                self.run_hook('dependency-pins', fails=fails,
                              diagnostic='pin an exact release' if fails else None)

    def test_partial_gradle_uses_index_in_both_directions(self):
        path = 'build.gradle.kts'
        good = 'dependencies { implementation("example:core:1.2.3") }\n'
        bad = good.replace('1.2.3', '1.+')
        for staged, working, fails in ((good, bad, False), (bad, good, True)):
            with self.subTest(fails=fails):
                self.write(path, staged, stage=True)
                self.write(path, working)
                self.run_hook('dependency-pins', fails=fails,
                              diagnostic='pin an exact release' if fails else None)

    def test_build_script_checks_unstaged_catalog_from_index(self):
        self.write('build.gradle.kts', '// dependency change\n', stage=True)
        self.write('gradle/libs.versions.toml', '[versions]\ncore = "latest.release"\n')
        self.run_hook('dependency-pins')

    def test_staged_missing_native_pin_fails_despite_unstaged_fix(self):
        self.write('.gitmodules', MANIFEST, stage=True)
        self.write('.gitmodules', '')
        self.run_hook('native-dependency-pins', fails=True,
                      diagnostic='no staged Git submodule revision')

    def test_staged_native_pin_passes_despite_unstaged_bad_manifest(self):
        self.write('.gitmodules', MANIFEST, stage=True)
        self.pin()
        # Git's checkout during pre-commit stashing creates empty gitlink
        # directories. Start with that normal uninitialized-submodule layout
        # so our status comparison still detects any actual content changes.
        (self.root / 'third_party/example').mkdir(parents=True)
        self.assertFalse((self.root / 'third_party/example/.git').exists())
        self.write('.gitmodules', MANIFEST.replace('path = third_party/example',
                                                 'path = third_party/missing'))
        self.run_hook('native-dependency-pins')

    def test_gitlink_only_change_is_checked_without_checkout(self):
        self.pin()
        self.assertFalse((self.root / 'third_party/example').exists())
        self.run_hook('native-dependency-pins', fails=True,
                      diagnostic='third_party/example: missing .gitmodules declaration')

    def test_excluded_paths_are_not_linted(self):
        self.write('build/bad.cpp', 'not formatted or valid C++\n', stage=True)
        self.write('build/bad.yaml', 'broken: [\n', stage=True)
        before = self.snapshot()
        result = self.command(sys.executable, '-m', 'pre_commit', 'run', '--color=never')
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertEqual(before, self.snapshot(), result.stdout)
        for label in ('Check C/C++ formatting', 'Check C/C++ bugs with Cppcheck', 'check yaml'):
            self.assertRegex(result.stdout, re.escape(label) + r'.*\(no files to check\)Skipped')

    def test_kotlin_bridge_filter_snapshot_and_failure_propagation(self):
        paths = ['app/src/main/kotlin/Space Name.kt', 'engine/example/Smoke.kt']
        for path in paths:
            self.write(path, 'val staged = 1\n', stage=True)
        self.write(paths[0], 'val unstaged = 2\n')
        self.write('outside/NotSelected.kt', 'val outside = 3\n', stage=True)
        for exit_code in ('0', '7'):
            with self.subTest(gradle_exit=exit_code):
                self.env['SMOKE_GRADLE_EXIT'] = exit_code
                output = self.run_hook('ktlint', fails=exit_code != '0')
                line = next(line for line in output.splitlines() if line.startswith('KOTLIN_BRIDGE='))
                probe = json.loads(line.partition('=')[2])
                self.assertEqual(['--console=plain', 'ktlintCheck'], probe['args'])
                self.assertEqual(str(self.root), probe['cwd'])
                self.assertEqual(sorted(paths), probe['files'])
                self.assertEqual({path: 'val staged = 1\n' for path in paths}, probe['contents'])
                if exit_code != '0':
                    self.assertIn('- exit code: 7', output)

    def test_installed_git_hook_rejects_bad_commit_and_accepts_fix(self):
        result = self.command(sys.executable, '-m', 'pre_commit', 'install', '--install-hooks')
        self.assertEqual(0, result.returncode, result.stdout)
        self.write('commit.txt', 'trailing  \n', stage=True)
        head, before = self.git('rev-parse', 'HEAD'), self.snapshot()
        result = self.command('git', '-c', 'commit.gpgsign=false', 'commit', '-m', 'Must fail')
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn('trailing whitespace', result.stdout)
        self.assertEqual(head, self.git('rev-parse', 'HEAD'))
        self.assertEqual(before, self.snapshot(), result.stdout)
        self.write('commit.txt', 'clean\n', stage=True)
        self.commit('Passes installed hooks')
        self.assertNotEqual(head, self.git('rev-parse', 'HEAD'))
        self.assertEqual('', self.git('status', '--porcelain'))


if __name__ == '__main__':
    unittest.main()
