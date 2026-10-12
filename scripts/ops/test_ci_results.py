import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("ci_results", Path(__file__).with_name("assert-ci-results.py"))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class CiResultsTest(unittest.TestCase):
    def results(self):
        return {name: {"result": "success"} for name in gate.REQUIRED}

    def test_all_required_jobs_must_succeed(self):
        gate.validate(self.results())

    def test_missing_job_is_not_success(self):
        results = self.results()
        del results["backend"]
        with self.assertRaises(ValueError):
            gate.validate(results)

    def test_failure_skip_and_cancellation_block_gate(self):
        for status in ("failure", "skipped", "cancelled", None):
            with self.subTest(status=status):
                results = self.results()
                results["real-review-e2e"] = {"result": status}
                with self.assertRaises(ValueError):
                    gate.validate(results)

    def test_release_jobs_are_not_required_for_validation(self):
        results = self.results()
        results["images"] = {"result": "skipped"}
        gate.validate(results)
