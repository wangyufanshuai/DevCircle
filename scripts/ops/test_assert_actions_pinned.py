import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("assert-actions-pinned.py")
SPEC = importlib.util.spec_from_file_location("assert_actions_pinned", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class ActionsPinnedTest(unittest.TestCase):
    def test_accepts_full_commit_and_local_action(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "ci.yml").write_text(
                "steps:\n"
                "  - uses: actions/checkout@" + "a" * 40 + " # v5\n"
                "  - uses: ./actions/local\n",
                encoding="utf-8",
            )
            self.assertEqual([], MODULE.violations(root))

    def test_rejects_mutable_tag_branch_and_short_sha(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "security.yaml").write_text(
                "steps:\n"
                "  - uses: actions/checkout@v5\n"
                "  - uses: owner/action@main\n"
                "  - uses: owner/action@1234567\n",
                encoding="utf-8",
            )
            failures = MODULE.violations(root)
            self.assertEqual(3, len(failures))
            self.assertTrue(all("security.yaml" in failure for failure in failures))


if __name__ == "__main__":
    unittest.main()
