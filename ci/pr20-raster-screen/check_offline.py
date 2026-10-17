"""Offline proposal checks only. No Gradle, SDK, ADB, network or ACL calls."""
import argparse,ast,copy,hashlib,io,json,math,os,pathlib,re,shutil,subprocess,sys,time,types,xml.etree.ElementTree as ET
from collections import Counter
from contextlib import redirect_stdout
import yaml
P=pathlib.Path;p=argparse.ArgumentParser();p.add_argument('--out',type=P,required=True);a=p.parse_args();assert not a.out.exists() and a.out.resolve().is_relative_to('/workspace/scratch');a.out.mkdir(parents=True)
H=P(__file__).resolve().parent;R=H.parents[1];sys.path.insert(0,str(H));import markers,failure_text,size_bounds
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
assert [s['ordinal'] for s in steps]==list(range(1,11))
assert Counter(s['phase'] for s in steps)=={'data-diagnostics':2,'raster-diagnostics':8}
assert len({s['install_group']for s in steps})==5
assert [(s['identity'],s['seed'])for s in steps[6:]]==[('C1',4101),('C2',4101),('C2',4102),('C1',4102)]
assert all(s['expected_junit_tests']==1 and s['outer_cap_seconds']==3600 for s in steps)
check('unbound refusal and exact source/method scope',dict(methods=10,groups=5,apks=5,cleanup_and_original_protocol_unchanged=True))
source=(H/'run.py').read_text();tree=ast.parse(source);oldtree=ast.parse((R/'ci/pr20-causal/run.py').read_text());ns={'Path':P,'re':re,'json':json,'hashlib':hashlib,'math':math,'Counter':Counter,'time':time,'os':os,'sys':sys,'shutil':shutil,'subprocess':subprocess,'ET':ET}
for n in tree.body:
 if isinstance(n,(ast.FunctionDef,ast.ClassDef)):exec(compile(ast.Module([n],type_ignores=[]),'candidate-controller-functions','exec'),ns)
unchanged=['write_json','save','seconds','digest','owned','run','capture','shell','phase','host','environment','source_manifest','official_dependency_check','validate_instrumentation_manifest','processes_after','junit']
for name in unchanged:
 current=next(n for n in tree.body if isinstance(n,ast.FunctionDef)and n.name==name);old=next(n for n in oldtree.body if isinstance(n,ast.FunctionDef)and n.name==name);assert ast.dump(current)==ast.dump(old),name
check('reviewed core functions preserved',unchanged)
# Only the user hard-stop gate is added to the existing clock validation.
current=next(n for n in tree.body if isinstance(n,ast.FunctionDef) and n.name=='validate_clock')
old=next(n for n in oldtree.body if isinstance(n,ast.FunctionDef) and n.name=='validate_clock')
assert len(current.body)==len(old.body)+2 and ast.dump(ast.Module(current.body[:-3]+current.body[-1:],type_ignores=[]))==ast.dump(ast.Module(old.body,type_ignores=[]))
check('hard stop extends unchanged clock validation',dict(user_hard_stop_epoch=1792281600,requires_full_hour_before_start=True))
ns.update(ADB=['adb','-s','emulator-5554'],PACKAGES=['org.inkweft.app.a0','org.inkweft.app.a0.test','org.inkweft.data.test'],MARKERS=contract)
# Real prior payloads are parser fixtures only; no native run is claimed.
prior=R/'android/verification/pr20-match-execution-20261014/extracted';raws={}; mapping={3:60,4:61,5:26,6:28}
for s in steps:
 n=s['ordinal'];values=[]
 if n in mapping:values=json.loads(next(prior.glob(f'method-{mapping[n]:02d}-*-records.json')).read_text())
 if n>=7:
  values=json.loads((R/'android/verification/pr20-causal-results-20261014/extracted/method-13-C1-records.json').read_text())
  for r in values:r['data']['seed']=s['seed'];r['data']['fixture']['seed']=s['seed']
 raw='\n'.join(r['marker']+' '+json.dumps(r['data'],separators=(',',':')) for r in values)
 markers.safe_marker_payloads(s,raw,contract);raws[n]=raw
faults=0
for n in [3,4,6,7,8,9,10]:
 s=steps[n-1];raw=raws[n]
 for bad in ['',raw+'\n'+raw,raw+'\nNATIVE_UNEXPECTED {}',raw.replace('{','{"unexpected":1,',1),raw.replace('{','{"unknown":NaN,',1)]:reject(lambda:markers.safe_marker_payloads(s,bad,contract));faults+=1
for n in [7,8,9,10]:
 bad=raws[n].replace('"phase":"first"','"phase":"full"',1);reject(lambda:markers.safe_marker_payloads(steps[n-1],bad,contract));faults+=1
 wrong=raws[n].replace('"seed":'+str(steps[n-1]['seed']),'"seed":4103');reject(lambda:markers.safe_marker_payloads(steps[n-1],wrong,contract));faults+=1
for s in steps:
 reject(lambda:markers.safe_marker_payloads(s,raws[s['ordinal']]+'\nHISTORY_DELETE_WRITER_CORRECTNESS {}',contract));faults+=1
check('actual historical marker fixtures and negative cases',dict(accepted=10,negatives=faults,new_native=0,unknown_history_marker_rejected_for_all_methods=True))
def junit_raw(s,body):
 c,m=s['selection'].split('#');block=f'INSTRUMENTATION_STATUS: class={c}\nINSTRUMENTATION_STATUS: test={m}\nINSTRUMENTATION_STATUS: numtests=1\n'
 return block+'INSTRUMENTATION_STATUS_CODE: 1\n'+body+'\n'+block+'INSTRUMENTATION_STATUS_CODE: 0\nINSTRUMENTATION_RESULT: stream=\nTime: 0.001\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n'
for s in steps:
 e=copy.deepcopy(s);text=junit_raw(s,raws[s['ordinal']]);ns['raw_result'](e,text,0);assert e['junit_status']=='PASS'
 for fault in [text+'\nOK (1 test)\n',text.replace('numtests=1','numtests=2'),text+'\nFAILURES!!!\n',text.replace('INSTRUMENTATION_STATUS_CODE: 0','INSTRUMENTATION_STATUS_CODE: -3')]:
  e=copy.deepcopy(s);ns['raw_result'](e,fault,0);assert e['junit_status']=='INCOMPLETE'
check('strict JUnit state parser',dict(valid=10,incomplete=40))
fixture_root=R/'android/verification/pr20-raster-screen-20261017'
meta=json.loads((fixture_root/'prior-raw-junit-failure.json').read_text());raw=(fixture_root/'prior-raw-junit-failure.txt').read_bytes();assert sha(raw)==meta['sha256'] and len(raw)==meta['bytes']
e={'selection':meta['selection']};ns['raw_result'](e,raw.decode(),0);assert e['junit_status']=='FAIL'
for text in ['',raw.decode()+'\nProcess crashed\n',raw.decode().replace('INSTRUMENTATION_CODE: -1','')]:
 e={'selection':meta['selection']};ns['raw_result'](e,text,0);assert e['junit_status']=='INCOMPLETE'
# The selected runner uses -r; a formatted -w-only success must never silently pass.
formatted=json.loads((R/'ci/fixtures/pan_continuity_run1.json').read_text())['cases'][0]
e={'selection':formatted['selection']};ns['raw_result'](e,formatted['raw'],0);assert e['junit_status']=='INCOMPLETE'
# Model real sendStatus(0) wrappers around unchanged emitted payloads.
s=steps[2];wrapped='\n'.join('INSTRUMENTATION_STATUS: stream='+line+'\nINSTRUMENTATION_STATUS_CODE: 0' for line in raws[3].splitlines());raw=junit_raw(s,wrapped)
e=copy.deepcopy(s);ns['raw_result'](e,raw,0);assert e['junit_status']=='PASS';assert len(markers.safe_marker_payloads(s,raw,contract))==4
for bad in [junit_raw(s,wrapped)+'\nProcess crashed\n',junit_raw(s,wrapped).replace('INSTRUMENTATION_STATUS_CODE: 1','INSTRUMENTATION_STATUS_CODE: 0',1)]:
 e=copy.deepcopy(s);ns['raw_result'](e,bad,0);assert e['junit_status']=='INCOMPLETE'
# Regressions from independent review: protocol order must be authoritative.
good=junit_raw(steps[0],'');end='INSTRUMENTATION_CODE: -1\n'
cls,method=steps[0]['selection'].split('#');fields=f'INSTRUMENTATION_STATUS: class={cls}\nINSTRUMENTATION_STATUS: test={method}\nINSTRUMENTATION_STATUS: numtests=1\n'
malformed=[end+good.replace(end,''),good+'INSTRUMENTATION_STATUS_CODE: 0\n',good+'INSTRUMENTATION_RESULT: shortMsg=java.lang.RuntimeException\n',good.replace(end,'INSTRUMENTATION_RESULT: shortMsg=java.lang.RuntimeException\n'+end),fields+fields+'INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\n'+end,good.replace('INSTRUMENTATION_STATUS_CODE: 1','INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: 0',1)]
malformed += [good.replace('OK (1 test)','OK (1 test)\nOK (not-a-count)'),good.replace('INSTRUMENTATION_RESULT: stream=\n',''), 'OK (1 test)\n'+good.replace('OK (1 test)\n',''),good.replace('INSTRUMENTATION_STATUS_CODE: 0','INSTRUMENTATION_STATUS: stack=java.lang.AssertionError\nINSTRUMENTATION_STATUS_CODE: 0'),good.replace('INSTRUMENTATION_STATUS_CODE: 1','INSTRUMENTATION_STATUS: stack=java.lang.AssertionError\nINSTRUMENTATION_STATUS_CODE: 1')]
for bad in malformed:
 e=copy.deepcopy(steps[0]);ns['raw_result'](e,bad,0);assert e['junit_status']=='INCOMPLETE'
check('real raw failure and emitted status-stream fixtures',dict(real_failure=1,incomplete=17,status_stream=1,native_executions=0))
w=yaml.safe_load((H/'workflow.proposed.yml').read_text());job=w['jobs']['causal'];assert job['timeout-minutes']==60 and w['permissions']=={'contents':'read'} and job['runs-on']=='ubuntu-24.04'
assert '0000000000000000000000000000000000000000' in job['if'] and 'github.run_attempt == 1' in job['if']
count=0
for i,step in enumerate(job['steps']):
 if 'run'not in step:continue
 f=a.out/f'step-{i:02d}.sh';f.write_text(step['run']);subprocess.run(['bash','-n',f],check=True,capture_output=True)
 if 'run.py' in step['run']:count+=1;assert 'timeout --signal=TERM --kill-after=2' in step['run']
assert count==6
lint=shutil.which('actionlint');actionlint_status='NOT_RUN_TOOL_UNAVAILABLE'
if lint:
 q=subprocess.run([lint,'-shellcheck=','-pyflakes=',H/'workflow.proposed.yml'],capture_output=True,text=True);(a.out/'actionlint.log').write_text(q.stdout+q.stderr);assert q.returncode==0,q.stderr;actionlint_status='PASS'
clockroot=a.out/'clock';clockroot.mkdir();envfile=clockroot/'env';envfile.touch();env=os.environ.copy();env.update(RUNNER_TEMP=str(clockroot),GITHUB_ENV=str(envfile));first=subprocess.run(['bash','-c',job['steps'][0]['run']],env=env,capture_output=True,text=True);assert first.returncode==0,first.stderr
clock=json.loads((clockroot/'pr20-raster-screen/evidence/clock.json').read_text());assert clock['deadlines_minutes']==[30,45,50,55,60]
again=subprocess.run(['bash','-c',job['steps'][0]['run']],env=env,capture_output=True,text=True);assert again.returncode!=0
check('inactive workflow syntax and one-use local clock',dict(actionlint=actionlint_status,wrappers=6,clock_reuse_refused=True))
# Exercise the actual method failure finally with full original raw and normal-start suppression.
for mode in ['failure','normal_started_failure']:
 out=a.out/mode;out.mkdir();s=copy.deepcopy(steps[6]);s['status']='NOT_RUN';raw=junit_raw(s,raws[s['ordinal']]);real_forward=ns['forward_causal_records']
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
 assert s['status'] in ['FAIL','INCOMPLETE'] and (out/'method-07-C1-instrumentation.log').read_bytes()==raw.encode()
 if mode=='failure':assert failure_text.reconstruct_failure_text(lines,lines[0]['binding'])==raw.encode()
 else:assert len(lines)==1 and lines[0]['kind']=='REFUSED' and lines[0]['reason']=='NORMAL_FORWARD_ALREADY_STARTED'
 ns['forward_causal_records']=real_forward
check('actual method failure integration',dict(full_raw_roundtrip=True,normal_started_duplicate_refused=True,original_error_preserved=True))
# Real forward function: printing/context/END and post-forward cutoff faults must
# preserve the started latch and cannot add a second original-text stream.
for fault_at in ['CONTEXT','RECORD','END','after-forward-seconds']:
 out=a.out/('real-forward-'+fault_at);out.mkdir();s=copy.deepcopy(steps[6]);s['status']='NOT_RUN';raw=junit_raw(s,raws[7]);state={'end':False,'saved_started':False}
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
 assert (out/'method-07-C1-instrumentation.log').read_bytes()==raw.encode()
 ns.pop('print')
check('actual normal forwarding boundary faults',dict(cases=4,latch_saved_before_first_print=True,duplicate_original_streams=0))
result=size_bounds.calculate(contract);assert result['within_limit'];(a.out/'budget.json').write_text(json.dumps(result,indent=2)+'\n')
check('joint success/failure text cap',result)
# Source/readiness preparation remains incapable of launching a native run.
scope=json.loads((H/'source-scope.template.json').read_text())
assert re.fullmatch('[0-9a-f]{40}',scope['identities']['C1']) and scope['identities']['C2']=='01011eacec7d0d6411703631f49fb64f2f9e7aab'
assert subprocess.check_output(['git','-C',R,'rev-parse',json.loads((H/'prior-correctness-reuse.json').read_text())['local_tree_equivalent_commit']+'^{tree}']).decode().strip()==scope['candidate_tree']
for name,expected in {**scope['common_tests'],**scope['pan_production_sha256']}.items():assert sha((R/name).read_bytes())==expected,name
candidate=(R/scope['control_path']).read_text();assert sha(candidate.encode())==scope['candidate_sha256']
branch='''else if(reused.isEmpty()){
                // The ordered-ID check above already pairs every decoded stroke with its
                // spatial identity. A cold batch needs no hash map or reordered copy.
                snapshot.strokes.forEachIndexed{index,stroke->cache.put(stroke,batch[index].spatialKey)}
                visit(snapshot.strokes)
            }else{'''
assert candidate.count(branch)==1 and sha(candidate.replace(branch,'else{').encode())==scope['control_sha256']
assert sha((H/'prior-correctness-reuse.json').read_bytes())==scope['prior_correctness_reuse_sha256']
reuse=json.loads((H/'prior-correctness-reuse.json').read_text());assert len(reuse['omitted_methods'])==2
assert not {x['selection'] for x in reuse['omitted_methods']} & {x['selection'] for x in steps}
for name,item in reuse['evidence_files'].items():
 data=(R/'android/verification/pr20-raster-screen-20261017'/P(name).name).read_bytes();assert len(data)==item['bytes'] and sha(data)==item['sha256']
assert subprocess.check_output(['git','-C',R,'diff','--name-only',reuse['prior_fixed_commit'],'HEAD','--','android',':(exclude)android/verification'])==b''
q=subprocess.run([sys.executable,H/'freeze_readiness.py','--repo',R,'--out',a.out/'source-freeze'],capture_output=True,text=True);assert q.returncode==0,q.stderr
q=subprocess.run([sys.executable,a.out/'source-freeze/prepare_sources.py','--repo',R,'--out',a.out/'reconstructed-sources'],capture_output=True,text=True);assert q.returncode==0,q.stderr
assert json.loads((H/'authorization.json').read_text())['approved'] is False
assert 'UNBOUND' in (H/'run.py').read_text() and 'UNBOUND' in (H/'package.py').read_text()
check('exact-source prior reuse and unbound native refusal',dict(omitted_prior_passes=2,new_native=0,control=scope['identities']['C1'],source_pair_reconstructed=True,candidate_tree=scope['candidate_tree']))
(a.out/'checks.json').write_text(json.dumps(dict(status='HOST_STATIC_SYNTHETIC_PASS_NOT_NATIVE',checks=checks,controller_sha256={p.name:sha(p.read_bytes()) for p in H.iterdir()if p.is_file()}),indent=2)+'\n')
print(json.dumps({'checks':len(checks),'status':'HOST_STATIC_SYNTHETIC_PASS_NOT_NATIVE','out':str(a.out)}))
