import unittest

from tools.check import unique_object
from tools.check_branch_name import valid


class ChecksTest(unittest.TestCase):
    def test_valid_branches(self):
        for prefix in ("feat", "fix", "chore", "refactor", "docs"):
            self.assertTrue(valid(f"{prefix}/mod-foundation"))

    def test_invalid_branches(self):
        for name in ("main", "feat/Test", "feat/-test", "feat/a--b", "feat/a_b", "feat/a/b", ""):
            self.assertFalse(valid(name))

    def test_dependabot_exception_requires_bot_author(self):
        self.assertTrue(valid("dependabot/gradle/test", "dependabot[bot]"))
        self.assertFalse(valid("dependabot/gradle/test", "someone"))

    def test_duplicate_json_keys_are_rejected(self):
        with self.assertRaises(ValueError):
            unique_object([("id", "a"), ("id", "b")])


if __name__ == "__main__":
    unittest.main()
