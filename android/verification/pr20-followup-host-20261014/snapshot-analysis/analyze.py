#!/usr/bin/env python3
"""Decode the new controlled snapshot experiment; no native or acceptance result is implied."""
import argparse
import hashlib
import json
import re
from pathlib import Path
import boundary_segments as prior

PREFIX = "LIBRARY_SNAPSHOT_CONTROL_"
MODES = ("IDLE", "HELD_SNAPSHOT", "ACTIVE_SEARCH")
STAGES = ("save-request save-writer-acquired save-head-read save-head-written save-revision-written "
          "save-receipt-written save-returned save-failed writer-submit writer-run-enter writer-run-exit "
          "writer-submit-failed room-begin-enter room-begin-exit room-success-enter room-success-exit "
          "room-end-enter room-end-exit sqlite-begin-enter sqlite-begin-exit sqlite-success-enter "
          "sqlite-success-exit sqlite-end-enter sqlite-end-exit").split()
READ_STAGES = ("read-requested read-start connection-open snapshot-open drafts-ready scope-start scope-end "
               "hits-ready page-hits-start page-hits-end card-hits-start card-hits-end page-start page-end "
               "metadata-start metadata-end pending-start pending-end snapshot-closed connection-closed "
               "read-end read-cancel read-error pin-read hold-enter hold-exit pin-recheck idle-ready active-ready").split()
OUTCOMES = ("entered", "returned", "threw")
require = prior.require
FIELDS = {
    "RESULT": set("schema diagnosticOnly seed mode readiness fixture startRevision finalRevision readerBeforeRevision readerAfterRevision saves".split()),
    "EVENTS": set("schema mode part events".split()),
    "READS": set("schema mode part events".split()),
    "END": set("schema mode completed coverageValid committedSaves recordedEvents recordedReadEvents droppedEvents droppedReadEvents invalid readerExited writersExited databaseClosed databaseDeleted cleanupErrors".split()),
}


def integer_row(row, limits):
    require(type(row) is list and len(row) == len(limits), "integer array width")
    require(all(prior.integer(v, lo, hi) for v, (lo, hi) in zip(row, limits)), "integer array bounds/types")


def boundary(row):
    integer_row(row, [(0, 23), (-1, 29), (0, 999999), (0, 999999), (0, 8), (0, 1), (0, 2),
                      (0, 600000000000), (0, 600000000000), (1, 2147483647)])
    return dict(zip(("stage", "sample", "task", "transaction", "depth", "successRequested", "outcome",
                     "wallNs", "threadCpuNs", "threadId"),
                    [STAGES[row[0]], *row[1:5], bool(row[5]), OUTCOMES[row[6]], *row[7:]]))


def read_event(row):
    integer_row(row, [(0, 1), (0, 28), (0, 1), (-1, 20001), (0, 600000000000),
                      (0, 600000000000), (1, 2147483647)])
    return dict(zip(("owner", "stage", "read", "count", "wallNs", "threadCpuNs", "threadId"),
                    [row[0], READ_STAGES[row[1]], *row[2:]]))


def load_markers(text):
    markers = []
    for number, line in enumerate(text.splitlines(), 1):
        if PREFIX not in line:
            continue
        require(line.count(PREFIX) == 1, "duplicate marker prefix")
        match = re.fullmatch(r"LIBRARY_SNAPSHOT_CONTROL_(RESULT|EVENTS|READS|END) (\{.*\})\s*", line[line.index(PREFIX):])
        require(match is not None, f"malformed marker on line {number}")
        kind, payload = match.groups()
        require(len(payload.encode()) <= 32768, "oversized marker")
        value = json.loads(payload, object_pairs_hook=prior.unique_object,
                          parse_constant=lambda x: (_ for _ in ()).throw(ValueError(x)))
        markers.append({"kind": kind, "line": number, "value": value})
    return markers


def chunks(group, kind, count, cap):
    require(prior.integer(count, 0, cap), "event capacity")
    parts = group[kind]
    require(len(parts) == (count + 95) // 96, "chunk count")
    output = []
    for number, part in enumerate(parts):
        require(type(part.get("part")) is int and part["part"] == number, "chunk order")
        require(type(part.get("events")) is list and len(part["events"]) == min(96, count - 96 * number), "chunk length")
        output.extend(part["events"])
    return output


def coverage(mode, reads, saves, result):
    def point(owner, stage):
        return prior.single([e for e in reads if e["owner"] == owner and e["stage"] == stage], f"{mode}/{stage}")
    first, last = saves[0]["startNs"], saves[-1]["endNs"]
    if mode == "IDLE":
        closed, ready = point(0, "connection-closed"), point(0, "idle-ready")
        require(closed["wallNs"] <= ready["wallNs"] <= first, "IDLE reader not closed before saves")
        require(result["readerBeforeRevision"] == 1 and result["readerAfterRevision"] == -1, "IDLE revision witness")
        begin, end = point(0, "connection-open"), closed
        lifecycle = [point(0, s) for s in ("connection-open", "snapshot-open", "pin-read", "snapshot-closed", "connection-closed", "idle-ready")]
        require(len(reads) == len(lifecycle) and lifecycle[2]["count"] == 1, "IDLE pin lifecycle")
        require(all(e["owner"] == 0 and e["threadId"] == begin["threadId"] for e in reads), "IDLE reader thread")
        require(all(a["wallNs"] <= b["wallNs"] for a, b in zip(lifecycle, lifecycle[1:])), "IDLE lifecycle order")
    elif mode == "HELD_SNAPSHOT":
        pin, begin, end, recheck = [point(0, s) for s in ("pin-read", "hold-enter", "hold-exit", "pin-recheck")]
        require(result["readerBeforeRevision"] == result["readerAfterRevision"] == pin["count"] == recheck["count"] == 1, "held revision not pinned")
        require(pin["wallNs"] <= begin["wallNs"] <= first <= last <= end["wallNs"] <= recheck["wallNs"] <= point(0, "snapshot-closed")["wallNs"], "held coverage")
        require(all(e["owner"] == 0 and e["threadId"] == pin["threadId"] for e in reads), "held reader changed threads")
        lifecycle = [point(0, s) for s in ("connection-open", "snapshot-open", "pin-read", "hold-enter", "hold-exit", "pin-recheck", "snapshot-closed", "connection-closed")]
        require(len(reads) == len(lifecycle) and all(a["wallNs"] <= b["wallNs"] for a, b in zip(lifecycle, lifecycle[1:])), "held lifecycle")
    else:
        # One successful read has the complete fixed phase sequence. The scalar
        # coverage flag cannot stand in for a missing connection/snapshot witness.
        expected_stages = READ_STAGES[:9] + ["active-ready"] + READ_STAGES[9:21]
        require([e["stage"] for e in reads] == expected_stages, "ACTIVE complete lifecycle")
        require(all(e["owner"] == (0 if e["stage"] == "active-ready" else 1) for e in reads), "ACTIVE owners")
        require(all(a["wallNs"] <= b["wallNs"] for a, b in zip(reads, reads[1:])), "ACTIVE lifecycle order")
        begin, end = point(1, "page-hits-start"), point(1, "page-hits-end")
        ready = point(0, "active-ready")
        require(point(1, "snapshot-open")["wallNs"] <= begin["wallNs"] <= ready["wallNs"] <= first <= last <= end["wallNs"] <= point(1, "snapshot-closed")["wallNs"] <= point(1, "read-end")["wallNs"], "ACTIVE page query failed complete coverage")
        require(begin["threadId"] == ready["threadId"] == end["threadId"], "ACTIVE SQL thread changed")
        opened = point(1, "connection-open")
        first_native = expected_stages.index("connection-open")
        last_native = expected_stages.index("connection-closed")
        require(all(e["threadId"] == opened["threadId"] for e in reads[first_native:last_native + 1]), "ACTIVE native connection changed threads")
        require(result["readerBeforeRevision"] == result["readerAfterRevision"] == -1, "ACTIVE has fabricated pin witness")
        require(all(e["read"] == 1 for e in reads if e["owner"] == 1), "ACTIVE read identity")
        require(not any(e["stage"] in ("read-cancel", "read-error") for e in reads), "ACTIVE failed read")
    return prior.segment("held_wait" if mode == "HELD_SNAPSHOT" else "active_source_query" if mode == "ACTIVE_SEARCH" else "idle_reader_setup", begin, end)


def analyze(markers):
    groups = {mode: {kind: [] for kind in ("RESULT", "EVENTS", "READS", "END")} for mode in MODES}
    for marker in markers:
        value = marker["value"]
        require(type(value) is dict and value.get("mode") in MODES and type(value.get("schema")) is int and value["schema"] == 1, "mode/schema")
        prior.shape(value, FIELDS[marker["kind"]], marker["kind"])
        groups[value["mode"]][marker["kind"]].append(value)
    require([(m["value"]["mode"], m["kind"]) for m in markers] ==
            [(mode, kind) for mode in MODES for kind in ("RESULT", "EVENTS", "READS", "END") for _ in groups[mode][kind]], "marker sequence")
    outputs = {}
    for mode, group in groups.items():
        result = prior.single(group["RESULT"], mode + " RESULT")
        end = prior.single(group["END"], mode + " END")
        require(result.get("diagnosticOnly") is True and type(result.get("seed")) is int and result["seed"] == 4101, "seed/scope")
        require(all(type(result.get(k)) is int for k in ("startRevision", "finalRevision", "readerBeforeRevision", "readerAfterRevision")), "revision integer types")
        require(result.get("startRevision") == 1 and result.get("finalRevision") == 31, "revision span")
        require(result.get("readiness") == dict(zip(MODES, ("reader-closed", "main-snapshot-pinned", "first-source-check")))[mode], "readiness")
        require(end.get("completed") is True and end.get("coverageValid") is True and end.get("invalid") is False, "incomplete/invalid/coverage")
        require(end.get("committedSaves") == 30 and end.get("droppedEvents") == end.get("droppedReadEvents") == 0, "counts/drops")
        require(all(type(end.get(k)) is int for k in ("committedSaves", "recordedEvents", "recordedReadEvents", "droppedEvents", "droppedReadEvents")), "count integer types")
        require(all(end.get(k) is True for k in ("readerExited", "writersExited", "databaseClosed", "databaseDeleted")) and type(end.get("cleanupErrors")) is int and end["cleanupErrors"] == 0, "test resource cleanup")
        fixture = result.get("fixture")
        require(fixture == {"logicalSha256": prior.EXPECTED_FIXTURE_SHA256, "rowCounts": prior.ROW_COUNTS,
                            "writerSqlite": {"page_size": 1024, "auto_vacuum": 1, "journal_mode": "wal", "synchronous": 2}}, "fixture/settings")
        require(all(type(v) is int for v in fixture["rowCounts"].values()) and
                all(type(fixture["writerSqlite"][k]) is int for k in ("page_size", "auto_vacuum", "synchronous")), "fixture integer types")
        events = list(map(boundary, chunks(group, "EVENTS", end["recordedEvents"], 1536)))
        reads = list(map(read_event, chunks(group, "READS", end["recordedReadEvents"], 128)))
        require(all(e["read"] == e["owner"] for e in reads), "read identity by owner")
        prior.thread_order(events, mode + " boundaries"); prior.thread_order(reads, mode + " reads")
        submits = [e["task"] for e in events if e["stage"] == "writer-submit"]
        exits = [e["task"] for e in events if e["stage"] in ("writer-run-exit", "writer-submit-failed")]
        require(len(submits) == len(set(submits)) and sorted(exits) == sorted(submits), "writer ledger")
        raw_saves = result.get("saves")
        require(type(raw_saves) is list and len(raw_saves) == 30, "30 saves required")
        saves = []
        for number, row in enumerate(raw_saves):
            integer_row(row, [(number, number), (0, 600000000000), (0, 600000000000), (number + 2, number + 2), (1, 1)])
            require(row[1] <= row[2] and (not saves or saves[-1]["endNs"] <= row[1]), "save order")
            saves.append({"sample": number, "startNs": row[1], "endNs": row[2], "revision": row[3], "committed": True,
                          "elapsedMs": (row[2] - row[1]) / 1e6})
        samples = [prior.analyze_sample(mode, s, events) for s in saves]
        names = [s["name"] for s in samples[0]["segments"]]
        outputs[mode] = {"saveWallMs": prior.quantiles([s["elapsedMs"] for s in saves]),
                         "segmentsWallMs": {n: prior.quantiles([next(v["wallMs"] for v in s["segments"] if v["name"] == n) for s in samples]) for n in names},
                         "readerInterval": coverage(mode, reads, saves, result), "samples": samples,
                         "readEvents": reads, "allBoundaryEvents": events,
                         "outlierSampleOrder": sorted(range(30), key=lambda n: saves[n]["elapsedMs"], reverse=True)}
    return {"status": "COMPLETE_CONTROLLED_TRACE_ONLY_PENDING_TERMINAL_AUDIT", "saveCount": 90, "modes": outputs,
            "limits": ["Single fixed-order seed, observer enabled; no public acceptance or causal B/C result.",
                       "HELD tests snapshot sufficiency; ACTIVE additionally includes SQL/UDF CPU, I/O and scheduling.",
                       "Low CPU END is not proof of checkpoint, fsync or lock wait. No indexer is present.",
                       "No quantitative pass band or automatic production-fix decision is introduced.",
                       "Cross-thread CPU is null; component quantiles are not additive.",
                       "Official JUnit, process exit and exact cleanup must be audited separately."], "rawMarkers": markers}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--markers", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    data = args.markers.read_bytes()
    output = analyze(load_markers(data.decode("utf-8-sig")))
    output["inputSha256"] = hashlib.sha256(data).hexdigest()
    args.out.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"status": output["status"], "saveCount": output["saveCount"]}))


if __name__ == "__main__":
    main()
