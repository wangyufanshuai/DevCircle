import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("assert-observability-runbooks.py")
SPEC = importlib.util.spec_from_file_location("assert_observability_runbooks", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class ObservabilityRunbookContractTest(unittest.TestCase):
    def test_accepts_alerts_with_existing_runbook_headings(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            rules = root / "alerts.yml"
            runbook = root / "runbook.md"
            rules.write_text(
                "- alert: ExampleDown\n"
                "  annotations:\n"
                "    runbook_url: \"https://example/runbook.md#service-down\"\n",
                encoding="utf-8",
            )
            runbook.write_text("# Runbook\n\n## service-down\n", encoding="utf-8")
            self.assertEqual([], MODULE.validate([rules], runbook))

    def test_rejects_missing_links_and_missing_sections(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            rules = root / "alerts.yml"
            runbook = root / "runbook.md"
            rules.write_text(
                "- alert: MissingUrl\n  annotations: {}\n"
                "- alert: MissingSection\n"
                "  annotations:\n"
                "    runbook_url: \"https://example/runbook.md#not-documented\"\n",
                encoding="utf-8",
            )
            runbook.write_text("# Runbook\n\n## service-down\n", encoding="utf-8")
            failures = MODULE.validate([rules], runbook)
            self.assertEqual(2, len(failures))
            self.assertTrue(any("alerts=2 runbook_urls=1" in failure for failure in failures))
            self.assertTrue(any("#not-documented" in failure for failure in failures))
