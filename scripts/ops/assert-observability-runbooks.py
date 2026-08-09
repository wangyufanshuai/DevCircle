#!/usr/bin/env python3
"""Ensure every Prometheus alert points to a real local runbook section."""

from __future__ import annotations

import re
import sys
from pathlib import Path


RUNBOOK = re.compile(r"runbook_url:\s*[\"']?[^\s\"']+#([a-z0-9-]+)")
HEADING = re.compile(r"^#{2,6}\s+([a-z0-9-]+)\s*$", re.MULTILINE)


def validate(rule_files: list[Path], runbook: Path) -> list[str]:
    headings = set(HEADING.findall(runbook.read_text(encoding="utf-8")))
    failures: list[str] = []
    alert_count = 0
    for path in rule_files:
        text = path.read_text(encoding="utf-8")
        alerts = len(re.findall(r"^\s*- alert:\s*\S+", text, re.MULTILINE))
        anchors = RUNBOOK.findall(text)
        alert_count += alerts
        if len(anchors) != alerts:
            failures.append(f"{path}: alerts={alerts} runbook_urls={len(anchors)}")
        for anchor in anchors:
            if anchor not in headings:
                failures.append(f"{path}: missing runbook heading #{anchor}")
    if alert_count == 0:
        failures.append("no Prometheus alerts were found")
    return failures


def main() -> int:
    failures = validate(
        [Path("deploy/prometheus/alerts.yml"), Path("deploy/prometheus/slo.yml")],
        Path("docs/observability-runbook.md"),
    )
    if failures:
        print("Observability runbook contract failed:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1
    print("Every Prometheus alert is linked to an existing runbook section")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
