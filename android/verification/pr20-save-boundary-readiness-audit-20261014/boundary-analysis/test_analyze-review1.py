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
                events.append(dict(stage=stage, sample=number, task=0 if caller else task, transaction=transaction,
                                   depth=depth, successRequested=success, outcome=outcome, wallNs=base + tick,
                                   threadCpuNs=(base + tick) * (2 if caller else 1), threadId=8 if caller else 7))

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
                      fixture=dict(logicalSha256="0" * 64, rowCounts=dict(notes=501, notebook_pages=20001, page_search_text=20000),
                                   writerSqlite=dict(page_size=1024, auto_vacuum=1, journal_mode="wal", synchronous=2)))
        markers.append(dict(kind="RESULT", line=len(markers) + 1, value=result))
        for part, offset in enumerate(range(0, len(events), 96)):
            markers.append(dict(kind="EVENTS", line=len(markers) + 1, value=dict(schema=1, mode=mode, part=part, events=events[offset:offset + 96])))
        markers.append(dict(kind="END", line=len(markers) + 1, value=dict(schema=1, mode=mode, completed=True, committedSaves=30,
                            recordedEvents=len(events), recordedReadEvents=0, droppedEvents=0, droppedReadEvents=0, invalid=False)))
    return markers


def events(markers):
    return [event for marker in markers if marker["kind"] == "EVENTS" for event in marker["value"]["events"]]


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


if __name__ == "__main__":
    unittest.main(verbosity=2)
