"""Narrow offline independent raster failure-wire/budget audit. No CI or native calls."""
import ast,base64,copy,hashlib,importlib.util,json,re,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent
OUT=Path(__file__).resolve().parent
C=ROOT/'controller/ci/pr20-raster-screen'
sys.path.insert(0,str(C));sys.dont_write_bytecode=True
import size_bounds
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
expected={'run.py':'5ba57d828a02581a1bbfc21df982abc085fb4f39c39dc481735cfafe46319f60',
    'package.py':'9e7cdd2b90079d5262707c360ee2611d42d71802170a4123404fca90c10c4900',
    'size_bounds.py':'82e1d347d341242d7054a090c6b0b4e2364a9ae69854e41e816e16e3e908e1a5',
    'bind_control.py':'3ac263b06de4680b3263e1188cf99f8e8171fdd13c8036b50b9aa89c89b57bec'}
assert all(sha(C/n)==v for n,v in expected.items())
plan=json.loads((C/'method-plan.template.json').read_text())
contract=json.loads((C/'marker-contract.template.json').read_text())
cases=[]
def passed(name,**detail):cases.append(dict(name=name,status='PASS',**detail))

fixed=dict(frozen_control=786432,build_dependency=1048576,source_install_environment=262144,
    summary=524288,cleanup_emulator=131072,method_receipts=163840,packaging=98304,official_fixed=131072)
assert size_bounds.SHARED_BUCKETS==fixed and sum(fixed.values())==3145728
full_failure=32*(4*((32768+2)//3)+1024)+8192
assert full_failure==1439104==contract['failure_text']['wire_max_bytes']
rows=[]
states=[]
for ordinal in range(1,13):
    rule=contract['methods'][str(ordinal)]; payload=rule['method_payload_max_bytes'];count=sum(v['max_count']for v in rule['markers'].values())
    normal=2*payload+1024*count+16384
    rows.append(dict(raw=payload+512*count+16384,parsed=payload+16384,wire=normal))
    options=[({'status':'NOT_RUN'},False,0),({'status':'PASS'},True,normal),
        ({'status':'RUNNING'},True,full_failure),({'status':'INCOMPLETE'},False,full_failure),
        ({},True,full_failure),({'status':'RUNNING','normal_forward_started':True},True,normal+8192),
        ({'status':'FAIL','normal_forward_started':True,'failure_log_forwarding':{'status':'REFUSED'}},True,normal+8192)]
    for entry,raw_exists,amount in options:
        got,kind=size_bounds.method_wire_bound(entry,rule,raw_exists);assert got==amount
        states.append(dict(ordinal=ordinal,entry=entry,raw_exists=raw_exists,wire_bytes=got,kind=kind))
success=sum(sum(r.values())for r in rows)+sum(fixed.values())
failures=[sum(sum(r.values())for r in rows[:i])+1048576+(2*1048576+65536)+full_failure+sum(fixed.values())for i in range(12)]
interruptions=[sum(sum(r.values())for r in rows[:i+1])+8192+sum(fixed.values())for i in range(12)]
assert success==17795072 and max(failures)==19245952 and max(interruptions)==17803264
calc=size_bounds.calculate(contract)
assert calc['success_with_shared_reserve']==success
assert [r['bytes']for r in calc['failure_paths']]==failures
assert [r['bytes']for r in calc['interrupted_normal_paths']]==interruptions
passed('independent-12-method-arithmetic-and-84-wire-states',normal=success,failure=max(failures),normal_interrupted=max(interruptions),headroom=20971520-max(failures))

# Invoke only the actual failure forward function. A BaseException simulates process
# interruption after output while the persisted method remains RUNNING.
tree=ast.parse((C/'run.py').read_text())
forward=next(n for n in tree.body if isinstance(n,ast.FunctionDef)and n.name=='forward_failure_log')
class SimulatedKill(BaseException):pass
for kill_at in [0,1,2]:
    root=OUT/f'kill-{kill_at}';root.mkdir(exist_ok=True)
    entry=dict(copy.deepcopy(plan['steps'][-1]),status='FAIL',junit_status='FAIL',adb_exit=0,process_exit_outcome='NATURAL_EXIT')
    persisted=dict(entry,status='RUNNING');persisted.pop('junit_status');persisted.pop('adb_exit');persisted.pop('process_exit_outcome')
    data='原始失败\n完整stack\n'.encode();path=root/f"method-{entry['ordinal']:02d}-{entry['identity']}-instrumentation.log";path.write_bytes(data)
    entry['log_sha256']=hashlib.sha256(data).hexdigest();emitted=[]
    def emit(value,**kw):
        emitted.append(value)
        if len(emitted)-1==kill_at:raise SimulatedKill()
    ns={'OUT':root,'PLAN':plan,'MARKERS':contract,'REPORT':{'run_id':'900000001','run_attempt':'1','workflow_sha':'a'*40},
        'SPEC':{'identities':{s['identity']:{'git':'b'*40}for s in plan['steps']}},
        'INPUT_HASHES':{'source_spec_sha256':'c'*64,'method_plan_sha256':'d'*64},'print':emit}
    exec(compile(ast.Module([forward],type_ignores=[]),'actual-raster-failure-forward','exec'),ns)
    try:ns['forward_failure_log'](entry,path)
    except SimulatedKill:pass
    else:raise AssertionError('Simulated interruption not reached')
    assert 'failure_log_forwarding'not in persisted and 'normal_forward_started'not in persisted
    amount,kind=size_bounds.method_wire_bound(persisted,contract['methods'][str(entry['ordinal'])],True)
    assert amount==full_failure and kind=='FAILURE_OR_INTERRUPTED_FAILURE' and path.read_bytes()==data
    passed('actual-failure-print-then-kill-'+str(kill_at),persisted_status=persisted['status'],wire_reserved=amount,emitted_lines=len(emitted))

# Check fixed selection and category uniqueness directly from the actual packager.
packtree=ast.parse((C/'package.py').read_text());nodes=[];inside=False
for n in packtree.body:
    if isinstance(n,ast.Assign)and any(isinstance(t,ast.Name)and t.id=='fixed'for t in n.targets):inside=True
    if inside and isinstance(n,ast.Assign)and any(isinstance(t,ast.Name)and t.id=='optional'for t in n.targets):break
    if inside:nodes.append(n)
space={'plan':plan};exec(compile(ast.Module(nodes,type_ignores=[]),'actual-fixed-selection','exec'),space)
names=space['fixed'];assert len(names)==len(set(names))==102
classified={n:size_bounds.fixed_bucket(n)for n in names}
assert sum(v is None for v in classified.values())==24
assert size_bounds.fixed_bucket('summary.tmp')=='summary'
try:size_bounds.fixed_bucket('unknown.log')
except AssertionError:pass
else:raise AssertionError('Unbudgeted file accepted')
package=(C/'package.py').read_text();binder=(C/'bind_control.py').read_text()
assert 'out.mkdir(exist_ok=False)'in package
assert "assert not {p.name for p in source.iterdir()}-set(fixed)-set(optional)"in package
assert "fixed+optional"in package and "'size_bounds.py'"in binder
assert "'failure_text.py','size_bounds.py','workflow.proposed.yml'"in package
assert "sys.exit(bool(rejected or missing))"in package
passed('closed-selection-new-upload-and-runtime-dependency',selected_files=len(names),method_raw_parsed=24,optional_summary_tmp='retained and summary-counted',runtime_dependency='size_bounds copied by binder and selected by package')

audit_path=ROOT/'budget-audit-03/audit.json';audit=json.loads(audit_path.read_text())
assert sha(audit_path)=='abe7b399ec4ddb218001f2be1ca1d430b4592ee1d509cb24cb648df490476641'
assert audit['status']=='HOST_OFFLINE_BUDGET_AUDIT_PASS_NOT_NATIVE'
assert len(audit['checks'])==16 and len(audit['wire_state_cases'])==84
for trial in ['success','killed-failure-before-save','killed-normal-after-start','summary-tmp']:
    home=ROOT/'budget-audit-03'/trial
    budget=json.loads((home/'upload/publication-budget.json').read_text())
    assert budget['joint_bytes_upper']==budget['artifact_bytes_upper']+budget['official_evidence_wire_upper']
    assert sum(x['bytes_upper']for x in budget['shared_buckets'].values())==budget['shared_bytes']
    assert all(x['bytes_upper']<=x['limit']for x in budget['shared_buckets'].values())
    assert sum(p.stat().st_size for p in (home/'upload').iterdir())<=budget['artifact_bytes_upper']
    manifest=json.loads((home/'upload/manifest.json').read_text())
    size_item=next(v for v in manifest if v['name']=='size_bounds.py')
    assert size_item['original_sha256']==size_item['uploaded_sha256']==expected['size_bounds.py']
    if trial=='killed-failure-before-save':assert budget['wire_rows'][-1]['bytes_upper']==full_failure
    if trial=='killed-normal-after-start':assert budget['wire_rows'][-1]['bytes_upper']==rows[-1]['wire']+8192
    if trial=='summary-tmp':assert (home/'upload/summary.tmp').read_bytes()==b'x'*32768
passed('reviewed-real-packager-receipts-and-size-module-manifest',receipt_cases=4,owner_checks=16)

assert all(sha(C/n)==v for n,v in expected.items()),'Reviewed files changed'
result=dict(status='PASS_NARROW_INDEPENDENT_OFFLINE_REVIEW_NOT_NATIVE',checks=cases,input_sha256=expected,
    owner_budget_audit_sha256=sha(audit_path),wire_states=states,normal_bytes=success,maximum_bytes=max(failures),headroom_bytes=20971520-max(failures),
    limitations=['Official fixed 128 KiB is a conservative fixed-source envelope; only FINAL has a direct 32 KiB pre-print check.',
        'The closed contract does not guarantee publication of arbitrary external output; actual artifact and joint caps fail closed.',
        'No fresh native/CI execution; no source or controller edits; final readiness/runtime SHA manifest remains root-owned.'])
(OUT/'results.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({k:result[k]for k in ['status','normal_bytes','maximum_bytes','headroom_bytes']}))
