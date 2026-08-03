#!/usr/bin/env python3
"""Enforce branch coverage for the event/state-machine classes that gate releases."""

from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CRITICAL_CLASSES = (
    ("dc-auth", "io/github/devcircle/auth/application/AuthService"),
    ("dc-auth", "io/github/devcircle/auth/application/GitHubOAuthService"),
    ("dc-common", "io/github/devcircle/common/event/ReliableOutboxWorker"),
    ("dc-content", "io/github/devcircle/content/application/DeductionService"),
    ("dc-content", "io/github/devcircle/content/application/PostService"),
    ("dc-content", "io/github/devcircle/content/infrastructure/ContentCounterProjectionCoordinator"),
    ("dc-content", "io/github/devcircle/content/infrastructure/ContentCommentProjectionRepository"),
    ("dc-content", "io/github/devcircle/content/infrastructure/ContentInteractionProjectionRepository"),
    ("dc-notification", "io/github/devcircle/notification/application/NotificationEventConsumer"),
    ("dc-recommend", "io/github/devcircle/recommend/application/JdbcInteractionConsumerDedup"),
    ("dc-recommend", "io/github/devcircle/recommend/application/InteractionChangedConsumer"),
    ("dc-recommend", "io/github/devcircle/recommend/application/FollowChangedConsumer"),
    ("dc-search", "io/github/devcircle/search/application/SearchEventCoordinator"),
    ("dc-user", "io/github/devcircle/user/application/UserRegisteredConsumer"),
)


def class_branch_coverage(report: Path, class_name: str) -> float:
    root = ET.parse(report).getroot()
    for clazz in root.findall(".//class"):
        if clazz.attrib.get("name") != class_name:
            continue
        counter = next(
            (node for node in clazz.findall("./counter") if node.attrib.get("type") == "BRANCH"),
            None,
        )
        if counter is None:
            return 100.0
        covered = int(counter.attrib["covered"])
        missed = int(counter.attrib["missed"])
        total = covered + missed
        return 100.0 if total == 0 else covered * 100.0 / total
    raise ValueError(f"missing class {class_name} in {report}")


def validate(root: Path = ROOT, minimum: float = 85.0) -> list[str]:
    failures: list[str] = []
    for module, class_name in CRITICAL_CLASSES:
        report = root / module / "target" / "site" / "jacoco" / "jacoco.xml"
        if not report.is_file():
            failures.append(f"{module}: missing JaCoCo report {report}")
            continue
        try:
            actual = class_branch_coverage(report, class_name)
        except (ET.ParseError, KeyError, ValueError) as error:
            failures.append(str(error))
            continue
        label = class_name.rsplit("/", 1)[-1]
        print(f"{module}/{label}: branch={actual:.2f}% minimum={minimum:.2f}%")
        if actual + 1e-9 < minimum:
            failures.append(f"{module}/{label}: branch {actual:.2f}% < {minimum:.2f}%")
    return failures


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--minimum", type=float, default=85.0)
    args = parser.parse_args(argv)
    if args.minimum < 0 or args.minimum > 100:
        parser.error("--minimum must be between 0 and 100")
    failures = validate(args.root.resolve(), args.minimum)
    if failures:
        print("Critical state coverage gate failed:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1
    print("Critical state coverage gate passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
