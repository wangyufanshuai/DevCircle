#!/usr/bin/env python3
"""Reject Boot archives embedding stale project dependencies from incremental builds."""
from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import zipfile


ROOT = Path(__file__).resolve().parents[2]


def verify_archive(archive: Path, root: Path = ROOT) -> dict[str, str]:
    verified = {}
    with zipfile.ZipFile(archive) as jar:
        for name in jar.namelist():
            if not name.startswith("BOOT-INF/lib/dc-") or not name.endswith(".jar"):
                continue
            filename = name.removeprefix("BOOT-INF/lib/")
            matches = list(root.glob("dc-*/target/" + filename))
            if len(matches) != 1:
                raise AssertionError(f"Expected exactly one reactor artifact for {filename}")
            actual = hashlib.sha256(jar.read(name)).hexdigest()
            expected = hashlib.sha256(matches[0].read_bytes()).hexdigest()
            if actual != expected:
                raise AssertionError(f"Stale nested dependency {filename}: packaged={actual}, reactor={expected}")
            verified[filename] = actual
    if not verified:
        raise AssertionError("No reactor dependencies found in the executable archive")
    return verified


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    args = parser.parse_args()
    print(f"PASS {len(verify_archive(args.archive))} current reactor dependencies in {args.archive}")
