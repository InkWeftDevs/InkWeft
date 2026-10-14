"""Independent selected runtime fault injection, AST functions only; never starts CI/ADB/Gradle."""
import ast, base64, copy, hashlib, io, json, os, re, subprocess, sys, time, types
from pathlib import Path
import xml.etree.ElementTree as ET

HERE=Path(__file__).resolve().parent
CTRL=HERE.parent/'controller'
ROOT=HERE/'runtime-fixtures'
ROOT.mkdir(exist_ok=True)
PLAN=json.loads((CTRL/'method-plan.template.json').read_text())
CONTRACT=json.loads((CTRL/'marker-contract.template.json').read_text())
source=(CTRL/'run.py').read_text(); tree=ast.parse(source)
helper=ast.parse((HERE.parent/'failure-proposal/failure_text.py').read_text())
hashfile=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
before={str(p):hashfile(p) for p in [CTRL/'run.py',CTRL/'package.py',CTRL/'method-plan.template.json',CTRL/'marker-contract.template.json']}
cases=[]

def space():
    ns=dict(base64=base64,hashlib=hashlib,json=json,re=re,Path=Path,time=time,os=os,subprocess=subprocess,ET=ET,sys=sys)
    for n in tree.body:
        if isinstance(n,(ast.FunctionDef,ast.ClassDef)) or (isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id in ['CHUNK_BYTES','SECRET','PREFIX','BINDING_KEYS'] for t in n.targets)):
            exec(compile(ast.Module([n],type_ignores=[]),'review-runtime-functions','exec'),ns)
    return ns

for name in ['validate_binding','encoded','wire_bytes','maximum_wire_bytes','prepare_failure_text']:
    a=next(n for n in helper.body if isinstance(n,ast.FunctionDef) and n.name==name)
    b=next(n for n in tree.body if isinstance(n,ast.FunctionDef) and n.name==name)
    assert ast.dump(a)==ast.dump(b),name
cases.append({'name':'embedded-producer-byte-equivalent-AST','status':'PASS'})

def configure(name, ordinal=8):
    ns=space(); root=ROOT/name; out=root/'evidence'; out.mkdir(parents=True,exist_ok=True)
    entry=dict(copy.deepcopy(PLAN['steps'][ordinal-1]),status='FAIL',junit_status='FAIL',adb_exit=0,process_exit_outcome='NATURAL_EXIT')
    report=dict(run_id='900000001',run_attempt='1',workflow_sha='a'*40,methods=[entry])
    snapshots=[]; emitted=[]
    ns.update(ROOT=root,OUT=out,PLAN=PLAN,MARKERS=CONTRACT,REPORT=report,
        SPEC={'identities':{'C1':{'git':CONTRACT['source_commit']}}},INPUT_HASHES={'source_spec_sha256':'b'*64,'method_plan_sha256':'c'*64},
        save=lambda:snapshots.append(copy.deepcopy(entry)), print=lambda value,**kw:emitted.append(value))
    path=out/f'method-{ordinal:02d}-C1-instrumentation.log'
    return ns,entry,path,snapshots,emitted

for failure_at in [None,0,2,4,'save']:
    ns,entry,path,snapshots,emitted=configure('forward-'+str(failure_at))
    data=("字节完整\n"*8000).encode(); path.write_bytes(data); entry['log_sha256']=hashlib.sha256(data).hexdigest()
    called=[0]
    sentinel=OSError('synthetic-forward-interruption')
    if failure_at=='save': ns['save']=lambda:(_ for _ in ()).throw(sentinel)
    else:
        def emit(value,**kw):
            assert snapshots and snapshots[-1]['failure_wire_bytes']>0
            assert snapshots[-1]['forwarding_path']=='FAILURE_STARTED'
            n=called[0];called[0]+=1
            if n==failure_at:raise sentinel
            emitted.append(value)
        ns['print']=emit
    try: ns['forward_failure_log'](entry,path)
    except OSError as e: assert e is sentinel and failure_at is not None
    else: assert failure_at is None
    assert entry['status']=='FAIL' and path.read_bytes()==data
    if failure_at=='save':assert emitted==[]
    else:
        assert snapshots[0]['failure_wire_bytes']>0
        if failure_at is None:
            records=[json.loads(line.split(' ',1)[1])for line in emitted]
            raw=b''.join(base64.b64decode(r['base64'],validate=True)for r in records if r['kind']=='PART')
            assert raw==data and records[-1]['raw_sha256']==hashlib.sha256(data).hexdigest()
    cases.append({'name':'failure-precharge-and-interruption-'+str(failure_at),'status':'PASS','emitted_lines':len(emitted)})

for fault in ['wrong-path','symlink','wrong-selection','wrong-hash','normal-started']:
    ns,e,path,snapshots,emitted=configure('binding-'+fault); data=b'original complete failure\n';path.write_bytes(data);e['log_sha256']=hashlib.sha256(data).hexdigest()
    supplied=path
    if fault=='wrong-path':supplied=path.parent/'other.log';supplied.write_bytes(data)
    if fault=='symlink':path.unlink();path.symlink_to(path.parent/'other.log');(path.parent/'other.log').write_bytes(data)
    if fault=='wrong-selection':e['selection']='org.inkweft.data.Other#method'
    if fault=='wrong-hash':e['log_sha256']='0'*64
    if fault=='normal-started':e['forwarding_path']='NORMAL_STARTED'
    try:ns['forward_failure_log'](e,supplied)
    except AssertionError:assert fault in ['wrong-path','symlink','wrong-selection'] and emitted==[]
    else:
        assert fault in ['wrong-hash','normal-started']
        record=json.loads(emitted[0].split(' ',1)[1]);assert len(emitted)==1 and record['kind']=='REFUSED'
    assert e['status']=='FAIL'
    cases.append({'name':'exact-runtime-binding-'+fault,'status':'PASS'})

for fault in [False,True]:
    ns,e,path,snapshots,emitted=configure('method-primary-'+str(fault),1)
    if path.exists():path.unlink()
    e['status']='NOT_RUN'
    cls,meth=e['selection'].split('#')
    raw=f'INSTRUMENTATION_STATUS: class={cls}\nINSTRUMENTATION_STATUS: test={meth}\nINSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS: class={cls}\nINSTRUMENTATION_STATUS: test={meth}\nINSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS_CODE: -2\nFAILURES!!!\nTests run: 1, Failures: 1\nINSTRUMENTATION_CODE: -1\n'
    def fake_run(args,*unused,**kw):kw['stdout'].write(raw);return subprocess.CompletedProcess(args,0)
    ns.update(run=fake_run,seconds=lambda *a:100,processes_after=lambda e:e.update(process_exit_outcome='NATURAL_EXIT'))
    if fault:ns['forward_failure_log']=lambda *a:(_ for _ in ()).throw(OSError('synthetic forward error'))
    try:ns['method'](e)
    except AssertionError as error:assert str(error)=='Original method/JUnit failed; stop without retry'
    else:raise AssertionError('Primary failure swallowed')
    assert e['status']=='FAIL' and e['junit_status']=='FAIL' and path.read_text()==raw
    try:ns['method'](e)
    except AssertionError:pass
    else:raise AssertionError('Method retry accepted')
    cases.append({'name':'primary-JUnit-preserved-and-no-retry-'+str(fault),'status':'PASS'})

# Exercise actual main preconditions with inert side-effect stubs, so stale method counts cannot hide behind UNBOUND guard.
for count in [8,7,9,10]:
    ns=space(); entries=[dict(s,status='NOT_RUN')for s in PLAN['steps']]
    while len(entries)<count:entries.append(dict(entries[-1]))
    entries=entries[:count]; calls=[]
    env={'GITHUB_SHA':'a'*40,'GITHUB_RUN_ID':'900000001','GITHUB_RUN_ATTEMPT':'1'}
    report=dict(input_hashes={},source_spec={},actual_job_started_epoch=None,budget_anchor_epoch=1,
        workflow_sha=env['GITHUB_SHA'],phase_cutoff_epochs={},work_deadline_epoch=2,run_id=env['GITHUB_RUN_ID'],
        run_attempt='1',methods=entries,actions={},status='PREPARING')
    ns.update(REPORT=report,INPUT_HASHES={},SPEC={},START=1,CUTOFFS={},DEADLINE=2,PLAN=PLAN,
        os=types.SimpleNamespace(environ=env),sys=types.SimpleNamespace(argv=['run.py','setup']),
        save=lambda:None,seconds=lambda:100,setup=lambda:calls.append('setup'))
    try:ns['main']()
    except AssertionError:assert count!=8 and calls==[]
    else:assert count==8 and calls==['setup'] and report['actions']['setup']['status']=='PASS'
    cases.append({'name':'actual-main-count-'+str(count),'status':'PASS'})

# Actual ordered methods loop must propagate the first exception without invoking a successor.
ns=space(); out=ROOT/'fail-stop';out.mkdir(exist_ok=True);(out/'device-before.json').write_text('{}')
entries=[dict(s,status='NOT_RUN')for s in PLAN['steps']];calls=[];fault=RuntimeError('first-method-failure')
def invoked(e):
    calls.append(e['ordinal'])
    if e['ordinal']==2:raise fault
    e['status']='PASS'
ns.update(OUT=out,REPORT={'methods':entries},PHASE_NAMES={'data':'data-diagnostics'},install=lambda *a:None,method=invoked)
try:ns['methods']('data')
except RuntimeError as error:assert error is fault
else:raise AssertionError('Method plan failure swallowed')
assert calls==[1,2] and all(e['status']=='NOT_RUN' for e in entries[2:])
cases.append({'name':'actual-method-loop-global-first-failure-stop','status':'PASS'})

packtree=ast.parse((CTRL/'package.py').read_text())
account=next(n for n in packtree.body if isinstance(n,ast.FunctionDef) and n.name=='account_text')
pack={'re':re};exec(compile(ast.Module([account],type_ignores=[]),'review-package-function','exec'),pack)
report={'methods':[dict(s,status='NOT_RUN')for s in PLAN['steps']]}
baseline=pack['account_text']([],report,CONTRACT,0);assert baseline['combined_reserved_bytes']==65536
category_files={'source_frozen_protocol_controller_and_manifest_text':'run.py',
    'build_and_sdk_source_setup_logs':'build-C1.log',
    'dependencies_apk_install_host_device_receipts':'apk-C1.json',
    'forwarding_inventory_context_method_final_and_summary_copies':'summary.json',
    'guard_raw_junit_cleanup_acl_packaging_and_manifest':'cleanup-trap.json'}
for category,filename in category_files.items():
    cap=CONTRACT['transport']['fixed_text_buckets_bytes'][category]
    try:pack['account_text']([(filename,b'x'*(cap+1))],report,CONTRACT,0)
    except AssertionError:pass
    else:raise AssertionError('Fixed category overflow accepted: '+category)
    cases.append({'name':'actual-fixed-bucket-reject-'+category,'status':'PASS'})

filled=[]
for category,filename in category_files.items():
    size=CONTRACT['transport']['fixed_text_buckets_bytes'][category]
    if category=='forwarding_inventory_context_method_final_and_summary_copies':size-=65536
    filled.append((filename,b'x'*size))
report={'methods':[dict(s,status='PASS')for s in PLAN['steps']]}
for ordinal in [6,7,8]:
    e=report['methods'][ordinal-1];b=CONTRACT['transport']['methods'][str(ordinal)]
    if ordinal==8:
        e.update(status='FAIL',forwarding_path='FAILURE_COMPLETE',failure_wire_bytes=b['failure_wire_max_bytes'],failure_log_forwarding={'status':'FORWARDED'})
        raw_bytes=b['failure_raw_max_bytes']
    else:
        e.update(forwarding_path='NORMAL_COMPLETE',normal_record_end_wire_bytes=b['normal_record_end_max_bytes'])
        raw_bytes=b['normal_raw_max_bytes']
    filled.append((f'method-{ordinal:02d}-C1-instrumentation.log',b'x'*raw_bytes))
maxcase=pack['account_text'](filled,report,CONTRACT,0)
assert maxcase['combined_reserved_bytes']==19675060
cases.append({'name':'actual-accounting-full-shared-buckets-plus-largest-exclusive-case','status':'PASS','bytes':maxcase['combined_reserved_bytes']})
duplicate=copy.deepcopy(report);duplicate['methods'][-1]['normal_record_end_wire_bytes']=1
try:pack['account_text']([],duplicate,CONTRACT,0)
except AssertionError:pass
else:raise AssertionError('Normal and full failure streams both accepted')
cases.append({'name':'actual-accounting-rejects-two-full-streams','status':'PASS'})
successor=copy.deepcopy(report);successor['methods'][6]['status']='FAIL';successor['methods'][7]['status']='PASS'
try:pack['account_text']([],successor,CONTRACT,0)
except AssertionError:pass
else:raise AssertionError('Successor after first failure accepted')
cases.append({'name':'actual-accounting-rejects-successor-after-failure','status':'PASS'})
low=copy.deepcopy(CONTRACT);low['transport']['total_text_limit_bytes']=19675059
try:pack['account_text'](filled,report,low,0)
except AssertionError:pass
else:raise AssertionError('Combined total overflow accepted')
cases.append({'name':'actual-accounting-rejects-combined-total-overflow','status':'PASS'})

assert all(hashfile(Path(p))==v for p,v in before.items()),'Reviewed runtime changed during check'
result=dict(status='PASS_SELECTED_OFFLINE_RUNTIME_CHECKS_NOT_NATIVE',cases=len(cases),details=cases,input_sha256=before,
            limits=['No functions with real command, network, Android, Gradle or permission side effects were invoked.',
                    'Actual functions extracted by AST; main side effects are inert stubs.',
                    'Packaging full suite is separately reviewed/bound; this script covers focused failure integration.'])
(HERE/'runtime-results.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({'status':result['status'],'cases':len(cases),'inputs':before}))
