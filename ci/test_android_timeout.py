"""Host-only checks: scoped timeout evidence never hides failure or blocks cleanup."""
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import runpy
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from android_timeout import capture_app_timeout, COMMAND_TIMEOUT, PACKAGE, TEXT_LIMIT

ROOT = Path(__file__).resolve().parents[1]


class AndroidTimeoutTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.out = Path(self.temp.name)
        self.calls = []
        self.error_at = None
        self.identity = PACKAGE.encode() + b"\0"

    def device(self, command, **kwargs):
        self.calls.append(command)
        self.assertEqual(["adb", "-s", "emulator-5554"], command[:3])
        self.assertEqual(COMMAND_TIMEOUT, kwargs["timeout"])
        if self.error_at and self.error_at in command:
            raise subprocess.TimeoutExpired(command, kwargs["timeout"])
        data = b""
        if command[-1] == "ro.kernel.qemu":
            data = b"1\n"
        elif "screencap" in command:
            data = b"\x89PNG\r\n\x1a\nsynthetic screenshot"
        elif "pidof" in command:
            data = b"123\n"
        elif "cat" in command:
            data = self.identity
        elif "logcat" in command:
            data = b"W synthetic warning\n" * TEXT_LIMIT
        return subprocess.CompletedProcess(command, 0, data, b"")

    def test_scoped_evidence_is_bounded_and_precedes_both_stops(self):
        capture_app_timeout("emulator-5554", self.out, self.device)
        screen = next(i for i, call in enumerate(self.calls) if "screencap" in call)
        errors = next(i for i, call in enumerate(self.calls) if "logcat" in call)
        trace = next(i for i, call in enumerate(self.calls) if "debuggerd" in call)
        stops = [i for i, call in enumerate(self.calls) if "force-stop" in call]
        self.assertLess(screen, errors)
        self.assertLess(trace, min(stops))
        self.assertEqual([PACKAGE, PACKAGE + ".test"], [self.calls[i][-1] for i in stops])
        self.assertIn("--pid=123", self.calls[errors])
        self.assertIn("200", self.calls[errors])
        self.assertIn("*:W", self.calls[errors])
        self.assertEqual(["run-as", PACKAGE, "debuggerd", "-b", "123"], self.calls[trace][-5:])
        self.assertEqual(TEXT_LIMIT, (self.out / "timeout-app-logcat.txt").stat().st_size)
        self.assertTrue(any(row.get("truncated") for row in self.receipt()))
        self.assertTrue((self.out / "timeout-screen.png").is_file())

    def receipt(self):
        return json.loads((self.out / "timeout-diagnostics.json").read_text())

    def test_device_and_pid_guards_never_collect_other_app_logs_or_threads(self):
        for serial in ("physical-tablet", "emulator-not-a-port", "emulator-5554;other"):
            with self.subTest(serial=serial), self.assertRaises(ValueError):
                capture_app_timeout(serial, self.out, self.device)
        self.assertFalse(self.calls)
        self.identity = b"some.other.app\0"
        capture_app_timeout("emulator-5554", self.out, self.device)
        self.assertFalse(any("logcat" in call or "ps" in call or "debuggerd" in call for call in self.calls))
        self.assertTrue(any(row.get("status") == "SKIPPED_UNVERIFIED_APP_PID" for row in self.receipt()))
        self.assertEqual(2, sum("force-stop" in call for call in self.calls))

    def test_each_diagnostic_or_cleanup_timeout_is_best_effort(self):
        for step in ("getprop", "screencap", "pidof", "cat", "logcat", "ps", "debuggerd", "force-stop"):
            with self.subTest(step=step):
                self.calls = []
                self.error_at = step
                capture_app_timeout("emulator-5554", self.out, self.device)
                self.assertEqual(2, sum("force-stop" in call for call in self.calls))
                self.assertTrue(any(row.get("error") == "TimeoutExpired" for row in self.receipt()))
        self.calls = []
        self.error_at = None
        with patch.object(Path, "write_bytes", side_effect=OSError("disk unavailable")), \
                patch.object(Path, "write_text", side_effect=OSError("disk unavailable")):
            capture_app_timeout("emulator-5554", self.out, self.device)
        self.assertEqual(2, sum("force-stop" in call for call in self.calls))

    def test_runner_keeps_timeout_failure_and_continues_after_diagnostic_failure(self):
        root = self.out
        (root / "ci").mkdir()
        script = root / "ci/run_android_tests.py"
        shutil.copyfile(ROOT / "ci/run_android_tests.py", script)
        source = root / "android/app/src/androidTest/TimeoutExample.kt"
        source.parent.mkdir(parents=True)
        source.write_text("@Test fun first() {}\n@Test fun second() {}")
        (root / "android-plan.json").write_text(json.dumps({
            "room": [], "app": ["ci.TimeoutExample"], "expected_app": 2}))
        selections = []

        def adb(command, **kwargs):
            self.assertEqual(180, kwargs["timeout"])
            if command == ["adb", "get-serialno"]:
                return "emulator-5554\n"
            selection = command[command.index("class") + 1]
            selections.append(selection)
            if len(selections) == 1:
                # Even an apparent OK in partial stdout must never make a timeout pass.
                raise subprocess.TimeoutExpired(command, 180, output=b"partial\nOK (1 test)\n")
            return "OK (1 test)\n"

        def diagnostic(serial, out):
            self.assertIn("RUNNER_TIMEOUT", (out / "instrumentation.txt").read_text())
            raise OSError("diagnostics unavailable")

        with patch.object(sys, "argv", [str(script)]), \
                patch("subprocess.check_output", side_effect=adb), \
                patch("subprocess.run", return_value=subprocess.CompletedProcess([], 0)), \
                patch("android_device.prepare_case"), \
                patch("android_timeout.capture_app_timeout", side_effect=diagnostic) as capture, \
                redirect_stdout(io.StringIO()), self.assertRaisesRegex(SystemExit, "1 failing selections"):
            runpy.run_path(str(script), run_name="__main__")
        self.assertEqual(["ci.TimeoutExample#first", "ci.TimeoutExample#second"], selections)
        capture.assert_called_once()
        summary = json.loads((root / "android/build/evidence/app-summary.json").read_text())
        self.assertEqual([selections[0]], summary["failures"])
        self.assertEqual(1, summary["passed"])

    def test_both_uploads_include_only_explicit_timeout_evidence(self):
        workflow = (ROOT / ".github/workflows/android-six-batches.yml").read_text()
        for filename in ("timeout-screen.png", "timeout-diagnostics.json", "timeout-app-logcat.txt",
                         "timeout-app-threads.txt", "timeout-app-backtrace.txt"):
            self.assertEqual(2, workflow.count("android/build/evidence/app-*/" + filename))


if __name__ == "__main__":
    unittest.main()
