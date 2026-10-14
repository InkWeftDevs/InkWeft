#!/usr/bin/env python3
"""Strict opt-in save-boundary analysis, never an acceptance/causal-comparison classifier.

Input is the complete am-instrument stdout containing the four boundary markers. All
source marker objects and event order are retained. CPU deltas require equal thread IDs.
"""
import argparse
import hashlib
import json
import math
import re
from pathlib import Path

PREFIX = "LIBRARY_SAVE_BOUNDARY_"
KINDS = {"RESULT", "EVENTS", "READS", "END"}
MODES = ("IDLE", "DIRECT", "OBSERVE")
BOUNDARY_FIELDS = {"stage", "sample", "task", "transaction", "depth", "successRequested", "outcome", "wallNs", "threadCpuNs", "threadId"}
READ_FIELDS = {"owner", "stage", "read", "count", "wallNs", "threadCpuNs", "threadId"}
STAGES = {"save-request", "save-writer-acquired", "save-head-read", "save-head-written", "save-revision-written", "save-receipt-written", "save-returned", "save-failed", "writer-submit", "writer-run-enter", "writer-run-exit", "writer-submit-failed"} | {
    f"{layer}-{operation}-{edge}" for layer in ("room", "sqlite") for operation in ("begin", "success", "end") for edge in ("enter", "exit")}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def integer(value, low=None):
    return type(value) is int and (low is None or value >= low)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, f"duplicate JSON key: {key}")
        result[key] = value
    return result


def load_markers(text):
    markers = []
    for line_number, line in enumerate(text.splitlines(), 1):
        if PREFIX not in line:
            continue
        require(line.count(PREFIX) == 1, f"line {line_number}: repeated marker prefix")
        # Android's stream prefix or a decoded CI timestamp may precede the marker.
        tail = line[line.index(PREFIX):]
        match = re.fullmatch(r"LIBRARY_SAVE_BOUNDARY_([A-Z]+) (\{.*\})\s*", tail)
        require(match is not None, f"line {line_number}: malformed marker")
        kind, payload = match.groups()
        require(kind in KINDS, f"line {line_number}: unknown marker kind {kind}")
        value = json.loads(payload, object_pairs_hook=unique_object,
                           parse_constant=lambda value: (_ for _ in ()).throw(ValueError(f"nonfinite JSON: {value}")))
        markers.append({"kind": kind, "line": line_number, "value": value})
    return markers


def single(values, description):
    require(len(values) == 1, f"{description}: expected exactly one, found {len(values)}")
    return values[0]


def quantiles(values):
    require(len(values) == 30, "quantiles require the original 30 samples")
    ordered = sorted(values)
    return {"p50": ordered[14], "p95": ordered[28], "max": ordered[29], "method": "nearest-rank n=30; indices 14,28,29"}


def segment(name, begin, end):
    require(end["wallNs"] >= begin["wallNs"], f"{name}: reversed wall interval")
    same_thread = begin["threadId"] == end["threadId"]
    cpu = end["threadCpuNs"] - begin["threadCpuNs"] if same_thread else None
    require(cpu is None or cpu >= 0, f"{name}: same-thread CPU counter decreased")
    return {"name": name, "begin": begin, "end": end, "wallMs": (end["wallNs"] - begin["wallNs"]) / 1e6,
            "threadCpuMs": cpu / 1e6 if cpu is not None else None,
            "cpuMeaning": "same-thread counter, includes intervening work" if same_thread else "unavailable: endpoint threads differ"}


def analyze_sample(mode, sample, events):
    number = sample["sample"]
    own = [event for event in events if event["sample"] == number]

    def get(stage, **constraints):
        return single([event for event in own if event["stage"] == stage and all(event.get(key) == value for key, value in constraints.items())], f"{mode}/{number}/{stage}/{constraints}")

    request = get("save-request")
    submit = get("writer-submit")
    run = get("writer-run-enter")
    run_exit = get("writer-run-exit")
    body = get("save-writer-acquired")
    returned = get("save-returned")
    require(not any(event["stage"] in {"save-failed", "writer-submit-failed"} for event in own), f"{mode}/{number}: failed save task")
    for stage in ("save-head-read", "save-head-written", "save-revision-written", "save-receipt-written"):
        get(stage)
    room_begin = get("room-begin-enter", depth=1)
    room_begun = get("room-begin-exit", depth=1)
    room_success = get("room-success-enter", depth=1)
    room_success_exit = get("room-success-exit", depth=1)
    room_end = get("room-end-enter", depth=1)
    room_ended = get("room-end-exit", depth=1)
    room_transaction = room_begin["transaction"]
    require(all(event["transaction"] == room_transaction for event in [room_begun, room_success, room_success_exit, room_end, room_ended]), f"{mode}/{number}: Room outer transaction mismatch")
    candidates = []
    for begin in own:
        if begin["stage"] != "sqlite-begin-exit" or begin["depth"] != 1 or begin["outcome"] != "returned":
            continue
        ends = [event for event in own if event["stage"] == "sqlite-end-exit" and event["depth"] == 1 and event["transaction"] == begin["transaction"]]
        if len(ends) == 1 and begin["wallNs"] <= body["wallNs"] <= ends[0]["wallNs"]:
            candidates.append((begin, ends[0]))
    sqlite_begun, sqlite_ended = single(candidates, f"{mode}/{number}: SQLite outer transaction spanning author body")
    transaction = sqlite_begun["transaction"]
    sqlite_begin = get("sqlite-begin-enter", depth=1, transaction=transaction)
    sqlite_end = get("sqlite-end-enter", depth=1, transaction=transaction)
    sqlite_success = get("sqlite-success-enter", depth=1, transaction=transaction)
    sqlite_success_exit = get("sqlite-success-exit", depth=1, transaction=transaction)
    task = submit["task"]
    require(task > 0, f"{mode}/{number}: missing writer task")
    require(all(event["task"] == task for event in own if event["stage"] not in {"save-request", "save-returned"}), f"{mode}/{number}: writer task token mismatch")
    worker_events = [run, run_exit, body, room_begin, room_begun, room_success, room_success_exit, room_end, room_ended,
                     sqlite_begin, sqlite_begun, sqlite_success, sqlite_success_exit, sqlite_end, sqlite_ended]
    require(all(event["threadId"] == run["threadId"] for event in worker_events), f"{mode}/{number}: transaction worker thread changed")
    require(all(event["outcome"] == ("entered" if event["stage"].endswith("-enter") else "returned") for event in worker_events), f"{mode}/{number}: unsuccessful boundary")
    require(all(event["successRequested"] for event in [room_success_exit, room_end, room_ended, sqlite_success_exit, sqlite_end, sqlite_ended]), f"{mode}/{number}: outer success was not requested")
    require(sample["startNs"] <= request["wallNs"] <= submit["wallNs"] <= run["wallNs"], f"{mode}/{number}: request ordering")
    require(room_ended["wallNs"] <= run_exit["wallNs"], f"{mode}/{number}: worker exited before Room END")
    require(returned["wallNs"] <= sample["endNs"], f"{mode}/{number}: caller end precedes return")
    require(room_success["wallNs"] <= sqlite_success["wallNs"] <= sqlite_success_exit["wallNs"] <= room_success_exit["wallNs"] <= room_end["wallNs"] <= sqlite_end["wallNs"], f"{mode}/{number}: success/END ordering")
    chain = [("request_to_submit", request), ("submit_to_worker", submit), ("worker_to_room_begin", run),
             ("room_pre_sqlite_begin", room_begin), ("sqlite_begin", sqlite_begin),
             ("sqlite_begin_to_room_begin_return", sqlite_begun), ("room_begin_to_body", room_begun),
             ("body_to_room_success", body), ("success_and_end_prologue", room_success),
             ("sqlite_end_commit_or_rollback_and_release", sqlite_end), ("room_post_sqlite_end", sqlite_ended),
             ("room_end_to_caller_return", room_ended)]
    segments = [segment(name, begin, chain[index + 1][1] if index + 1 < len(chain) else returned) for index, (name, begin) in enumerate(chain)]
    require(math.isclose(sum(value["wallMs"] for value in segments), (returned["wallNs"] - request["wallNs"]) / 1e6, abs_tol=1e-8), f"{mode}/{number}: disjoint segment sum")
    return {"sample": number, "save": sample, "writerTask": task, "roomOuterTransaction": room_transaction,
            "sqliteOuterTransaction": transaction, "segments": segments, "rawEvents": own,
            "outside_request_before_ms": (request["wallNs"] - sample["startNs"]) / 1e6,
            "outside_return_after_ms": (sample["endNs"] - returned["wallNs"]) / 1e6,
            "writerRunExitAfterCallerReturn": run_exit["wallNs"] > returned["wallNs"]}


def analyze(markers):
    require(bool(markers), "no boundary markers")
    groups = {mode: {kind: [] for kind in KINDS} for mode in MODES}
    result_order = []
    for marker in markers:
        kind = marker["kind"]; value = marker["value"]
        require(kind in KINDS and type(value) is dict, "unknown marker kind/payload")
        require(type(value.get("schema")) is int and value["schema"] == 1, "unsupported schema")
        mode = value.get("mode")
        require(mode in MODES, f"unexpected mode {mode}")
        groups[mode][kind].append(value)
        if kind == "RESULT":
            result_order.append(mode)
    require(result_order == list(MODES), "expected exactly IDLE, DIRECT, OBSERVE in fixed order")
    outputs = {}
    fixtures = []
    for mode in MODES:
        group = groups[mode]
        result = single(group["RESULT"], f"{mode}: RESULT")
        end = single(group["END"], f"{mode}: END")
        require(result.get("diagnosticOnly") is True and type(result.get("seed")) is int and result["seed"] == 4101, f"{mode}: seed/diagnostic binding")
        expected_revision = 32 if mode == "OBSERVE" else 1
        expected_ready = {"IDLE": "fixture-ready", "DIRECT": "first-source-check", "OBSERVE": "first-index-commit"}[mode]
        require(type(result.get("startRevision")) is int and result["startRevision"] == expected_revision and result.get("readiness") == expected_ready, f"{mode}: readiness/revision binding")
        require(end.get("completed") is True and end.get("invalid") is False, f"{mode}: incomplete/invalid trace")
        for key, expected in (("committedSaves", 30), ("droppedEvents", 0), ("droppedReadEvents", 0)):
            require(type(end.get(key)) is int and end[key] == expected, f"{mode}: {key}")
        fixture = result.get("fixture")
        require(type(fixture) is dict and re.fullmatch(r"[a-f0-9]{64}", fixture.get("logicalSha256", "")) is not None, f"{mode}: missing fixture hash")
        require(fixture.get("rowCounts", {}).get("notes") == 501 and fixture.get("rowCounts", {}).get("notebook_pages") == 20001 and fixture.get("rowCounts", {}).get("page_search_text") == 20000, f"{mode}: fixture rows")
        settings=fixture.get("writerSqlite")
        require(settings == {"page_size": 1024, "auto_vacuum": 1, "journal_mode": "wal", "synchronous": 2}
                and all(type(settings[key]) is int for key in ("page_size", "auto_vacuum", "synchronous")), f"{mode}: SQLite settings")
        fixtures.append(fixture)

        def chunks(kind, count_key, cap):
            parts = group[kind]
            count = end.get(count_key)
            require(integer(count, 0) and count <= cap, f"{mode}: {count_key} exceeds cap")
            require(len(parts) == (count + 95) // 96, f"{mode}: {kind} missing/extra part")
            flattened = []
            for index, part in enumerate(parts):
                require(type(part.get("part")) is int and part["part"] == index, f"{mode}: {kind} noncontiguous part")
                batch = part.get("events")
                require(type(batch) is list and len(batch) == min(96, count - 96 * index), f"{mode}: {kind} invalid part length")
                flattened.extend(batch)
            require(len(flattened) == count, f"{mode}: {kind} count mismatch")
            return flattened

        events = chunks("EVENTS", "recordedEvents", 4096)
        reads = chunks("READS", "recordedReadEvents", 2048)
        for event in events:
            require(type(event) is dict and set(event) == BOUNDARY_FIELDS, f"{mode}: boundary shape")
            require(event["stage"] in STAGES, f"{mode}: unknown boundary stage")
            require(integer(event["sample"], -1) and event["sample"] < 30 and integer(event["task"], 0) and integer(event["transaction"], 0) and integer(event["depth"], 0), f"{mode}: boundary identifiers")
            require(type(event["successRequested"]) is bool and event["outcome"] in {"entered", "returned", "threw"}, f"{mode}: boundary outcome")
            require(all(integer(event[key], 0) for key in ("wallNs", "threadCpuNs")) and integer(event["threadId"], 1), f"{mode}: boundary clocks")
        for event in reads:
            require(type(event) is dict and set(event) == READ_FIELDS, f"{mode}: read shape")
            require(event["owner"] in {"index", "read", "control"} and type(event["stage"]) is str and integer(event["read"], 0) and integer(event["count"], -1), f"{mode}: read identifiers")
            require(all(integer(event[key], 0) for key in ("wallNs", "threadCpuNs")) and integer(event["threadId"], 1), f"{mode}: read clocks")
        submits = [event["task"] for event in events if event["stage"] == "writer-submit"]
        exits = [event["task"] for event in events if event["stage"] in {"writer-run-exit", "writer-submit-failed"}]
        require(len(set(submits)) == len(submits) and all(exits.count(task) == 1 for task in submits), f"{mode}: submitted task lacks unique exit")
        saves = result.get("saves")
        require(type(saves) is list and len(saves) == 30, f"{mode}: save count")
        samples = []
        for number, save in enumerate(saves):
            require(type(save.get("sample")) is int and save["sample"] == number and save.get("committed") is True and type(save.get("revision")) is int and save["revision"] == expected_revision + number + 1, f"{mode}: save ordinal/outcome/revision")
            require(integer(save.get("startNs"), 0) and integer(save.get("endNs"), save["startNs"]), f"{mode}/{number}: save clocks")
            require(type(save.get("elapsedMs")) in {int, float} and math.isfinite(save["elapsedMs"]) and math.isclose(save["elapsedMs"], (save["endNs"] - save["startNs"]) / 1e6, abs_tol=1e-8), f"{mode}/{number}: elapsed recomputation")
            if number:
                require(saves[number - 1]["endNs"] <= save["startNs"], f"{mode}: overlapping sequential saves")
            for key in ("sourceChecksStart", "sourceChecksEnd", "indexRowsStart", "indexRowsEnd"):
                require(integer(save.get(key), 0), f"{mode}/{number}: missing progress counter {key}")
            require(save["sourceChecksEnd"] >= save["sourceChecksStart"] and save["indexRowsEnd"] >= save["indexRowsStart"], f"{mode}/{number}: progress counter decreased")
            samples.append(analyze_sample(mode, save, events))
        segment_names = [segment["name"] for segment in samples[0]["segments"]]
        outputs[mode] = {"saveWallMs": quantiles([save["elapsedMs"] for save in saves]),
                         "segmentsWallMs": {name: quantiles([next(segment["wallMs"] for segment in sample["segments"] if segment["name"] == name) for sample in samples]) for name in segment_names},
                         "samples": samples, "readEvents": reads, "allBoundaryEvents": events,
                         "outlierSampleOrder": sorted(range(30), key=lambda number: saves[number]["elapsedMs"], reverse=True)}
    require(fixtures[0] == fixtures[1] == fixtures[2], "fixture evidence differs across the initial IDLE/public sequence binding")
    return {"status": "COMPLETE_DIAGNOSTIC_TRACE_ONLY", "schema": 1, "saveCount": 90, "modes": outputs,
            "limits": ["Candidate-only one-seed diagnostic; no B-to-C1 causality or acceptance gate closure.",
                       "IDLE has different readiness; mode medians are not additive causal costs.",
                       "Same-thread CPU counters include intervening work; cross-thread CPU is null.",
                       "SQLite END includes COMMIT/ROLLBACK and connection release; it is not fsync-only or lock-wait-only.",
                       "Room post-END includes invalidation scheduling; caller-return tail includes context and scheduling.",
                       "Executor run-exit may follow caller return and is not used as a disjoint save segment endpoint.",
                       "Marker completeness alone does not prove test/JUnit, process exit or cleanup success; independent terminal audit remains required.",
                       "Per-component medians must not be summed into the median total."], "rawMarkers": markers}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--markers", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    data = args.markers.read_bytes()
    result = analyze(load_markers(data.decode("utf-8-sig")))
    result["source"] = {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(), "name": args.markers.name}
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": result["status"], "saves": result["saveCount"], "source": result["source"]}))


if __name__ == "__main__":
    main()
