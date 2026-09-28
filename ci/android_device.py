"""Bounded recovery for idempotent setup on the disposable CI emulator only."""
import subprocess


def prepare_case(serial, package, record, run=subprocess.run):
    if not serial.startswith("emulator-") or package != "org.inkweft.app.a0.insertion":
        raise ValueError("Isolation requires the disposable CI app and emulator")
    prefix = ["adb", "-s", serial]
    commands = [("pm", "clear", package),
                ("settings", "put", "system", "font_scale", "1.0"),
                ("wm", "size", "1920x1200"), ("wm", "density", "240")]
    for command in commands:
        args = prefix + ["shell", *command]
        result = run(args, capture_output=True, text=True, timeout=30)
        if result.returncode and any(s in result.stderr.lower() for s in ("offline", "not found")):
            record("ADB setup unavailable: " + result.stderr.strip())
            run(prefix + ["wait-for-device"], check=True, timeout=30)
            # Only setup is retried. Instrumentation failures are never hidden.
            result = run(args, capture_output=True, text=True, timeout=30)
        if result.returncode:
            raise RuntimeError("CI setup failed: " + result.stderr.strip())
