"""Entirely synthetic contract/fault tests; these numbers are not Android evidence."""
import copy
import json
import unittest
from pathlib import Path
import analyze as a


def synthetic():
    markers = []
    order = ("save-request writer-submit writer-run-enter room-begin-enter sqlite-begin-enter sqlite-begin-exit "
             "room-begin-exit save-writer-acquired save-head-read save-head-written save-revision-written "
             "save-receipt-written room-success-enter sqlite-success-enter sqlite-success-exit room-success-exit "
             "room-end-enter sqlite-end-enter sqlite-end-exit room-end-exit writer-run-exit save-returned").split()
    for mode in a.MODES:
        events, saves = [], []
        for sample in range(30):
            start = 1000000 + sample * 1000
            for n, stage in enumerate(order, 1):
                tx = (100 + sample if stage.startswith("room-") else 200 + sample if stage.startswith("sqlite-") else 0)
                success = stage.startswith(("room-", "sqlite-")) and stage.split("-", 1)[1] in ("success-exit", "end-enter", "end-exit")
                outcome = 0 if stage.endswith("-enter") or stage == "writer-submit" else 1
                task = 0 if stage in ("save-request", "save-returned") else sample + 1
                thread = 1 if stage in ("save-request", "save-returned", "writer-submit") else 2
                events.append([a.STAGES.index(stage), sample, task, tx, int(tx > 0), int(success), outcome, start + n, (start + n) // 2, thread])
            saves.append([sample, start, start + 23, sample + 2, 1])
        stages = {
            "IDLE": ["connection-open", "snapshot-open", "pin-read", "snapshot-closed", "connection-closed", "idle-ready"],
            "HELD_SNAPSHOT": ["connection-open", "snapshot-open", "pin-read", "hold-enter", "hold-exit", "pin-recheck", "snapshot-closed", "connection-closed"],
            "ACTIVE_SEARCH": a.READ_STAGES[:9] + ["active-ready"] + a.READ_STAGES[9:21],
        }[mode]
        reads = []
        for n, stage in enumerate(stages):
            owner = int(mode == "ACTIVE_SEARCH" and stage != "active-ready")
            after = (mode == "HELD_SNAPSHOT" and n >= 4) or (mode == "ACTIVE_SEARCH" and n >= 10)
            wall = (2000000 if after else 100) + n * 10
            reads.append([owner, a.READ_STAGES.index(stage), owner, 1 if stage in ("pin-read", "pin-recheck") else -1, wall, n + 10, 3])
        result = dict(schema=1, diagnosticOnly=True, seed=4101, mode=mode,
                      readiness=dict(zip(a.MODES, ("reader-closed", "main-snapshot-pinned", "first-source-check")))[mode],
                      fixture={"logicalSha256": a.prior.EXPECTED_FIXTURE_SHA256, "rowCounts": a.prior.ROW_COUNTS,
                               "writerSqlite": {"page_size": 1024, "auto_vacuum": 1, "journal_mode": "wal", "synchronous": 2}},
                      startRevision=1, finalRevision=31, readerBeforeRevision=-1 if mode == "ACTIVE_SEARCH" else 1,
                      readerAfterRevision=1 if mode == "HELD_SNAPSHOT" else -1, saves=saves)
        values = [("RESULT", result)]
        for kind, rows in (("EVENTS", events), ("READS", reads)):
            for offset in range(0, len(rows), 96):
                values.append((kind, dict(schema=1, mode=mode, part=offset // 96, events=rows[offset:offset + 96])))
        values.append(("END", dict(schema=1, mode=mode, completed=True, coverageValid=True, committedSaves=30,
                                   recordedEvents=len(events), recordedReadEvents=len(reads), droppedEvents=0,
                                   droppedReadEvents=0, invalid=False, readerExited=True, writersExited=True,
                                   databaseClosed=True, databaseDeleted=True, cleanupErrors=0)))
        markers += [{"kind": kind, "line": len(markers) + n + 1, "value": value} for n, (kind, value) in enumerate(values)]
    return markers


class AnalysisTests(unittest.TestCase):
    def test_complete_synthetic(self):
        result = a.analyze(synthetic())
        self.assertEqual(90, result["saveCount"])
        for mode in a.MODES:
            self.assertEqual(0.000023, result["modes"][mode]["saveWallMs"]["p50"])
            self.assertEqual(30, len(result["modes"][mode]["samples"]))
            self.assertEqual(12, len(result["modes"][mode]["samples"][0]["segments"]))

    def test_faults_rejected(self):
        mutations = [
            lambda m: m.pop(),
            lambda m: m.reverse(),
            lambda m: m[0]["value"].update(readiness="wrong"),
            lambda m: m[0]["value"]["saves"][4].__setitem__(4, 0),
            lambda m: m[1]["value"]["events"][0].__setitem__(0, 24),
            lambda m: m[1]["value"]["events"][0].__setitem__(1, True),
            lambda m: m[-1]["value"].update(databaseDeleted=False),
            lambda m: m[-1]["value"].update(droppedEvents=1),
            lambda m: m[-1]["value"].update(coverageValid=False),
            lambda m: m[-1]["value"].update(extra="not in schema"),
            lambda m: next(v["value"] for v in m if v["kind"] == "READS" and v["value"]["mode"] == "IDLE")["events"][2].__setitem__(6, 4),
            lambda m: next(v["value"] for v in m if v["kind"] == "READS" and v["value"]["mode"] == "HELD_SNAPSHOT")["events"][5].__setitem__(3, 31),
            lambda m: next(v["value"] for v in m if v["kind"] == "READS" and v["value"]["mode"] == "ACTIVE_SEARCH")["events"][10].__setitem__(4, 900000),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                markers = synthetic(); mutate(markers)
                with self.assertRaises((ValueError, KeyError)):
                    a.analyze(markers)

    def test_text_roundtrip_and_duplicate_keys(self):
        original = synthetic()
        text = "\n".join(a.PREFIX + m["kind"] + " " + json.dumps(m["value"]) for m in original)
        parsed = a.load_markers(text)
        self.assertEqual(a.analyze(original)["modes"], a.analyze(parsed)["modes"])
        with self.assertRaises(ValueError):
            a.load_markers(a.PREFIX + 'END {"mode":"IDLE","mode":"ACTIVE_SEARCH"}')

    def test_source_contract_mapping(self):
        contract = json.loads(Path(__file__).with_name("source-contract.json").read_text())
        self.assertEqual(a.STAGES, contract["boundaryStages"])
        self.assertEqual(a.READ_STAGES, contract["readStages"])
        self.assertEqual(list(a.OUTCOMES), contract["outcomes"])
        self.assertEqual(a.FIELDS, {k: set(v) for k, v in contract["fields"].items()})


if __name__ == "__main__":
    unittest.main()
