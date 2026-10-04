"""Exercise the existing runner against the three-shard workflow without a device."""
from contextlib import redirect_stdout
import io
import json
import os
from pathlib import Path
import re
import runpy
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest
from unittest.mock import patch

from android_plan import select_methods
from android_shards import partition
from six_batch_scope import build_plan, lint_requirement

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = (ROOT / ".github/workflows/android-six-batches.yml").read_text()
STEPS = re.split(r"^      - ", WORKFLOW, flags=re.M)[1:]


def step_containing(text):
    return next(step for step in STEPS if text in step)


def assigned_app_methods(plan):
    methods = []
    for name in plan["app"]:
        source = next((ROOT / "android/app/src/androidTest").rglob(name.rsplit(".", 1)[1] + ".kt"))
        available = re.findall(r"@Test(?:\([^)]*\))?\s+fun\s+(\w+)", source.read_text())
        methods.extend(name + "#" + method for method in select_methods(available, plan["app_methods"].get(name)))
    return methods


def run_regression(root, plan, shard, selections, failing=None, shard_count=3):
    """Run the unchanged CLI; replace only device commands and per-case setup."""
    (root / "ci").mkdir(exist_ok=True)
    script = root / "ci/run_android_tests.py"
    shutil.copyfile(ROOT / "ci/run_android_tests.py", script)
    source = root / "android/app/src/androidTest"
    source.parent.mkdir(parents=True, exist_ok=True)
    source.symlink_to(ROOT / "android/app/src/androidTest", target_is_directory=True)
    (root / "android-plan.json").write_text(json.dumps(plan))

    def adb(command, **kwargs):
        if command == ["adb", "get-serialno"]:
            return "emulator-5554\n"
        selection = command[command.index("class") + 1]
        selections.append(selection)
        if selection == failing:
            return "FAILURES!!!\nTests run: 1, Failures: 1\n"
        expected = 1 if "#" in selection else sum(plan["room_class_counts"][name] for name in selection.split(","))
        return f"OK ({expected} tests)\n"

    with patch.object(sys, "argv", [str(script), "--shard-index", str(shard), "--shard-count", str(shard_count)]), \
            patch("subprocess.check_output", side_effect=adb), \
            patch("subprocess.run", return_value=subprocess.CompletedProcess([], 0)), \
            patch("android_device.prepare_case"), redirect_stdout(io.StringIO()):
        runpy.run_path(str(script), run_name="__main__")


class SixBatchWorkflowTest(unittest.TestCase):
    def test_three_standard_runners_keep_failures_and_artifacts_independent(self):
        self.assertIn("shard: ${{ fromJSON((inputs.mode == 'full' || (github.event_name == 'push' && contains(github.event.head_commit.message, '[six-batches-full]'))) && '[0, 1, 2]' || '[0]') }}", WORKFLOW)
        self.assertIn("options: [focused, full]", WORKFLOW)
        self.assertIn("default: focused", WORKFLOW)
        self.assertIn("INKWEFT_MODE: ${{ (inputs.mode == 'full' || (github.event_name == 'push' && contains(github.event.head_commit.message, '[six-batches-full]'))) && 'full' || 'focused' }}", WORKFLOW)
        for expected in ("runs-on: ubuntu-24.04", "fail-fast: false", "cancel-in-progress: false",
                         "INKWEFT_SHARD_INDEX: ${{ matrix.shard }}", "INKWEFT_SHARD_COUNT: ${{ strategy.job-total }}"):
            self.assertIn(expected, WORKFLOW)
        regression = step_containing("id: regression")
        self.assertIn('--shard-index "$INKWEFT_SHARD_INDEX" --shard-count "$INKWEFT_SHARD_COUNT"', regression)
        build = step_containing("id: build")
        self.assertNotIn("if:", build)
        self.assertIn(":app:assembleDebug", build)
        self.assertIn("sha256sum", build)
        self.assertIn('test "$(git rev-parse HEAD)" = "$INKWEFT_HEAD_SHA"', build)
        uploads = [step for step in STEPS if "uses: actions/upload-artifact@" in step]
        self.assertEqual(2, len(uploads))
        for step in uploads:
            self.assertRegex(step, r"name: six-batches-\w+-\$\{\{ github.sha \}\}-shard-\$\{\{ matrix.shard \}\}")
            self.assertIn("android/build/evidence/APK-SHA256.txt", step)
            self.assertIn("android/build/evidence/source-commit.txt", step)
            self.assertNotIn(".apk", step)
            self.assertIn("android/build/evidence/app-*/outline-input-route.txt", step)
            self.assertNotIn("android/build/evidence/**/*.txt", step)
        self.assertNotIn("download-artifact", WORKFLOW)

    def test_gradle_remote_cache_is_disabled_while_wrapper_validation_stays_enabled(self):
        step = step_containing("uses: gradle/actions/setup-gradle@")
        for setting in ("cache-disabled: true", "dependency-graph: disabled", "build-scan-publish: false", "validate-wrappers: true"):
            self.assertIn(setting, step)
        self.assertNotIn("gradle-home-cache-includes", step)
        self.assertNotIn("cache-disabled: ${{", step)

    def test_push_marker_and_dispatch_choose_the_same_mode_and_runner_count(self):
        matrix = re.search(r"shard: \$\{\{ fromJSON\((.+)\) \}\}", WORKFLOW).group(1)
        mode = re.search(r"INKWEFT_MODE: \$\{\{ (.+) \}\}", WORKFLOW).group(1)
        for event, requested, message, expected, shards in (
                ("push", "", "ordinary fix", "focused", [0]),
                ("push", "", "收口 [six-batches-full]", "full", [0, 1, 2]),
                ("workflow_dispatch", "full", "", "full", [0, 1, 2]),
                ("workflow_dispatch", "focused", "[six-batches-full]", "focused", [0])):
            with self.subTest(event=event, requested=requested, message=message):
                def evaluate(expression):
                    expression = expression.replace("inputs.mode", repr(requested)).replace("github.event_name", repr(event))
                    expression = expression.replace("github.event.head_commit.message", repr(message))
                    return eval(expression.replace("&&", "and").replace("||", "or"),
                                {"__builtins__": {}, "contains": lambda text, value: value.lower() in text.lower()})
                self.assertEqual(expected, evaluate(mode))
                self.assertEqual(shards, json.loads(evaluate(matrix)))

    def test_full_lint_has_one_non_fixture_owner_and_is_not_ignored(self):
        self.assertEqual(["NOT_ASSIGNED", "RUN_REQUIRED", "NOT_ASSIGNED"],
                         [lint_requirement("full", shard) for shard in range(3)])
        self.assertEqual("NOT_RUN_FOCUSED", lint_requirement("focused", 0))
        for mode, shard in (("full", 3), ("focused", 1), ("unknown", 0)):
            with self.assertRaises(ValueError): lint_requirement(mode, shard)
        build = step_containing("id: build")
        self.assertIn("lint_gate=lint_requirement(p['mode'],shard)", build)
        self.assertIn("subprocess.run(command,cwd='android',check=True)", build)
        self.assertNotIn("continue-on-error", build)
        # Existing matrix-contract checks require all three jobs, including lint owner1.
        self.assertIn("fail-fast: false", WORKFLOW)

    def test_focused_omits_lint_and_fixture_budget_leaves_cleanup_reserve(self):
        build = step_containing("id: build")
        self.assertIn("if lint_gate=='RUN_REQUIRED': command+=[':app:lintDebug']", build)
        self.assertIn("'lint':'PASS' if lint_gate=='RUN_REQUIRED' else lint_gate", build)
        self.assertIn("45*60 - 180", WORKFLOW)
        for step in STEPS:
            if "run: python3 ci/run_six_batch_fixture.py" in step:
                self.assertIn('--deadline-epoch "$INKWEFT_FIXTURE_DEADLINE"', step)

    def test_fixture_chain_is_owned_only_by_shard_zero_and_acl_always_restores(self):
        fixture_steps = [step for step in STEPS if "run: python3 ci/run_six_batch_fixture.py" in step]
        self.assertEqual(2, len(fixture_steps))
        for step in fixture_steps:
            self.assertIn("matrix.shard == 0", step)
            self.assertIn("!cancelled()", step)
        self.assertEqual(1, sum("--not-run" in step for step in fixture_steps))
        grant = step_containing("sudo setfacl -m")
        self.assertLess(grant.index("getfacl -p /dev/kvm >"), grant.index("sudo setfacl -m"))
        restore = step_containing("sudo setfacl --restore=")
        self.assertIn("if: always()", restore)
        self.assertIn('sudo setfacl --restore="$RUNNER_TEMP/inkweft-kvm-before.acl"', restore)
        self.assertIn('cmp "$RUNNER_TEMP/inkweft-kvm-before.acl" "$RUNNER_TEMP/inkweft-kvm-after.acl"', restore)
        self.assertIn("stat -c '%d:%i:%t:%T' /dev/kvm", grant)
        self.assertIn("stat -c '%d:%i:%t:%T' /dev/kvm", restore)
        self.assertIn("if: always()", step_containing("name: 仅保存本片验证回执"))

    def test_real_source_inventory_is_complete_disjoint_and_unassigned_is_not_failure(self):
        plan = build_plan()
        expected_app = assigned_app_methods(plan)
        self.assertEqual(plan["expected_app"], len(expected_app))
        self.assertEqual(len(expected_app), len(set(expected_app)))
        all_app = []
        all_room = []
        unassigned = step_containing("name: 明确记录Room与固定样本只分配给0号分片")
        self.assertIn("if: matrix.shard != 0", unassigned)
        script = textwrap.dedent(unassigned.split("        run: |\n", 1)[1])
        for shard in range(3):
            with self.subTest(shard=shard), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root / "android-plan.json").write_text(json.dumps(plan))
                if shard:
                    env = dict(os.environ, INKWEFT_HEAD_SHA="a" * 40, INKWEFT_SHARD_INDEX=str(shard), INKWEFT_SHARD_COUNT="3")
                    subprocess.run(["bash", "-e", "-c", script], cwd=root, env=env, check=True)
                    for key, total in (("room", plan["expected_room"]), ("fixture", plan["fixture"]["expected_methods"])):
                        summary = json.loads((root / f"android/build/evidence/{key}-summary.json").read_text())
                        self.assertEqual("NOT_ASSIGNED", summary["status"])
                        self.assertEqual((0, total, 0, 0, shard, 3), tuple(summary[key] for key in
                                         ("expected", "planned_total", "passed", "assigned_shard", "shard_index", "shard_count")))
                        self.assertEqual([], summary["failures"])
                        if key == "fixture":
                            self.assertEqual({phase: {"status": "NOT_ASSIGNED"} for phase in plan["fixture"]["phases"]}, summary["phases"])
                selections = []
                run_regression(root, plan, shard, selections)
                app = [selection for selection in selections if "#" in selection]
                room = [selection for selection in selections if "#" not in selection]
                self.assertEqual(partition(expected_app, shard, 3), app)
                self.assertEqual(plan["room"] if shard == 0 else [], room)
                summary = json.loads((root / "android/build/evidence/app-summary.json").read_text())
                self.assertEqual((len(app), plan["expected_app"], len(app), shard, 3), tuple(summary[key] for key in
                                 ("expected", "planned_total", "passed", "shard_index", "shard_count")))
                self.assertEqual([], summary["failures"])
                room_summary = json.loads((root / "android/build/evidence/room-summary.json").read_text())
                if shard == 0:
                    self.assertEqual(plan["expected_room"], room_summary["expected"])
                    self.assertEqual(plan["expected_room"], room_summary["passed"])
                else:
                    self.assertEqual("NOT_ASSIGNED", room_summary["status"])
                all_app.extend(app)
                all_room.extend(room)
        self.assertCountEqual(expected_app, all_app)
        self.assertEqual(len(expected_app), len(set(all_app)))
        self.assertEqual(plan["room"], all_room)

    def test_runner_rejects_stale_app_and_room_totals(self):
        for key, shard, error in (("app", 1, "Test inventory mismatch"), ("room", 0, "Room inventory mismatch")):
            with self.subTest(key=key), tempfile.TemporaryDirectory() as directory:
                plan = build_plan()
                plan["expected_" + key] += 1
                with self.assertRaisesRegex(SystemExit, error):
                    run_regression(Path(directory), plan, shard, [])

    def test_failed_ui_method_retains_failure_and_runs_remaining_assigned_methods(self):
        plan = build_plan()
        methods = partition(assigned_app_methods(plan), 1, 3)
        selections = []
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaisesRegex(SystemExit, "1 failing selections"):
                run_regression(root, plan, 1, selections, failing=methods[0])
            self.assertEqual(methods, selections)
            summary = json.loads((root / "android/build/evidence/app-summary.json").read_text())
            self.assertEqual([methods[0]], summary["failures"])
            self.assertEqual(len(methods) - 1, summary["passed"])


if __name__ == "__main__":
    unittest.main()
