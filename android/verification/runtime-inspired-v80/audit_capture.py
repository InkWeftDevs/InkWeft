#!/usr/bin/env python3
"""Read-only archive audit; never imports or executes uploaded scripts or contacts a device."""
import argparse
import csv
import hashlib
import json
from pathlib import Path


def audit(root):
    root = root.resolve()

    def local(name):
        path = (root / name).resolve()
        if not path.is_relative_to(root) or not path.is_file():
            raise ValueError("Missing or unsafe evidence path")
        return path

    def read(name):
        return json.loads(local(name).read_text(encoding="utf-8"))

    manifest = read("final-manifest.json")
    for entry in manifest["files"]:
        data = local(entry["path"]).read_bytes()
        if len(data) != entry["bytes"] or hashlib.sha256(data).hexdigest() != entry["sha256"]:
            raise ValueError("Manifest mismatch: " + entry["path"])

    reports = []
    for name in ("touch-pipeline-analysis", "starnote-pipeline-analysis"):
        report = read("arm64-research/" + name + ".json")
        for entry in report["sources"]:
            parts = entry["path"].replace("\\", "/").split("/")
            data = local("/".join(parts[parts.index("runs"):])).read_bytes()
            if len(data) != entry["bytes"] or hashlib.sha256(data).hexdigest() != entry["sha256"]:
                raise ValueError("Analysis source mismatch")
        reports.append({"report": name, "source_files": len(report["sources"]),
                        "reported_numeric_mismatches": len(report["mismatches"])})

    sessions = []
    for name in ("goodnotes-arm64-jni-01", "goodnotes-arm64-features-01",
                 "starnote-arm64-neopen-01", "starnote-arm64-hwr-01"):
        calls, returns = {}, {}
        for line in local("runs/" + name + "/events.jsonl").read_text(encoding="utf-8").splitlines():
            event = json.loads(line)
            if event.get("kind") not in ("arm64-call", "arm64-return"):
                continue
            if event.get("architecture") != "arm64":
                raise ValueError("Unexpected event ISA")
            target = calls if event["kind"] == "arm64-call" else returns
            key = (event["callId"], event["function"])
            if key in target:
                raise ValueError("Duplicate call/return identity")
            target[key] = True
        if calls.keys() != returns.keys():
            raise ValueError("Unpaired native call")
        # Preserve ERROR even when all retained calls have an onLeave.
        sessions.append({"run": name, "retained_call_return_pairs": len(calls),
                         "reported_session_status": read("runs/" + name + "/result.json")["status"]})

    cases = []
    for case in read("synthetic-input/starnote-packet/index.json")["cases"]:
        path = local("synthetic-input/starnote-packet/" + case["file"])
        if hashlib.sha256(path.read_bytes()).hexdigest() != case["sha256"]:
            raise ValueError("Input CSV mismatch")
        with path.open(encoding="utf-8") as stream:
            rows = list(csv.DictReader(line for line in stream if not line.startswith("#")))
        if len(rows) != case["points"] or float(rows[-1]["pressure"]) != 0:
            raise ValueError("Input count or terminal pressure mismatch")
        cases.append({"id": case["id"], "points": len(rows), "csv_sha256": case["sha256"]})

    return {"manifest_files_verified": len(manifest["files"]), "reports": reports,
            "native_sessions": sessions, "total_retained_native_pairs": sum(s["retained_call_return_pairs"] for s in sessions),
            "matrix_cases": cases, "physical_pen": manifest["physicalPen"],
            "scope": "offline hash, count and call identity audit; numeric relations are the supplied analysis results, not independently recomputed"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture_directory", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    args.output.write_text(json.dumps(audit(args.capture_directory), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("Capture evidence audit written; no uploaded code executed.")
