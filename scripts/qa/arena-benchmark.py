#!/usr/bin/env python3
"""Freeze QA-only arena code, prove physical count/abort gates, start a fresh full stand."""
from __future__ import annotations
import argparse
import datetime as dt
import importlib
import json
import os
from pathlib import Path
import shutil
import socket
import struct
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
helpers = importlib.import_module("process-crash-acceptance")
runtime_tools = importlib.import_module("disk-lifecycle-acceptance")
sys.path.insert(0, str(ROOT / "scripts/qa"))
checker = importlib.import_module("check-benchmark")
PASSWORD = "viewerparrots-arena-local-20261013"


def write(path: Path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def hashes(path: Path):
    return {file.relative_to(path).as_posix(): helpers.sha(file) for file in path.rglob("*.class")}


def freeze(base: Path, java: Path, source_runtime: Path | None = None):
    source_directory, source_manifest = runtime_tools.read_runtime(source_runtime)
    original = runtime_tools.validated_main(source_directory / "classes/java/main", source_manifest)
    runtime = base / "runtime"
    runtime.mkdir()
    for category in ("classes/java", "resources"):
        for source in ("main", "client", "gametest"):
            shutil.copytree(source_directory / category / source, runtime / category / source)
    def rewrite(value):
        return runtime_tools.runtime_rewrite(value, source_directory, source_manifest, runtime)
    (runtime / "launch.cfg").write_text(rewrite((source_directory / "launch.cfg").read_text(encoding="utf-8")), encoding="utf-8")
    for role in ("Server", "Client"):
        lines = (source_directory / f"{role}.args").read_text(encoding="utf-8").splitlines()
        if lines[0].strip() not in ("-cp", "-classpath"):
            raise ValueError(f"Runtime {role}.args must begin with a classpath flag")
        classpath = rewrite(json.loads(lines[1]))
        (runtime / f"{role}.args").write_text("-cp\n" + json.dumps(classpath) + "\n", encoding="utf-8")
    assert original == hashes(runtime / "classes/java/main"), "Production bytecode changed"
    cp = json.loads((runtime / "Client.args").read_text(encoding="utf-8").splitlines()[1])
    sources = [ROOT / "src/gametest/java/io/github/viewerparrots/gametest" / name for name in ("AcceptanceHarness.java", "AcceptanceClient.java")]
    arguments = ["-proc:none", "-encoding", "UTF-8", "-cp", cp, "-d", str(runtime / "classes/java/gametest"), *map(str, sources)]
    argfile = runtime / "arena-javac.args"
    argfile.write_text("\n".join(json.dumps(value.replace("\\", "/")) for value in arguments), encoding="utf-8")
    compiled = subprocess.run([str(java.with_name("javac.exe" if os.name == "nt" else "javac")), "@" + str(argfile)], capture_output=True, text=True, encoding="utf-8", errors="replace")
    (runtime / "arena-javac.log").write_text(compiled.stdout + compiled.stderr, encoding="utf-8")
    if compiled.returncode:
        raise RuntimeError(compiled.stdout + compiled.stderr)
    assert original == hashes(runtime / "classes/java/main"), "Production bytecode changed"
    assert original == hashes(source_directory / "classes/java/main"), "Original production bytecode changed"
    proof = {"runtime": str(runtime), "directory": str(runtime), "configuration": str(runtime / "launch.cfg"),
             "arguments": {role: str(runtime / f"{role}.args") for role in ("Server", "Client")},
             "sourceSnapshot": str(source_directory), "mainClassCount": len(original),
             "allMainClassBytesMatchFinal": True, "mainClassTreeSha256": runtime_tools.tree_sha256(original),
             "mainClassTreeSha256Algorithm": runtime_tools.TREE_ALGORITHM, "mainClassSha256": original,
             "qaSources": {str(source.relative_to(ROOT)): helpers.sha(source) for source in sources}}
    write(runtime / "manifest.json", proof)
    return runtime, proof


def rcon(command: str):
    def read_exact(sock, count):
        data = b""
        while len(data) < count:
            part = sock.recv(count - len(data))
            if not part: raise RuntimeError("RCON connection closed")
            data += part
        return data
    def packet(sock, number, kind, body):
        data = struct.pack("<ii", number, kind) + body.encode() + b"\0\0"
        sock.sendall(struct.pack("<i", len(data)) + data)
    def response(sock):
        size = struct.unpack("<i", read_exact(sock, 4))[0]
        assert 10 <= size <= 4 * 1024 * 1024
        data = read_exact(sock, size)
        number, kind = struct.unpack("<ii", data[:8])
        return number, kind, data[8:-2].decode(errors="replace")
    with socket.create_connection(("127.0.0.1", 25592), timeout=10) as sock:
        packet(sock, 1, 3, PASSWORD)
        while True:
            number, kind, _ = response(sock)
            if kind == 2:
                assert number != -1, "RCON authorization failed"
                break
        packet(sock, 2, 2, command)
        number, _, result = response(sock)
        assert number == 2
        return result


def prepare(directory: Path):
    directory.mkdir()
    (directory / "eula.txt").write_text("eula=true\n")
    (directory / "server.properties").write_text(
        "server-ip=127.0.0.1\nserver-port=25591\nonline-mode=false\nlevel-name=world\nlevel-type=minecraft:flat\n"
        "generate-structures=false\ngamemode=creative\nforce-gamemode=true\nspawn-protection=0\n"
        "view-distance=8\nsimulation-distance=8\nmax-players=1\nallow-flight=true\n"
        "spawn-monsters=false\nspawn-animals=true\nenable-status=false\nenable-rcon=true\nrcon.port=25592\n"
        f"rcon.password={PASSWORD}\n", encoding="utf-8")
    (directory / "config").mkdir()
    shutil.copyfile(ROOT / "examples/viewerparrots.json", directory / "config/viewerparrots.json")


def wait(predicate, timeout, description, processes=()):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        for process in processes:
            if process.poll() is not None: raise RuntimeError(f"Owned JVM {process.pid} exited during {description}")
        if predicate(): return
        time.sleep(0.5)
    raise RuntimeError("Timed out: " + description)


def log_text(directory):
    path = directory / "server-stdout.log"
    return path.read_text(encoding="utf-8", errors="replace") if path.exists() else ""


def registry_ready(log: str) -> bool:
    if "Реестр загружен" in log:
        return True
    for line in log.splitlines():
        if "registry_loaded" not in line:
            continue
        opening = line.find("{")
        if opening < 0:
            continue
        try:
            event = json.loads(line[opening:])
        except json.JSONDecodeError:
            continue
        if isinstance(event, dict) and event.get("event") == "registry_loaded" and event.get("status") == "READY":
            return True
    return False


def start(base: Path, mode: str, runtime: Path, java: Path, client_java: Path):
    server_dir = base / mode
    prepare(server_dir)
    client_dir = base / f"{mode}-client"
    client_dir.mkdir()
    (client_dir / "options.txt").write_text("renderDistance:2\nsimulationDistance:2\nmaxFps:30\nentityDistanceScaling:0.5\npauseOnLostFocus:false\n", encoding="utf-8")
    common = [f"-Dfabric.dli.config={runtime / 'launch.cfg'}", "-Dfile.encoding=UTF-8"]
    server_args = [str(java), "-Xmx4G", "-XX:ActiveProcessorCount=8", *common,
                   "-Dfabric.dli.env=server", "-Dfabric.dli.main=net.fabricmc.loader.impl.launch.knot.KnotServer",
                   "@" + str(runtime / "Server.args"), "net.fabricmc.devlaunchinjector.Main", "nogui"]
    client_args = [str(client_java), "-Xmx2G", "-XX:ActiveProcessorCount=4", *common,
                   "-Dfabric.dli.env=client", "-Dfabric.dli.main=net.fabricmc.loader.impl.launch.knot.KnotClient",
                   "-Dviewerparrots.qa.connect=true", "-Dviewerparrots.qa.address=127.0.0.1:25591",
                   "@" + str(runtime / "Client.args"), "net.fabricmc.devlaunchinjector.Main", "--username", "QAStreamer"]
    write(server_dir / "launch-command.json", server_args); write(client_dir / "launch-command.json", client_args)
    with (server_dir / "server-stdout.log").open("wb") as output:
        server = subprocess.Popen(server_args, cwd=server_dir, stdout=output, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)
    server_affinity = helpers.process_affinity(server, 0xFF)
    print(f"SERVER {mode} pid={server.pid} mask=0xFF path={server_dir}", flush=True)
    wait(lambda: "RCON running" in log_text(server_dir) and registry_ready(log_text(server_dir)), 150, "server READY", (server,))
    with (client_dir / "client-stdout.log").open("wb") as output:
        client = subprocess.Popen(client_args, cwd=client_dir, stdout=output, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)
    client_affinity = helpers.process_affinity(client, 0xF00)
    print(f"CLIENT {mode} pid={client.pid} mask=0xF00", flush=True)
    wait(lambda: "QAStreamer joined the game" in log_text(server_dir), 150, "actual client login", (server, client))
    transcript = []
    for command in ("gamerule doMobSpawning false", "gamerule doDaylightCycle false", "time set day", "tp QAStreamer 0.5 -60 0.5", "vp setstreamer QAStreamer"):
        transcript.append({"command": command, "response": rcon(command)})
    wait(lambda: "QAStreamer" in rcon("vp streamer") and ": true" in rcon("vp streamer"), 30, "durable streamer assignment", (server, client))
    command = "viewerparrots-qa benchmark" if mode == "full" else "viewerparrots-qa quick-guard-probe" if mode == "negative" else "viewerparrots-qa quick-benchmark"
    transcript.append({"command": command, "response": rcon(command)})
    wait(lambda: "VIEWERPARROTS_QA_BEGIN baseline run=1" in log_text(server_dir), 20, "benchmark start", (server, client))
    proof = {"mode": mode, "startedAt": dt.datetime.now().astimezone().isoformat(), "serverPid": server.pid, "clientPid": client.pid,
             "serverDirectory": str(server_dir), "clientDirectory": str(client_dir), "serverAffinity": server_affinity, "clientAffinity": client_affinity,
             "serverArgs": str(server_dir / "launch-command.json"), "clientArgs": str(client_dir / "launch-command.json"),
             "results": str(server_dir / "benchmark-results.json"), "arena": str(server_dir / "benchmark-arena.json"),
             "fullAcceptanceCandidate": mode == "full", "transcript": transcript}
    write(server_dir / "launch-proof.json", proof)
    return server, client, server_dir, proof


def stop(server, client):
    rcon("stop")
    server.wait(timeout=60)
    client.terminate(); client.wait(timeout=30)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--client-java", type=Path, required=True)
    parser.add_argument("--source-runtime", type=Path, help="Runtime directory or manifest; default artifacts/qa-runtime/current.json")
    args = parser.parse_args()
    base = args.output.resolve(); base.mkdir(parents=True, exist_ok=False)
    runtime, provenance = freeze(base, args.java.resolve(), args.source_runtime)
    server, client, directory, negative = start(base, "negative", runtime, args.java.resolve(), args.client_java.resolve())
    wait(lambda: "VIEWERPARROTS_QA_BEGIN vanilla50 run=1" in log_text(directory), 45, "negative vanilla phase", (server, client))
    negative["injection"] = {"command": "kill @e[type=minecraft:parrot,limit=1]", "response": rcon("kill @e[type=minecraft:parrot,limit=1]")}
    wait(lambda: (directory / "benchmark-failure.json").exists(), 15, "actual49-body abort", (server, client))
    failure = json.loads((directory / "benchmark-failure.json").read_text())
    assert failure["accepted"] is False and failure["loadedEntities"] == 49
    negative["expectedFailure"] = failure; write(base / "negative-count-proof.json", negative)
    stop(server, client); print("NEGATIVE PASS actual loss49 aborts before completion", flush=True)
    server, client, directory, quick = start(base, "quick", runtime, args.java.resolve(), args.client_java.resolve())
    wait(lambda: "VIEWERPARROTS_QA_COMPLETE" in log_text(directory) or (directory / "benchmark-failure.json").exists(), 390, "nine quick windows", (server, client))
    assert not (directory / "benchmark-failure.json").exists(), "Enclosed quick fixture lost a physical body"
    rows = json.loads((directory / "benchmark-results.json").read_text())
    assert len(rows) == 9 and all(row["minimumLoadedEntities"] == row["minimumWarmupEntities"] == (0 if row["scenario"] == "baseline" else 50)
                                 and row["maximumAiDisabledEntities"] == 0 for row in rows)
    assert all(row["arena"] == rows[0]["arena"] for row in rows)
    verdict = checker.evaluate(rows)
    assert verdict["passed"] is False, "Short preflight must never satisfy full acceptance gate"
    quick["passedPhysicalFixture"] = True; quick["acceptedFullBenchmark"] = False; quick["fullGateRejectsQuick"] = verdict
    write(base / "quick-fixture-proof.json", quick)
    stop(server, client); print("QUICK PASS nine identical arenas retain50 with AI; full gate rejects short duration", flush=True)
    server, client, directory, full = start(base, "full", runtime, args.java.resolve(), args.client_java.resolve())
    write(base / "full-launch-proof.json", {**full, "provenance": provenance, "negativeProof": str(base / "negative-count-proof.json"), "quickProof": str(base / "quick-fixture-proof.json")})
    print("FULL START " + json.dumps(full, ensure_ascii=False), flush=True)


if __name__ == "__main__": main()
