#!/usr/bin/env python3
"""Run/restart the real dedicated Minecraft JVM at test-only durable crash gates.

First generate build/process-crash-launch.args with runCrashServer in smoke mode.
The harness does not edit user saves or need an authenticated Minecraft account.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
from pathlib import Path
import socket
import stat
import subprocess
import time

CRASH_SCENARIOS = (
    "BEFORE_PREPARE", "AFTER_PREPARE", "AFTER_SPAWN_BEFORE_COMMIT",
    "AFTER_COMMIT_BEFORE_WORLD_SAVE", "AFTER_REMOVE_COMMIT_BEFORE_DISCARD",
    "DURING_CHECKPOINT",
)
STORAGE_SCENARIOS = (
    "CORRUPT_SNAPSHOT_WITH_BACKUP", "CORRUPT_JOURNAL_MIDDLE",
    "JOURNAL_TRUNCATION", "READ_ONLY_STORAGE", "DISK_WRITE_FAILURE",
)


def available_port() -> int:
    with socket.socket() as handle:
        handle.bind(("127.0.0.1", 0))
        return handle.getsockname()[1]


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def flatten_launch_args(source: Path, destination: Path) -> Path:
    # Loom may include a classpath @file in allJvmArgs. Java accepts @file only
    # on the command line, so flatten that generated classpath into our export.
    lines = []
    for line in source.read_text(encoding="utf-8").splitlines():
        value = line.strip().strip('"')
        if value.startswith("@"):
            nested = Path(value[1:])
            for argument in nested.read_text(encoding="utf-8").splitlines():
                lines.append('"' + argument.replace("\\", "/").replace('"', '\\"') + '"')
        else:
            lines.append(line)
    destination.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return destination


def freeze_launch(source: Path, directory: Path) -> Path:
    """Snapshot compiled mod outputs so another Gradle task cannot remove them during startup."""
    directory.mkdir(parents=True, exist_ok=False)
    flattened = flatten_launch_args(source, directory / "flattened.args")
    values = [json.loads(line) for line in flattened.read_text(encoding="utf-8").splitlines()]
    classpath = values[values.index("-cp") + 1]
    gametest = next(Path(value) for value in classpath.split(os.pathsep)
                    if value.replace("\\", "/").endswith("/classes/java/gametest"))
    build = gametest.parents[2]
    replacements = []
    for relative in ("classes/java/main", "classes/java/client", "classes/java/gametest",
                     "resources/main", "resources/client", "resources/gametest"):
        original, copied = build / relative, directory / "outputs" / relative
        if original.is_dir():
            shutil.copytree(original, copied)
        else:
            copied.mkdir(parents=True)
        replacements.append((original, copied))

    def rewrite(value: str) -> str:
        for original, copied in replacements:
            value = value.replace(original.as_posix(), copied.as_posix()).replace(str(original), str(copied))
        return value

    config_index = next(index for index, value in enumerate(values) if value.startswith("-Dfabric.dli.config="))
    original_config = Path(values[config_index].split("=", 1)[1])
    frozen_config = directory / "launch.cfg"
    frozen_config.write_text(rewrite(original_config.read_text(encoding="utf-8")), encoding="utf-8")
    values = [rewrite(value) for value in values]
    values[config_index] = "-Dfabric.dli.config=" + frozen_config.as_posix()
    result = directory / "dedicated-launch.args"
    result.write_text("\n".join(json.dumps(value) for value in values) + "\n", encoding="utf-8")
    class_hashes = {str(file.relative_to(directory)): sha(file)
                    for file in (directory / "outputs").rglob("*") if file.is_file()}
    (directory / "compiled-output-sha256.json").write_text(json.dumps(class_hashes, indent=2), encoding="utf-8")
    return result


def process_affinity(process: subprocess.Popen, requested: int | None) -> dict:
    if os.name != "nt":
        return {"platform": "not Windows", "requestedMask": requested}
    import ctypes
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.SetProcessAffinityMask.argtypes = (ctypes.c_void_p, ctypes.c_size_t)
    kernel.SetProcessAffinityMask.restype = ctypes.c_int
    kernel.GetProcessAffinityMask.argtypes = (ctypes.c_void_p, ctypes.POINTER(ctypes.c_size_t), ctypes.POINTER(ctypes.c_size_t))
    kernel.GetProcessAffinityMask.restype = ctypes.c_int
    handle = ctypes.c_void_p(int(process._handle))
    if requested is not None and not kernel.SetProcessAffinityMask(handle, requested):
        raise ctypes.WinError(ctypes.get_last_error())
    owned, system = ctypes.c_size_t(), ctypes.c_size_t()
    if not kernel.GetProcessAffinityMask(handle, ctypes.byref(owned), ctypes.byref(system)):
        raise ctypes.WinError(ctypes.get_last_error())
    if requested is not None and owned.value != requested:
        raise AssertionError("Owned JVM affinity differs from the requested mask")
    return {"processMask": hex(owned.value), "systemMask": hex(system.value),
            "requestedMask": None if requested is None else hex(requested)}


def prepare(directory: Path, config_source: Path) -> None:
    directory.mkdir(parents=True, exist_ok=False)
    (directory / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (directory / "server.properties").write_text(
        "server-ip=127.0.0.1\n"
        f"server-port={available_port()}\n"
        "online-mode=false\nlevel-name=world\nlevel-type=minecraft:flat\n"
        "generate-structures=false\ngamemode=creative\nforce-gamemode=true\n"
        "spawn-protection=0\nview-distance=4\nsimulation-distance=4\n"
        "max-players=1\nallow-flight=true\nspawn-monsters=false\n"
        "spawn-animals=true\nenable-status=false\n", encoding="utf-8")
    config = json.loads(config_source.read_text(encoding="utf-8-sig"))
    config["storage"]["checkpointIntervalTicks"] = 100000
    (directory / "config").mkdir()
    (directory / "config" / "viewerparrots.json").write_text(
        json.dumps(config, ensure_ascii=False, indent=2), encoding="utf-8")


def launch(java: Path, args: Path, directory: Path, scenario: str, mode: str,
           timeout: int, expected_code: int = 0, affinity: int | None = None) -> dict:
    report = directory / "crash-report.json"
    if report.exists():
        report.rename(directory / f"crash-report-before-{mode}-{time.time_ns()}.json")
    command = [str(java), f"-Dviewerparrots.qa.crash.scenario={scenario}",
               f"-Dviewerparrots.qa.crash.mode={mode}", f"@{args}"]
    started = time.monotonic()
    log = directory / f"process-{mode}.log"
    with log.open("wb") as output:
        process = subprocess.Popen(command, cwd=directory, stdout=output,
                                   stderr=subprocess.STDOUT,
                                   creationflags=(subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0))
        try:
            observed_affinity = process_affinity(process, affinity)
        except Exception:
            process.kill()
            process.wait(timeout=30)
            raise
        print(f"START {scenario} {mode} pid={process.pid}", flush=True)
        try:
            code = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=30)
            raise RuntimeError(f"Dedicated server timed out: {scenario}/{mode}; {log}")
    elapsed = round(time.monotonic() - started, 3)
    if code != expected_code:
        detail = report.read_text(encoding="utf-8") if report.exists() else log.read_text(encoding="utf-8", errors="replace")[-7000:]
        raise RuntimeError(f"Exit {code}, expected {expected_code}: {scenario}/{mode}\n{detail}")
    if expected_code == 77:
        witness = directory / "crash-witness.json"
        if not witness.is_file():
            raise AssertionError("Abrupt exit lacks durable crash witness")
        result = json.loads(witness.read_text(encoding="utf-8"))
    else:
        if not report.is_file():
            raise AssertionError(f"Missing process report: {log}")
        result = json.loads(report.read_text(encoding="utf-8"))
        if result.get("passed") is not True:
            raise AssertionError(f"Process acceptance failed: {result}")
    print(f"PASS {scenario} {mode} exit={code} seconds={elapsed}", flush=True)
    return {"pid": process.pid, "exitCode": code, "seconds": elapsed,
            "log": str(log), "affinity": observed_affinity, "observed": result}


def corrupt_storage(directory: Path, scenario: str) -> dict:
    data = directory / "world" / "data" / "viewerparrots"
    snapshot, backup, journal = (data / name for name in ("registry.json", "registry.json.bak", "journal.jsonl"))
    evidence = {"primarySha256Before": sha(snapshot), "backupSha256Before": sha(backup),
                "journalSha256Before": sha(journal)}
    (directory / "journal-before-damage.jsonl").write_bytes(journal.read_bytes())
    if scenario == "CORRUPT_SNAPSHOT_WITH_BACKUP":
        # Both JSON snapshots were independently valid before only the primary was damaged.
        json.loads(snapshot.read_text(encoding="utf-8"))
        json.loads(backup.read_text(encoding="utf-8"))
        snapshot.write_bytes(b'{"schemaVersion":1,"parrots":\xff\xfeINVALID_UTF8}\n')
        evidence["injection"] = "only registry.json corrupted with malformed UTF-8 (IOException); valid .bak and journal preserved"
    elif scenario == "CORRUPT_JOURNAL_MIDDLE":
        lines = journal.read_bytes().splitlines(keepends=True)
        assert len(lines) >= 3, "Fixture must have a non-terminal journal line"
        value = json.loads(lines[1])
        value["checksum"] = "0" * 64
        lines[1] = json.dumps(value, separators=(",", ":"), ensure_ascii=False).encode("utf-8") + b"\n"
        journal.write_bytes(b"".join(lines))
        evidence["injection"] = "complete second JSON journal entry has invalid checksum; following entries remain"
    elif scenario == "JOURNAL_TRUNCATION":
        with journal.open("ab") as output:
            output.write(b'{"schemaVersion":1,"sequence":')
        evidence["injection"] = "incomplete final journal JSON appended after all confirmed entries"
    elif scenario == "READ_ONLY_STORAGE":
        os.chmod(journal, stat.S_IREAD)
        evidence["injection"] = "OS read-only journal attribute before dedicated server startup"
    evidence["primarySha256After"] = sha(snapshot)
    evidence["journalSha256After"] = sha(journal)
    return evidence


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--args", type=Path, default=Path("build/process-crash-launch.args"))
    parser.add_argument("--base", type=Path, default=Path("build/process-crash") / time.strftime("%Y%m%d-%H%M%S"))
    parser.add_argument("--config", type=Path, default=Path("examples/viewerparrots.json"))
    parser.add_argument("--timeout", type=int, default=150)
    parser.add_argument("--affinity-mask", type=lambda value: int(value, 0), help="Owned JVM CPU mask on Windows, e.g. 0xF00")
    parser.add_argument("--scenario", choices=CRASH_SCENARIOS + STORAGE_SCENARIOS, action="append")
    parser.add_argument("--smoke", action="store_true")
    options = parser.parse_args()
    java, launch_args, config = options.java.resolve(), options.args.resolve(), options.config.resolve()
    for required in (java, launch_args, config):
        if not required.is_file():
            parser.error(f"Missing required file: {required}")
    base = options.base.resolve()
    base.mkdir(parents=True, exist_ok=True)
    launch_args = freeze_launch(launch_args, base / "binary-snapshot")
    summary_path = base / "process-matrix-results.json"
    summary = {"java": str(java), "launchArgs": str(launch_args), "launchArgsSha256": sha(launch_args),
               "startedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "scenarios": [], "passed": False}
    try:
        for scenario in (("STARTUP_SMOKE",) if options.smoke else options.scenario or CRASH_SCENARIOS + STORAGE_SCENARIOS):
            directory = base / scenario.lower()
            prepare(directory, config)
            result = {"scenario": scenario, "worldDirectory": str(directory)}
            summary["scenarios"].append(result)
            if scenario == "STARTUP_SMOKE":
                result["smoke"] = launch(java, launch_args, directory, scenario, "smoke", options.timeout, affinity=options.affinity_mask)
            elif scenario in CRASH_SCENARIOS:
                result["crash"] = launch(java, launch_args, directory, scenario, "crash", options.timeout, 77, options.affinity_mask)
                if scenario == "DURING_CHECKPOINT":
                    temporary = directory / "world" / "data" / "viewerparrots" / result["crash"]["observed"]["temporarySnapshot"]
                    json.loads(temporary.read_text(encoding="utf-8"))
                    result["forcedTemporarySnapshotSha256"] = sha(temporary)
                result["restart"] = launch(java, launch_args, directory, scenario, "verify", options.timeout, affinity=options.affinity_mask)
            elif scenario == "DISK_WRITE_FAILURE":
                result["writeFailure"] = launch(java, launch_args, directory, scenario, "crash", options.timeout, affinity=options.affinity_mask)
                for file in (directory / "world" / "data" / "viewerparrots").glob("*.tmp"):
                    os.chmod(file, stat.S_IREAD | stat.S_IWRITE)
                result["restart"] = launch(java, launch_args, directory, scenario, "verify", options.timeout, affinity=options.affinity_mask)
            else:
                result["seed"] = launch(java, launch_args, directory, scenario, "seed", options.timeout, affinity=options.affinity_mask)
                result["damage"] = corrupt_storage(directory, scenario)
                try:
                    result["restart"] = launch(java, launch_args, directory, scenario, "verify", options.timeout, affinity=options.affinity_mask)
                finally:
                    if scenario == "READ_ONLY_STORAGE":
                        os.chmod(directory / "world" / "data" / "viewerparrots" / "journal.jsonl", stat.S_IREAD | stat.S_IWRITE)
                data = directory / "world" / "data" / "viewerparrots"
                result["afterRestartSha256"] = {name: sha(data / name)
                                                 for name in ("registry.json", "registry.json.bak", "journal.jsonl")}
                if scenario in ("CORRUPT_JOURNAL_MIDDLE", "READ_ONLY_STORAGE"):
                    assert result["afterRestartSha256"]["registry.json"] == result["damage"]["primarySha256Before"], "Degraded startup must not replace confirmed registry with empty data"
                    assert result["afterRestartSha256"]["registry.json.bak"] == result["damage"]["backupSha256Before"], "Degraded startup must preserve backup"
                    (data / "journal.jsonl").write_bytes((directory / "journal-before-damage.jsonl").read_bytes())
                    result["repairedRestart"] = launch(java, launch_args, directory, scenario + "_RECOVERED", "verify",
                                                        options.timeout, affinity=options.affinity_mask)
                elif scenario == "CORRUPT_SNAPSHOT_WITH_BACKUP":
                    assert result["afterRestartSha256"]["registry.json.bak"] == result["damage"]["backupSha256Before"], "Recovery must preserve the valid backup when the primary was corrupt"
                elif scenario == "JOURNAL_TRUNCATION":
                    assert (data / "journal.jsonl").read_bytes().endswith(b"\n"), "Partial journal tail is physically repaired"
                    for line in (data / "journal.jsonl").read_text(encoding="utf-8").splitlines():
                        json.loads(line)
            result["passed"] = True
            summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
        summary["passed"] = True
        return 0
    except Exception as failure:
        summary["failure"] = str(failure)
        print(f"FAIL {failure}", flush=True)
        return 1
    finally:
        summary["finishedAt"] = time.strftime("%Y-%m-%dT%H:%M:%S%z")
        summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"REPORT {summary_path}", flush=True)


if __name__ == "__main__":
    raise SystemExit(main())
