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
    elif rule['kind']=='save-boundary':validate_save_boundary(payloads)
    else:assert entry['marker_policy']=='none', 'Unknown diagnostic kind'
    return payloads


def validate_save_boundary(payloads):
    modes=['IDLE','DIRECT','OBSERVE']
    results=[p['data']for p in payloads if p['marker']=='LIBRARY_SAVE_BOUNDARY_RESULT']
    ends=[p['data']for p in payloads if p['marker']=='LIBRARY_SAVE_BOUNDARY_END']
    assert [r['mode']for r in results]==modes and [r['mode']for r in ends]==modes
    assert len({r['fixture']['logicalSha256']for r in results})==1, 'Initial synthetic fixtures differ'
    assert all(r['fixture']==results[0]['fixture']for r in results)
    expected=[]
    for result,end in zip(results,ends):
        mode=result['mode']
        assert result['startRevision']==(32 if mode=='OBSERVE' else 1)
        assert result['readiness']=={'IDLE':'fixture-ready','DIRECT':'first-source-check','OBSERVE':'first-index-commit'}[mode]
        events=[];reads=[]
        expected.append(('LIBRARY_SAVE_BOUNDARY_RESULT',mode))
        for marker,target,count,capacity in [('LIBRARY_SAVE_BOUNDARY_EVENTS',events,end['recordedEvents'],4096),('LIBRARY_SAVE_BOUNDARY_READS',reads,end['recordedReadEvents'],2048)]:
            parts=[p['data']for p in payloads if p['marker']==marker and p['data']['mode']==mode]
            assert [p['part']for p in parts]==list(range(len(parts))), 'Missing/duplicate/out-of-order part'
            assert all(len(p['events'])==96 for p in parts[:-1]), 'Short non-final part'
            target.extend(e for p in parts for e in p['events'])
            assert len(target)==count<=capacity, 'END count must account for every event'
            expected.extend((marker,mode)for _ in parts)
        expected.append(('LIBRARY_SAVE_BOUNDARY_END',mode))
        assert bool(reads)==(mode!='IDLE'), 'Expected read/index evidence only for DIRECT/OBSERVE'
        if mode=='DIRECT':
            control={stage:[event for event in reads if event['owner']=='control'and event['stage']==stage]for stage in ['direct-start','direct-end']}
            assert all(len(values)==1 for values in control.values()), 'DIRECT requires original start and awaited end'
            assert control['direct-start'][0]['wallNs']<=control['direct-end'][0]['wallNs']
        elif mode=='OBSERVE':
            assert any(event['owner']=='index'and event['stage']=='committed'and event['count']>0 for event in reads), 'OBSERVE starts after an original index commit'
        assert [s['sample']for s in result['saves']]==list(range(30))
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
        for save in result['saves']:
            sample=save['sample'];start=save['startNs'];stop=save['endNs']
            assert previous_end<=start<=stop and abs(save['elapsedMs']-(stop-start)/1e6)<1e-6
            previous_end=stop
            assert save['revision']==result['startRevision']+sample+1
            assert save['sourceChecksStart']<=save['sourceChecksEnd'] and save['indexRowsStart']<=save['indexRowsEnd']
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
        # Original raw strings remain untouched. This checks full marker ordering, not re-sorting output.
    assert [(p['marker'],p['data']['mode'])for p in payloads]==expected
