"""Best-effort timeout evidence from the synthetic CI app, before stopping it."""
import json
import re
import subprocess

PACKAGE = "org.inkweft.app.a0.insertion"
COMMAND_TIMEOUT = 5
TEXT_LIMIT = 128 * 1024


def capture_app_timeout(serial, out, run=subprocess.run):
    if not re.fullmatch(r"emulator-[0-9]+", serial):
        raise ValueError("Timeout evidence requires the disposable CI emulator")
    prefix = ["adb", "-s", serial]
    records = []

    def command(label, args, filename=None, limit=TEXT_LIMIT):
        record = {"step": label}
        records.append(record)
        try:
            result = run(prefix + args, capture_output=True, timeout=COMMAND_TIMEOUT)
            record["returncode"] = result.returncode
            data = result.stdout or b""
            if filename:
                if filename.endswith(".png"):
                    if result.returncode or not data.startswith(b"\x89PNG\r\n\x1a\n") or len(data) > limit:
                        record["status"] = "SCREENSHOT_UNAVAILABLE"
                        return b""
                else:
                    data += b"\n" + (result.stderr or b"")
                    record["truncated"] = len(data) > limit
                (out / filename).write_bytes(data[:limit])
            return data if result.returncode == 0 else b""
        except Exception as error:
            # Evidence failure must neither replace RUNNER_TIMEOUT nor prevent cleanup.
            record["error"] = type(error).__name__
            return b""

    try:
        if command("emulator", ["shell", "getprop", "ro.kernel.qemu"]).strip() != b"1":
            records.append({"step": "capture", "status": "SKIPPED_NOT_EMULATOR"})
            return
        command("screen", ["exec-out", "screencap", "-p"], "timeout-screen.png", 8 * 1024 * 1024)
        pid = command("app-pid", ["shell", "pidof", "-s", PACKAGE]).strip()
        if not re.fullmatch(rb"[1-9][0-9]*", pid):
            records.append({"step": "app", "status": "SKIPPED_NO_APP_PID"})
            return
        pid = pid.decode("ascii")
        identity = ["shell", "run-as", PACKAGE, "cat", f"/proc/{pid}/cmdline"]
        if command("app-identity", identity).rstrip(b"\0\r\n") != PACKAGE.encode():
            records.append({"step": "app", "status": "SKIPPED_UNVERIFIED_APP_PID"})
            return
        command("app-errors", ["shell", "logcat", "-d", "-b", "main", "-b", "crash",
                               "--pid=" + pid, "-t", "200", "*:W"], "timeout-app-logcat.txt")
        # run-as retains the app's existing debug UID; never elevate or read /data/anr.
        command("app-threads", ["shell", "run-as", PACKAGE, "ps", "-T", "-p", pid,
                                "-o", "PID,TID,NAME,STAT,WCHAN"], "timeout-app-threads.txt")
        if command("app-identity-before-backtrace", identity).rstrip(b"\0\r\n") == PACKAGE.encode():
            command("app-backtrace", ["shell", "run-as", PACKAGE, "debuggerd", "-b", pid],
                    "timeout-app-backtrace.txt")
    finally:
        # Stop both the target and its fixed test package, even if any capture failed.
        for package in (PACKAGE, PACKAGE + ".test"):
            command("force-stop:" + package, ["shell", "am", "force-stop", package])
        try:
            (out / "timeout-diagnostics.json").write_text(json.dumps(records, indent=2), encoding="utf-8")
        except OSError:
            pass
