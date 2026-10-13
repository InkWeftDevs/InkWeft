#!/usr/bin/env python3
"""Parse only the four prescribed default-off runs. Invoke after C repeat 2 completes."""
import argparse
import hashlib
import json
import math
import statistics
from datetime import datetime
from pathlib import Path

RUNS = [
    ("candidate", "candidate-raster-hex-v1-off-repeat1"),
    ("baseline", "baseline-raster-hex-pair-off-repeat1"),
    ("baseline", "baseline-raster-hex-pair-off-repeat2"),
    ("candidate", "candidate-raster-hex-v1-off-repeat2"),
]
METRICS = ["firstCompleteMs", "appendCompleteMs", "forcedFullCompleteMs", "metadataMs",
           "rasterMs", "publicationMs", "localDecoded", "fullDecoded", "localRebuiltTiles",
           "fullRebuiltTiles", "nativeStrokeBuilds", "bitmapBytes"]
LAYERS = [1, 4, 8]
OUTPUT = "candidate-raster-hex-v1-off-pair-summary"


def load(path):
    return json.loads(path.read_text())


def records(text, marker):
    return [json.loads(line.split(marker, 1)[1]) for line in text.splitlines() if marker in line]


def sample_summary(values):
    numeric = [v for v in values if v is not None]
    return {"raw_values": values, "sample_count": len(values), "numeric_sample_count": len(numeric),
            "median": statistics.median(numeric) if numeric else None,
            "min": min(numeric) if numeric else None, "max": max(numeric) if numeric else None}


def collect(root, side, stem):
    receipt_path = root / (stem + "-targeted.json")
    log_path = root / (stem + "-targeted.log")
    receipt = load(receipt_path)
    assert receipt.get("status") == "PASS" and receipt.get("passed") is True, "PASS receipt missing"
    assert receipt.get("expected") == receipt.get("executed") == receipt.get("tests") == 2, "test count is not 2/2"
    assert receipt.get("exit_code") == 0 and receipt.get("failures") == 0, "failure or nonzero exit"
    assert receipt.get("diagnostics") is False, "receipt does not confirm diagnostics=false"
    assert receipt.get("selection") == "org.inkweft.app.TileLocalRedrawTest", "different test selection"
    start = datetime.fromisoformat(receipt["started_utc"])
    end = datetime.fromisoformat(receipt["completed_utc"])
    assert end >= start, "invalid completed time"
    raw_log = log_path.read_bytes()
    assert hashlib.sha256(raw_log).hexdigest() == receipt.get("log_sha256"), "log hash differs from completed receipt"
    text = raw_log.decode()
    assert "OK (2 tests)" in text, "completed two-test log marker missing"
    local = records(text, "TILE_LOCAL_EVIDENCE ")
    pan = records(text, "TILE_PAN_EVIDENCE ")
    assert [r.get("layers") for r in local] == LAYERS, "missing/duplicate/reordered 1/4/8 cases"
    assert len(pan) == 1 and pan[0].get("panPixelsEqual") is True and pan[0].get("zoomPixelsEqual") is True, "pan/zoom evidence incomplete"
    assert text.index("TILE_PAN_EVIDENCE ") < text.index("TILE_LOCAL_EVIDENCE "), "original pan/zoom prelude order changed"
    for row in [*local, *pan]:
        assert not any("diagnostic" in k.lower() for k in row), "diagnostic fields present in default-off evidence"
    for row in local:
        assert row.get("pixelsEqual") is True, "pixel equality missing/false"
        assert row.get("initialStrokes") == 320 and row.get("finalStrokes") == 321, "fixture size differs"
        native_present = "nativeStrokeBuilds" in row
        if side == "baseline":
            assert not native_present, "baseline unexpectedly contains a native counter; investigate instead of substituting"
            row["nativeStrokeBuilds"] = None
        else:
            assert native_present, "candidate native counter missing"
        row["nativeCounterPresentInRaw"] = native_present
        for key in METRICS:
            value = row[key]
            if key == "nativeStrokeBuilds" and side == "baseline":
                continue
            assert isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) and value >= 0, "invalid numeric metric: " + key
    env_paths = [root / (stem + "-environment.json"), root / (stem + "-environment-after.json")]
    envs = [load(p) for p in env_paths]
    assert all(e.get("status") == "RECORDED" and e.get("comparison_sha256") for e in envs), "before/after environment incomplete"
    return {"side": side, "phase": stem, "receipt": receipt, "log_path": str(log_path),
            "receipt_path": str(receipt_path), "pan_evidence": pan[0], "cases": local,
            "diagnostic_fields_absent": True,
            "environments": [{"path": str(p), "comparison_sha256": e["comparison_sha256"]} for p, e in zip(env_paths, envs)]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence-dir", type=Path, default=Path(__file__).resolve().parent)
    args = parser.parse_args()
    root = args.evidence_dir
    runs, errors = [], []
    for side, stem in RUNS:
        try:
            runs.append(collect(root, side, stem))
        except (OSError, ValueError, KeyError, TypeError, AssertionError) as error:
            errors.append({"phase": stem, "reason": str(error)})
    if not errors:
        for before, after in zip(runs, runs[1:]):
            if datetime.fromisoformat(before["receipt"]["completed_utc"]) > datetime.fromisoformat(after["receipt"]["started_utc"]):
                errors.append({"phase": after["phase"], "reason": "fixed C/B/B/C runs overlap or are out of order"})
        if len({e["comparison_sha256"] for run in runs for e in run["environments"]}) != 1:
            errors.append({"phase": "pair", "reason": "environment configurations differ; do not silently pool"})
    if errors:
        print(json.dumps({"status": "INCOMPLETE", "errors": errors, "expected_runs": [s for _, s in RUNS],
                          "summary_files_written": False}, indent=2))
        return 2

    summaries = {}
    for layer in LAYERS:
        summaries[str(layer)] = {}
        for side in ["candidate", "baseline"]:
            rows = [next(row for row in run["cases"] if row["layers"] == layer) for run in runs if run["side"] == side]
            summaries[str(layer)][side] = {"metrics": {key: sample_summary([row[key] for row in rows]) for key in METRICS},
                                           "pixelsEqual": {"raw_values": [row["pixelsEqual"] for row in rows], "all": all(row["pixelsEqual"] for row in rows)}}
    comparisons = {}
    for layer, sides in summaries.items():
        comparisons[layer] = {}
        for key in ["firstCompleteMs", "appendCompleteMs", "forcedFullCompleteMs", "metadataMs", "rasterMs", "publicationMs"]:
            c = sides["candidate"]["metrics"][key]["median"]
            b = sides["baseline"]["metrics"][key]["median"]
            comparisons[layer][key] = {"candidate_median": c, "baseline_median": b,
                                      "difference": c - b, "change_percent": (c / b - 1) * 100 if b else None}
    support = {
        "candidate": ["candidate-raster-hex-v1-source-binding.json", "candidate-raster-hex-v1-source-sha256.json",
                      "candidate-raster-hex-v1-build.json", "candidate-raster-hex-v1-targeted-apks.json"],
        "baseline": ["raster-diagnostic-v2-source-chain.json", "baseline-raster-diagnostic-v2-source-sha256.json",
                     "baseline-raster-diagnostic-v2-build.json", "baseline-raster-diagnostic-v2-targeted-apks.json",
                     "baseline-raster-diagnostic-v2-install-targeted-apks.json"],
    }
    binding_paths = {side: [{"path": str(root / name), "exists": (root / name).is_file()} for name in names] for side, names in support.items()}
    limits = ["Only the four fixed new default-off logs are parsed; no old v4/final or diagnostic-on measurements are included.",
              "Two samples per side/layer: median, min and max are descriptive only; no percentile or statistical significance estimate.",
              "Baseline native counter is absent in raw output and null in summaries, never zero.",
              "Multi-layer metadata/raster/publication sums overlap and must not be added to append wall.",
              "Pixel equality is within each version against its cold reference. previewCaptureReady=false is not hardware screenshot verification.",
              "bitmapBytes is reported after destructive update; it is not append peak allocation.",
              "Matching environment configuration does not prove identical instantaneous load, cache, ART/JIT or GC conditions.",
              "Source/APK paths are references to parent verification records. This log-only parser does not rehash APKs, source trees or establish each run's installed-APK continuity.",
              "A complete log summary is not final acceptance; original failures and outstanding FrameProbe/NativeStroke/Streamed gates must be assessed separately."]
    result = {"status": "COMPLETE_LOG_SUMMARY", "generated_utc": datetime.now().astimezone().isoformat(),
              "fixed_run_order": [s for _, s in RUNS], "expected_tests": 8, "executed_tests": 8,
              "runs": runs, "per_layer": summaries, "median_comparisons": comparisons,
              "source_apk_binding_paths": binding_paths, "artifact_continuity_verified_by_this_script": False, "limits": limits}
    output_json = root / (OUTPUT + ".json")
    output_md = root / (OUTPUT + ".md")
    for p in [output_json, output_md]:
        if p.exists():
            raise SystemExit("Refusing to overwrite existing completed summary: " + str(p))
    lines = ["# New default-off raster hex pair", "", "All four prescribed full-class runs completed 2/2 PASS (8/8 total), in C/B/B/C order. Diagnostic arrays are absent and receipts say diagnostics=false. This descriptive summary is not final acceptance.", ""]
    def fmt(value):
        return "null" if value is None else f"{value:.3f}" if isinstance(value, float) else str(value)
    for layer in LAYERS:
        lines += [f"## {layer} layer(s)", "", "Times are milliseconds; counts/bytes retain their original units.", "",
                  "| Metric | Candidate raw 1 / 2 | Median [min, max] | Baseline raw 1 / 2 | Median [min, max] |",
                  "|---|---:|---:|---:|---:|"]
        for key in METRICS:
            c = summaries[str(layer)]["candidate"]["metrics"][key]
            b = summaries[str(layer)]["baseline"]["metrics"][key]
            def cell(x):
                return " / ".join(fmt(v) for v in x["raw_values"]), f"{fmt(x['median'])} [{fmt(x['min'])}, {fmt(x['max'])}]"
            cr, cs = cell(c); br, bs = cell(b)
            lines.append(f"| {key} | {cr} | {cs} | {br} | {bs} |")
        lines += ["", "pixelsEqual: candidate [true, true]; baseline [true, true].", ""]
    lines += ["## Binding and limits", ""] + ["- " + x for x in limits]
    lines += ["", "Full raw cases, all eight environment paths, source/APK verification paths and descriptive median comparisons are in the companion JSON. No older/default-on samples are merged, and no failed or incomplete run is replaced.", ""]
    output_json.write_text(json.dumps(result, indent=2) + "\n")
    output_md.write_text("\n".join(lines))
    print(json.dumps({"status": result["status"], "json": str(output_json), "markdown": str(output_md)}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
