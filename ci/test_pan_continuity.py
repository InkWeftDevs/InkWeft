"""Offline inventory, unchanged-runner selection and strict control-result guards."""
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import pan_continuity
from pan_continuity import PLAN, ROOT, classify, classify_formatted_positive, observe_fixed, resolve_fixed_commit, selections, valid_runner_summary, validate_manifest, validate_plan
from test_six_batch_workflow import run_regression


def raw_result(selection, failure=None):
    cls, method = selection.split("#")
    fields = f"INSTRUMENTATION_STATUS: class={cls}\nINSTRUMENTATION_STATUS: test={method}\nINSTRUMENTATION_STATUS: numtests=1\n"
    ending = "INSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\n" if failure is None else (
        "INSTRUMENTATION_STATUS: stack=" + failure + "\nINSTRUMENTATION_STATUS_CODE: -2\nFAILURES!!!\nTests run: 1, Failures: 1\n")
    return fields + "INSTRUMENTATION_STATUS_CODE: 1\n" + fields + ending + "INSTRUMENTATION_CODE: -1\n"


class PanContinuityTest(unittest.TestCase):
    def test_exact_inventory_and_original_parent(self):
        validate_plan()
        self.assertEqual([6, 4, 1], [len(PLAN["app_methods"][name]) for name in PLAN["app"]])
        self.assertEqual("d3edb986bf2f965c25160f69701edaf22cfbc3ad", PLAN["local_fixed_commit"])
        self.assertEqual("2febc36a2efbec23340ccded797b222d7d7d6eae", PLAN["fixed_tree"])
        self.assertEqual("3852d6e5153d1ab4962d1c13234747417b1d3d39", PLAN["baseline_commit"])
        for key, value in [("diagnostic_rounds", 3), ("expected_app", 12), ("room", ["anything"]), ("lint", True)]:
            plan = copy.deepcopy(PLAN)
            plan[key] = value
            with self.assertRaises(AssertionError):
                validate_plan(plan)

    def test_api_created_commit_requires_exact_tree_baseline_and_three_files(self):
        fixed = PLAN["published_fixed_commit"]
        self.assertNotEqual(fixed, PLAN["local_fixed_commit"])
        responses = {
            ("rev-parse", fixed + "^{commit}"): fixed,
            ("merge-base", "--is-ancestor", fixed, "HEAD"): "",
            ("rev-parse", fixed + "^{tree}"): PLAN["fixed_tree"],
            ("rev-parse", fixed + "^"): PLAN["baseline_commit"],
            ("diff", "--name-only", PLAN["baseline_commit"], fixed): "\n".join(PLAN["production_files"] + ["android/app/src/androidTest/java/org/inkweft/app/StreamedPanContinuityTest.kt"]),
        }
        def git(*args):
            if isinstance(responses[args], Exception):
                raise responses[args]
            return (responses[args] + "\n").encode()
        with patch("pan_continuity.git", side_effect=git):
            self.assertEqual(fixed, resolve_fixed_commit())
            for key, wrong in [(("rev-parse", fixed + "^{tree}"), "b" * 40),
                               (("rev-parse", fixed + "^"), "c" * 40),
                               (("diff", "--name-only", PLAN["baseline_commit"], fixed), "extra-file.kt")]:
                original = responses[key]
                responses[key] = wrong
                with self.assertRaises(AssertionError):
                    resolve_fixed_commit()
                responses[key] = original
            responses[("merge-base", "--is-ancestor", fixed, "HEAD")] = subprocess.CalledProcessError(1, "git merge-base")
            with self.assertRaises(subprocess.CalledProcessError):
                resolve_fixed_commit()

    def test_unchanged_runner_executes_only_the_eleven_methods_once(self):
        with tempfile.TemporaryDirectory() as directory:
            actual = []
            root = Path(directory)
            run_regression(root, PLAN, 0, actual, shard_count=1)
            self.assertEqual(selections(), actual)
            summary = json.loads((root / "android/build/evidence/app-summary.json").read_text())
            self.assertEqual(11, summary["passed"])
            self.assertEqual(11, summary["executed"])
            self.assertEqual([], summary["failures"])
            self.assertFalse((root / "android/build/evidence/room-summary.json").exists())

    def test_positive_requires_exact_identity_count_and_completed_junit(self):
        selection = selections()[0]
        raw = raw_result(selection)
        self.assertEqual("PASS", classify(raw, selection, 0))
        self.assertEqual("PASS", classify(raw.replace("INSTRUMENTATION_STATUS_CODE: 1\n", "INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS: stream=STAGE\nINSTRUMENTATION_STATUS_CODE: 0\n"), selection, 0))
        for bad in ["", raw.replace("numtests=1", "numtests=0"), raw.replace("OK (1 test)", "OK (0 tests)"),
                    raw.replace("INSTRUMENTATION_CODE: -1", ""), raw.replace("test=attached", "test=wrong"),
                    raw + "RUNNER_TIMEOUT", raw + "INSTRUMENTATION_FAILED: Process crashed",
                    "INSTRUMENTATION_STATUS_CODE: 0\n" + raw,
                    raw + "INSTRUMENTATION_STATUS_CODE: invalid\n", raw + "INSTRUMENTATION_CODE: invalid\n"]:
            self.assertNotEqual("PASS", classify(bad, selection, 0))
        self.assertNotEqual("PASS", classify(raw, selection, 124))

    def test_negative_accepts_only_the_original_pixel_assertion(self):
        selection = PLAN["negative_control"]
        failure = "java.lang.AssertionError: Retained ink disappeared at (130, 150)\n at org.inkweft.app.StreamedPanContinuityTest$Fixture.assertInk(StreamedPanContinuityTest.kt:64)"
        raw = raw_result(selection, failure)
        self.assertEqual("EXPECTED_ASSERTION_FAILURE", classify(raw, selection, 0, negative=True))
        self.assertEqual("FAIL", classify(raw, selection, 0))
        self.assertEqual("UNEXPECTED_PASS", classify(raw_result(selection), selection, 0, negative=True))
        for bad in [raw.replace("Retained ink disappeared", "Unrelated assertion"), raw + "Suppressed: cleanup failed",
                    raw.replace("STATUS_CODE: -2", "STATUS_CODE: -1"), raw.replace("Failures: 1", "Failures: 2"),
                    raw.replace("$Fixture.assertInk", "$Fixture.close"), raw + "RUNNER_TIMEOUT",
                    raw.replace("(130, 150)", "(150, 150)"), raw.replace("(130, 150)", "(100, 150)")]:
            self.assertNotEqual("EXPECTED_ASSERTION_FAILURE", classify(bad, selection, 0, negative=True))
        self.assertNotEqual("EXPECTED_ASSERTION_FAILURE", classify(raw, selection, 1, negative=True))

    def test_workflow_is_isolated_push_only_and_has_no_matrix_or_apk_upload(self):
        workflow = (ROOT / ".github/workflows/android-six-batches.yml").read_text()
        old, pan = workflow.split("\n  pan:\n")
        self.assertIn("if: github.ref != 'refs/heads/codex/pan-continuity-validation-20261016'", old)
        for text in ["github.event_name == 'push'", "github.ref == 'refs/heads/codex/pan-continuity-validation-20261016'",
                     "github.run_attempt == 1", "[pan-continuity]", "timeout-minutes: 45", "cache-disabled: true",
                     "contents: read", "retention-days: 3", "ci/pr20-causal/cleanup.py always"]:
            self.assertIn(text, pan)
        for text in ["matrix:", "workflow_dispatch", "chmod", "continue-on-error", "actions: write", "contents: write", "--full"]:
            self.assertNotIn(text, pan)
        self.assertEqual(1, pan.count("uses: actions/upload-artifact@"))
        self.assertIn("path: ${{ runner.temp }}/inkweft-pan/upload/", pan)
        self.assertIn("fetch-depth: 4", pan)
        self.assertIn('["shell", "am", "instrument", "-w", "-r", "-e", "class", PLAN["negative_control"]', (ROOT / "ci/pan_continuity.py").read_text())

    def test_actual_run_38887941132_formatted_logs_all_pass(self):
        fixture = json.loads((ROOT / "ci/fixtures/pan_continuity_run1.json").read_text())
        self.assertEqual("5faeb6e0150e2e8130c7025986bba4fde025f080e016cd96f9ed7188e0d3892d", fixture["artifact_sha256"])
        self.assertEqual(selections(), [case["selection"] for case in fixture["cases"]])
        self.assertTrue(valid_runner_summary(fixture["runner_summary"]))
        for case in fixture["cases"]:
            self.assertEqual(case["raw_sha256"], hashlib.sha256(case["raw"].encode()).hexdigest())
            self.assertEqual("PASS", classify_formatted_positive(case["raw"], case["selection"], 0))
            # These historical -w logs never contained raw -r protocol fields.
            self.assertEqual("INFRASTRUCTURE_OR_INCOMPLETE", classify(case["raw"], case["selection"], 0))

    def test_formatted_positive_rejects_missing_duplicate_wrong_or_failed_results(self):
        fixture = json.loads((ROOT / "ci/fixtures/pan_continuity_run1.json").read_text())
        case = fixture["cases"][0]
        raw, selection = case["raw"], case["selection"]
        for bad in ["", raw.replace("OK (1 test)", "OK (0 tests)"), raw.replace("OK (1 test)", "OK (2 tests)"),
                    raw + "OK (1 test)\n", raw.replace("StreamedPanContinuityTest:", "WrongTest:"),
                    raw.replace("Time:", "Incomplete:"), raw.replace(":.", ":.."), raw + "RUNNER_TIMEOUT",
                    raw.replace("OK (1 test)", "FAILURES!!!\nTests run: 1, Failures: 1")]:
            self.assertNotEqual("PASS", classify_formatted_positive(bad, selection, 0))
        self.assertNotEqual("PASS", classify_formatted_positive(raw, selection, 1))
        diagnostic = fixture["cases"][8]
        self.assertNotEqual("PASS", classify_formatted_positive(diagnostic["raw"].replace("STREAMED_FRAME_STAGE {", "STREAMED_FRAME_STAGE INVALID{"), diagnostic["selection"], 0))
        for key, value in [("passed", 10), ("executed", 10), ("diagnostic_rounds", 3), ("failures", [selection]), ("infrastructure", ["adb failure"])]:
            changed = dict(fixture["runner_summary"], **{key: value})
            self.assertFalse(valid_runner_summary(changed))

    def test_formatted_positive_requires_exact_round_selection_and_source_binding(self):
        fixture = json.loads((ROOT / "ci/fixtures/pan_continuity_run1.json").read_text())
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            control = root / "control"
            control.mkdir()
            (control / "source.json").write_text(json.dumps({"fixed_commit": fixture["fixed_commit"]}))
            for index, case in enumerate(fixture["cases"]):
                out = root / "android/build/evidence" / f"app-{index:03d}"
                out.mkdir(parents=True)
                (out / "selection.txt").write_text(case["selection"])
                (out / "instrumentation.txt").write_text(case["raw"])
                (out / "round.json").write_text(json.dumps(case["round"]))
            with patch.multiple(pan_continuity, ROOT=root, OUT=control, create=True):
                self.assertTrue(all(case["status"] == "PASS" for case in observe_fixed()))
                path = root / "android/build/evidence/app-000/round.json"
                for key, value in [("round", 2), ("source", "wrong"), ("selection", selections()[1])]:
                    path.write_text(json.dumps(dict(fixture["cases"][0]["round"], **{key: value})))
                    self.assertNotEqual("PASS", observe_fixed()[0]["status"])

    def test_instrumentation_manifest_requires_one_exact_runner_and_target(self):
        xml = ('    E: instrumentation (line=1)\n'
               '      A: android:name(0x01010003)="androidx.test.runner.AndroidJUnitRunner"\n'
               '      A: android:targetPackage(0x01010021)="org.inkweft.app.a0.insertion"\n')
        validate_manifest(xml)
        for bad in ["", xml + xml, xml.replace("a0.insertion", "a0.wrong"), xml.replace("AndroidJUnitRunner", "WrongRunner")]:
            with self.assertRaises(AssertionError):
                validate_manifest(bad)

    def test_packaging_excludes_apks_and_counts_metadata_in_twenty_mib_cap(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            evidence = root / "evidence"
            evidence.mkdir()
            (evidence / "result.txt").write_text("synthetic evidence")
            (evidence / "never-upload.apk").write_bytes(b"APK")
            with patch.multiple(pan_continuity, WORK=root, OUT=evidence, ROOT=root, create=True):
                pan_continuity.package()
            self.assertFalse((root / "upload/control/never-upload.apk").exists())
            self.assertTrue(json.loads((root / "upload/package.json").read_text())["complete"])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            evidence = root / "evidence"
            evidence.mkdir()
            with (evidence / "full.log").open("wb") as stream:
                stream.truncate(20 * 1024 * 1024)
            with patch.multiple(pan_continuity, WORK=root, OUT=evidence, ROOT=root, create=True):
                with self.assertRaises(AssertionError):
                    pan_continuity.package()
            self.assertFalse(json.loads((root / "upload/package.json").read_text())["complete"])
            self.assertFalse((root / "upload/control").exists())


if __name__ == "__main__":
    unittest.main()
