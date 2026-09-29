"""Real-editor frame/compute samples and bounded Perfetto traces on a disposable emulator.

Only an independently installed .performance application is reset. Both APK identities
are checked before any reset; existing workspace/insertion applications are never touched.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import subprocess
import uuid

from device_fault_probe import require_disposable

PACKAGES = tuple('org.inkweft.app.a0' + variant + '.performance' for variant in ('', '.workspace', '.insertion'))
CONDITIONS = ('writing', 'map', 'encryption', 'png')
COMPARABLE = ('fixtureSha256', 'pngSha256', 'pngBytes', 'pngPixels', 'encryptedPlainBytes',
              'seedStrokes', 'naturalObjects', 'authorMapNodes', 'viewport', 'canvasWidthPx',
              'canvasHeightPx', 'windowWidthPx', 'windowHeightPx', 'density', 'fontScale', 'refreshRate')


def performance_package(value):
    if value not in PACKAGES:
        raise ValueError('Only a dedicated .performance package may be reset')
    return value


def percentile(values, quantile):
    return sorted(values)[max(0, math.ceil(len(values) * quantile) - 1)] if values else None


def summarize(report):
    assert report['status'] == 'PASS' and not report['debuggable'] and report['profileable']
    performance_package(report['package'])
    assert report['savedStrokes'] == 624
    if report['condition'] in ('encryption', 'png'):
        assert report['computeOverlapsEveryInput']
    start, end = report['windowStart'], report['windowEnd']
    frames = [frame for frame in report['frames'] if start <= frame['intendedVsyncNs'] <= end and not frame['firstDraw']]
    assert frames, 'No steady rendered frames within the measured window'
    durations = [frame['totalNs'] / 1e6 for frame in frames]
    saves = [(event['end'] - event['start']) / 1e6 for event in report['events'] if event['kind'] == 'input-and-confirmed-save']
    assert len(saves) == 24
    inputs = [event for event in report['events'] if event['kind'] == 'input-and-confirmed-save']
    # The PNG operation includes the production foreground-yield wait. Separate
    # actual synchronous decode calls from an operation merely remaining active.
    work_kind = 'png-decode-verify' if report['condition'] == 'png' else report['condition']
    work = [event for event in report['events'] if event['kind'] == work_kind and event['start'] < end and event['end'] > start]
    operations = [event for event in report['events'] if event['kind'] == report['condition']]
    def overlapping_inputs(events):
        return sum(any(event['start'] < point['end'] and event['end'] > point['start'] for event in events) for point in inputs)
    deadlines = [frame for frame in frames if frame['deadlineNs'] >= 0]
    return {'frame_count': len(frames), 'frame_p50_ms': percentile(durations, .5),
            'frame_p95_ms': percentile(durations, .95), 'frame_max_ms': max(durations),
            'deadline_frame_count': len(deadlines), 'deadline_misses': sum(f['totalNs'] > f['deadlineNs'] for f in deadlines),
            'dropped_reports': report['droppedReports'], 'input_save_count': len(saves),
            'input_save_p50_ms': percentile(saves, .5), 'input_save_p95_ms': percentile(saves, .95),
            'work_call_kind': work_kind, 'work_calls_in_window': len(work),
            'work_call_overlap_inputs': overlapping_inputs(work),
            'background_operation_overlap_inputs': overlapping_inputs(operations)}


def trace_config(package):
    performance_package(package)
    # A unique detached session is always stopped in finally; the 3-minute deadline
    # also bounds its lifetime if the host process or USB connection disappears.
    return f'''duration_ms: 180000
write_into_file: true
file_write_period_ms: 1000
flush_period_ms: 1000
max_file_size_bytes: 67108864
buffers {{ size_kb: 16384 fill_policy: RING_BUFFER }}
data_sources {{ config {{ name: "linux.ftrace" ftrace_config {{
  ftrace_events: "sched/sched_switch"
  ftrace_events: "sched/sched_wakeup"
  ftrace_events: "task/task_newtask"
  ftrace_events: "task/task_rename"
  ftrace_events: "power/cpu_frequency"
  ftrace_events: "power/cpu_idle"
  atrace_categories: "gfx"
  atrace_categories: "view"
  atrace_categories: "input"
  atrace_categories: "dalvik"
  atrace_categories: "am"
  atrace_categories: "wm"
  atrace_apps: "{package}"
}} }} }}
data_sources {{ config {{ name: "linux.process_stats" process_stats_config {{ scan_all_processes_on_start: true proc_stats_poll_ms: 1000 }} }} }}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb'); parser.add_argument('--serial', required=True)
    parser.add_argument('--apk', required=True); parser.add_argument('--test-apk', required=True)
    parser.add_argument('--package', choices=PACKAGES, default=PACKAGES[0])
    parser.add_argument('--output', required=True); parser.add_argument('--label', choices=('baseline', 'candidate'), required=True)
    parser.add_argument('--rounds', type=int, choices=range(1, 6), default=3)
    parser.add_argument('--conditions', nargs='+', choices=CONDITIONS, default=list(CONDITIONS))
    parser.add_argument('--baseline-summary'); parser.add_argument('--mumu-manager'); parser.add_argument('--mumu-index', default='5')
    args = parser.parse_args(); package = performance_package(args.package)
    assert len(set(args.conditions)) == len(args.conditions)
    adb = [args.adb, '-s', args.serial]
    require_disposable(adb, args.serial, args.mumu_manager, args.mumu_index)
    def command(*words, timeout=30):
        return subprocess.check_output(adb + list(words), text=True, timeout=timeout).strip()
    identities = {}
    for name, local in ((package, args.apk), (package + '.test', args.test_apk)):
        expected = hashlib.sha256(Path(local).read_bytes()).hexdigest()
        installed = command('shell', 'pm', 'path', name).splitlines()
        assert len(installed) == 1 and installed[0].startswith('package:'), 'Expected a single installed APK'
        assert command('shell', 'sha256sum', installed[0].removeprefix('package:')).split()[0] == expected
        identities[name] = expected
    output = Path(args.output); output.mkdir(parents=True, exist_ok=False)
    device = {'serial': args.serial, 'build': command('shell', 'getprop', 'ro.build.fingerprint'),
              'size': command('shell', 'wm', 'size'), 'density': command('shell', 'wm', 'density'),
              'animation_scales': {name: command('shell', 'settings', 'get', 'global', name)
                                   for name in ('window_animation_scale', 'transition_animation_scale', 'animator_duration_scale')}}
    baseline = json.loads(Path(args.baseline_summary).read_text()) if args.baseline_summary else None
    if baseline:
        assert baseline['device'] == device, 'Baseline and candidate device/window conditions differ'
        assert all(row['passed'] for row in baseline['samples']), 'Baseline contains failed samples'
        assert set(baseline['apk_sha256']) == set(identities), 'Baseline/candidate package identities differ'
        assert baseline['apk_sha256'][package + '.test'] == identities[package + '.test'], 'Use the same instrumentation APK for both measurements'
    result = {'label': args.label, 'apk_sha256': identities, 'device': device, 'samples': [],
              'scope': 'Dedicated performance app; automated editor/save timings, not human pen latency'}
    for round_index in range(args.rounds):
        ordered = args.conditions[round_index % len(args.conditions):] + args.conditions[:round_index % len(args.conditions)]
        for condition in ordered:
            run_id = f'{args.label}-{round_index + 1}-{condition}-{uuid.uuid4().hex[:8]}'
            folder = output / run_id; folder.mkdir()
            row = {'run_id': run_id, 'round': round_index + 1, 'condition': condition, 'passed': False}
            remote_config = f'/data/local/tmp/inkweft-{run_id}.pbtxt'
            remote_trace = f'/data/misc/perfetto-traces/inkweft-{run_id}.perfetto-trace'
            local_trace = folder / 'editor.perfetto-trace'; active_trace = False
            try:
                command('shell', 'am', 'force-stop', package)
                assert command('shell', 'pm', 'clear', performance_package(package)) == 'Success'
                config = folder / 'trace.pbtxt'; config.write_text(trace_config(package))
                command('push', str(config), remote_config)
                command('shell', 'perfetto', '--txt', '-c', remote_config, '-o', remote_trace, '--detach=' + run_id)
                active_trace = True
                command('shell', 'perfetto', '--is_detached=' + run_id)
                invoke = adb + ['shell', 'am', 'instrument', '-w', '-e', 'class', 'org.inkweft.app.EditorFramePerformanceProbe',
                                '-e', 'runId', run_id, '-e', 'perfCondition', condition, package + '.test/androidx.test.runner.AndroidJUnitRunner']
                try:
                    completed = subprocess.run(invoke, capture_output=True, timeout=150)
                    log = completed.stdout + completed.stderr
                except subprocess.TimeoutExpired as error:
                    log = (error.stdout or b'') + (error.stderr or b'') + b'\nRUNNER_TIMEOUT'
                (folder / 'instrumentation.log').write_bytes(log)
                command('shell', 'perfetto', '--attach=' + run_id, '--stop'); active_trace = False
                command('pull', remote_trace, str(local_trace))
                command('pull', f'/sdcard/Android/data/{package}/files/editor-frame-{run_id}.json', str(folder / 'timeline.json'))
                report = json.loads((folder / 'timeline.json').read_text())
                assert re.search(rb'^OK \(1 test\)\s*$', log, re.M), 'Instrumentation did not pass exactly one test'
                assert report['runId'] == run_id and report['condition'] == condition and report['package'] == package
                raw_trace = local_trace.read_bytes()
                assert (report['tracePrefix'] + '.window').encode() in raw_trace, 'Measured window missing from Perfetto trace'
                assert (report['tracePrefix'] + '.input').encode() in raw_trace, 'Editor input marker missing from Perfetto trace'
                row.update(metrics=summarize(report), conditions={key: report[key] for key in COMPARABLE},
                           trace_sha256=hashlib.sha256(raw_trace).hexdigest(), source=report['source'])
                if baseline:
                    previous = next(item for item in baseline['samples'] if item['condition'] == condition and item['round'] == round_index + 1)
                    changed = [key for key in COMPARABLE if previous['conditions'][key] != row['conditions'][key]]
                    assert not changed, 'Baseline/candidate conditions changed: ' + ', '.join(changed)
                row['passed'] = True
            except Exception as error:
                row['error'] = str(error)
            finally:
                if active_trace:
                    stopped = subprocess.run(adb + ['shell', 'perfetto', '--attach=' + run_id, '--stop'], capture_output=True)
                    (folder / 'trace-stop.log').write_bytes(stopped.stdout + stopped.stderr)
                    active_trace = stopped.returncode != 0
                if not local_trace.exists():
                    subprocess.run(adb + ['pull', remote_trace, str(local_trace)], capture_output=True)
                if not (folder / 'timeline.json').exists():
                    subprocess.run(adb + ['pull', f'/sdcard/Android/data/{package}/files/editor-frame-{run_id}.json', str(folder / 'timeline.json')], capture_output=True)
                subprocess.run(adb + ['shell', 'am', 'force-stop', package], capture_output=True)
                # Clean only this run's named trace/config after a local trace is retained.
                if local_trace.exists() and local_trace.stat().st_size and not active_trace:
                    cleanup = subprocess.run(adb + ['shell', 'rm', '-f', remote_config, remote_trace], capture_output=True)
                    if cleanup.returncode:
                        row.update(passed=False, error='Remote trace cleanup failed')
                else:
                    row['retained_remote_evidence'] = [remote_config, remote_trace]
                result['samples'].append(row)
                (output / 'summary.json').write_text(json.dumps(result, indent=2) + '\n')
                print(run_id, 'PASS' if row['passed'] else 'FAIL: ' + row['error'], flush=True)
    assert all(row['passed'] for row in result['samples']), 'Failed samples retained in summary.json'


if __name__ == '__main__':
    main()
