import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from android_plan import inventory
from run_six_batch_fixture import PACKAGE, PHASE_METHODS, RUNNER
from six_batch_scope import FOCUSED_METHODS, build_plan


class SixBatchScopeTest(unittest.TestCase):
    def test_b4_b5_are_source_checked_without_isolating_fixture_methods(self):
        plan = build_plan()
        for name in ("LayerAuthoringNativeTest", "LayerSnapshotPreviewTest", "VisiblePageExportTest", "RecallMaskUiTest",
                     "RecallDurableRecoveryTest", "RecallMaskNativeTest"):
            self.assertIn("org.inkweft.app." + name, plan["app"])
        self.assertNotIn("org.inkweft.app.RecallMaskUiTest", plan["app_methods"])
        for name in ("PageAuthoringRepositoryTest", "AuthoringPendingStoreTest",
                     "BranchReviewRepositoryTest", "BranchReviewRoundRepositoryTest", "RecallStudyRepositoryTest"):
            self.assertIn("org.inkweft.data." + name, plan["room"])
        for name in ("PageAuthoringTest", "SearchRequestVersionTest", "RecallStudyTest", "SuperMemo2PortTest"):
            self.assertIn("org.inkweft.core." + name, plan["core_classes"])
        for key, module in (("app", "app"), ("room", "data-local")):
            self.assertEqual(len(plan[key]), len(set(plan[key])))
            expected = sum(len(plan["app_methods"][name]) if name in plan["app_methods"] else inventory(module)[name]
                           for name in plan[key])
            self.assertEqual(expected, plan["expected_" + key])
        self.assertFalse(any("SixBatch" in name for name in plan["app"]))
        self.assertEqual(["prepare", "reopen", "visual", "native_recall", "native_recall_reopen"], plan["fixture"]["phases"])
        self.assertEqual(24, plan["fixture"]["native_workspace_screenshots"])
        self.assertEqual(7, plan["fixture"]["native_recall_screenshots"])
        self.assertEqual(39, plan["fixture"]["expected_screenshots"])

    def test_focused_is_explicit_small_scope_and_full_inventory_stays_complete(self):
        focused, full = build_plan("focused"), build_plan("full")
        self.assertEqual((153, 202, 5, 39), (full["expected_app"], full["expected_room"],
                         full["fixture"]["expected_methods"], full["fixture"]["expected_screenshots"]))
        self.assertEqual(3, focused["expected_app"])
        self.assertEqual(FOCUSED_METHODS, focused["app_methods"])
        self.assertEqual(set(focused["app"]), set(focused["app_methods"]))
        self.assertEqual([], focused["fixture"]["selected_phases"])
        self.assertEqual(0, focused["fixture"]["selected_screenshots"])
        self.assertEqual(list(PHASE_METHODS), full["fixture"]["selected_phases"])
        self.assertEqual("NOT_RUN_FOCUSED", focused["lint"])
        self.assertEqual("RUN_REQUIRED", full["lint"])
        for name, method in (("StudyOrganizationUiTest", "outlineEdgeScrollReachesOffscreenParentAndCancelKeepsWholeAuthorGraph"),
                             ("StudyOrganizationUiTest", "realOutlineHandleMovesWholeBranchAndSupportsUndoRedoAndCancel"),
                             ("StudyOrganizationUiTest", "workModesPreserveSelectedGraphAndResumeTheSameUnrevealedQuestion")):
            self.assertIn(method, focused["app_methods"]["org.inkweft.app." + name])
        with self.assertRaises(ValueError):
            build_plan("unknown")

    def test_workflow_uploads_only_explicit_fixture_files(self):
        workflow = (Path(__file__).resolve().parents[1] / ".github/workflows/android-six-batches.yml").read_text()
        files = [line.strip() for line in workflow.splitlines() if line.strip().startswith("android/build/evidence/six-batch-fixture/")]
        self.assertEqual(39, sum(name.endswith(".png") for name in files))
        self.assertEqual(24, sum("/visual-" in name and name.endswith(".png") for name in files))
        self.assertFalse(any("*" in name for name in files))
        self.assertNotIn("android/build/evidence/**/*.png", workflow)
        self.assertIn("python3 -m unittest discover -s android/verification/six-batch-fixture-20261004 -p 'test_*.py'", workflow)
        self.assertIn("timeout-minutes: 45", workflow)
        self.assertIn("!cancelled() && steps.build.outcome == 'success'", workflow)

    def test_existing_fixture_runner_matches_phase_methods_and_upload_whitelist(self):
        root = Path(__file__).resolve().parents[1]
        spec = importlib.util.spec_from_file_location("six_batch_existing_runner", root / RUNNER)
        runner = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(runner)
        calls = []
        missing_png = None
        pixels = b"\x89PNG\r\n\x1a\nsynthetic-host-contract-only"
        manifest = {"synthetic": True, "runId": "same-full-sized-fixture", "documentPages": [f"page-{n}" for n in range(12)],
                    "documentSha256": "b" * 64, "stressAuthoringFingerprint": "c" * 64, "nativePageCaptures": {}}
        sections = {"06-pressure-page-layers.png": "current", "07-pressure-hidden-layer.png": "hidden",
                    "08-pressure-locked-layer.png": "locked"}
        for name, index in runner.NATIVE_PAGE_CAPTURES.items():
            manifest["nativePageCaptures"][name] = {
                "runId": manifest["runId"], "sourceCommit": "a" * 40, "pageId": manifest["documentPages"][index],
                "sourcePage": index + 1, "sha256": hashlib.sha256(pixels).hexdigest(), "layers": 3,
                "documentSha256": manifest["documentSha256"], "nativeInkSha256": "e" * 64,
                "pdfTilePresent": True, "sourceFrameDrawn": True, "frameCommitted": True, "pendingRaster": False, "pendingImages": False,
                "nativeStoredStrokes": 1000, "nativeStoredPoints": 100000, "nativeVisibleStrokes": 1000,
                "authoringFingerprint": manifest["stressAuthoringFingerprint"], "continuous": index == 0,
                "baseLayerBluePixels": 20, "lockedLayerBluePixels": 20, "hiddenLayers": 1, "lockedLayers": 1,
                "currentLayer": "00000000-0000-0000-0000-000000000001", "panelAssertionsPassed": True,
                "panelSection": sections.get(name), "pressurePixelsFile": "05-layered-1000-stroke-pressure.png",
            }

        def command(command, **kwargs):
            calls.append(command)
            if command[-1] == "ro.kernel.qemu":
                stdout = "1"
            elif "path" in command:
                stdout = "package:/data/app/~~synthetic/base.apk"
            elif "sha256sum" in command:
                stdout = "b" * 64 + "  /data/app/~~synthetic/base.apk"
            elif command[-1].endswith("manifest.json"):
                stdout = json.dumps(manifest).encode()
            elif command[-1].endswith(".png"):
                if command[-1].endswith(missing_png or "no-missing-screenshot"):
                    raise subprocess.CalledProcessError(1, command)
                stdout = pixels
            elif "instrument" in command:
                stdout = "OK (1 test)\n"
            else:
                stdout = ""
            return subprocess.CompletedProcess(command, 0, stdout, "")

        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            for phase, (name, method) in PHASE_METHODS.items():
                arguments = [RUNNER, "--serial", "emulator-5554", "--package", PACKAGE,
                             "--source-commit", "a" * 40, "--phase", phase, "--output", directory]
                with patch.object(sys, "argv", arguments), patch.object(runner.subprocess, "run", side_effect=command):
                    self.assertEqual(0, runner.main(), phase)
                launched = [call for call in calls if "instrument" in call][-1]
                self.assertIn("org.inkweft.app." + name + "#" + method, launched)
                receipt = json.loads((out / f"{phase}-runner.json").read_text())
                self.assertEqual("same-full-sized-fixture", receipt["run_id"])
            self.assertFalse(any("clear" in call for call in calls))
            self.assertTrue(any("force-stop" in call for call in calls))
            workflow = (root / ".github/workflows/android-six-batches.yml").read_text()
            prefix = "android/build/evidence/six-batch-fixture/"
            whitelist = {line.strip().removeprefix(prefix) for line in workflow.splitlines() if line.strip().startswith(prefix)}
            self.assertEqual(whitelist - {"setup.txt"}, {file.name for file in out.iterdir()})
            # Even a stale local PNG cannot hide any failed extraction from this run.
            for phase, missing_png in [("prepare", name) for name in sections] + [("visual", "visual-wide-01-default-tools.png")]:
                with self.subTest(missing_png=missing_png):
                    arguments = [RUNNER, "--serial", "emulator-5554", "--package", PACKAGE,
                                 "--source-commit", "a" * 40, "--phase", phase, "--output", directory]
                    with patch.object(sys, "argv", arguments), patch.object(runner.subprocess, "run", side_effect=command):
                        self.assertEqual(1, runner.main())
                    receipt = json.loads((out / f"{phase}-runner.json").read_text())
                    self.assertEqual("PASS", receipt["instrumentation_status"])
                    self.assertEqual("INCOMPLETE_EVIDENCE", receipt["status"])
                    self.assertIn({"file": missing_png, "status": "UNAVAILABLE"}, receipt["screenshots"])
            # An available PNG with stale native proof also fails, rather than passing on its filename.
            missing_png = None
            stale_png = "05-layered-1000-stroke-pressure.png"
            manifest["nativePageCaptures"][stale_png]["sourceCommit"] = "d" * 40
            arguments = [RUNNER, "--serial", "emulator-5554", "--package", PACKAGE,
                         "--source-commit", "a" * 40, "--phase", "prepare", "--output", directory]
            with patch.object(sys, "argv", arguments), patch.object(runner.subprocess, "run", side_effect=command):
                self.assertEqual(1, runner.main())
            receipt = json.loads((out / "prepare-runner.json").read_text())
            self.assertEqual("PASS", receipt["instrumentation_status"])
            self.assertEqual("INCOMPLETE_EVIDENCE", receipt["status"])
            capture = next(item for item in receipt["screenshots"] if item["file"] == stale_png)
            self.assertEqual("FAILED_NO_NATIVE_PROOF", capture["status"])
            self.assertNotIn("native_page_proof", capture)
            self.assertEqual(pixels, (out / stale_png).read_bytes())
            # A failed screenshot remains inspectable even when an old successful proof happens to match.
            manifest["nativePageCaptures"][stale_png]["sourceCommit"] = "a" * 40
            manifest["failedNativePageCaptures"] = {stale_png: {"status": "FAILED_NO_NATIVE_PROOF"}}
            with patch.object(sys, "argv", arguments), patch.object(runner.subprocess, "run", side_effect=command):
                self.assertEqual(1, runner.main())
            receipt = json.loads((out / "prepare-runner.json").read_text())
            self.assertEqual("INCOMPLETE_EVIDENCE", receipt["status"])
            capture = next(item for item in receipt["screenshots"] if item["file"] == stale_png)
            self.assertEqual("FAILED_NO_NATIVE_PROOF", capture["status"])
            self.assertNotIn("native_page_proof", capture)
            self.assertEqual(pixels, (out / stale_png).read_bytes())


if __name__ == "__main__":
    unittest.main()
