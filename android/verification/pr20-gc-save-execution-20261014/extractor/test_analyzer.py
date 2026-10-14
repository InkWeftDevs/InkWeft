"""Synthetic offline transport/terminal tests. No native run or real Git binding."""
import argparse,ast,base64,copy,datetime,hashlib,importlib.util,json,math,sys,types
from pathlib import Path
from unittest.mock import patch
import analyze_logs as A

p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True);args=p.parse_args()
args.out.mkdir(parents=True,exist_ok=False);here=Path(__file__).resolve().parent;draft=here/'fixture-inputs'
frozen=here/'frozen-inputs';root=Path('/workspace/InkWeft-gc-save-run')
fixed=json.loads((here/'FIXED_BINDING.json').read_text())
F=A.frozen_inputs(frozen,A.GIT_BINDING['source_commit'],A.GIT_BINDING['readiness_git'],root,fixed)
plan=F['plan'];contract=F['contract']
spec=importlib.util.spec_from_file_location('synthetic_failure_producer',draft/'failure_text.py')
producer=importlib.util.module_from_spec(spec);spec.loader.exec_module(producer)
ns={'json':json,'math':math,'M':1_000_000,
    'gc_fixture_spec':types.SimpleNamespace(MODES=A.frozen_markers.gc_MODES,SEGMENTS=A.frozen_markers.gc_SEGMENTS,
          COLUMNS=A.frozen_markers.gc_COLUMNS,MARKERS=A.frozen_markers.gc_MARKERS),
    'snapshot_fixture_spec':types.SimpleNamespace(MODES=contract['snapshot_control']['order'],
          STAGES=contract['snapshot_control']['boundaryStages'],READ_STAGES=contract['snapshot_control']['readStages'],
          prior=types.SimpleNamespace(EXPECTED_FIXTURE_SHA256=contract['snapshot_control']['expected']['fixtureSha256'],
              ROW_COUNTS=contract['snapshot_control']['expected']['rowCounts']))}
for node in ast.parse((draft/'check_offline.py').read_text()).body:
    if isinstance(node,ast.FunctionDef)and node.name in ['synthetic_distribution','synthetic_fixture','synthetic_synthetic','fixtures']:
        exec(compile(ast.Module([node],type_ignores=[]),'synthetic-fixture-only','exec'),ns)
raws=ns['fixtures']()
expected={'run_id':'900001','job_id':'900002',**A.GIT_BINDING}
git_receipt=A.verify_local_git(root,frozen,expected,F,fixed)
binding={'scope':'DIAGNOSTIC_ONLY',**expected,'run_attempt':'1','run_status':'completed','run_conclusion':'success','job_status':'completed','job_conclusion':'success'}
START=1791954000
iso=lambda t:datetime.datetime.fromtimestamp(t,datetime.timezone.utc).isoformat(timespec='milliseconds').replace('+00:00','Z')
clock={'status':'PASS','scope':'DIAGNOSTIC_ONLY','anchor_kind':'runner_first_step','budget_anchor_epoch':START,
       'first_step_observed_epoch':START+.1,'actual_job_started_epoch':None,'deadlines_minutes':[30,45,55,60],
       'deadline_epochs':{k:START+m*60 for k,m in [('setup',30),('data',45),('work',55)]}}
events=[(START+.2,'CLOCK',clock)];contexts=[];methods=[];final_methods=[]
for step in plan['steps']:
    i=step['ordinal'];payloads=[{'marker':line.partition(' ')[0],'original_json':line.partition(' ')[2]}for line in raws[i].splitlines()]
    records=[{'marker':x['marker'],'data':json.loads(x['original_json'])}for x in payloads]
    selected=[{'package':'org.inkweft.data.test','sha256':'1'*64,'signer_sha256':['2'*64]}]
    installed=[{'package':'org.inkweft.data.test','sha256':'1'*64,'source_apk_sha256':'1'*64}]
    context={'scope':'DIAGNOSTIC_ONLY','run_id':expected['run_id'],'run_attempt':'1','workflow_sha':expected['workflow_sha'],
       'source_commit':expected['source_commit'],'source_spec_sha256':F['hashes']['source_spec_sha256'],
       'method_plan_sha256':F['hashes']['method_plan_sha256'],'test_source_sha256':contract['methods'][str(i)]['test_source_sha256'],
       'source_manifest_sha256':F['spec']['identities']['C1']['canonical_manifest_sha256'],'selected_apks':selected,'installed_apks':installed,'apk_receipt_sha256':'3'*64,
       'install_log_sha256':'4'*64,'environment_sha256':'5'*64,**{k:step[k]for k in ['ordinal','identity','seed','args','install_group','selection']},
       'raw_log_sha256':A.sha(('synthetic-'+str(i)).encode()),'records_sha256':A.sha(A.compact(records)+b'\n'),
       'junit_status':'PASS','adb_exit':0,'process_exit_outcome':'NATURAL_EXIT'}
    at=START+10*i;events.append((at,'CONTEXT',context));contexts.append(context);ordered=[]
    for n,payload in enumerate(payloads):
        original=payload['original_json'];record={'ordinal':i,'identity':'C1','sequence':n,'marker':payload['marker'],
            'payload_bytes':len(original.encode()),'payload_sha256':A.sha(original.encode()),'original_json':original}
        ordered.append({k:record[k]for k in ['sequence','marker','payload_bytes','payload_sha256']})
        events.append((at+.001*(n+1),'RECORD',record))
    events.append((at+.2,'END',{'ordinal':i,'identity':'C1','count':len(records),'ordered_payloads':ordered,'ordered_payloads_sha256':A.sha(A.compact(ordered))}))
    method={**{k:step[k]for k in ['ordinal','phase','identity','selection']},'status':'PASS','junit_status':'PASS','adb_exit':0,'process_exit_outcome':'NATURAL_EXIT','error':None}
    events.append((at+.3,'METHOD',method));methods.append(method)
    final_methods.append({**{k:v for k,v in method.items()if k!='error'},'seed':step['seed'],'log_sha256':context['raw_log_sha256']})
clean={'emulator':'STOPPED_CONFIRMED','command_groups':'STOPPED_CONFIRMED','acl':'RESTORED_EXACT','error_count':0,'before_sha256':'6'*64,'after_sha256':'6'*64,'exact_match':True}
final={'scope':'DIAGNOSTIC_ONLY','run_id':expected['run_id'],'workflow_sha':expected['workflow_sha'],'input_hashes':F['hashes'],
       'status':'EXECUTION_COMPLETE_ANALYSIS_PENDING','cleanup_verified':True,'cleanup':{'trap':clean,'always':copy.deepcopy(clean)},
       'methods':final_methods,'install_groups':{'C1-data-diagnostics':{'identity':'C1','status':'PASS'}},
       'device_before_sha256':'5'*64,'device_after_sha256':'5'*64,'missing_files':[],'packaging_complete':True,
       'manifest_sha256':'7'*64,'summary_sha256':'8'*64}
events.append((START+180,'FINAL',final))
run={'id':900001,'head_sha':expected['workflow_sha'],'run_attempt':1,'status':'completed','conclusion':'success',
     'event':'push','head_branch':F['branch'],'path':F['workflow_path']}
steps=[{'name':name,'status':'completed','conclusion':'success','started_at':iso(START+lo),'completed_at':iso(START+hi)}
       for name,(lo,hi)in zip(F['phases'],[(1,2),(2,4),(4,6),(7,130)])]
jobs={'total_count':1,'jobs':[{'id':900002,'run_id':900001,'head_sha':expected['workflow_sha'],'run_attempt':1,'status':'completed',
       'conclusion':'success','started_at':iso(START-2),'completed_at':iso(START+200),'steps':steps}]}
render=lambda values:'\n'.join(iso(t)+' '+A.PREFIX+kind+' '+json.dumps(v,ensure_ascii=True,separators=(',',':'))for t,kind,v in values)+'\n'
def event(values,kind,ordinal=None):return next(v for _,k,v in values if k==kind and(ordinal is None or v.get('ordinal')==ordinal))
checks=[{'name':'Actual fixed source/readiness/prep/active Git chain and file bytes; read-only','status':'PASS','receipt':git_receipt}]
def analyze_synthetic(text,frozen,expected,binding,run,jobs):
    # No synthesized fixture can produce the real-run completion status.
    with patch.object(A,'BINDING_STATUS','UNBOUND'):
        return A.analyze(text,frozen,expected,binding,run,jobs)
def check(name,mutate=None,valid=False):
    e,b,r,j=copy.deepcopy((events,binding,run,jobs))
    if mutate:mutate(e,b,r,j)
    result=analyze_synthetic(render(e),F,expected,b,r,j)
    want='SYNTHETIC_STRUCTURE_COMPLETE_UNBOUND'if valid else'INCOMPLETE'
    assert result['analysis_status']==want,(name,result['issues'])
    checks.append({'name':name,'status':'PASS','analysis_status':result['analysis_status'],'issues':result['issues']})
    return result

valid=check('8 calls / exact new synthetic markers / one data APK; UNBOUND',valid=True)
for name,mutate in [
 ('missing FINAL',lambda e,b,r,j:e.pop()),('duplicate FINAL',lambda e,b,r,j:e.append(e[-1])),
 ('wrong run',lambda e,b,r,j:b.update(run_id='1')),('wrong source',lambda e,b,r,j:event(e,'CONTEXT',1).update(source_commit='e'*40)),
 ('wrong args',lambda e,b,r,j:event(e,'CONTEXT',8)['args'].append('unexpected')),
 ('wrong test hash',lambda e,b,r,j:event(e,'CONTEXT',6).update(test_source_sha256='e'*64)),
 ('wrong job',lambda e,b,r,j:j['jobs'][0].update(id=1)),('attempt bool',lambda e,b,r,j:r.update(run_attempt=True)),
 ('extra job',lambda e,b,r,j:j.update(total_count=2)),('wrong workflow',lambda e,b,r,j:r.update(path='other.yml')),
 ('wrong branch',lambda e,b,r,j:r.update(head_branch='other')),('not push',lambda e,b,r,j:r.update(event='workflow_dispatch')),
 ('wrong APK',lambda e,b,r,j:event(e,'CONTEXT',8)['selected_apks'][0].update(package='org.wrong')),
 ('installed mismatch',lambda e,b,r,j:event(e,'CONTEXT',1)['installed_apks'][0].update(sha256='e'*64)),
 ('signer mismatch',lambda e,b,r,j:event(e,'CONTEXT',8)['selected_apks'][0].update(signer_sha256=['e'*64])),
 ('raw hash mismatch',lambda e,b,r,j:event(e,'FINAL')['methods'][5].update(log_sha256='e'*64)),
 ('payload hash',lambda e,b,r,j:event(e,'RECORD',6).update(payload_sha256='e'*64)),
 ('payload length',lambda e,b,r,j:event(e,'RECORD',8).update(payload_bytes=1)),
 ('sequence bool',lambda e,b,r,j:event(e,'RECORD',6).update(sequence=False)),
 ('unknown marker',lambda e,b,r,j:event(e,'RECORD',6).update(marker='HISTORY_PRIVATE')),
 ('footer hash',lambda e,b,r,j:event(e,'END',8).update(ordered_payloads_sha256='e'*64)),
 ('records hash',lambda e,b,r,j:event(e,'CONTEXT',7).update(records_sha256='e'*64)),
 ('missing END',lambda e,b,r,j:e.__setitem__(slice(None),[x for x in e if not(x[1]=='END'and x[2]['ordinal']==7)])),
 ('JUnit skip',lambda e,b,r,j:event(e,'METHOD',8).update(junit_status='SKIP')),
 ('adb nonzero',lambda e,b,r,j:event(e,'METHOD',8).update(adb_exit=1)),
 ('non-natural exit',lambda e,b,r,j:event(e,'METHOD',8).update(process_exit_outcome='AUXILIARY_AM_KILL_CONFIRMED')),
 ('cleanup missing',lambda e,b,r,j:event(e,'FINAL')['cleanup'].pop('trap')),
 ('cleanup error',lambda e,b,r,j:event(e,'FINAL')['cleanup']['always'].update(error_count=1)),
 ('ACL mismatch',lambda e,b,r,j:event(e,'FINAL')['cleanup']['always'].update(after_sha256='e'*64)),
 ('ACL origins mismatch',lambda e,b,r,j:event(e,'FINAL')['cleanup']['always'].update(before_sha256='e'*64,after_sha256='e'*64)),
 ('environment mismatch',lambda e,b,r,j:event(e,'FINAL').update(device_after_sha256='e'*64)),
 ('incomplete packaging',lambda e,b,r,j:event(e,'FINAL').update(packaging_complete=False)),
 ('missing files',lambda e,b,r,j:event(e,'FINAL').update(missing_files=['method-08-C1-instrumentation.log'])),
 ('late setup',lambda e,b,r,j:j['jobs'][0]['steps'][2].update(completed_at=iso(START+1801))),
 ('wrong cutoff',lambda e,b,r,j:event(e,'CLOCK')['deadline_epochs'].update(data=START+2800)),
 ('over60m',lambda e,b,r,j:j['jobs'][0].update(completed_at=iso(START+3601))),
 ('official failure',lambda e,b,r,j:(r.update(conclusion='failure'),b.update(run_conclusion='failure'))),
]:check(name,mutate)

# Preserve the old extractor's byte/duplicate/phase/type regression coverage.
def duplicate(e,kind):
    i=next(i for i,x in enumerate(e)if x[1]==kind);e.insert(i,copy.deepcopy(e[i]))
for kind in ['CLOCK','CONTEXT','RECORD','END','METHOD']:
    check('duplicate '+kind,lambda e,b,r,j,k=kind:duplicate(e,k))
for name,mutate in [
 ('wrong prep',lambda e,b,r,j:b.update(prep_sha='e'*40)),
 ('wrong readiness',lambda e,b,r,j:b.update(readiness_git='e'*40)),
 ('job attempt bool',lambda e,b,r,j:j['jobs'][0].update(run_attempt=True)),
 ('job count bool',lambda e,b,r,j:j.update(total_count=True)),
 ('END ordinal bool',lambda e,b,r,j:event(e,'END',1).update(ordinal=True)),
 ('FINAL exit bool',lambda e,b,r,j:event(e,'FINAL')['methods'][0].update(adb_exit=False)),
 ('cleanup error count bool',lambda e,b,r,j:event(e,'FINAL')['cleanup']['trap'].update(error_count=False)),
 ('group incomplete',lambda e,b,r,j:event(e,'FINAL')['install_groups']['C1-data-diagnostics'].update(status='INSTALLED')),
 ('data phase excludes first context',lambda e,b,r,j:j['jobs'][0]['steps'][-1].update(started_at=iso(START+10.1))),
 ('official phase overlap',lambda e,b,r,j:j['jobs'][0]['steps'][2].update(started_at=iso(START+3))),
 ('phase after job end',lambda e,b,r,j:j['jobs'][0]['steps'][-1].update(completed_at=iso(START+202))),
]:check(name,mutate)
def payload(e,ordinal,value,full=False):
    rec=event(e,'RECORD',ordinal);rec.update(original_json=value,payload_bytes=len(value.encode()),payload_sha256=A.sha(value.encode()))
    if full:
        end=event(e,'END',ordinal)
        end['ordered_payloads'][rec['sequence']]={k:rec[k]for k in ['sequence','marker','payload_bytes','payload_sha256']}
        end['ordered_payloads_sha256']=A.sha(A.compact(end['ordered_payloads']))
        parsed=[{'marker':v['marker'],'data':json.loads(v['original_json'])}for _,k,v in e if k=='RECORD'and v['ordinal']==ordinal]
        event(e,'CONTEXT',ordinal)['records_sha256']=A.sha(A.compact(parsed)+b'\n')
for ordinal in [6,8]:
    for label,value in [('truncated','{"schema":'),('array','[]'),('duplicate key','{"schema":1,"schema":1}'),('nonfinite','{"schema":NaN}')]:
        check(f'method{ordinal} rehashed {label}',lambda e,b,r,j,n=ordinal,v=value:payload(e,n,v))
    def unknown(e,b,r,j,n=ordinal):
        value=json.loads(event(e,'RECORD',n)['original_json']);value['unapproved']=1
        payload(e,n,json.dumps(value),full=True)
    check(f'method{ordinal} full rehash unknown schema field',unknown)
def spaced(e,b,r,j):
    for n in [6,8]:payload(e,n,'  '+event(e,'RECORD',n)['original_json']+'  ',full=True)
preserved=check('GC and snapshot original JSON whitespace retained',spaced,valid=True)
A.write_outputs(args.out/'original-json-output',preserved)
for n in [6,8]:
    original=json.loads((args.out/f'original-json-output/method-{n:02}-C1-original-payloads.json').read_text())[0]
    assert original['original_json'].startswith('  ')and original['original_json'].endswith('  ')
    assert original['payload_sha256']==A.sha(original['original_json'].encode())

RAW=b'x'*32767+'中文\r\n\0INKWEFT_CAUSAL_FINAL fake\u2028tail\n'.encode()
def failed(e,b,r,j,ordinal=6,raw=RAW,refusal=False,normal=False):
    step=plan['steps'][ordinal-1]
    fb={k:expected[k]for k in ['run_id','workflow_sha','source_commit']}
    fb.update(run_attempt=1,source_spec_sha256=F['hashes']['source_spec_sha256'],method_plan_sha256=F['hashes']['method_plan_sha256'],
       test_source_sha256=contract['methods'][str(ordinal)]['test_source_sha256'],ordinal=ordinal,identity='C1',selection=step['selection'],
       status='FAIL',junit_status='FAIL',adb_exit=0,process_exit_outcome='NATURAL_EXIT')
    bound=contract['transport']['methods'][str(ordinal)]
    packets=producer.prepare_failure_text(raw,fb,A.sha(raw),bound['failure_raw_max_bytes'],bound['failure_wire_max_bytes'],normal_forward_started=normal)
    if refusal:packets=[dict(schema=2,scope='DIAGNOSTIC_FAILURE_ONLY',kind='REFUSED',binding=fb,raw_bytes=bound['failure_raw_max_bytes']+1,raw_sha256=A.sha(raw),reason='RAW_OVERSIZE')]
    method={**methods[ordinal-1],**{k:fb[k]for k in ['status','junit_status','adb_exit','process_exit_outcome']},'error':'synthetic failure'}
    e[:]=[x for x in e if x[1]=='CLOCK'or(x[1]!='FINAL'and x[2].get('ordinal',99)<ordinal)
           or(normal and x[1]in ['CONTEXT','RECORD','END']and x[2].get('ordinal')==ordinal)]
    if normal:event(e,'CONTEXT',ordinal)['raw_log_sha256']=A.sha(raw)
    e.extend((START+ordinal*10+.3+n*.001,'FAILURE_TEXT',v)for n,v in enumerate(packets))
    e.append((START+ordinal*10+1,'METHOD',method))
    terminal=copy.deepcopy(final);terminal.update(status='BLOCKED_OR_FAILED',device_after_sha256=None,packaging_complete=False,missing_files=['device-after.json'])
    terminal['install_groups']['C1-data-diagnostics']['status']='INCOMPLETE'
    for m in terminal['methods']:
        if m['ordinal']==ordinal:m.update({**{k:v for k,v in method.items()if k!='error'},'log_sha256':A.sha(raw)})
        elif m['ordinal']>ordinal:m.update(status='NOT_RUN',junit_status=None,adb_exit=None,process_exit_outcome=None,log_sha256=None)
    e.append((START+180,'FINAL',terminal))
    r['conclusion']=j['jobs'][0]['conclusion']=b['run_conclusion']=b['job_conclusion']='failure';j['jobs'][0]['steps'][-1]['conclusion']='failure'
    return packets

failure=check('full failure bytes, crossed UTF8 chunk, ignored embedded receipt, audited FINAL',failed)
assert failure['state']['failed_final_audit']['dual_cleanup_exact']and failure['state']['failures'][0]['raw']==RAW
A.write_outputs(args.out/'failed-output',failure)
assert (args.out/'failed-output/failure-method-06-C1-instrumentation.log').read_bytes()==RAW
assert failure['failure_evidence'][0]['whole_run_complete']is False
check('guard failure',lambda e,b,r,j:failed(e,b,r,j,ordinal=1))
check('save failure',lambda e,b,r,j:failed(e,b,r,j,ordinal=8))
check('empty UTF8 failure',lambda e,b,r,j:failed(e,b,r,j,raw=b''))
check('explicit oversize refusal',lambda e,b,r,j:failed(e,b,r,j,refusal=True))
normal=check('normal forwarded then failure refusal remains incomplete',lambda e,b,r,j:failed(e,b,r,j,normal=True))
assert normal['state']['failed_final_audit']['dual_cleanup_exact']and normal['state']['failures'][0]['status']=='REFUSED'

def packet(e,kind):return next(v for _,k,v in e if k=='FAILURE_TEXT'and v['kind']==kind)
def fault(name,change):
    def mutate(e,b,r,j):failed(e,b,r,j);change(e,b,r,j)
    return check(name,mutate)
for name,change in [
 ('failure missing BEGIN',lambda e,b,r,j:e.__setitem__(slice(None),[x for x in e if not(x[1]=='FAILURE_TEXT'and x[2]['kind']=='BEGIN')])),
 ('failure missing END',lambda e,b,r,j:e.__setitem__(slice(None),[x for x in e if not(x[1]=='FAILURE_TEXT'and x[2]['kind']=='END')])),
 ('failure bad binding',lambda e,b,r,j:packet(e,'BEGIN')['binding'].update(source_commit='e'*40)),
 ('failure attempt bool',lambda e,b,r,j:packet(e,'BEGIN')['binding'].update(run_attempt=True)),
 ('failure bad whole hash',lambda e,b,r,j:packet(e,'BEGIN').update(raw_sha256='e'*64)),
 ('failure bad context',lambda e,b,r,j:packet(e,'PART').update(context_sha256='e'*64)),
 ('failure bad part',lambda e,b,r,j:packet(e,'PART').update(part=1)),
 ('failure boolean part',lambda e,b,r,j:packet(e,'PART').update(part=False)),
 ('failure bad chunk length',lambda e,b,r,j:packet(e,'PART').update(raw_bytes=1)),
 ('failure bad chunk hash',lambda e,b,r,j:packet(e,'PART').update(raw_sha256='e'*64)),
 ('failure invalid base64',lambda e,b,r,j:packet(e,'PART').update(base64='!')),
 ('failure bad footer',lambda e,b,r,j:packet(e,'END').update(parts=99)),
 ('failure missing FINAL',lambda e,b,r,j:e.pop()),
 ('failure cleanup broken',lambda e,b,r,j:event(e,'FINAL')['cleanup']['always'].update(exact_match=False)),
 ('failure METHOD differs',lambda e,b,r,j:event(e,'METHOD',6).update(junit_status='INCOMPLETE')),
 ('failure FINAL raw differs',lambda e,b,r,j:event(e,'FINAL')['methods'][5].update(log_sha256='e'*64)),
 ('failure successor ran',lambda e,b,r,j:event(e,'FINAL')['methods'][6].update(status='PASS')),
 ('failure fake success FINAL',lambda e,b,r,j:event(e,'FINAL').update(status='EXECUTION_COMPLETE_ANALYSIS_PENDING')),
]:
    result=fault(name,change)
    assert 'failed_final_audit'not in result['state'],name

# Forge internally consistent packets without the producer so its own refusal
# cannot hide a consumer bug in full-hash/UTF-8/sensitive-text enforcement.
def replace_failure_bytes(e,data,advertised_hash=None):
    begin=copy.deepcopy(packet(e,'BEGIN'));h=advertised_hash or A.sha(data)
    begin.update(raw_bytes=len(data),raw_sha256=h,parts=(len(data)+32767)//32768)
    context=A.sha(json.dumps(begin,ensure_ascii=True,separators=(',',':')).encode())
    packets=[begin]
    for i,start in enumerate(range(0,len(data),32768)):
        chunk=data[start:start+32768]
        packets.append(dict(schema=2,scope='DIAGNOSTIC_FAILURE_ONLY',kind='PART',context_sha256=context,
            part=i,parts=begin['parts'],raw_bytes=len(chunk),raw_sha256=A.sha(chunk),base64=base64.b64encode(chunk).decode()))
    packets.append(dict(schema=2,scope='DIAGNOSTIC_FAILURE_ONLY',kind='END',context_sha256=context,
        parts=begin['parts'],raw_bytes=len(data),raw_sha256=h))
    e[:]=[x for x in e if x[1]!='FAILURE_TEXT']
    i=next(i for i,x in enumerate(e)if x[1]=='METHOD'and x[2]['ordinal']==6)
    e[i:i]=[(START+60.3+n*.001,'FAILURE_TEXT',v)for n,v in enumerate(packets)]
    event(e,'FINAL')['methods'][5]['log_sha256']=h
for name,data,advertised in [('invalid complete UTF8',b'\xff',None),('sensitive complete bytes',b'ghp_'+b'x'*30,None),
                             ('whole hash with consistent envelope context',b'valid utf8','e'*64)]:
    result=fault(name,lambda e,b,r,j,d=data,h=advertised:replace_failure_bytes(e,d,h))
    assert 'failed_final_audit'not in result['state']and result['state']['failures'][0]['status']=='OPEN'
for ordinal in [1,6,8]:
    maximum=contract['transport']['methods'][str(ordinal)]['failure_raw_max_bytes']
    result=check(f'method{ordinal} exact failure cap {maximum} bytes',lambda e,b,r,j,n=ordinal,m=maximum:failed(e,b,r,j,ordinal=n,raw=bytes(m)))
    assert result['state']['failed_final_audit']['dual_cleanup_exact']and len(result['state']['failures'][0]['raw'])==maximum
def mixed(e,b,r,j):
    failed(e,b,r,j,normal=True)
    begin=producer.prepare_failure_text(RAW,packet(e,'REFUSED')['binding'],A.sha(RAW),contract['transport']['methods']['6']['failure_raw_max_bytes'],contract['transport']['methods']['6']['failure_wire_max_bytes'])[0]
    e[next(i for i,x in enumerate(e)if x[1]=='FAILURE_TEXT')]=(START+60.3,'FAILURE_TEXT',begin)
result=check('normal and full failure forwarding cannot mix',mixed)
assert 'failed_final_audit'not in result['state']
def no_envelope(e,b,r,j):
    failed(e,b,r,j);e[:]=[x for x in e if x[1]!='FAILURE_TEXT']
result=check('missing failure text still audits terminal without claiming text',no_envelope)
assert result['state']['failed_final_audit']['failure_text_audited']is False
def method_unicode(e,b,r,j):
    failed(e,b,r,j);event(e,'METHOD',6)['error']='synthetic\u2028message'
e,b,r,j=copy.deepcopy((events,binding,run,jobs));method_unicode(e,b,r,j)
text=render(e).replace('synthetic\\u2028message','synthetic\u2028message')
result=analyze_synthetic(text,F,expected,b,r,j)
assert result['state']['failed_final_audit']['dual_cleanup_exact']
checks.append({'name':'Literal Unicode line separator inside METHOD JSON remains one physical line','status':'PASS'})

# Main must refuse all real closures while readiness/prep/active are unbound.
argv=['analyze_logs.py']
for key in ['job-log','frozen','control-root','binding','run-metadata','jobs-metadata']:
    argv+=['--'+key,str(args.out/'does-not-exist')]
argv+=['--out',str(args.out/'unbound-cli')]
for k,v in expected.items():argv+=['--'+k.replace('_','-'),v]
with patch.object(sys,'argv',argv),patch.object(A,'BINDING_STATUS','UNBOUND'):assert A.main()==1
unbound=json.loads((args.out/'unbound-cli/analysis.json').read_text())
assert unbound['binding_status']=='UNBOUND'and'UNBOUND:'in unbound['issues'][0]
checks.append({'name':'UNBOUND CLI refuses before reading unavailable inputs','status':'PASS'})
argv[argv.index('--out')+1]=str(args.out/'wrong-run-cli')
with patch.object(sys,'argv',argv):assert A.main()==1
wrong=json.loads((args.out/'wrong-run-cli/analysis.json').read_text())
assert 'Reviewed official run/job differs'in wrong['issues'][0]
checks.append({'name':'BOUND CLI rejects a synthetic run/job before reading unavailable inputs','status':'PASS'})
A.write_outputs(args.out/'synthetic-structure-output',valid)
assert not any(item['whole_run_complete']for item in valid['extracted_records'])
receipt={'status':'PASS_FIXED_CHAIN_OFFLINE_SYNTHETIC_ONLY','binding_status':A.BINDING_STATUS,'cases':checks,
    'source_sha256':{n:A.sha((here/n).read_bytes())for n in ['analyze_logs.py','frozen_markers.py','test_analyzer.py']},
    'limitations':['Git chain checked locally; all job-log/metadata fixtures are synthetic','No native run result or online authenticity verification'],
    'fixture_input_sha256':{x.name:A.sha(x.read_bytes())for x in draft.iterdir()if x.is_file()}}
(args.out/'selftest-results.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps({'status':receipt['status'],'cases':len(checks)}))
