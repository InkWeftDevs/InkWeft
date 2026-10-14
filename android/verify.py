#!/usr/bin/env python3
"""Run a scoped Android check and keep its command, logs and result together.

Examples:
  python verify.py core --test org.inkweft.core.InkPartialHistoryTest
  python verify.py data --test org.inkweft.data.OriginalStoreTest
  python verify.py app --serial emulator-5554 --device-test org.inkweft.app.FastPencilTest
  python verify.py data --screen search
  python verify.py data --screen search --serial emulator-5554 --performance

Set JAVA_HOME (JDK 17), ANDROID_HOME and optionally GRADLE_USER_HOME normally.
The reusable Gradle daemon and build cache are preserved. Device runs require
an explicit serial and test class; the application uses a separate .verify ID.
"""
import argparse
import contextlib
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent

# Daily checks: one current source, selected existing tests, no acceptance verdict.
# The frozen three-source/four-seed plan remains the formal comparison protocol.
SCREENS = {
    "search": ("data", ["LibrarySearchTermsTest", "LibrarySearchObservationTest"],
               ["LibrarySearchRepositoryTest", "LibraryPagingTest"], ["LibraryReadPerformanceTest"]),
    "paging": ("data", ["LibrarySearchTermsTest"], ["LibraryPagingTest"], ["LibraryReadPerformanceTest"]),
    "history": ("data", ["StorageValidationTest", "OriginalStoreTest"],
                ["HistoryStorageTest", "HistoryCollectionTest", "HistoryConcurrencyTest"],
                ["HistoryCollectionBenchmarkTest", "HistoryDeletionExposureTest"]),
    "raster": ("app", ["CanvasViewportTest", "LayerHeaderProjectionTest", "InkModelsTest"],
               ["TileLocalRedrawTest", "NativeStrokeTileReuseTest", "StreamedInkRasterTest"],
               ["RasterPerformanceProbe"]),
}


def selected_tasks(args):
    tasks = []
    if args.screen:
        module = "core-domain" if args.scope == "app" else "data-local"
        tasks.append(":core-domain:test" if module == "core-domain" else ":data-local:testDebugUnitTest")
        for test in args.test:
            tasks.extend(["--tests", test])
        if args.serial:
            tasks.extend([":app:assembleDebug", ":app:assembleDebugAndroidTest"] if args.scope == "app"
                         else [":data-local:assembleDebugAndroidTest"])
        else:
            tasks.extend([":app:compileDebugKotlin", ":app:compileDebugAndroidTestKotlin"] if args.scope == "app"
                         else [":data-local:compileDebugAndroidTestKotlin"])
        return tasks
    if args.scope in ("core", "all"):
        tasks.append(":core-domain:test")
    if args.scope in ("data", "all"):
        tasks.append(":data-local:testDebugUnitTest")
    for test in args.test:
        tasks.extend(["--tests", test])
    if args.scope == "all" or args.scope == "data" and args.serial:
        tasks.append(":data-local:assembleDebugAndroidTest")
    if args.scope in ("app", "all"):
        tasks.extend([":app:assembleDebug", ":app:assembleDebugAndroidTest"])
    return tasks


def screen_methods(classes):
    methods = []
    for name in classes:
        module, tree = ("app", "java") if name.startswith("org.inkweft.app.") else ("data-local", "kotlin")
        source = ROOT / module / "src/androidTest" / tree / (name.replace(".", "/") + ".kt")
        found = re.findall(r"@Test\s+fun\s+(\w+)\s*\(", source.read_text(encoding="utf-8"))
        if not found:
            raise RuntimeError("No supported named @Test methods in screen: " + name)
        methods.extend(name + "#" + method for method in found)
    if len(set(methods)) != len(methods):
        raise RuntimeError("Duplicate screen methods")
    return methods


def check_screen_instrumentation(result, expected):
    passed, block = [], {}
    for line in result.splitlines():
        match = re.fullmatch(r"INSTRUMENTATION_STATUS: (class|test)=(.+)", line.strip())
        if match:
            block[match[1]] = match[2]
        status = re.fullmatch(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", line.strip())
        if status:
            if status[1] == "0" and set(block) == {"class", "test"}:
                passed.append(block["class"] + "#" + block["test"])
            block = {}
    count = re.search(r"OK \((\d+) tests?\)", result)
    if (not count or int(count[1]) != len(expected) or not expected
            or sorted(passed) != sorted(expected)):
        raise RuntimeError("Screen instrumentation has missing, duplicate, skipped or unexpected tests")


@contextlib.contextmanager
def build_lock():
    path = ROOT / "build" / "verification.lock"
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a+b") as handle:
        handle.write(b"\0")
        handle.flush()
        handle.seek(0)
        if os.name == "nt":
            import msvcrt
            msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            yield
        finally:
            if os.name == "nt":
                handle.seek(0)
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scope", choices=["core", "data", "app", "all"])
    parser.add_argument("--test", action="append", default=[], help="JVM test class or class.method; core/data scopes only")
    parser.add_argument("--serial", help="Exact adb serial; never auto-select a device")
    parser.add_argument("--device-test", action="append", default=[], help="Android class or class#method; data/app scopes only")
    parser.add_argument("--online", action="store_true", help="Allow Gradle to fetch missing dependencies")
    parser.add_argument("--output", type=Path, help="New evidence directory; existing directories are rejected")
    parser.add_argument("--gradle", type=Path, help="Use an existing local Gradle executable instead of downloading a wrapper distribution")
    parser.add_argument("--screen", choices=sorted(SCREENS), help="Daily scoped checks; never formal performance acceptance")
    parser.add_argument("--performance", action="store_true", help="Add unchanged area probes to a screen on an explicit emulator")
    parser.add_argument("--plan-only", action="store_true", help="Print selected work without executing or claiming a test result")
    args = parser.parse_args()
    if args.test and args.scope not in ("core", "data"):
        parser.error("--test requires core or data scope")
    if args.screen:
        scope, unit, device, performance = SCREENS[args.screen]
        if args.scope != scope or args.test or args.device_test:
            parser.error("--screen requires its matching scope and cannot override preset test selections")
        if args.serial and not re.fullmatch(r"emulator-[0-9]+", args.serial):
            parser.error("Screens accept only an explicitly selected emulator serial")
        if args.performance and not args.serial:
            parser.error("--performance requires --screen and an explicit emulator --serial")
        prefix = "org.inkweft.core." if scope == "app" else "org.inkweft.data."
        args.test = [prefix + test for test in unit]
        prefix = "org.inkweft.app." if scope == "app" else "org.inkweft.data."
        args.device_test = [prefix + test for test in device + (performance if args.performance else [])] if args.serial else []
    elif args.performance:
        parser.error("--performance requires --screen")
    if bool(args.serial) != bool(args.device_test) or args.device_test and args.scope not in ("data", "app"):
        parser.error("Device checks require data/app scope, --serial and --device-test together")
    if any(not re.fullmatch(r"[A-Za-z0-9_.$#]+", value) for value in args.test + args.device_test):
        parser.error("Test names must be class, class.method or class#method identifiers")
    if args.serial and not re.fullmatch(r"[A-Za-z0-9_.:-]+", args.serial):
        parser.error("Invalid adb serial")
    tasks = selected_tasks(args)
    gradle = args.gradle.resolve() if args.gradle else ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if not gradle.is_file():
        parser.error("Gradle executable does not exist")
    plan = {"scope": args.scope, "screen": args.screen, "gradleTasks": tasks,
            "jvmTests": args.test, "deviceTests": args.device_test, "deviceSerial": args.serial,
            "performanceRequested": args.performance, "formalAcceptance": False,
            "evidenceKind": "SCREENING_ONLY" if args.screen else "SCOPED_CHECK_ONLY"}
    if args.screen:
        plan["expectedDeviceMethods"] = screen_methods(args.device_test)
    if args.plan_only:
        print(json.dumps({**plan, "status": "NOT_RUN_PLAN_ONLY"}, ensure_ascii=False, indent=2))
        return 0
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    output = (args.output or ROOT / "build" / "verification" / f"{stamp}-{os.getpid()}").resolve()
    output.mkdir(parents=True, exist_ok=False)
    report = {**plan, "startedUtc": stamp, "commands": [], "status": "RUNNING", "artifacts": [],
              "nativeCorrectness": "NOT_RUN", "nativePerformance": "NOT_RUN"}
    screen_cleanup = []
    lock_stack = contextlib.ExitStack()

    def save():
        (output / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    def run(command, name, allowed_codes=(0,)):
        start = time.monotonic()
        with (output / f"{name}.log").open("w", encoding="utf-8") as log:
            process = subprocess.Popen(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                       text=True, encoding="utf-8", errors="replace",
                                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
            try:
                for line in process.stdout:
                    log.write(line)
                    log.flush()
                    print(line, end="", flush=True)
                code = process.wait()
            except BaseException:
                # Stop only this invocation's client; never stop another Gradle daemon.
                process.terminate()
                process.wait()
                raise
        report["commands"].append({"args": command, "exitCode": code, "seconds": round(time.monotonic() - start, 3), "log": f"{name}.log"})
        save()
        if code not in allowed_codes:
            raise RuntimeError(f"{name} failed ({code}); see {output / (name + '.log')}")
        return code

    save()
    started = time.monotonic()
    try:
        lock_stack.enter_context(build_lock())
        init = output / "application-id.init.gradle"
        init.write_text("""gradle.beforeProject { p ->
  if (p.path == ':app') p.pluginManager.withPlugin('com.android.application') {
    p.androidComponents.finalizeDsl { a ->
      a.defaultConfig.applicationId = 'org.inkweft.app.verify'
      a.defaultConfig.testApplicationId = 'org.inkweft.app.verify.test'
      a.defaultConfig.manifestPlaceholders['appLabel'] = '墨织开发验证'
    }
  }
  if (p.path == ':data-local') p.pluginManager.withPlugin('com.android.library') {
    p.androidComponents.finalizeDsl { a ->
      a.defaultConfig.testApplicationId = 'org.inkweft.data.verify.test'
    }
  }
}
""", encoding="utf-8")
        command = [str(gradle), *tasks,
                   "--daemon", "--max-workers=1", "--console=plain", "-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8",
                   "-Pkotlin.compiler.execution.strategy=in-process", "--init-script", str(init)]
        if not args.online:
            command.append("--offline")
        run(command, "gradle")
        suites = []
        for module, folder in (("core-domain", "test"), ("data-local", "testDebugUnitTest")):
            if args.scope not in ("all", "core" if module == "core-domain" else "data") and not (args.screen == "raster" and module == "core-domain"):
                continue
            for xml in sorted((ROOT / module / "build" / "test-results" / folder).glob("TEST-*.xml")):
                suite = ET.parse(xml).getroot()
                if args.test and not any(test == suite.attrib["name"] or test.startswith(suite.attrib["name"] + ".") for test in args.test):
                    continue
                suites.append({"class": suite.attrib["name"], **{key: int(suite.attrib.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")}})
                (output / xml.name).write_bytes(xml.read_bytes())
        report["jvmSuites"] = suites
        if args.screen:
            jvm_task = ":core-domain:test" if args.scope == "app" else ":data-local:testDebugUnitTest"
            task_result = re.findall(r"^> Task " + re.escape(jvm_task) + r"(?: (UP-TO-DATE|FROM-CACHE))?$",
                                     (output / "gradle.log").read_text(encoding="utf-8"), re.M)
            if len(task_result) != 1:
                raise RuntimeError("Screen JVM task did not confirm execution or validated Gradle reuse")
            report["jvmTaskOutcome"] = task_result[0] or "EXECUTED"
            if {suite["class"] for suite in suites} != set(args.test) or any(
                    suite["tests"] <= 0 or suite["failures"] or suite["errors"] or suite["skipped"] for suite in suites):
                raise RuntimeError("Screen JVM results are missing, empty, failed or skipped")
        apks = []
        if args.scope in ("app", "all") and (not args.screen or args.serial):
            apks.extend([ROOT / "app/build/outputs/apk/debug/app-debug.apk", ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"])
        if args.scope == "all" or args.scope == "data" and args.serial:
            apks.append(ROOT / "data-local/build/outputs/apk/androidTest/debug/data-local-debug-androidTest.apk")
        for apk in apks:
            with apk.open("rb") as stream:
                digest = hashlib.file_digest(stream, "sha256").hexdigest()
            report["artifacts"].append({"path": str(apk), "bytes": apk.stat().st_size, "sha256": digest})
        if args.serial:
            sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
            if not sdk:
                raise RuntimeError("ANDROID_HOME or ANDROID_SDK_ROOT is required for device checks")
            adb = str(Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb"))
            if args.scope == "app":
                runner = "org.inkweft.app.verify.test/androidx.test.runner.AndroidJUnitRunner"
            else:
                runner = "org.inkweft.data.verify.test/androidx.test.runner.AndroidJUnitRunner"
            run([adb, "-s", args.serial, "get-state"], "device")
            if args.screen:
                run([adb, "-s", args.serial, "shell", "getprop", "ro.kernel.qemu"], "emulator-identity")
                if (output / "emulator-identity.log").read_text(encoding="utf-8").strip() != "1":
                    raise RuntimeError("Screening requires a verified emulator; no APK installed")
                packages = ["org.inkweft.app.verify", "org.inkweft.app.verify.test"] if args.scope == "app" else ["org.inkweft.data.verify.test"]
                screen_cleanup = [(adb, package) for package in packages]
            for index, apk in enumerate(apks):
                run([adb, "-s", args.serial, "install", "-r", "-t", str(apk)], f"install-{index}")
            report["nativeCorrectness"] = "RUNNING"
            if args.performance:
                report["nativePerformance"] = "RUNNING"
            save()
            run([adb, "-s", args.serial, "shell", "am", "instrument", "-w", "-r", "-e", "class", ",".join(args.device_test), runner], "instrumentation")
            result = (output / "instrumentation.log").read_text(encoding="utf-8")
            if not re.search(r"OK \(\d+ tests?\)", result) or "FAILURES!!!" in result or "INSTRUMENTATION_FAILED" in result or "INSTRUMENTATION_CODE: -1" not in result:
                raise RuntimeError("Instrumentation did not confirm a complete passing run")
            if args.screen:
                check_screen_instrumentation(result, plan["expectedDeviceMethods"])
            report["deviceResult"] = "PASS"
            report["nativeCorrectness"] = "PASS"
            if args.performance:
                report["nativePerformance"] = "SCREENING_COMPLETED_NOT_ACCEPTANCE"
        report["status"] = "PASS"
    except BaseException as error:
        report["status"] = "FAILED"
        report["error"] = str(error)
        for key in ("nativeCorrectness", "nativePerformance"):
            if report[key] == "RUNNING":
                report[key] = "FAILED_OR_INCOMPLETE"
        raise
    finally:
        for adb, package in screen_cleanup:
            try:
                label = "cleanup-" + package
                run([adb, "-s", args.serial, "shell", "am", "force-stop", package], label)
                run([adb, "-s", args.serial, "shell", "ps", "-A", "-o", "NAME"], label + "-processes")
                names = (output / (label + "-processes.log")).read_text(encoding="utf-8").split()
                if any(name == package or name.startswith(package + ":") for name in names):
                    raise RuntimeError("Screening package process survived cleanup: " + package)
            except BaseException as error:
                report["status"] = "FAILED"
                report.setdefault("cleanupErrors", []).append(str(error))
        if screen_cleanup:
            report["screenProcessCleanup"] = "FAILED" if report.get("cleanupErrors") else "PASS"
        lock_stack.close()
        report["seconds"] = round(time.monotonic() - started, 3)
        save()
        print(f"Verification record: {output / 'result.json'}")
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
