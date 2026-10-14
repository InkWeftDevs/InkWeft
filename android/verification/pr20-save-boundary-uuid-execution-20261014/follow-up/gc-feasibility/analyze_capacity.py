#!/usr/bin/env python3
"""Offline capacity arithmetic only; no controller, device, network or performance test."""
import hashlib
import json
import pathlib
import subprocess

OUT = pathlib.Path(__file__).resolve().parent
REPO = pathlib.Path('/workspace/InkWeft')
SOURCE = '4c4a2d67a27b56f351b3433d4f99deb7ce3e2591'
GUARD_SOURCE = '94379190b9ad0e1034f136ea9887fb0ac12e7ea7'
TEST = 'android/data-local/src/androidTest/kotlin/org/inkweft/data/HistoryDeletionBoundaryDiagnosticsTest.kt'
LIMIT = 20 * 1024 * 1024
LONG = 9223372036854775807

def compact(value):
    return json.dumps(value, ensure_ascii=True, separators=(',', ':'))

def size(value):
    return len(compact(value).encode('utf-8'))

def git(*args):
    return subprocess.check_output(['git', '-C', str(REPO), *args])

def blob(path):
    return hashlib.sha256(git('show', SOURCE + ':' + path)).hexdigest()

def event_sizes(event):
    # This is a schema-shaped capacity witness, not a valid transaction trace.
    # Use exact 512 = 10*48+32, not eleven full chunks.
    raw = records = forward = receipts = events = payload_count = 0
    details = []
    for ordinal, connection, databases in [(6, 'same-room', ['collector']),
                                            (7, 'separate-room', ['collector', 'author'])]:
        method_records = []
        ordered = []
        for sample in range(30):
            for database in databases:
                for part, n in enumerate([48] * 10 + [32]):
                    data = {'sample': sample, 'connection': connection, 'database': database,
                            'part': part, 'parts': 11, 'events': [event] * n}
                    original = compact(data)
                    marker = 'HISTORY_GC_BOUNDARY_EVENTS'
                    raw += len((marker + ' ' + original + '\n').encode())
                    method_records.append({'marker': marker, 'data': data})
                    item = {'ordinal': ordinal, 'identity': 'C1', 'sequence': len(ordered),
                            'marker': marker, 'payload_bytes': len(original.encode()),
                            'payload_sha256': hashlib.sha256(original.encode()).hexdigest(),
                            'original_json': original}
                    forward += len(('INKWEFT_CAUSAL_RECORD ' + compact(item) + '\n').encode())
                    ordered.append({k: item[k] for k in ['sequence', 'marker', 'payload_bytes', 'payload_sha256']})
                    events += n
                    payload_count += 1
        records += size(method_records) + 1
        footer = {'ordinal': ordinal, 'identity': 'C1', 'count': len(ordered),
                  'ordered_payloads': ordered,
                  'ordered_payloads_sha256': hashlib.sha256(compact(ordered).encode()).hexdigest()}
        receipts += len(('INKWEFT_CAUSAL_END ' + compact(footer) + '\n').encode())
        details.append({'connection': connection, 'database_windows': 30 * len(databases),
                        'boundary_events': 30 * len(databases) * 512,
                        'EVENTS_markers': len(ordered)})
    assert events == 46080 and payload_count == 990
    return {'event_bytes': size(event), 'event_body_bytes': size(event) * events,
            'raw_marker_lines_bytes': raw, 'records_json_bytes': records,
            'forwarded_RECORD_lines_bytes': forward, 'forwarded_END_lines_bytes': receipts,
            'raw_plus_records_bytes': raw + records,
            'raw_records_forward_and_END_bytes': raw + records + forward + receipts,
            'details': details}

def main():
    test_sha = blob(TEST)
    assert test_sha == '98b69ea1d09e0bce21646d79f50f9b7dd9396c9e73bb1a9baea647de7cdf6b72'
    production_paths = ['android/core-domain', 'android/data-local/src/main', 'android/gradle',
                        'android/data-local/build.gradle.kts', 'android/settings.gradle.kts',
                        'android/build.gradle.kts']
    differences = git('diff', '--name-status', GUARD_SOURCE, SOURCE, '--', *production_paths).decode()
    assert differences == ''
    # 258-byte witness: actual enum, source JVM integer widths, all ASCII.
    # It is not asserted that maximal ordinals/depth are reachable in these 30 trials.
    event = {'stage': 'sqlite-success-enter', 'ownerToken': 89, 'task': LONG,
             'transaction': LONG, 'depth': 2147483647, 'successRequested': False,
             'outcome': 'returned', 'atNs': LONG, 'cpuNs': LONG, 'thread': LONG}
    bounded = event_sizes(event)
    assert bounded['event_bytes'] == 258
    assert bounded['raw_plus_records_bytes'] > LIMIT
    # Even a tiny typed-object capacity witness is informative, not a native prediction.
    small = {'stage': 'room-end-exit', 'ownerToken': 0, 'task': 1, 'transaction': 1,
             'depth': 1, 'successRequested': True, 'outcome': 'threw',
             'atNs': 1, 'cpuNs': 1, 'thread': 1}
    compact_witness = event_sizes(small)
    # Independent upper-capacity array accounting, before containing TRIAL object.
    batch = {'phase': 'transaction-returned', 'afterSequence': LONG, 'candidates': 256,
             'expectedDataVersion': LONG, 'expectedChanges': LONG, 'observedDataVersion': LONG,
             'observedChanges': LONG, 'atNs': LONG, 'cpuNs': LONG, 'thread': LONG, 'callbackNs': LONG}
    phase = {'phase': 'before-batch-commit', 'atNs': LONG}
    batch_arrays = 60 * size([batch] * 64)
    phase_arrays = 60 * size([phase] * 32)
    failure_raw = 256 * 1024
    failure_envelope = 6 * failure_raw + 8192
    reserve = 3 * 1024 * 1024
    # Three copies of these ASCII array bodies, plus quote escaping in original_json.
    batch_phase_copies = 2 * (batch_arrays + phase_arrays)
    batch_phase_forward = 60 * (size(compact([batch] * 64)) + size(compact([phase] * 32)))
    subtotal = (bounded['raw_records_forward_and_END_bytes'] + batch_phase_copies +
                batch_phase_forward + failure_raw + failure_envelope + reserve)
    result = {
        'status': 'BLOCKED_CONSERVATIVE_CAPACITY_GUARANTEE',
        'execution': 'OFFLINE_JSON_ARITHMETIC_ONLY_NO_NATIVE',
        'source_commit': SOURCE, 'test_path': TEST, 'test_sha256': test_sha,
        'native_guard_source_commit': GUARD_SOURCE,
        'unchanged_production_paths': production_paths, 'production_diff_name_status': differences,
        'input_plan_sha256': hashlib.sha256(pathlib.Path('/workspace/scratch/pr20-gc-followup-20261014/native-execution-plan.txt').read_bytes()).hexdigest(),
        'limit_bytes': LIMIT, 'trials': 60, 'database_windows': 90,
        'capacities': {'boundary_per_database': 512, 'batch_per_trial': 64, 'phase_per_trial': 32},
        'counts': {'boundary_events': 46080, 'EVENTS_markers': 990, 'TRIAL_markers': 60,
                   'SUMMARY_markers': 2, 'batch_events': 3840, 'phases': 1920},
        'full_width_event_witness': event, 'full_width_event_sizes': bounded,
        'small_event_witness': small, 'small_event_sizes': compact_witness,
        'batch_phase_array_accounting': {'batch_object_bytes': size(batch), 'phase_object_bytes': size(phase),
            'batch_arrays_one_copy_bytes': batch_arrays, 'phase_arrays_one_copy_bytes': phase_arrays,
            'raw_records_array_bodies_bytes': batch_phase_copies,
            'forwarded_escaped_array_bodies_bytes': batch_phase_forward},
        'failure_reserve': {'raw_bytes': failure_raw, 'escaped_envelope_bytes': failure_envelope,
                           'other_text_reserve_bytes': reserve, 'at_most_one_failure': True},
        'combined_accounted_capacity_subtotal_bytes': subtotal,
        'subtotal_excess_bytes': subtotal - LIMIT,
        'excluded_nonnegative_items': ['TRIAL wrapper/scalars/author segments/delete intervals/covered times',
            'SUMMARY and distribution payloads', 'normal instrumentation framing',
            'normal forwarding metadata for TRIAL/SUMMARY', 'extra raw failure bytes beyond forwardable cap'],
        'limitations': [
            'Capacity-shaped JSON witnesses are not valid success traces, native samples, or reachability proofs.',
            'Exceeding this conservative model proves no budget guarantee here, not actual inevitable overflow.',
            'The subtotal is not a complete worst-case upper bound; omitted items only add bytes.',
            'Arbitrary failure class strings and raw instrumentation logs have no complete source-side byte bound.',
            'Failure forwarding above 256KiB must REFUSE explicitly, preserving failure and local raw; it cannot truncate or upgrade PASS.',
            'No capacity, sample, fixture, timeout, event or evidence representation was changed.']}
    (OUT / 'capacity.json').write_text(json.dumps(result, indent=2) + '\n')
    guards = ['sameRoomAuthorQueuedBeforeDeleteCommitPreservesNewRoot',
              'separateRoomAuthorQueuedBeforeDeleteCommitPreservesNewRoot',
              'interruptedBatchRollsBackAndReopenedCollectionResumes',
              'malformedTransformAfterAFullRootBatchAbortsBeforeAnyDeletion',
              'missingRoomGuardLogAbortsWithoutDeletingCandidates']
    diagnostics = ['sameRoomNaturalArrivalBoundaries', 'separateRoomNaturalArrivalBoundaries']
    steps = []
    for ordinal, method in enumerate(guards + diagnostics, 1):
        diagnostic = ordinal > 5
        cls = 'HistoryDeletionBoundaryDiagnosticsTest' if diagnostic else 'HistoryCollectionTest'
        selection = 'org.inkweft.data.' + cls + '#' + method
        args = ['adb', '-s', 'emulator-5554', 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', selection]
        if diagnostic:
            args += ['-e', 'historyGcBoundaryDiagnostics', 'true']
        args += ['org.inkweft.data.test/androidx.test.runner.AndroidJUnitRunner']
        steps.append({'ordinal': ordinal, 'selection': selection, 'args': args, 'expected_junit_tests': 1,
                      'identity': 'C1', 'install_group': 'C1-data-diagnostics',
                      'marker_policy': 'gc-boundary-draft' if diagnostic else 'none',
                      'outer_cap_seconds_proposal': 180 if diagnostic else None,
                      'guard_budget_note': None if diagnostic else 'Inherit reviewed existing guard budget; no new timeout chosen.'})
    plan = {'status': 'DRAFT_CAPACITY_BLOCKED_NOT_EXECUTABLE_OR_AUTHORIZATION',
            'source_commit': SOURCE, 'test_sha256': test_sha,
            'guard_test_sha256': blob('android/data-local/src/androidTest/kotlin/org/inkweft/data/HistoryCollectionTest.kt'),
            'instrumentation_calls': 7, 'installation_groups': 1, 'data_test_apks': 1, 'steps': steps,
            'environment': 'ubuntu24 / API35 AOSP ATD x86_64 / KVM / 2CPU / 2GiB / Room2.8.5 / requery3.49.0',
            'limits_unchanged': {'job_minutes': 60, 'work_minutes': 55, 'text_bytes': LIMIT, 'retention_days': 3,
                                 'save_watchdog_ms': 5000, 'trials_per_mode': 30},
            'cleanup': 'Same minimal UID ACL, exact restoration, trap plus always cleanup, natural process exit',
            'not_created': ['controller', 'workflow', 'readiness', 'prep', 'active', 'new native run']}
    (OUT / 'method-plan.draft.json').write_text(json.dumps(plan, indent=2) + '\n')
    print(json.dumps({'status': result['status'], 'raw_plus_records': bounded['raw_plus_records_bytes'],
                      'raw_records_forward_END': bounded['raw_records_forward_and_END_bytes'],
                      'accounted_capacity_subtotal': subtotal, 'limit': LIMIT}))

if __name__ == '__main__':
    main()
