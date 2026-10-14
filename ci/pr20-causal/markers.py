"""Pure bounded JSON validation. Never print unvalidated diagnostic payloads."""
import hashlib
import json
import math
import re
from collections import Counter


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
        validate_shape(data,contract['schemas'][marker])
        payloads.append({'marker':marker,'original_json':original,'data':data})
    counts=Counter(p['marker'] for p in payloads)
    for marker,bound in rule['markers'].items():
        assert bound['min_count']<=counts[marker]<=bound['max_count'], 'Marker count'
    assert sum(len(p['original_json'].encode()) for p in payloads)<=rule['method_payload_max_bytes'], 'Method byte limit'
    if entry['marker_policy']=='none':assert not payloads and not rule['markers']
    elif rule['kind']=='save':validate_save(payloads)
    elif rule['kind']=='history':validate_history(payloads,rule['connection'])
    elif rule['kind']=='raster':validate_raster(payloads)
    elif rule['kind']=='warm':validate_warm(payloads)
    elif rule['kind']=='unicode':
        assert len(payloads)==1 and payloads[0]['marker']=='LIBRARY_CAUSAL_UNICODE'
    else:assert entry['marker_policy']=='none', 'Unknown diagnostic kind'
    return payloads


def validate_save(payloads):
    modes=['IDLE','TRACKER','INDEX_ONLY','INDEX_TRACKER','READS_ONLY','FULL']
    expected=[(b,m)for b in range(2)for m in (modes if b==0 else modes[::-1])]
    results=[p['data']for p in payloads if p['marker']=='LIBRARY_SAVE_CAUSAL_RESULT']
    ends=[p['data']for p in payloads if p['marker']=='LIBRARY_SAVE_CAUSAL_END']
    assert [(r['block'],r['mode'])for r in results]==expected
    assert [(r['block'],r['mode'])for r in ends]==expected
    for result,end in zip(results,ends):
        b,m=result['block'],result['mode']
        assert result['seed']==4101+b and len(result['saves'])==30
        assert end['completed'] is True and end['savesStarted'] is True and end['committedSaves']==30
        assert end['lastAttemptedSample']==29 and end['failureOutcome']=='none' and end['droppedEvents']==0
        samples=result['saves'];assert [s['sample']for s in samples]==list(range(30))
        assert all(s['committed'] is True and s['startNs']<=s['endNs'] for s in samples)
        assert all(abs(s['elapsedMs']-(s['endNs']-s['startNs'])/1e6)<1e-6 for s in samples)
        parts=[p['data']for p in payloads if p['marker']=='LIBRARY_SAVE_CAUSAL_STAGES' and p['data']['block']==b and p['data']['mode']==m]
        assert [p['part']for p in parts]==list(range(len(parts)))
        assert sum(len(p['events'])for p in parts)==end['recordedEvents']<=2048
        assert all(len(p['events'])==128 for p in parts[:-1])
        ordered=[p['marker'] for p in payloads if p['data']['block']==b and p['data']['mode']==m]
        assert ordered==['LIBRARY_SAVE_CAUSAL_RESULT']+['LIBRARY_SAVE_CAUSAL_STAGES']*len(parts)+['LIBRARY_SAVE_CAUSAL_END']


def validate_history(payloads,connection):
    trials=[p['data']for p in payloads if p['marker']=='HISTORY_GC_CAUSAL_TRIAL']
    final=[p['data']for p in payloads if p['marker']=='HISTORY_GC_CAUSAL_SUMMARY']
    assert len(trials)==30 and len(final)==1 and payloads[-1]['marker']=='HISTORY_GC_CAUSAL_SUMMARY'
    assert [t['sample']for t in trials]==list(range(30))
    for trial in trials:
        assert trial['connection']==connection and trial['status']=='PASS' and trial['committed'] is True
        assert trial['saveOutcome']=='COMMITTED' and trial['contentVerified'] is True
        assert trial['authorReadyNs']<=trial['notificationNs']<=trial['saveStartNs']<=trial['saveEndNs']
        assert trial['saveWatchdogMs']==5000 and trial['scenario']=='CONDITIONAL_DELETE_ARRIVAL'
        assert trial['replacesNaturalAcceptance'] is False and trial['artificialGates'] is False
    final=final[0]
    assert final['connection']==connection and final['functionalStatus']=='PASS'
    assert final['recordedTrials']==final['passedTrials']==final['committedSaves']==30
    assert final['failedTrials']==final['unknownCommitOutcomes']==final['notStartedSaves']==0
    assert final['latencyAcceptance']=='NOT_EVALUATED' and final['replacesNaturalAcceptance'] is False
    assert final['allSaves']['sampleIndices']==list(range(30))
    assert final['allSaves']['saveSamplesMs']==[t['saveMs']for t in trials]
    exposed=[t for t in trials if t['deletionTransactionExposed']]
    assert final['exposedSaves']['sampleIndices']==[t['sample']for t in exposed]
    assert final['exposedSaves']['saveSamplesMs']==[t['saveMs']for t in exposed]
    for d,minimum in [(final['allSaves'],30),(final['exposedSaves'],20)]:
        samples=sorted(d['saveSamplesMs']);n=len(samples);assert d['count']==n
        assert d['maxMs']==(samples[-1] if n else None)
        assert d['p50Ms']==(samples[(n+1)//2-1] if n>=minimum else None)
        assert d['p95Ms']==(samples[(95*n+99)//100-1] if n>=minimum else None)


def validate_raster(payloads):
    assert len(payloads)==3
    assert [p['data']['result']['layers']for p in payloads]==[1,4,8]
    for payload in payloads:
        data=payload['data'];n=data['result']['layers']
        assert data['scope']=='DIAGNOSTIC_ONLY' and data['seed']==4101 and data['joined'] is True
        assert data['result']['pixelsEqual'] is True and data['result']['initialStrokes']==320 and data['result']['finalStrokes']==321
        assert len(data['frames'])==5*n
        for frame in data['frames']:
            assert 'WORKER_FINALLY' in frame['timesNanos'] and 'READY_DRAW_END' in frame['timesNanos']
            assert 'FAILED' not in frame['timesNanos'] and set(frame['timesNanos'])==set(frame['threadIds'])


def validate_warm(payloads):
    assert len(payloads)==1 and payloads[0]['marker']=='LIBRARY_CAUSAL_WARM'
    # Final field-level relations are frozen with the owner's schema before binding.
