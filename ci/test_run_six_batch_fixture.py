import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

from run_six_batch_fixture import FOCUSED_PHASES, PHASE_METHODS, run_fixture


class SixBatchFixtureRunnerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.out = self.root / "android/build/evidence/six-batch-fixture"
        self.commit = "a" * 40
        self.calls = []
        self.serial = "emulator-5554"
        self.failed_phase = None
        self.omit_receipt = False
        for suffix in ("debug/app-debug.apk", "androidTest/debug/app-debug-androidTest.apk"):
            path = self.root / "android/app/build/outputs/apk" / suffix
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"synthetic apk bytes")

    def command(self, command, **kwargs):
        self.calls.append(command)
        if command[0] == "git":
            stdout = self.commit
        elif command[-1] == "get-serialno":
            stdout = self.serial
        elif command[-1] == "ro.kernel.qemu":
            stdout = "1"
        elif "--phase" in command:
            phase = command[command.index("--phase") + 1]
            status = "FAILED" if phase == self.failed_phase else "PASS"
            if not self.omit_receipt:
                (self.out / f"{phase}-runner.json").write_text(json.dumps({"status": status}))
            return subprocess.CompletedProcess(command, int(status != "PASS"))
        else:
            stdout = "Success"
        return subprocess.CompletedProcess(command, 0, stdout, "")

    def summary(self):
        return json.loads((self.out.parent / "fixture-summary.json").read_text())

    def test_same_build_runs_all_phases_with_only_one_initial_clear(self):
        self.assertEqual(0, run_fixture(self.root, self.commit, run=self.command))
        phases = [call for call in self.calls if "--phase" in call]
        self.assertEqual(list(PHASE_METHODS), [call[call.index("--phase") + 1] for call in phases])
        clears = [index for index, call in enumerate(self.calls) if "clear" in call]
        self.assertEqual(1, len(clears))
        self.assertLess(clears[0], self.calls.index(phases[0]))
        for command in phases:
            self.assertEqual(hashlib.sha256(b"synthetic apk bytes").hexdigest(), command[command.index("--expected-app-sha256") + 1])
            self.assertEqual(command[command.index("--expected-app-sha256") + 1], command[command.index("--expected-test-sha256") + 1])
            self.assertEqual(self.commit, command[command.index("--source-commit") + 1])
            self.assertEqual(str(self.out), command[-1])
        self.assertEqual("PASS", self.summary()["status"])
        self.assertEqual(len(PHASE_METHODS), self.summary()["passed"])
        self.assertEqual("NOT_RUN", self.summary()["real_device"])

    def test_focused_prepare_and_recall_keep_same_sample_safety_and_never_claims_full_pass(self):
        self.assertEqual(0, run_fixture(self.root, self.commit, run=self.command, mode="focused"))
        phases = [call[call.index("--phase") + 1] for call in self.calls if "--phase" in call]
        self.assertEqual(list(FOCUSED_PHASES), phases)
        self.assertEqual(1, sum("clear" in call for call in self.calls))
        summary = self.summary()
        self.assertEqual("FOCUSED_COMPLETE_NOT_FULL", summary["status"])
        self.assertEqual((5, 2, 2), (summary["expected"], summary["selected_expected"], summary["passed"]))
        self.assertEqual("PASS", summary["phases"]["prepare"]["status"])
        for phase in PHASE_METHODS:
            self.assertEqual("PASS" if phase in FOCUSED_PHASES else "NOT_RUN_FOCUSED",
                             summary["phases"][phase]["status"])

    def test_focused_prepare_failure_retains_failure_and_omitted_stage_states(self):
        self.failed_phase = "prepare"
        self.assertEqual(1, run_fixture(self.root, self.commit, run=self.command, mode="focused"))
        summary = self.summary()
        self.assertEqual("FAILED", summary["status"])
        self.assertEqual(0, summary["passed"])
        self.assertEqual("FAILED", summary["phases"]["prepare"]["status"])
        self.assertEqual("NOT_RUN", summary["phases"]["native_recall"]["status"])
        self.assertTrue(all(summary["phases"][phase]["status"] == "NOT_RUN_FOCUSED"
                            for phase in PHASE_METHODS if phase not in FOCUSED_PHASES))

    def test_stage_timeout_is_bounded_by_remaining_job_budget(self):
        self.assertEqual(0, run_fixture(self.root, self.commit, run=self.command, mode="focused",
                                      deadline_epoch=1000, now=lambda: 877))
        command = next(call for call in self.calls if "--phase" in call)
        self.assertEqual("63", command[command.index("--timeout") + 1])
        self.assertEqual("1000", command[command.index("--deadline-epoch") + 1])
        self.assertEqual(63, self.summary()["phases"]["prepare"]["timeout_seconds"])

    def test_exhausted_budget_does_not_start_dependents_or_reseed(self):
        clock = [0]
        def command(args, **kwargs):
            result = self.command(args, **kwargs)
            if "--phase" in args:
                clock[0] = 1000
            return result
        self.assertEqual(1, run_fixture(self.root, self.commit, run=command,
                                      deadline_epoch=1000, now=lambda: clock[0]))
        summary = self.summary()
        self.assertEqual("NOT_RUN_TIME_BUDGET", summary["status"])
        self.assertEqual(1, summary["passed"])
        self.assertEqual("PASS", summary["phases"]["prepare"]["status"])
        self.assertEqual(1, sum("--phase" in call for call in self.calls))
        self.assertEqual(1, sum("clear" in call for call in self.calls))
        self.assertTrue(all(summary["phases"][phase]["status"] == "NOT_RUN_TIME_BUDGET"
                            for phase in list(PHASE_METHODS)[1:]))

    def test_no_budget_never_installs_or_clears_the_fixture(self):
        self.assertEqual(1, run_fixture(self.root, self.commit, run=self.command,
                                      deadline_epoch=1000, now=lambda: 1000))
        self.assertFalse(self.calls)
        self.assertEqual("NOT_RUN_TIME_BUDGET", self.summary()["status"])

    def test_runner_timeout_keeps_partial_evidence_and_never_runs_dependents(self):
        clock = [800]
        def command(args, **kwargs):
            if "--phase" in args:
                self.assertLessEqual(kwargs["timeout"], 200)
                (self.out / "prepare-runner.json").write_text('{"status":"RUNNING"}')
                clock[0] = 1000
                raise subprocess.TimeoutExpired(args, kwargs["timeout"])
            return self.command(args, **kwargs)
        self.assertEqual(1, run_fixture(self.root, self.commit, run=command,
                                      deadline_epoch=1000, now=lambda: clock[0]))
        self.assertEqual("TIMEOUT_RESULT_UNKNOWN", self.summary()["status"])
        self.assertEqual("TIMEOUT_RESULT_UNKNOWN", self.summary()["phases"]["prepare"]["status"])
        self.assertEqual("NOT_RUN_TIME_BUDGET", self.summary()["phases"]["reopen"]["status"])
        self.assertTrue((self.out / "prepare-runner.json").is_file())

    def test_setup_timeout_is_not_misreported_as_exhausted_job_budget(self):
        def command(args, **kwargs):
            if "get-serialno" in args:
                raise subprocess.TimeoutExpired(args, kwargs["timeout"])
            return self.command(args, **kwargs)
        self.assertEqual(1, run_fixture(self.root, self.commit, run=command,
                                      deadline_epoch=1000, now=lambda: 100))
        self.assertEqual("NOT_RUN_SETUP_TIMEOUT", self.summary()["status"])
        self.assertTrue(all(phase["status"] == "NOT_RUN" for phase in self.summary()["phases"].values()))
        self.assertFalse(any("clear" in call for call in self.calls))

    def test_focused_unavailable_device_and_budget_are_not_passes(self):
        self.assertEqual(1, run_fixture(self.root, self.commit, "Build unavailable", self.command, mode="focused"))
        self.assertFalse(self.calls)
        self.assertEqual("NOT_RUN", self.summary()["status"])
        self.assertEqual("NOT_RUN", self.summary()["phases"]["prepare"]["status"])
        self.assertEqual("NOT_RUN_FOCUSED", self.summary()["phases"]["reopen"]["status"])

    def test_failure_retains_receipt_and_never_reseeds_or_runs_dependents(self):
        self.failed_phase = "reopen"
        self.assertEqual(1, run_fixture(self.root, self.commit, run=self.command))
        summary = self.summary()
        self.assertEqual("FAILED", summary["status"])
        self.assertEqual("FAILED", summary["phases"]["reopen"]["status"])
        self.assertEqual("NOT_RUN", summary["phases"]["visual"]["status"])
        self.assertEqual(2, sum("--phase" in call for call in self.calls))
        self.assertEqual(1, sum("clear" in call for call in self.calls))
        self.assertTrue((self.out / "reopen-runner.json").is_file())

    def test_missing_evidence_fails_even_when_process_returns_zero(self):
        self.omit_receipt = True
        self.assertEqual(1, run_fixture(self.root, self.commit, run=self.command))
        self.assertEqual("MISSING_RUNNER_RECEIPT", self.summary()["phases"]["prepare"]["status"])
        self.assertEqual("NOT_RUN", self.summary()["phases"]["reopen"]["status"])

    def test_unavailable_build_records_not_run_without_any_device_access(self):
        self.assertEqual(1, run_fixture(self.root, self.commit, "Build unavailable", self.command))
        self.assertFalse(self.calls)
        self.assertEqual("NOT_RUN", self.summary()["status"])
        self.assertEqual("Build unavailable", self.summary()["reason"])

    def test_physical_device_is_rejected_before_install_or_clear(self):
        self.serial = "physical-device"
        self.assertEqual(1, run_fixture(self.root, self.commit, run=self.command))
        self.assertFalse(any("install" in call or "clear" in call for call in self.calls))
        self.assertEqual("NOT_RUN", self.summary()["status"])

    def test_previous_fixture_is_not_overwritten_or_cleared_on_retry(self):
        self.out.mkdir(parents=True)
        original = self.out / "prepare-runner.json"
        original.write_text('{"status":"TIMEOUT_RESULT_UNKNOWN"}')
        self.assertEqual(1, run_fixture(self.root, self.commit, run=self.command))
        self.assertEqual('{"status":"TIMEOUT_RESULT_UNKNOWN"}', original.read_text())
        self.assertFalse(any(call[0] == "adb" for call in self.calls))


if __name__ == "__main__":
    unittest.main()
