#!/usr/bin/env python3
"""Guard Comment's thin-library/executable-service artifact split."""

from __future__ import annotations

import unittest
import xml.etree.ElementTree as element_tree
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MAVEN_NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}
EXECUTABLE = "dc-comment/target/dc-comment-0.1.0-SNAPSHOT-exec.jar"
ORDINARY = "dc-comment/target/dc-comment-0.1.0-SNAPSHOT.jar"


class CommentArtifactContractTest(unittest.TestCase):
    def test_comment_publishes_thin_and_executable_artifacts(self) -> None:
        comment_pom = element_tree.parse(ROOT / "dc-comment/pom.xml")
        classifiers = comment_pom.findall(
            ".//m:plugin[m:artifactId='spring-boot-maven-plugin']/m:configuration/m:classifier",
            MAVEN_NAMESPACE,
        )
        self.assertEqual(["exec"], [classifier.text for classifier in classifiers])

    def test_standalone_compose_builds_use_the_executable_classifier(self) -> None:
        for relative_path in ("docker-compose.yml", "docker-compose.e2e.yml"):
            source = (ROOT / relative_path).read_text(encoding="utf-8")
            with self.subTest(compose=relative_path):
                self.assertEqual(1, source.count(f"JAR_FILE: {EXECUTABLE}"))
                self.assertNotIn(f"JAR_FILE: {ORDINARY}", source)

    def test_ci_resolves_the_production_comment_image_to_the_exec_jar(self) -> None:
        source = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        self.assertIn(
            "module: dc-comment, image: dc-comment, release_class: production",
            source,
        )
        classifier_branch = next(
            line for line in source.splitlines()
            if "elif [[" in line and '"dc-user"' in line
        )
        self.assertIn('"dc-comment"', classifier_branch)
        self.assertIn(
            'path="${{ matrix.module }}/target/${{ matrix.module }}-0.1.0-SNAPSHOT-exec.jar"',
            source,
        )


if __name__ == "__main__":
    unittest.main()
