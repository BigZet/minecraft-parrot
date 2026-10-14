#!/usr/bin/env python3
"""AC13/14 real saved-world lifecycle proof, overlaying only isolated QA bytecode.

No Gradle, production output modification, synthetic old NBT, or benchmark world access.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib
import json
import os
from pathlib import Path
import subprocess
import time

helpers = importlib.import_module("process-crash-acceptance")
ENTRYPOINT = "io.github.viewerparrots.gametest.DiskLifecycleAcceptance"
ROOT = Path(__file__).resolve().parents[1]
TREE_ALGORITHM = "runtime-main-paths-sha256-v1"


def fingerprint(directory: Path) -> dict[str, str]:
    return {file.relative_to(directory).as_posix(): helpers.sha(file)
            for file in directory.rglob("*.class")}


def tree_sha256(files: dict[str, str]) -> str:
    """Existing freeze-runtime.ps1 digest, independent of the snapshot's location.

    Java binary names sort with the outer class before its $ nested classes, as
    in the existing PowerShell manifest. Paths and file digests use its original
    Windows representation even when this verifier is run on another platform.
    """
    names = sorted(files, key=lambda name: name.removesuffix(".class").casefold())
    lines = ["\\classes\\java\\main\\" + name.replace("/", "\\") + ":" + files[name].upper()
             for name in names]
    return hashlib.sha256("\n".join(lines).encode("utf-8")).hexdigest().upper()


def validated_main(directory: Path, manifest: dict | None = None) -> dict[str, str]:
    files = fingerprint(directory)
    if not files:
        raise ValueError(f"Production classes are missing: {directory}")
    if manifest is not None:
        if type(manifest.get("mainClassCount")) is not int or manifest["mainClassCount"] != len(files):
            raise ValueError("Runtime manifest class count differs from actual production files")
        expected = manifest.get("mainClassTreeSha256")
        if not isinstance(expected, str) or expected.upper() != tree_sha256(files):
            raise ValueError("Runtime manifest class hash differs from actual production bytes")
        if manifest.get("mainClassTreeSha256Algorithm", TREE_ALGORITHM) != TREE_ALGORITHM:
            raise ValueError("Unsupported runtime manifest class hash algorithm")
    return files


def read_runtime(source_runtime: Path | None) -> tuple[Path, dict]:
    selected = (source_runtime or ROOT / "artifacts/qa-runtime/current.json").resolve()
    manifest_path = selected / "manifest.json" if selected.is_dir() else selected
    manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
    if selected.is_dir() or manifest_path.name == "manifest.json":
        runtime = manifest_path.parent
    else:
        runtime = Path(manifest["directory"])
        if not runtime.is_absolute():
            runtime = manifest_path.parent / runtime
    runtime = runtime.resolve()
    validated_main(runtime / "classes/java/main", manifest)
    return runtime, manifest


def runtime_rewrite(value: str, runtime: Path, manifest: dict, destination: Path) -> str:
    for prefix in (str(runtime), str(manifest["directory"])):
        value = value.replace(prefix.replace("\\", "/"), destination.as_posix())
        value = value.replace(prefix, str(destination))
    return value


def runtime_launch_args(runtime: Path, manifest: dict, destination: Path) -> Path:
    """Turn the CP-only runtime receipt into the dedicated launch export format."""
    lines = (runtime / "Server.args").read_text(encoding="utf-8").splitlines()
    flag = lines[0].strip()
    if flag not in ("-cp", "-classpath"):
        raise ValueError("Runtime Server.args must begin with a classpath flag")
    cp = json.loads(lines[1])
    cp = runtime_rewrite(cp, runtime, manifest, runtime)
    config = destination.with_suffix(".cfg")
    config.write_text(runtime_rewrite((runtime / "launch.cfg").read_text(encoding="utf-8"), runtime, manifest, runtime), encoding="utf-8")
    values = ["-Dfabric.dli.config=" + config.as_posix(), "-Dfabric.dli.env=server",
              "-Dfabric.dli.main=net.fabricmc.loader.impl.launch.knot.KnotServer", "-cp", cp,
              "net.fabricmc.devlaunchinjector.Main", "nogui"]
    destination.write_text("\n".join(json.dumps(value) for value in values) + "\n", encoding="utf-8")
    return destination


def compile_overlay(java: Path, source_args: Path, directory: Path, source: Path,
                    source_manifest: dict | None = None) -> tuple[Path, dict]:
    launch = helpers.freeze_launch(source_args, directory)
    values = [json.loads(line) for line in launch.read_text(encoding="utf-8").splitlines()]
    cp = values[values.index("-cp") + 1]
    classes = directory / "outputs/classes/java/gametest"
    original_values = [json.loads(line) for line in (directory / "flattened.args").read_text(encoding="utf-8").splitlines()]
    original_cp = original_values[original_values.index("-cp") + 1]
    main_directories = [Path(entry) for entry in original_cp.split(os.pathsep)
                        if entry.replace("\\", "/").endswith("/classes/java/main")]
    if len(main_directories) != 1:
        raise ValueError("Original launch classpath must contain exactly one classes/java/main directory")
    original_main = main_directories[0]
    copied_main = directory / "outputs/classes/java/main"
    before = validated_main(original_main, source_manifest)
    assert fingerprint(copied_main) == before, "Frozen production classes changed"
    arguments = ["-proc:none", "-encoding", "UTF-8", "-cp", cp, "-d", str(classes), str(source)]
    javac_args = directory / "disk-javac.args"
    javac_args.write_text("\n".join(json.dumps(value.replace("\\", "/")) for value in arguments) + "\n", encoding="utf-8")
    command = [str(java.with_name("javac.exe" if os.name == "nt" else "javac")), "@" + str(javac_args)]
    completed = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace")
    (directory / "disk-javac.log").write_text(completed.stdout + completed.stderr, encoding="utf-8")
    if completed.returncode:
        raise RuntimeError("Standalone QA compile failed:\n" + completed.stdout + completed.stderr)
    metadata = directory / "outputs/resources/gametest/fabric.mod.json"
    mod = json.loads(metadata.read_text(encoding="utf-8"))
    if ENTRYPOINT not in mod["entrypoints"]["main"]:
        mod["entrypoints"]["main"].append(ENTRYPOINT)
    metadata.write_text(json.dumps(mod, indent=2) + "\n", encoding="utf-8")
    assert fingerprint(copied_main) == before, "QA overlay modified production classes"
    assert fingerprint(original_main) == before, "Original production classes changed during QA overlay"
    provenance = {"sourceLaunch": str(source_args), "frozenLaunch": str(launch),
                  "sourceLaunchSha256": helpers.sha(source_args),
                  "mainClassCount": len(before), "allMainClassBytesMatchFinal": True,
                  "mainClassTreeSha256": tree_sha256(before), "mainClassTreeSha256Algorithm": TREE_ALGORITHM,
                  "mainClassSha256": before,
                  "qaSourceSha256": helpers.sha(source), "javacLog": str(directory / "disk-javac.log")}
    (directory / "disk-provenance.json").write_text(json.dumps(provenance, indent=2), encoding="utf-8")
    return launch, provenance


def launch(java: Path, args: Path, world: Path, scenario: str, mode: str, expected: int, timeout: int) -> dict:
    command = [str(java), "-Xmx2G", "-XX:ActiveProcessorCount=4",
               f"-Dviewerparrots.qa.disk.scenario={scenario}",
               f"-Dviewerparrots.qa.disk.mode={mode}", "@" + str(args)]
    log = world / f"disk-{mode}.log"
    start = time.monotonic()
    with log.open("wb") as output:
        process = subprocess.Popen(command, cwd=world, stdout=output, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        affinity = helpers.process_affinity(process, 0xF00)
        print(f"START {scenario}/{mode} pid={process.pid} affinity={affinity}", flush=True)
        try:
            code = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            process.kill(); process.wait(timeout=30)
            raise RuntimeError(f"Owned fixture timed out; preserved {log}")
    report = world / ("disk-lifecycle-witness.json" if expected == 77 else "disk-lifecycle-report.json")
    if code != expected or not report.is_file():
        raise RuntimeError(f"Exit {code}, expected {expected}; {log}\n" + log.read_text(encoding="utf-8", errors="replace")[-9000:])
    observed = json.loads(report.read_text(encoding="utf-8"))
    assert observed.get("passed") is True, observed
    result = {"pid": process.pid, "exitCode": code, "seconds": round(time.monotonic() - start, 3),
              "affinity": affinity, "log": str(log), "report": str(report), "observed": observed}
    print(f"PASS {scenario}/{mode} pid={process.pid} exit={code}", flush=True)
    return result


def region_witness(world: Path) -> dict:
    # B=(256,256), hence vanilla entity/chunk region (8,8).
    paths = [world / "world/entities/r.8.8.mca", world / "world/region/r.8.8.mca"]
    assert all(file.is_file() and file.stat().st_size > 0 for file in paths), "Real saved B region files are missing"
    return {str(file.relative_to(world)): {"bytes": file.stat().st_size, "sha256": helpers.sha(file)} for file in paths}


def validate(run: dict, expected: int) -> None:
    report = run["observed"]
    events = report["diskLoadEvents"]
    assert len(events) == 1
    old = report["seedOriginal"]["parrots"]["disk-lifecycle"] if "seedOriginal" in report else report["restartSnapshotBeforeRecovery"]["parrots"]["disk-lifecycle"]
    event = events[0]
    assert event["uuid"] == old["entityUuid"] and event["incarnation"] == old["incarnationId"] and event["generation"] == old["generation"]
    assert event["matchesOriginal"] and event["removed"] and event["aiDisabled"] and event["age"] == 0
    assert report["physicalAfterStaleLoad"] == expected == report["authorizedAfterStaleLoad"]
    assert report["chunkBUnloadedAtEnd"] is True
    counts = report["finalResources"]
    assert counts["runtimes"] == counts["loadedIndex"] == counts["reverseIndex"] == expected
    assert all(counts[key] == 0 for key in ("recoveryJobs", "recoveryTickets", "fixtureTickets", "writerTasks", "writerQueue", "operations", "pendingReservations"))
    if expected:
        new = report["finalSnapshot"]["parrots"]["disk-lifecycle"]
        assert new["generation"] == old["generation"] + 1 and new["incarnationId"] == old["incarnationId"] and new["entityUuid"] != old["entityUuid"]
        assert all(new[key] == old[key] for key in ("viewer", "trait", "variant"))
        assert report["recoveryFailure"] == "PARROT_ENTITY_MISSING"
    else:
        assert report["beforeRemoveLifecycle"] == "UNLOADED"
        assert report["finalSnapshot"]["parrots"] == {}
        tomb = report["finalSnapshot"]["tombstones"][0]
        assert tomb["incarnationId"] == old["incarnationId"] and tomb["lastGeneration"] == old["generation"]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--java", type=Path, required=True)
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--source-args", type=Path, help="Explicit Gradle or frozen dedicated launch export")
    source.add_argument("--source-runtime", type=Path, help="Runtime directory or manifest; default artifacts/qa-runtime/current.json")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--config", type=Path, default=Path("examples/viewerparrots.json"))
    parser.add_argument("--timeout", type=int, default=240)
    args = parser.parse_args()
    base = args.output.resolve(); base.mkdir(parents=True, exist_ok=False)
    manifest = None
    if args.source_args is not None:
        source_args = args.source_args.resolve()
    else:
        runtime, manifest = read_runtime(args.source_runtime)
        source_args = runtime_launch_args(runtime, manifest, base / "source-runtime-launch.args")
    launch_args, provenance = compile_overlay(args.java.resolve(), source_args, base / "binary-snapshot",
                                              ROOT / "src/gametest/java/io/github/viewerparrots/gametest/DiskLifecycleAcceptance.java", manifest)
    result = {"passed": False, "provenance": provenance, "scenarios": {}}
    output = base / "disk-lifecycle-results.json"
    try:
        hint = base / "hint-loss"
        helpers.prepare(hint, args.config.resolve())
        seed = launch(args.java.resolve(), launch_args, hint, "HINT_LOSS", "seed", 77, args.timeout)
        saved = region_witness(hint)
        seed_primary = json.loads((hint / "world/data/viewerparrots/registry.json").read_text(encoding="utf-8"))
        assert seed_primary == seed["observed"]["durableSnapshotA"]
        verify = launch(args.java.resolve(), launch_args, hint, "HINT_LOSS", "verify", 0, args.timeout)
        validate(verify, 1)
        result["scenarios"]["AC13_HINT_LOSS_OLD_GENERATION"] = {"seed": seed, "savedRegionsBeforeRestart": saved, "verify": verify}
        output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        remove = base / "unloaded-remove"
        helpers.prepare(remove, args.config.resolve())
        removed = launch(args.java.resolve(), launch_args, remove, "UNLOADED_REMOVE", "run", 0, args.timeout)
        validate(removed, 0)
        result["scenarios"]["AC14_REMOVE_ACTUALLY_UNLOADED"] = {"run": removed, "savedRegions": region_witness(remove)}
        result["passed"] = True
    finally:
        output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"ALL PASS {output}", flush=True)


if __name__ == "__main__":
    main()
