#!/usr/bin/env python3
"""Reject duplicate or malformed versioned Flyway migrations before startup."""

from __future__ import annotations

import argparse
import re
import sys
from collections import defaultdict
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MIGRATION_NAME = re.compile(
    r"^V(?P<version>[0-9]+(?:[._][0-9]+)*)__.+\.sql$",
    re.IGNORECASE,
)


def canonical_version(raw: str) -> tuple[int, ...]:
    parts = tuple(int(part) for part in re.split(r"[._]", raw))
    while len(parts) > 1 and parts[-1] == 0:
        parts = parts[:-1]
    return parts


def validate(root: Path = ROOT) -> list[str]:
    failures: list[str] = []
    migrations_by_location: dict[tuple[str, str], dict[tuple[int, ...], list[Path]]] = defaultdict(
        lambda: defaultdict(list)
    )

    for db_dir in sorted(root.glob("dc-*/src/main/resources/db")):
        if not db_dir.is_dir():
            continue
        module = db_dir.relative_to(root).parts[0]
        for migration_dir in sorted(path for path in db_dir.iterdir() if path.is_dir()):
            location = migration_dir.relative_to(db_dir).as_posix()
            location_key = (module, location)
            for migration in sorted(migration_dir.glob("V*.sql")):
                match = MIGRATION_NAME.fullmatch(migration.name)
                if match is None:
                    failures.append(
                        f"{migration.relative_to(root)}: expected V<version>__<description>.sql"
                    )
                    continue
                migrations_by_location[location_key][canonical_version(match.group("version"))].append(
                    migration.relative_to(root)
                )

    for (module, location), versions in sorted(migrations_by_location.items()):
        for version, migrations in sorted(versions.items()):
            if len(migrations) < 2:
                continue
            label = ".".join(str(part) for part in version)
            joined = ", ".join(str(path) for path in migrations)
            scope = module if location == "migration" else f"{module}/{location}"
            failures.append(f"{scope}: duplicate Flyway version {label}: {joined}")

    return failures


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=ROOT)
    args = parser.parse_args(argv)
    root = args.root.resolve()
    failures = validate(root)
    if failures:
        print("Flyway migration version gate failed:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1
    print(f"Flyway migration versions validated under {root}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
