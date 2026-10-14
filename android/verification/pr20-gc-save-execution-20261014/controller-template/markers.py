"""Pure bounded JSON validation. Never print unvalidated diagnostic payloads."""
import hashlib
import json
import math
import re
from collections import Counter, defaultdict


def validate_shape(value, schema):
    if 'const' in schema:
        assert type(value) is type(schema['const']) and value==schema['const'], 'Constant mismatch'
        return
    if 'enum' in schema:
        assert any(type(value) is type(x) and value==x for x in schema['enum']), 'Enum mismatch'
        if 'type' not in schema:return
    if value is None and (schema.get('nullable',False) or 'null' in schema.get('type',[])):
        return
    kind=schema['type']
    if isinstance(kind,list):kind=next(x for x in kind if x!='null')
    if kind=='object':
        assert type(value) is dict
        required=set(schema['required']); allowed=set(schema['properties'])
        assert required <= set(value) <= allowed, 'Unknown/missing object key'
        for key,child in value.items():
            try:validate_shape(child,schema['properties'][key])
            except AssertionError as error:raise AssertionError('Field '+key+': '+str(error)) from error
    elif kind=='array':
        assert type(value) is list
        assert schema.get('minItems',schema.get('minLength',schema.get('length',0)))<=len(value)<=schema.get('maxItems',schema.get('maxLength',schema.get('length',0))), 'Array bounds'
        if 'prefixItems' in schema:
            assert len(value)==len(schema['prefixItems']) and schema.get('items') is False, 'Fixed tuple length'
            for child,child_schema in zip(value,schema['prefixItems']):validate_shape(child,child_schema)
        else:
            for child in value:validate_shape(child,schema['items'])
    elif kind in ['number','integer']:
        assert type(value) is int if kind=='integer' else type(value) in [int,float]
        assert math.isfinite(value) and schema.get('minimum',0)<=value<=schema.get('maximum',9223372036854775807), 'Numeric bounds'
    elif kind=='boolean':assert type(value) is bool
    elif kind=='string':
        assert type(value) is str and len(value)<=schema.get('maxLength',max(map(len,schema.get('enum',['']))))
        assert value in schema['enum'] if 'enum' in schema else re.fullmatch(schema['pattern'],value), 'String allowlist'
    else:assert kind=='null' and value is None
    if 'const' in schema:assert value==schema['const'] and type(value) is type(schema['const'])
    if 'enum' in schema and kind!='string':assert value in schema['enum']


def safe_marker_payloads(entry, raw, contract):
    assert contract['status']=='FROZEN', 'Unbound marker contract'
    rule=contract['methods'][str(entry['ordinal'])]
    assert rule['selection']==entry['selection'] and rule['marker_policy']==entry['marker_policy']
    def pairs(items):
        result={}
        for key,value in items:
            assert key not in result, 'Duplicate JSON key'
            result[key]=value
        return result
    def constant(value):raise AssertionError('Non-finite JSON constant')
    payloads=[]
    for line in raw.splitlines():
        match=re.search(r'\b((?:KVM|LIBRARY|HISTORY|TILE|INDEX|RASTER)_[A-Z0-9_]+)(.*)$',line)
        if not match:continue
        marker,tail=match.groups()
        assert marker in rule['markers'] and tail.startswith(' '), 'Unknown/malformed marker'
        original=tail[1:]
        assert len(original.encode())<=rule['markers'][marker]['payload_max_bytes'], 'Marker byte limit'
        data=json.loads(original,object_pairs_hook=pairs,parse_constant=constant)
        if rule['kind']!='gc-boundary':validate_shape(data,contract['schemas'][marker])
        payloads.append({'marker':marker,'original_json':original,'data':data})
    counts=Counter(p['marker'] for p in payloads)
    for marker,bound in rule['markers'].items():
        assert bound['min_count']<=counts[marker]<=bound['max_count'], 'Marker count'
    assert sum(len(p['original_json'].encode()) for p in payloads)<=rule['method_payload_max_bytes'], 'Method byte limit'
    if entry['marker_policy']=='none':assert not payloads and not rule['markers']
    elif rule['kind']=='snapshot-control':validate_snapshot_control(payloads,contract['snapshot_control'])
    elif rule['kind']=='gc-boundary':validate_gc_boundary(payloads,rule['connection'])
    else:assert entry['marker_policy']=='none', 'Unknown diagnostic kind'
    return payloads


def validate_author_samples(events,reads,saves):
    # A background fragment may enter before a window starts. Every submit that was
    # actually recorded must have exactly one recorded terminal, including sample=-1.
    submissions=[e for e in events if e['stage']=='writer-submit']
    assert len({e['task']for e in submissions})==len(submissions), 'Duplicate writer task submission'
    for submit in submissions:
        terminal=[e for e in events if e['task']==submit['task'] and e['stage']in ['writer-run-exit','writer-submit-failed']]
        assert len(terminal)==1 and terminal[0]['sample']==submit['sample'] and submit['wallNs']<=terminal[0]['wallNs'], 'Recorded writer submit missing terminal'
    for stream in [events,reads]:
        previous={}
        for event in stream:
            thread=event['threadId'];value=(event['wallNs'],event['threadCpuNs'])
            if thread in previous:assert value[0]>=previous[thread][0] and value[1]>=previous[thread][1], 'Per-thread counters decreased'
            previous[thread]=value
    previous_end=-1
    for save in saves:
        sample=save['sample'];start=save['startNs'];stop=save['endNs']
        assert previous_end<=start<=stop
        previous_end=stop
        assert save['revision']==sample+2
        traced=[e for e in events if e['sample']==sample]
        assert all(e['stage']not in ['save-failed','writer-submit-failed'] and e['outcome']!='threw'for e in traced), 'Successful author sample contains failure evidence'
        def one(stage,depth=None,transaction=None):
            found=[e for e in traced if e['stage']==stage and (depth is None or e['depth']==depth) and (transaction is None or e['transaction']==transaction)]
            assert len(found)==1, 'Expected one '+stage
            return found[0]
        request=one('save-request');returned=one('save-returned')
        submit=one('writer-submit');run_enter=one('writer-run-enter');run_exit=one('writer-run-exit')
        assert submit['task']==run_enter['task']==run_exit['task']>0
        assert submit['outcome']==run_enter['outcome']=='entered' and run_exit['outcome']=='returned'
        task=submit['task'];thread=run_enter['threadId']
        assert run_exit['threadId']==thread and request['wallNs']<=submit['wallNs']<=run_enter['wallNs']
        body=[one('save-'+stage)for stage in ['writer-acquired','head-read','head-written','revision-written','receipt-written']]
        for event in [request,returned,*body]:
            assert event['depth']==event['transaction']==0 and event['outcome']=='returned' and event['successRequested']is False
        room_begin=one('room-begin-exit',1)
        # Trigger sync may be another complete outer SQLite transaction within Room BEGIN.
        # Select the latest outer SQLite BEGIN preceding actual author body entry.
        eligible=[e for e in traced if e['stage']=='sqlite-begin-exit'and e['depth']==1 and e['outcome']=='returned'and e['wallNs']<=body[0]['wallNs']]
        assert eligible
        sqlite_begin=max(eligible,key=lambda e:e['wallNs'])
        def outer(layer,begin):
            tx=begin['transaction'];assert tx>0
            values={action+'-'+edge:one(layer+'-'+action+'-'+edge,1,tx)for action in ['begin','success','end']for edge in ['enter','exit']}
            for label,event in values.items():
                assert event['task']==task and event['threadId']==thread
                assert event['outcome']==('entered'if label.endswith('-enter')else 'returned')
                assert event['successRequested']is (label in ['success-exit','end-enter','end-exit'])
            return values
        room=outer('room',room_begin);sqlite=outer('sqlite',sqlite_begin)
        # Do not require Room and SQLite transaction IDs to be equal; their namespaces
        # arise at distinct interception layers. Match all six boundaries within each.
        timeline=[room['begin-enter'],sqlite['begin-enter'],sqlite['begin-exit'],room['begin-exit'],*body,
                  room['success-enter'],sqlite['success-enter'],sqlite['success-exit'],room['success-exit'],
                  room['end-enter'],sqlite['end-enter'],sqlite['end-exit'],room['end-exit']]
        assert all(e['task']==task and e['threadId']==thread for e in timeline)
        times=[e['wallNs']for e in timeline];cpus=[e['threadCpuNs']for e in timeline]
        assert times==sorted(times) and cpus==sorted(cpus), 'Author boundaries out of order'
        assert start<=request['wallNs']<=run_enter['wallNs']<=times[0]<=times[-1]<=returned['wallNs']<=stop
        # Worker exit can occur after caller return; it is not a caller-delay endpoint.
        assert times[-1]<=run_exit['wallNs']


def validate_snapshot_control(payloads, spec):
    modes=spec['order'];expected=[]
    results=[p['data'] for p in payloads if p['marker']=='LIBRARY_SNAPSHOT_CONTROL_RESULT']
    ends=[p['data'] for p in payloads if p['marker']=='LIBRARY_SNAPSHOT_CONTROL_END']
    assert [r['mode'] for r in results]==modes and [e['mode'] for e in ends]==modes
    for i,(result,end) in enumerate(zip(results,ends)):
        mode=result['mode'];e=spec['expected']
        assert result['readiness']==e['readiness'][i]
        assert result['readerBeforeRevision']==e['readerBeforeRevision'][i]
        assert result['readerAfterRevision']==e['readerAfterRevision'][i]
        expected.append(('LIBRARY_SNAPSHOT_CONTROL_RESULT',mode))
        rows=[];readrows=[]
        for suffix,target,count,cap in [('EVENTS',rows,end['recordedEvents'],1536),('READS',readrows,end['recordedReadEvents'],128)]:
            marker='LIBRARY_SNAPSHOT_CONTROL_'+suffix
            parts=[p['data'] for p in payloads if p['marker']==marker and p['data']['mode']==mode]
            assert [p['part'] for p in parts]==list(range(len(parts))), 'Missing/duplicate/out-of-order part'
            assert all(len(p['events'])==96 for p in parts[:-1])
            target.extend(row for part in parts for row in part['events'])
            assert len(target)==count<=cap
            expected.extend((marker,mode) for _ in parts)
        expected.append(('LIBRARY_SNAPSHOT_CONTROL_END',mode))
        events=[]
        for row in rows:
            events.append(dict(stage=spec['boundaryStages'][row[0]],sample=row[1],task=row[2],transaction=row[3],
                depth=row[4],successRequested=bool(row[5]),outcome=spec['outcomes'][row[6]],
                wallNs=row[7],threadCpuNs=row[8],threadId=row[9]))
        reads=[dict(owner=spec['owners'][r[0]],stage=spec['readStages'][r[1]],read=r[2],count=r[3],
                    wallNs=r[4],threadCpuNs=r[5],threadId=r[6])for r in readrows]
        saves=[dict(sample=r[0],startNs=r[1],endNs=r[2],revision=r[3],committed=r[4])for r in result['saves']]
        assert [r['sample'] for r in saves]==list(range(30)) and all(r['committed']==1 for r in saves)
        validate_author_samples(events,reads,saves)
        start,stop=saves[0]['startNs'],saves[-1]['endNs']
        def one(owner,stage):
            matches=[r for r in reads if r['owner']==owner and r['stage']==stage]
            assert len(matches)==1, 'Reader stage missing/duplicate: '+stage
            return matches[0]
        if mode in ['IDLE','HELD_SNAPSHOT']:
            names=(['connection-open','snapshot-open','pin-read','snapshot-closed','connection-closed','idle-ready'] if mode=='IDLE' else
                   ['connection-open','snapshot-open','pin-read','hold-enter','hold-exit','pin-recheck','snapshot-closed','connection-closed'])
            assert [(r['owner'],r['stage']) for r in reads]==[('control',name)for name in names]
            assert len({r['threadId'] for r in reads})==1 and all(r['read']==0 for r in reads)
            times=[r['wallNs'] for r in reads];assert times==sorted(times)
            assert one('control','pin-read')['count']==1
            if mode=='IDLE':assert one('control','idle-ready')['wallNs']<=start
            else:
                assert one('control','pin-recheck')['count']==1
                assert one('control','hold-enter')['wallNs']<=start<=stop<=one('control','hold-exit')['wallNs']
        else:
            expected_stages=spec['readStages'][:9]+['active-ready']+spec['readStages'][9:21]
            assert [r['stage'] for r in reads]==expected_stages, 'ACTIVE full lifecycle'
            assert all(r['owner']==('control' if r['stage']=='active-ready' else 'search') for r in reads)
            assert [r['wallNs'] for r in reads]==sorted(r['wallNs'] for r in reads)
            first_native=expected_stages.index('connection-open');last_native=expected_stages.index('connection-closed')
            assert len({r['threadId'] for r in reads[first_native:last_native+1]})==1, 'ACTIVE native thread'
            control=[r for r in reads if r['owner']=='control']
            assert len(control)==1 and control[0]['stage']=='active-ready' and control[0]['read']==0
            search=[r for r in reads if r['owner']=='search']
            assert search and all(r['read']==1 and r['stage'] not in ['read-cancel','read-error'] for r in search)
            names=['read-requested','read-start','connection-open','snapshot-open','page-hits-start','page-hits-end','snapshot-closed','connection-closed','read-end']
            lifecycle=[one('search',name)for name in names]
            times=[r['wallNs']for r in lifecycle];assert times==sorted(times)
            pin=one('search','snapshot-open');opened=one('search','page-hits-start');closed=one('search','page-hits-end')
            assert pin['wallNs']<=opened['wallNs']<=control[0]['wallNs']<=start<=stop<=closed['wallNs']
            # Synchronous SQL's page-hits interval must belong to one native reader thread.
            assert opened['threadId']==closed['threadId']==pin['threadId']==control[0]['threadId']
    assert [(p['marker'],p['data']['mode'])for p in payloads]==expected, 'Whole marker stream order'


# GC pure semantic functions copied from independently reviewed analyzer 7d26b088f0ead4b10646f9d794d1783ef88bc9850073054d886a9de5bb31dd12
"""Independent AUTHOR_ONLY GC marker arithmetic; never executes a controller/device."""
gc_PRODUCER_SHA256 = '597bed443289598870d063af99731a60db48ca0e38d8e3576b24fcd995cf4055'
gc_COLUMNS = ['stage', 'ownerToken', 'task', 'transaction', 'depth', 'successRequested', 'outcome', 'atNs', 'cpuNs', 'thread']
gc_MODES = ('same-room', 'separate-room')
gc_MARKERS = ('HISTORY_GC_BOUNDARY_EVENTS', 'HISTORY_GC_BOUNDARY_TRIAL', 'HISTORY_GC_BOUNDARY_SUMMARY')
gc_SEGMENTS = ['requestToSubmit', 'writerQueue', 'workerToSqliteBegin', 'sqliteBeginCall', 'sqliteBeginToRoomBegin', 'roomBeginToReceipt', 'receiptToSqliteEnd', 'sqliteEndCall', 'sqliteEndToRoomEnd', 'roomEndToRenameReturn', 'renameReturnToCallEnd']
gc_TRIAL_KEYS = set('sample connection scenario replacesFrozenProbe fixtureSourceVersions fixtureDetachedSources fixtureExplicitRoots fixtureLatestRetained artificialGates committed saveOutcome saveWatchdogMs batchLimit authorBoundaries boundaryCapacityPerDatabase batchCapacity phaseCapacity boundaryScope pureSqliteLockWaitMs gcOwnerToken authorOwnerToken finishOwnerToken initialRevision writerSqlite authorWriterSqlite savedRevision finalRevision contentVerified retainedRevisions status stage failureClass suppressedFailureClasses collectorFailureClass collectorStartNs collectorEndNs saveStartNs saveEndNs saveMs beforeRenameReceiptNs afterRenameTransactionNs firstDeleted firstDeferred firstRootScans firstCommittedBatches finishStartNs finishEndNs finishDeleted finishRootScans finishDeferred deletionTransactionExposed deleteWindowIntersected saveStartedDuringDelete deleteIntervals batchEvents batchDropped phaseDropped workersExited helpersClosed fixtureDeleted boundaries coveredBeforeBatchCommitNs phases'.split())
gc_BATCH_KEYS = set('phase afterSequence candidates expectedDataVersion expectedChanges observedDataVersion observedChanges atNs cpuNs thread callbackNs'.split())

class gc_Invalid(ValueError):
    pass

def gc_require(ok, why):
    if not ok:
        raise gc_Invalid(why)

def gc_integer(v):
    return type(v) is int

def gc_num(v):
    return type(v) in (int, float) and math.isfinite(v)

def gc_same(a, b):
    return a == b

def gc_close(a, b):
    return gc_num(a) and gc_num(b) and math.isclose(a, b, rel_tol=0, abs_tol=1e-08)

def gc_one(es, stage, **kw):
    found = [e for e in es if e['stage'] == stage and all((e[k] == v for k, v in kw.items()))]
    gc_require(len(found) == 1, f'unique {stage} {kw}: {len(found)}')
    return found[0]

def gc_stats(v, available=True):
    ordered = sorted(v)
    n = len(v)
    return {'count': n, 'p50Ms': ordered[math.ceil(n * 0.5) - 1] if n and available else None, 'p95Ms': ordered[math.ceil(n * 0.95) - 1] if n and available else None, 'maxMs': ordered[-1] if n else None}

def gc_cpu(a, b):
    if 'cpuNs' not in a or 'cpuNs' not in b:
        return (None, 'ENDPOINT_CPU_NOT_RECORDED')
    if a['thread'] != b['thread']:
        return (None, 'CROSS_THREAD')
    gc_require(b['cpuNs'] >= a['cpuNs'], 'CPU went backwards')
    return (b['cpuNs'] - a['cpuNs'], None)

def gc_union(intervals, lo, hi):
    points = Counter()
    for a, b in intervals:
        a, b = (max(a, lo), min(b, hi))
        if a < b:
            points[a] += 1
            points[b] -= 1
    active = 0
    previous = lo
    total = 0
    for t, delta in sorted(points.items()):
        if active:
            total += t - previous
        active += delta
        previous = t
    gc_require(active == 0, 'interval sweep failed')
    return total

def gc_monotonic_by_thread(es):
    previous = {}
    for e in es:
        for k in ('atNs', 'cpuNs', 'thread'):
            gc_require(gc_integer(e[k]), 'bad clock/thread type')
        gc_require(e['cpuNs'] >= 0 and e['thread'] > 0, 'invalid CPU/thread')
        p = previous.get(e['thread'])
        if p:
            gc_require(p['atNs'] <= e['atNs'] and p['cpuNs'] <= e['cpuNs'], 'per-thread clock order')
        previous[e['thread']] = e

def gc_validate_transactions(events, layer):
    stack = []
    pending = []
    closed = []
    for e in events:
        if not e['stage'].startswith(layer + '-'):
            continue
        _, operation, edge = e['stage'].split('-')
        gc_require(operation in ('begin', 'success', 'end') and edge in ('enter', 'exit'), 'unknown operation')
        depth = e['depth']
        tx = e['transaction']
        if edge == 'enter':
            gc_require(e['outcome'] == 'entered', 'entry outcome')
            gc_require(depth == len(stack) + (operation == 'begin'), 'transaction depth')
            if operation == 'begin':
                gc_require(tx > 0 and (not stack or tx == stack[0]['transaction']), 'begin transaction identity')
                gc_require(e['successRequested'] is False, 'begin success flag')
            else:
                gc_require(stack and tx == stack[0]['transaction'], 'operation without matching begin')
                gc_require(e['successRequested'] == stack[-1]['success'], 'success flag before operation')
            pending.append(e)
        else:
            gc_require(pending, 'exit without entry')
            start = pending.pop()
            gc_require(start['stage'] == f'{layer}-{operation}-enter', 'operation pair mismatch')
            gc_require(all((start[k] == e[k] for k in ['task', 'transaction', 'depth', 'thread'])), 'paired identity mismatch')
            gc_require(e['outcome'] == 'returned' and start['atNs'] <= e['atNs'], 'failed/reversed operation')
            gc_cpu(start, e)
            if operation == 'begin':
                gc_require(e['successRequested'] is False, 'begin exit flag')
                stack.append({'transaction': tx, 'begin': start, 'begun': e, 'success': False})
            elif operation == 'success':
                gc_require(e['successRequested'] is True, 'success exit flag')
                stack[-1]['success'] = True
            else:
                gc_require(e['successRequested'] == stack[-1]['success'], 'end exit flag')
                frame = stack.pop()
                closed.append({**frame, 'end': start, 'ended': e, 'depth': depth})
    gc_require(not stack and (not pending), 'unclosed transaction operations')
    return closed

def gc_author(events, t):
    gc_require(events, 'no author events')
    gc_monotonic_by_thread(events)
    token = t['authorOwnerToken']
    for e in events:
        gc_require(list(e) == gc_COLUMNS, 'column mapping changed')
        gc_require(e['ownerToken'] == token, 'non-author event')
        for k in ['ownerToken', 'task', 'transaction', 'depth']:
            gc_require(gc_integer(e[k]) and e[k] >= 0, 'bad identity field')
        gc_require(type(e['successRequested']) is bool, 'bad success flag')
        gc_require(e['stage'] in ['writer-submit', 'writer-run-enter', 'writer-run-exit'] or re.fullmatch('(room|sqlite)-(begin|success|end)-(enter|exit)', e['stage']), 'unexpected author stage')
    submit = gc_one(events, 'writer-submit')
    run = gc_one(events, 'writer-run-enter')
    exit_event = gc_one(events, 'writer-run-exit')
    gc_require(submit['outcome'] == run['outcome'] == 'entered' and exit_event['outcome'] == 'returned', 'writer outcome')
    gc_require(submit['task'] == run['task'] == exit_event['task'] > 0, 'writer task mismatch')
    for e in events:
        gc_require(e['task'] == run['task'], 'author task mismatch')
        if e['stage'] != 'writer-submit':
            gc_require(e['thread'] == run['thread'], 'worker thread mismatch')
        if e['stage'].startswith('writer-'):
            gc_require(e['depth'] == e['transaction'] == 0 and (not e['successRequested']), 'writer metadata')
    rooms = gc_validate_transactions(events, 'room')
    sqls = gc_validate_transactions(events, 'sqlite')
    room = [x for x in rooms if x['depth'] == 1]
    gc_require(len(room) == 1, 'outer Room count')
    room = room[0]
    receipt = t['beforeRenameReceiptNs']
    selected = [x for x in sqls if x['depth'] == 1 and x['begun']['atNs'] <= receipt <= x['ended']['atNs']]
    gc_require(len(selected) == 1, 'SQLite transaction spanning receipt is not unique')
    sql = selected[0]
    gc_require(room['success'] and sql['success'], 'outer transaction not successful')
    for layer, frame in [('room', room), ('sqlite', sql)]:
        for op in ['begin', 'success', 'end']:
            gc_one(events, f'{layer}-{op}-enter', depth=1, transaction=frame['transaction'])
            gc_one(events, f'{layer}-{op}-exit', depth=1, transaction=frame['transaction'])
    points = [{'atNs': t['saveStartNs']}, submit, run, sql['begin'], sql['begun'], room['begun'], {'atNs': receipt}, sql['end'], sql['ended'], room['ended'], {'atNs': t['afterRenameTransactionNs']}, {'atNs': t['saveEndNs']}]
    times = [p['atNs'] for p in points]
    gc_require(times == sorted(times), 'author segment order')
    gc_require(room['begin']['atNs'] <= sql['begin']['atNs'] and room['end']['atNs'] <= sql['end']['atNs'], 'Room/SQLite nesting')
    gc_require(exit_event['atNs'] >= room['ended']['atNs'], 'worker exit before Room end')
    pieces = []
    for name, a, b in zip(gc_SEGMENTS, points, points[1:]):
        ns = b['atNs'] - a['atNs']
        cn, why = gc_cpu(a, b)
        pieces.append({'name': name, 'startNs': a['atNs'], 'endNs': b['atNs'], 'elapsedNs': ns, 'wallMs': ns / 1000000.0, 'threadCpuNs': cn, 'cpuUnavailableReason': why})
    observed = t['authorBoundaries']
    gc_require(set(observed) == {'databaseTask', 'sqliteTransaction', 'roomTransaction', 'writerThread', 'segments', 'sqliteBeginThreadCpuNs', 'sqliteEndThreadCpuNs', 'pureSqliteLockWaitMs'}, 'author summary schema')
    for k in ['databaseTask', 'sqliteTransaction', 'roomTransaction', 'writerThread', 'sqliteBeginThreadCpuNs', 'sqliteEndThreadCpuNs']:
        gc_require(gc_integer(observed[k]), 'author summary integer')
    gc_require(all((set(s) == {'name', 'startNs', 'endNs', 'elapsedNs'} and all((gc_integer(s[k]) for k in ['startNs', 'endNs', 'elapsedNs'])) for s in observed['segments'])), 'author segment schema')
    gc_require(observed['databaseTask'] == run['task'] and observed['writerThread'] == run['thread'], 'reported author identity')
    gc_require(observed['sqliteTransaction'] == sql['transaction'] and observed['roomTransaction'] == room['transaction'], 'reported transaction identity')
    gc_require(observed['segments'] == [{k: x[k] for k in ['name', 'startNs', 'endNs', 'elapsedNs']} for x in pieces], 'reported segment mismatch')
    for label, a, b in [('sqliteBeginThreadCpuNs', sql['begin'], sql['begun']), ('sqliteEndThreadCpuNs', sql['end'], sql['ended'])]:
        gc_require(observed[label] == gc_cpu(a, b)[0], 'reported CPU mismatch')
    gc_require(observed['pureSqliteLockWaitMs'] is None, 'invented pure lock wait')
    total = t['saveEndNs'] - t['saveStartNs']
    gc_require(sum((x['elapsedNs'] for x in pieces)) == total, 'duration conservation')
    gc_require(gc_close(t['saveMs'], total / 1000000.0), 'reported saveMs mismatch')
    return {'segments': pieces, 'saveMs': total / 1000000.0, 'writerTask': run['task'], 'writerThread': run['thread'], 'roomTransaction': room['transaction'], 'sqliteTransaction': sql['transaction'], 'selectedSqliteBegin': sql['begin'], 'selectedSqliteEnd': sql['ended'], 'writerExitAfterRenameReturn': exit_event['atNs'] > t['afterRenameTransactionNs']}

def gc_trial(t, events):
    gc_require(set(t) == gc_TRIAL_KEYS, 'TRIAL fieldset')
    s = t['sample']
    gc_require(gc_integer(s) and 0 <= s < 30, 'sample range')
    gc_require(t['status'] == 'PASS' and t['stage'] == 'complete', 'nonPASS trial')
    for k in ['committed', 'contentVerified', 'workersExited', 'helpersClosed', 'fixtureDeleted']:
        gc_require(t[k] is True, k)
    gc_require(t['saveOutcome'] == 'COMMITTED', 'save outcome')
    for k in ['failureClass', 'collectorFailureClass', 'pureSqliteLockWaitMs']:
        gc_require(t[k] is None, k)
    gc_require(t['suppressedFailureClasses'] == [], 'suppressed failure')
    expected = {'scenario': 'AFTER_SCAN_ARRIVAL_BOUNDARIES', 'replacesFrozenProbe': False, 'fixtureSourceVersions': 1281, 'fixtureDetachedSources': 1280, 'fixtureExplicitRoots': 0, 'fixtureLatestRetained': 1, 'artificialGates': False, 'saveWatchdogMs': 5000, 'batchLimit': 256, 'boundaryCapacityPerDatabase': 512, 'batchCapacity': 64, 'phaseCapacity': 32, 'boundaryScope': 'AUTHOR_ONLY', 'gcOwnerToken': s * 3, 'authorOwnerToken': s * 3 + 1, 'finishOwnerToken': s * 3 + 2, 'batchDropped': 0, 'phaseDropped': 0}
    for k, v in expected.items():
        gc_require(t[k] == v and type(t[k]) is type(v), f'fixture/contract {k}')
    settings = {'page_size': 1024, 'auto_vacuum': 1, 'journal_mode': 'wal', 'synchronous': 2}
    gc_require(t['writerSqlite'] == t['authorWriterSqlite'] == settings, 'SQLite settings')
    gc_require(all((gc_integer(t[field][k]) for field in ['writerSqlite', 'authorWriterSqlite'] for k in ['page_size', 'auto_vacuum', 'synchronous'])), 'SQLite settings type')
    for k in ['initialRevision', 'savedRevision', 'finalRevision', 'firstDeleted', 'firstRootScans', 'firstCommittedBatches', 'finishDeleted', 'finishRootScans']:
        gc_require(gc_integer(t[k]) and t[k] >= 0, 'integer field ' + k)
    gc_require(t['initialRevision'] == 2 and t['savedRevision'] == t['finalRevision'] == 3, 'revision chain')
    gc_require(t['retainedRevisions'] == [1281], 'retention')
    clocks = ['collectorStartNs', 'collectorEndNs', 'saveStartNs', 'saveEndNs', 'beforeRenameReceiptNs', 'afterRenameTransactionNs', 'finishStartNs', 'finishEndNs']
    for k in clocks:
        gc_require(gc_integer(t[k]), 'missing/noninteger clock ' + k)
    gc_require(t['collectorStartNs'] <= t['collectorEndNs'] <= t['finishStartNs'] <= t['finishEndNs'], 'collector/finish order')
    gc_require(t['saveEndNs'] <= t['finishStartNs'], 'finish precedes author completion')
    a = gc_author(events, t)
    phases = t['phases']
    gc_require(len(phases) <= 32, 'phase capacity')
    gc_require(all((set(p) == {'phase', 'atNs'} and p['phase'] in ['after-roots', 'after-stats', 'after-scan', 'before-batch-commit', 'after-batch'] and gc_integer(p['atNs']) for p in phases)), 'phase schema')
    gc_require([p['phase'] for p in phases[:3]] == ['after-roots', 'after-stats', 'after-scan'] and sum((p['phase'] == 'after-stats' for p in phases)) == 1, 'scan phase chain')
    gc_require([p['atNs'] for p in phases] == sorted((p['atNs'] for p in phases)), 'phase order')
    gc_require(all((t['collectorStartNs'] <= p['atNs'] <= t['collectorEndNs'] for p in phases)), 'phase outside collection')
    gc_require(sum((p['phase'] == 'after-roots' for p in phases)) == t['firstRootScans'] == 1, 'first roots count')
    scans = [p['atNs'] for p in phases if p['phase'] == 'after-scan']
    gc_require(len(scans) == 1 and scans[0] <= t['saveStartNs'], 'natural after-scan arrival')
    gc_require(t['firstCommittedBatches'] == sum((p['phase'] == 'after-batch' for p in phases)), 'committed batch count')
    batches = t['batchEvents']
    gc_require(0 < len(batches) <= 64, 'batch capacity')
    gc_require(all((set(b) == gc_BATCH_KEYS for b in batches)), 'batch fieldset')
    for b in batches:
        for k in gc_BATCH_KEYS - {'phase'}:
            gc_require(gc_integer(b[k]), 'batch integer ' + k)
    gc_monotonic_by_thread(batches)
    gc_require([b['atNs'] for b in batches] == sorted((b['atNs'] for b in batches)), 'serial GC callback order')
    gc_require(all((t['collectorStartNs'] <= b['atNs'] <= t['collectorEndNs'] and gc_integer(b['callbackNs']) and (b['callbackNs'] >= 0) for b in batches)), 'batch timing')
    groups = defaultdict(list)
    for b in batches:
        gc_require(b['phase'] in ['guard-checked', 'before-delete', 'after-delete', 'transaction-returned'], 'batch stage')
        gc_require(gc_integer(b['afterSequence']) and b['afterSequence'] >= 0 and gc_integer(b['candidates']) and (0 <= b['candidates'] <= 256), 'batch identity')
        groups[b['afterSequence']].append(b)
    gc_require(list(groups) == sorted(groups), 'batch sequence order')
    intervals = []
    candidate_sum = 0
    delete_cpus = []
    for seq, bs in groups.items():
        phases_here = [b['phase'] for b in bs]
        gc_require(phases_here in [['guard-checked', 'transaction-returned'], ['guard-checked', 'before-delete', 'after-delete', 'transaction-returned']], 'batch closure')
        gc_require([b['atNs'] for b in bs] == sorted((b['atNs'] for b in bs)), 'batch stage order')
        fields = ['afterSequence', 'candidates', 'expectedDataVersion', 'expectedChanges', 'observedDataVersion', 'observedChanges']
        gc_require(all((all((b[k] == bs[0][k] for k in fields)) for b in bs)), 'batch values changed')
        if len(bs) == 4:
            g, begin, end, returned = bs
            gc_require(g['candidates'] == 256 and g['expectedDataVersion'] == g['observedDataVersion'] and (g['expectedChanges'] == g['observedChanges']), 'delete guard/full batch')
            candidate_sum += g['candidates']
            gc_require(begin['thread'] == end['thread'], 'DELETE window switched worker')
            delete_cpus.append({'afterSequence': seq, 'thread': begin['thread'], 'threadCpuNs': gc_cpu(begin, end)[0]})
            overlap = max(t['saveStartNs'], begin['atNs']) < min(t['saveEndNs'], end['atNs'])
            started = begin['atNs'] <= t['saveStartNs'] <= end['atNs']
            intervals.append({'afterSequence': seq, 'candidates': g['candidates'], 'deleteStartNs': begin['atNs'], 'deleteEndNs': end['atNs'], 'transactionReturnedNs': returned['atNs'], 'saveIntersected': overlap, 'saveStartedDuringDelete': started})
    gc_require(intervals == t['deleteIntervals'], 'reported DELETE intervals mismatch')
    gc_require(candidate_sum == t['firstDeleted'] == t['firstCommittedBatches'] * 256 and candidate_sum <= 1280, 'first deleted count')
    gc_require(list(groups) == list(range(0, candidate_sum + 1, 256)), 'missing/extra GC sequence group')
    last = groups[candidate_sum]
    gc_require(len(last) == 2, 'missing terminal empty/deferred guard')
    guard = last[0]
    matches = guard['expectedDataVersion'] == guard['observedDataVersion'] and guard['expectedChanges'] == guard['observedChanges']
    gc_require(guard['candidates'] == (0 if candidate_sum == 1280 else 256), 'terminal candidate count')
    gc_require(t['firstDeferred'] is (not matches), 'terminal guard/defer mismatch')
    gc_require(not matches or candidate_sum == 1280, 'matched nonempty terminal guard')
    precommit = [p['atNs'] for p in phases if p['phase'] == 'before-batch-commit']
    afterbatch = [p['atNs'] for p in phases if p['phase'] == 'after-batch']
    gc_require(len(precommit) == len(afterbatch) == len(intervals), 'missing commit/after-batch phase')
    for d, pc, ab in zip(intervals, precommit, afterbatch):
        gc_require(d['deleteEndNs'] <= pc <= d['transactionReturnedNs'] <= ab, 'DELETE/commit/after-batch order')
    gc_require(t['finishDeleted'] + candidate_sum == 1280 and t['finishDeferred'] is False and (t['finishRootScans'] == 1), 'finish count/state')
    gc_require(type(t['firstDeferred']) is bool, 'first deferred type')
    covered = [p['atNs'] for p in phases if p['phase'] == 'before-batch-commit' and t['saveStartNs'] <= p['atNs'] <= t['saveEndNs']]
    gc_require(covered == t['coveredBeforeBatchCommitNs'], 'precommit timestamps')
    exposure = {'deletionTransactionExposed': bool(covered), 'deleteWindowIntersected': any((x['saveIntersected'] for x in intervals)), 'saveStartedDuringDelete': any((x['saveStartedDuringDelete'] for x in intervals))}
    gc_require(all((type(t[k]) is bool and t[k] == v for k, v in exposure.items())), 'reported exposure mismatch')
    spans = [(x['deleteStartNs'], x['deleteEndNs']) for x in intervals]
    return {'sample': s, **a, **exposure, 'collectorWallMs': (t['collectorEndNs'] - t['collectorStartNs']) / 1000000.0, 'finishWallMs': (t['finishEndNs'] - t['finishStartNs']) / 1000000.0, 'cleanupWallMs': (t['collectorEndNs'] - t['collectorStartNs'] + t['finishEndNs'] - t['finishStartNs']) / 1000000.0, 'firstRootScans': t['firstRootScans'], 'finishRootScans': t['finishRootScans'], 'firstDeleted': t['firstDeleted'], 'finishDeleted': t['finishDeleted'], 'deleteUnionMs': gc_union(spans, t['collectorStartNs'], t['collectorEndNs']) / 1000000.0, 'deleteSaveUnionOverlapMs': gc_union(spans, t['saveStartNs'], t['saveEndNs']) / 1000000.0, 'deleteIntervals': intervals, 'deleteWindowCpu': delete_cpus, 'rawTrial': t, 'rawAuthorEvents': events, 'pureSqliteLockWaitMs': None}

def gc_distribution(samples, flag=None):
    selected = [x for x in samples if flag is None or x[flag]]
    vals = [x['saveMs'] for x in selected]
    return {**gc_stats(vals, flag is None or len(vals) >= 20), 'sampleIndices': [x['sample'] for x in selected], 'saveSamplesMs': vals}

def gc_summary(actual, samples, mode):
    expected = {'scenario': 'AFTER_SCAN_ARRIVAL_BOUNDARIES', 'replacesFrozenProbe': False, 'connection': mode, 'plannedTrials': 30, 'recordedTrials': 30, 'passedTrials': 30, 'committedSaves': 30, 'failedTrials': 0, 'unknownCommitOutcomes': 0, 'notStartedSaves': 0, 'functionalStatus': 'PASS', 'exposureStatus': 'SUFFICIENT_EXPOSURE' if sum((x['deletionTransactionExposed'] for x in samples)) >= 20 else 'INSUFFICIENT_EXPOSURE', 'minimumExposedSamplesForQuantiles': 20, 'latencyAcceptance': 'NOT_EVALUATED', 'saveWatchdogMs': 5000, 'watchdogIsLatencyAcceptance': False, 'quantileDefinition': 'nearest rank: ceil(p*n)-1, zero based; n=30 p50=14 p95=28', 'pureSqliteLockWaitMs': None}
    gc_require(set(actual) == set(expected) | {'allSaves', 'exposedSaves', 'deleteWindowIntersectedSaves', 'saveStartedDuringDeleteSaves'}, 'SUMMARY fieldset')
    for k, v in expected.items():
        gc_require(actual[k] == v and type(actual[k]) is type(v), 'summary ' + k)
    distributions = {}
    for key, flag in [('allSaves', None), ('exposedSaves', 'deletionTransactionExposed'), ('deleteWindowIntersectedSaves', 'deleteWindowIntersected'), ('saveStartedDuringDeleteSaves', 'saveStartedDuringDelete')]:
        d = gc_distribution(samples, flag)
        got = actual[key]
        gc_require(set(got) == set(d), 'distribution schema')
        for k, v in d.items():
            if isinstance(v, list):
                gc_require(isinstance(got[k], list) and all((gc_integer(z) if k == 'sampleIndices' else gc_num(z) for z in got[k])) and (got[k] == v), 'distribution raw values/order')
            elif v is None:
                gc_require(got[k] is None, 'low exposure quantile fabricated')
            elif k == 'count':
                gc_require(gc_integer(got[k]) and got[k] == v, 'distribution count')
            else:
                gc_require(gc_close(got[k], v), 'distribution quantile')
        distributions[key] = d
    return distributions

def validate_gc_boundary(payloads, mode):
    # Closed per-method wrapper over independently reviewed pure GC arithmetic.
    def bounded(value):
        if type(value) is int:assert -9223372036854775808<=value<=9223372036854775807
        elif type(value) is float:assert math.isfinite(value)
        elif type(value) is str:assert len(value)<=256
        elif type(value) is list:
            assert len(value)<=512
            for child in value:bounded(child)
        elif type(value) is dict:
            for child in value.values():bounded(child)
        else:assert value is None or type(value) is bool
    for p in payloads:
        bounded(p['data']);assert p['data']['connection']==mode
    assert mode in gc_MODES and payloads[-1]['marker']==gc_MARKERS[2]
    summaries=[p['data'] for p in payloads if p['marker']==gc_MARKERS[2]];assert len(summaries)==1
    trials=[p['data'] for p in payloads if p['marker']==gc_MARKERS[1]]
    assert [t['sample'] for t in trials]==list(range(30))
    shards=defaultdict(list);current_sample=0
    for p in payloads[:-1]:
        d=p['data'];assert type(d['sample']) is int and d['sample']==current_sample
        if p['marker']==gc_MARKERS[1]:current_sample+=1
        else:
            assert p['marker']==gc_MARKERS[0]
            assert set(d)=={'sample','connection','database','part','parts','eventSchema','eventColumns','events'}
            assert d['eventSchema']=='author-boundary-v1' and d['eventColumns']==gc_COLUMNS
            assert d['database']==('collector' if mode=='same-room' else 'author')
            assert type(d['part']) is int and type(d['parts']) is int and 1<=d['parts']<=11
            assert type(d['events']) is list and 1<=len(d['events'])<=48
            assert all(type(e) is list and len(e)==10 for e in d['events'])
            shards[current_sample].append(d)
    samples=[]
    for t in trials:
        ps=shards[t['sample']];assert ps
        assert [x['part'] for x in ps]==list(range(len(ps))) and all(x['parts']==len(ps) for x in ps)
        assert all(len(x['events'])==48 for x in ps[:-1])
        es=[dict(zip(gc_COLUMNS,e)) for p in ps for e in p['events']];assert len(es)<=512
        dbs=['collector'] if mode=='same-room' else ['collector','author']
        expected=[{'database':db,'count':len(es) if db==ps[0]['database'] else 0,'dropped':0,'invalid':False} for db in dbs]
        assert json.dumps(t['boundaries'],sort_keys=True)==json.dumps(expected,sort_keys=True), 'Boundary counts/types'
        assert all(type(value) is int for value in t['retainedRevisions'])
        assert all(type(value) is int for value in t['coveredBeforeBatchCommitNs'])
        for interval in t['deleteIntervals']:
            integers=['afterSequence','candidates','deleteStartNs','deleteEndNs','transactionReturnedNs']
            booleans=['saveIntersected','saveStartedDuringDelete']
            assert set(interval)==set(integers+booleans)
            assert all(type(interval[k]) is int for k in integers)
            assert all(type(interval[k]) is bool for k in booleans)
        samples.append(gc_trial(t,es))
    gc_summary(summaries[0],samples,mode)
