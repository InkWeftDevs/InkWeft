#!/usr/bin/env python3
"""Offline causal analysis. Reads complete validated marker records; never runs Android.

Input: per-method JSON list [{"marker": str, "data": object}, ...].
All original records/events/samples are preserved in analysis.json, including partial ones.
This adds relation checks; it does not replace the controller's schema/artifact validation.
"""
import argparse
from collections import Counter, defaultdict
import csv
import hashlib
import json
import math
from pathlib import Path

MODES = ['IDLE', 'TRACKER', 'INDEX_ONLY', 'INDEX_TRACKER', 'READS_ONLY', 'FULL']
SAVE_STAGES = ['waiting-writer', 'writer-acquired', 'head-read', 'head-written',
               'revision-written', 'receipt-written', 'transaction-returned']
SAVE_PHASES = [
    ('requestToEntry', 'waiting-writer', 'writer-acquired'),
    ('entryToHeadRead', 'writer-acquired', 'head-read'),
    ('headReadToWrite', 'head-read', 'head-written'),
    ('headWriteToRevision', 'head-written', 'revision-written'),
    ('revisionToReceipt', 'revision-written', 'receipt-written'),
    ('receiptToReturn', 'receipt-written', 'transaction-returned'),
    ('enteredBody', 'writer-acquired', 'receipt-written'),
    ('enteredThroughReturn', 'writer-acquired', 'transaction-returned'),
]
INDEX_PHASES = [
    ('prepare', 'preparing-batch', 'batch-precomputed'),
    ('requestToEntry', 'waiting-writer', 'writer-acquired'),
    ('enteredBody', 'writer-acquired', 'batch-written'),
    ('bodyToReturn', 'batch-written', 'batch-committed'),
    ('enteredThroughReturn', 'writer-acquired', 'batch-committed'),
]
READ_PHASES = [
    ('dispatch', 'read-requested', 'read-start'),
    ('open', 'read-start', 'connection-open'),
    ('registerAndBegin', 'connection-open', 'snapshot-open'),
    ('drafts', 'snapshot-open', 'drafts-ready'),
    ('scope', 'scope-start', 'scope-end'),
    ('hitTables', 'scope-end', 'hits-ready'),
    ('pageHits', 'page-hits-start', 'page-hits-end'),
    ('cardHits', 'card-hits-start', 'card-hits-end'),
    ('page', 'page-start', 'page-end'),
    ('metadata', 'metadata-start', 'metadata-end'),
    ('pending', 'pending-start', 'pending-end'),
    ('snapshot', 'snapshot-open', 'snapshot-closed'),
]
NS_PER_MS = 1_000_000


def require(condition, message, issues):
    if not condition:
        issues.append(message)


def distribution(values):
    """Frozen nearest-rank percentiles; no samples removed."""
    ordered = sorted(values)
    if not ordered:
        return {'n': 0, 'p50': None, 'p95': None, 'max': None, 'sum': 0}
    return {'n': len(ordered), 'p50': ordered[math.ceil(len(ordered)*.5)-1],
            'p95': ordered[math.ceil(len(ordered)*.95)-1],
            'max': ordered[-1], 'sum': sum(ordered)}


def overlap_union(start, end, intervals):
    spans = sorted((max(start, a), min(end, b)) for a, b in intervals if max(start, a) < min(end, b))
    total = 0
    at = None
    for a, b in spans:
        if at is None or a > at:
            total += b-a
        elif b > at:
            total += b-at
        at = b if at is None else max(at, b)
    return total


def delta(start, end):
    """Thread CPU is only comparable for matching thread IDs and valid counters."""
    same_thread = start['threadId'] == end['threadId']
    valid_cpu = same_thread and 0 <= start['threadCpuNs'] <= end['threadCpuNs']
    return {'startNs': start['wallNs'], 'endNs': end['wallNs'],
            'wallNs': end['wallNs']-start['wallNs'],
            'startThreadId': start['threadId'], 'endThreadId': end['threadId'],
            'sameThread': same_thread, 'threadCpuDeltaNs': end['threadCpuNs']-start['threadCpuNs'] if valid_cpu else None,
            'cpuUnavailableReason': None if valid_cpu else ('different-thread' if not same_thread else 'invalid-counter'),
            'cpuIsExclusiveWork': False}


def phase_pairs(events, definitions, issues, prefix):
    stages = defaultdict(list)
    for e in events:
        stages[e['stage']].append(e)
    result = {}
    for label, first, last in definitions:
        a, b = stages[first], stages[last]
        if len(a) == len(b) == 1:
            record = delta(a[0], b[0])
            require(record['wallNs'] >= 0, f'{prefix}: reversed {label}', issues)
            result[label] = record
        elif a or b:
            result[label] = {'complete': False, 'startCount': len(a), 'endCount': len(b),
                             'observedStartNs': a[0]['wallNs'] if a else None,
                             'observedEndNs': b[-1]['wallNs'] if b else None}
    return result


def full_interval(phases, key):
    r = phases.get(key, {})
    return (r['startNs'], r['endNs']) if 'wallNs' in r and r['wallNs'] >= 0 else None


def summarize_phases(groups):
    names = sorted({name for g in groups for name in g['phases']})
    summary = {}
    for name in names:
        rows = [g['phases'][name] for g in groups if name in g['phases']]
        complete = [r for r in rows if 'wallNs' in r]
        summary[name] = {'completeIntervals': len(complete), 'incompleteIntervals': len(rows)-len(complete),
                         'wallMs': distribution([r['wallNs']/NS_PER_MS for r in complete]),
                         'sameThreadCpuMs': distribution([r['threadCpuDeltaNs']/NS_PER_MS for r in complete if r['threadCpuDeltaNs'] is not None]),
                         'unavailableCpuIntervals': sum(r['threadCpuDeltaNs'] is None for r in complete)}
    return summary


def load_records(path):
    raw = path.read_bytes()
    data = json.loads(raw)
    if not isinstance(data, list) or any(not isinstance(r, dict) or 'marker' not in r or 'data' not in r for r in data):
        raise ValueError(f'{path}: expected complete list of marker/data records')
    if any('parse_error' in r for r in data):
        raise ValueError(f'{path}: parse-error record retained; analysis cannot claim completeness')
    return data, {'path': str(path.resolve()), 'sha256': hashlib.sha256(raw).hexdigest(), 'records': len(data)}


def extraction_binding(path, inputs, issues):
    raw = path.read_bytes()
    report = json.loads(raw)
    complete = report.get('analysis_status') == 'COMPLETE_SCOPED_CAUSAL_LOG_EVIDENCE'
    require(complete, 'extraction: whole run is not complete scoped causal log evidence', issues)
    selected = []
    for name, ordinal in [('unicode',8), ('warm',9), ('saves',10)]:
        if name not in inputs:
            continue
        files = [r for r in report.get('extracted_records',[]) if r['ordinal']==ordinal]
        blocks = [b for b in report.get('state',{}).get('blocks',[]) if b['context']['ordinal']==ordinal]
        require(len(files)==len(blocks)==1, f'extraction {name}: expected exactly one file/block', issues)
        if len(files)!=1 or len(blocks)!=1:
            complete = False
            continue
        record, block = files[0], blocks[0]
        context = block['context']
        valid = (record['sha256']==inputs[name]['sha256']==context['records_sha256'] and
                 record['record_count']==inputs[name]['records']==block['record_count'] and
                 all(record.get(k) is True for k in ['block_closed','method_pass','whole_run_complete']) and
                 block.get('ended') is True and block.get('method_pass') is True and
                 context['junit_status']=='PASS' and context['adb_exit']==0 and
                 context['process_exit_outcome']=='NATURAL_EXIT')
        require(valid, f'extraction {name}: hash/count/closure/PASS binding mismatch', issues)
        complete = complete and valid
        selected.append({'name':name,'record':record,'block':block})
    return {'path':str(path.resolve()), 'sha256':hashlib.sha256(raw).hexdigest(),
            'analysisStatus':report.get('analysis_status'), 'completeScopedInput':complete,
            'expected':report.get('expected'), 'selected':selected,
            'issues':report.get('issues')}


def analyze_warm(records, issues):
    entries = [r['data'] for r in records if r['marker'] == 'LIBRARY_CAUSAL_WARM']
    require(len(entries) == 1, 'warm: exactly one complete warm marker required', issues)
    if len(entries) != 1:
        return {'complete': False, 'originalRecords': records}
    d = entries[0]
    before, instrumented, after = [d[k] for k in ['productionBefore', 'instrumentedPlan', 'productionAfter']]
    normalized = instrumented['pageQuery']
    for field in ['title', 'body', 'tags']:
        normalized = normalized.replace('library_contains_'+field, 'library_contains')
    equivalent_sql = before['pageQuery'] == after['pageQuery'] == normalized
    equivalent_plan = before['queryPlan'] == instrumented['queryPlan'] == after['queryPlan']
    require(equivalent_sql == d['sqlDifferenceOnlyDiagnosticFunctionNames'], 'warm: SQL equality flag inconsistent', issues)
    require(equivalent_plan == d['planEquality'], 'warm: plan equality flag inconsistent', issues)
    require(equivalent_sql and equivalent_plan and d['resultEquality'], 'warm: SQL/plan/results equivalence failed', issues)
    require([r['sample'] for r in d['profiledReads']] == list(range(3)), 'warm: missing/reordered profiled sample', issues)
    require([r['sample'] for r in d['reference']['reads']] == list(range(3)), 'warm: missing/reordered reference sample', issues)
    reads = []
    for row in d['profiledReads']:
        fields = {}
        for name, f in row['fields'].items():
            valid = f['timingValid']
            require(all(f[k] >= 0 for k in ['calls', 'utf16Chars', 'matches']), f'warm {row["sample"]} {name}: negative count', issues)
            calls = f['calls']
            fields[name] = dict(f,
                conversionWallMs=f['conversionWallNs']/NS_PER_MS if valid else None,
                conversionCpuMs=f['conversionCpuNs']/NS_PER_MS if valid else None,
                containsWallMs=f['containsWallNs']/NS_PER_MS if valid else None,
                containsCpuMs=f['containsCpuNs']/NS_PER_MS if valid else None,
                utf16PerCall=f['utf16Chars']/calls if calls else None,
                conversionCpuNsPerCall=f['conversionCpuNs']/calls if valid and calls else None,
                containsCpuNsPerUtf16=f['containsCpuNs']/f['utf16Chars'] if valid and f['utf16Chars'] else None)
        page_fields = [fields[n] for n in ['title', 'body', 'tags']]
        all_valid = all(f['timingValid'] for f in fields.values())
        page_valid = row['pageTimingValid'] and all(f['timingValid'] for f in page_fields)
        page_udf_wall = sum(f['conversionWallNs']+f['containsWallNs'] for f in page_fields) if page_valid else None
        page_udf_cpu = sum(f['conversionCpuNs']+f['containsCpuNs'] for f in page_fields) if page_valid else None
        all_udf_wall = sum(f['conversionWallNs']+f['containsWallNs'] for f in fields.values()) if all_valid else None
        reads.append({'sample': row['sample'], 'completed': row['completed'], 'wallMs': row['wallNs']/NS_PER_MS,
            'pageWallMs': row['pageWallNs']/NS_PER_MS if row['pageTimingValid'] else None,
            'pageCpuMs': row['pageCpuNs']/NS_PER_MS if row['pageTimingValid'] else None,
            'fields': fields,
            'pageCallbackWallMs': page_udf_wall/NS_PER_MS if page_valid else None,
            'pageCallbackCpuMs': page_udf_cpu/NS_PER_MS if page_valid else None,
            'pageWallResidualMs': (row['pageWallNs']-page_udf_wall)/NS_PER_MS if page_valid else None,
            'pageCpuResidualMs': (row['pageCpuNs']-page_udf_cpu)/NS_PER_MS if page_valid else None,
            'wholeReadWallMinusAllCallbacksMs': (row['wallNs']-all_udf_wall)/NS_PER_MS if all_valid else None,
            'sourceExcludedFromPageResidual': True})
    return {'complete': len(entries) == 1, 'fixture': d['fixture'], 'query': d['query'],
            'sqlEquivalentAfterFunctionRename': equivalent_sql, 'planEquivalent': equivalent_plan,
            'recordedResultEquality': d['resultEquality'],
            'sqlSha256': {k: hashlib.sha256(d[k]['pageQuery'].encode()).hexdigest() for k in ['productionBefore', 'instrumentedPlan', 'productionAfter']},
            'productionBeforeWallMsIncludingExplain': before['wallNs']/NS_PER_MS,
            'productionAfterWallMsIncludingExplain': after['wallNs']/NS_PER_MS,
            'profiledReads': reads, 'reference': d['reference'], 'originalRecords': records}


def analyze_case(result, parts, end, issues):
    b, mode = result['block'], result['mode']
    prefix = f'block {b} {mode}'
    require([p['part'] for p in parts] == list(range(len(parts))), f'{prefix}: missing/reordered stage part', issues)
    events = [dict(e, originalEventOrdinal=i) for i, e in enumerate(e for p in parts for e in p['events'])]
    require(len(events) == end['recordedEvents'] and end['droppedEvents'] == 0, f'{prefix}: incomplete event stream', issues)
    require(end['completed'] and end['savesStarted'] and end['committedSaves'] == 30 and end['failureOutcome'] == 'none', f'{prefix}: incomplete/failed case', issues)
    raw_saves = result['saves']
    require([s['sample'] for s in raw_saves] == list(range(30)), f'{prefix}: missing/reordered save sample', issues)
    require([s['revision'] for s in raw_saves] == list(range(2, 32)), f'{prefix}: committed revision sequence mismatch', issues)
    # Queue insertion order can interleave after a thread has read its wall clock. Preserve
    # original order, and use the recorded wall times only to derive temporal intervals.
    temporal = sorted(events, key=lambda e: (e['wallNs'], e['originalEventOrdinal']))
    require(all(e['threadId'] > 0 and e['wallNs'] >= 0 for e in events), f'{prefix}: invalid wall/thread identifier', issues)
    grouped = defaultdict(list)
    for e in temporal:
        grouped[(e['owner'], e['sample'])].append(e)
    readers = []
    for (owner, number), evs in grouped.items():
        if owner != 'read' or number < 0:
            continue
        phases = phase_pairs(evs, READ_PHASES, issues, f'{prefix} read {number}')
        terminal = [e for e in evs if e['stage'] in ['read-end', 'read-cancel', 'read-error']]
        started = [e for e in evs if e['stage'] == 'read-start']
        if len(started) == len(terminal) == 1:
            phases['ioRead'] = delta(started[0], terminal[0])
        readers.append({'readId': number, 'terminal': terminal[-1]['stage'] if terminal else None,
                        'lastRecordedStage': evs[-1]['stage'], 'events': evs, 'phases': phases})
    batches = []
    for (owner, number), evs in grouped.items():
        evs = [e for e in evs if e['stage'] != 'committed-rows']
        if owner != 'index' or not evs:
            continue
        batches.append({'batch': number, 'events': evs, 'phases': phase_pairs(evs, INDEX_PHASES, issues, f'{prefix} batch {number}')})
    spans = {}
    for key, collection, phase in [('readerIO', readers, 'ioRead'), ('readerSnapshot', readers, 'snapshot'),
                                   ('indexPrepare', batches, 'prepare'), ('indexRequestToEntry', batches, 'requestToEntry'),
                                   ('indexEnteredBody', batches, 'enteredBody'), ('indexEnteredThroughReturn', batches, 'enteredThroughReturn')]:
        spans[key] = [s for g in collection if (s := full_interval(g['phases'], phase)) is not None]
    saves = []
    for row in raw_saves:
        n, start, finish = row['sample'], row['startNs'], row['endNs']
        require(row['committed'] and start <= finish, f'{prefix} save {n}: not committed/invalid window', issues)
        require(abs(row['elapsedMs']-(finish-start)/NS_PER_MS) < 1e-6, f'{prefix} save {n}: duration mismatch', issues)
        evs = grouped[('save', n)]
        require([e['stage'] for e in evs] == SAVE_STAGES, f'{prefix} save {n}: stage sequence mismatch', issues)
        require(all(start <= e['wallNs'] <= finish for e in evs), f'{prefix} save {n}: stage outside save', issues)
        phases = phase_pairs(evs, SAVE_PHASES, issues, f'{prefix} save {n}')
        observed = {key+'OverlapMs': overlap_union(start, finish, ints)/NS_PER_MS for key, ints in spans.items()}
        entry_delay = full_interval(phases, 'requestToEntry')
        if entry_delay:
            observed['saveRequestToEntryIndexBodyOverlapMs'] = overlap_union(*entry_delay, spans['indexEnteredBody'])/NS_PER_MS
        commits = [e for e in temporal if e['owner'] == 'index' and e['stage'] == 'committed-rows' and start <= e['wallNs'] <= finish]
        observed['indexCommittedRowEvents'] = [dict(wallNs=e['wallNs'], rows=e['sample']) for e in commits]
        observed['rowsAdvanced'] = row['indexRowsEnd']-row['indexRowsStart']
        observed['sourceChecksAdvanced'] = row['sourceChecksEnd']-row['sourceChecksStart']
        observed['indexCommitCountsProveWriterHold'] = False
        saves.append({'sample': n, 'raw': row, 'events': evs, 'phases': phases, 'overlap': observed})
    window = (raw_saves[0]['startNs'], raw_saves[-1]['endNs']) if raw_saves else (0, 0)
    control_start = [e for e in temporal if e['owner'] == 'control' and e['stage'] == 'saves-start']
    control_end = [e for e in temporal if e['owner'] == 'control' and e['stage'] == 'saves-end']
    require(len(control_start) == len(control_end) == 1, f'{prefix}: missing control window', issues)
    cancelled = [r for r in readers if r['terminal'] == 'read-cancel']
    before_cancel = Counter()
    for r in cancelled:
        work = [e['stage'] for e in r['events'] if e['stage'] not in ['snapshot-closed', 'connection-closed', 'read-cancel', 'observe-read-cancel']]
        if work:
            before_cancel[work[-1]] += 1
    phase_overlap = {}
    for owner, groups in [('reader', readers), ('index', batches)]:
        for name in sorted({k for group in groups for k in group['phases']}):
            intervals = [v for group in groups if (v := full_interval(group['phases'], name)) is not None]
            phase_overlap[owner+'.'+name] = overlap_union(*window, intervals)/NS_PER_MS
    threads = []
    for tid in sorted({e['threadId'] for e in temporal}):
        te = [e for e in temporal if e['threadId']==tid]
        valid = [e for e in te if e['threadCpuNs'] >= 0]
        monotonic = all(a['threadCpuNs'] <= z['threadCpuNs'] for a,z in zip(valid,valid[1:]))
        require(monotonic, f'{prefix}: thread {tid} CPU counter decreased', issues)
        threads.append({'threadId':tid, 'eventCount':len(te), 'owners':dict(Counter(e['owner'] for e in te)),
                        'firstWallNs':te[0]['wallNs'], 'lastWallNs':te[-1]['wallNs'],
                        'validCpuEvents':len(valid), 'cpuCounterMonotonic':monotonic,
                        'observedThreadCpuDeltaNs':valid[-1]['threadCpuNs']-valid[0]['threadCpuNs'] if valid and monotonic else None,
                        'cpuIsExclusiveWork':False})
    conditional = {}
    for name, predicate in [
        ('indexEnteredBodyOverlap', lambda s: s['overlap']['indexEnteredBodyOverlapMs']>0),
        ('noObservedIndexEnteredBodyOverlap', lambda s: s['overlap']['indexEnteredBodyOverlapMs']==0),
        ('readerIOOverlap', lambda s: s['overlap']['readerIOOverlapMs']>0),
        ('noObservedReaderIOOverlap', lambda s: s['overlap']['readerIOOverlapMs']==0),
        ('indexRowsAdvanced', lambda s: s['overlap']['rowsAdvanced']>0),
        ('sourceChecksAdvanced', lambda s: s['overlap']['sourceChecksAdvanced']>0),
    ]:
        selected = [s for s in saves if predicate(s)]
        conditional[name] = {'sampleIds':[s['sample'] for s in selected],
                             'saveMs':distribution([s['raw']['elapsedMs'] for s in selected]),
                             'exploratoryOnly':True}
    return {'block': b, 'mode': mode, 'seed': result['seed'], 'readiness': result['readiness'],
            'fixture': result['fixture'], 'saveMs': distribution([s['elapsedMs'] for s in raw_saves]),
            'sampleCount': len(raw_saves), 'allCommitted': all(s['committed'] for s in raw_saves),
            'saveWindowNs': list(window), 'controlStartNs': control_start[0]['wallNs'] if control_start else None,
            'controlEndNs': control_end[0]['wallNs'] if control_end else None,
            'indexRowsAfterSaves': result['indexRowsAfterSaves'], 'sourceChecksAfterSaves': result['sourceChecks'],
            'readerCounts': {'traced': len(readers), 'started': sum(any(e['stage']=='read-start' for e in r['events']) for r in readers),
                             'completed': sum(r['terminal']=='read-end' for r in readers), 'cancelled': len(cancelled),
                             'errored': sum(r['terminal']=='read-error' for r in readers),
                             'noTerminal': sum(r['terminal'] is None for r in readers), 'lastWorkBeforeCancel': dict(before_cancel)},
            'observationEvents': dict(Counter(e['stage'] for e in temporal if e['owner']=='read' and e['sample'] < 0)),
            'trackerEventCount': sum(e['owner']=='tracker' for e in temporal),
            'savePhases': summarize_phases(saves), 'readerPhases': summarize_phases(readers), 'indexPhases': summarize_phases(batches),
            'windowOverlapMs': {key: overlap_union(*window, ints)/NS_PER_MS for key, ints in spans.items()},
            'phaseSaveWindowOverlapMs':phase_overlap, 'threads':threads,
            'conditionalSaveMs':conditional,
            'overlapIsTemporalNotCausal': True, 'incompleteIntervalsAreNotExtendedOrImputed': True,
            'saves': saves, 'readers': readers, 'indexBatches': batches, 'originalEvents': events,
            'end': end}


def analyze_saves(records, issues):
    results = [r['data'] for r in records if r['marker']=='LIBRARY_SAVE_CAUSAL_RESULT']
    expected = [(b, m) for b in range(2) for m in (MODES if b==0 else MODES[::-1])]
    actual = [(r['block'], r['mode']) for r in results]
    require(actual == expected, 'saves: expected all 12 cases in fixed forward/reverse order', issues)
    cases = []
    for r in results:
        key = r['block'], r['mode']
        parts = [x['data'] for x in records if x['marker']=='LIBRARY_SAVE_CAUSAL_STAGES' and (x['data']['block'], x['data']['mode'])==key]
        ends = [x['data'] for x in records if x['marker']=='LIBRARY_SAVE_CAUSAL_END' and (x['data']['block'], x['data']['mode'])==key]
        require(len(ends) == 1, f'{key}: expected one end marker', issues)
        if len(ends) == 1:
            cases.append(analyze_case(r, parts, ends[0], issues))
    same_fixtures = {}
    for b in range(2):
        subset = [c for c in cases if c['block']==b]
        same_fixtures[str(b)] = len(subset)==6 and len({json.dumps(c['fixture'], sort_keys=True) for c in subset})==1
        require(same_fixtures[str(b)], f'block {b}: modes do not share the same logical fixture', issues)
    contrasts = []
    for b in range(2):
        by_mode = {c['mode']: c for c in cases if c['block']==b}
        for a, z in [('IDLE','TRACKER'), ('IDLE','INDEX_ONLY'), ('TRACKER','INDEX_TRACKER'),
                     ('IDLE','READS_ONLY'), ('READS_ONLY','FULL'), ('INDEX_ONLY','FULL'), ('IDLE','FULL')]:
            if a not in by_mode or z not in by_mode:
                continue
            row = {'block': b, 'from': a, 'to': z, 'additiveCausalEstimate': False,
                   'sameReadiness': by_mode[a]['readiness']==by_mode[z]['readiness']}
            for metric in ['p50','p95','max']:
                old, new = by_mode[a]['saveMs'][metric], by_mode[z]['saveMs'][metric]
                row[metric+'DeltaMs'] = new-old
                row[metric+'DeltaPct'] = (new-old)/old*100 if old else None
            contrasts.append(row)
    return {'cases': cases, 'sameLogicalFixtureWithinBlock': same_fixtures,
            'contrasts': contrasts, 'originalRecords': records}


def write_csv(path, rows):
    with path.open('w', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=list(dict.fromkeys(k for row in rows for k in row)) if rows else ['empty'])
        writer.writeheader()
        writer.writerows(rows)


LIMITS = [
    'Causal-only, one instrumented run. These samples do not replace the frozen public probe or any original gate.',
    'All 30 saves per case remain in original order. p50 uses ceil(.5*n)-1; p95 uses ceil(.95*n)-1; no trimming or pooling across blocks.',
    'Mode differences are not additive causal effects: readiness differs (fixture, tracker registration, first source check or first index commit); background progress/cache/scheduling differs.',
    'Equal logical fixture hashes within a block do not imply equal physical database/cache/WAL state at save start.',
    'requestToEntry includes scheduling, Room serialization and transaction begin. It is not a direct SQLite lock-wait measurement.',
    'Same-thread CPU deltas are thread counter increments, not exclusive operation CPU across coroutine suspension. Cross-thread or invalid-counter CPU deltas remain null.',
    'Overlap shows co-occurrence, not causation. Index enteredBody is inside the transaction; enteredThroughReturn also includes transaction exit and possible coroutine resumption.',
    'A committed-rows callback proves a completed batch, not its writer hold interval. Counter changes are not substitutes for stage timestamps.',
    'Incomplete stage pairs are retained and never extended to the save end. Complete-interval overlap can undercount activity whose endpoint was not observed.',
    'READS_ONLY does not inject diagnostics into LibrarySearchObservation, so absent observe-invalidation/cancel events cannot be compared with FULL as absent invalidations.',
    'Diagnostic callbacks/clocks/queues perturb both save and warm timings. UDF-off planned reads include EXPLAIN and are not the public warm benchmark.',
    'Page residual subtracts only title/body/tags callbacks. Source callbacks occur before page SQL and are excluded from that subtraction.',
    'Args.getString timing starts inside the Java callback; it omits earlier SQLite argument evaluation/materialization. Residual time is not exclusively JNI, I/O or locks.',
    'Kotlin reference excludes preload, FTS, SQL, sorting and result construction; its wall/CPU is not a complete library read. Result identity is asserted in the instrumented test, not reconstructed from IDs in logs.',
    'Artifact/source/APK/environment attestation remains external. This script records input byte hashes and checks relations but does not recreate the controller verification.',
]


def number(value):
    return 'unavailable' if value is None else f'{value:.6f}'


def write_report(out, analysis):
    warm, saves = analysis['warm'], analysis['saves']
    lines = ['# Search causal evidence analysis', '', 'Status: '+analysis['status']+'. Native acceptance thresholds are unchanged.', '',
             '## Input binding', '']
    for key, value in analysis['inputs'].items():
        lines.append(f'- {key}: `{value["path"]}`, SHA-256 `{value["sha256"]}`, {value["records"]} records.')
    lines.append('- Scoped extraction: `'+analysis['extraction']['analysisStatus']+'`; complete selected bindings: '+str(analysis['extraction']['completeScopedInput'])+'.')
    lines.append('- Expected source/run binding: `'+json.dumps(analysis['extraction']['expected'],sort_keys=True)+'`.')
    lines += ['', '## Warm UDF attribution', '',
              f'SQL equality after diagnostic function renaming: **{warm.get("sqlEquivalentAfterFunctionRename")}**. Actual plan equality: **{warm.get("planEquivalent")}**.', '',
              '| Read | Field | Calls | UTF-16 | getString wall ms | getString CPU ms | contains wall ms | contains CPU ms |',
              '|---|---|---:|---:|---:|---:|---:|---:|']
    for r in warm.get('profiledReads', []):
        for name, f in r['fields'].items():
            lines.append(f'| {r["sample"]} | {name} | {f["calls"]} | {f["utf16Chars"]} | {number(f["conversionWallMs"])} | {number(f["conversionCpuMs"])} | {number(f["containsWallMs"])} | {number(f["containsCpuMs"])} |')
    lines += ['', '| Read | Whole read ms | Page wall ms | Page CPU ms | Page wall residual ms | Page CPU residual ms | Whole read minus all callbacks ms |',
              '|---|---:|---:|---:|---:|---:|---:|']
    for r in warm.get('profiledReads', []):
        lines.append('| '+str(r['sample'])+' | '+' | '.join(number(r[k]) for k in ['wallMs','pageWallMs','pageCpuMs','pageWallResidualMs','pageCpuResidualMs','wholeReadWallMinusAllCallbacksMs'])+' |')
    lines += ['', 'UDF-off planned read wall times (including EXPLAIN): before '+number(warm.get('productionBeforeWallMsIncludingExplain'))+' ms; after '+number(warm.get('productionAfterWallMsIncludingExplain'))+' ms. Their SQL and all actual plans remain in analysis.json.', '',
              'Reference preload wall: '+number(warm.get('reference',{}).get('preloadWallNs',0)/NS_PER_MS)+' ms. Each reference match interval follows preload and excludes SQLite/FTS/sorting.', '']
    for r in warm.get('reference', {}).get('reads', []):
        valid = r['timingValid']
        lines.append(f'- Reference {r["sample"]}: wall {number(r["wallNs"]/NS_PER_MS if valid else None)} ms; CPU {number(r["cpuNs"]/NS_PER_MS if valid else None)} ms; matches {r["matchCount"]}.')
    lines += ['', '## All 12 save cases', '', '| Block | Mode | Readiness | Saves | p50 ms | p95 ms | max ms | Index rows after saves | Source checks after saves |',
              '|---:|---|---|---:|---:|---:|---:|---:|---:|']
    for c in saves['cases']:
        lines.append(f'| {c["block"]} | {c["mode"]} | {c["readiness"]} | {c["sampleCount"]} | '+
                     ' | '.join(number(c['saveMs'][k]) for k in ['p50','p95','max'])+f' | {c["indexRowsAfterSaves"]} | {c["sourceChecksAfterSaves"]} |')
    lines += ['', '## Phase and overlap evidence', '', 'Every save phase, endpoint thread ID, valid same-thread CPU increment, individual read/index interval, cancellation stage and original event is retained in analysis.json and the CSVs. Temporal sums below use unions, so parallel intervals are not double-counted.', '']
    for c in saves['cases']:
        p = c['savePhases'].get('requestToEntry',{}).get('wallMs',{})
        r = c['readerCounts']
        lines += [f'### Block {c["block"]} {c["mode"]}', '',
                  f'Request-to-entry p50/p95/max: {number(p.get("p50"))}/{number(p.get("p95"))}/{number(p.get("max"))} ms. Readers started/completed/cancelled/no terminal: {r["started"]}/{r["completed"]}/{r["cancelled"]}/{r["noTerminal"]}.',
                  'Save-window overlap ms: '+', '.join(k+'='+number(v) for k,v in c['windowOverlapMs'].items())+'.',
                  'Last work stage before reader cancellation: `'+json.dumps(r['lastWorkBeforeCancel'],sort_keys=True)+'`.', '']
        lines += ['Save phase wall distributions (same-thread CPU remains a non-exclusive counter):', '',
                  '| Phase | n | p50 ms | p95 ms | max ms | Valid same-thread CPU pairs |',
                  '|---|---:|---:|---:|---:|---:|']
        for name, phase in c['savePhases'].items():
            wall=phase['wallMs']
            lines.append(f'| {name} | {wall["n"]} | '+ ' | '.join(number(wall[k]) for k in ['p50','p95','max'])+f' | {phase["sameThreadCpuMs"]["n"]} |')
        lines += ['', 'Read/index phase unions clipped to the actual save window (ms): `'+json.dumps(c['phaseSaveWindowOverlapMs'],sort_keys=True)+'`.',
                  'All pre-readiness and cleanup intervals remain in JSON; the phase wall summaries there are not restricted to the save window.', '']
    lines += ['## Interpretation limits', '']+['- '+s for s in LIMITS]
    lines += ['', '## Relation issues', '']
    lines += ['- '+s for s in analysis['issues']] if analysis['issues'] else ['No relation issues detected. This is not a performance acceptance verdict.']
    (out/'analysis.md').write_text('\n'.join(lines)+'\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--warm', type=Path)
    parser.add_argument('--saves', type=Path)
    parser.add_argument('--unicode', type=Path)
    parser.add_argument('--extraction-analysis', type=Path)
    parser.add_argument('--synthetic-interface-check', action='store_true', help='Label all output as synthetic, never native evidence')
    parser.add_argument('--out', type=Path)
    parser.add_argument('--self-check', action='store_true')
    args = parser.parse_args()
    if args.self_check:
        assert distribution(list(range(30))) == {'n':30,'p50':14,'p95':28,'max':29,'sum':435}
        assert overlap_union(0, 10, [(1,4),(3,7),(9,20)]) == 7
        a={'wallNs':1,'threadId':7,'threadCpuNs':20}
        b={'wallNs':10,'threadId':8,'threadCpuNs':200}
        assert delta(a,b)['threadCpuDeltaNs'] is None
        b.update(threadId=7,threadCpuNs=30)
        assert delta(a,b)['threadCpuDeltaNs']==10
        b['threadCpuNs']=-1
        assert delta(a,b)['threadCpuDeltaNs'] is None
        issues=[]
        partial=phase_pairs([dict(a,stage='begin')],[('work','begin','end')],issues,'offline')
        assert full_interval(partial,'work') is None and not issues
        print('OFFLINE_SELF_CHECK_PASS (synthetic arithmetic only; no Android/performance result)')
        return
    if not (args.warm and args.saves and args.extraction_analysis and args.out):
        parser.error('--warm, --saves, --extraction-analysis and --out are required')
    issues=[]
    warm, warm_input=load_records(args.warm)
    saves, save_input=load_records(args.saves)
    analysis={'schemaVersion':1,'scope':'CAUSAL_ONLY','inputs':{'warm':warm_input,'saves':save_input},
              'warm':analyze_warm(warm,issues),'saves':analyze_saves(saves,issues),'limits':LIMITS,'issues':issues}
    if args.unicode:
        unicode, provenance=load_records(args.unicode)
        analysis['inputs']['unicode']=provenance;analysis['unicodeRecords']=unicode
        require(len(unicode)==1 and unicode[0]['marker']=='LIBRARY_CAUSAL_UNICODE', 'unicode: expected one complete correctness marker', issues)
    analysis['extraction']=extraction_binding(args.extraction_analysis,analysis['inputs'],issues)
    analysis['status']='RELATIONS_CHECKED_CAUSAL_ONLY' if not issues else 'RELATION_ISSUES_REVIEW_REQUIRED'
    if args.synthetic_interface_check:
        analysis['status']='OFFLINE_SYNTHETIC_INTERFACE_ONLY'
    args.out.mkdir(parents=True,exist_ok=True)
    (args.out/'analysis.json').write_text(json.dumps(analysis,ensure_ascii=False,indent=2)+'\n')
    sample_rows=[];phase_rows=[];event_rows=[];udf_rows=[]
    for c in analysis['saves']['cases']:
        for s in c['saves']:
            sample_rows.append({'block':c['block'],'mode':c['mode'],**s['raw'],
                               **{k:v for k,v in s['overlap'].items() if isinstance(v,(int,float)) and not isinstance(v,bool)}})
            for label,p in s['phases'].items():
                if 'wallNs' in p:
                    phase_rows.append({'block':c['block'],'mode':c['mode'],'sample':s['sample'],'phase':label,**p})
        event_rows.extend({'block':c['block'],'mode':c['mode'],**e} for e in c['originalEvents'])
    for r in analysis['warm'].get('profiledReads',[]):
        udf_rows.extend({'sample':r['sample'],'field':name,**f} for name,f in r['fields'].items())
    write_csv(args.out/'save-samples.csv',sample_rows);write_csv(args.out/'save-phases.csv',phase_rows)
    write_csv(args.out/'original-events.csv',event_rows);write_csv(args.out/'warm-udf.csv',udf_rows)
    write_report(args.out,analysis)
    print(json.dumps({'status':analysis['status'],'issues':len(issues),'cases':len(analysis['saves']['cases']),
                      'saves':len(sample_rows),'events':len(event_rows),'out':str(args.out)}))
    raise SystemExit(1 if issues else 0)


if __name__=='__main__':
    main()
