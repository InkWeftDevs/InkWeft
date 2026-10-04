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
from six_batch_scope import build_plan


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
        self.assertEqual(6, plan["fixture"]["native_recall_screenshots"])
        self.assertEqual(35, plan["fixture"]["expected_screenshots"])

    def test_workflow_uploads_only_explicit_fixture_files(self):
        workflow = (Path(__file__).resolve().parents[1] / ".github/workflows/android-six-batches.yml").read_text()
        files = [line.strip() for line in workflow.splitlines() if line.strip().startswith("android/build/evidence/six-batch-fixture/")]
        self.assertEqual(35, sum(name.endswith(".png") for name in files))
        self.assertEqual(24, sum("/visual-" in name and name.endswith(".png") for name in files))
        self.assertFalse(any("*" in name for name in files))
        self.assertNotIn("android/build/evidence/**/*.png", workflow)
        self.assertIn("timeout-minutes: 45", workflow)
        self.assertIn("!cancelled() && steps.build.outcome == 'success'", workflow)

    def test_existing_fixture_runner_matches_phase_methods_and_upload_whitelist(self):
        root = Path(__file__).resolve().parents[1]
        spec = importlib.util.spec_from_file_location("six_batch_existing_runner", root / RUNNER)
        runner = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(runner)
        calls = []
        missing_png = None

        def command(command, **kwargs):
            calls.append(command)
            if command[-1] == "ro.kernel.qemu":
                stdout = "1"
            elif "path" in command:
                stdout = "package:/data/app/~~synthetic/base.apk"
            elif "sha256sum" in command:
                stdout = "b" * 64 + "  /data/app/~~synthetic/base.apk"
            elif command[-1].endswith("manifest.json"):
                stdout = json.dumps({"synthetic": True, "runId": "same-full-sized-fixture"}).encode()
            elif command[-1].endswith(".png"):
                if command[-1].endswith(missing_png or "no-missing-screenshot"):
                    raise subprocess.CalledProcessError(1, command)
                stdout = b"\x89PNG\r\n\x1a\nsynthetic-mock"
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
            # A stale local PNG must not hide a failed extraction from this run.
            missing_png = "visual-wide-01-default-tools.png"
            arguments = [RUNNER, "--serial", "emulator-5554", "--package", PACKAGE,
                         "--source-commit", "a" * 40, "--phase", "visual", "--output", directory]
            with patch.object(sys, "argv", arguments), patch.object(runner.subprocess, "run", side_effect=command):
                self.assertEqual(1, runner.main())
            receipt = json.loads((out / "visual-runner.json").read_text())
            self.assertEqual("PASS", receipt["instrumentation_status"])
            self.assertEqual("INCOMPLETE_EVIDENCE", receipt["status"])
            self.assertTrue(any(item.get("status") == "UNAVAILABLE" for item in receipt["screenshots"]))


if __name__ == "__main__":
    unittest.main()
