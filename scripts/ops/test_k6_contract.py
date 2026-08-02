from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SOURCE = (ROOT / "tests" / "k6" / "mysticverse-smoke.js").read_text(encoding="utf-8")


class K6ContractTest(unittest.TestCase):
    def test_every_operation_has_its_own_latency_and_error_threshold(self) -> None:
        limits = {
            "feed": 300,
            "search": 300,
            "detail": 300,
            "like": 600,
            "unlike": 600,
            "answer": 600,
        }
        for operation, p95 in limits.items():
            with self.subTest(operation=operation):
                self.assertIn(
                    f"'http_req_duration{{operation:{operation}}}': ['p(95)<{p95}']",
                    SOURCE,
                )
                self.assertIn(
                    f"'http_req_failed{{operation:{operation}}}': ['rate<0.01']",
                    SOURCE,
                )

    def test_write_idempotency_keys_are_unique_across_test_runs(self) -> None:
        self.assertIn("__ENV.RUN_ID", SOURCE)
        self.assertIn("const requestId = `${runId}-${__VU}-${__ITER}`", SOURCE)
        for operation in ("like", "unlike", "answer"):
            self.assertIn(f"`k6-{operation}-${{requestId}}`", SOURCE)

    def test_setup_rejects_an_invalid_performance_fixture(self) -> None:
        self.assertIn("export function setup()", SOURCE)
        self.assertIn("body?.data?.status !== 'PUBLISHED'", SOURCE)
        self.assertIn("body?.data?.solvable !== true", SOURCE)
        self.assertIn("POST_ID preflight failed with HTTP", SOURCE)


if __name__ == "__main__":
    unittest.main()
