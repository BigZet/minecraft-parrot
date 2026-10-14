#!/usr/bin/env python3
"""Seed 10,000 inactive registrations, then measure actual dedicated-server registry bootstrap."""
from __future__ import annotations

import argparse
import importlib
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time

sys.dont_write_bytecode = True
driver = importlib.import_module("process-crash-acceptance")


def snapshot_payload(path: Path) -> tuple[dict, int, str]:
    snapshot = json.loads(path.read_text(encoding="utf-8"))
    payload = {"parrots": snapshot["parrots"], "tombstones": snapshot["tombstones"],
               "streamerPlayerUuid": snapshot.get("streamerPlayerUuid")}
    canonical = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return payload, snapshot["checkpointSequence"], hashlib.sha256(canonical).hexdigest()


def run(command: list[str], directory: Path, log: Path, timeout: int, affinity: int | None) -> dict:
    started = time.monotonic()
    with log.open("wb") as output:
        process = subprocess.Popen(command, cwd=directory, stdout=output, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        try:
            observed_affinity = driver.process_affinity(process, affinity)
            print(f"START pid={process.pid} log={log}", flush=True)
            code = process.wait(timeout=timeout)
        except Exception:
            process.kill()
            process.wait(timeout=30)
            raise
    return {"pid": process.pid, "exitCode": code, "seconds": time.monotonic() - started,
            "affinity": observed_affinity, "log": str(log)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--args", type=Path, default=Path("build/process-crash-launch.args"))
    parser.add_argument("--config", type=Path, default=Path("examples/viewerparrots.json"))
    parser.add_argument("--base", type=Path, default=Path("build/registry-bootstrap") / time.strftime("%Y%m%d-%H%M%S"))
    parser.add_argument("--affinity-mask", type=lambda value: int(value, 0))
    parser.add_argument("--timeout", type=int, default=150)
    options = parser.parse_args()
    java, args, config, base = (value.resolve() for value in (options.java, options.args, options.config, options.base))
    base.mkdir(parents=True, exist_ok=False)
    frozen = driver.freeze_launch(args, base / "binary-snapshot")
    cpu_options = [] if options.affinity_mask is None else [f"-XX:ActiveProcessorCount={options.affinity_mask.bit_count()}"]
    directory = base / "dedicated-world"
    driver.prepare(directory, config)
    values = [json.loads(line) for line in frozen.read_text(encoding="utf-8").splitlines()]
    classpath = values[values.index("-cp") + 1]
    seed_args = base / "fixture-generator.args"
    seed_args.write_text("\n".join(json.dumps(value, ensure_ascii=False) for value in (
        "-Xmx4G", "-cp", classpath, "io.github.viewerparrots.gametest.RegistryFixtureGenerator",
        str(directory / "world" / "data" / "viewerparrots"))) + "\n", encoding="utf-8")
    evidence = {"java": str(java), "launchArgs": str(frozen), "launchArgsSha256": driver.sha(frozen),
                "worldDirectory": str(directory), "fixture": "10000 DEAD records; all variants/traits/desired behaviors, varied identities/generations/revisions/timestamps/locations; no entity UUID or occupied slot",
                "passed": False}
    try:
        seeded = run([str(java), f"@{seed_args}"], directory, base / "fixture-generator.log",
                     options.timeout, options.affinity_mask)
        evidence["seedJvm"] = seeded
        if seeded["exitCode"] != 0:
            raise RuntimeError("Fixture generator failed: " + (base / "fixture-generator.log").read_text(encoding="utf-8", errors="replace")[-4000:])
        evidence["inputSnapshotSha256"] = driver.sha(directory / "world" / "data" / "viewerparrots" / "registry.json")
        initial_payload, initial_sequence, initial_digest = snapshot_payload(directory / "world" / "data" / "viewerparrots" / "registry.json")
        evidence["initialPayloadSha256"] = initial_digest
        evidence["initialCheckpointSequence"] = initial_sequence
        measured = run([str(java), *cpu_options, "-Xmx4G", "-Dviewerparrots.qa.registry.profile=true", f"@{frozen}"],
                       directory, base / "dedicated-startup.log", options.timeout, options.affinity_mask)
        evidence["dedicatedJvm"] = measured
        report_path = directory / "registry-bootstrap-report.json"
        if not report_path.is_file():
            raise AssertionError("Dedicated server did not write its bootstrap profile report")
        report = json.loads(report_path.read_text(encoding="utf-8"))
        evidence["observed"] = report
        if measured["exitCode"] != 0 or report.get("passed") is not True:
            raise AssertionError(f"Actual dedicated bootstrap gate failed: exit={measured['exitCode']} report={report}")
        if report.get("checkpoint", {}).get("passed") is not True:
            raise AssertionError("Actual dedicated server did not complete the bounded 10k checkpoint")
        report_path.rename(directory / "registry-bootstrap-before-restart.json")
        primary = directory / "world" / "data" / "viewerparrots" / "registry.json"
        evidence["checkpointSnapshotSha256"] = driver.sha(primary)
        checkpoint_payload, checkpoint_sequence, checkpoint_digest = snapshot_payload(primary)
        evidence["checkpointPayloadSha256"] = checkpoint_digest
        evidence["checkpointSequence"] = checkpoint_sequence
        if checkpoint_payload != initial_payload or checkpoint_sequence < initial_sequence:
            raise AssertionError("10k checkpoint changed complete record/tombstone/streamer payload or regressed sequence")
        if report.get("registryPayloadSha256") != initial_digest or report["checkpoint"]["primaryValidated"].get("payloadSha256") != initial_digest:
            raise AssertionError("Actual loaded registry or manually validated primary altered the full 10k fixture payload")
        restart = run([str(java), *cpu_options, "-Xmx4G", "-Dviewerparrots.qa.registry.profile=true",
                       "-Dviewerparrots.qa.registry.checkpoint=false", f"@{frozen}"],
                      directory, base / "dedicated-restart.log", options.timeout, options.affinity_mask)
        evidence["restartJvm"] = restart
        replay = json.loads(report_path.read_text(encoding="utf-8"))
        evidence["replayed"] = replay
        if restart["exitCode"] != 0 or replay.get("passed") is not True or replay.get("registryRecords") != 10000:
            raise AssertionError(f"Actual dedicated 10k checkpoint restart failed: exit={restart['exitCode']} report={replay}")
        replay_payload, replay_sequence, replay_digest = snapshot_payload(primary)
        evidence["replayPayloadSha256"] = replay_digest
        evidence["replayCheckpointSequence"] = replay_sequence
        if replay_payload != initial_payload or replay_sequence < checkpoint_sequence or replay.get("registryPayloadSha256") != initial_digest:
            raise AssertionError("Actual restarted registry/disk changed complete 10k identities, names, traits, or other payload fields")
        evidence["completePayloadPreserved"] = True
        evidence["passed"] = True
        print(f"PASS actual bootstrap records=10000 seconds={report['loadSeconds']:.6f} retainedMiB={report['retainedHeapDeltaMiB']:.3f}", flush=True)
        print(f"PASS actual checkpoint copyTicks={report['checkpoint']['copyTicks']} maximumPerTick={report['checkpoint']['maximumCopiesPerTick']} replayRecords={replay['registryRecords']}", flush=True)
        return 0
    except Exception as failure:
        evidence["failure"] = str(failure)
        print("FAIL " + str(failure), flush=True)
        return 1
    finally:
        report = base / "registry-bootstrap-results.json"
        report.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
        print("REPORT " + str(report), flush=True)


if __name__ == "__main__":
    raise SystemExit(main())
