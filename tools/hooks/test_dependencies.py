"""Regression tests for invalid pins and references, without Gradle or network."""

import tempfile
import unittest
from pathlib import Path

from check_dependencies import check_catalog, check_gradle


class DependencyChecksTest(unittest.TestCase):
    def check(self, content, check=check_catalog):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "fixture"
            path.write_text(content, encoding="utf-8")
            return check(path)

    def test_fixed_versions_and_bom_managed_library(self):
        self.assertEqual([], self.check('''
[versions]
kotlin = "2.4.10"
[libraries]
core = { module = "example:core", version.ref = "kotlin" }
bomManaged = { module = "example:bom-managed" }
[plugins]
kotlin = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
[bundles]
all = ["core", "bomManaged"]
'''))

    def test_dynamic_versions(self):
        for version in ("1.+", "latest.release", "1.0-SNAPSHOT", "[1.0,2.0)", "*"):
            with self.subTest(version=version):
                self.assertTrue(self.check(f'[versions]\nbad = "{version}"\n'))

    def test_unknown_references(self):
        errors = self.check('''
[libraries]
bad = { module = "example:core", version.ref = "missing" }
[bundles]
bad = ["absent"]
''')
        self.assertEqual(2, len(errors))

    def test_rich_fixed_and_dynamic_versions(self):
        self.assertEqual([], self.check('[versions]\na = { strictly = "1.2.3" }'))
        self.assertTrue(self.check('[versions]\na = { require = "1.+" }'))

    def test_gradle_literals_and_comments(self):
        errors = self.check('''
// implementation("example:old:1.+")
/* id("plugin") version "latest.release" */
implementation("example:core:1.+")
id("plugin") version "1.0-SNAPSHOT"
implementation("example:fixed:1.0.0")
maven("https://example.com/releases")
''', check_gradle)
        self.assertEqual(2, len(errors))

    def test_invalid_toml(self):
        self.assertTrue(self.check('[versions\nbad ='))


if __name__ == "__main__":
    unittest.main()
