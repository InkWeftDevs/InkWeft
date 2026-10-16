"""One fixed 11-method correctness run and one exact-parent production control.

No test retries, threshold changes, full fixtures, remote caches, or APK uploads.
The existing Android runner and owned-process/ACL cleanup remain unchanged.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import stat
import subprocess
import sys
import time
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PLAN = json.loads((ROOT / "ci/pan_continuity_plan.json").read_text())
PACKAGE = "org.inkweft.app.a0.insertion"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
APK = "android/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK = "android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
ADB = ["adb", "-s", "emulator-5554"]


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def selections(plan=PLAN):
    return [name + "#" + method for name in plan["app"] for method in plan["app_methods"][name]]


def validate_plan(plan=PLAN):
    assert plan["expected_app"] == 11 and len(selections(plan)) == 11
    assert len(set(selections(plan))) == 11 and plan["diagnostic_rounds"] == 1
    assert plan["room"] == [] and plan["expected_room"] == 0 and plan["mode"] == "pan"
    assert not any(plan[key] for key in ["core", "lint", "tls", "backup", "experiments"])
    assert plan["negative_control"] == selections(plan)[0]
    assert list(plan["app_methods"]) == plan["app"]
    for name in plan["app"]:
        path = ROOT / "android/app/src/androidTest/java/org/inkweft/app" / (name.rsplit(".", 1)[1] + ".kt")
        methods = re.findall(r"@Test(?:\([^)]*\))?\s+fun\s+(\w+)", path.read_text())
        assert methods == plan["app_methods"][name], "Changed method inventory: " + name


def classify(raw, selection, returncode, negative=False):
    """A JUnit assertion is evidence; crashes, timeouts and empty runs are not."""
    cls, method = selection.split("#")
    fields = lambda key: re.findall(r"^INSTRUMENTATION_STATUS: " + key + r"=(.*)$", raw, re.M)
    codes = re.findall(r"^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$", raw, re.M)
    valid = (returncode == 0 and fields("class") == [cls, cls] and fields("test") == [method, method]
             and fields("numtests") == ["1", "1"] and codes.count("1") == 1
             and len(codes) == sum(line.startswith("INSTRUMENTATION_STATUS_CODE:") for line in raw.splitlines())
             and sum(line.startswith("INSTRUMENTATION_CODE:") for line in raw.splitlines()) == 1
             and re.findall(r"^INSTRUMENTATION_CODE: (-?\d+)\s*$", raw, re.M) == ["-1"]
             and not any(word in raw for word in ["INSTRUMENTATION_FAILED", "Process crashed", "RUNNER_TIMEOUT"]))
    if not valid:
        return "INFRASTRUCTURE_OR_INCOMPLETE"
    # Test-owned sendStatus(0) evidence adds zero codes between start and completion.
    passed = (len(codes) >= 2 and codes[0] == "1" and all(code == "0" for code in codes[1:])
              and re.findall(r"^OK \((\d+) tests?\)\s*$", raw, re.M) == ["1"] and "FAILURES!!!" not in raw)
    if passed:
        return "UNEXPECTED_PASS" if negative else "PASS"
    expected = (codes == ["1", "-2"] and "FAILURES!!!" in raw
                and re.search(r"Tests run:\s*1,\s*Failures:\s*1\s*$", raw, re.M)
                and "java.lang.AssertionError: Retained ink disappeared at (130, 150)" in raw
                and "StreamedPanContinuityTest$Fixture.assertInk" in raw
                and "Suppressed:" not in raw and "MultipleFailureException" not in raw)
    return "EXPECTED_ASSERTION_FAILURE" if negative and expected else "FAIL"


def validate_manifest(xmltree):
    blocks = re.findall(r'^    E: instrumentation[^\n]*\n((?:      [^\n]*\n)*)', xmltree + '\n', re.M)
    assert len(blocks) == 1, "Expected one instrumentation element"
    for field, expected in [("name", "androidx.test.runner.AndroidJUnitRunner"), ("targetPackage", PACKAGE)]:
        found = re.findall(r'^      A: android:' + field + r'\(0x[0-9a-f]+\)="([^"\n]+)"', blocks[0], re.M)
        assert found == [expected], "Wrong instrumentation " + field


def owned(pid):
    process = Path(f"/proc/{pid}")
    return {"pid": pid, "uid": process.stat().st_uid,
            "start": (process / "stat").read_text().rsplit(")", 1)[1].split()[19]}


def command(argv, logname, limit=180, check=True, data=None, **kwargs):
    cutoff = int(os.environ["PAN_SETUP_DEADLINE" if PHASE in ["build", "emulator"] else "PAN_WORK_DEADLINE"])
    remaining = min(limit, cutoff - time.time())
    if remaining <= 0:
        raise TimeoutError("Work deadline reached before " + logname)
    started = time.time()
    with (OUT / logname).open("w") as log:
        process = subprocess.Popen([str(arg) for arg in argv], stdout=log, stderr=subprocess.STDOUT,
                                   start_new_session=True, stdin=subprocess.PIPE if data is not None else subprocess.DEVNULL, **kwargs)
        groups = json.loads((WORK / "command-groups.json").read_text()) if (WORK / "command-groups.json").exists() else []
        try:
            groups.append(owned(process.pid))
        except FileNotFoundError:
            if process.poll() is None:
                raise
        write(WORK / "command-groups.json", groups)
        try:
            process.communicate(input=data, timeout=remaining)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait(timeout=5)
            write(OUT / (logname + ".json"), {"status": "TIMEOUT", "seconds": time.time() - started})
            raise
    write(OUT / (logname + ".json"), {"returncode": process.returncode, "seconds": time.time() - started})
    if check and process.returncode:
        raise RuntimeError(logname + " failed; no retry")
    return process.returncode, (OUT / logname).read_text(errors="replace")


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, timeout=30)


def resolve_fixed_commit():
    # API publication may change commit author/timestamps, but never the reviewed tree or parent.
    fixed = git("rev-parse", "HEAD^").decode().strip()
    assert re.fullmatch(r"[0-9a-f]{40}", fixed)
    assert git("rev-parse", fixed + "^{tree}").decode().strip() == PLAN["fixed_tree"], "Wrong fixed tree"
    assert git("rev-parse", fixed + "^").decode().strip() == PLAN["baseline_commit"], "Wrong original baseline parent"
    changed = git("diff", "--name-only", PLAN["baseline_commit"], fixed).decode().splitlines()
    overlay = "android/app/src/androidTest/java/org/inkweft/app/StreamedPanContinuityTest.kt"
    assert sorted(changed) == sorted(PLAN["production_files"] + [overlay]), "Unexpected fixed source change"
    return fixed


def verify_source(commit, fixed):
    # CI helper/workflow changes are outside Android; tests are the sole baseline overlay.
    changed = git("diff", "--name-only", commit, "--", "android").decode().splitlines()
    overlay = "android/app/src/androidTest/java/org/inkweft/app/StreamedPanContinuityTest.kt"
    assert changed == ([] if commit == fixed else [overlay]), "Unexpected Android source difference"
    assert (ROOT / overlay).read_bytes() == git("show", fixed + ":" + overlay)


def build():
    validate_plan()
    assert os.environ["GITHUB_EVENT_NAME"] == "push" and os.environ["GITHUB_RUN_ATTEMPT"] == "1"
    assert os.environ["GITHUB_REF"] == "refs/heads/codex/pan-continuity-validation-20261016"
    assert git("rev-parse", "HEAD").decode().strip() == os.environ["GITHUB_SHA"]
    fixed = resolve_fixed_commit()
    verify_source(fixed, fixed)
    write(OUT / "source.json", {"workflow_commit": os.environ["GITHUB_SHA"], "fixed_commit": fixed, "plan": PLAN,
                               "test_sources": {str(path.relative_to(ROOT)): digest(path) for path in
                                (ROOT / "android/app/src/androidTest").rglob("*.kt")}})
    write(OUT / "summary.json", {"fixed": "NOT_RUN", "negative_control": "NOT_RUN"})
    (WORK / "apks").mkdir()
    base = ["./gradlew", "--no-daemon", "--max-workers=1", "--no-build-cache",
            "-Pkotlin.compiler.execution.strategy=in-process",
            "-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8"]
    env = os.environ.copy()
    env["INKWEFT_HEAD_SHA"] = fixed
    command(base + [":app:assembleDebug", ":app:assembleDebugAndroidTest"], "build-fixed.log", 1800, cwd=ROOT / "android", env=env)
    verify_source(fixed, fixed)
    shutil.copyfile(ROOT / APK, WORK / "apks/fixed.apk")
    shutil.copyfile(ROOT / TEST_APK, WORK / "apks/tests.apk")
    try:
        for path in PLAN["production_files"]:
            (ROOT / path).write_bytes(git("show", PLAN["baseline_commit"] + ":" + path))
        verify_source(PLAN["baseline_commit"], fixed)
        env["INKWEFT_HEAD_SHA"] = PLAN["baseline_commit"]
        command(base + [":app:assembleDebug"], "build-original.log", 900, cwd=ROOT / "android", env=env)
        verify_source(PLAN["baseline_commit"], fixed)
        shutil.copyfile(ROOT / APK, WORK / "apks/original.apk")
    finally:
        for path in PLAN["production_files"]:
            (ROOT / path).write_bytes(git("show", fixed + ":" + path))
        verify_source(fixed, fixed)
    receipts = {}
    for name in ["fixed", "tests", "original"]:
        path = WORK / "apks" / (name + ".apk")
        _, signature = command([SDK / "build-tools/36.0.0/apksigner", "verify", "--print-certs", path], name + "-signature.txt", 60)
        signers = re.findall(r"^Signer #\d+ certificate SHA-256 digest: (\S+)$", signature, re.M)
        assert len(signers) == 1
        _, manifest = command([SDK / "build-tools/36.0.0/aapt", "dump", "badging", path], name + "-manifest.txt", 60)
        expected = PACKAGE + (".test" if name == "tests" else "")
        assert re.search(r"^package: name='" + re.escape(expected) + "'", manifest, re.M)
        if name == "tests":
            _, xmltree = command([SDK / "build-tools/36.0.0/aapt", "dump", "xmltree", path, "AndroidManifest.xml"], "test-instrumentation-manifest.txt", 60)
            validate_manifest(xmltree)
        else:
            with zipfile.ZipFile(path) as archive:
                identity = json.loads(archive.read("assets/inkweft-build-identity.json"))
                assert identity["source_commit"] == (fixed if name == "fixed" else PLAN["baseline_commit"])
                assert identity["build_commit"] == os.environ["GITHUB_SHA"] and identity["formal"] is False
        receipts[name] = {"sha256": digest(path), "bytes": path.stat().st_size, "signer": signers[0], "package": expected}
    assert len({item["signer"] for item in receipts.values()}) == 1
    write(OUT / "apks.json", receipts)


def emulator():
    node = os.lstat("/dev/kvm")
    assert stat.S_ISCHR(node.st_mode)
    write(OUT / "kvm-identity.json", [node.st_dev, node.st_ino, node.st_rdev])
    command(["getfacl", "-pn", "/dev/kvm"], "kvm-before.acl", 15)
    if not (os.access("/dev/kvm", os.R_OK) and os.access("/dev/kvm", os.W_OK)):
        (WORK / "acl-may-have-changed").touch()
        command(["sudo", "-n", "setfacl", "--no-mask", "-m", f"u:{os.getuid()}:rw", "/dev/kvm"], "kvm-grant.log", 15)
    assert os.access("/dev/kvm", os.R_OK) and os.access("/dev/kvm", os.W_OK)
    command([SDK / "emulator/emulator", "-accel-check"], "acceleration.log", 30)
    command(["avdmanager", "create", "avd", "--name", "inkweft-pan", "--package", "system-images;android-35;aosp_atd;x86_64", "--device", "pixel_2"], "avd.log", 60, data=b"no\n")
    args = [str(SDK / "emulator/emulator"), "-avd", "inkweft-pan", "-port", "5554", "-no-window", "-no-snapshot", "-noaudio", "-no-boot-anim", "-gpu", "swiftshader", "-accel", "on", "-cores", "2", "-memory", "2048"]
    with (OUT / "emulator.log").open("w") as log:
        process = subprocess.Popen(args, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    write(WORK / "emulator.json", owned(process.pid))
    until = min(time.time() + 240, int(os.environ["PAN_SETUP_DEADLINE"]))
    while time.time() < until:
        assert process.poll() is None, "Emulator exited before boot"
        _, value = command(ADB + ["shell", "getprop", "sys.boot_completed"], "boot.log", 10, check=False)
        if value.strip() == "1":
            break
        time.sleep(2)
    else:
        raise TimeoutError("Emulator boot did not complete; native methods NOT_RUN")
    command(ADB + ["shell", "getprop"], "device-properties.txt", 15)
    for key, expected in [("ro.kernel.qemu", "1"), ("ro.build.version.sdk", "35"), ("ro.product.cpu.abi", "x86_64")]:
        _, value = command(ADB + ["shell", "getprop", key], key + ".txt", 15)
        assert value.strip() == expected
    for index, args in enumerate([["wm", "size", "1920x1200"], ["wm", "density", "240"], ["settings", "put", "system", "font_scale", "1.0"], ["input", "keyevent", "82"]]):
        command(ADB + ["shell", *args], f"device-setup-{index}.txt", 15)


def installed(name):
    path = WORK / "apks" / (name + ".apk")
    receipts = json.loads((OUT / "apks.json").read_text())
    assert digest(path) == receipts[name]["sha256"]
    command(ADB + ["install", "-r", path], "install-" + name + ".log", 180)
    _, paths = command(ADB + ["shell", "pm", "path", receipts[name]["package"]], name + "-installed-path.txt", 30)
    values = paths.strip().splitlines()
    assert len(values) == 1 and values[0].startswith("package:")
    target = WORK / (name + "-installed.apk")
    command(ADB + ["pull", values[0][8:], target], name + "-installed-pull.log", 180)
    assert digest(target) == receipts[name]["sha256"], "Installed APK bytes differ"
    target.unlink()
    write(OUT / (name + "-installed.json"), {"sha256": receipts[name]["sha256"], "verified": True})


def observe_fixed():
    cases = ROOT / "android/build/evidence"
    observed = []
    for index, selection in enumerate(selections()):
        case = cases / f"app-{index:03d}"
        actual = (case / "selection.txt").read_text() if (case / "selection.txt").exists() else None
        raw = (case / "instrumentation.txt").read_text() if (case / "instrumentation.txt").exists() else ""
        observed.append({"selection": selection, "status": classify(raw, selection, 0) if actual == selection else "NOT_RUN"})
    return observed


def methods():
    validate_plan()
    fixed = resolve_fixed_commit()
    verify_source(fixed, fixed)
    assert json.loads((OUT / "source.json").read_text())["fixed_commit"] == fixed
    cases = ROOT / "android/build/evidence"
    assert not list(cases.glob("app-*")), "Do not reuse prior method evidence"
    shutil.copyfile(WORK / "apks/fixed.apk", ROOT / APK)
    shutil.copyfile(WORK / "apks/tests.apk", ROOT / TEST_APK)
    installed("fixed")
    installed("tests")
    write(ROOT / "android-plan.json", PLAN)
    env = os.environ.copy()
    env["INKWEFT_HEAD_SHA"] = fixed
    result = None
    try:
        # The unchanged runner installs these same verified bytes again and clears each synthetic case.
        result, _ = command([sys.executable, "ci/run_android_tests.py", "--shard-index", "0", "--shard-count", "1"], "fixed-runner.log", 1800, check=False, cwd=ROOT, env=env)
    finally:
        observed = observe_fixed()
        summary = {"fixed": "PASS" if result == 0 and all(item["status"] == "PASS" for item in observed) else "FAIL_OR_INCOMPLETE",
                   "fixed_methods": observed, "negative_control": "NOT_RUN"}
        write(OUT / "summary.json", summary)
    if any(item["status"] in ["NOT_RUN", "INFRASTRUCTURE_OR_INCOMPLETE"] for item in observed):
        raise RuntimeError("Positive run incomplete; preserve evidence without pretending control ran")
    installed("original")
    from android_device import prepare_case
    setup = []
    try:
        prepare_case("emulator-5554", PACKAGE, setup.append)
    finally:
        (OUT / "negative-setup.txt").write_text("\n".join(setup))
    code = None
    try:
        code, _ = command(ADB + ["shell", "am", "instrument", "-w", "-e", "class", PLAN["negative_control"], RUNNER], "negative-instrumentation.txt", 180, check=False)
    finally:
        path = OUT / "negative-instrumentation.txt"
        raw = path.read_text(errors="replace") if path.exists() else ""
        summary["negative_control"] = classify(raw, PLAN["negative_control"], code, negative=True)
        write(OUT / "summary.json", summary)
    assert summary["fixed"] == "PASS" and summary["negative_control"] == "EXPECTED_ASSERTION_FAILURE", "See separate fixed and negative-control outcomes"


def package():
    upload = WORK / "upload"
    upload.mkdir(exist_ok=True)
    summary_path = OUT / "summary.json"
    if summary_path.exists():
        summary = json.loads(summary_path.read_text())
        if summary["fixed"] == "NOT_RUN" and (OUT / "fixed-runner.log").exists():
            summary.update(fixed="INCOMPLETE", fixed_methods=observe_fixed())
        if summary["negative_control"] == "NOT_RUN" and (OUT / "negative-instrumentation.txt").exists():
            summary["negative_control"] = "INFRASTRUCTURE_OR_INCOMPLETE"
        write(summary_path, summary)
    files = [(path, "control/" + path.name) for path in OUT.iterdir() if path.is_file() and path.suffix in [".json", ".txt", ".log", ".acl"]]
    cases = ROOT / "android/build/evidence"
    files += [(path, "fixed/" + str(path.relative_to(cases))) for pattern in ["app-summary.json", "app-*/instrumentation.txt", "app-*/selection.txt", "app-*/round.json", "app-*/setup.txt"] for path in cases.glob(pattern)]
    size = sum(path.stat().st_size for path, _ in files)
    manifest = {relative: {"bytes": path.stat().st_size, "sha256": digest(path)} for path, relative in files}
    receipt = {"evidence_bytes": size, "limit_bytes": 20 * 1024 * 1024, "complete": True}
    size += sum(len((json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode()) for value in [manifest, receipt])
    receipt["complete"] = size <= receipt["limit_bytes"]
    write(upload / "package.json", receipt)
    assert receipt["complete"], "Evidence exceeds cap; do not silently truncate raw results"
    for path, relative in files:
        destination = upload / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, destination)
    write(upload / "manifest.json", manifest)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("phase", choices=["build", "emulator", "methods", "package"])
    PHASE = parser.parse_args().phase
    WORK = Path(os.environ["KVM_ONCE"])
    OUT = WORK / "evidence"
    OUT.mkdir(parents=True, exist_ok=True)
    SDK = Path(os.environ["ANDROID_HOME"])
    try:
        globals()[PHASE]()
    except Exception as error:
        write(OUT / (PHASE + "-failure.json"), {"type": type(error).__name__, "error": str(error), "phase": PHASE})
        raise
