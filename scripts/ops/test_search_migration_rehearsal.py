import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("rehearsal", Path(__file__).with_name("rehearse_search_migration.py"))
rehearsal = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(rehearsal)


class SearchMigrationRehearsalTest(unittest.TestCase):
    def test_equal_counts_with_wrong_ids_or_versions_are_rejected(self):
        rows = [{"id": 1, "title": "one", "aggregateVersion": 5, "excerpt": "body"}]
        doc = dict(rows[0], status="PUBLISHED")
        rehearsal.compare_projection(rows, [doc])
        for changed in (dict(doc, id=2), dict(doc, aggregateVersion=4), dict(doc, title="stale"), dict(doc, excerpt="stale")):
            with self.assertRaises(AssertionError):
                rehearsal.compare_projection(rows, [changed])

    def test_only_valid_versioned_tombstones_are_allowed(self):
        rehearsal.compare_projection([], [{"id": 1, "status": "DELETED", "aggregateVersion": 6}])
        for version in (None, -1, True, "6"):
            with self.assertRaises(AssertionError):
                rehearsal.compare_projection([], [{"id": 1, "status": "DELETED", "aggregateVersion": version}])

    def test_corrupt_or_escaping_backup_is_rejected_before_restore(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "mysql.sql.gz").write_bytes(b"backup")
            manifest = {"sha256": {"mysql.sql.gz": hashlib.sha256(b"backup").hexdigest()}}
            rehearsal.verify_files(root, manifest)
            (root / "mysql.sql.gz").write_bytes(b"corrupted")
            with self.assertRaisesRegex(ValueError, "checksum"):
                rehearsal.verify_files(root, manifest)
            with self.assertRaisesRegex(ValueError, "escapes"):
                rehearsal.verify_files(root, {"sha256": {"../outside": "irrelevant"}})

    def test_c_drive_is_rejected_even_on_linux_ci(self):
        for path in ("C:/backup", "c:\\backup", "C:relative"):
            with self.assertRaisesRegex(ValueError, "C:"):
                rehearsal.non_system_path(Path(path))


if __name__ == "__main__":
    unittest.main()
