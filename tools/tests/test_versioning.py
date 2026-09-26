import unittest

from tools.versioning import read_version, validate_tag


class VersioningTest(unittest.TestCase):
    def test_supported_versions(self):
        for version in ("0.2.0", "0.2.1", "1.0.0", "0.2.0-rc.1", "0.3.0-beta.2", "2.0.0-alpha.1"):
            self.assertEqual(version, read_version(f"mod_version={version}\n"))
            validate_tag(version, f"v{version}")

    def test_ambiguous_versions_are_rejected(self):
        for version in ("0.2", "01.2.0", "1.0.0-rc.0", "1.0.0-SNAPSHOT", "x", "1.0.0\nmod_version=2.0.0"):
            with self.assertRaises(ValueError):
                read_version(f"mod_version={version}\n")

    def test_missing_and_mismatched_versions_are_rejected(self):
        with self.assertRaises(ValueError):
            read_version("minecraft_version=26.1.2\n")
        for tag in ("0.2.0", "v0.1.0", "v0.2.0-rc.1", "v0.2.0;echo bad"):
            with self.assertRaises(ValueError):
                validate_tag("0.2.0", tag)
