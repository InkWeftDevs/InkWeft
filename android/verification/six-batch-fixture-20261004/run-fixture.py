#!/usr/bin/env python3
"""Run only on an already-provisioned, empty, isolated Android emulator; never wipe or install."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time


NATIVE_PAGE_CAPTURES = {
    "01-twelve-page-material.png": 0,
    "04-reopened-same-material.png": 0,
    "05-layered-1000-stroke-pressure.png": 11,
    "06-pressure-page-layers.png": 11,
    "07-pressure-hidden-layer.png": 11,
    "08-pressure-locked-layer.png": 11,
    "native-recall-reopen-01-material-page.png": 0,
}


def checked_native_capture(manifest, name, pixels, source_commit):
    """A PNG header/filename alone is not proof of a rendered, correctly bound source page."""
    if name not in NATIVE_PAGE_CAPTURES:
        return None
    if name in manifest.get("failedNativePageCaptures", {}):
        raise ValueError("Failed diagnostic capture is not native source-frame proof")
    index = NATIVE_PAGE_CAPTURES[name]
    proof = manifest.get("nativePageCaptures", {}).get(name, {})
    expected = {"runId": manifest["runId"], "sourceCommit": source_commit,
                "pageId": manifest["documentPages"][index], "sourcePage": index + 1,
                "sha256": hashlib.sha256(pixels).hexdigest()}
    if index == 11:
        expected["layers"] = 3
    else:
        expected["continuous"] = name != "native-recall-reopen-01-material-page.png"
    if name == "native-recall-reopen-01-material-page.png":
        capture = manifest.get("nativeRecallReopenCapture", {})
        if manifest.get("nativeRecallReopen") != "PASS" or any(capture.get(key) != value for key, value in {
                "file": name, "sha256": expected["sha256"], "verifiedRecallRecords": 28,
                "proves": "REOPENED_MATERIAL_PAGE_ONLY"}.items()):
            raise ValueError("Cold-reopen material capture lacks its separate persisted-record verification")
    sections = {"06-pressure-page-layers.png": "current", "07-pressure-hidden-layer.png": "hidden",
                "08-pressure-locked-layer.png": "locked"}
    if name in sections:
        expected.update(hiddenLayers=1, lockedLayers=1, currentLayer="00000000-0000-0000-0000-000000000001",
                        panelSection=sections[name], authoringFingerprint=manifest["stressAuthoringFingerprint"],
                        panelAssertionsPassed=True, pressurePixelsFile="05-layered-1000-stroke-pressure.png")
    else:
        if not re.fullmatch(r"[0-9a-f]{64}", proof.get("nativeInkSha256", "")):
            raise ValueError("Missing full native ink payload fingerprint")
        expected.update(documentSha256=manifest["documentSha256"], pdfTilePresent=True,
                        sourceFrameDrawn=True, frameCommitted=True, pendingRaster=False, pendingImages=False)
    if name == "05-layered-1000-stroke-pressure.png":
        expected.update(nativeStoredStrokes=1000, nativeStoredPoints=100000, nativeVisibleStrokes=1000,
                        authoringFingerprint=manifest["stressAuthoringFingerprint"], continuous=False)
        if min(proof.get("baseLayerBluePixels", 0), proof.get("lockedLayerBluePixels", 0)) < 20:
            raise ValueError("Pressure screenshot lacks actual base/locked-layer ink pixel proof")
    if any(proof.get(key) != value for key, value in expected.items()):
        raise ValueError("Missing, stale, or incomplete native source-frame proof for " + name)
    return proof


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", default="org.inkweft.app.a0")
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--expected-app-sha256")
    parser.add_argument("--expected-test-sha256")
    parser.add_argument("--phase", choices=("prepare", "reopen", "visual", "native_recall", "native_recall_reopen"), required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--timeout", type=int, default=1800)
    parser.add_argument("--deadline-epoch", type=int, help="Bound preflight and evidence reads before job cleanup")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-[0-9]+", args.serial):
        parser.error("Only an explicitly named emulator serial is accepted")
    if not re.fullmatch(r"org\.inkweft\.app\.a0(?:\.[a-z]+)?", args.package):
        parser.error("Unexpected application package")
    if not re.fullmatch(r"[0-9a-f]{40}", args.source_commit):
        parser.error("A full frozen source commit is required")
    for value in (args.expected_app_sha256, args.expected_test_sha256):
        if value is not None and not re.fullmatch(r"[0-9a-f]{64}", value):
            parser.error("Expected APK identities must be complete lowercase SHA-256 values")
    adb = ["adb", "-s", args.serial]
    def run(parts, **kwargs):
        if args.deadline_epoch is not None:
            remaining = args.deadline_epoch - time.time()
            if remaining <= 0:
                raise subprocess.TimeoutExpired(adb + parts, 0)
            kwargs["timeout"] = min(kwargs.get("timeout", 180), remaining)
        return subprocess.run(adb + parts, check=True, capture_output=True, **kwargs)
    if run(["shell", "getprop", "ro.kernel.qemu"], text=True).stdout.strip() != "1":
        parser.error("The selected target does not report itself as an emulator")
    def installed_identity(package, expected):
        paths = run(["shell", "pm", "path", package], text=True).stdout.strip().splitlines()
        if len(paths) != 1 or not paths[0].startswith("package:"):
            parser.error("Exactly one installed monolithic APK is required for " + package)
        path = paths[0][len("package:"):].strip()
        if not re.fullmatch(r"/[A-Za-z0-9/_.+=~-]+\.apk", path):
            parser.error("Unexpected installed APK path")
        fields = run(["shell", "sha256sum", path], text=True).stdout.split()
        value = fields[0] if fields else ""
        if not re.fullmatch(r"[0-9a-f]{64}", value) or expected is not None and value != expected:
            parser.error("Installed APK hash mismatched for " + package)
        return {"package": package, "path": path, "sha256": value}
    identity = {"app": installed_identity(args.package, args.expected_app_sha256),
                "test": installed_identity(args.package + ".test", args.expected_test_sha256)}
    if args.phase != "prepare":
        try:
            original = json.loads((args.output / "prepare-runner.json").read_text())["installed_apks"]
        except (OSError, ValueError, KeyError):
            parser.error("Cold reopen requires the original prepare receipt in the same output directory")
        if any(original[k]["package"] != identity[k]["package"] or original[k]["sha256"] != identity[k]["sha256"] for k in ("app", "test")):
            parser.error("Reopen must use exactly the same installed app and test APK hashes")
    args.output.mkdir(parents=True, exist_ok=True)
    evidence = {"phase": args.phase, "source_commit": args.source_commit,
                "status": "RUNNING", "synthetic_only": True, "installed_apks": identity,
                "expected_hashes_supplied": bool(args.expected_app_sha256 and args.expected_test_sha256),
                "real_device": "NOT_RUN", "human_handwriting": "NOT_RUN"}
    result_path = args.output / f"{args.phase}-runner.json"
    result_path.write_text(json.dumps(evidence, indent=2))
    method = {"prepare": "prepareFullSizeBaselineAndExerciseReadingStructureAndCrossBookReturn",
              "reopen": "reopenExactlyTheSameFullSizedFixture", "visual": "captureSameFullFixtureWideAndNarrowNativeWorkspace",
              "native_recall": "captureSameFixtureTypedRecallAndPersistentAnswers",
              "native_recall_reopen": "reopenSameFixtureNativeRecallRecords"}[args.phase]
    test_class = "SixBatchNativeRecallTest" if args.phase.startswith("native_recall") else "SixBatchVisualTest" if args.phase == "visual" else "SixBatchAcceptanceTest"
    command = ["shell", "am", "instrument", "-w", "-r", "-e", "class",
               "org.inkweft.app." + test_class + "#" + method,
               "-e", "sixBatchFixture", "dedicated-empty-emulator",
               "-e", "sourceCommit", args.source_commit,
               args.package + ".test/androidx.test.runner.AndroidJUnitRunner"]
    # Cold reopen is a new process on the same synthetic library, without clearing app data.
    if args.phase in ("reopen", "native_recall_reopen"):
        run(["shell", "am", "force-stop", args.package])
        evidence["cold_process_restart"] = True
        result_path.write_text(json.dumps(evidence, indent=2))
    start = time.monotonic()
    try:
        completed = run(command, text=True, timeout=args.timeout)
        raw = completed.stdout + completed.stderr
        (args.output / f"{args.phase}-instrumentation.log").write_text(raw)
        evidence["elapsed_seconds"] = time.monotonic() - start
        evidence["status"] = "PASS" if re.search(r"OK \(1 test\)", raw) and "FAILURES!!!" not in raw else "FAILED"
    except subprocess.TimeoutExpired as error:
        evidence["status"] = "TIMEOUT_RESULT_UNKNOWN"
        # Never turn a client timeout into a fake device outcome or clear data to make it pass.
        (args.output / f"{args.phase}-instrumentation.log").write_bytes(error.stdout or b"")
    except subprocess.CalledProcessError as error:
        evidence["status"] = "FAILED_ADB"
        (args.output / f"{args.phase}-instrumentation.log").write_text(str(error))
    finally:
        evidence["elapsed_seconds"] = time.monotonic() - start
        evidence["instrumentation_status"] = evidence["status"]
        try:
            raw = run(["exec-out", "run-as", args.package, "cat", "files/six-batch-fixture-v1/manifest.json"]).stdout
            manifest = json.loads(raw)
            if manifest.get("synthetic") is not True:
                raise ValueError("Refusing non-synthetic output")
            (args.output / f"{args.phase}-manifest.json").write_bytes(raw)
            evidence["run_id"] = manifest["runId"]
            names = {"prepare": ("01-twelve-page-material.png", "02-outline-120-topics.png", "03-unavailable-return-keeps-location.png", "05-layered-1000-stroke-pressure.png", "06-pressure-page-layers.png", "07-pressure-hidden-layer.png", "08-pressure-locked-layer.png"),
                     "reopen": ("04-reopened-same-material.png",),
                     "visual": tuple("visual-" + layout + "-" + stage + ".png" for layout in ("wide", "narrow") for stage in (
                         "01-default-tools", "02-advanced-pen", "03-map-selected", "04-outline-selected", "05-long-card-title", "06-long-card-body",
                         "07-long-card-annotation", "08-long-card-source", "09-page-layers", "10-whitespace-expanded", "11-whitespace-collapsed", "12-bound-region-collapsed")),
                     "native_recall": tuple("native-recall-" + stage + ".png" for stage in (
                         "01-source-masked", "02-source-answer", "03-source-revealed", "04-cloze-masked", "05-cloze-revealed", "06-question-result", "07-sealed-grading-controls")),
                     "native_recall_reopen": ("native-recall-reopen-01-material-page.png",)}[args.phase]
            evidence["screenshots"] = []
            for name in names:
                try:
                    pixels = run(["exec-out", "run-as", args.package, "cat", "files/six-batch-fixture-v1/" + name]).stdout
                    if not pixels.startswith(b"\x89PNG\r\n\x1a\n"):
                        raise ValueError("Invalid synthetic screenshot")
                    (args.output / name).write_bytes(pixels)
                    record = {"file": name, "bytes": len(pixels), "sha256": hashlib.sha256(pixels).hexdigest()}
                    try:
                        proof = checked_native_capture(manifest, name, pixels, args.source_commit)
                        if proof is not None:
                            record["native_page_proof"] = proof
                    except (ValueError, KeyError, IndexError):
                        record["status"] = "FAILED_NO_NATIVE_PROOF"
                    evidence["screenshots"].append(record)
                except (subprocess.SubprocessError, ValueError, KeyError, IndexError):
                    evidence["screenshots"].append({"file": name, "status": "UNAVAILABLE"})
            evidence["pixels_review"] = "PENDING_HUMAN_OR_VISUAL_REVIEW"
        except (subprocess.SubprocessError, ValueError, KeyError, json.JSONDecodeError):
            evidence["manifest"] = "UNAVAILABLE"
        if evidence["status"] == "PASS" and (evidence.get("manifest") == "UNAVAILABLE" or
                any(item.get("status") in ("UNAVAILABLE", "FAILED_NO_NATIVE_PROOF") for item in evidence.get("screenshots", []))):
            evidence["status"] = "INCOMPLETE_EVIDENCE"
        result_path.write_text(json.dumps(evidence, indent=2))
    return 0 if evidence["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
