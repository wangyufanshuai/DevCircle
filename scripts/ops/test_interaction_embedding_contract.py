#!/usr/bin/env python3
"""Guard the thin-library/executable-service split during Interaction embedding."""

from __future__ import annotations

import unittest
import xml.etree.ElementTree as element_tree
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MAVEN_NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}
EXECUTABLE = "dc-interaction/target/dc-interaction-0.1.0-SNAPSHOT-exec.jar"


class InteractionEmbeddingContractTest(unittest.TestCase):
    def test_interaction_publishes_thin_and_executable_artifacts(self) -> None:
        interaction_pom = element_tree.parse(ROOT / "dc-interaction/pom.xml")
        classifiers = interaction_pom.findall(
            ".//m:plugin[m:artifactId='spring-boot-maven-plugin']/m:configuration/m:classifier",
            MAVEN_NAMESPACE,
        )
        self.assertEqual(["exec"], [classifier.text for classifier in classifiers])

        identity_pom = element_tree.parse(ROOT / "dc-identity/pom.xml")
        interaction_dependencies = identity_pom.findall(
            ".//m:dependency[m:artifactId='dc-interaction']", MAVEN_NAMESPACE
        )
        self.assertEqual(1, len(interaction_dependencies))
        self.assertIsNone(interaction_dependencies[0].find("m:classifier", MAVEN_NAMESPACE))
        self.assertIsNone(interaction_dependencies[0].find("m:systemPath", MAVEN_NAMESPACE))

    def test_standalone_compose_builds_use_the_executable_classifier(self) -> None:
        for relative_path in ("docker-compose.yml", "docker-compose.e2e.yml"):
            source = (ROOT / relative_path).read_text(encoding="utf-8")
            with self.subTest(compose=relative_path):
                self.assertEqual(1, source.count(f"JAR_FILE: {EXECUTABLE}"))
                self.assertNotIn(
                    "JAR_FILE: dc-interaction/target/dc-interaction-0.1.0-SNAPSHOT.jar",
                    source,
                )

    def test_ci_resolves_the_standalone_interaction_image_to_the_exec_jar(self) -> None:
        source = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        self.assertIn(
            "module: dc-interaction, image: dc-interaction, release_class: production",
            source,
        )
        classifier_branch = next(
            line for line in source.splitlines()
            if "elif [[" in line and '"dc-user"' in line
        )
        self.assertIn('"dc-interaction"', classifier_branch)
        self.assertIn(
            'path="${{ matrix.module }}/target/${{ matrix.module }}-0.1.0-SNAPSHOT-exec.jar"',
            source,
        )

    def test_production_routes_remain_on_the_standalone_runtime(self) -> None:
        compose = (ROOT / "docker-compose.prod.yml").read_text(encoding="utf-8")
        helm_values = (ROOT / "deploy/helm/devcircle/values.yaml").read_text(encoding="utf-8")
        helm_prod = (ROOT / "deploy/helm/devcircle/values-prod.yaml").read_text(encoding="utf-8")

        self.assertIn("  dc-interaction:\n", compose)
        self.assertGreaterEqual(
            compose.count("DC_INTERACTION_URI: http://dc-interaction:8105"), 3
        )
        self.assertIn('INTERACTION_FLYWAY_ENABLED: "false"', compose)
        self.assertIn('INTERACTION_RUNTIME_OUTBOX_ENABLED: "false"', compose)
        self.assertIn("  interaction: { port: 8105 }", helm_values)
        self.assertIn("DC_INTERACTION_URI: http://dc-interaction:8105", helm_values)
        self.assertIn('INTERACTION_FLYWAY_ENABLED: "false"', helm_prod)
        self.assertIn('INTERACTION_RUNTIME_OUTBOX_ENABLED: "false"', helm_prod)


if __name__ == "__main__":
    unittest.main()
