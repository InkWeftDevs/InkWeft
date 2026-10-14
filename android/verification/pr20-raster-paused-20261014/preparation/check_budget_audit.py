"""Offline numerical audit and real packager faults; no native/network/CI actions."""
import argparse,ast,copy,hashlib,json,os,shutil,subprocess,sys
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True);a=p.parse_args();assert not a.out.exists() and a.out.resolve().is_relative_to('/workspace/scratch');a.out.mkdir()
h=Path('/workspace/scratch/pr20-raster-screen-ci-20261014');c=h/'controller/ci/pr20-raster-screen';sys.path.insert(0,str(c));import size_bounds
plan=json.loads((c/'method-plan.template.json').read_text());contract=json.loads((c/'marker-contract.template.json').read_text());checks=[]
def good(name,detail):checks.append(dict(name=name,status='PASS',detail=detail))
# Independently recompute arithmetic; each shared byte belongs to exactly one bucket.
assert sum(size_bounds.SHARED_BUCKETS.values())==3145728
rows=[]
for i in range(1,13):
 rule=contract['methods'][str(i)];n=sum(x['max_count'] for x in rule['markers'].values());v=rule['method_payload_max_bytes']
 rows.append({'ordinal':i,'payload':v,'records':n,'artifact_raw':v+512*n+16384,'artifact_parsed':v+16384,'official_normal':2*v+1024*n+16384})
normal=sum(x['artifact_raw']+x['artifact_parsed']+x['official_normal'] for x in rows)+3145728
raw=1048576;chunks=[min(32768,raw-offset)for offset in range(0,raw,32768)];base64_chars=sum(4*((n+2)//3)for n in chunks);failure_wire=base64_chars+len(chunks)*1024+8192
assert failure_wire==1439104
paths=[]
for i in range(12):
 prior=sum(x['artifact_raw']+x['artifact_parsed']+x['official_normal']for x in rows[:i])
 paths.append({'ordinal':i+1,'prior_complete':prior,'failure_raw':raw,'failure_parsed_and_framing':2*raw+65536,'failure_wire':failure_wire,'shared_once':3145728,'total':prior+raw+2*raw+65536+failure_wire+3145728})
calc=size_bounds.calculate(contract);assert calc['success_with_shared_reserve']==normal==17795072
assert [x['total'] for x in paths]==[x['bytes'] for x in calc['failure_paths']]
assert max(x['total']for x in paths)==19245952 and max(x['bytes']for x in calc['interrupted_normal_paths'])==17803264
good('independent normal/failure/interrupted-normal arithmetic',{'normal':normal,'late_failure':paths[-1],'late_interrupted_normal':17803264,'headroom':20971520-paths[-1]['total']})
# Parse only the packager's pure list construction, never its runtime top level.
tree=ast.parse((c/'package.py').read_text());nodes=[];inside=False
for n in tree.body:
 if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name)and t.id=='fixed'for t in n.targets):inside=True
 if inside and isinstance(n,ast.Assign) and any(isinstance(t,ast.Name)and t.id=='optional'for t in n.targets):break
 if inside:nodes.append(n)
ns={'plan':plan};exec(compile(ast.Module(nodes,type_ignores=[]),'fixed-list-only','exec'),ns);fixed=ns['fixed'];assert len(fixed)==len(set(fixed))==102
counts={k:0 for k in size_bounds.SHARED_BUCKETS};method_content=0
for name in fixed:
 bucket=size_bounds.fixed_bucket(name)
 if bucket:counts[bucket]+=1
 else:method_content+=1
assert method_content==24
assert size_bounds.fixed_bucket('summary.tmp')=='summary'
try:size_bounds.fixed_bucket('unplanned.bin')
except AssertionError:pass
else:raise AssertionError('Unknown fixed file accepted')
good('closed mutually exclusive fixed file inventory',{'runtime_files':len(fixed),'raw_and_parsed_files':method_content,'fixed_counts':counts,'optional_summary_tmp':1})
# Each possible method branch is charged exactly once, including the killed-save gap.
wire_states=[]
for i in range(1,13):
 rule=contract['methods'][str(i)];r=rows[i-1]
 for state,entry,exists,expected in [('not-run',{'status':'NOT_RUN'},False,0),('pass',{'status':'PASS'},True,r['official_normal']),('killed-failure',{'status':'RUNNING'},True,failure_wire),('missing-final-receipt',{'status':'INCOMPLETE'},False,failure_wire),('raw-without-summary',{},True,failure_wire),('killed-normal',{'status':'RUNNING','normal_forward_started':True},True,r['official_normal']+8192),('late-normal-failure',{'status':'FAIL','normal_forward_started':True,'failure_log_forwarding':{'status':'REFUSED'}},True,r['official_normal']+8192)]:
  value,kind=size_bounds.method_wire_bound(entry,rule,exists);assert value==expected;wire_states.append({'ordinal':i,'state':state,'wire':value,'kind':kind})
good('84 exact wire state cases including interrupted failure',{'cases':len(wire_states),'failed_normal_adds_refusal_only':True})
# Execute the real packager on synthetic fixture trees, never the native controller.
layout=a.out/'layout';shutil.copytree(h/'check-source-package-04/package-layout',layout)
target=layout/'ci/pr20-raster-screen'
for name in ['package.py','size_bounds.py','failure_text.py']:
 text=(c/name).read_text()
 if name=='package.py':text=text.replace("'UNBOUND'",repr(hashlib.sha256((h/'source-preview/source-spec.json').read_bytes()).hexdigest()))
 (target/name).write_text(text)
base=h/'check-source-package-04/package-complete/evidence'
summary=json.loads((base/'summary.json').read_text());summary['methods']=[dict(s,status='PASS',junit_status='PASS',adb_exit=0,process_exit_outcome='NATURAL_EXIT',log_sha256='1'*64,normal_forward_started=True)for s in plan['steps']]
summary['status']='EXECUTION_COMPLETE_ANALYSIS_PENDING';summary['missing_files']=[]
def run(name,mutate=None,stale=False):
 home=a.out/name;shutil.copytree(base,home/'evidence');e=home/'evidence';(e/'summary-before-packaging.json').unlink();(e/'summary.json').write_text(json.dumps(summary))
 if mutate:mutate(e)
 if stale:(home/'upload').mkdir();(home/'upload/residual.txt').write_text('not in fixed manifest')
 env=os.environ.copy();env['KVM_ONCE']=str(home);q=subprocess.run([sys.executable,target/'package.py'],env=env,capture_output=True,text=True)
 (home/'stdout.txt').write_text(q.stdout);(home/'stderr.txt').write_text(q.stderr)
 return home,q
home,q=run('success');assert q.returncode==0,q.stderr
budget=json.loads((home/'upload/publication-budget.json').read_text());assert all(v['bytes_upper']<=v['limit']for v in budget['shared_buckets'].values())
assert sum(v['bytes_upper']for v in budget['shared_buckets'].values())==budget['shared_bytes']
assert budget['official_evidence_wire_upper']==131072+sum(r['official_normal'] for r in rows)
assert budget['joint_bytes_upper']==budget['artifact_bytes_upper']+budget['official_evidence_wire_upper']
assert sum(p.stat().st_size for p in (home/'upload').iterdir())<=budget['artifact_bytes_upper']
line=next(x for x in q.stdout.splitlines()if x.startswith('INKWEFT_CAUSAL_FINAL '));assert len((line+'\n').encode())<=32768
known_input_bytes=budget['shared_buckets']['frozen_control']['bytes_upper']
good('actual package totals including FINAL/cleanup/manifest',dict(budget,actual_FINAL_bytes=len((line+'\n').encode())))
for case,latch in [('killed-failure-before-save',False),('killed-normal-after-start',True)]:
 def mutate(e):
  s=copy.deepcopy(summary);s['methods'][-1]['status']='RUNNING';s['methods'][-1].pop('normal_forward_started');s['status']='MEASURING'
  if latch:s['methods'][-1]['normal_forward_started']=True
  (e/'summary.json').write_text(json.dumps(s));(e/'method-12-C1-log-forwarding.json').unlink()
 home,q=run(case,mutate);assert q.returncode==1
 b=json.loads((home/'upload/publication-budget.json').read_text());expected=rows[-1]['official_normal']+8192 if latch else failure_wire
 assert b['wire_rows'][-1]['bytes_upper']==expected
 assert json.loads((home/'upload/summary.json').read_text())['methods'][-1]['status']=='INCOMPLETE'
 good(case,{'last_method_wire':expected,'joint_bytes_upper':b['joint_bytes_upper'],'artifact_complete':False})
# Atomic leftover summary is retained, counted once, and distinct from both final summaries.
def tmp(e):(e/'summary.tmp').write_bytes(b'x'*32768)
home,q=run('summary-tmp',tmp);assert q.returncode==0
b=json.loads((home/'upload/publication-budget.json').read_text());assert (home/'upload/summary.tmp').read_bytes()==b'x'*32768
assert b['shared_buckets']['summary']['bytes_upper']-budget['shared_buckets']['summary']['bytes_upper']==32768
good('summary.tmp retained and charged once',{'bytes':32768})
for case,mut,stale in [('existing-upload',None,True),('unknown-evidence',lambda e:(e/'unexpected.log').write_text('unknown'),False)]:
 home,q=run(case,mut,stale);assert q.returncode!=0
 assert not (home/'upload/manifest.json').exists();good('refuse '+case,{'exit':q.returncode})
examples={'build_dependency':'build-C2.log','source_install_environment':'install-C2-app-guards.log','summary':'summary-before-packaging.json','cleanup_emulator':'emulator-selected.log','method_receipts':'method-12-C1-junit.xml'}
for bucket,file in examples.items():
 def mutate(e):(e/file).write_bytes(b'x'*(size_bounds.SHARED_BUCKETS[bucket]+1))
 home,q=run('overflow-'+bucket,mutate);assert q.returncode==1
 b=json.loads((home/'upload/packaging-failure.json').read_text());assert bucket in b['bucket_violations'] and len(list((home/'upload').iterdir()))==1
 assert (home/'evidence'/file).stat().st_size==size_bounds.SHARED_BUCKETS[bucket]+1
 good('actual bucket refusal '+bucket,{'limit':size_bounds.SHARED_BUCKETS[bucket],'refusal_artifact_bytes':(home/'upload/packaging-failure.json').stat().st_size,'no_truncation':True})
# The frozen/control bucket is also enforced on actual immutable-input copies.
original_run=(target/'run.py').read_bytes();(target/'run.py').write_bytes(b'x'*(size_bounds.SHARED_BUCKETS['frozen_control']+1))
try:
 home,q=run('overflow-frozen-control');assert q.returncode==1
 b=json.loads((home/'upload/packaging-failure.json').read_text());assert 'frozen_control'in b['bucket_violations']
 good('actual bucket refusal frozen_control',{'limit':size_bounds.SHARED_BUCKETS['frozen_control'],'no_truncation':True})
finally:(target/'run.py').write_bytes(original_run)
# Closed 120-file manifest/name set: give every decimal a 20-digit width and
# deliberately add the full rejection text to every normal hash-bearing row.
manifest=json.loads((a.out/'success/upload/manifest.json').read_text())
manifest.append(dict(manifest[0],name='summary.tmp'))
for item in manifest:
 item.update(original_bytes=10**20-1,uploaded_bytes=10**20-1,omitted='credential/key pattern; refuse unsafe content')
max_manifest=len((json.dumps(manifest,indent=2)+'\n').encode())
max_packaging=len((json.dumps({'files':120,'bytes_before_manifest':10**20-1,'rejected_names':[m['name']for m in manifest],'truncated_files':0,'retention_days':3,'missing_files':fixed,'complete':False},indent=2)+'\n').encode())
assert max_manifest+max_packaging+16384<=size_bounds.SHARED_BUCKETS['packaging']
good('closed packaging bucket maximum with optional residual',{'manifest':max_manifest,'packaging_metadata':max_packaging,'budget_receipt_cap':16384,'sum':max_manifest+max_packaging+16384,'limit':size_bounds.SHARED_BUCKETS['packaging']})
# Fixed wire allowance is one shared item, not added a second time in static total.
fixed_wire={'clock':1024,'method_receipts_11_pass_1_failure':11*1024+8192,'fatal_controller_receipt':8192,'cleanup_two_stdout_receipts':2*8192,'FINAL_enforced':32768,'package_receipt':2048,'bounded_controller_tracebacks':16384}
assert sum(fixed_wire.values())<=131072
report={'status':'HOST_OFFLINE_BUDGET_AUDIT_PASS_NOT_NATIVE','checks':checks,'normal_rows':rows,'failure_paths':paths,'shared_bucket_limits':size_bounds.SHARED_BUCKETS,'known_frozen_control_bytes':known_input_bytes,'fixed_official_reserve':{'model':fixed_wire,'sum':sum(fixed_wire.values()),'reserve':131072,'headroom':131072-sum(fixed_wire.values()),'note':'Only FINAL is length-checked directly. Other values are conservative fixed-source runner/error framing allowances, not a bound for arbitrary external output. Non-protocol platform action logs are outside evidence publication scope.'},'wire_state_cases':wire_states,'model_limits':'Closed source marker/frame/count contract and bounded failure raw text. Arbitrary malformed output is subject to actual bucket/joint refusal, not guaranteed publishable. No native performance evidence.'}
(a.out/'audit.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({'status':report['status'],'checks':len(checks),'normal':normal,'late_failure':paths[-1]['total'],'known_frozen_control_bytes':known_input_bytes,'shared_limit':3145728}))
