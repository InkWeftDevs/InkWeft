"""Independent arithmetic over already verified original records; does not import the analyzer."""
from pathlib import Path
import hashlib, json, math
BASE = Path('/workspace/scratch/pr20-save-boundary-uuid-execution-20261014')
OUT = Path(__file__).resolve().parent
RECORDS = Path('/workspace/scratch/pr20-save-boundary-uuid-log-analysis-20261014/independent-actual-review/actual-reviewed-output/method-10-C1-records.json')
MARKERS = RECORDS.with_name('method-10-C1-markers.log')
ANALYSIS = BASE / 'numeric-analysis/analysis.json'
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
assert sha(RECORDS) == '61eb1e524e001498e9a3ceaa59a85529439610f590e329052bc0b75846da716d'
assert sha(MARKERS) == '0ef0092547196143cb9a28e185052d29e63d5e2e25ea0ab5aaab60cffc702e22'
assert sha(ANALYSIS) == '05f728fae9fff2df0fc217ff6e3cb77a6cc69d0edae9bfa07853b7dc1c91f265'
raw = json.loads(RECORDS.read_text()); reported = json.loads(ANALYSIS.read_text())
assert reported['saveCount'] == 90
assert [{'marker': 'LIBRARY_SAVE_BOUNDARY_' + x['kind'], 'data': x['value']} for x in reported['rawMarkers']] == raw
assert [x['line'] for x in reported['rawMarkers']] == list(range(1, len(raw) + 1))
assert reported['source']['sha256'] == sha(MARKERS)
names = ['request_to_submit', 'submit_to_worker', 'worker_to_room_begin', 'room_pre_sqlite_begin', 'sqlite_begin', 'sqlite_begin_to_room_begin_return', 'room_begin_to_body', 'body_to_room_success', 'success_and_end_prologue', 'sqlite_end_commit_or_rollback_and_release', 'room_post_sqlite_end', 'room_end_to_caller_return']
def only(items):
    assert len(items) == 1, len(items)
    return items[0]
def q(values):
    ordered = sorted(values); assert len(ordered) == 30
    return {key: ordered[index] for key, index in [('p50', 14), ('p95', 28), ('max', 29)]}
def thread_monotonic(events):
    previous = {}
    for e in events:
        p = previous.get(e['threadId'])
        if p: assert p['wallNs'] <= e['wallNs'] and p['threadCpuNs'] <= e['threadCpuNs']
        previous[e['threadId']] = e
    return sorted(previous)
results = {}; null_cpu = 0; same_cpu = 0; run_exit_after = 0
for mode in ['IDLE', 'DIRECT', 'OBSERVE']:
    selected = [r for r in raw if r['data']['mode'] == mode]
    result = only([r['data'] for r in selected if r['marker'] == 'LIBRARY_SAVE_BOUNDARY_RESULT'])
    events = [e for r in selected if r['marker'] == 'LIBRARY_SAVE_BOUNDARY_EVENTS' for e in r['data']['events']]
    reads = [e for r in selected if r['marker'] == 'LIBRARY_SAVE_BOUNDARY_READS' for e in r['data']['events']]
    assert reported['modes'][mode]['allBoundaryEvents'] == events and reported['modes'][mode]['readEvents'] == reads
    boundary_threads, read_threads = thread_monotonic(events), thread_monotonic(reads)
    samples = []
    for s in result['saves']:
        number = s['sample']; own = [e for e in events if e['sample'] == number]
        saved = reported['modes'][mode]['samples'][number]
        assert saved['save'] == s and saved['rawEvents'] == own
        def get(stage, **filters): return only([e for e in own if e['stage'] == stage and all(e[k] == v for k, v in filters.items())])
        request, submit, enter, returned = [get(k) for k in ['save-request', 'writer-submit', 'writer-run-enter', 'save-returned']]
        body = get('save-writer-acquired'); room_end = get('room-end-enter', depth=1); room_done = get('room-end-exit', depth=1)
        # Select the actual depth-one SQLite END inside the outer Room END interval.
        # This independently excludes the earlier Room initialization transaction.
        sql_end = only([e for e in own if e['stage'] == 'sqlite-end-enter' and e['depth'] == 1 and room_end['wallNs'] <= e['wallNs'] <= room_done['wallNs']])
        tx = sql_end['transaction']; sql_begin = get('sqlite-begin-enter', depth=1, transaction=tx)
        sql_begun = get('sqlite-begin-exit', depth=1, transaction=tx); sql_done = get('sqlite-end-exit', depth=1, transaction=tx)
        assert sql_begun['wallNs'] <= body['wallNs'] <= sql_done['wallNs']
        endpoints = [request, submit, enter, get('room-begin-enter', depth=1), sql_begin, sql_begun, get('room-begin-exit', depth=1), body, get('room-success-enter', depth=1), sql_end, sql_done, room_done, returned]
        assert saved['sqliteOuterTransaction'] == tx and saved['roomOuterTransaction'] == endpoints[3]['transaction']
        assert saved['writerTask'] == submit['task'] == enter['task']
        assert all(e['threadId'] == enter['threadId'] for e in own if e['stage'] not in ['save-request', 'save-returned', 'writer-submit'])
        segments = {}
        for name, begin, end, observed in zip(names, endpoints, endpoints[1:], saved['segments']):
            assert observed['name'] == name and observed['begin'] == begin and observed['end'] == end
            wall = end['wallNs'] - begin['wallNs']; assert wall >= 0
            cpu = end['threadCpuNs'] - begin['threadCpuNs'] if begin['threadId'] == end['threadId'] else None
            assert cpu is None or cpu >= 0
            assert observed['wallMs'] == wall / 1e6
            assert observed['threadCpuMs'] == (cpu / 1e6 if cpu is not None else None)
            null_cpu += cpu is None; same_cpu += cpu is not None
            segments[name] = {'wall_ms': wall / 1e6, 'thread_cpu_ms': cpu / 1e6 if cpu is not None else None, 'begin_thread': begin['threadId'], 'end_thread': end['threadId']}
        before = request['wallNs'] - s['startNs']; after = s['endNs'] - returned['wallNs']
        inside = returned['wallNs'] - request['wallNs']; total = s['endNs'] - s['startNs']
        assert before >= 0 and after >= 0 and before + inside + after == total
        assert math.isclose(sum(v['wall_ms'] for v in segments.values()), inside / 1e6, abs_tol=1e-12)
        assert saved['outside_request_before_ms'] == before / 1e6 and saved['outside_return_after_ms'] == after / 1e6
        assert s['elapsedMs'] == total / 1e6
        exit_after = get('writer-run-exit')['wallNs'] > returned['wallNs']; assert saved['writerRunExitAfterCallerReturn'] == exit_after; run_exit_after += exit_after
        samples.append({'sample': number, 'elapsed_ms': total / 1e6, 'writer_task': submit['task'], 'worker_thread': enter['threadId'], 'room_transaction': saved['roomOuterTransaction'], 'sqlite_transaction': tx, 'inside_request_return_ms': inside / 1e6, 'outside_request_before_ms': before / 1e6, 'outside_return_after_ms': after / 1e6, 'segments': segments})
    computed_q = q([s['elapsed_ms'] for s in samples])
    assert all(reported['modes'][mode]['saveWallMs'][k] == v for k, v in computed_q.items())
    segment_q = {name: q([s['segments'][name]['wall_ms'] for s in samples]) for name in names}
    for name, values in segment_q.items(): assert all(reported['modes'][mode]['segmentsWallMs'][name][k] == v for k, v in values.items())
    order = [s['sample'] for s in sorted(samples, key=lambda s: s['elapsed_ms'], reverse=True)]
    assert order == reported['modes'][mode]['outlierSampleOrder']
    results[mode] = {'save_ms': computed_q, 'quantile_samples': {k: only([s['sample'] for s in samples if s['elapsed_ms'] == v]) for k, v in computed_q.items()}, 'segments_wall_ms': segment_q, 'outlier_sample_order': order, 'boundary_threads': boundary_threads, 'read_threads': read_threads, 'samples': samples}

observe_events = [e for r in raw if r['marker'] == 'LIBRARY_SAVE_BOUNDARY_EVENTS' and r['data']['mode'] == 'OBSERVE' for e in r['data']['events']]
observe_reads = [e for r in raw if r['marker'] == 'LIBRARY_SAVE_BOUNDARY_READS' and r['data']['mode'] == 'OBSERVE' for e in r['data']['events']]
prev = [e for e in observe_events if e['task'] == 213]
previous_enter = only([e for e in prev if e['stage'] == 'writer-run-enter']); previous_exit = only([e for e in prev if e['stage'] == 'writer-run-exit'])
current = [e for e in observe_events if e['sample'] == 17]
submit = only([e for e in current if e['stage'] == 'writer-submit']); start = only([e for e in current if e['stage'] == 'writer-run-enter'])
assert submit['task'] == start['task'] == 214 and previous_enter['threadId'] == previous_exit['threadId'] == start['threadId'] == 44
index_worker_events = [e for e in observe_reads if e['owner'] == 'index' and e['threadId'] == 44 and previous_enter['wallNs'] <= e['wallNs'] <= previous_exit['wallNs']]
assert {e['stage'] for e in index_worker_events} >= {'writer-acquired', 'first-source-checked', 'first-fts-written', 'batch-written'}
overlap_ns = min(previous_exit['wallNs'], start['wallNs']) - max(previous_enter['wallNs'], submit['wallNs'])
special = {'observe_sample17': {'total_ms': results['OBSERVE']['samples'][17]['elapsed_ms'], 'queue_wall_ms': (start['wallNs'] - submit['wallNs']) / 1e6, 'queue_cpu_ms': None, 'caller_thread': submit['threadId'], 'worker_thread': start['threadId'], 'writer_task': 214, 'previous_task': 213, 'previous_task_sample': previous_enter['sample'], 'previous_task_wall_ms': (previous_exit['wallNs'] - previous_enter['wallNs']) / 1e6, 'previous_task_same_thread_cpu_ms': (previous_exit['threadCpuNs'] - previous_enter['threadCpuNs']) / 1e6, 'queue_overlap_previous_task_ms': overlap_ns / 1e6, 'previous_task_exit_to_author_start_ms': (start['wallNs'] - previous_exit['wallNs']) / 1e6, 'index_owner_worker_events': index_worker_events, 'task_events': prev}, 'direct_sample8': results['DIRECT']['samples'][8], 'direct_max_sample25': results['DIRECT']['samples'][25]}
result = {'status': 'PASS_ALL_90_SAMPLES_AND_1080_SEGMENTS_RECOMPUTED', 'scope': 'EXISTING_ONE_SEED_INSTRUMENTED_NATIVE_TRACE_ARITHMETIC_ONLY', 'input_analysis_sha256': sha(ANALYSIS), 'source_marker_sha256': sha(MARKERS), 'source_records_sha256': sha(RECORDS), 'independent_analyzer_imported': False, 'save_samples_recomputed': 90, 'wall_segments_recomputed': 1080, 'null_cross_thread_cpu_segments': null_cpu, 'same_thread_cpu_segments_recomputed': same_cpu, 'writer_run_exit_after_caller_return_count': run_exit_after, 'quantile_rule': 'nearest rank n=30: sorted zero-based indices 14,28,29; not interpolated/average median', 'modes': results, 'special_checks': special, 'performance_acceptance': 'NOT_EVALUATED_NO_FOUR_PAIR_GATE', 'checker_sha256': sha(Path(__file__))}
(OUT / 'numeric-checks.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps({'status': result['status'], 'quantiles': {m: v['save_ms'] for m, v in results.items()}, 'cross_thread_cpu_null': null_cpu, 'same_thread_cpu': same_cpu, 'observe_queue_overlap_ms': special['observe_sample17']['queue_overlap_previous_task_ms']}, indent=2))
