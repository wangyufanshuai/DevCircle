#!/usr/bin/env python3
"""Kill an owned search consumer after its committed claim, then verify real Kafka redelivery."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
from scripts.ops.rehearse_search_migration import Rehearsal, NEW_INDEX
from scripts.dev.local import host_available
from scripts.ops.assert_reactor_archive import verify_archive


def recovery_complete(state, offset):
    if state is None or state.get("status") not in {"PROCESSING", "FAILED", "COMPLETED"}:
        raise AssertionError("Recovery lost the persisted event state")
    if offset > 2:
        raise AssertionError("Unexpected source offset during isolated crash recovery")
    if offset > 1 and state["status"] != "COMPLETED":
        raise AssertionError("Kafka acknowledged an unfinished PROCESSING event after restart")
    return state["status"] == "COMPLETED" and offset == 2


class SearchCrash(Rehearsal):
    def __init__(self, args):
        super().__init__(args)
        self.target = "dc-discovery" if args.runtime == "discovery" else "dc-search"
        self.override = self.output / "compose-crash.yml"
        self.compose = ["docker", "compose", "-p", self.project, "-f", str(ROOT / "docker-compose.e2e.yml"),
                        "-f", str(self.override), "--profile", "search-recovery" if args.runtime == "discovery" else "legacy-search"]
        self.receipt.pop("legacyRef")
        self.receipt.update(scope="owned search process crash during projection; real leases and Kafka offsets", runtime=self.target,
                            runnerSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest())
        self.paused = False
        self.supplied_image = args.image_id
        if self.supplied_image and not re.fullmatch(r"sha256:[0-9a-f]{64}", self.supplied_image):
            raise ValueError("A prebuilt baseline must be an immutable local sha256 image ID")
        self.override.write_text(f"""services:
  {self.target}:
    image: {self.new_image}
    depends_on: !override
      mysql: {{condition: service_healthy}}
      kafka: {{condition: service_healthy}}
      elasticsearch: {{condition: service_healthy}}
    environment:
      SPRING_KAFKA_LISTENER_AUTO_STARTUP: 'true'
  elasticsearch:
    environment:
      xpack.ml.enabled: 'false'
""", encoding="utf-8")

    def build_current(self):
        if self.supplied_image:
            self.run(["docker", "image", "tag", self.supplied_image, self.new_image])
            self.receipt["imageSource"] = "supplied immutable local image; sourceCommit identifies the runner checkout only"
        else:
            wrapper = ROOT / ("mvnw.cmd" if os.name == "nt" else "mvnw")
            self.run([wrapper, "-B", "-pl", self.target, "-am", "-DskipTests", "package",
                      "-Dmaven.repo.local=" + str(self.repo), "-Djava.io.tmpdir=" + self.env["TEMP"]], timeout=900)
            suffix = "-exec" if self.target == "dc-search" else ""
            archive = ROOT / self.target / "target" / f"{self.target}-0.1.0-SNAPSHOT{suffix}.jar"
            self.receipt["reactorDependencies"] = verify_archive(archive)
            self.receipt["archiveSha256"] = hashlib.sha256(archive.read_bytes()).hexdigest()
            self.dc("build", self.target, timeout=900)
            self.receipt["imageSource"] = "built from current checkout"
        self.receipt["image"] = self.run(["docker", "image", "inspect", self.new_image, "--format", "{{.Id}}"]).decode().strip()

    def state(self, event_id):
        row = self.sql("dc_search", "SELECT JSON_OBJECT('status',d.status,'attempts',d.attempts,"
                       "'leaseToken',d.lease_token,'watermark',w.aggregate_version,'owner',w.lease_owner,"
                       "'leaseActive',w.lease_until>CURRENT_TIMESTAMP,'updatedAt',CAST(d.updated_at AS CHAR)) "
                       "FROM search_consumer_dedup d LEFT JOIN search_event_watermark w ON w.post_id=d.post_id "
                       f"WHERE d.event_id='{event_id}';")
        return json.loads(row) if row else None

    def committed(self, topic="post.updated"):
        values = [row["offset"] for row in self.offsets() if row["topic"] == topic]
        return values[0] if len(values) == 1 else -1

    def end_offset(self, topic):
        output = self.dc("exec", "-T", "kafka", "/opt/kafka/bin/kafka-get-offsets.sh",
                         "--bootstrap-server", "localhost:9092", "--topic", topic).decode()
        return sum(int(line.rsplit(":", 1)[1]) for line in output.splitlines() if line.startswith(topic + ":"))

    def observe_recovery(self, event_id):
        # Offset commit follows the DB transaction. Read the offset first so a concurrently
        # completed transaction cannot look like an acknowledgement of an older DB snapshot.
        offset = self.committed()
        return self.state(event_id), offset

    def execute(self):
        self.preflight()
        if host_available() < 1024**3:
            raise RuntimeError("Less than 1 GiB host memory available")
        self.build_current()
        self.started = True
        self.dc("up", "-d", "--no-build", "--pull", "never", "--wait", "--wait-timeout", "240",
                "mysql", "kafka", "elasticsearch", self.target, timeout=360)
        baseline = self.event(1, title="before-crash")
        self.publish(baseline)
        self.wait(lambda: (self.state(baseline["eventId"]) or {}).get("status") == "COMPLETED", "baseline event")
        self.wait(lambda: self.committed() == 1, "baseline offset")
        self.receipt["baseline"] = {"state": self.state(baseline["eventId"]), "offsets": self.offsets(), "documents": self.docs(NEW_INDEX)}
        self.check("real_consumer_baseline_committed")

        # Pausing the dependency leaves the consumer alive long enough to observe its committed lease.
        self.dc("pause", "elasticsearch")
        self.paused = True
        interrupted = self.event(2, title="after-crash")
        self.publish(interrupted)
        self.wait(lambda: (self.state(interrupted["eventId"]) or {}).get("status") == "PROCESSING", "in-flight lease", timeout=30)
        claimed = self.state(interrupted["eventId"])
        if not claimed["leaseToken"] or claimed["owner"] != claimed["leaseToken"] or not claimed["leaseActive"]:
            raise AssertionError("The interrupted worker did not own a live aggregate lease")
        self.dc("kill", "-s", "SIGKILL", self.target)
        killed = json.loads(self.run(["docker", "inspect", self.dc("ps", "-a", "-q", self.target).decode().strip()]))[0]
        if killed["State"]["Running"] or killed["State"]["OOMKilled"] or killed["State"]["ExitCode"] != 137:
            raise AssertionError("Search did not stop at the deliberate SIGKILL point")
        if self.state(interrupted["eventId"])["status"] != "PROCESSING" or self.committed() != 1:
            raise AssertionError("Interrupted event was completed or acknowledged before restart")
        self.receipt["interrupted"] = {"event": interrupted, "state": claimed, "offsets": self.offsets(), "exitCode": 137}
        self.dc("unpause", "elasticsearch")
        self.paused = False
        # A request already queued in the kernel can commit in ES after the caller has died.
        # Either durable outcome must recover; neither permits acknowledging an unfinished DB claim.
        resumed_documents = self.docs(NEW_INDEX)
        if (len(resumed_documents) != 1 or
                (resumed_documents[0]["aggregateVersion"], resumed_documents[0]["title"])
                not in {(1, "before-crash"), (2, "after-crash")}):
            raise AssertionError("Projection has an unexpected state after dependency recovery")
        self.receipt["projectionAfterDependencyResume"] = resumed_documents
        self.check("kill_after_claim_before_completion_and_offset_commit")
        self.dc("up", "-d", "--no-deps", "--no-build", "--pull", "never", "--wait", "--wait-timeout", "180", self.target, timeout=240)

        deadline = time.monotonic() + 360
        while True:
            state, offset = self.observe_recovery(interrupted["eventId"])
            self.receipt["recoveryObservation"] = {"state": state, "offset": offset}
            self.save()
            if recovery_complete(state, offset):
                break
            if time.monotonic() >= deadline:
                raise AssertionError("Interrupted event did not recover after its real lease expiry")
            time.sleep(2)
        if state["attempts"] != 2 or state["watermark"] != 2 or state["owner"] is not None:
            raise AssertionError("Lease reclaim did not complete exactly once")
        documents = self.docs(NEW_INDEX)
        if len(documents) != 1 or documents[0]["title"] != "after-crash" or documents[0]["aggregateVersion"] != 2:
            raise AssertionError("Recovered projection is missing or incorrect")
        if self.end_offset("post.updated.DLT") != 0:
            raise AssertionError("A busy lease was diverted to DLT instead of being resumed")
        self.check("real_lease_expiry_redelivery_and_projection_recovery")

        self.publish(interrupted)
        deletion = self.event(3, True)
        self.publish(deletion)
        self.wait(lambda: (self.state(deletion["eventId"]) or {}).get("status") == "COMPLETED", "delete")
        stale = self.event(1, title="must-not-resurrect")
        self.publish(stale)
        self.wait(lambda: (self.state(stale["eventId"]) or {}).get("status") == "COMPLETED", "stale replay")
        self.wait(lambda: self.committed() == 4, "duplicate and stale offsets")
        documents = self.docs(NEW_INDEX)
        if len(documents) != 1 or documents[0]["status"] != "DELETED" or documents[0]["aggregateVersion"] != 3:
            raise AssertionError("Duplicate/stale replay resurrected a deleted projection")
        if self.sql("dc_search", "SELECT COUNT(*) FROM search_consumer_dedup WHERE status='COMPLETED';") != "4":
            raise AssertionError("Replay changed the number of completed unique events")
        self.check("duplicates_and_stale_messages_cannot_resurrect_after_crash")
        poison = self.event()
        self.publish(poison)
        self.wait(lambda: self.end_offset("post.updated.DLT") == 1 and self.committed() == 5, "poison event DLT", timeout=90)
        if self.docs(NEW_INDEX) != documents:
            raise AssertionError("Invalid event changed the projection")
        self.check("normal_invalid_event_still_reaches_DLT")
        ids = self.dc("ps", "-a", "-q").decode().split()
        containers = json.loads(self.run(["docker", "inspect", *ids]))
        if len(containers) != 4 or any(c["State"]["OOMKilled"] or not c["State"]["Running"] for c in containers):
            raise AssertionError("Crash fixture has missing, stopped or OOM-killed services")
        oom = self.run(["docker", "events", "--since", self.started_at, "--until", datetime.now(timezone.utc).isoformat(),
                        "--filter", f"label=com.docker.compose.project={self.project}", "--filter", "event=oom", "--format", "{{json .}}"])
        if oom.strip(): raise AssertionError("Docker reported an OOM event")
        self.receipt.update(status="passed", oomEvents=0, finalDocuments=documents, finalOffsets=self.offsets())
        self.save()

    def close(self):
        if self.paused:
            try:
                self.dc("unpause", "elasticsearch")
            except Exception as error:
                self.receipt["unpauseError"] = type(error).__name__
            finally:
                self.paused = False
        super().close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--maven-repo", required=True)
    parser.add_argument("--runtime", choices=("discovery", "standalone"), default="discovery",
                        help="Default is the production Discovery assembly; standalone is the rollback search executable")
    parser.add_argument("--image-id", help="Optional immutable local image ID for reproducing a known baseline; never pulls")
    parser.add_argument("--output-root", default=str(ROOT / "artifacts/local/search-crash"))
    run = SearchCrash(parser.parse_args())
    print("Evidence: " + str(run.output), flush=True)
    try:
        run.execute()
    except Exception as error:
        run.receipt.update(status="failed", error=str(error))
        raise
    finally:
        run.close()


if __name__ == "__main__":
    main()
