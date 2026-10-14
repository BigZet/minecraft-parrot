"""Filesystem regressions for QA overlay inputs; no Java/Minecraft process starts."""
from pathlib import Path
import importlib
import json
import os
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
sys.path.insert(0, str(SCRIPTS / "qa"))
driver = importlib.import_module("disk-lifecycle-acceptance")
arena = importlib.import_module("arena-benchmark")


class DiskLifecycleLaunchPathTest(unittest.TestCase):
    def fixture(self, root: Path, frozen: bool, count: int = 173) -> tuple[Path, Path, Path]:
        launch_directory = root / ("binary-snapshot" if frozen else "build")
        outputs = launch_directory / "outputs" if frozen else launch_directory
        main = outputs / "classes/java/main"
        gametest = outputs / "classes/java/gametest"
        resources = outputs / "resources/gametest"
        for directory in (main, gametest, resources):
            directory.mkdir(parents=True)
        # Counts deliberately differ from the old release; every file is checked.
        for number in range(count):
            (main / f"Production{number}.class").write_bytes(f"fixture-bytecode-{number}".encode())
        (resources / "fabric.mod.json").write_text(json.dumps({"entrypoints": {"main": []}}), encoding="utf-8")
        config = launch_directory / "loom-cache/launch.cfg"
        config.parent.mkdir(parents=True)
        config.write_text("commonProperties\n\tfabric.development=true\n", encoding="utf-8")
        launch = launch_directory / ("dedicated-launch.args" if frozen else "process-crash-launch.args")
        classpath = os.pathsep.join((str(main), str(gametest), str(resources)))
        arguments = ["-Dfabric.dli.config=" + str(config), "-cp", classpath, "net.fabricmc.devlaunchinjector.Main"]
        launch.write_text("\n".join(json.dumps(value) for value in arguments) + "\n", encoding="utf-8")
        source = root / "DiskLifecycleAcceptance.java"
        source.write_text("// Path-selection fixture; compiler is deliberately isolated.\n", encoding="utf-8")
        return launch, source, main

    def run_layout(self, frozen: bool) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            launch, source, original_main = self.fixture(root, frozen)
            expected_bytes = driver.fingerprint(original_main)
            overlay = root / "new-overlay"
            # Real flattening, copying, metadata and hash guards execute. Only javac
            # is replaced, because this regression concerns paths rather than Java.
            with patch.object(driver.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, "", "")):
                result, provenance = driver.compile_overlay(root / "jdk/bin/java.exe", launch, overlay, source)
            self.assertTrue(result.is_file())
            self.assertEqual(expected_bytes, driver.fingerprint(overlay / "outputs/classes/java/main"))
            self.assertEqual(expected_bytes, driver.fingerprint(original_main))
            self.assertEqual(173, provenance["mainClassCount"])
            self.assertTrue(provenance["allMainClassBytesMatchFinal"])
            self.assertEqual(driver.tree_sha256(expected_bytes), provenance["mainClassTreeSha256"])
            self.assertNotIn("inheritedFinalCommonClassTreeSha256", provenance)
            metadata = json.loads((overlay / "outputs/resources/gametest/fabric.mod.json").read_text(encoding="utf-8"))
            self.assertIn(driver.ENTRYPOINT, metadata["entrypoints"]["main"])

    def test_normal_gradle_export_resolves_actual_main_classes(self) -> None:
        self.run_layout(frozen=False)

    def test_already_frozen_export_resolves_actual_main_classes(self) -> None:
        self.run_layout(frozen=True)

    def test_qa_overlay_cannot_change_production_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            launch, source, _ = self.fixture(root, frozen=True)
            overlay = root / "new-overlay"
            def corrupt_copied_main(*args, **kwargs):
                (overlay / "outputs/classes/java/main/Production0.class").write_bytes(b"changed-by-qa")
                return subprocess.CompletedProcess([], 0, "", "")
            with patch.object(driver.subprocess, "run", side_effect=corrupt_copied_main):
                with self.assertRaisesRegex(AssertionError, "QA overlay modified production classes"):
                    driver.compile_overlay(root / "jdk/bin/java.exe", launch, overlay, source)

    def runtime(self, root: Path, count: int = 179) -> Path:
        runtime = root / "runtime-source"
        for category in ("classes/java", "resources"):
            for name in ("main", "client", "gametest"):
                (runtime / category / name).mkdir(parents=True)
        main = runtime / "classes/java/main"
        for number in range(count):
            (main / f"Production{number}.class").write_bytes(f"release-{number}".encode())
        (runtime / "resources/gametest/fabric.mod.json").write_text(
            json.dumps({"entrypoints": {"main": []}}), encoding="utf-8")
        config = runtime / "launch.cfg"
        config.write_text("commonProperties\n\tfabric.development=true\n", encoding="utf-8")
        arguments = {}
        cp = os.pathsep.join(str(runtime / category / name) for category in ("classes/java", "resources")
                             for name in ("main", "client", "gametest"))
        for role in ("Server", "Client"):
            file = runtime / f"{role}.args"
            file.write_text("-cp\n" + json.dumps(cp.replace("\\", "/")) + "\n", encoding="utf-8")
            arguments[role] = str(file)
        manifest = {"directory": str(runtime), "configuration": str(config), "arguments": arguments,
                    "mainClassCount": count, "mainClassTreeSha256": driver.tree_sha256(driver.fingerprint(main))}
        (runtime / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        return runtime

    def test_runtime_directory_and_current_manifest_resolve_same_validated_release(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runtime = self.runtime(root)
            current = root / "artifacts/qa-runtime/current.json"
            current.parent.mkdir(parents=True)
            current.write_bytes((runtime / "manifest.json").read_bytes())
            with patch.object(driver, "ROOT", root):
                self.assertEqual(driver.read_runtime(runtime), driver.read_runtime(None))
            (runtime / "classes/java/main/Production0.class").write_bytes(b"tampered")
            with self.assertRaisesRegex(ValueError, "manifest.*hash"):
                driver.read_runtime(runtime)

    def test_runtime_manifest_wrong_count_and_empty_main_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runtime = self.runtime(root)
            manifest_path = runtime / "manifest.json"
            manifest = json.loads(manifest_path.read_text())
            manifest["mainClassCount"] += 1
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "manifest.*count"):
                driver.read_runtime(runtime)
            for file in (runtime / "classes/java/main").glob("*.class"):
                file.unlink()
            with self.assertRaisesRegex(ValueError, "missing.*production|Production.*missing"):
                driver.read_runtime(runtime)

    def test_runtime_manifest_launch_is_accepted_by_disk_overlay(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runtime = self.runtime(root)
            runtime, manifest = driver.read_runtime(runtime / "manifest.json")
            args = driver.runtime_launch_args(runtime, manifest, root / "source-launch.args")
            source = root / "QA.java"
            source.write_text("// No JVM is launched by this filesystem regression.\n", encoding="utf-8")
            with patch.object(driver.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, "", "")):
                launch, proof = driver.compile_overlay(root / "jdk/bin/java.exe", args, root / "overlay", source, manifest)
            self.assertEqual(179, proof["mainClassCount"])
            frozen = [json.loads(line) for line in launch.read_text().splitlines()]
            frozen_cp = frozen[frozen.index("-cp") + 1]
            self.assertIn(str(root / "overlay/outputs/classes/java/main").replace("\\", "/"), frozen_cp.replace("\\", "/"))
            self.assertNotIn(str(runtime).replace("\\", "/"), frozen_cp.replace("\\", "/"))

    def test_manifest_digest_orders_outer_before_nested_classes(self) -> None:
        import hashlib
        files = {"pkg/Outer$Nested.class": "b" * 64, "pkg/Outer.class": "a" * 64}
        text = "\\classes\\java\\main\\pkg\\Outer.class:" + "A" * 64 + "\n"
        text += "\\classes\\java\\main\\pkg\\Outer$Nested.class:" + "B" * 64
        self.assertEqual(hashlib.sha256(text.encode()).hexdigest().upper(), driver.tree_sha256(files))

    def test_arena_freeze_checks_dynamic_complete_byte_tree(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source_runtime = self.runtime(root)
            base = root / "arena"
            base.mkdir()
            with patch.object(arena.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, "", "")):
                runtime, proof = arena.freeze(base, root / "jdk/bin/java.exe", source_runtime)
            self.assertEqual(179, proof["mainClassCount"])
            self.assertEqual(driver.fingerprint(source_runtime / "classes/java/main"),
                             driver.fingerprint(runtime / "classes/java/main"))
            self.assertEqual(driver.tree_sha256(driver.fingerprint(source_runtime / "classes/java/main")),
                             proof["mainClassTreeSha256"])
            self.assertTrue(proof["allMainClassBytesMatchFinal"])
            self.assertNotIn("all166MainClassesMatchFinal", proof)
            self.assertEqual(runtime, driver.read_runtime(runtime)[0])

    def test_arena_overlay_same_count_byte_change_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source_runtime = self.runtime(root)
            base = root / "arena"
            base.mkdir()
            def corrupt(*args, **kwargs):
                (base / "runtime/classes/java/main/Production0.class").write_bytes(b"changed")
                return subprocess.CompletedProcess([], 0, "", "")
            with patch.object(arena.subprocess, "run", side_effect=corrupt):
                with self.assertRaisesRegex(AssertionError, "Production bytecode changed"):
                    arena.freeze(base, root / "jdk/bin/java.exe", source_runtime)

    def test_ready_requires_real_structured_ready_or_legacy_receipt(self) -> None:
        ready = {"event": "registry_loaded", "status": "READY"}
        self.assertTrue(arena.registry_ready("[Server thread/INFO] (viewerparrots) " + json.dumps(ready)))
        self.assertTrue(arena.registry_ready("Реестр загружен: записей=50"))
        self.assertFalse(arena.registry_ready(json.dumps({**ready, "status": "DEGRADED"})))
        self.assertFalse(arena.registry_ready(json.dumps({"event": "other", "details": ready})))
        self.assertFalse(arena.registry_ready('registry_loaded status=READY malformed JSON'))


if __name__ == "__main__":
    unittest.main()
