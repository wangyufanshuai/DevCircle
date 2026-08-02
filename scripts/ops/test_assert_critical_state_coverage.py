from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("assert-critical-state-coverage.py")
SPEC = importlib.util.spec_from_file_location("critical_state_coverage", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"cannot load {SCRIPT}")
coverage = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(coverage)


def write_report(root: Path, branch_covered: int, branch_missed: int) -> None:
    by_module: dict[str, list[str]] = {}
    for module, class_name in coverage.CRITICAL_CLASSES:
        by_module.setdefault(module, []).append(class_name)
    for module, class_names in by_module.items():
        class_dir = root / module / "target" / "site" / "jacoco"
        class_dir.mkdir(parents=True, exist_ok=True)
        classes = "".join(
            '<class name="%s"><counter type="BRANCH" covered="%s" missed="%s"/></class>'
            % (class_name, branch_covered, branch_missed)
            for class_name in class_names
        )
        (class_dir / "jacoco.xml").write_text(
            f"<report><package>{classes}</package></report>",
            encoding="utf-8",
        )


class CriticalStateCoverageTest(unittest.TestCase):
    def test_accepts_critical_classes_at_the_release_threshold(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_report(root, 17, 3)
            self.assertEqual([], coverage.validate(root, 85.0))

    def test_rejects_a_critical_class_below_the_release_threshold(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_report(root, 16, 4)
            failures = coverage.validate(root, 85.0)
            self.assertTrue(any("branch 80.00% < 85.00%" in failure for failure in failures))

    def test_rejects_a_missing_class_report(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_report(root, 17, 3)
            first_module, _ = coverage.CRITICAL_CLASSES[0]
            (root / first_module / "target" / "site" / "jacoco" / "jacoco.xml").write_text(
                "<report><package /></report>", encoding="utf-8"
            )
            failures = coverage.validate(root, 85.0)
            self.assertTrue(any("missing class" in failure for failure in failures))


if __name__ == "__main__":
    unittest.main()
