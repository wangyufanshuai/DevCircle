#!/usr/bin/env python3
"""Reject mutable third-party GitHub Action references in workflow files."""

from __future__ import annotations

import re
import sys
from pathlib import Path


USES = re.compile(r"^\s*(?:-\s*)?uses:\s*([^\s#]+)")
FULL_COMMIT = re.compile(r"^[0-9a-f]{40}$")


def violations(workflows: Path) -> list[str]:
    failures: list[str] = []
    for path in sorted((*workflows.glob("*.yml"), *workflows.glob("*.yaml"))):
        for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            match = USES.match(line)
            if not match:
                continue
            reference = match.group(1)
            if reference.startswith("./") or reference.startswith("docker://"):
                continue
            action, separator, revision = reference.rpartition("@")
            if not separator or not action or not FULL_COMMIT.fullmatch(revision):
                failures.append(f"{path}:{line_number}: {reference}")
    return failures


def main(argv: list[str]) -> int:
    root = Path(argv[1]) if len(argv) > 1 else Path(".github/workflows")
    failures = violations(root)
    if failures:
        print("Mutable or invalid GitHub Action references found:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1
    print(f"GitHub Actions references are pinned to full commit SHAs: {root}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
