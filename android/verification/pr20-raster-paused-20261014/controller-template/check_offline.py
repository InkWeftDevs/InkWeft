"""Offline proposal checks only. No Gradle, SDK, ADB, network or ACL calls."""
import argparse,ast,copy,hashlib,io,json,math,os,pathlib,re,shutil,subprocess,sys,time,types,xml.etree.ElementTree as ET
from collections import Counter
from contextlib import redirect_stdout
import yaml
P=pathlib.Path;p=argparse.ArgumentParser();p.add_argument('--out',type=P,required=True);a=p.parse_args();assert not a.out.exists() and a.out.resolve().is_relative_to('/workspace/scratch');a.out.mkdir(parents=True)
H=P(__file__).parent;R=P('/workspace/InkWeft');sys.path.insert(0,str(H));import markers,failure_text,size_bounds
plan=json.loads((H/'method-plan.template.json').read_text());contract=json.loads((H/'marker-contract.template.json').read_text());contract['status']='FROZEN';steps=plan['steps'];checks=[]
sha=lambda b:hashlib.sha256(b).hexdigest()
def check(name,details):checks.append(dict(name=name,status='PASS',details=details))
def reject(fn):
 try:fn()
 except (AssertionError,ValueError,TypeError,KeyError,RuntimeError):return
 raise AssertionError('Expected refusal')
for file in H.glob('*.py'):ast.parse(file.read_text())
assert (H/'cleanup.py').read_bytes()==(R/'ci/pr20-causal/cleanup.py').read_bytes()
assert (H/'original-protocol.json').read_bytes()==(R/'ci/pr20-causal/original-protocol.json').read_bytes()
for f in ['run.py','package.py']:
 q=subprocess.run([sys.executable,H/f],capture_output=True,text=True);assert q.returncode and 'Finalize source' in q.stderr
assert [s['ordinal'] for s in steps]==list(range(1,13))
assert Counter(s['phase'] for s in steps)=={'data-diagnostics':2,'raster-diagnostics':10}
assert len({s['install_group']for s in steps})==5
assert [(s['identity'],s['seed'])for s in steps[8:]]==[('C1',4101),('C2',4101),('C2',4102),('C1',4102)]
assert all(s['expected_junit_tests']==1 and s['outer_cap_seconds']==3600 for s in steps)
check('unbound refusal and exact source/method scope',dict(methods=12,groups=5,apks=5,cleanup_and_original_protocol_unchanged=True))
source=(H/'run.py').read_text();tree=ast.parse(source);oldtree=ast.parse((R/'ci/pr20-causal/run.py').read_text());ns={'Path':P,'re':re,'json':json,'hashlib':hashlib,'math':math,'Counter':Counter,'time':time,'os':os,'sys':sys,'shutil':shutil,'subprocess':subprocess,'ET':ET}
for n in tree.body:
 if isinstance(n,(ast.FunctionDef,ast.ClassDef)):exec(compile(ast.Module([n],type_ignores=[]),'candidate-controller-functions','exec'),ns)
unchanged=['write_json','save','seconds','digest','owned','run','capture','shell','phase','host','environment','source_manifest','validate_clock','official_dependency_check','validate_instrumentation_manifest','processes_after','junit']
for name in unchanged:
 current=next(n for n in tree.body if isinstance(n,ast.FunctionDef)and n.name==name);old=next(n for n in oldtree.body if isinstance(n,ast.FunctionDef)and n.name==name);assert ast.dump(current)==ast.dump(old),name
check('reviewed core functions preserved',unchanged)
ns.update(ADB=['adb','-s','emulator-5554'],PACKAGES=['org.inkweft.app.a0','org.inkweft.app.a0.test','org.inkweft.data.test'],MARKERS=contract)
# Real prior payloads are parser fixtures only; no native run is claimed.
prior=R/'android/verification/pr20-match-execution-20261014/extracted';raws={}; mapping={3:60,4:61,5:62,6:62,7:26,8:28}
for s in steps:
 n=s['ordinal'];values=[]
 if n in mapping:values=json.loads(next(prior.glob(f'method-{mapping[n]:02d}-*-records.json')).read_text())
 if n==6:
  for r in values:
   v=r['data'];v.update(case='bounded-two-batch',fixtureStrokes=258,fixturePoints=8226)
   if v['stage']=='frame-complete':v['snapshotRows']=258;v['snapshotReads']=v['snapshotReads'][:2];v['snapshotReads'][1]['rows']=2
 if n>=9:
  values=json.loads((R/'android/verification/pr20-causal-results-20261014/extracted/method-13-C1-records.json').read_text())
  for r in values:r['data']['seed']=s['seed'];r['data']['fixture']['seed']=s['seed']
 raw='\n'.join(r['marker']+' '+json.dumps(r['data'],separators=(',',':')) for r in values)
 markers.safe_marker_payloads(s,raw,contract);raws[n]=raw
faults=0
for n in [3,4,5,6,8,9,10,11,12]:
 s=steps[n-1];raw=raws[n]
 for bad in ['',raw+'\n'+raw,raw+'\nNATIVE_UNEXPECTED {}',raw.replace('{','{"unexpected":1,',1),raw.replace('{','{"unknown":NaN,',1)]:reject(lambda:markers.safe_marker_payloads(s,bad,contract));faults+=1
for n in [9,10,11,12]:
 bad=raws[n].replace('"phase":"first"','"phase":"full"',1);reject(lambda:markers.safe_marker_payloads(steps[n-1],bad,contract));faults+=1
 wrong=raws[n].replace('"seed":'+str(steps[n-1]['seed']),'"seed":4103');reject(lambda:markers.safe_marker_payloads(steps[n-1],wrong,contract));faults+=1
check('actual historical marker fixtures and negative cases',dict(accepted=12,negatives=faults,new_native=0))
def junit_raw(s,body):
 c,m=s['selection'].split('#');block=f'INSTRUMENTATION_STATUS: class={c}\nINSTRUMENTATION_STATUS: test={m}\nINSTRUMENTATION_STATUS: numtests=1\n'
 return block+'INSTRUMENTATION_STATUS_CODE: 1\n'+body+'\n'+block+'INSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n'
for s in steps:
 e=copy.deepcopy(s);text=junit_raw(s,raws[s['ordinal']]);ns['raw_result'](e,text,0);assert e['junit_status']=='PASS'
 for fault in [text+'\nOK (1 test)\n',text.replace('numtests=1','numtests=2'),text+'\nFAILURES!!!\n',text.replace('INSTRUMENTATION_STATUS_CODE: 0','INSTRUMENTATION_STATUS_CODE: -3')]:
  e=copy.deepcopy(s);ns['raw_result'](e,fault,0);assert e['junit_status']=='FAIL'
check('strict JUnit state parser',dict(valid=12,invalid=48))
w=yaml.safe_load((H/'workflow.proposed.yml').read_text());job=w['jobs']['causal'];assert job['timeout-minutes']==60 and w['permissions']=={'contents':'read'} and job['runs-on']=='ubuntu-24.04'
assert '0000000000000000000000000000000000000000' in job['if'] and 'github.run_attempt == 1' in job['if']
count=0
for i,step in enumerate(job['steps']):
 if 'run'not in step:continue
 f=a.out/f'step-{i:02d}.sh';f.write_text(step['run']);subprocess.run(['bash','-n',f],check=True,capture_output=True)
 if 'run.py' in step['run']:count+=1;assert 'timeout --signal=TERM --kill-after=2' in step['run']
assert count==6
lint=P('/workspace/scratch/actionlint-1.7.12/actionlint');q=subprocess.run([lint,'-shellcheck=','-pyflakes=',H/'workflow.proposed.yml'],capture_output=True,text=True);(a.out/'actionlint.log').write_text(q.stdout+q.stderr);assert q.returncode==0,q.stderr
clockroot=a.out/'clock';clockroot.mkdir();envfile=clockroot/'env';envfile.touch();env=os.environ.copy();env.update(RUNNER_TEMP=str(clockroot),GITHUB_ENV=str(envfile));first=subprocess.run(['bash','-c',job['steps'][0]['run']],env=env,capture_output=True,text=True);assert first.returncode==0,first.stderr
clock=json.loads((clockroot/'pr20-raster-screen/evidence/clock.json').read_text());assert clock['deadlines_minutes']==[30,45,50,55,60]
again=subprocess.run(['bash','-c',job['steps'][0]['run']],env=env,capture_output=True,text=True);assert again.returncode!=0
check('inactive workflow syntax and one-use local clock',dict(actionlint=0,wrappers=6,clock_reuse_refused=True))
# Exercise the actual method failure finally with full original raw and normal-start suppression.
for mode in ['failure','normal_started_failure']:
 out=a.out/mode;out.mkdir();s=copy.deepcopy(steps[8]);s['status']='NOT_RUN';raw=junit_raw(s,raws[s['ordinal']]);real_forward=ns['forward_causal_records']
 report=dict(methods=[s],run_id='SYNTHETIC',run_attempt='1',workflow_sha='0'*40,install_groups={})
 ns.update(OUT=out,ROOT=out,PLAN=plan,REPORT=report,SPEC={'identities':{'C1':{'git':'1'*40}}},INPUT_HASHES={'source_spec_sha256':'0'*64,'method_plan_sha256':'0'*64},seconds=lambda limit=3600:limit,save=lambda:None,processes_after=lambda e:e.update(process_exit_outcome='NATURAL_EXIT'),digest=lambda p:sha(p.read_bytes()))
 def run(args,*pos,**kw):kw['stdout'].write(raw);return subprocess.CompletedProcess(args,1 if mode=='failure'else 0)
 ns['run']=run
 if mode=='normal_started_failure':
  def forward(entry,raw):entry['normal_forward_started']=True;raise RuntimeError('synthetic post-forward failure')
  ns['forward_causal_records']=forward;ns['common_fixtures']=lambda entry,records:None
 output=io.StringIO()
 with redirect_stdout(output):reject(lambda:ns['method'](s))
 lines=[json.loads(x[len(failure_text.PREFIX):]) for x in output.getvalue().splitlines() if x.startswith(failure_text.PREFIX)]
 assert s['status']=='FAIL' and (out/'method-09-C1-instrumentation.log').read_bytes()==raw.encode()
 if mode=='failure':assert failure_text.reconstruct_failure_text(lines,lines[0]['binding'])==raw.encode()
 else:assert len(lines)==1 and lines[0]['kind']=='REFUSED' and lines[0]['reason']=='NORMAL_FORWARD_ALREADY_STARTED'
 ns['forward_causal_records']=real_forward
check('actual method failure integration',dict(full_raw_roundtrip=True,normal_started_duplicate_refused=True,original_error_preserved=True))
# Real forward function: printing/context/END and post-forward cutoff faults must
# preserve the started latch and cannot add a second original-text stream.
for fault_at in ['CONTEXT','RECORD','END','after-forward-seconds']:
 out=a.out/('real-forward-'+fault_at);out.mkdir();s=copy.deepcopy(steps[8]);s['status']='NOT_RUN';raw=junit_raw(s,raws[9]);state={'end':False,'saved_started':False}
 apk={'package':'org.inkweft.app.a0','sha256':'2'*64,'source_apk_sha256':'2'*64,'signer_sha256':'3'*64}
 report=dict(methods=[s],run_id='SYNTHETIC',run_attempt='1',workflow_sha='0'*40,install_groups={s['install_group']:{'installed_apks':[apk]}})
 (out/'apk-C1.json').write_text(json.dumps({'source_manifest_sha256':'4'*64,'apks':[apk]}))
 for file in ['install-'+s['install_group']+'.log','install-'+s['install_group']+'-environment-before.json']:(out/file).write_text('{}')
 def save():
  if s.get('normal_forward_started'):state['saved_started']=True
 def seconds(limit=3600):
  if fault_at=='after-forward-seconds' and state['end']:raise ns['BudgetAbort']('synthetic after-forward cutoff')
  return limit
 real_print=print
 def fault_print(line,*args,**kwargs):
  if line.startswith('INKWEFT_CAUSAL_') and any(line.startswith('INKWEFT_CAUSAL_'+name+' ')for name in ['CONTEXT','RECORD','END']):
   assert state['saved_started'] and s['normal_forward_started'] is True
   if line.startswith('INKWEFT_CAUSAL_'+fault_at+' '):raise RuntimeError('synthetic actual forward print '+fault_at)
   if line.startswith('INKWEFT_CAUSAL_END '):state['end']=True
  real_print(line,*args,**kwargs)
 def run(args,*pos,**kw):kw['stdout'].write(raw);return subprocess.CompletedProcess(args,0)
 ns.update(OUT=out,ROOT=out,REPORT=report,PLAN=plan,run=run,save=save,seconds=seconds,print=fault_print,common_fixtures=lambda entry,records:None)
 output=io.StringIO()
 with redirect_stdout(output):reject(lambda:ns['method'](s))
 lines=output.getvalue().splitlines();failure_lines=[json.loads(x[len(failure_text.PREFIX):]) for x in lines if x.startswith(failure_text.PREFIX)]
 assert state['saved_started'] and s['status'] in ['FAIL','INCOMPLETE'] and len(failure_lines)==1 and failure_lines[0]['kind']=='REFUSED' and failure_lines[0]['reason']=='NORMAL_FORWARD_ALREADY_STARTED'
 assert (out/'method-09-C1-instrumentation.log').read_bytes()==raw.encode()
 ns.pop('print')
check('actual normal forwarding boundary faults',dict(cases=4,latch_saved_before_first_print=True,duplicate_original_streams=0))
result=size_bounds.calculate(contract);assert result['within_limit'];(a.out/'budget.json').write_text(json.dumps(result,indent=2)+'\n')
check('joint success/failure text cap',result)
(a.out/'checks.json').write_text(json.dumps(dict(status='HOST_STATIC_SYNTHETIC_PASS_NOT_NATIVE',checks=checks,controller_sha256={p.name:sha(p.read_bytes()) for p in H.iterdir()if p.is_file()}),indent=2)+'\n')
print(json.dumps({'checks':len(checks),'status':'HOST_STATIC_SYNTHETIC_PASS_NOT_NATIVE','out':str(a.out)}))
