import unittest
from unittest.mock import patch

from scripts.ops.rehearse_search_crash import SearchCrash, recovery_complete, Rehearsal


class SearchCrashEvidenceTest(unittest.TestCase):
    def test_completion_during_offset_read_uses_the_later_database_state(self):
        run = SearchCrash.__new__(SearchCrash)
        durable = {"status": "PROCESSING"}
        def commit_during_query():
            durable["status"] = "COMPLETED"
            return 2
        with patch.object(run, "committed", side_effect=commit_during_query), \
                patch.object(run, "state", side_effect=lambda _: dict(durable)):
            state, offset = run.observe_recovery("fixture-event")
        self.assertTrue(recovery_complete(state, offset))

    def test_offset_commit_does_not_turn_an_unfinished_event_into_success(self):
        for status in ("PROCESSING", "FAILED"):
            with self.subTest(status=status):
                self.assertFalse(recovery_complete({"status": status}, 1))
                with self.assertRaisesRegex(AssertionError, "unfinished"):
                    recovery_complete({"status": status}, 2)

    def test_both_persistent_completion_and_source_ack_are_required(self):
        self.assertFalse(recovery_complete({"status": "COMPLETED"}, 1))
        self.assertTrue(recovery_complete({"status": "COMPLETED"}, 2))
        for state, offset in ((None, 2), ({"status": "UNKNOWN"}, 1), ({"status": "COMPLETED"}, 3)):
            with self.subTest(state=state, offset=offset), self.assertRaises(AssertionError):
                recovery_complete(state, offset)

    def test_unpause_error_does_not_skip_owned_project_cleanup(self):
        run = SearchCrash.__new__(SearchCrash)
        run.paused = True
        run.receipt = {}
        with patch.object(run, "dc", side_effect=TimeoutError), patch.object(Rehearsal, "close") as close:
            run.close()
            close.assert_called_once()
        self.assertFalse(run.paused)
        self.assertEqual(run.receipt["unpauseError"], "TimeoutError")


if __name__ == "__main__":
    unittest.main()
