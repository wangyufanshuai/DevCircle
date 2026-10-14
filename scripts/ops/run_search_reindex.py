#!/usr/bin/env python3
"""Drive one durable reindex job. Only COMPLETED is success; tokens are never written to receipts."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path, PureWindowsPath
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, build_opener, HTTPRedirectHandler
from uuid import UUID

ROOT = Path(__file__).resolve().parents[2]
TERMINAL = {"COMPLETED", "FAILED", "CANCELLED"}


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Api:
    def __init__(self, base_url: str, token: str):
        parsed = urlsplit(base_url)
        if (parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password
                or parsed.query or parsed.fragment or parsed.path not in {"", "/"}):
            raise ValueError("Supply a gateway origin without credentials, path, query or fragment")
        if parsed.scheme == "http" and parsed.hostname not in {"127.0.0.1", "localhost", "::1"}:
            raise ValueError("HTTP bearer authentication is restricted to loopback; use HTTPS elsewhere")
        if not token or "\n" in token or "\r" in token:
            raise ValueError("Set DEVCIRCLE_ADMIN_TOKEN to a valid admin access token")
        self.base = base_url.rstrip("/")
        self.token = token
        self.opener = build_opener(NoRedirect())

    def request(self, method: str, path: str) -> dict:
        request = Request(self.base + path, method=method,
                          headers={"Authorization": "Bearer " + self.token, "Accept": "application/json"})
        try:
            with self.opener.open(request, timeout=75) as response:
                payload = json.load(response)
        except HTTPError as error:
            # Do not print potentially sensitive server response bodies or request headers.
            raise RuntimeError(f"Gateway returned HTTP {error.code}; rerun with the same job ID after resolving it") from None
        except (URLError, TimeoutError, OSError):
            raise RuntimeError("Request outcome unknown; rerun with the same job ID to read the persisted checkpoint") from None
        if payload.get("code") != "OK" or not isinstance(payload.get("data"), dict):
            raise RuntimeError("Gateway did not return a valid job envelope")
        return payload["data"]


def output_directory(root: Path, job_id: UUID) -> Path:
    if PureWindowsPath(str(root)).drive.lower() == "c:":
        raise ValueError("Reindex receipts must not be written to C:")
    destination = (root / str(job_id)).resolve()
    if destination.drive.lower() == "c:":
        raise ValueError("Resolved receipt location must not be on C:")
    destination.mkdir(parents=True, exist_ok=True)
    return destination


def drive(api: Api, job_id: UUID, output: Path, *, create: bool, max_steps: int = 0) -> dict:
    if max_steps < 0:
        raise ValueError("max_steps cannot be negative")
    endpoint = f"/api/search/admin/reindex/jobs/{job_id}"
    job = api.request("PUT", endpoint + "?maintenanceConfirmed=true") if create else api.request("GET", endpoint)
    steps = 0
    previous_revision = -1
    while True:
        if job.get("jobId") != str(job_id) or job.get("phase") not in TERMINAL | {"COPYING", "VERIFYING"}:
            raise RuntimeError("Invalid job identity or phase")
        revision = job.get("revision")
        if not isinstance(revision, int) or isinstance(revision, bool) or revision < 0:
            raise RuntimeError("Invalid checkpoint revision")
        if steps and revision <= previous_revision:
            raise RuntimeError("Batch response did not advance the checkpoint; query the job before retrying")
        # Save only the defined, non-secret job fields. Atomic replacement preserves a valid receipt on interruption.
        receipt = {key: job.get(key) for key in ("jobId", "phase", "cursor", "copied", "verified", "copyHash", "verifyHash", "revision", "lastError")}
        temporary = output / "status.tmp"
        temporary.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.replace(output / "status.json")
        if job["phase"] in TERMINAL or max_steps and steps >= max_steps:
            return receipt
        previous_revision = revision
        job = api.request("POST", endpoint + f"/step?revision={revision}")
        steps += 1


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--job-id", required=True, type=UUID, help="Keep this UUID for every retry/resume")
    parser.add_argument("--create-confirm-maintenance", action="store_true", help="Create only after writers and search consumers are stopped")
    parser.add_argument("--max-steps", type=int, default=0, help="Stop after this many batches, leaving the durable job resumable; 0 runs to terminal state")
    parser.add_argument("--output-root", type=Path, default=ROOT / "artifacts/local/search-reindex")
    args = parser.parse_args(argv)
    try:
        api = Api(args.base_url, os.environ.get("DEVCIRCLE_ADMIN_TOKEN", ""))
        directory = output_directory(args.output_root, args.job_id)
        result = drive(api, args.job_id, directory, create=args.create_confirm_maintenance, max_steps=args.max_steps)
        print(json.dumps(result, ensure_ascii=False))
        return 0 if result["phase"] == "COMPLETED" else 2 if result["phase"] in TERMINAL else 3
    except (ValueError, RuntimeError) as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
