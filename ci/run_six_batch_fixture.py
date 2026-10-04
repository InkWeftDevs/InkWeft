"""Run the existing full-sized B6 fixture once, then reopen/capture without clearing it."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import time

from android_device import prepare_case

PACKAGE = "org.inkweft.app.a0.insertion"
RUNNER = "android/verification/six-batch-fixture-20261004/run-fixture.py"
PHASE_METHODS = {
    "prepare": ("SixBatchAcceptanceTest", "prepareFullSizeBaselineAndExerciseReadingStructureAndCrossBookReturn"),
    "reopen": ("SixBatchAcceptanceTest", "reopenExactlyTheSameFullSizedFixture"),
    "visual": ("SixBatchVisualTest", "captureSameFullFixtureWideAndNarrowNativeWorkspace"),
    "native_recall": ("SixBatchNativeRecallTest", "captureSameFixtureTypedRecallAndPersistentAnswers"),
    "native_recall_reopen": ("SixBatchNativeRecallTest", "reopenSameFixtureNativeRecallRecords"),
}

# The current blocker is native recall; both stages use the same full-sized library.
FOCUSED_PHASES = ("prepare", "native_recall")


def run_fixture(root, source_commit, not_run=None, run=subprocess.run, mode="full", deadline_epoch=None, now=time.time):
    if mode not in ("focused", "full"):
        raise ValueError("Unknown six-batch mode: " + mode)
    selected = list(FOCUSED_PHASES) if mode == "focused" else list(PHASE_METHODS)
    out = root / "android/build/evidence/six-batch-fixture"
    out.mkdir(parents=True, exist_ok=True)
    summary = {"status": "NOT_RUN", "mode": mode, "source_commit": source_commit, "synthetic_only": True,
               "expected": len(PHASE_METHODS), "selected_expected": len(selected), "passed": 0,
               "deadline_epoch": deadline_epoch,
               "phases": {phase: {"status": "NOT_RUN" if phase in selected else "NOT_RUN_FOCUSED"}
                          for phase in PHASE_METHODS},
               "real_device": "NOT_RUN", "human_handwriting": "NOT_RUN",
               "pixels_review": "PENDING_HUMAN_OR_VISUAL_REVIEW"}

    def save():
        (out.parent / "fixture-summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")

    def bounded_run(command, **kwargs):
        if deadline_epoch is not None:
            remaining = deadline_epoch - now()
            if remaining <= 0:
                raise subprocess.TimeoutExpired(command, 0)
            kwargs["timeout"] = min(kwargs.get("timeout", remaining), remaining)
        return run(command, **kwargs)

    def checked(command):
        return bounded_run(command, check=True, capture_output=True, text=True, timeout=180).stdout.strip()

    save()
    if not_run:
        summary["reason"] = not_run
        save()
        return 1
    try:
        if not re.fullmatch(r"[0-9a-f]{40}", source_commit):
            raise ValueError("A full frozen source commit is required")
        if checked(["git", "-C", str(root), "rev-parse", "HEAD"]) != source_commit:
            raise ValueError("Source commit differs from checked-out build")
        # Refuse to overwrite a previous attempt or clear a failed fixture on rerun.
        if any(out.iterdir()):
            raise ValueError("Fixture evidence already exists; preserve the same library and investigate")
        serial = checked(["adb", "get-serialno"])
        if not re.fullmatch(r"emulator-[0-9]+", serial):
            raise ValueError("Only the disposable CI emulator is accepted")
        adb = ["adb", "-s", serial]
        if checked(adb + ["shell", "getprop", "ro.kernel.qemu"]) != "1":
            raise ValueError("Target must report itself as an emulator")
        apks = [root / "android/app/build/outputs/apk/debug/app-debug.apk",
                root / "android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]
        hashes = [hashlib.sha256(path.read_bytes()).hexdigest() for path in apks]
        # The normal regression may have stopped before installing app tests.
        for path in apks:
            checked(adb + ["install", "-r", str(path)])
        setup = []
        try:
            prepare_case(serial, PACKAGE, setup.append, bounded_run)
        finally:
            (out / "setup.txt").write_text("\n".join(setup), encoding="utf-8")
        summary["status"] = "RUNNING"
        save()
        for phase in selected:
            timeout = min(1800, int(deadline_epoch - now() - 60)) if deadline_epoch is not None else 1800
            if timeout <= 0:
                for pending in selected:
                    if summary["phases"][pending]["status"] == "NOT_RUN":
                        summary["phases"][pending] = {"status": "NOT_RUN_TIME_BUDGET"}
                summary["status"] = "NOT_RUN_TIME_BUDGET"
                summary["reason"] = "Insufficient job budget for instrumentation and bounded evidence capture; original library retained"
                return 1
            summary["phases"][phase] = {"status": "RUNNING", "timeout_seconds": timeout}
            save()
            command = [sys.executable, str(root / RUNNER), "--serial", serial,
                             "--package", PACKAGE, "--source-commit", source_commit,
                             "--expected-app-sha256", hashes[0], "--expected-test-sha256", hashes[1],
                             "--phase", phase, "--timeout", str(timeout)]
            if deadline_epoch is not None:
                command += ["--deadline-epoch", str(int(deadline_epoch))]
            command += ["--output", str(out)]
            completed = bounded_run(command, check=False)
            receipt_path = out / f"{phase}-runner.json"
            receipt = json.loads(receipt_path.read_text()) if receipt_path.is_file() else {"status": "MISSING_RUNNER_RECEIPT"}
            summary["phases"][phase] = {"status": receipt["status"], "returncode": completed.returncode, "timeout_seconds": timeout}
            if completed.returncode != 0 or receipt["status"] != "PASS":
                summary["status"] = "FAILED"
                summary["reason"] = f"{phase} did not pass; dependent phases not executed, original library retained"
                save()
                return 1
            summary["passed"] += 1
            save()
        summary["status"] = "FOCUSED_COMPLETE_NOT_FULL" if mode == "focused" else "PASS"
        return 0
    except subprocess.TimeoutExpired as error:
        exhausted = deadline_epoch is not None and now() >= deadline_epoch
        for phase in summary["phases"].values():
            if phase["status"] == "RUNNING":
                phase["status"] = "TIMEOUT_RESULT_UNKNOWN"
            elif phase["status"] == "NOT_RUN" and exhausted:
                phase["status"] = "NOT_RUN_TIME_BUDGET"
        summary["status"] = "TIMEOUT_RESULT_UNKNOWN" if any(
            p["status"] == "TIMEOUT_RESULT_UNKNOWN" for p in summary["phases"].values()) else (
                "NOT_RUN_TIME_BUDGET" if exhausted else "NOT_RUN_SETUP_TIMEOUT")
        summary["reason"] = str(error) + "; original library retained"
        return 1
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.SubprocessError) as error:
        for phase in summary["phases"].values():
            if phase["status"] == "RUNNING":
                phase["status"] = "RUNNER_ERROR"
        summary["status"] = "FAILED" if any(p["status"] not in ("NOT_RUN", "NOT_RUN_FOCUSED") for p in summary["phases"].values()) else "NOT_RUN"
        summary["reason"] = str(error)
        return 1
    finally:
        save()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--not-run", help="Record an unavailable build/device without any device access")
    parser.add_argument("--mode", choices=("focused", "full"), default="full")
    parser.add_argument("--deadline-epoch", type=int, help="Stop starting phases before the job cleanup/upload reserve")
    args = parser.parse_args()
    raise SystemExit(run_fixture(Path(__file__).resolve().parents[1], args.source_commit, args.not_run, mode=args.mode, deadline_epoch=args.deadline_epoch))
