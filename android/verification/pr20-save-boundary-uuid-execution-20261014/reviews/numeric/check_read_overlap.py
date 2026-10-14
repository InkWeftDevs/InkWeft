"""Independent endpoint sweep over original records. No producer/analyzer imports."""
from collections import Counter, defaultdict
from pathlib import Path
import hashlib
import json
import math

BASE = Path('/workspace/scratch/pr20-save-boundary-uuid-execution-20261014')
OUT = Path(__file__).resolve().parent
RECORDS = Path('/workspace/scratch/pr20-save-boundary-uuid-log-analysis-20261014/independent-actual-review/actual-reviewed-output/method-10-C1-records.json')
DERIVED = BASE / 'numeric-analysis/derived.json'
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
assert sha(RECORDS) == '61eb1e524e001498e9a3ceaa59a85529439610f590e329052bc0b75846da716d'
raw = json.loads(RECORDS.read_text())
derived = json.loads(DERIVED.read_text())

def unique(es, stage, optional=False):
    found = [e for e in es if e['stage'] == stage]
    assert len(found) <= 1 and (optional or found), stage
    return found[0] if found else None

def span(a, b):
    assert a['wallNs'] <= b['wallNs']
    cpu = b['threadCpuNs'] - a['threadCpuNs'] if a['threadId'] == b['threadId'] else None
    assert cpu is None or cpu >= 0
    return {'begin': a, 'end': b, 'wallMs': (b['wallNs'] - a['wallNs']) / 1e6,
            'threadCpuMs': None if cpu is None else cpu / 1e6,
            'sameThread': cpu is not None}

def union_ms(spans, start, end):
    # Sweep signed endpoint counts. Only derived geometry is sorted.
    deltas = Counter()
    for s in spans:
        a, b = max(start, s['begin']['wallNs']), min(end, s['end']['wallNs'])
        if a < b:
            deltas[a] += 1
            deltas[b] -= 1
    active, previous, total = 0, start, 0
    for t, change in sorted(deltas.items()):
        if active:
            total += t - previous
        active += change
        assert active >= 0
        previous = t
    assert active == 0
    return total / 1e6

def stats(v):
    v = sorted(v)
    return {'n': len(v), **{k: v[math.ceil(len(v) * p) - 1] if v else None
                            for k, p in [('p50', .5), ('p95', .95), ('max', 1)]}}

phases = {'dispatch': ('read-requested', 'read-start'), 'connection_open': ('read-start', 'connection-open'),
          'register_functions_and_begin': ('connection-open', 'snapshot-open'), 'drafts': ('snapshot-open', 'drafts-ready'),
          'scope': ('scope-start', 'scope-end'), 'hit_temp_setup': ('scope-end', 'hits-ready'),
          'page_hits': ('page-hits-start', 'page-hits-end'), 'card_hits': ('card-hits-start', 'card-hits-end'),
          'result_page': ('page-start', 'page-end'), 'metadata': ('metadata-start', 'metadata-end'),
          'pending': ('pending-start', 'pending-end')}
output = {}
for mode in ['IDLE', 'DIRECT', 'OBSERVE']:
    rr = [r for r in raw if r['data']['mode'] == mode]
    saves = next(r['data']['saves'] for r in rr if r['marker'].endswith('_RESULT'))
    events = [e for r in rr if r['marker'].endswith('_EVENTS') for e in r['data']['events']]
    reads = [e for r in rr if r['marker'].endswith('_READS') for e in r['data']['events']]
    reported = derived['modes'][mode]
    lo, hi = saves[0]['startNs'], saves[-1]['endNs']
    assert reported['saveWindow'] == {'startNs': lo, 'endNs': hi, 'wallMs': (hi-lo)/1e6}
    groups = defaultdict(list)
    for e in reads:
        if e['owner'] == 'read' and e['read'] > 0:
            groups[e['read']].append(e)
    io, snapshots, phase_spans = [], [], defaultdict(list)
    phase_incomplete, outcomes, last_stages = Counter(), Counter(), Counter()
    per_read, started_last_stages = {}, Counter()
    for rid, es in groups.items():
        rep = next(x for x in reported['reads'] if x['read'] == rid)
        assert rep['rawEvents'] == es
        start = unique(es, 'read-start', True)
        terminals = [e for e in es if e['stage'] in ['read-end', 'read-cancel', 'read-error']]
        assert len(terminals) == (1 if start else 0)
        per_read[rid] = {}
        if start:
            s = span(start, terminals[0]); io.append(s); per_read[rid]['io'] = s
            assert rep['ioEnvelope'] == s
            outcomes[terminals[0]['stage']] += 1
        else:
            outcomes['cancelled_before_io_start'] += 1
        opened = unique(es, 'snapshot-open', True); closed = unique(es, 'snapshot-closed', True)
        assert bool(opened) == bool(closed)
        if opened:
            s = span(opened, closed); snapshots.append(s); per_read[rid]['snapshot'] = s
            assert rep['snapshotEnvelope'] == s
        incomplete = []
        for name, (a, b) in phases.items():
            first, last = unique(es, a, True), unique(es, b, True)
            if first and last:
                s = span(first, last); phase_spans[name].append(s)
                assert rep['phases'][name] == s
            elif first:
                incomplete.append(name); phase_incomplete[name] += 1
        assert incomplete == rep['incompletePhases']
        work = [e for e in es if any(e['stage'] in pair for pair in phases.values())]
        last_stage = work[-1]['stage'] if work else None
        assert rep['lastObservedWorkStage'] == last_stage
        last_stages[last_stage if last_stage else 'null'] += 1
        if start: started_last_stages[last_stage] += 1
    tasks = defaultdict(list)
    for e in events:
        if e['sample'] == -1 and e['task'] > 0:
            tasks[e['task']].append(e)
    index = []
    for task, es in tasks.items():
        enter = unique(es, 'writer-run-enter', True)
        if not enter: continue
        exit_event = unique(es, 'writer-run-exit')
        acquired = [e for e in reads if e['owner'] == 'index' and e['stage'] == 'writer-acquired'
                    and e['threadId'] == enter['threadId'] and enter['wallNs'] <= e['wallNs'] <= exit_event['wallNs']]
        assert len(acquired) == 1
        s = span(enter, exit_event); index.append(s)
        rep = next(x for x in reported['indexWriterTasks'] if x['task'] == task)
        assert rep['run'] == s and rep['rawEvents'] == es
    precompute, batch = [], []
    for e in reads:
        if e['owner'] != 'index': continue
        if e['stage'] == 'preparing-batch': batch = [e]
        elif e['stage'] == 'batch-precomputed':
            assert len(batch) == 1
            precompute.append(span(batch[0], e)); batch.append(e)
    summary = {'readStageCounts': dict(Counter(e['stage'] for e in reads if e['owner'] == 'read')),
               'outcomes': dict(outcomes), 'lastWorkStageCounts': dict(last_stages),
               'ioUnionOverlapWholeWindowMs': union_ms(io, lo, hi),
               'snapshotUnionOverlapWholeWindowMs': union_ms(snapshots, lo, hi),
               'completePrecomputeUnionOverlapWholeWindowMs': union_ms(precompute, lo, hi),
               'indexWriterRunUnionOverlapWholeWindowMs': union_ms(index, lo, hi), 'phases': {}}
    for name in phases:
        ss = phase_spans[name]
        summary['phases'][name] = {'complete': len(ss), 'incomplete': phase_incomplete[name],
                                  'wallMs': stats([s['wallMs'] for s in ss]),
                                  'sameThreadCpuMs': stats([s['threadCpuMs'] for s in ss if s['sameThread']]),
                                  'unionOverlapWholeWindowMs': union_ms(ss, lo, hi)}
    assert summary == reported['readSummary']
    overlap_samples = []
    for save in saves:
        n, a, b = save['sample'], save['startNs'], save['endNs']
        own = [e for e in events if e['sample'] == n]
        submit, enter = unique(own, 'writer-submit'), unique(own, 'writer-run-enter')
        check = {'sample': n, 'readIoUnionOverlapMs': union_ms(io, a, b), 'snapshotUnionOverlapMs': union_ms(snapshots, a, b),
                 'indexWriterRunUnionOverlapMs': union_ms(index, a, b),
                 'queueIndexWriterRunOverlapMs': union_ms(index, submit['wallNs'], enter['wallNs']),
                 'overlappingReadIds': [rid for rid, x in per_read.items() if 'io' in x and union_ms([x['io']], a, b) > 0]}
        rep = reported['samples'][n]
        assert all(rep[k] == v for k, v in check.items())
        overlap_samples.append(check)
    exposed = [x['sample'] for x in overlap_samples if x['queueIndexWriterRunOverlapMs'] > 0]
    assert exposed == reported['indexQueueExposedSamples']
    output[mode] = {'window_ms': (hi-lo)/1e6, 'read_summary': summary, 'all_30_sample_overlaps': overlap_samples,
                    'index_queue_exposed_samples': exposed, 'started_read_last_work_stages': dict(started_last_stages),
                    'cross_thread_io_cpu_null_count': sum(not s['sameThread'] for s in io)}
result = {'status': 'PASS_ALL_READ_PHASES_90_SAMPLE_OVERLAPS_AND_INTERVAL_UNIONS',
          'scope': 'EXISTING_ACTUAL_NATIVE_RECORDS_LOCAL_ARITHMETIC_ONLY',
          'source_records_sha256': sha(RECORDS), 'compared_derived_sha256': sha(DERIVED),
          'independent_producer_or_analyzer_imported': False, 'checker_sha256': sha(Path(__file__)), 'modes': output,
          'caveat': 'Sorted endpoint geometry only. Missing phase endings remain censored. Overlap is exposure, not causal or additive save cost.'}
(OUT / 'read-overlap-checks.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps({'status': result['status'], 'windows': {k: {x: v['read_summary'][x] for x in ['ioUnionOverlapWholeWindowMs', 'snapshotUnionOverlapWholeWindowMs', 'indexWriterRunUnionOverlapWholeWindowMs']} for k, v in output.items()}, 'index_exposed': {k: v['index_queue_exposed_samples'] for k, v in output.items()}}, indent=2))
