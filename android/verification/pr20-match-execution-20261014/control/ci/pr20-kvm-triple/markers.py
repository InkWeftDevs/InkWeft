"""Pure fixed public-evidence validation; no device/network calls."""
import json,math,re
from collections import Counter

def validate_shape(value, schema):
    if 'oneOf' in schema:
        matches=0
        for branch in schema['oneOf']:
            try:validate_shape(value,branch);matches+=1
            except AssertionError:pass
        assert matches==1, 'Expected one closed schema variant'
        return
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
    assert (rule['selection'],rule['identity'])==(entry['selection'],entry['identity'])
    def pairs(items):
        result={}
        for key,value in items:
            assert key not in result, 'Duplicate JSON marker key'
            result[key]=value
        return result
    def constant(value):raise AssertionError('Non-finite JSON constant')
    payloads=[]
    for line in raw.splitlines():
        match=re.search(r'\b((?:KVM|LIBRARY|HISTORY|TILE|INDEX|RASTER|STREAMED|NATIVE)_[A-Z0-9_]+)(.*)$',line)
        if not match:continue
        marker,tail=match.groups()
        assert marker in rule['markers'] and tail.startswith(' '), 'Unknown or malformed marker'
        original=tail[1:]
        assert len(original.encode())<=rule['markers'][marker]['payload_max_bytes'], 'Marker byte limit'
        data=json.loads(original,object_pairs_hook=pairs,parse_constant=constant)
        schema=contract.get('stage_schemas',{}).get(marker,contract['schemas'][marker]) if rule['kind']=='stage' else contract['schemas'][marker]
        validate_shape(data,schema)
        payloads.append({'marker':marker,'original_json':original,'data':data})
    counts=Counter(p['marker']for p in payloads)
    for marker,bound in rule['markers'].items():
        assert bound['min_count']<=counts[marker]<=bound['max_count'], 'Missing or extra marker'
    assert sum(len(p['original_json'].encode())for p in payloads)<=rule['method_payload_max_bytes'], 'Method publication byte limit'
    def values(marker):return [p['data']for p in payloads if p['marker']==marker]
    def one(marker):
        result=values(marker);assert len(result)==1
        return result[0]
    kind=rule['kind']
    if kind=='none':assert not payloads
    elif kind in ['library','stage']:
        final=one('LIBRARY_READ_EVIDENCE');checkpoint=one('LIBRARY_DIRECT_SAVE_CHECKPOINT');idx=one('LIBRARY_INDEX_SAVE_CHECKPOINT');observed=final['observeIndexConcurrentSaves']
        assert final['saveCount']==checkpoint['saveCount']==idx['saveCount']==observed['saveCount']==30
        assert final['saveSamplesMs']==checkpoint['saveSamplesMs'] and observed['saveSamplesMs']==idx['saveSamplesMs']
        assert [p['rows']for p in values('INDEX_SAVE_PROGRESS')]==[64,4096,8192,12288,16384,20000]
        saves=idx['saveProgressSamples'];assert [p['sample']for p in saves]==list(range(30))
        if kind=='library':assert all(p['committed'] is True and p['startNs']<=p['endNs']for p in saves)
        assert [p['elapsedMs']for p in saves]==observed['saveSamplesMs']
        assert final['writerSqlite']==contract['library_checks']['writerSqlite']
        if kind=='library':
            fixture=one('KVM_PAIR_FIXTURE')
            assert fixture['seed']==entry['seed'] and fixture['rowCounts']==contract['library_checks']['fixture_counts']
            assert fixture['writerSqlite']==final['writerSqlite'] and fixture['label']=='library-before'
            direct=one('KVM_DIRECT_INTERVALS')['saves'];assert [x['sample']for x in direct]==list(range(30))
            assert all(x['committed'] is True and x['startNs']<=x['endNs']for x in direct)
            assert [x['elapsedMs']for x in direct]==final['saveSamplesMs']
            batches=one('KVM_INDEX_INTERVALS')['batches']
            assert [x['rows']for x in batches]==list(range(64,20000,64))+[20000]
            assert all(a['committedNs']<=b['committedNs']for a,b in zip(batches,batches[1:]))
            assert 'stageDiagnosticsEnabled' not in final
        else:
            parts=values('LIBRARY_STAGE_DIAGNOSTICS');assert [p['part']for p in parts]==list(range(len(parts)))
            assert len({p['originNs']for p in parts})==1 and final['stageDiagnosticsEnabled'] is True
            saved=[v for p in parts for v in p['saves']]
            for phase in ['direct','observe']:
                selected=[v for v in saved if v['phase']==phase]
                assert [x['sample']for x in selected]==list(range(30))
                assert all(x['committed'] is True and x['startNs']<=x['endNs']for x in selected)
    elif kind=='raster':
        assert [p['marker']for p in payloads]==['KVM_PAIR_FIXTURE','TILE_LOCAL_EVIDENCE','KVM_RASTER_CLEANUP']*3
        for index,layer in enumerate([1,4,8]):
            fixture,timing,cleanup=[p['data']for p in payloads[index*3:index*3+3]]
            assert fixture['seed']==entry['seed'] and fixture['label']==f'raster-{layer}-before'
            assert timing['layers']==cleanup['layers']==layer and timing['pixelsEqual'] is True and cleanup['joined'] is True
    elif kind=='history':
        trials=values('HISTORY_DELETION_EXPOSURE_TRIAL');summary=one('HISTORY_DELETION_EXPOSURE_SUMMARY')
        assert [t['sample']for t in trials]==list(range(30)) and payloads[-1]['marker']=='HISTORY_DELETION_EXPOSURE_SUMMARY'
        assert all(t['connection']==rule['connection'] and t['status']=='PASS' and t['committed'] is True and t['contentVerified'] is True for t in trials)
        assert all(t['saveWatchdogMs']==5000 and t['artificialGates'] is False and t['saveStartNs']<=t['saveEndNs']for t in trials)
        assert summary['connection']==rule['connection'] and summary['functionalStatus']=='PASS'
        assert summary['recordedTrials']==summary['passedTrials']==summary['committedSaves']==30
        assert summary['minimumExposedSamplesForQuantiles']==20 and summary['latencyAcceptance']=='NOT_EVALUATED'
        assert summary['allSaves']['sampleIndices']==list(range(30)) and summary['allSaves']['saveSamplesMs']==[t['saveMs']for t in trials]
        exposed=[t for t in trials if t['deletionTransactionExposed']]
        assert summary['exposedSaves']['count']==len(exposed)
        assert summary['exposedSaves']['sampleIndices']==[t['sample']for t in exposed]
        assert summary['exposedSaves']['saveSamplesMs']==[t['saveMs']for t in exposed]
        assert summary['exposureStatus']==('SUFFICIENT_EXPOSURE'if len(exposed)>=20 else'INSUFFICIENT_EXPOSURE')
        if len(exposed)<20:assert summary['exposedSaves']['p50Ms'] is summary['exposedSaves']['p95Ms'] is None
    elif kind=='native':
        phases=values('NATIVE_STROKE_TILE_EVIDENCE')
        assert [p['phase']for p in phases]==[0,1,2,3]
        assert [p['nativeStrokeBuilds']for p in phases]==[2,2,1,0]
        assert all(p['pixelsEqual'] is True and p['tiles']>1 and 0<=p['rebuilt']<=p['tiles']for p in phases)
        assert all(p['perTileReferenceBuilds']>p['nativeStrokeBuilds']for p in phases[:3])
        assert phases[3]['perTileReferenceBuilds']==0
    elif kind=='frame':
        frames=values('RASTER_PAIRED_EVIDENCE')
        assert [p['case']for p in frames]==['initial']+[f'transform-{a}-{b}'for a in range(3)for b in range(4)]+['separated-append']
        assert all(p['pixelsEqual'] is True for p in frames)
        assert payloads[-1]['marker']=='RASTER_SQLITE_EVIDENCE'
    elif kind=='dense':
        assert [p['data']['stage']for p in payloads]==['fixture-start','fixture-complete','editor-window-complete','frame-start','frame-complete','verified-and-released']
        complete=next(p['data']for p in payloads if p['data']['stage']=='frame-complete')
        assert complete['snapshotRows']==1201 and sum(x['rows']for x in complete['snapshotReads'])==1201
        assert all(1<=x['rows']<=256 for x in complete['snapshotReads'])
    elif kind=='unicode':assert len(payloads)==1 and payloads[0]['marker']=='LIBRARY_CAUSAL_UNICODE'
    else:assert kind=='guard'
    return payloads
