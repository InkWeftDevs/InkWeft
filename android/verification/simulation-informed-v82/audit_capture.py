"""Read sealed synthetic capture evidence; never execute bundled drivers.

Usage: python3 audit_capture.py /path/to/extracted/Simulation-V80
Produces aggregate JSON. No device, original archive or user DB is changed.
"""
import collections
import hashlib
import json
import math
import statistics
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(sys.argv[1]).resolve()
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def local(path):
    value=path.replace('\\','/')
    if '/Simulation-V80/' in value:value=value.split('/Simulation-V80/',1)[1]
    p=(root/value).resolve();assert p.is_relative_to(root);return p

def read(path):return json.loads(local(path).read_text(encoding='utf-8-sig'))

coverage=read('reports/CASE-COVERAGE.json')
statuses=collections.Counter(c['status'] for c in coverage['cases'])
assert dict(statuses)=={'CAPTURED':4,'PARTIAL':11,'FAIL':7,'PASS':12}
performance=read('runs/LOAD-07/performance-summary.json')
formal=[a for a in performance['attempts'] if a['is_formal_attempt']]
assert len(formal)==225
outcomes=collections.Counter();scenes=[];verified=0;primary_sources={}
for a in formal:
    p=local(a['report']);assert digest(p)==a['report_sha256'];report=json.loads(p.read_text())
    assert report['status']==a['report_status']
    assert a['status']==a['report_status']
    outcomes[a['outcome_category']]+=1;verified+=1
    primary_sources[(a['scene'],a['repeat'])]=report
for scene in performance['scenes']:
    attempts=[a for a in formal if a['scene']==scene['scene']]
    assert len(attempts)==scene['execution_count']==scene['target_attempts']
    successes=[a for a in attempts if a['status']=='PASS']
    assert len(successes)==scene['success_count']
    metric=scene['primary_metric'];actual=[]
    for a in successes:
        raw=primary_sources[(a['scene'],a['repeat'])]
        if metric.startswith('step.'):
            _,step,field=metric.split('.',2)
            found=next(x for x in raw['steps'] if x['stepId']==step)
            value=found.get('actual',{}).get(field,found.get(field))
            assert value==a['metrics'][metric]
        else:
            value=raw[metric] # Library proxy kept separate from screenshot boundary.
            assert value==a['metrics'][metric]
        assert isinstance(value,(int,float)) and math.isfinite(value);actual.append(value)
    stats=scene['metrics'].get(metric)
    if actual:
        assert stats['n']==len(actual)
        assert sorted(stats['raw'])==sorted(actual)
        assert math.isclose(statistics.median(actual),stats['median'],abs_tol=1e-8)
    else:assert stats is None or stats['n']==0
    scenes.append({'case':scene['case_id'],'scene':scene['scene'],'attempts':len(attempts),'pass':len(successes),'fail':len(attempts)-len(successes),'primary':metric,'success_n':len(actual),'success_median_ms':statistics.median(actual) if actual else None})
assert sum(s['pass'] for s in scenes)==191 and sum(s['fail'] for s in scenes)==34

java_path=local('runs/star-v80-output01/events.jsonl');native_path=local('runs/star-v80-native01/events.jsonl')
java=[];calls={};returns={};configs={};call_configs={};array_values=0;nonempty_output=0
segment_counts=collections.Counter();prediction_nonempty=collections.Counter();getter_matches=collections.Counter()
def arrays(value):
    if isinstance(value,dict):
        typ=value.get('type')
        if isinstance(typ,str) and typ in {'[F','[I','[D'} and 'length' in value:yield value
        for k,v in value.items():
            if k!='samples':yield from arrays(v)
    elif isinstance(value,list):
        for v in value:yield from arrays(v)
for number,line in enumerate(java_path.open()):
    e=json.loads(line);e['_line']=number+1;java.append(e);kind=e['kind'];key=(e.get('pid'),e.get('callId'))
    if kind=='java-call':
        assert key not in calls;calls[key]=e
        if e['method'].startswith('nativeOnPen'):call_configs[key]=configs.get(str(e['args'][0]))
    elif kind=='java-return':
        assert key not in returns and key in calls;returns[key]=e;entry=calls[key]
        assert e['method']==entry['method'] and e['threadId']==entry['threadId'] and e['timeMs']>=entry['timeMs']
        cfg=next((a for a in entry['args'] if isinstance(a,dict) and a.get('type','').endswith('.PenConfig')),None)
        if cfg and e.get('returnType')=='long':configs[str(e['result'])]={k:v['value'] for k,v in cfg['fields'].items()}
        obj=e.get('result');cfg=call_configs.get(key) or {};pen_type=cfg.get('type','UNKNOWN')
        if isinstance(obj,dict) and obj.get('type','').endswith('.PenInkResult'):
            for field in ['a','b']:
                ink=obj['fields'].get(field)
                if not isinstance(ink,dict):continue
                f=ink['fields'].get('a');ints=ink['fields'].get('b')
                if isinstance(f,dict) and isinstance(ints,dict):
                    values=f['samples'];lengths=ints['samples']
                    assert all(isinstance(x,int) and x>=0 for x in lengths)
                    assert sum(lengths)==len(values)
                    segment_counts.update((str(pen_type),field,n) for n in lengths)
                    if values:prediction_nonempty[(str(pen_type),field)]+=1
    if kind in {'java-call','java-return'}:
        for a in arrays(e.get('args') if kind=='java-call' else e.get('result')):
            assert a['capturedLength']==a['length']==len(a['samples']) and not a['truncated']
            assert all(isinstance(x,(int,float)) and math.isfinite(x) for x in a['samples'])
            array_values+=a['length']
            if kind=='java-return' and a['length']>0:nonempty_output+=1
    if kind=='alg06-consumer-read' and e['method'] in {'getRealInk','getPredictionInk'}:
        owner=e.get('ownerCorrelation') or {};ret=returns.get((e['pid'],owner.get('callId')))
        if ret and isinstance(ret.get('result'),dict):
            obj=ret['result'];slot='a' if e['method']=='getRealInk' else 'b';f=obj.get('fields',{}).get(slot)
            if isinstance(f,dict) and obj['identityHashCode']==owner.get('identityHashCode') and f['identityHashCode']==e.get('returnedIdentityHashCode'):getter_matches[e['method']]+=1
    if kind=='alg06-consumer-read' and e['method'] in {'getPoints','getPointSizeArray'}:
        assert e['invokedByObserver'] is False
        owner=e.get('ownerCorrelation') or {};ret=returns.get((e['pid'],owner.get('callId')))
        if ret and isinstance(ret.get('result'),dict) and owner.get('role') in {'result.a','result.b'}:
            ink=ret['result']['fields'].get(owner['role'][-1])
            field='a' if e['method']=='getPoints' else 'b'
            arr=ink.get('fields',{}).get(field) if isinstance(ink,dict) else None
            if isinstance(arr,dict) and ink['identityHashCode']==owner.get('identityHashCode') and arr['identityHashCode']==e.get('returnedIdentityHashCode'):
                assert arr['length']==e['returnedLength'];getter_matches[e['method']]+=1
assert calls.keys()==returns.keys() and len(calls)==1982
assert nonempty_output==6508 and array_values==543399
native_calls={};native_returns={}
for line in native_path.open():
    e=json.loads(line);key=(e.get('pid'),e.get('callId'))
    if e['kind']=='arm64-call':assert key not in native_calls;native_calls[key]=e
    elif e['kind']=='arm64-return':assert key not in native_returns;native_returns[key]=e
assert native_calls.keys()==native_returns.keys() and len(native_calls)==1816
pen_calls=[(k,e) for k,e in calls.items() if e['method'].startswith('nativeOnPen')];assert len(pen_calls)==1816
correlated=0;matched_ids=set()
for key,n in native_calls.items():
    leave=native_returns[key];method=n['function'].split('_')[-1];matches=[]
    for jk,j in pen_calls:
        r=returns[jk]
        if j['pid']!=n['pid'] or j['threadId']!=n['threadId'] or j['method']!=method or not j['timeMs']<=n['timeMs']<=leave['timeMs']<=r['timeMs']:continue
        if int(n['rawArguments'][2],16)!=int(j['args'][0]):continue
        compatible=True
        for index,typ in enumerate(j['argumentTypes']):
            if typ=='boolean':compatible &= (int(n['rawArguments'][index+2],16)&255)==int(j['args'][index])
            elif typ.startswith('['):compatible &= (int(n['rawArguments'][index+2],16)!=0)==(j['args'][index] is not None)
        if compatible:matches.append(jk)
    assert len(matches)==1,(key,len(matches));assert matches[0] not in matched_ids
    matched_ids.add(matches[0]);correlated+=1

batch=read('reports/ALG-01-05-batch-analysis.json');line_case=next(c for c in batch['cases'] if c['caseId']=='ALG-01/line__dense_uniform')
extents=[]
for key,enter in pen_calls:
    if enter['method']!='nativeOnPenMove' or enter['args'][-1] is not False or not line_case['inputFirstLine']<=enter['_line']<=line_case['inputLastLine']:continue
    values=enter['args'][1]['samples'];assert len(values)==7
    cfg=call_configs.get(key) or {};assert cfg['type']==6
    fields=returns[key]['result']['fields'];record={}
    for field in ['a','b']:
        arr=fields[field]['fields']['a']['samples']
        if arr:assert len(arr)%2==0;record[field]=max(arr[::2])-values[0]
    extents.append(record)
regression=[]
for method in read('runs/REG-01/method-results.json'):
    p=local(method['raw_receipt']);assert digest(p)==method['raw_sha256']
    text=p.read_text();class_name,test_name=method['exact_method'].split('#')
    assert method['status']=='PASS' and method['adb_exit_code']==0
    assert 'INSTRUMENTATION_STATUS: class='+class_name in text
    assert 'INSTRUMENTATION_STATUS: test='+test_name in text
    assert 'INSTRUMENTATION_STATUS_CODE: 0' in text and 'OK (1 test)' in text
    assert 'INSTRUMENTATION_CODE: -1' in text
    xml=next(local('runs/REG-01/junit').glob('TEST-*'+test_name+'.xml'))
    suite=ET.parse(xml).getroot();case=suite.find('testcase')
    assert suite.attrib['tests']=='1' and suite.attrib['failures']=='0' and suite.attrib['errors']=='0'
    assert case.attrib['classname']==class_name and case.attrib['name']==test_name
    assert any(x.attrib.get('name')=='source_commit' and x.attrib.get('value')=='a8177df0aaa6c0fbd684af0965bd8d7c78ca71a9' for x in suite.findall('./properties/property'))
    regression.append({'method':method['exact_method'],'status':'VERIFIED_UPLOADED_V80_EMULATOR_PASS','raw_sha256':digest(p),'junit_sha256':digest(xml)})
assert len(regression)==12
result={'scope':'OFFLINE_AUDIT_OF_UPLOADED_V80_SYNTHETIC_CAPTURE_NOT_NEW_DEVICE_RUN','source_commit':'a8177df0aaa6c0fbd684af0965bd8d7c78ca71a9','case_status_counts':dict(statuses),
'regression':{'scope':'UPLOADED_V80_API35_X86_64_EMULATOR_NOT_ARM64_PHYSICAL_NOT_V82','verified_pass':len(regression),'methods':regression},
'performance':{'raw_report_hashes_verified':verified,'formal_attempts':225,'complete_pass':191,'complete_fail':34,'outcomes':dict(outcomes),'scenes':scenes,'primary_scope':'All 28 scene primary values independently read from raw reports. Library proxies remain separate from screenshot boundaries. Failures retained in denominator.'},
'native':{'java_log_sha256':digest(java_path),'arm_log_sha256':digest(native_path),'java_enter_return_pairs':len(calls),'pen_pairs':len(pen_calls),'arm_pairs_uniquely_correlated':correlated,'nonempty_return_arrays':nonempty_output,'captured_numeric_values':array_values,'truncated_or_length_mismatch':0,'getter_field_identity_matches':dict(getter_matches),'getter_limit':'identityHashCode association only; collisions not excluded; getter observations capped at 4000.','segment_counts':[{'pen_type':t,'field':f,'segment_float_count':n,'segments':v} for (t,f,n),v in sorted(segment_counts.items())],'nonempty_outputs':[{'pen_type':t,'field':f,'calls':v} for (t,f),v in sorted(prediction_nonempty.items())],
'formal_dense_horizontal_line':{'move_calls':len(extents),'nonempty_a':sum('a' in r for r in extents),'nonempty_b':sum('b' in r for r in extents),'field_a_x_extent_minus_input_x_median_local_units':statistics.median(r['a'] for r in extents if 'a' in r),'field_b_x_extent_minus_input_x_median_local_units':statistics.median(r['b'] for r in extents if 'b' in r),'interpretation':'Array x-extent difference, including cap/control coordinates. Not centerline lag, filter reconstruction, latency or rendered pixels.'}},
'limits':['Pencil/type4 quintuple trailing semantics UNKNOWN; no formula invented from constants.','ALG04 tilt/direction disabled in captured reference configuration; no hardware pressure/tilt capability established.','Reference CANCEL commit and active stylus POINTER_UP loss are observed reference behavior, not behavior to copy into InkWeft.','PDF test runtime thread exceptions and presentation timeouts retain unknown root cause.','Recognition synthetic scores and asymmetric pipelines do not rank real handwriting accuracy.']}
print(json.dumps(result,ensure_ascii=False,indent=2))
