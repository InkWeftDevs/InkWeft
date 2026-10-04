import hashlib
import json
from pathlib import Path
import shutil
import tempfile
import unittest

from room_evidence import BASELINE, INPUT_PATHS, input_hashes, room_evidence
from six_batch_scope import build_plan
from test_six_batch_workflow import ROOT, assigned_app_methods, run_regression


class RoomEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.baseline = json.loads((ROOT / BASELINE).read_text())
        for name in (*INPUT_PATHS, BASELINE, self.baseline["receipt"]):
            source, target = ROOT / name, self.root / name
            if not source.exists():
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            if source.is_dir():
                shutil.copytree(source, target)
            else:
                shutil.copyfile(source, target)
        # Unit fixture follows current source, so legitimate future changes still test fallback.
        self.baseline["input_sha256"] = input_hashes(self.root)
        (self.root / BASELINE).write_text(json.dumps(self.baseline))

    def evidence(self, mode="focused"):
        return room_evidence(self.root, mode, 180, self.baseline["room_classes"])

    def test_exact_content_reuses_actual_receipt_but_full_always_executes(self):
        evidence = self.evidence()
        self.assertEqual("REUSED_EVIDENCE", evidence["status"])
        self.assertEqual(37209060586, evidence["source_run"])
        self.assertEqual("46d2967a2f35756ddf8536c395cd85ccd6216dc2", evidence["source_commit"])
        self.assertEqual(180, evidence["reused_passed"])
        self.assertEqual("RUN_REQUIRED", self.evidence("full")["status"])
        self.assertEqual("RUN_REQUIRED", room_evidence(self.root, "focused", 180, ["changed-selection"])["status"])

    def test_each_source_schema_test_and_build_input_change_requires_fresh_room(self):
        for name in INPUT_PATHS:
            with self.subTest(input=name):
                path = self.root / name
                probe = path / "changed-input" if path.is_dir() else path
                original = probe.read_bytes() if probe.exists() else None
                probe.parent.mkdir(parents=True, exist_ok=True)
                probe.write_bytes((original or b"") + b"changed")
                self.assertEqual("RUN_REQUIRED", self.evidence()["status"])
                if original is None:
                    probe.unlink()
                else:
                    probe.write_bytes(original)
        self.assertEqual("REUSED_EVIDENCE", self.evidence()["status"])

    def test_new_android_source_sets_cannot_reuse_old_evidence(self):
        for name in ("android/data-local/src/debug/kotlin/DebugBehavior.kt",
                     "android/data-local/src/androidTestDebug/kotlin/DebugTest.kt",
                     "android/core-domain/src/custom/kotlin/NewSource.kt"):
            with self.subTest(source=name):
                path = self.root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("// new source input")
                self.assertEqual("RUN_REQUIRED", self.evidence()["status"])
                path.unlink()

    def test_stored_baseline_preserves_actual_receipt_and_declared_input_coverage(self):
        baseline = json.loads((ROOT / BASELINE).read_text())
        receipt = (ROOT / baseline["receipt"]).read_bytes()
        self.assertEqual(hashlib.sha256(receipt).hexdigest(), baseline["receipt_sha256"])
        self.assertEqual(180, json.loads(receipt)["passed"])
        self.assertEqual(set(INPUT_PATHS), set(baseline["input_sha256"]))
        self.assertEqual(11306726676, baseline["artifact_id"])
        self.assertEqual("6a37aca6ef053f8caa5a9f6bb5cff27b49e64eaf", baseline["matching_local_source_commit"])

    def test_missing_or_invalid_receipt_cannot_reuse(self):
        path = self.root / self.baseline["receipt"]
        original = path.read_bytes()
        for contents in (b"{}", b"corrupt"):
            path.write_bytes(contents)
            self.assertEqual("RUN_REQUIRED", self.evidence()["status"])
        path.unlink()
        self.assertEqual("RUN_REQUIRED", self.evidence()["status"])
        # Even a freshly hashed receipt cannot convert failure into reusable evidence.
        receipt = json.loads(original)
        receipt["failures"] = ["a-real-failure"]
        path.write_text(json.dumps(receipt))
        self.baseline["receipt_sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
        (self.root / BASELINE).write_text(json.dumps(self.baseline))
        self.assertEqual("RUN_REQUIRED", self.evidence()["status"])

    def test_runner_rechecks_inputs_and_reports_reuse_as_zero_new_execution(self):
        plan = build_plan("focused")
        selections = []
        run_regression(self.root, plan, 0, selections, shard_count=1)
        self.assertEqual(assigned_app_methods(plan), selections)
        summary = json.loads((self.root / "android/build/evidence/room-summary.json").read_text())
        self.assertEqual("REUSED_EVIDENCE", summary["status"])
        self.assertEqual((0, 0, 0, 180, 180), tuple(summary[key] for key in
                         ("expected", "passed", "executed", "planned_total", "reused_passed")))

    def test_stale_plan_reuse_falls_back_to_all_original_room_methods(self):
        plan = build_plan("focused")
        plan["room_evidence"] = {"status":"REUSED_EVIDENCE"}  # A stale planner result must never be trusted.
        path = self.root / "android/data-local/build.gradle.kts"
        path.write_text(path.read_text() + "\n// changed since planning\n")
        selections = []
        run_regression(self.root, plan, 0, selections, shard_count=1)
        self.assertEqual(plan["room"], [selection for selection in selections if "#" not in selection])
        self.assertEqual(assigned_app_methods(plan), [selection for selection in selections if "#" in selection])
        summary = json.loads((self.root / "android/build/evidence/room-summary.json").read_text())
        self.assertEqual((180, 180), (summary["expected"], summary["passed"]))
        evidence = json.loads((self.root / "android/build/evidence/room-input-evidence.json").read_text())
        self.assertEqual("RUN_REQUIRED", evidence["status"])
