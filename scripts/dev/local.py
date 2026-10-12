#!/usr/bin/env python3
"""Bounded local workflows. Only the dedicated core Compose project is managed."""
import argparse
import ctypes
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import threading
import time

ROOT = Path(__file__).resolve().parents[2]
PROJECT = "devcircle-local-core"
IMAGE = "devcircle-minio-test:07c3a429bfed433e49018cb0f78a52145d4bedeb"
COMPOSE = ["docker", "compose", "-p", PROJECT, "-f", "docker-compose.e2e.yml",
           "-f", "deploy/compose/docker-compose.local.yml", "--profile", "review"]
ENV = {**os.environ, "REAL_E2E_ENABLE_OUTBOX": "true", "DEVCIRCLE_TEST_MINIO_IMAGE": IMAGE}
ENV.pop("COMPOSE_PROFILES", None)
MAVEN = [str(ROOT / ("mvnw.cmd" if os.name == "nt" else "mvnw"))]
NPM = shutil.which("npm.cmd" if os.name == "nt" else "npm") or "npm"


def capture(args, env=None):
    return subprocess.run(args, cwd=ROOT, env=env or ENV, text=True, encoding="utf-8",
                          errors="replace", capture_output=True, check=True, timeout=30).stdout.strip()


def run(args, log, cwd=ROOT, env=None):
    print(f"Running {Path(str(args[0])).name}; log: {log}", flush=True)
    with log.open("w", encoding="utf-8") as stream:
        subprocess.run(args, cwd=cwd, env=env or ENV, stdout=stream, stderr=subprocess.STDOUT, check=True)


def source_identity():
    files = capture(["git", "ls-files", "-co", "--exclude-standard"]).splitlines()
    digest = hashlib.sha256()
    for name in sorted(set(files)):
        path = ROOT / name
        if path.is_file():
            digest.update(name.encode()); digest.update(path.read_bytes())
    return {"sha": capture(["git", "rev-parse", "HEAD"]),
            "dirty": bool(capture(["git", "status", "--porcelain"])),
            "source_sha256": digest.hexdigest()}


def inspect_core():
    ids = capture(COMPOSE + ["ps", "-aq"]).splitlines()
    return json.loads(capture(["docker", "inspect", *ids])) if ids else []


def resource_sample():
    states = inspect_core()
    ids = [c["Id"] for c in states if c["State"]["Running"]]
    stats = capture(["docker", "stats", "--no-stream", "--format", "{{json .}}", *ids]) if ids else ""
    return {"at": datetime.now(timezone.utc).isoformat(), "host_available_bytes": host_available(),
            "stats": [json.loads(line) for line in stats.splitlines() if line],
            "states": [{"name": c["Name"], "status": c["State"]["Status"],
                        "oom_killed": c["State"].get("OOMKilled", False),
                        "restart_count": c["RestartCount"], "exit_code": c["State"]["ExitCode"],
                        "health": c["State"].get("Health", {}).get("Status"),
                        "image_id": c["Image"]} for c in states]}


def mib(value):
    number, unit = value.strip().split(" ")[0], ""
    for unit in ("GiB", "MiB", "KiB", "B"):
        if number.endswith(unit):
            return float(number[:-len(unit)]) * {"GiB": 1024, "MiB": 1, "KiB": 1/1024, "B": 1/1024**2}[unit]
    raise ValueError(f"Unknown memory unit: {value}")


def summarize(samples, expected):
    if not samples or not samples[-1]["states"]:
        raise ValueError("No running-container evidence was collected")
    final = samples[-1]["states"]
    if len(final) != expected or any(s["status"] != "running" or s["health"] != "healthy" for s in final):
        raise ValueError("Core slice is incomplete or unhealthy")
    if len(samples[-1]["stats"]) != expected:
        raise ValueError("Final resource sample is incomplete")
    if any(s["oom_killed"] or s["restart_count"] or (s["status"] == "exited" and s["exit_code"] != 0)
           for sample in samples for s in sample["states"]):
        raise ValueError("OOM, restart or abnormal exit observed")
    totals = [sum(mib(s["MemUsage"].split("/")[0]) for s in sample["stats"]) for sample in samples]
    return {"samples": len(samples), "peak_sampled_total_mib": round(max(totals), 2),
            "min_sampled_host_available_bytes": min((s["host_available_bytes"] for s in samples if "host_available_bytes" in s), default=None),
            "oom_killed": False, "unexpected_restarts": 0,
            "scope": "sampled core containers; excludes host/WSL/build memory and is not a long-duration capacity claim"}


def host_available():
    if os.name == "nt":
        class MemoryStatus(ctypes.Structure):
            _fields_ = [("length", ctypes.c_ulong), ("load", ctypes.c_ulong)] + [
                (name, ctypes.c_ulonglong) for name in ("total", "available", "page_total", "page_available", "virtual_total", "virtual_available", "extended")]
        status = MemoryStatus(); status.length = ctypes.sizeof(status)
        if not ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status)):
            raise OSError("Cannot read host memory")
        return status.available
    for line in Path("/proc/meminfo").read_text().splitlines():
        if line.startswith("MemAvailable:"): return int(line.split()[1]) * 1024
    raise OSError("Cannot read host memory")


def disk_preflight():
    paths = {str(ROOT.anchor): 4 * 1024**3}
    if os.name == "nt":
        system_drive = str(Path(os.environ.get("SystemDrive", "C:") + "/"))
        paths[system_drive] = max(paths.get(system_drive, 0), 2 * 1024**3)
    free = {path: shutil.disk_usage(path).free for path in paths}
    for path, minimum in paths.items():
        if free[path] < minimum:
            raise ValueError(f"Insufficient disk reserve on {path}: {free[path] // 1024**2} MiB free; requires {minimum // 1024**2} MiB")
    return free


def preflight():
    disks = disk_preflight()
    info = json.loads(capture(["docker", "info", "--format", "{{json .}}"])); config = json.loads(capture(COMPOSE + ["config", "--format", "json"]))
    # Compose config lists disabled profile services too. `--services` selects the active graph.
    names = set(capture(COMPOSE + ["config", "--services"]).splitlines())
    services = {k: v for k, v in config["services"].items() if k in names}
    ceiling = sum(int(v["mem_limit"]) for v in services.values())
    available = host_available()
    if available < 1024**3:
        raise ValueError("Less than 1 GiB host memory available; stop other development workloads first")
    if ceiling > 4 * 1024**3 or info["MemTotal"] < ceiling + 512 * 1024**2:
        raise ValueError("Insufficient Docker memory for this bounded slice; do not start the full stack")
    for service in services.values():
        for port in service.get("ports", []):
            if port.get("host_ip") != "127.0.0.1":
                raise ValueError("Local ports must only bind to 127.0.0.1")
    return {"docker_version": info["ServerVersion"], "docker_memory_bytes": info["MemTotal"], "host_available_bytes": available, "host_disk_free_bytes": disks,
            "services": sorted(services), "declared_memory_bytes": ceiling}


def quick(out):
    disk_preflight()
    run([sys.executable, "-m", "unittest", "discover", "-s", "scripts/ops", "-p", "test_*.py"], out / "ops.log")
    tests = "DeductionServiceStateTest,SearchEventCoordinatorTest,ReliableOutboxWorkerTest,FileControllerTest"
    run(MAVEN + ["-B", "test", f"-Dtest={tests}", "-Dsurefire.failIfNoSpecifiedTests=false"], out / "quick-java.log")
    if not (ROOT / "web/node_modules").exists():
        run([NPM, "ci"], out / "npm-ci.log", ROOT / "web")
    run([NPM, "run", "build"], out / "web-build.log", ROOT / "web")


def build(out):
    if inspect_core():
        run(COMPOSE + ["down"], out / "stop-before-build.log")  # avoid overlapping JVM/build memory; keep volumes
    run(MAVEN + ["-B", "-pl", "dc-identity,dc-content-deployment,dc-admin,dc-gateway", "-am", "-DskipTests", "package"], out / "package.log")
    if not (ROOT / "web/node_modules").exists():
        run([NPM, "ci"], out / "npm-ci.log", ROOT / "web")
    run([NPM, "run", "build"], out / "web-build.log", ROOT / "web",
        {**ENV, "VITE_NOTIFICATION_SOCKET_ENABLED": "true"})
    if subprocess.run(["docker", "image", "inspect", IMAGE], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
        run(["docker", "build", "-f", "deploy/ci/Dockerfile.minio", "-t", IMAGE, "deploy/ci"], out / "minio-build.log")
    run(COMPOSE + ["build"], out / "images.log")
    preflight()  # builds can change host memory availability


def verify(out, report, cycles, hold):
    from core_checks import check_core, assert_persisted
    build(out)
    previous = None
    report["cycles"] = []
    for cycle in range(cycles):
        started = int(time.time()); samples = []; errors = []; stop = threading.Event()
        def monitor():
            while not stop.is_set():
                try:
                    samples.append(resource_sample())
                except Exception as error:
                    errors.append(type(error).__name__)
                stop.wait(3)
        thread = threading.Thread(target=monitor, daemon=True); thread.start()
        try:
            run(COMPOSE + ["up", "-d", "--no-build", "--wait", "--wait-timeout", "240"], out / f"up-{cycle}.log")
            if previous:
                assert_persisted(previous, sql)
            previous = check_core(sql)
            time.sleep(hold)
        finally:
            stop.set(); thread.join(timeout=30)
            (out / f"resources-{cycle}.json").write_text(json.dumps(samples, indent=2), encoding="utf-8")
        if thread.is_alive() or errors:
            raise ValueError(f"Resource collection incomplete: {errors}")
        samples.append(resource_sample())
        (out / f"resources-{cycle}.json").write_text(json.dumps(samples, indent=2), encoding="utf-8")
        events = capture(["docker", "events", "--since", str(started), "--until", str(int(time.time()) + 1),
                          "--filter", f"label=com.docker.compose.project={PROJECT}", "--filter", "event=oom",
                          "--format", "{{json .}}"])
        (out / f"oom-events-{cycle}.jsonl").write_text(events, encoding="utf-8")
        if events.strip():
            raise ValueError("Docker reported an OOM event during the cycle")
        report["cycles"].append({"checks": previous, "resources": summarize(samples, len(report["environment"]["services"]))})
        if cycle + 1 < cycles:
            run(COMPOSE + ["down"], out / f"down-{cycle}.log")  # preserve volumes


def sql(query):
    return capture(COMPOSE + ["exec", "-T", "mysql", "mysql", "-uroot", "-pe2e-root-password",
                              "--batch", "--skip-column-names", "-e", query])


def accept(out):
    if inspect_core():
        raise ValueError("Stop the core slice with 'down' before full acceptance to free resources")
    run([sys.executable, "-m", "unittest", "discover", "-s", "scripts/ops", "-p", "test_*.py"], out / "ops.log")
    if subprocess.run(["docker", "image", "inspect", IMAGE], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
        run(["docker", "build", "-f", "deploy/ci/Dockerfile.minio", "-t", IMAGE, "deploy/ci"], out / "minio-build.log")
    run(MAVEN + ["-B", "clean", "verify", "-Pcoverage"], out / "full-java.log")
    bash = shutil.which("bash")
    if os.name == "nt":
        git_dir = Path(shutil.which("git")).resolve().parent
        bash = next((str(p) for p in (git_dir / "bash.exe", git_dir.parent / "bin/bash.exe") if p.exists()), None)
    if not bash: raise ValueError("Bash is required for complete acceptance")
    for script in ("assert-coverage.sh", "assert-no-skipped-tests.sh"):
        run([bash, f"scripts/ops/{script}"], out / f"{script}.log",
            env={**ENV, "MIN_LINE_COVERAGE": "75", "MIN_BRANCH_COVERAGE": "70"})
    run([sys.executable, "scripts/ops/assert-critical-state-coverage.py"], out / "critical-coverage.log")
    run([NPM, "ci", "--registry=https://registry.npmjs.org"], out / "npm-ci.log", ROOT / "web")
    run([NPM, "audit", "--audit-level=high", "--registry=https://registry.npmjs.org"], out / "audit.log", ROOT / "web")
    run([NPM, "run", "build"], out / "web-build.log", ROOT / "web")
    run([NPM, "run", "test:e2e"], out / "browser.log", ROOT / "web")
    for suite in ("auth", "content", "review", "community"):
        run([bash, f"scripts/e2e/run-real-{suite}.sh"], out / f"real-{suite}.log")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["quick", "up", "down", "status", "verify", "accept"])
    parser.add_argument("--cycles", type=int, default=2)
    parser.add_argument("--hold-seconds", type=int, default=30)
    args = parser.parse_args()
    if not 2 <= args.cycles <= 5 or not 10 <= args.hold_seconds <= 300:
        parser.error("cycles must be 2..5 and hold-seconds 10..300")
    out = ROOT / "artifacts/local" / (datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%f") + "-" + args.mode)
    out.mkdir(parents=True)
    report = {"mode": args.mode, "source": source_identity(), "project": PROJECT, "status": "running",
              "started_at": datetime.now(timezone.utc).isoformat()}
    try:
        if args.mode == "quick": quick(out)
        elif args.mode == "down": run(COMPOSE + ["down"], out / "down.log")
        elif args.mode == "status": report["resources"] = resource_sample()
        else:
            report["environment"] = preflight()
            if args.mode == "up":
                build(out); run(COMPOSE + ["up", "-d", "--no-build", "--wait", "--wait-timeout", "240"], out / "up.log")
                from core_checks import check_core
                report["checks"] = check_core(sql)
                report["resources"] = resource_sample()
            elif args.mode == "verify": verify(out, report, args.cycles, args.hold_seconds)
            elif args.mode == "accept": accept(out)
        report["status"] = "passed"
        if args.mode in ("up", "verify"): print("Core UI: http://127.0.0.1:5512 (local test accounts and data only)")
    except Exception as error:
        report["status"] = "failed"; report["error"] = str(error)
        print(str(error), file=sys.stderr)
    finally:
        report["source_at_finish"] = source_identity()
        report["source_unchanged"] = report["source"]["source_sha256"] == report["source_at_finish"]["source_sha256"]
        if not report["source_unchanged"] and args.mode in ("quick", "verify", "accept"):
            report["status"] = "failed"
            report["source_error"] = "Source changed during validation; rerun against a stable source tree"
            report.setdefault("error", report["source_error"])
        report["finished_at"] = datetime.now(timezone.utc).isoformat()
        (out / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"Evidence: {out / 'report.json'}")
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
