"""Offline screening CLI/receipt tests; all Gradle and adb processes are fake."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch


REPO = Path(__file__).resolve().parents[1]
ANDROID = REPO / "android"
SPEC = importlib.util.spec_from_file_location("screen_verify", ANDROID / "verify.py")
verify = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verify)


def source_for(name, device=False):
    package, klass = name.rsplit(".", 1)
    module = "core-domain" if package.endswith("core") else (
        "app" if package.endswith("app") else "data-local")
    language = "java" if module == "app" else "kotlin"
    return Path(module) / "src" / ("androidTest" if device else "test") / language / Path(*package.split(".")) / (klass + ".kt")


def declared_methods(text):
    return re.findall(r"@Test\s+(?:fun\s+)([A-Za-z0-9_]+)\s*\(", text)


class ScreenPlanTest(unittest.TestCase):
    def plan(self, *args):
        out = io.StringIO()
        with patch.object(sys, "argv", ["verify.py", *args, "--plan-only"]), \
                patch.object(verify.subprocess, "Popen", side_effect=AssertionError("plan must not execute")), \
                patch.object(verify, "build_lock", side_effect=AssertionError("plan must not lock")), \
                contextlib.redirect_stdout(out):
            self.assertEqual(0, verify.main())
        return json.loads(out.getvalue())

    def test_all_presets_resolve_real_nonempty_kotlin_tests(self):
        for screen, (scope, unit, device, performance) in verify.SCREENS.items():
            for native, classes in [(False, unit), (True, device + performance)]:
                package = "org.inkweft." + ("app" if native and scope == "app" else "core" if scope == "app" else "data")
                for klass in classes:
                    name = package + "." + klass
                    with self.subTest(screen=screen, name=name):
                        text = (ANDROID / source_for(name, native)).read_text()
                        self.assertRegex(text, r"package\s+" + re.escape(package) + r"\b")
                        self.assertRegex(text, r"class\s+" + klass + r"\b")
                        self.assertTrue(declared_methods(text), "preset has no declared @Test")

    def test_host_screens_only_select_jvm_and_compilation(self):
        for screen, (scope, unit, _, _) in verify.SCREENS.items():
            with self.subTest(screen=screen):
                result = self.plan(scope, "--screen", screen)
                self.assertEqual("NOT_RUN_PLAN_ONLY", result["status"])
                self.assertEqual("SCREENING_ONLY", result["evidenceKind"])
                self.assertFalse(result["formalAcceptance"])
                self.assertEqual([], result["deviceTests"])
                self.assertEqual(len(unit), len(result["jvmTests"]))
                tasks = result["gradleTasks"]
                self.assertFalse(any("assemble" in task or "connected" in task for task in tasks))
                self.assertTrue(any("compileDebugAndroidTestKotlin" in task for task in tasks))

    def test_native_performance_is_opt_in_and_does_not_replace_correctness(self):
        for screen, (scope, _, device, performance) in verify.SCREENS.items():
            base = self.plan(scope, "--screen", screen, "--serial", "emulator-5554")
            extended = self.plan(scope, "--screen", screen, "--serial", "emulator-5554", "--performance")
            self.assertEqual(len(device), len(base["deviceTests"]))
            self.assertEqual(base["deviceTests"], extended["deviceTests"][:len(device)])
            self.assertEqual(len(device) + len(performance), len(extended["deviceTests"]))
            self.assertFalse(extended["formalAcceptance"])

    def test_invalid_combinations_fail_before_work(self):
        cases = [
            ["app", "--screen", "search"], ["data", "--screen", "raster"],
            ["data", "--screen", "search", "--test", "org.inkweft.data.X"],
            ["data", "--screen", "search", "--device-test", "org.inkweft.data.X"],
            ["data", "--screen", "search", "--performance"], ["data", "--performance"],
            ["data", "--screen", "history", "--serial", "physical-tablet"],
            ["app", "--screen", "raster", "--serial", "emulator-5554;other"],
            ["core", "--serial", "emulator-5554", "--device-test", "org.inkweft.core.X"],
        ]
        for args in cases:
            with self.subTest(args=args), contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
                self.plan(*args)
            self.assertEqual(2, error.exception.code)

    def test_legacy_scope_plans_keep_original_tasks_and_explicit_device_support(self):
        expected = {
            "core": [":core-domain:test"],
            "data": [":data-local:testDebugUnitTest"],
            "app": [":app:assembleDebug", ":app:assembleDebugAndroidTest"],
            "all": [":core-domain:test", ":data-local:testDebugUnitTest", ":data-local:assembleDebugAndroidTest", ":app:assembleDebug", ":app:assembleDebugAndroidTest"],
        }
        for scope, tasks in expected.items():
            self.assertEqual(tasks, self.plan(scope)["gradleTasks"])
        selected = self.plan("data", "--test", "org.inkweft.data.OriginalStoreTest")
        self.assertEqual([":data-local:testDebugUnitTest", "--tests", "org.inkweft.data.OriginalStoreTest"], selected["gradleTasks"])
        legacy = self.plan("app", "--serial", "explicit-legacy-device", "--device-test", "org.inkweft.app.FastPencilTest")
        self.assertEqual("SCOPED_CHECK_ONLY", legacy["evidenceKind"])


class FakeProcess:
    def __init__(self, text, code, interrupt=False):
        self.stdout = io.StringIO(text)
        self.returncode = code
        self.pid = 12345
        self.interrupt = interrupt

    def wait(self, *args, **kwargs):
        if self.interrupt:
            self.interrupt = False
            raise KeyboardInterrupt("synthetic interruption")
        return self.returncode

    def poll(self):
        return self.returncode

    def terminate(self):
        pass

    def kill(self):
        pass

    def communicate(self, *args, **kwargs):
        return self.stdout.read(), None


class ScreenExecutionTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name) / "android"
        self.root.mkdir()
        (self.root / ("gradlew.bat" if os.name == "nt" else "gradlew")).write_text("fake executable; Popen is mocked\n")
        self.output = self.root.parent / "evidence"
        self.calls = []
        self.locked = False
        self.cleanup_locked = []
        self.xml_mode = "valid"
        self.task_outcome = "EXECUTED"
        self.native_mode = "valid"
        self.install_failure = False
        self.cleanup_failure = False
        self.process_names = "NAME\nsynthetic.unrelated.process\n"
        self.interrupt_native = False
        self.emulator = "1\n"
        for scope, unit, device, performance in verify.SCREENS.values():
            for native, classes in [(False, unit), (True, device + performance)]:
                package = "org.inkweft." + ("app" if native and scope == "app" else "core" if scope == "app" else "data")
                for klass in classes:
                    relative = source_for(package + "." + klass, native)
                    target = self.root / relative
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(ANDROID / relative, target)
        for relative in ["app/build/outputs/apk/debug/app-debug.apk", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk", "data-local/build/outputs/apk/androidTest/debug/data-local-debug-androidTest.apk"]:
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(b"synthetic APK placeholder, never installed")

    @contextlib.contextmanager
    def lock(self):
        self.assertFalse(self.locked)
        self.locked = True
        try:
            yield
        finally:
            self.locked = False

    def process(self, command, **kwargs):
        command = list(map(str, command))
        self.calls.append(command)
        if "--tests" in command:
            names = [command[i + 1] for i, value in enumerate(command) if value == "--tests"]
            for name in names:
                module = "core-domain" if ".core." in name else "data-local"
                folder = "test" if module == "core-domain" else "testDebugUnitTest"
                target = self.root / module / "build/test-results" / folder / ("TEST-" + name + ".xml")
                target.parent.mkdir(parents=True, exist_ok=True)
                if self.xml_mode == "missing":
                    continue
                failed = int(self.xml_mode == "failed")
                skipped = int(self.xml_mode == "skipped")
                target.write_text(f'<testsuite name="{name}" tests="1" failures="{failed}" errors="0" skipped="{skipped}"><testcase classname="{name}" name="synthetic"/></testsuite>')
            task = ":core-domain:test" if any(".core." in name for name in names) else ":data-local:testDebugUnitTest"
            suffix = "" if self.task_outcome == "EXECUTED" else " " + self.task_outcome
            return FakeProcess(f"> Task {task}{suffix}\nBUILD SUCCESSFUL\n", 0)
        if command[-1] == "get-state":
            return FakeProcess("device\n", 0)
        if command[-1] == "ro.kernel.qemu":
            return FakeProcess(self.emulator, 0)
        if "install" in command:
            return FakeProcess("Failure synthetic\n" if self.install_failure else "Success\n", int(self.install_failure))
        if "instrument" in command:
            classes = command[command.index("class") + 1].split(",")
            pairs = [(name, method) for name in classes for method in declared_methods((self.root / source_for(name, True)).read_text())]
            if self.native_mode == "zero":
                pairs = []
            elif self.native_mode == "missing":
                pairs = pairs[:-1]
            elif self.native_mode == "duplicate":
                pairs[-1] = pairs[0]
            raw = ""
            for name, method in pairs:
                raw += f"INSTRUMENTATION_STATUS: class={name}\nINSTRUMENTATION_STATUS: test={method}\nINSTRUMENTATION_STATUS_CODE: 1\n"
                raw += f"INSTRUMENTATION_STATUS: class={name}\nINSTRUMENTATION_STATUS: test={method}\nINSTRUMENTATION_STATUS_CODE: 0\n"
            return FakeProcess(raw + f"OK ({len(pairs)} tests)\nINSTRUMENTATION_CODE: -1\n", 0, self.interrupt_native)
        if "force-stop" in command:
            self.cleanup_locked.append(self.locked)
            return FakeProcess("denied\n" if self.cleanup_failure else "", int(self.cleanup_failure))
        if "pidof" in command:
            return FakeProcess("", 1)
        if command[-4:] == ["ps", "-A", "-o", "NAME"]:
            return FakeProcess(self.process_names, 0)
        raise AssertionError("Unexpected subprocess: " + repr(command))

    def invoke(self, screen="paging", native=True):
        scope = verify.SCREENS[screen][0]
        argv = ["verify.py", scope, "--screen", screen, "--output", str(self.output)]
        if native:
            argv += ["--serial", "emulator-5554"]
        with patch.object(verify, "ROOT", self.root), patch.object(verify, "build_lock", self.lock), \
                patch.object(verify.subprocess, "Popen", side_effect=self.process), \
                patch.dict(os.environ, {"ANDROID_HOME": str(self.root.parent / "fake-sdk")}), \
                patch.object(sys, "argv", argv), contextlib.redirect_stdout(io.StringIO()):
            return verify.main()

    def receipt(self):
        return json.loads((self.output / "result.json").read_text())

    def test_host_success_never_claims_native_execution(self):
        self.assertEqual(0, self.invoke(native=False))
        report = self.receipt()
        self.assertEqual("PASS", report["status"])
        self.assertEqual("NOT_RUN", report["nativeCorrectness"])
        self.assertEqual("NOT_RUN", report["nativePerformance"])
        self.assertEqual([], report["artifacts"])
        self.assertEqual(1, len(self.calls))

    def test_missing_or_failed_or_skipped_jvm_result_is_not_pass(self):
        for mode in ["missing", "failed", "skipped"]:
            with self.subTest(mode=mode):
                self.output = self.root.parent / ("evidence-" + mode)
                self.xml_mode = mode
                with self.assertRaises(RuntimeError):
                    self.invoke(native=False)
                self.assertEqual("FAILED", self.receipt()["status"])

    def test_validated_gradle_reuse_is_explicit_and_skipped_task_is_rejected(self):
        for outcome in ["UP-TO-DATE", "FROM-CACHE"]:
            with self.subTest(outcome=outcome):
                self.output = self.root.parent / ("evidence-" + outcome)
                self.task_outcome = outcome
                self.assertEqual(0, self.invoke(native=False))
                self.assertEqual(outcome, self.receipt()["jvmTaskOutcome"])
        self.output = self.root.parent / "evidence-skipped-task"
        self.task_outcome = "SKIPPED"
        with self.assertRaisesRegex(RuntimeError, "JVM task"):
            self.invoke(native=False)
        self.assertEqual("FAILED", self.receipt()["status"])

    def test_native_missing_duplicate_or_zero_execution_is_not_pass(self):
        for mode in ["zero", "missing", "duplicate"]:
            with self.subTest(mode=mode):
                self.output = self.root.parent / ("evidence-" + mode)
                self.native_mode = mode
                with self.assertRaises(RuntimeError):
                    self.invoke()
                self.assertEqual("FAILED", self.receipt()["status"])
                self.assertEqual("FAILED_OR_INCOMPLETE", self.receipt()["nativeCorrectness"])
                self.assertEqual("PASS", self.receipt()["screenProcessCleanup"])

    def test_success_cleans_up_before_releasing_the_shared_lock(self):
        self.assertEqual(0, self.invoke())
        self.assertEqual([True], self.cleanup_locked)
        self.assertFalse(self.locked)
        self.assertEqual("PASS", self.receipt()["screenProcessCleanup"])
        self.assertEqual("NOT_RUN", self.receipt()["nativePerformance"])

    def test_non_emulator_is_rejected_before_install_or_cleanup(self):
        self.emulator = "0\n"
        with self.assertRaises(RuntimeError):
            self.invoke()
        self.assertFalse(any("install" in c or "force-stop" in c for c in self.calls))

    def test_install_failure_keeps_original_error_and_attempts_each_owned_cleanup(self):
        self.install_failure = self.cleanup_failure = True
        with self.assertRaisesRegex(RuntimeError, "install-0 failed"):
            self.invoke("raster")
        self.assertEqual(["org.inkweft.app.verify", "org.inkweft.app.verify.test"], [c[-1] for c in self.calls if "force-stop" in c])
        report = self.receipt()
        self.assertEqual("FAILED", report["status"])
        self.assertIn("install-0 failed", report["error"])
        self.assertEqual(2, len(report["cleanupErrors"]))

    def test_helper_residual_makes_host_fail_even_after_native_pass(self):
        self.process_names = "NAME\norg.inkweft.data.verify.test:helper\n"
        self.assertEqual(1, self.invoke())
        report = self.receipt()
        self.assertEqual("PASS", report["nativeCorrectness"])
        self.assertEqual("FAILED", report["status"])
        self.assertEqual("FAILED", report["screenProcessCleanup"])
        self.assertEqual([True], self.cleanup_locked)

    def test_cleanup_command_failure_returns_nonzero_after_native_pass(self):
        self.cleanup_failure = True
        self.assertEqual(1, self.invoke())
        self.assertEqual("FAILED", self.receipt()["status"])
        self.assertEqual("FAILED", self.receipt()["screenProcessCleanup"])

    def test_interrupted_client_does_not_promote_partial_success_and_still_cleans(self):
        self.interrupt_native = True
        with self.assertRaisesRegex(KeyboardInterrupt, "synthetic interruption"):
            self.invoke()
        self.assertEqual("FAILED", self.receipt()["status"])
        self.assertEqual("FAILED_OR_INCOMPLETE", self.receipt()["nativeCorrectness"])
        self.assertEqual("PASS", self.receipt()["screenProcessCleanup"])
        self.assertEqual([True], self.cleanup_locked)
        self.assertFalse(self.locked)


if __name__ == "__main__":
    unittest.main()
