import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import Mock
from uuid import uuid4

from scripts.ops.run_search_reindex import Api, drive, output_directory


class ReindexDriverTests(unittest.TestCase):
    def test_http_driver_supplies_gateway_write_keys_and_resumes_without_leaking_token(self):
        job_id = uuid4()
        job = {"jobId": str(job_id), "phase": "COPYING", "revision": 0}
        received = []

        class Gateway(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def handle_job(self):
                key = self.headers.get("Idempotency-Key")
                received.append((self.command, self.path, key))
                if self.headers.get("Authorization") != "Bearer fixture-token" or (self.command != "GET" and not key):
                    self.send_error(400)
                    return
                if self.command == "POST":
                    job.update(phase="COMPLETED", revision=1)
                payload = json.dumps({"code": "OK", "data": job}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            do_GET = do_PUT = do_POST = handle_job

        server = ThreadingHTTPServer(("127.0.0.1", 0), Gateway)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as tmp:
                api = Api(f"http://127.0.0.1:{server.server_port}", "fixture-token")
                endpoint = f"/api/search/admin/reindex/jobs/{job_id}?maintenanceConfirmed=true"
                api.request("PUT", endpoint)
                api.request("PUT", endpoint)
                self.assertEqual(received[0][2], received[1][2])
                result = drive(api, job_id, Path(tmp), create=False)
                self.assertEqual(result["phase"], "COMPLETED")
                self.assertIsNone(received[2][2])
                self.assertNotEqual(received[3][2], received[0][2])
                self.assertLessEqual(len(received[3][2]), 128)
                self.assertNotIn("fixture-token", (Path(tmp) / "status.json").read_text())
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)

    def test_checkpointed_stop_and_resume_uses_server_revision(self):
        job_id = uuid4()
        first = {"jobId": str(job_id), "phase": "COPYING", "revision": 0}
        second = dict(first, phase="VERIFYING", revision=1, copied=20)
        completed = dict(second, phase="COMPLETED", revision=2, verified=20)
        with tempfile.TemporaryDirectory() as tmp:
            directory = output_directory(Path(tmp), job_id)
            api = Mock()
            api.request.side_effect = [first, second]
            self.assertEqual(drive(api, job_id, directory, create=True, max_steps=1),
                             json.loads((directory / "status.json").read_text()))
            self.assertEqual(api.request.call_count, 2)
            api.request.reset_mock(side_effect=True)
            api.request.side_effect = [second, completed]
            result = drive(api, job_id, directory, create=False)
            self.assertEqual(result["phase"], "COMPLETED")
            self.assertEqual(api.request.call_args.args, ("POST", f"/api/search/admin/reindex/jobs/{job_id}/step?revision=1"))

    def test_unknown_outcome_preserves_last_receipt_for_resume(self):
        job_id = uuid4()
        with tempfile.TemporaryDirectory() as tmp:
            directory = output_directory(Path(tmp), job_id)
            api = Mock()
            api.request.side_effect = [{"jobId": str(job_id), "phase": "COPYING", "revision": 3}, RuntimeError("unknown outcome")]
            with self.assertRaises(RuntimeError):
                drive(api, job_id, directory, create=False)
            self.assertEqual(json.loads((directory / "status.json").read_text())["revision"], 3)

    def test_failed_job_is_terminal_and_unknown_fields_are_not_persisted(self):
        job_id = uuid4()
        with tempfile.TemporaryDirectory() as tmp:
            api = Mock()
            api.request.return_value = {"jobId": str(job_id), "phase": "FAILED", "revision": 2, "secret": "must-not-store"}
            result = drive(api, job_id, Path(tmp), create=False)
            self.assertEqual(result["phase"], "FAILED")
            self.assertEqual(api.request.call_count, 1)
            self.assertNotIn("must-not-store", (Path(tmp) / "status.json").read_text())

    def test_mismatched_job_cannot_replace_receipt(self):
        with tempfile.TemporaryDirectory() as tmp:
            api = Mock()
            api.request.return_value = {"jobId": str(uuid4()), "phase": "COPYING", "revision": 0}
            with self.assertRaises(RuntimeError):
                drive(api, uuid4(), Path(tmp), create=False)
            self.assertFalse((Path(tmp) / "status.json").exists())

    def test_c_drive_and_insecure_remote_origins_are_rejected(self):
        with self.assertRaises(ValueError):
            output_directory(Path(r"C:\reindex"), uuid4())
        for url in ["http://example.com", "https://user:password@example.com", "https://example.com/?token=secret"]:
            with self.subTest(url=url), self.assertRaises(ValueError):
                Api(url, "test-token")

    def test_nonadvancing_response_cannot_spin_forever(self):
        job_id = uuid4()
        with tempfile.TemporaryDirectory() as tmp:
            api = Mock()
            api.request.return_value = {"jobId": str(job_id), "phase": "COPYING", "revision": 0}
            with self.assertRaisesRegex(RuntimeError, "did not advance"):
                drive(api, job_id, Path(tmp), create=False)
            self.assertEqual(api.request.call_count, 2)


if __name__ == "__main__":
    unittest.main()
