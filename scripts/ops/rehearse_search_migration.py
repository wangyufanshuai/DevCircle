#!/usr/bin/env python3
"""Isolated, destructive-to-owned-fixtures-only search cutover/rollback rehearsal.

Uses real old/new binaries, MySQL, Kafka and Elasticsearch snapshots. All data
belongs to a fresh random Compose project; never accepts an existing project.
Rollback is deliberately read-only: retained post-cutover Kafka records must
not be fed to the old timestamp-based consumer.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import gzip
import hashlib
import json
import os
from pathlib import Path, PureWindowsPath
import shutil
import subprocess
import sys
import time
import urllib.request
import urllib.error
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[2]
LEGACY_REF = "00a4b99ebb938120b0a3c22f806bb52bdfc50abd"
OLD_INDEX = "devcircle-posts"
NEW_INDEX = "devcircle-posts-v2"
TOPICS = ["post.created", "post.updated", "post.deleted", "audit.completed", "content.rejected"]


def non_system_path(path: Path) -> Path:
    if PureWindowsPath(str(path)).drive.upper() == "C:":
        raise ValueError(f"Rehearsal data must not be placed on C: {path}")
    path = path.resolve()
    if path.drive.upper() == "C:":
        raise ValueError(f"Rehearsal data must not be placed on C: {path}")
    return path


def verify_files(directory: Path, manifest: dict) -> None:
    for name, expected in manifest["sha256"].items():
        path = (directory / name).resolve()
        if path.parent != directory.resolve():
            raise ValueError("Backup artifact escapes its directory")
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError(f"Backup checksum mismatch: {name}")


def compare_projection(rows: list[dict], documents: list[dict]) -> None:
    expected = {str(row["id"]): row for row in rows}
    actual = {str(doc["id"]): doc for doc in documents if doc["status"] == "PUBLISHED"}
    if len(actual) != len([d for d in documents if d["status"] == "PUBLISHED"]) or actual.keys() != expected.keys():
        raise AssertionError("Published search IDs do not match the database")
    for key, row in expected.items():
        for field in ("title", "aggregateVersion", "excerpt"):
            if actual[key].get(field) != row[field]:
                raise AssertionError(f"Search mismatch: post {key} field {field}")
    for doc in documents:
        version = doc.get("aggregateVersion")
        if type(version) is not int or version < 0 or doc["status"] not in ("PUBLISHED", "DELETED"):
            raise AssertionError("Invalid search version or state")


class Rehearsal:
    def __init__(self, args):
        self.output = non_system_path(Path(args.output_root)) / (time.strftime("%Y%m%dT%H%M%S") + "-" + uuid.uuid4().hex[:8])
        self.output.mkdir(parents=True)
        self.project = "devcircle-search-migration-" + uuid.uuid4().hex[:12]
        self.repo = non_system_path(Path(args.maven_repo))
        self.env = os.environ.copy()
        temp = self.output / "tmp"
        temp.mkdir()
        self.env.update(TEMP=str(temp), TMP=str(temp), TMPDIR=str(temp),
                        REAL_E2E_ENABLE_OUTBOX="false", REAL_E2E_SEARCH_URI="http://dc-search:8106")
        self.override = self.output / "compose.json"
        self.compose = ["docker", "compose", "-p", self.project, "-f", str(ROOT / "docker-compose.e2e.yml"),
                        "-f", str(self.override), "--profile", "legacy-search"]
        self.old_image = self.project + ":old"
        self.new_image = self.project + ":new"
        self.receipt = {"status": "running", "project": self.project, "legacyRef": LEGACY_REF, "checks": [],
                        "runnerSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                        "scope": "isolated fixture data; read-only point-in-time rollback; no production migration"}
        self.started = False
        self.closing = False
        self.started_at = datetime.now(timezone.utc).isoformat()

    def run(self, args, *, data=None, cwd=ROOT, timeout=600, binary=False) -> bytes:
        if self.started and not self.closing and shutil.disk_usage(self.output).free < 4 * 1024**3:
            raise RuntimeError("Work disk reserve fell below 4 GiB; preserve results and stop owned containers")
        result = subprocess.run(list(map(str, args)), cwd=cwd, env=self.env, input=data,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        with (self.output / "commands.log").open("ab") as log:
            if not binary:
                log.write(result.stdout)
            log.write(result.stderr)
        if result.returncode:
            raise RuntimeError(f"Command {args[0]} failed ({result.returncode}): {result.stderr.decode('utf-8', errors='replace')[-1500:]}")
        return result.stdout

    def dc(self, *args, **kwargs):
        return self.run([*self.compose, *args], **kwargs)

    def check(self, name):
        if self.started:
            stats = self.dc("stats", "--no-stream", "--format", "json").decode()
            with (self.output / "resource-samples.jsonl").open("a", encoding="utf-8") as samples:
                samples.write(json.dumps({"phase": name, "dockerStats": stats}) + "\n")
        self.receipt["checks"].append(name)
        print(f"PASS {name}", flush=True)
        self.save()

    def save(self):
        (self.output / "report.json").write_text(json.dumps(self.receipt, indent=2) + "\n", encoding="utf-8")

    def sql(self, database, statement):
        return self.dc("exec", "-T", "mysql", "mysql", "-uroot", "-pe2e-root-password", "-N", "-B", database,
                       data=statement.encode()).decode().strip()

    def es(self, method, path, body=None):
        args = ["exec", "-T", "elasticsearch", "curl", "-fsS", "-X", method, "http://localhost:9200/" + path]
        if body is not None:
            args += ["-H", "Content-Type: application/json", "--data-binary", "@-"]
        return json.loads(self.dc(*args, data=json.dumps(body).encode() if body is not None else None))

    def api(self, method, path, body=None, token=None):
        headers = {"Content-Type": "application/json", "Idempotency-Key": str(uuid.uuid4())}
        if token:
            headers["Authorization"] = "Bearer " + token
        request = urllib.request.Request("http://127.0.0.1:28080" + path,
                                         data=json.dumps(body).encode() if body is not None else (b"" if method == "POST" else None),
                                         headers=headers, method=method)
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.load(response)["data"]

    def drive_reindex(self, job_id, token, *, create=False, max_steps=6):
        driver = ROOT / "scripts/ops/run_search_reindex.py"
        self.receipt["reindexDriverSha256"] = hashlib.sha256(driver.read_bytes()).hexdigest()
        args = [sys.executable, str(driver), "--base-url", "http://127.0.0.1:28080",
                "--job-id", job_id, "--max-steps", str(max_steps), "--output-root", str(self.output / "reindex-driver")]
        if create:
            args.append("--create-confirm-maintenance")
        # Pass credentials through the child environment only, never argv, receipts or command logs.
        env = dict(self.env, DEVCIRCLE_ADMIN_TOKEN=token)
        result = subprocess.run(args, cwd=ROOT, env=env, capture_output=True, timeout=600)
        if result.returncode not in (0, 3):
            raise AssertionError("Reindex CLI failed: " + result.stderr.decode("utf-8", errors="replace")[-1500:])
        checkpoint = json.loads(result.stdout)
        saved = json.loads((self.output / "reindex-driver" / job_id / "status.json").read_text(encoding="utf-8"))
        if saved != checkpoint or (result.returncode == 0) != (checkpoint["phase"] == "COMPLETED"):
            raise AssertionError("Reindex CLI exit status or saved checkpoint disagrees with its response")
        return checkpoint

    def configuration(self, image, consume):
        self.override.write_text(json.dumps({"services": {
            "dc-search": {"image": image, "environment": {"SPRING_KAFKA_LISTENER_AUTO_STARTUP": str(consume).lower()}},
            "elasticsearch": {"environment": {"path.repo": "/snapshots", "xpack.ml.enabled": "false"}, "mem_limit": "1024m",
                              "volumes": ["migration-snapshots:/snapshots"]}},
            "volumes": {"migration-snapshots": {}}}), encoding="utf-8")

    def preflight(self):
        for path in (self.output, self.repo):
            if shutil.disk_usage(path).free < 6 * 1024**3:
                raise RuntimeError(f"At least 6 GiB free required on {path.anchor}")
        if os.name == "nt":
            settings = json.loads((Path(os.environ["APPDATA"]) / "Docker/settings-store.json").read_text(encoding="utf-8"))
            docker_data = non_system_path(Path(settings["CustomWslDistroDir"]))
            if shutil.disk_usage(docker_data).free < 6 * 1024**3:
                raise RuntimeError("Docker data disk needs 6 GiB free")
            self.receipt["dockerData"] = str(docker_data)
        info = json.loads(self.run(["docker", "info", "--format", "{{json .}}"]))
        if info["MemTotal"] < 4 * 1024**3:
            raise RuntimeError("Docker requires 4 GiB memory")
        self.receipt["sourceCommit"] = self.run(["git", "rev-parse", "HEAD"]).decode().strip()
        self.check("storage_preflight_no_C_data")

    def build(self):
        archive = self.output / "legacy.zip"
        self.run(["git", "archive", "--format=zip", "-o", archive, LEGACY_REF])
        legacy = self.output / "legacy-source"
        legacy.mkdir()
        with zipfile.ZipFile(archive) as source:
            for name in source.namelist():
                if not (legacy / name).resolve().is_relative_to(legacy):
                    raise ValueError("Unsafe archive member")
            source.extractall(legacy)
        wrapper = ROOT / ("mvnw.cmd" if os.name == "nt" else "mvnw")
        self.run([wrapper, "-B", "-f", legacy / "pom.xml", "-pl", "dc-search", "-am", "-DskipTests",
                  f"-Dmaven.repo.local={self.repo}", "package"], timeout=1200)
        image_dir = self.output / "legacy-image"
        image_dir.mkdir()
        shutil.copy2(legacy / "dc-search/target/dc-search-0.1.0-SNAPSHOT-exec.jar", image_dir / "app.jar")
        shutil.copy2(ROOT / "Dockerfile.service", image_dir / "Dockerfile")
        self.run(["docker", "build", "-t", self.old_image, "--build-arg", "JAR_FILE=app.jar", image_dir], timeout=900)
        self.run([wrapper, "-B", "-pl", "dc-identity,dc-content-deployment,dc-admin,dc-search,dc-gateway", "-am",
                  "-DskipTests", f"-Dmaven.repo.local={self.repo}", "package"], timeout=1200)
        self.configuration(self.new_image, True)
        self.dc("build", timeout=1200)
        self.receipt["images"] = {key: self.run(["docker", "image", "inspect", "--format", "{{.Id}}", value]).decode().strip()
                                  for key, value in (("legacy", self.old_image), ("current", self.new_image))}
        self.check("immutable_old_and_current_binaries_built")

    def wait(self, predicate, description, timeout=150):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if predicate():
                return
            time.sleep(2)
        raise AssertionError("Timed out: " + description)

    def docs(self, index):
        self.es("POST", index + "/_refresh")
        result = self.es("POST", index + "/_search", {"size": 10000, "query": {"match_all": {}}})
        if result["hits"]["total"]["relation"] != "eq" or result["hits"]["total"]["value"] != len(result["hits"]["hits"]):
            raise AssertionError("Incomplete rehearsal index enumeration")
        return sorted([hit["_source"] for hit in result["hits"]["hits"]], key=lambda doc: doc["id"])

    def rows(self):
        result = self.sql("dc_content", "SELECT JSON_OBJECT('id',p.id,'title',p.title,'aggregateVersion',p.version,'excerpt',LEFT(pc.markdown,140)) FROM post p JOIN post_content pc ON pc.post_id=p.id WHERE p.status='PUBLISHED' ORDER BY p.id;")
        return [json.loads(line) for line in result.splitlines() if line]

    def database_state(self):
        return {
            "posts": self.sql("dc_content", "SELECT id,title,status,version FROM post ORDER BY id;"),
            "watermarks": self.sql("dc_search", "SELECT * FROM search_event_watermark ORDER BY post_id;"),
            "dedup": self.sql("dc_search", "SELECT * FROM search_consumer_dedup ORDER BY event_id;"),
            "migration": self.sql("dc_search", "SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank;")}

    def offsets(self):
        output = self.dc("exec", "-T", "kafka", "/opt/kafka/bin/kafka-consumer-groups.sh", "--bootstrap-server", "localhost:9092", "--group", "dc-search", "--describe").decode()
        result = []
        for line in output.splitlines():
            parts = line.split()
            if len(parts) >= 4 and parts[0] == "dc-search" and parts[2].isdigit() and parts[3].isdigit():
                result.append({"topic": parts[1], "partition": int(parts[2]), "offset": int(parts[3])})
        if not result:
            raise AssertionError("No committed Kafka offsets to back up")
        return sorted(result, key=lambda r: (r["topic"], r["partition"]))

    def event(self, version=None, delete=False, title="migration-original"):
        payload = {"postId": 900001, "status": "DELETED" if delete else "PUBLISHED", "title": title,
                   "excerpt": "migration-body", "tags": [], "authorId": 42}
        if version is not None:
            payload["aggregateVersion"] = version
        return {"eventId": str(uuid.uuid4()), "type": "post.deleted" if delete else "post.updated", "version": 1,
                "occurredAt": "2020-01-01T00:00:00Z" if delete else "2026-10-14T00:00:00Z",
                "producer": "dc-content", "aggregateId": "900001", "payload": payload}

    def publish(self, event):
        self.dc("exec", "-T", "kafka", "/opt/kafka/bin/kafka-console-producer.sh", "--bootstrap-server", "localhost:9092",
                "--topic", event["type"], data=(json.dumps(event) + "\n").encode())

    def backup(self, name, indices):
        self.dc("stop", "dc-search", "dc-admin", "dc-content-aggregate", "dc-gateway", "dc-identity")
        directory = self.output / name
        directory.mkdir()
        dump = self.dc("exec", "-T", "mysql", "mysqldump", "-uroot", "-pe2e-root-password", "--single-transaction",
                       "--routines", "--events", "--triggers", "--hex-blob", "--add-drop-database", "--databases", "dc_content", "dc_search", binary=True)
        (directory / "mysql.sql.gz").write_bytes(gzip.compress(dump))
        offsets = self.offsets()
        (directory / "offsets.json").write_text(json.dumps(offsets, indent=2), encoding="utf-8")
        documents = {index: self.docs(index) for index in indices}
        (directory / "documents.json").write_text(json.dumps(documents, sort_keys=True), encoding="utf-8")
        result = self.es("PUT", "_snapshot/migration/" + name + "?wait_for_completion=true",
                         {"indices": ",".join(indices), "include_global_state": False})
        if result["snapshot"]["state"] != "SUCCESS":
            raise AssertionError("Elasticsearch snapshot failed")
        # The official Elasticsearch image intentionally omits tar/gzip. Use the
        # already-pulled Alpine maintenance runtime with a read-only volume.
        (directory / "es-repository.tar.gz").write_bytes(self.run([
            "docker", "run", "--rm", "--network", "none", "--read-only", "--mount",
            f"type=volume,src={self.project}_migration-snapshots,dst=/snapshots,readonly",
            "redis:8.2.3-alpine", "tar", "-C", "/snapshots/repository", "-czf", "-", "."], binary=True))
        files = list(directory.iterdir())
        manifest = {"snapshot": name, "indices": indices, "rows": self.rows(), "databaseState": self.database_state(), "sha256":
                    {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in files}}
        (directory / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
        verify_files(directory, manifest)
        self.check(name + "_sql_snapshot_offsets_backup")
        return directory, manifest

    def restore(self, backup):
        directory, manifest = backup
        verify_files(directory, manifest)  # Before stopping or modifying anything.
        self.dc("stop", "dc-search", "dc-admin", "dc-content-aggregate", "dc-gateway", "dc-identity")
        self.dc("exec", "-T", "mysql", "mysql", "-uroot", "-pe2e-root-password", data=gzip.decompress((directory / "mysql.sql.gz").read_bytes()))
        restore_repo = "restore-" + manifest["snapshot"]
        location = "/snapshots/" + restore_repo
        self.run(["docker", "run", "--rm", "-i", "--network", "none", "--read-only", "--mount",
                  f"type=volume,src={self.project}_migration-snapshots,dst=/snapshots",
                  "redis:8.2.3-alpine", "sh", "-c", 'mkdir -p "$1" && tar -C "$1" -xzf - && chown -R 1000:0 "$1"',
                  "restore", location], data=(directory / "es-repository.tar.gz").read_bytes())
        self.es("PUT", "_snapshot/" + restore_repo, {"type": "fs", "settings": {"location": location, "readonly": True}})
        for index in manifest["indices"]:
            self.es("DELETE", index)
        result = self.es("POST", "_snapshot/" + restore_repo + "/" + manifest["snapshot"] + "/_restore?wait_for_completion=true",
                         {"indices": ",".join(manifest["indices"]), "include_global_state": False})
        if result["snapshot"]["shards"]["failed"] != 0:
            raise AssertionError("Snapshot restore failed")
        # Reset the entire owned group, including partitions first consumed AFTER
        # the backup. Resetting only listed partitions leaves a mixed checkpoint.
        self.dc("exec", "-T", "kafka", "/opt/kafka/bin/kafka-consumer-groups.sh", "--bootstrap-server", "localhost:9092",
                "--group", "dc-search", "--delete")
        for offset in json.loads((directory / "offsets.json").read_text()):
            self.dc("exec", "-T", "kafka", "/opt/kafka/bin/kafka-consumer-groups.sh", "--bootstrap-server", "localhost:9092",
                    "--group", "dc-search", "--topic", f"{offset['topic']}:{offset['partition']}", "--reset-offsets", "--to-offset", str(offset["offset"]), "--execute")
        if self.offsets() != json.loads((directory / "offsets.json").read_text()):
            raise AssertionError("Kafka offset restore mismatch")
        if self.rows() != manifest["rows"]:
            raise AssertionError("Database content restore mismatch")
        if self.database_state() != manifest["databaseState"]:
            raise AssertionError("Restored business versions, dedup, watermarks or schema differ")
        expected = json.loads((directory / "documents.json").read_text())
        if any(self.docs(index) != expected[index] for index in manifest["indices"]):
            raise AssertionError("Restored Elasticsearch documents differ from backup")
        self.check(manifest["snapshot"] + "_exact_restore")

    def start_search(self, image, consume):
        self.configuration(image, consume)
        self.dc("up", "-d", "--no-build", "--pull", "never", "--wait", "--wait-timeout", "240", timeout=360)

    def execute(self):
        self.preflight()
        self.build()
        self.configuration(self.old_image, True)
        self.started = True
        self.start_search(self.old_image, True)
        self.dc("exec", "-T", "-u", "0", "elasticsearch", "chown", "1000:0", "/snapshots")
        self.es("PUT", "_snapshot/migration", {"type": "fs", "settings": {"location": "/snapshots/repository"}})
        self.sql("dc_content", "INSERT INTO post(id,author_id,title,status,version) VALUES(900001,42,'migration-original','PUBLISHED',5),(900002,42,'migration-retained','PUBLISHED',3); INSERT INTO post_content(post_id,markdown) VALUES(900001,'migration-body'),(900002,'retained-body');")
        credentials = {"email": "migration-admin@example.test", "password": "Migration-Only-2026!"}
        self.api("POST", "/api/auth/register", dict(credentials, displayName="Migration Admin"))
        self.sql("dc_auth", "INSERT IGNORE INTO user_role(user_id,role_id) SELECT u.id,r.id FROM user_account u JOIN role r ON r.code='ADMIN' WHERE u.email='migration-admin@example.test';")
        token = self.api("POST", "/api/auth/login", credentials)["accessToken"]
        self.api("POST", "/api/search/admin/reindex", token=token)
        legacy_event = self.event()
        self.publish(legacy_event)
        self.wait(lambda: self.sql("dc_search", f"SELECT COUNT(*) FROM search_consumer_dedup WHERE event_id='{legacy_event['eventId']}' AND status='COMPLETED';") == "1", "old consumer")
        if len(self.docs(OLD_INDEX)) != 2 or self.sql("dc_search", "SELECT MAX(version) FROM flyway_schema_history WHERE success=1;") != "3":
            raise AssertionError("Legacy binary did not establish V3 baseline")
        self.check("legacy_binary_V3_index_and_Kafka_readable")
        baseline = self.backup("baseline", [OLD_INDEX])
        self.start_search(self.new_image, False)
        # Add 19 isolated rows after the old baseline so the durable job must cross a batch boundary.
        for post_id in range(900003, 900022):
            self.sql("dc_content", f"INSERT INTO post(id,author_id,title,status,version) VALUES({post_id},42,'checkpoint-{post_id}','PUBLISHED',1); INSERT INTO post_content(post_id,markdown) VALUES({post_id},'checkpoint-body-{post_id}');")
        job_id = str(uuid.uuid4())
        endpoint = "/api/search/admin/reindex/jobs/" + job_id
        self.api("PUT", endpoint + "?maintenanceConfirmed=true", token=token)
        ordinary = self.api("POST", "/api/auth/register", {
            "email": "reindex-reader@example.test", "password": "Reindex-Reader-2026!", "displayName": "Reader"})["accessToken"]
        for method, path, auth, expected in (
                ("GET", endpoint, None, 401), ("GET", endpoint, ordinary, 403),
                ("POST", endpoint + "/step?revision=0", ordinary, 403),
                ("POST", endpoint + "/cancel", ordinary, 403),
                ("PUT", "/api/search/admin/reindex/jobs/" + str(uuid.uuid4()) + "?maintenanceConfirmed=true", ordinary, 403)):
            try:
                self.api(method, path, token=auth)
                raise AssertionError("Non-admin reindex operation was accepted")
            except urllib.error.HTTPError as error:
                if error.code != expected:
                    raise AssertionError("Unexpected reindex authorization result") from None
        if self.sql("dc_search", "SELECT COUNT(*) FROM search_reindex_job WHERE revision=0 AND phase='COPYING'") != "1":
            raise AssertionError("Rejected reindex requests changed job state")
        self.check("reindex_job_gateway_permissions_no_side_effects")
        checkpoint = self.drive_reindex(job_id, token, create=True, max_steps=1)
        if checkpoint["phase"] != "COPYING" or checkpoint["copied"] != 20 or not checkpoint["cursor"]:
            raise AssertionError("Reindex did not persist the copy checkpoint")
        # Kill only this isolated project's search process; DB/index volumes remain intact.
        self.dc("kill", "-s", "SIGKILL", "dc-search")
        self.start_search(self.new_image, False)
        if self.api("GET", endpoint, token=token) != checkpoint:
            raise AssertionError("Reindex checkpoint was lost across process termination")
        if self.api("POST", endpoint + "/step?revision=0", token=token) != checkpoint:
            raise AssertionError("Retry of an acknowledged batch advanced twice")
        completed = self.drive_reindex(job_id, token)
        if completed["phase"] != "COMPLETED" or completed["copied"] != 21 or completed["verified"] != 21 or completed["copyHash"] != completed["verifyHash"]:
            raise AssertionError("Reindex exact verification did not complete after restart")
        self.receipt["reindexJob"] = completed
        compare_projection(self.rows(), self.docs(NEW_INDEX))
        if self.sql("dc_search", "SELECT COUNT(*) FROM search_event_watermark WHERE aggregate_version IS NOT NULL;") != "0":
            raise AssertionError("Migration fabricated versions from old watermarks")
        self.check("V5_durable_reindex_restart_and_exact_verification")
        self.dc("stop", "dc-search")
        self.start_search(self.new_image, True)
        unversioned = self.event(title="legacy-must-not-overwrite")
        self.publish(unversioned)
        self.wait(lambda: int(self.dc("exec", "-T", "kafka", "/opt/kafka/bin/kafka-get-offsets.sh", "--bootstrap-server", "localhost:9092", "--topic", "post.updated.DLT").decode().strip().split(":")[-1]) > 0, "legacy DLT")
        compare_projection(self.rows(), self.docs(NEW_INDEX))
        self.sql("dc_content", "UPDATE post SET status='DELETED',version=6 WHERE id=900001;")
        deletion = self.event(6, True)
        self.publish(deletion)
        self.wait(lambda: self.sql("dc_search", "SELECT COALESCE(aggregate_version,-1) FROM search_event_watermark WHERE post_id=900001;") == "6", "versioned delete")
        stale = self.event(5, title="stale-must-not-resurrect")
        self.publish(stale)
        self.publish(deletion)
        self.wait(lambda: self.sql("dc_search", f"SELECT COUNT(*) FROM search_consumer_dedup WHERE event_id='{stale['eventId']}' AND status='COMPLETED';") == "1", "stale event rejected")
        self.wait(lambda: any(o["topic"] == "post.deleted" and o["offset"] == 2 for o in self.offsets()), "duplicate delete committed")
        self.api("POST", "/api/search/admin/reindex", token=token)
        compare_projection(self.rows(), self.docs(NEW_INDEX))
        tombstone = next(d for d in self.docs(NEW_INDEX) if d["id"] == 900001)
        if tombstone["status"] != "DELETED" or tombstone["aggregateVersion"] != 6:
            raise AssertionError("Replay resurrected deleted content")
        self.check("legacy_DLT_duplicate_stale_replay_and_reindex_no_resurrection")
        current = self.backup("current", [OLD_INDEX, NEW_INDEX])
        self.restore(baseline)
        self.start_search(self.old_image, False)
        if 900001 not in [row["id"] for row in self.api("GET", "/api/search/posts?keyword=migration-original")["records"]]:
            raise AssertionError("Old binary cannot read restored baseline")
        if self.offsets() != json.loads((baseline[0] / "offsets.json").read_text()):
            raise AssertionError("Read-only rollback consumed post-cutover messages")
        self.check("old_binary_read_only_rollback_without_Kafka_replay")
        self.restore(current)
        self.start_search(self.new_image, False)
        compare_projection(self.rows(), self.docs(NEW_INDEX))
        if 900001 in [row["id"] for row in self.api("GET", "/api/search/posts?keyword=migration-original")["records"]]:
            raise AssertionError("Forward recovery lost deletion")
        self.check("forward_recovery_preserves_post_cutover_state")
        ids = self.dc("ps", "-a", "-q").decode().split()
        states = json.loads(self.run(["docker", "inspect", *ids]))
        if any(container["State"].get("OOMKilled") for container in states):
            raise AssertionError("OOM during rehearsal")
        events = self.run(["docker", "events", "--since", self.started_at,
                           "--until", datetime.now(timezone.utc).isoformat(), "--filter", "event=oom",
                           "--filter", "label=com.docker.compose.project=" + self.project, "--format", "{{json .}}"])
        if events.strip():
            raise AssertionError("OOM event observed during rehearsal")
        self.receipt["oomEvents"] = 0
        self.receipt["oomKilledAtFinalInspect"] = False
        self.receipt["status"] = "passed"
        self.save()

    def close(self):
        self.closing = True
        if self.started:
            if self.receipt["status"] != "passed":
                try:
                    (self.output / "failure-containers.log").write_bytes(self.dc("logs", "--no-color", "--tail", "150"))
                except Exception as error:
                    self.receipt["logCollectionError"] = str(error)
            # The project name is generated here, never supplied by the caller.
            self.dc("down", "--remove-orphans", timeout=180)
            self.receipt["containersStopped"] = True
        self.save()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-root", default=str(ROOT / "artifacts/local/search-migration"))
    parser.add_argument("--maven-repo", required=True)
    args = parser.parse_args()
    rehearsal = Rehearsal(args)
    print("Evidence: " + str(rehearsal.output), flush=True)
    try:
        rehearsal.execute()
    except Exception as error:
        rehearsal.receipt.update(status="failed", error=str(error))
        raise
    finally:
        rehearsal.close()


if __name__ == "__main__":
    main()
