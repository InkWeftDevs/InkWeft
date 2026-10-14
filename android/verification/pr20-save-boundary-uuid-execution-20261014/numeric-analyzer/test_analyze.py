"""Offline synthetic parser tests only. No fixture here is Android performance evidence."""
import copy
import importlib.util
import json
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("boundary_analysis", Path(__file__).with_name("analyze.py"))
analysis = importlib.util.module_from_spec(spec)
spec.loader.exec_module(analysis)


def fixture():
    markers = []
    for mode in analysis.MODES:
        events = []
        saves = []
        start_revision = 32 if mode == "OBSERVE" else 1
        for number in range(30):
            base = 1_000_000 * (number + 1)
            task = 100 + number
            room = 1000 + number
            sqlite = 2000 + number
            tick = 0

            def event(stage, transaction=0, depth=0, success=False, caller=False):
                nonlocal tick
                tick += 1000
                outcome = "entered" if stage.endswith("-enter") or stage == "writer-submit" else "returned"
                caller_thread = caller or stage == "writer-submit"
                events.append(dict(stage=stage, sample=number, task=0 if caller else task, transaction=transaction,
                                   depth=depth, successRequested=success, outcome=outcome, wallNs=base + tick,
                                   threadCpuNs=(base + tick) * (2 if caller_thread else 1), threadId=8 if caller_thread else 7))

            event("save-request", caller=True)
            event("writer-submit")
            event("writer-run-enter")
            event("room-begin-enter", room, 1)
            # A complete Room trigger-sync SQLite transaction must not be selected as
            # the actual outer author transaction merely because it is depth one.
            event("sqlite-begin-enter", 3000 + number, 1)
            event("sqlite-begin-exit", 3000 + number, 1)
            event("sqlite-success-enter", 3000 + number, 1)
            event("sqlite-success-exit", 3000 + number, 1, True)
            event("sqlite-end-enter", 3000 + number, 1, True)
            event("sqlite-end-exit", 3000 + number, 1, True)
            event("sqlite-begin-enter", sqlite, 1)
            event("sqlite-begin-exit", sqlite, 1)
            event("room-begin-exit", room, 1)
            event("save-writer-acquired")
            event("save-head-read")
            event("sqlite-begin-enter", sqlite, 2)
            event("sqlite-begin-exit", sqlite, 2)
            event("sqlite-success-enter", sqlite, 2)
            event("sqlite-success-exit", sqlite, 2, True)
            event("sqlite-end-enter", sqlite, 2, True)
            event("sqlite-end-exit", sqlite, 2, True)
            event("save-head-written")
            event("save-revision-written")
            event("save-receipt-written")
            event("room-success-enter", room, 1)
            event("sqlite-success-enter", sqlite, 1)
            event("sqlite-success-exit", sqlite, 1, True)
            event("room-success-exit", room, 1, True)
            event("room-end-enter", room, 1, True)
            event("sqlite-end-enter", sqlite, 1, True)
            event("sqlite-end-exit", sqlite, 1, True)
            event("room-end-exit", room, 1, True)
            event("save-returned", caller=True)
            caller_end = base + tick + 1
            event("writer-run-exit")
            saves.append(dict(sample=number, startNs=base, endNs=caller_end, elapsedMs=(caller_end - base) / 1e6,
                              committed=True, revision=start_revision + number + 1,
                              sourceChecksStart=number, sourceChecksEnd=number + 1, indexRowsStart=0, indexRowsEnd=0))
        result = dict(schema=1, diagnosticOnly=True, seed=4101, mode=mode,
                      readiness={"IDLE": "fixture-ready", "DIRECT": "first-source-check", "OBSERVE": "first-index-commit"}[mode],
                      startRevision=start_revision, saves=saves,
                      fixture=dict(logicalSha256=analysis.EXPECTED_FIXTURE_SHA256, rowCounts=analysis.ROW_COUNTS.copy(),
                                   writerSqlite=dict(page_size=1024, auto_vacuum=1, journal_mode="wal", synchronous=2)))
        markers.append(dict(kind="RESULT", line=len(markers) + 1, value=result))
        for part, offset in enumerate(range(0, len(events), 96)):
            markers.append(dict(kind="EVENTS", line=len(markers) + 1, value=dict(schema=1, mode=mode, part=part, events=events[offset:offset + 96])))
        reads = []
        if mode == "DIRECT":
            reads = [dict(owner="control", stage=stage, read=0, count=-1, wallNs=wall, threadCpuNs=wall, threadId=9) for stage,wall in [("direct-start",1000),("direct-end",31_000_000)]]
        elif mode == "OBSERVE":
            reads = [dict(owner="index",stage="committed",read=0,count=128,wallNs=1000,threadCpuNs=1000,threadId=9)]
        if reads:
            markers.append(dict(kind="READS",line=len(markers)+1,value=dict(schema=1,mode=mode,part=0,events=reads)))
        markers.append(dict(kind="END", line=len(markers) + 1, value=dict(schema=1, mode=mode, completed=True, committedSaves=30,
                            recordedEvents=len(events), recordedReadEvents=len(reads), droppedEvents=0, droppedReadEvents=0, invalid=False)))
    return markers


def events(markers):
    return [event for marker in markers if marker["kind"] == "EVENTS" for event in marker["value"]["events"]]


def repartition(markers, mode, kind, replacement):
    """Adversarial count-consistent editing, so semantic rejection is tested independently."""
    output = []
    written = False
    for marker in markers:
        value = marker["value"]
        if value["mode"] == mode and marker["kind"] == kind:
            if not written:
                for part, offset in enumerate(range(0,len(replacement),96)):
                    output.append(dict(kind=kind,line=0,value=dict(schema=1,mode=mode,part=part,events=replacement[offset:offset+96])))
                written = True
        else:
            output.append(marker)
        if value["mode"] == mode and marker["kind"] == "END":
            value["recordedEvents" if kind=="EVENTS" else "recordedReadEvents"] = len(replacement)
    return output


class ParserTests(unittest.TestCase):
    def test_complete_nested_trace_cross_thread_cpu_stays_null(self):
        output = analysis.analyze(fixture())
        self.assertEqual(90, output["saveCount"])
        for mode in analysis.MODES:
            sample = output["modes"][mode]["samples"][0]
            self.assertEqual(2000, sample["sqliteOuterTransaction"])
            self.assertTrue(sample["writerRunExitAfterCallerReturn"])
            tail = next(segment for segment in sample["segments"] if segment["name"] == "room_end_to_caller_return")
            self.assertIsNone(tail["threadCpuMs"])
            begin = next(segment for segment in sample["segments"] if segment["name"] == "sqlite_begin")
            self.assertGreater(begin["threadCpuMs"], 0)

    def test_markers_roundtrip(self):
        original = fixture()
        text = "\n".join("INSTRUMENTATION_STATUS: stream=LIBRARY_SAVE_BOUNDARY_" + marker["kind"] + " " + json.dumps(marker["value"]) for marker in original)
        self.assertEqual(90, analysis.analyze(analysis.load_markers(text))["saveCount"])

    def test_missing_outer_end_cannot_use_nested_end(self):
        values = fixture()
        target = next(event for event in events(values) if event["stage"] == "sqlite-end-exit" and event["transaction"] == 2000 and event["depth"] == 1)
        target["depth"] = 2
        with self.assertRaisesRegex(ValueError, "SQLite outer transaction"):
            analysis.analyze(values)

    def test_task_token_mismatch(self):
        values = fixture()
        next(event for event in events(values) if event["stage"] == "save-head-written")["task"] = 9999
        with self.assertRaisesRegex(ValueError, "task token mismatch"):
            analysis.analyze(values)

    def test_missing_part_rejected(self):
        values = fixture()
        values.pop(next(index for index, marker in enumerate(values) if marker["kind"] == "EVENTS"))
        with self.assertRaisesRegex(ValueError, "missing/extra part"):
            analysis.analyze(values)

    def test_overflow_invalid_and_count_rejected(self):
        for key, value in [("invalid", True), ("droppedEvents", 1), ("droppedReadEvents", 1), ("recordedReadEvents", 1), ("committedSaves", True)]:
            with self.subTest(key=key):
                values = fixture()
                next(marker["value"] for marker in values if marker["kind"] == "END")[key] = value
                with self.assertRaises(ValueError):
                    analysis.analyze(values)

    def test_unpaired_background_submit_rejected(self):
        values = fixture()
        target = next(event for event in events(values) if event["stage"] == "writer-run-exit")
        target["task"] = 999999
        with self.assertRaisesRegex(ValueError, "lacks unique exit"):
            analysis.analyze(values)

    def test_wrong_revision_or_readiness_rejected(self):
        for key, value in [("startRevision", 1), ("readiness", "fixture-ready")]:
            values = fixture()
            next(marker["value"] for marker in values if marker["kind"] == "RESULT" and marker["value"]["mode"] == "OBSERVE")[key] = value
            with self.assertRaisesRegex(ValueError, "readiness/revision"):
                analysis.analyze(values)

    def test_duplicate_keys_and_nonfinite_json_rejected(self):
        for payload in ['{"schema":1,"schema":1}', '{"schema":NaN}']:
            with self.assertRaises(ValueError):
                analysis.load_markers("LIBRARY_SAVE_BOUNDARY_RESULT " + payload)
        with self.assertRaisesRegex(ValueError,"budget"):
            analysis.load_markers('LIBRARY_SAVE_BOUNDARY_END {"extra":"'+'x'*4096+'"}')

    def test_every_required_author_boundary_missing_even_with_correct_counts(self):
        required = [event for event in events(fixture()) if event["sample"]==0 and event["transaction"]!=3000 and event["depth"]<=1][:22]
        self.assertEqual(22,len(required))
        for required_event in required:
            with self.subTest(stage=required_event["stage"]):
                values = fixture()
                original = [event for marker in values if marker["kind"]=="EVENTS" and marker["value"]["mode"]=="IDLE" for event in marker["value"]["events"]]
                remove = next(index for index,event in enumerate(original) if event["sample"]==0 and event["stage"]==required_event["stage"] and event["depth"]==required_event["depth"] and event["transaction"]==required_event["transaction"])
                original.pop(remove)
                with self.assertRaises(ValueError):
                    analysis.analyze(repartition(values,"IDLE","EVENTS",original))

    def test_cross_thread_enqueue_interleaving_is_preserved(self):
        values=fixture()
        parts=[marker["value"]["events"] for marker in values if marker["kind"]=="EVENTS"]
        first=parts[0]
        index=next(index for index,event in enumerate(first) if event["stage"]=="save-returned")
        first[index],first[index+1]=first[index+1],first[index]
        self.assertGreater(first[index]["wallNs"],first[index+1]["wallNs"])
        result=analysis.analyze(values)
        self.assertEqual("writer-run-exit",result["modes"]["IDLE"]["allBoundaryEvents"][index]["stage"])

    def test_body_thread_cpu_order_depth_and_outcome_rejected(self):
        for key,value in [("threadId",99),("threadCpuNs",0),("wallNs",0),("depth",1),("transaction",123),("outcome","entered")]:
            with self.subTest(key=key):
                values=fixture()
                next(event for event in events(values) if event["stage"]=="save-head-written")[key]=value
                with self.assertRaises(ValueError):
                    analysis.analyze(values)
        values=fixture()
        next(event for event in events(values) if event["depth"]==2)["threadId"]=100
        with self.assertRaisesRegex(ValueError,"worker thread"):
            analysis.analyze(values)

    def test_window_and_part_type_reordering_rejected(self):
        values=fixture()
        first_end=values.pop(next(index for index,marker in enumerate(values) if marker["kind"]=="END"))
        values.append(first_end)
        with self.assertRaisesRegex(ValueError,"reordered"):
            analysis.analyze(values)
        values=fixture()
        part=values.pop(next(index for index,marker in enumerate(values) if marker["kind"]=="READS"))
        values.insert(next(index for index,marker in enumerate(values) if marker["kind"]=="EVENTS" and marker["value"]["mode"]=="DIRECT"),part)
        with self.assertRaisesRegex(ValueError,"reordered"):
            analysis.analyze(values)

    def test_all_structures_are_closed(self):
        for scope in ["RESULT","END","EVENTS","save","fixture","rows","settings"]:
            with self.subTest(scope=scope):
                values=fixture()
                result=values[0]["value"]
                target=(next(marker["value"] for marker in values if marker["kind"]==scope) if scope in {"RESULT","END","EVENTS"}
                        else result["saves"][0] if scope=="save" else result["fixture"] if scope=="fixture"
                        else result["fixture"]["rowCounts"] if scope=="rows" else result["fixture"]["writerSqlite"])
                target["unexpected"]=1
                with self.assertRaises(ValueError):
                    analysis.analyze(values)

    def test_six_fixture_counts_exact(self):
        for table in analysis.ROW_COUNTS:
            values=fixture();values[0]["value"]["fixture"]["rowCounts"][table]+=1
            with self.assertRaisesRegex(ValueError,"fixture rows"):
                analysis.analyze(values)
        values=fixture()
        for marker in values:
            if marker["kind"]=="RESULT":
                marker["value"]["fixture"]["logicalSha256"]="0"*64
        with self.assertRaisesRegex(ValueError,"fixture hash"):
            analysis.analyze(values)

    def test_read_stage_and_required_read_witness_rejected(self):
        values=fixture()
        next(marker["value"]["events"][0] for marker in values if marker["kind"]=="READS")["stage"]="not-a-real-stage"
        with self.assertRaisesRegex(ValueError,"read identifiers"):
            analysis.analyze(values)
        for mode in ["DIRECT","OBSERVE"]:
            values=fixture()
            with self.assertRaisesRegex(ValueError,"window count"):
                analysis.analyze(repartition(values,mode,"READS",[]))
        for mode in ["DIRECT","OBSERVE"]:
            values=fixture()
            next(marker["value"]["events"][0] for marker in values if marker["kind"]=="READS" and marker["value"]["mode"]==mode)["stage"]="read-start"
            with self.assertRaises(ValueError):
                analysis.analyze(values)


if __name__ == "__main__":
    unittest.main(verbosity=2)
