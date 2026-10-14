import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

from scripts.ops.assert_reactor_archive import verify_archive


class ReactorArchiveTest(unittest.TestCase):
    def test_current_dependency_is_accepted_and_stale_packaged_bytes_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            dependency = root / "dc-search/target/dc-search-0.1.0-SNAPSHOT.jar"
            dependency.parent.mkdir(parents=True)
            dependency.write_bytes(b"old dependency")
            archive = root / "discovery.jar"
            with ZipFile(archive, "w") as jar:
                jar.writestr("BOOT-INF/lib/" + dependency.name, dependency.read_bytes())
            self.assertEqual(1, len(verify_archive(archive, root)))
            dependency.write_bytes(b"new dependency, unchanged discovery classes")
            with self.assertRaisesRegex(AssertionError, "Stale nested dependency"):
                verify_archive(archive, root)

    def test_missing_dependency_and_non_executable_archive_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name, message in (("BOOT-INF/lib/dc-search-0.1.0-SNAPSHOT.jar", "exactly one"),
                                  ("application.class", "No reactor dependencies")):
                with self.subTest(name=name):
                    archive = root / "discovery.jar"
                    with ZipFile(archive, "w") as jar:
                        jar.writestr(name, b"fixture")
                    with self.assertRaisesRegex(AssertionError, message):
                        verify_archive(archive, root)


if __name__ == "__main__":
    unittest.main()
