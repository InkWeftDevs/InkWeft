#!/usr/bin/env python3
"""Run a scoped Android check and keep its command, logs and result together.

Examples:
  python verify.py core --test org.inkweft.core.InkPartialHistoryTest
  python verify.py data --test org.inkweft.data.OriginalStoreTest
  python verify.py app --serial emulator-5554 --device-test org.inkweft.app.FastPencilTest

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
    args = parser.parse_args()
    if args.test and args.scope not in ("core", "data"):
        parser.error("--test requires core or data scope")
    if bool(args.serial) != bool(args.device_test) or args.device_test and args.scope not in ("data", "app"):
        parser.error("Device checks require data/app scope, --serial and --device-test together")
    if any(not re.fullmatch(r"[A-Za-z0-9_.$#]+", value) for value in args.test + args.device_test):
        parser.error("Test names must be class, class.method or class#method identifiers")
    if args.serial and not re.fullmatch(r"[A-Za-z0-9_.:-]+", args.serial):
        parser.error("Invalid adb serial")
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    output = (args.output or ROOT / "build" / "verification" / f"{stamp}-{os.getpid()}").resolve()
    output.mkdir(parents=True, exist_ok=False)
    report = {"scope": args.scope, "startedUtc": stamp, "commands": [], "deviceSerial": args.serial,
              "deviceTests": args.device_test, "status": "RUNNING", "artifacts": []}

    def save():
        (output / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    def run(command, name):
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
        if code:
            raise RuntimeError(f"{name} failed ({code}); see {output / (name + '.log')}")

    save()
    started = time.monotonic()
    try:
        with build_lock():
            tasks = []
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
            command = [str(ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")), *tasks,
                       "--daemon", "--max-workers=1", "-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8",
                       "-Pkotlin.compiler.execution.strategy=in-process", "--init-script", str(init)]
            if not args.online:
                command.append("--offline")
            run(command, "gradle")
            suites = []
            for module, folder in (("core-domain", "test"), ("data-local", "testDebugUnitTest")):
                if args.scope not in ("all", "core" if module == "core-domain" else "data"):
                    continue
                for xml in sorted((ROOT / module / "build" / "test-results" / folder).glob("TEST-*.xml")):
                    suite = ET.parse(xml).getroot()
                    suites.append({"class": suite.attrib["name"], **{key: int(suite.attrib.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")}})
                    (output / xml.name).write_bytes(xml.read_bytes())
            report["jvmSuites"] = suites
            apks = []
            if args.scope in ("app", "all"):
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
                for index, apk in enumerate(apks):
                    run([adb, "-s", args.serial, "install", "-r", "-t", str(apk)], f"install-{index}")
                run([adb, "-s", args.serial, "shell", "am", "instrument", "-w", "-r", "-e", "class", ",".join(args.device_test), runner], "instrumentation")
                result = (output / "instrumentation.log").read_text(encoding="utf-8")
                if not re.search(r"OK \(\d+ tests?\)", result) or "FAILURES!!!" in result or "INSTRUMENTATION_FAILED" in result or "INSTRUMENTATION_CODE: -1" not in result:
                    raise RuntimeError("Instrumentation did not confirm a complete passing run")
                report["deviceResult"] = "PASS"
            report["status"] = "PASS"
    except BaseException as error:
        report["status"] = "FAILED"
        report["error"] = str(error)
        raise
    finally:
        report["seconds"] = round(time.monotonic() - started, 3)
        save()
        print(f"Verification record: {output / 'result.json'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
