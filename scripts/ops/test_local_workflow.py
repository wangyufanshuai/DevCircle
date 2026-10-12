import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("local_workflow", Path(__file__).parents[1] / "dev/local.py")
local = importlib.util.module_from_spec(spec)
spec.loader.exec_module(local)


class ResourceEvidenceTest(unittest.TestCase):
    def sample(self):
        return {"stats": [{"MemUsage": "128MiB / 256MiB"}], "states": [
            {"status": "running", "health": "healthy", "oom_killed": False, "restart_count": 0, "exit_code": 0}]}

    def test_units_and_peak_aggregation(self):
        self.assertEqual(local.mib("1.5GiB"), 1536)
        self.assertEqual(local.mib("1024KiB"), 1)
        self.assertEqual(local.summarize([self.sample()], 1)["peak_sampled_total_mib"], 128)

    def test_missing_evidence_cannot_pass(self):
        for samples in ([], [{"stats": [], "states": []}]):
            with self.subTest(samples=samples), self.assertRaises(ValueError): local.summarize(samples, 1)
        sample = self.sample(); sample["stats"] = []
        with self.assertRaises(ValueError): local.summarize([sample], 1)

    def test_oom_restart_exit_and_unhealthy_are_failures(self):
        for field, value in (("oom_killed", True), ("restart_count", 1), ("status", "exited"), ("health", "unhealthy")):
            with self.subTest(field=field):
                sample = self.sample(); sample["states"][0][field] = value
                with self.assertRaises(ValueError): local.summarize([sample], 1)

    def test_partial_service_set_cannot_pass(self):
        with self.assertRaises(ValueError): local.summarize([self.sample()], 2)

    def test_failure_in_an_earlier_sample_cannot_be_hidden_by_recovery(self):
        earlier = self.sample(); earlier["states"][0]["oom_killed"] = True
        with self.assertRaises(ValueError): local.summarize([earlier, self.sample()], 1)

    def test_preflight_rejects_low_host_memory_or_public_ports(self):
        for available, address in ((512 * 1024**2, "127.0.0.1"), (2 * 1024**3, "0.0.0.0")):
            outputs = [json.dumps({"MemTotal": 8 * 1024**3, "ServerVersion": "test"}),
                       json.dumps({"services": {"core": {"mem_limit": 256 * 1024**2,
                                                         "ports": [{"host_ip": address}]}}}), "core"]
            with self.subTest(available=available, address=address), \
                    patch.object(local, "capture", side_effect=outputs), \
                    patch.object(local, "disk_preflight", return_value={}), \
                    patch.object(local, "host_available", return_value=available), self.assertRaises(ValueError):
                local.preflight()

    def test_disk_exhaustion_blocks_work_before_docker_calls(self):
        with patch.object(local.shutil, "disk_usage") as usage, patch.object(local, "capture") as capture:
            usage.return_value.free = 64 * 1024**2
            with self.assertRaisesRegex(ValueError, "disk reserve"): local.preflight()
            capture.assert_not_called()
