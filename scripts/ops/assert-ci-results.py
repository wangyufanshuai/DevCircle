#!/usr/bin/env python3
"""Fail the aggregate CI check if any required verification did not succeed."""
import json
import os
from pathlib import Path

REQUIRED = {
    "backend", "frontend", "real-auth-e2e", "real-content-e2e",
    "real-review-e2e", "real-community-e2e", "delivery", "source-sbom", "test-storage",
}


def validate(results):
    missing = REQUIRED - results.keys()
    failed = {name: results[name].get("result") for name in REQUIRED & results.keys()
              if results[name].get("result") != "success"}
    if missing or failed:
        raise ValueError(f"CI verification incomplete: missing={sorted(missing)}, unsuccessful={failed}")


if __name__ == "__main__":
    results = json.loads(os.environ["CI_RESULTS"])
    summary = "## Verification evidence\n\n"
    summary += f"Commit: `{os.environ['GITHUB_SHA']}`\n\n"
    summary += f"Event: `{os.environ['GITHUB_EVENT_NAME']}`; ref: `{os.environ['GITHUB_REF']}`\n\n"
    summary += "| Job | Result |\n|---|---|\n"
    summary += "".join(f"| {name} | {results.get(name, {}).get('result', 'missing')} |\n"
                       for name in sorted(REQUIRED))
    with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a", encoding="utf-8") as report:
        report.write(summary)
    validate(results)
    print("All required verification jobs succeeded; this is not production acceptance.")
