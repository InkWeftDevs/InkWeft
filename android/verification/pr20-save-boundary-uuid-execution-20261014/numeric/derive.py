"""Derived arithmetic only, after the frozen analyzer validates the real marker stream.

Raw arrays stay untouched. Sorting occurs only for order statistics and interval-union
geometry, never to establish event order or to pair endpoints. All paired endpoints
are unique in their read/task/sample and must already be in causal wall order.
"""
from pathlib import Path
import collections
import csv
import hashlib
import json
import math

ROOT = Path(__file__).resolve().parent
SOURCE = ROOT / "analysis.json"
assert hashlib.sha256(SOURCE.read_bytes()).hexdigest() == "05f728fae9fff2df0fc217ff6e3cb77a6cc69d0edae9bfa07853b7dc1c91f265"
data = json.loads(SOURCE.read_text())
assert data["status"] == "COMPLETE_DIAGNOSTIC_TRACE_ONLY" and data["saveCount"] == 90


def stats(values):
    values = sorted(values)
    if not values:
        return {"n": 0, "p50": None, "p95": None, "max": None}
    return {"n": len(values), "p50": values[math.ceil(len(values) * .50) - 1],
            "p95": values[math.ceil(len(values) * .95) - 1], "max": values[-1]}


def one(events, stage, required=True):
    found = [e for e in events if e["stage"] == stage]
    assert len(found) <= 1, stage
    assert found or not required, stage
    return found[0] if found else None


def interval(a, b):
    assert b["wallNs"] >= a["wallNs"]
    same = a["threadId"] == b["threadId"]
    if same:
        assert b["threadCpuNs"] >= a["threadCpuNs"]
    return {"begin": a, "end": b, "wallMs": (b["wallNs"] - a["wallNs"]) / 1e6,
            "threadCpuMs": (b["threadCpuNs"] - a["threadCpuNs"]) / 1e6 if same else None,
            "sameThread": same}


def overlap_ns(a, b, c, d):
    return max(0, min(b, d) - max(a, c))


def union_ns(intervals, lo, hi):
    # Geometric interval union; does not reorder raw event arrays or pair endpoints.
    clipped = sorted((max(lo, a), min(hi, b)) for a, b in intervals if max(lo, a) < min(hi, b))
    total = 0
    last = lo
    for a, b in clipped:
        total += max(0, b - max(last, a))
        last = max(last, b)
    return total


def endpoints(spans):
    return [(s["begin"]["wallNs"], s["end"]["wallNs"]) for s in spans]


phase_pairs = {
    "dispatch": ("read-requested", "read-start"),
    "connection_open": ("read-start", "connection-open"),
    "register_functions_and_begin": ("connection-open", "snapshot-open"),
    "drafts": ("snapshot-open", "drafts-ready"),
    "scope": ("scope-start", "scope-end"),
    "hit_temp_setup": ("scope-end", "hits-ready"),
    "page_hits": ("page-hits-start", "page-hits-end"),
    "card_hits": ("card-hits-start", "card-hits-end"),
    "result_page": ("page-start", "page-end"),
    "metadata": ("metadata-start", "metadata-end"),
    "pending": ("pending-start", "pending-end"),
}

result = {"status": "DERIVED_FROM_VALIDATED_ACTUAL_90_SAVE_TRACE", "run": 38543698114,
          "job": 115784421756, "sourceAnalysisSha256": hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
          "modes": {}, "limitations": [
              "One seed, diagnostic hooks enabled; no current-run observer-off overhead calibration.",
              "IDLE is an independent fixture; DIRECT then paused save then OBSERVE share a different database.",
              "Mode differences and component quantiles are not additive costs or B-to-C1 effects.",
              "Same-thread CPU includes intervening event-loop work; cross-thread CPU is null.",
              "Wall minus CPU is not an I/O, lock-wait, fsync, or scheduler-specific measurement.",
              "Read/index wall overlap is exposure, not additive CPU cost or causal author-save delay.",
              "Incomplete phases have no fabricated end/duration; lifecycle end may include cancellation cleanup.",
          ]}
segment_rows = []
sample_rows = []
for mode, m in data["modes"].items():
    samples = m["samples"]
    lo = samples[0]["save"]["startNs"]
    hi = samples[-1]["save"]["endNs"]
    out = {"saveWindow": {"startNs": lo, "endNs": hi, "wallMs": (hi-lo)/1e6},
           "saveWallMs": m["saveWallMs"], "segments": {}, "reads": [], "indexBatches": [],
           "indexWriterTasks": [], "samples": []}
    for name, wall in m["segmentsWallMs"].items():
        segs = [next(s for s in x["segments"] if s["name"] == name) for x in samples]
        cpu = stats([s["threadCpuMs"] for s in segs if s["threadCpuMs"] is not None])
        assert stats([s["wallMs"] for s in segs]) == {"n": 30, **{k: wall[k] for k in ("p50", "p95", "max")}}
        out["segments"][name] = {"wallMs": wall, "sameThreadCpuMs": cpu,
                                 "crossThreadCpuUnavailableCount": 30-cpu["n"]}
        segment_rows.append({"mode": mode, "segment": name,
                             **{f"wall_{k}_ms": wall[k] for k in ("p50", "p95", "max")},
                             "cpu_n": cpu["n"], **{f"same_thread_cpu_{k}_ms": cpu[k] for k in ("p50", "p95", "max")}})
    groups = collections.defaultdict(list)
    for e in m["readEvents"]:
        if e["owner"] == "read" and e["read"] > 0:
            groups[e["read"]].append(e)
    read_spans, snapshot_spans = [], []
    phase_spans = collections.defaultdict(list)
    for rid, es in groups.items():
        read = {"read": rid, "rawEvents": es, "phases": {}, "incompletePhases": []}
        start = one(es, "read-start", False)
        terminal = [e for e in es if e["stage"] in ("read-end", "read-cancel", "read-error")]
        assert len(terminal) <= 1
        if start:
            assert terminal
            read["ioEnvelope"] = interval(start, terminal[0])
            read["outcome"] = terminal[0]["stage"]
            read_spans.append(read["ioEnvelope"])
        else:
            assert not terminal
            read["outcome"] = "cancelled_before_io_start"
        op, close = one(es, "snapshot-open", False), one(es, "snapshot-closed", False)
        assert bool(op) == bool(close)
        if op:
            read["snapshotEnvelope"] = interval(op, close)
            snapshot_spans.append(read["snapshotEnvelope"])
        for phase, (a, b) in phase_pairs.items():
            first, last = one(es, a, False), one(es, b, False)
            if first and last:
                span = interval(first, last)
                read["phases"][phase] = span
                phase_spans[phase].append(span)
            elif first:
                read["incompletePhases"].append(phase)
        work = [e for e in es if e["stage"] in {x for pair in phase_pairs.values() for x in pair}]
        read["lastObservedWorkStage"] = work[-1]["stage"] if work else None
        out["reads"].append(read)

    batch = None
    for e in m["readEvents"]:
        if e["owner"] != "index":
            continue
        if e["stage"] == "preparing-batch":
            if batch:
                out["indexBatches"].append(batch)
            batch = {"ordinal": len(out["indexBatches"])+1, "rawEvents": []}
        assert batch is not None
        batch["rawEvents"].append(e)
    if batch:
        out["indexBatches"].append(batch)
    precompute_spans = []
    for batch in out["indexBatches"]:
        es = batch["rawEvents"]
        a, b = one(es, "preparing-batch"), one(es, "batch-precomputed", False)
        if b:
            batch["precompute"] = interval(a, b)
            precompute_spans.append(batch["precompute"])
        batch["precomputeCensored"] = b is None

    tasks = collections.defaultdict(list)
    for e in m["allBoundaryEvents"]:
        if e["sample"] == -1 and e["task"] > 0:
            tasks[e["task"]].append(e)
    for task, es in tasks.items():
        entry = one(es, "writer-run-enter", False)
        if not entry:
            # Leading background fragments are allowed by the frozen contract.
            continue
        exit_event = one(es, "writer-run-exit")
        enters = [e for e in es if e["stage"] == "room-begin-exit" and e["depth"] == 1]
        ends = [e for e in es if e["stage"] == "room-end-exit" and e["depth"] == 1]
        assert len(enters) == len(ends) == 1
        a, b = enters[0], ends[0]
        matching = [(batch, e) for batch in out["indexBatches"] for e in batch["rawEvents"]
                    if e["stage"] == "writer-acquired" and e["threadId"] == a["threadId"]
                    and a["wallNs"] <= e["wallNs"] <= b["wallNs"]]
        assert len(matching) == 1, (mode, task)
        batch, acquired = matching[0]
        written = one(batch["rawEvents"], "batch-written")
        sqlbegins = [e for e in es if e["stage"] == "sqlite-begin-exit" and e["depth"] == 1
                     and e["outcome"] == "returned" and e["wallNs"] <= acquired["wallNs"]]
        assert sqlbegins
        sql = max(sqlbegins, key=lambda e: e["wallNs"])
        sqlends = [e for e in es if e["stage"] == "sqlite-end-exit" and e["depth"] == 1
                   and e["transaction"] == sql["transaction"]]
        assert len(sqlends) == 1
        sqlend = sqlends[0]
        sqlendentries = [e for e in es if e["stage"] == "sqlite-end-enter" and e["depth"] == 1
                        and e["transaction"] == sql["transaction"]]
        assert len(sqlendentries) == 1
        out["indexWriterTasks"].append({"task": task, "batch": batch["ordinal"],
            "run": interval(entry, exit_event), "roomOuter": interval(a, b),
            "bodyAcquiredToWritten": interval(acquired, written),
            "sqliteEnd": interval(sqlendentries[0], sqlend), "rawEvents": es})

    read_intervals = endpoints(read_spans)
    snapshots = endpoints(snapshot_spans)
    index_intervals = endpoints([t["run"] for t in out["indexWriterTasks"]])
    out["readSummary"] = {
        "readStageCounts": dict(collections.Counter(e["stage"] for e in m["readEvents"] if e["owner"] == "read")),
        "outcomes": dict(collections.Counter(r["outcome"] for r in out["reads"])),
        "lastWorkStageCounts": dict(collections.Counter(r["lastObservedWorkStage"] for r in out["reads"])),
        "ioUnionOverlapWholeWindowMs": union_ns(read_intervals, lo, hi)/1e6,
        "snapshotUnionOverlapWholeWindowMs": union_ns(snapshots, lo, hi)/1e6,
        "completePrecomputeUnionOverlapWholeWindowMs": union_ns(endpoints(precompute_spans), lo, hi)/1e6,
        "indexWriterRunUnionOverlapWholeWindowMs": union_ns(index_intervals, lo, hi)/1e6,
        "phases": {},
    }
    for phase in phase_pairs:
        spans = phase_spans[phase]
        out["readSummary"]["phases"][phase] = {
            "complete": len(spans), "incomplete": sum(phase in r["incompletePhases"] for r in out["reads"]),
            "wallMs": stats([s["wallMs"] for s in spans]),
            "sameThreadCpuMs": stats([s["threadCpuMs"] for s in spans if s["threadCpuMs"] is not None]),
            "unionOverlapWholeWindowMs": union_ns(endpoints(spans), lo, hi)/1e6}
    for s in samples:
        a, b = s["save"]["startNs"], s["save"]["endNs"]
        segs = s["segments"]
        assert abs(sum(x["wallMs"] for x in segs) + s["outside_request_before_ms"]
                   + s["outside_return_after_ms"] - s["save"]["elapsedMs"]) < 1e-9
        queue = next(x for x in segs if x["name"] == "submit_to_worker")
        qa, qb = queue["begin"]["wallNs"], queue["end"]["wallNs"]
        # Unique actual worker segment endpoints define a same-thread CPU envelope.
        worker = interval(segs[2]["begin"], segs[10]["end"])
        assert worker["sameThread"]
        spans = {"readIoUnionOverlapMs": union_ns(read_intervals, a, b)/1e6,
                 "snapshotUnionOverlapMs": union_ns(snapshots, a, b)/1e6,
                 "indexWriterRunUnionOverlapMs": union_ns(index_intervals, a, b)/1e6,
                 "queueIndexWriterRunOverlapMs": union_ns(index_intervals, qa, qb)/1e6}
        row = {"sample": s["sample"], "save": s["save"], "segments": segs,
               "outsideRequestBeforeMs": s["outside_request_before_ms"],
               "outsideReturnAfterMs": s["outside_return_after_ms"], "workerThroughRoomEnd": worker,
               **spans, "overlappingReadIds": [r["read"] for r in out["reads"] if "ioEnvelope" in r
                   and overlap_ns(a,b,r["ioEnvelope"]["begin"]["wallNs"],r["ioEnvelope"]["end"]["wallNs"]) > 0]}
        out["samples"].append(row)
        sample_rows.append({"mode": mode, "sample": s["sample"], "saveWallMs": s["save"]["elapsedMs"],
                            **spans, "workerWallMs": worker["wallMs"], "workerThreadCpuMs": worker["threadCpuMs"]})
    out["indexQueueExposedSamples"] = [r["sample"] for r in out["samples"] if r["queueIndexWriterRunOverlapMs"] > 0]
    out["saveRanks"] = {label: sorted(out["samples"], key=lambda r:r["save"]["elapsedMs"])[idx]["sample"]
                        for label, idx in (("p50",14),("p95",28),("max",29))}
    out["workerWallMs"] = stats([s["workerThroughRoomEnd"]["wallMs"] for s in out["samples"]])
    out["workerSameThreadCpuMs"] = stats([s["workerThroughRoomEnd"]["threadCpuMs"] for s in out["samples"]])
    result["modes"][mode] = out

(ROOT / "derived.json").write_text(json.dumps(result, ensure_ascii=False, indent=2)+"\n")
for name, rows in (("segment-statistics.csv", segment_rows), ("save-overlaps.csv", sample_rows)):
    with (ROOT / name).open("w",newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0]))
        writer.writeheader(); writer.writerows(rows)
print(json.dumps({mode: {k: m[k] for k in ("saveRanks", "indexQueueExposedSamples", "workerWallMs", "workerSameThreadCpuMs")}
                  for mode,m in result["modes"].items()},indent=2))
