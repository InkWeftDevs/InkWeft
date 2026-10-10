"""Audit uploaded sampler JSON; optionally write a PRIVATE local replay CSV.

No device connection or uploaded code execution. Public output has aggregate
counts/ranges only; coordinates, device descriptors and individual times stay
in the original attachment and optional private CSV outside the repository.
"""
import argparse
import collections
import csv
import hashlib
import json
import math
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("input", type=Path)
parser.add_argument("--private-replay", type=Path)
args = parser.parse_args()
raw = args.input.read_bytes()
data = json.loads(raw)
assert data["header"]["schema"] == "org.inkweft.styluscapture/1"
events = data["events"]
tools = collections.Counter()
contact = []
terminals = []
all_pen = []
actions = collections.Counter()
histories = collections.Counter()
pressure_ranges = set()
active = {}
completed = []
duplicates = stationary = backwards = 0
prior = {}
for sequence, event in enumerate(events):
    assert event["sequence"] == sequence
    assert event["historySize"] == len(event["history"])
    actions[event["actionName"]] += 1
    histories[event["historySize"]] += 1
    samples = [*event["history"], {"eventTimeMs": event["eventTimeMs"], "pointers": event["pointers"]}]
    times = [batch["eventTimeMs"] for batch in samples]
    assert times == sorted(times)
    for batch in samples:
        for p in batch["pointers"]:
            assert all(math.isfinite(p[field]) for field in ["x", "y", "pressure", "tiltRaw", "orientationRaw"])
            tools[p["toolName"]] += 1
            if p["toolType"] != 2:
                continue
            all_pen.append(p)
            r = p.get("pressureRange")
            if r:
                pressure_ranges.add((r["min"], r["max"]))
            # The sampler's contact summary excludes terminal UP readings.
            if event["actionMasked"] in {0, 2, 5}:
                contact.append(p)
            if event["actionMasked"] in {1, 6}:
                terminals.append(p)
    for p in event["pointers"]:
        if p["toolType"] == 2 and event["actionMasked"] == 0:
            key = (event["deviceId"], p["pointerId"])
            assert key not in active
            active[key] = (len(completed), event["eventTimeMs"], [])
            prior.pop(key, None)
    if event["actionMasked"] not in {0, 1, 2}:
        continue
    for batch in samples:
        for p in batch["pointers"]:
            key = (event["deviceId"], p["pointerId"])
            if p["toolType"] != 2 or key not in active:
                continue
            number, start, points = active[key]
            elapsed = batch["eventTimeMs"] - start
            assert elapsed >= 0
            reading = (p["x"], p["y"], elapsed, p["pressure"], p["tiltRaw"], p["orientationRaw"])
            old = prior.get(key)
            if old:
                duplicates += reading == old
                stationary += reading[:3] == old[:3] and reading != old
                backwards += elapsed < old[2]
            prior[key] = reading
            points.append((number, *reading))
    if event["actionMasked"] == 1:
        for p in event["pointers"]:
            key = (event["deviceId"], p["pointerId"])
            if p["toolType"] == 2 and key in active:
                completed.append(active.pop(key)[2])
                prior.pop(key, None)
assert not active
summary = data["summary"]
assert len(events) == summary["events"]
assert sum(tools.values()) == summary["samplesIncludingHistory"]
assert tools["STYLUS"] == summary["androidReportedStylusSamples"]
assert tools["FINGER"] == summary["fingerSamples"]
assert len(contact) == summary["stylusContactPressure"]["samples"]
assert pressure_ranges == {(0, 1)}, "This replay expects this upload's declared 0..1 pressure range."
if args.private_replay:
    args.private_replay.parent.mkdir(parents=True, exist_ok=True)
    with args.private_replay.open("w", newline="", encoding="utf-8") as output:
        writer = csv.writer(output)
        writer.writerow(["stroke", "x", "y", "elapsedMs", "pressure", "tiltRaw", "orientationRaw"])
        for points in completed:
            writer.writerows(points)
result = {
    "scope": "UPLOADED_PHYSICAL_SAMPLER_INPUT_NOT_APPLICATION_ALGORITHM_OUTPUT",
    "input_sha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw),
    "report_input_hash_matches": hashlib.sha256(raw).hexdigest() == "2960ccee9c69b4aabed57b19a2373427ef99095c02468098cd775b3df5dbb5b6",
    "events": len(events), "samples_including_history": sum(tools.values()), "tools": dict(tools),
    "actions": dict(actions), "history_size_counts": dict(histories),
    "contact_samples_excluding_up": len(contact), "terminal_stylus_samples": len(terminals),
    "pressure_contact_min_max": [min(p["pressure"] for p in contact), max(p["pressure"] for p in contact)],
    "declared_pressure_ranges": sorted(pressure_ranges),
    "tilt_contact_radians_min_max": [min(p["tiltRaw"] for p in contact), max(p["tiltRaw"] for p in contact)],
    "orientation_contact_radians_min_max": [min(p["orientationRaw"] for p in contact), max(p["orientationRaw"] for p in contact)],
    "negative_contact_orientations": sum(p["orientationRaw"] < 0 for p in contact),
    "terminal_pressure_min_max": [min(p["pressure"] for p in terminals), max(p["pressure"] for p in terminals)],
    "completed_pen_strokes": len(completed), "replay_samples_with_up": sum(map(len, completed)),
    "stroke_samples_min_max": [min(map(len, completed)), max(map(len, completed))],
    "same_position_time_different_axes": stationary, "exact_consecutive_duplicates": duplicates,
    "backwards_stroke_times": backwards,
    "notes": ["Observed pressure max near 0.5025 is not the declared hardware maximum of 1.",
              "Terminal pressure need not be zero; preserve actual readings.",
              "FINGER points cannot be classified as finger versus palm individually.",
              "Raw sampler input cannot establish StarNote or InkWeft smoothing output or optical latency."]
}
print(json.dumps(result, ensure_ascii=False, indent=2))
