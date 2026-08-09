from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from scripts.ops.assert_migration_versions import validate


class MigrationVersionGateTest(unittest.TestCase):
    def test_accepts_unique_versions_per_module(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write(root, "dc-content", "V1__schema.sql")
            self.write(root, "dc-content", "V2_1__reader_state.sql")
            self.write(root, "dc-auth", "V1__schema.sql")

            self.assertEqual([], validate(root))

    def test_rejects_flyway_equivalent_duplicate_versions(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write(root, "dc-content", "V19__projection.sql")
            self.write(root, "dc-content", "V19_0__consumer_lease.sql")

            failures = validate(root)

            self.assertEqual(1, len(failures))
            self.assertIn("dc-content: duplicate Flyway version 19", failures[0])
            self.assertIn("V19__projection.sql", failures[0])
            self.assertIn("V19_0__consumer_lease.sql", failures[0])

    def test_rejects_malformed_versioned_filename(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write(root, "dc-content", "Vnext__schema.sql")

            failures = validate(root)

            self.assertEqual(1, len(failures))
            self.assertIn("expected V<version>__<description>.sql", failures[0])

    def test_validates_non_default_flyway_locations(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            migration_dir = root / "dc-file" / "src/main/resources/db/file-migration"
            migration_dir.mkdir(parents=True, exist_ok=True)
            (migration_dir / "V1__schema.sql").write_text("SELECT 1;\n", encoding="utf-8")
            (migration_dir / "V1_0__duplicate.sql").write_text("SELECT 1;\n", encoding="utf-8")

            failures = validate(root)

            self.assertEqual(1, len(failures))
            self.assertIn("dc-file/file-migration: duplicate Flyway version 1", failures[0])

    def write(self, root: Path, module: str, filename: str) -> None:
        migration_dir = root / module / "src/main/resources/db/migration"
        migration_dir.mkdir(parents=True, exist_ok=True)
        (migration_dir / filename).write_text("SELECT 1;\n", encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
