"""Offline faults only. No network, Gradle, ADB, emulator or permission operations."""
import argparse,ast,copy,zipfile,hashlib,io,json,math,os,re,shutil,subprocess,sys,time,types,xml.etree.ElementTree as ET
from collections import Counter
from contextlib import redirect_stdout
from pathlib import Path
import yaml
p=argparse.ArgumentParser();p.add_argument('--out',required=True,type=Path);p.add_argument('--controller',type=Path,default=Path(__file__).resolve().parent);p.add_argument('--readiness',type=Path);p.add_argument('--repo',type=Path,default=Path('/workspace/InkWeft'));a=p.parse_args();here=a.controller.resolve()
assert a.out.resolve().is_relative_to('/workspace/scratch') and not a.out.exists();a.out.mkdir(parents=True)
sys.dont_write_bytecode=True
sys.path.insert(0,str(here));import markers,size_bounds
checks=[]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def reject(f):
 try:f()
 except (AssertionError,ValueError,TypeError,KeyError,RecursionError,RuntimeError):return
 raise AssertionError('Fault accepted')
def check(name,f):
 value=f();checks.append({'name':name,'status':'PASS','details':value})
plan=json.loads(((a.readiness/'method-plan.json')if a.readiness else (here/'method-plan.template.json')).read_text());contract=json.loads(((a.readiness/'marker-contract.json')if a.readiness else (here/'marker-contract.template.json')).read_text());contract['status']='FROZEN'
source=(here/'run.py').read_text();tree=ast.parse(source)
ns={'re':re,'json':json,'math':math,'Counter':Counter,'Path':Path,'hashlib':hashlib,'time':time,'os':os,'subprocess':subprocess,'ET':ET,'sys':sys,'shutil':shutil,'zipfile':zipfile}
for n in tree.body:
 if isinstance(n,(ast.FunctionDef,ast.ClassDef)):exec(compile(ast.Module([n],type_ignores=[]),'actual-run-ast','exec'),ns)
ns.update(MARKERS=contract,ADB=['adb','-s','emulator-5554'],PACKAGES=['org.inkweft.app.a0','org.inkweft.app.a0.test','org.inkweft.data.test'])
old=Path('/workspace/InkWeft/ci/pr20-search-screen')
def static():
 for f in here.glob('*.py'):ast.parse(f.read_text())
 assert (here/'cleanup.py').read_bytes()==(old/'cleanup.py').read_bytes()
 assert 'assert re.fullmatch' in source
 if a.readiness:
  auth=json.loads((here/'authorization.json').read_text());assert auth['approved'] is True and auth['status']=='FROZEN'
  for key,file in [('approved_plan_sha256','PLAN.md'),('source_spec_sha256','source-spec.json'),('method_plan_sha256','method-plan.json'),('original_protocol_sha256','original-protocol.json'),('marker_contract_sha256','marker-contract.json'),('prepare_sources_sha256','prepare_sources.py')]:
   assert auth[key]==sha(a.readiness/file)
   assert subprocess.check_output(['git','-C',str(a.repo),'show',auth['plan_git']+':android/verification/pr20-save-boundary-uuid-20261014/'+file])==(a.readiness/file).read_bytes()
  assert auth['source_spec_sha256'] in source
 else:
  assert "FROZEN_SOURCE_SPEC_SHA256 = 'UNBOUND'" in source
  result=subprocess.run([sys.executable,here/'run.py','setup'],capture_output=True,text=True);assert result.returncode!=0 and 'Finalize source identity' in result.stderr
  auth=json.loads((here/'authorization.json').read_text());assert auth['approved'] is False and auth['status']=='UNBOUND' and auth['plan_git']=='UNBOUND'
  result=subprocess.run([sys.executable,here/'package.py'],capture_output=True,text=True);assert result.returncode!=0 and 'Finalize source-spec hash' in result.stderr
 assert '--no-build-cache' in source and ':data-local:assembleDebugAndroidTest' in source and ':app:assemble' not in source
 assert 'maven.ghostscript.com' not in source and 'official_dependency_check' not in source
 assert [s['ordinal']for s in plan['steps']]==list(range(1,11)) and Counter(s['phase']for s in plan['steps'])=={'data-diagnostics':10}
 assert len({s['install_group']for s in plan['steps']})==1 and {s['identity']for s in plan['steps']}=={'C1'}
 assert [s['ordinal']for s in plan['steps']if s['outer_cap_seconds']==300]==[10]
 assert [s['ordinal']for s in plan['steps']if s['marker_policy']=='none']==list(range(1,10))
 oldtree=ast.parse((old/'run.py').read_text());copied=['write_json','save','seconds','digest','owned','run','capture','shell','phase','host','environment','source_manifest','junit','raw_result']
 # raw_result deliberately adds RASTER to marker discovery; strict JUnit unchanged.
 for name in copied[:-1]:
  now=next(n for n in tree.body if isinstance(n,ast.FunctionDef)and n.name==name);before=next(n for n in oldtree.body if isinstance(n,ast.FunctionDef)and n.name==name);assert ast.dump(now)==ast.dump(before),name
 proc=ast.unparse(next(n for n in tree.body if isinstance(n,ast.FunctionDef)and n.name=='processes_after'))
 assert 'force-stop' not in re.findall(r"'([^']+)'",proc) and "'kill'" not in proc
 return {'methods':10,'groups':1,'source_identities':1,'apk_count':1,'execution_binding':'REAL_READINESS_MATCH' if a.readiness else 'UNBOUND_REFUSED','copied_functions':copied[:-1],'cleanup_byte_identical':True}
check('unbound isolation and exact scope',static)
def workflow():
 value=yaml.safe_load((here/'workflow.proposed.yml').read_text());job=value['jobs']['causal'];assert job['timeout-minutes']==60 and job['runs-on']=='ubuntu-24.04' and value['permissions']=={'contents':'read'}
 assert 'github.run_attempt == 1' in job['if'] and 'github.event.created == false' in job['if'] and len(re.findall('[0-9a-f]{40}',job['if']))==1
 if not a.readiness:assert '0'*40 in job['if'], 'Inactive draft prep-parent guard must stay zero'
 count=0
 for i,step in enumerate(job['steps']):
  if 'run'not in step:continue
  f=a.out/f'step-{i:02}.sh';f.write_text(step['run']);subprocess.run(['bash','-n',f],check=True,capture_output=True)
  for _,code in re.findall(r"python3 - <<'([^']+)'\n(.*?)\n\1",step['run'],re.S):ast.parse(code)
  if 'run.py' in step['run']:assert 'timeout --signal=TERM --kill-after=2' in step['run'];count+=1
 assert count==4
 lint=subprocess.run(['/workspace/scratch/actionlint-1.7.12/actionlint','-shellcheck=','-pyflakes=',here/'workflow.proposed.yml'],capture_output=True,text=True);(a.out/'actionlint.log').write_text(lint.stdout+lint.stderr);assert lint.returncode==0,lint.stderr
 root=a.out/'clock';root.mkdir();envfile=root/'env';envfile.touch();env=os.environ.copy();env.update(RUNNER_TEMP=str(root),GITHUB_ENV=str(envfile))
 command=job['steps'][0]['run'];first=subprocess.run(['bash','-c',command],env=env,capture_output=True,text=True);assert first.returncode==0,first.stderr
 values=dict(line.split('=',1)for line in envfile.read_text().splitlines());assert len(values)==8 and all('\\n'not in x for x in values.values())
 clock=json.loads((root/'pr20-save-boundary-uuid/evidence/clock.json').read_text());assert clock['deadlines_minutes']==[30,45,55,60] and clock['scope']=='DIAGNOSTIC_ONLY'
 before=envfile.read_bytes();again=subprocess.run(['bash','-c',command],env=env,capture_output=True,text=True);assert again.returncode!=0 and envfile.read_bytes()==before
 return {'actionlint':0,'phase_wrappers':4,'actual_clock_lines':8,'second_clock_rejected':True}
check('workflow syntax and isolated real first-step clock',workflow)
def budget():
 fake=types.SimpleNamespace(time=lambda:1000.0);ns.update(time=fake,START=1000,PHASE='setup')
 for name in ['CUTOFFS','DEADLINE']:
  n=next(n for n in tree.body if isinstance(n,ast.Assign)and any(isinstance(t,ast.Name)and t.id==name for t in n.targets));exec(compile(ast.Module([n],type_ignores=[]),'actual-cutoffs','exec'),ns)
 assert ns['CUTOFFS']=={'setup':2800,'data':3700,'work':4300}
 for phase,end in ns['CUTOFFS'].items():
  ns['PHASE']=phase
  for offset in [-.01,0,.01]:
   fake.time=lambda end=end,offset=offset:end+offset
   if offset<0:assert 0<ns['seconds']()<=.011
   else:reject(ns['seconds'])
 ns['time']=time;return {'phase_boundaries':9}
check('phase/work absolute deadline boundaries',budget)
def rawchecks():
 raw=Path('/workspace/scratch/pr20-candidate3-results-20261014/raw');prior=json.loads((raw/'summary.json').read_text())
 for e in prior['methods']:
  text=(raw/f"method-{e['ordinal']:02}-{e['identity']}-instrumentation.log").read_text();test=copy.deepcopy(e);ns['raw_result'](test,text,0);assert test['junit_status']=='PASS'
 e=prior['methods'][0];text=(raw/f"method-{e['ordinal']:02}-{e['identity']}-instrumentation.log").read_text()
 faults=[(text,1),(text+'\nOK (1 test)\n',0),(text.replace('OK (1 test)','OK (2 tests)'),0),(text+'\nINSTRUMENTATION_STATUS_CODE: 0junk\n',0),(text+'\nINSTRUMENTATION_CODE: -1junk\n',0),(text.replace('INSTRUMENTATION_CODE: -1',''),0),(text.replace('INSTRUMENTATION_STATUS_CODE: 0','INSTRUMENTATION_STATUS_CODE: -3',1),0),(text+'\nFAILURES!!!\n',0),(text.replace('numtests=1','numtests=2'),0),(text[:text.index('OK (1 test)')],None)]
 for value,rc in faults:
  test=copy.deepcopy(e);ns['raw_result'](test,value,rc);assert test['junit_status']!='PASS'
 return {'prior_raw_junit':58,'failure_or_skipped_variants':len(faults),'new_native':0}
check('strict original JUnit parser and skipped/fault cases',rawchecks)

def specimen(s):
 if 'enum'in s:return s['enum'][0]
 if 'const'in s:return s['const']
 kind=s['type']
 if isinstance(kind,list) and 'null'in kind:return None
 if kind=='object':return {k:specimen(s['properties'][k])for k in s['required']}
 if kind=='array':return [specimen(s['items'])for _ in range(s['minItems'])]
 if kind=='string':return '0'*64 if '64' in s['pattern'] else ('SCAN n' if 'MATERIALIZE'in s['pattern'] else '')
 if kind in ['integer','number']:return s.get('minimum',0)
 if kind=='boolean':return False
 return None

def fixtures():
 out={step['ordinal']:'' for step in plan['steps']};S=contract['schemas'];records=[]
 for mode in ['IDLE','DIRECT','OBSERVE']:
  r=specimen(S['LIBRARY_SAVE_BOUNDARY_RESULT']);r.update(mode=mode,readiness={'IDLE':'fixture-ready','DIRECT':'first-source-check','OBSERVE':'first-index-commit'}[mode],startRevision=32 if mode=='OBSERVE'else 1)
  events=[]
  for sample,save in enumerate(r['saves']):
   start=sample*10000;save.update(sample=sample,startNs=start,endNs=start+100,elapsedMs=.0001,revision=r['startRevision']+sample+1)
   stages=['save-request','writer-submit','writer-run-enter','room-begin-enter','sqlite-begin-enter','sqlite-begin-exit','room-begin-exit','save-writer-acquired','save-head-read','save-head-written','save-revision-written','save-receipt-written','room-success-enter','sqlite-success-enter','sqlite-success-exit','room-success-exit','room-end-enter','sqlite-end-enter','sqlite-end-exit','room-end-exit','save-returned','writer-run-exit']
   for offset,stage in enumerate(stages,1):
    transaction=sample*10+(2 if stage.startswith('room-')else 3) if stage.startswith(('room-','sqlite-'))else 0
    depth=1 if transaction else 0;thread=1 if stage in ['save-request','save-returned','writer-submit']else 2
    success=any(stage.endswith(suffix)for suffix in ['success-exit','end-enter','end-exit'])
    outcome='entered'if stage.endswith('-enter')or stage=='writer-submit'else 'returned'
    e=specimen(S['LIBRARY_SAVE_BOUNDARY_EVENTS']['properties']['events']['items']);e.update(stage=stage,sample=sample,task=sample+1,transaction=transaction,depth=depth,threadId=thread,successRequested=success,wallNs=start+offset,threadCpuNs=start+offset,outcome=outcome);events.append(e)
  records.append(('LIBRARY_SAVE_BOUNDARY_RESULT',r))
  for part,offset in enumerate(range(0,len(events),96)):
   records.append(('LIBRARY_SAVE_BOUNDARY_EVENTS',dict(schema=1,mode=mode,part=part,events=events[offset:offset+96])))
  reads=[]
  if mode!='IDLE':
   read=specimen(S['LIBRARY_SAVE_BOUNDARY_READS']['properties']['events']['items']);read.update(owner='control' if mode=='DIRECT'else 'index',stage='direct-start' if mode=='DIRECT'else 'committed');reads=[read]
   if mode=='DIRECT':
    end_read=copy.deepcopy(read);end_read.update(stage='direct-end',wallNs=999999,threadCpuNs=999999);reads.append(end_read)
   if mode=='OBSERVE':read['count']=64
   records.append(('LIBRARY_SAVE_BOUNDARY_READS',dict(schema=1,mode=mode,part=0,events=reads)))
  end=specimen(S['LIBRARY_SAVE_BOUNDARY_END']);end.update(mode=mode,recordedEvents=len(events),recordedReadEvents=len(reads));records.append(('LIBRARY_SAVE_BOUNDARY_END',end))
 out[10]='\n'.join(marker+' '+json.dumps(v,separators=(',',':'))for marker,v in records)
 return out
fixture_raw=fixtures()
def markerchecks():
 for e in plan['steps']:markers.safe_marker_payloads(e,fixture_raw[e['ordinal']],contract)
 e=plan['steps'][9];valid=fixture_raw[10]
 negative=[valid+'\nLIBRARY_SAVE_BOUNDARY_RESULT []',valid+'\nLIBRARY_SAVE_BOUNDARY_RESULT {',valid+'\nLIBRARY_SAVE_BOUNDARY_RESULT',valid+'\nHISTORY_ARBITRARY []',valid.replace('"schema":1','"schema":NaN',1),valid.replace('"schema":1','"schema":1,"schema":1',1),valid.replace('"seed":4101','"seed":4102',1),valid.replace('"schema":1','"schema":1,"secret":"arbitrary"',1),'',valid+'\n'+valid,valid.replace('"droppedEvents":0','"droppedEvents":1',1),valid.replace('"recordedReadEvents":1','"recordedReadEvents":2',1),valid.replace('"sample":0','"sample":true',1),valid.replace('"part":0','"part":1',1),valid.replace('"recordedEvents":660','"recordedEvents":661',1),valid.replace('"startRevision":32','"startRevision":31',1),valid.replace('"wallNs":4','"wallNs":99',1)]
 for raw in negative:reject(lambda raw=raw:markers.safe_marker_payloads(e,raw,contract))
 reject(lambda:markers.safe_marker_payloads(plan['steps'][0],valid,contract))
 # Test background fragments independently of author completeness. Whole-method framing
 # still accounts exactly for the inserted event; no real evidence is changed.
 def added_background(stage):
  blocks=[(line.split(' ',1)[0],json.loads(line.split(' ',1)[1]))for line in valid.splitlines()]
  part=next(v for m,v in blocks if m=='LIBRARY_SAVE_BOUNDARY_EVENTS'and v['mode']=='IDLE'and v['part']==6)
  e=copy.deepcopy(part['events'][-1]);e.update(sample=-1,task=99999,transaction=0,stage=stage,wallNs=999999,outcome='entered'if stage=='writer-submit'else 'returned')
  part['events'].append(e)
  end=next(v for m,v in blocks if m=='LIBRARY_SAVE_BOUNDARY_END'and v['mode']=='IDLE');end['recordedEvents']+=1
  return '\n'.join(m+' '+json.dumps(v,separators=(',',':'))for m,v in blocks)
 markers.safe_marker_payloads(e,added_background('writer-run-exit'),contract)
 reject(lambda:markers.safe_marker_payloads(e,added_background('writer-submit'),contract))
 def change_author(transform):
  blocks=[(line.split(' ',1)[0],json.loads(line.split(' ',1)[1]))for line in valid.splitlines()]
  author=[event for marker,v in blocks if marker=='LIBRARY_SAVE_BOUNDARY_EVENTS'and v['mode']=='IDLE'for event in v['events']]
  transform(author)
  start=next(i for i,(marker,v)in enumerate(blocks)if marker=='LIBRARY_SAVE_BOUNDARY_EVENTS'and v['mode']=='IDLE')
  blocks=[(marker,v)for marker,v in blocks if not(marker=='LIBRARY_SAVE_BOUNDARY_EVENTS'and v['mode']=='IDLE')]
  parts=[('LIBRARY_SAVE_BOUNDARY_EVENTS',dict(schema=1,mode='IDLE',part=part,events=author[offset:offset+96]))for part,offset in enumerate(range(0,len(author),96))]
  blocks[start:start]=parts
  end=next(v for marker,v in blocks if marker=='LIBRARY_SAVE_BOUNDARY_END'and v['mode']=='IDLE');end['recordedEvents']=len(author)
  return '\n'.join(marker+' '+json.dumps(v,separators=(',',':'))for marker,v in blocks)
 def selected(events,stage):return next(e for e in events if e['sample']==0 and e['stage']==stage)
 late=change_author(lambda events:selected(events,'writer-run-exit').update(wallNs=101,threadCpuNs=101))
 markers.safe_marker_payloads(e,late,contract)
 def add_failed_author(events):
  failed=copy.deepcopy(selected(events,'save-returned'));failed['stage']='save-failed'
  events.insert(events.index(selected(events,'save-returned')),failed)
 false_complete=change_author(add_failed_author)
 reject(lambda:markers.safe_marker_payloads(e,false_complete,contract))
 required=[event['stage']for event in [data for marker,data in [(line.split(' ',1)[0],json.loads(line.split(' ',1)[1]))for line in valid.splitlines()]if marker=='LIBRARY_SAVE_BOUNDARY_EVENTS'and data['mode']=='IDLE'for data in data['events']]if event['sample']==0]
 # Delete every planned endpoint while recomputing all part and END counts: each must fail.
 for stage in required:
  missing=change_author(lambda events,stage=stage:events.remove(selected(events,stage)))
  reject(lambda missing=missing:markers.safe_marker_payloads(e,missing,contract))
 for stage in required:
  duplicate=change_author(lambda events,stage=stage:events.insert(events.index(selected(events,stage))+1,copy.deepcopy(selected(events,stage))))
  reject(lambda duplicate=duplicate:markers.safe_marker_payloads(e,duplicate,contract))
 variants=[('room-end-enter','threadId',3),('sqlite-end-enter','transaction',999),('save-head-written','task',999),('sqlite-begin-enter','depth',2),('save-revision-written','threadCpuNs',0),('sqlite-end-exit','wallNs',2),('room-success-exit','successRequested',False),('sqlite-end-exit','outcome','threw')]
 for stage,field,value in variants:
  bad=change_author(lambda events,stage=stage,field=field,value=value:selected(events,stage).update({field:value}))
  reject(lambda bad=bad:markers.safe_marker_payloads(e,bad,contract))
 blocks=[(line.split(' ',1)[0],json.loads(line.split(' ',1)[1]))for line in valid.splitlines()]
 for marker,data in blocks:
  if marker=='LIBRARY_SAVE_BOUNDARY_READS'and data['mode']=='DIRECT':data['events']=[event for event in data['events']if event['stage']!='direct-end']
  if marker=='LIBRARY_SAVE_BOUNDARY_END'and data['mode']=='DIRECT':data['recordedReadEvents']=1
 missing_end='\n'.join(marker+' '+json.dumps(data,separators=(',',':'))for marker,data in blocks)
 reject(lambda:markers.safe_marker_payloads(e,missing_end,contract))
 for count in [0,-1]:
  blocks=[(line.split(' ',1)[0],json.loads(line.split(' ',1)[1]))for line in valid.splitlines()]
  for marker,data in blocks:
   if marker=='LIBRARY_SAVE_BOUNDARY_READS'and data['mode']=='OBSERVE':
    for event in data['events']:
     if event['owner']=='index'and event['stage']=='committed':event['count']=count
  no_commit='\n'.join(marker+' '+json.dumps(data,separators=(',',':'))for marker,data in blocks)
  reject(lambda no_commit=no_commit:markers.safe_marker_payloads(e,no_commit,contract))
 wrong_initial_hash=valid.replace(contract['expected_initial_fixture']['logical_sha256'],'0'*64)
 assert wrong_initial_hash!=valid
 reject(lambda:markers.safe_marker_payloads(e,wrong_initial_hash,contract))
 for marker,schema in contract['schemas'].items():
  v=specimen(schema);v['unexpected']='bad';reject(lambda:markers.validate_shape(v,schema))
 return {'synthetic_complete_methods':10,'whole_method_fault_cases':len(negative)+7,'author_endpoint_deleted_and_recounted':len(required),'author_endpoint_duplicated_and_recounted':len(required),'thread_task_txn_depth_cpu_order_faults':len(variants),'background_fragment_positive':1,'run_exit_after_return_positive':1,'closed_object_schemas':len(contract['schemas']),'native_results':0}
check('closed boundary schemas and whole-method loss/order/count fault refusal',markerchecks)
def fixture_source_contracts():
 counts=contract['schemas']['LIBRARY_SAVE_BOUNDARY_RESULT']['properties']['fixture']['properties']['rowCounts']
 binding=contract['expected_initial_fixture'];assert binding['seed']==4101
 assert sha(a.repo/binding['record_path'])==binding['record_sha256']
 original=json.loads((a.repo/binding['record_path']).read_text())
 recorded=[r['data']for r in original if r['marker']=='KVM_PAIR_FIXTURE'];assert len(recorded)==1 and recorded[0]['seed']==4101
 expected=contract['schemas']['LIBRARY_SAVE_BOUNDARY_RESULT']['properties']['fixture']['properties']['logicalSha256']['const']
 assert expected==binding['logical_sha256']==recorded[0]['logicalSha256']
 actual={'notes':501,'note_revisions':501,'notebook_workspace':501,'notebook_pages':20001,'page_search_text':20000,'library_search_pending':20000}
 markers.validate_shape(actual,counts);reject(lambda:markers.validate_shape(dict(actual,notebook_pages=20000),counts))
 source_root=a.repo/'android/data-local/src/main/kotlin/org/inkweft/data'
 enum=contract['schemas']['LIBRARY_SAVE_BOUNDARY_READS']['properties']['events']['items']['properties']['stage']
 stages={'direct-start','direct-end','committed'}
 for file in ['LibrarySearchRepository.kt','LibrarySearchObservation.kt']:
  stages.update(re.findall(r'"((?:observe-|read-|connection-|snapshot-|index-|drafts-|scope-|hits-|page-|card-|metadata-|pending-)[a-z-]+)"',(source_root/file).read_text()))
 stages.update(re.findall(r'step\?\.invoke\("([a-z-]+)"',(source_root/'LibraryTextIndex.kt').read_text()))
 for name in stages:markers.validate_shape(name,enum)
 for rule in contract['methods'].values():
  assert sha(a.repo/rule['test_source_path'])==rule['test_source_sha256'], 'Source changed since draft schema; review/hash again before freeze'
 for path,expected in contract['reviewed_source_sha256'].items():assert sha(a.repo/path)==expected, 'Source differs from compile2/review binding: '+path
 source=(a.repo/contract['methods']['10']['test_source_path']).read_text()
 assert 'capacity:Int=4096'in source and '<=2048'in source and 'recordedReadEvents'in source and 'chunked(96)'in source
 return {'source_hashes_match':True,'fixture_pages':20001,'actual_stage_literals':len(stages),'boundary_capacity_per_arm':4096,'read_capacity_per_arm':2048}
check('actual fixture, source hashes, capacities and stage allowlist',fixture_source_contracts)

def processchecks():
 saved={k:ns.get(k)for k in ['run','time','seconds']};cases=[]
 for mode in ['gone','app_alive','test_alive','data_alive','transport']:
  clock=[0.0];calls=[];ns['time']=types.SimpleNamespace(monotonic=lambda:clock[0],sleep=lambda x:clock.__setitem__(0,clock[0]+max(x,.001)))
  def run(args,*pos,**kw):
   calls.append(args);clock[0]+=.01;package=args[-1];alive=(mode=='app_alive'and package=='org.inkweft.app.a0')or(mode=='test_alive'and package=='org.inkweft.app.a0.test')or(mode=='data_alive'and package=='org.inkweft.data.test')
   return subprocess.CompletedProcess(args,0 if alive else 1,'2717'if alive else '','bad'if mode=='transport'else'')
  ns.update(run=run,seconds=lambda limit=3600:limit);e={}
  if mode=='gone':ns['processes_after'](e);assert e['process_exit_outcome']=='NATURAL_EXIT'
  else:reject(lambda:ns['processes_after'](e));assert e['process_exit_outcome']=='UNCONFIRMED'
  assert all(args[3:5]==['shell','pidof']for args in calls);cases.append(mode)
 ns.update(saved);return cases
check('all-three package natural isolation and no cleanup fallback',processchecks)


def manifestchecks():
 xml=Path('/workspace/scratch/pr20-search-screen-native-20261014/local-aapt-xmltree.log').read_text()
 assert ns['validate_instrumentation_manifest'](xml,'org.inkweft.data.test')['targetPackage']=='org.inkweft.data.test'
 app=xml.replace('org.inkweft.data.test','org.inkweft.app.a0')
 assert ns['validate_instrumentation_manifest'](app,'org.inkweft.app.a0')['targetPackage']=='org.inkweft.app.a0'
 for value,target in [(xml,'org.inkweft.app.a0'),(app,'org.inkweft.data.test'),(xml.replace('androidx.test.runner.AndroidJUnitRunner','bad.Runner'),'org.inkweft.data.test'),(xml.replace('E: instrumentation','E: other'),'org.inkweft.data.test')]:
  reject(lambda value=value,target=target:ns['validate_instrumentation_manifest'](value,target))
 return {'prior_actual_data_manifest':1,'synthetic_app_target':1,'mismatches':4}
check('instrumentation manifest uses xmltree and exact target package',manifestchecks)


def buildchecks():
 saved={k:ns.get(k)for k in ['ROOT','OUT','SDK','SPEC','phase','capture','source_manifest','digest','seconds']}
 original_gradle=os.environ.get('GRADLE_USER_HOME');cases=[]
 try:
  for case in ['match','missing_apk_abi','different_aar_native','wrong_aar_version']:
   root=a.out/('build-'+case);out=root/'evidence';out.mkdir(parents=True)
   android=root/'sources/C1/android';apk=android/'data-local/build/outputs/apk/androidTest/debug/data-local-debug-androidTest.apk';apk.parent.mkdir(parents=True)
   cache=root/'gradle/caches/modules-2/files-2.1';version='3.48.0'if case=='wrong_aar_version'else '3.49.0'
   aar=cache/('com.github.requery/sqlite-android/'+version+'/synthetic/sqlite-android-'+version+'.aar');aar.parent.mkdir(parents=True)
   abis=['arm64-v8a','armeabi-v7a','x86','x86_64']
   with zipfile.ZipFile(apk,'w')as target,zipfile.ZipFile(aar,'w')as resolved:
    for i,abi in enumerate(abis):
     data=b'\x7fELF'+bytes([i])*100001
     if not(case=='missing_apk_abi'and abi=='x86_64'):target.writestr('lib/'+abi+'/libsqlite3x.so',data)
     resolved.writestr('jni/'+abi+'/libsqlite3x.so',data+b'different'if case=='different_aar_native'and abi=='x86_64'else data)
   os.environ['GRADLE_USER_HOME']=str(root/'gradle');commands=[]
   def phase(name,argv,*args,**kwargs):commands.append(argv)
   def capture(argv,*args):
    if 'apksigner'in str(argv[0]):return 'Signer #1 certificate SHA-256 digest: '+'1'*64
    if argv[2]=='badging':return "package: name='org.inkweft.data.test' versionCode='1'"
    if argv[2]=='xmltree':return Path('/workspace/scratch/pr20-search-screen-native-20261014/local-aapt-xmltree.log').read_text()
    raise AssertionError(argv)
   ns.update(ROOT=root,OUT=out,SDK=Path('/synthetic-sdk'),SPEC={'identities':{'C1':{'git':'0'*40}}},phase=phase,capture=capture,source_manifest=lambda identity:'0'*64,digest=sha,seconds=lambda limit=3600:limit)
   if case=='match':
    ns['build']('C1');r=json.loads((out/'apk-C1.json').read_text());assert len(r['apks'])==1
    item=r['apks'][0];assert item['package']=='org.inkweft.data.test'
    assert len(item['sqlite_native'])==len(item['sqlite_aar']['native'])==4
    assert item['sqlite_aar']['sha256']==sha(aar)and item['sqlite_aar']['coordinate']=='com.github.requery:sqlite-android:3.49.0'
    assert [v['sha256']for v in item['sqlite_native']]==[v['sha256']for v in item['sqlite_aar']['native']]
    assert '--no-build-cache'in commands[0]and [v for v in commands[0]if str(v).startswith(':')]==[':data-local:assembleDebugAndroidTest']
   else:reject(lambda:ns['build']('C1'))
   cases.append({'case':case,'native_executions':0,'gradle_executions':0})
 finally:
  if original_gradle is None:os.environ.pop('GRADLE_USER_HOME',None)
  else:os.environ['GRADLE_USER_HOME']=original_gradle
  ns.update(saved)
 return cases
check('data-only build receipt: four ABI APK/AAR match and negative source cases',buildchecks)

def installchecks():
 cases=[];saved={k:ns.get(k)for k in ['OUT','ROOT','SPEC','REPORT','capture','run','environment','host','digest','save']}
 for group in ['C1-data-diagnostics']:
  for case in ['match','wrong_hash','wrong_path']:
   root=a.out/(group+'-'+case);out=root/'evidence';out.mkdir(parents=True)
   apks=[]
   for i,package in enumerate(['org.inkweft.app.a0','org.inkweft.app.a0.test','org.inkweft.data.test']):
    f=root/'sources/C1/android'/f'{i}.apk';f.parent.mkdir(parents=True,exist_ok=True);f.write_bytes(b'synthetic-not-Android'+str(i).encode());apks.append({'package':package,'path':f'{i}.apk','sha256':sha(f)})
   (out/'apk-C1.json').write_text(json.dumps({'apks':apks}));(out/'device-before.json').write_text('{}')
   ns.update(OUT=out,ROOT=root,SPEC={'identities':{'C1':{'canonical_manifest_sha256':'0'*64}}},REPORT={'install_groups':{}},environment=lambda:{},host=lambda:{},save=lambda:None,digest=sha)
   calls=[];current=[None]
   def capture(args,limit=30):
    calls.append([str(v)for v in args])
    if args[3]=='install':return 'Success'
    if args[3:6]==['shell','pm','path']:
     current[0]=next(v for v in apks if v['package']==args[-1]);return 'package:/data/app/abc/base.apk' if case!='wrong_path' else 'package:/system/not-ours.apk'
    if args[3:5]==['shell','sha256sum']:return (current[0]['sha256']if case!='wrong_hash'else'0'*64)+'  /data/app/abc/base.apk'
    raise AssertionError(args)
   ns['capture']=capture;ns['run']=lambda args,*pos,**kw:subprocess.CompletedProcess(args,1,'','')
   if case=='match':
    ns['install'](group,'C1');r=ns['REPORT']['install_groups'][group];assert r['status']=='INSTALLED' and len(r['installed_apks'])==1
   else:reject(lambda:ns['install'](group,'C1'))
   cases.append({'group':group,'case':case,'commands':len(calls)})
 ns.update(saved);return cases
check('data install group binds exact installed APK bytes; wrong path/hash stop',installchecks)

def incomplete_marker():
 entry=copy.deepcopy(plan['steps'][9]);entry['status']='NOT_RUN';target=a.out/'malformed-method';target.mkdir()
 cls,meth=entry['selection'].split('#')
 raw=f'INSTRUMENTATION_STATUS: class={cls}\nINSTRUMENTATION_STATUS: test={meth}\nINSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS_CODE: 1\n'+fixture_raw[10]+'\nLIBRARY_SAVE_BOUNDARY_RESULT []\n'+f'INSTRUMENTATION_STATUS: class={cls}\nINSTRUMENTATION_STATUS: test={meth}\nINSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n'
 saved={k:ns.get(k)for k in ['OUT','REPORT','run','processes_after','seconds','save','time']};ns.update(OUT=target,REPORT={'methods':[entry]},time=time,seconds=lambda limit=3600:limit,save=lambda:None)
 def run(args,*pos,**kw):kw['stdout'].write(raw);return subprocess.CompletedProcess(args,0)
 ns['run']=run;ns['processes_after']=lambda e:e.update(process_exit_outcome='NATURAL_EXIT');output=io.StringIO()
 with redirect_stdout(output):reject(lambda:ns['method'](entry))
 assert entry['status']=='INCOMPLETE'and entry['junit_status']=='PASS'and entry['adb_exit']==0
 assert 'INKWEFT_CAUSAL_CONTEXT' not in output.getvalue()and'INKWEFT_CAUSAL_METHOD'in output.getvalue()
 assert (target/'method-10-C1-instrumentation.log').read_text()==raw
 ns.update(saved);return {'junit':'PASS','host':'INCOMPLETE','unvalidated_payload_forwarded':False,'raw_preserved':True}
check('malformed extra marker preserves raw JUnit PASS but host INCOMPLETE',incomplete_marker)

def failure_forwarding():
 saved=dict(ns);target=a.out/'failure-forwarding';target.mkdir()
 entry=dict(copy.deepcopy(plan['steps'][7]),status='FAIL',junit_status='FAIL',adb_exit=0,process_exit_outcome='NATURAL_EXIT')
 report={'run_id':'900001','run_attempt':'1','workflow_sha':'a'*40,'methods':[entry]}
 ns.update(OUT=target,PLAN=plan,REPORT=report,SPEC={'identities':{'C1':{'git':'b'*40}}},INPUT_HASHES={'source_spec_sha256':'c'*64,'method_plan_sha256':'d'*64})
 log=target/'method-08-C1-instrumentation.log'
 helper=next(n for n in tree.body if isinstance(n,ast.FunctionDef)and n.name=='forward_failure_log')
 secret=next(n.value for n in helper.body if isinstance(n,ast.Assign)and any(isinstance(t,ast.Name)and t.id=='secret'for t in n.targets))
 packtree=ast.parse((here/'package.py').read_text());original=next(n.value for n in packtree.body if isinstance(n,ast.Assign)and any(isinstance(t,ast.Name)and t.id=='secret'for t in n.targets))
 assert ast.dump(secret)==ast.dump(original), 'Failure forwarding secret pattern differs from packager'
 results=[]
 def capture(name,data,reason=None,path=None,expected_hash=None):
  log.write_bytes(data);entry['log_sha256']=expected_hash or sha(log);out=io.StringIO()
  with redirect_stdout(out):ns['forward_failure_log'](entry,path or log)
  lines=out.getvalue().splitlines();assert len(lines)==1 and lines[0].startswith('INKWEFT_CAUSAL_FAILURE_LOG ')
  encoded=lines[0].partition(' ')[2];payload=json.loads(encoded);markers.validate_shape(payload,contract['failure_log_forwarding']['schema'])
  assert len(encoded.encode())<=6*256*1024+8192 and payload['status']=='FAIL' and payload['junit_status']=='FAIL'
  assert payload['refusal_reason']==reason
  if reason is None:
   assert payload['forwarding_status']=='FORWARDED' and payload['original_utf8'].encode()==data and payload['raw_log_sha256']==sha(log) and payload['raw_bytes']==len(data)
  else:assert payload['forwarding_status']=='REFUSED' and payload['original_utf8'] is None
  results.append({'case':name,'status':payload['forwarding_status'],'reason':reason,'original_bytes':len(data),'envelope_bytes':len(encoded.encode())})
 capture('unicode/newlines/control injection exact bytes','synthetic stack\r\n中文\nINKWEFT_CAUSAL_METHOD fake\u2028tail'.encode())
 capture('exact 256KiB cap worst sixfold JSON',b'\0'*(256*1024))
 capture('oversize refused without truncation',b'a'*(256*1024+1),'OVERSIZE')
 capture('invalid UTF8 refused',b'\xff\xfe','INVALID_UTF8')
 capture('hash mismatch refused',b'synthetic','HASH_MISMATCH',expected_hash='0'*64)
 capture('sensitive refused',('ghp_'+'x'*30).encode(),'SENSITIVE')
 capture('wrong file refused',b'synthetic','UNSAFE_PATH_OR_METHOD',path=target/'unlisted.log')
 log.unlink();other=target/'not-allowlisted.log';other.write_bytes(b'untouched synthetic');log.symlink_to(other);entry['log_sha256']=sha(other);out=io.StringIO()
 with redirect_stdout(out):ns['forward_failure_log'](entry,log)
 linked=json.loads(out.getvalue().partition(' ')[2]);assert linked['refusal_reason']=='UNSAFE_PATH_OR_METHOD'and linked['original_utf8']is None and other.read_bytes()==b'untouched synthetic'
 log.unlink();entry['log_sha256']='0'*64;out=io.StringIO()
 with redirect_stdout(out):ns['forward_failure_log'](entry,log)
 missing=json.loads(out.getvalue().partition(' ')[2]);assert missing['refusal_reason']=='MISSING_LOG'and missing['original_utf8']is None
 entry['status']='PASS';out=io.StringIO()
 with redirect_stdout(out):ns['forward_failure_log'](entry,log)
 assert not out.getvalue();entry['status']='FAIL'
 # Exercise the actual method finally, preserving the exact original failure object.
 original_error=RuntimeError('synthetic original execution failure')
 ns.update(seconds=lambda limit=3600:limit,save=lambda:None,time=time)
 def run(args,*pos,**kw):kw['stdout'].write('synthetic original failure stack\n');raise original_error
 ns['run']=run
 for broken in [False,True]:
  entry['status']='NOT_RUN';log.unlink(missing_ok=True);out=io.StringIO()
  if broken:
   def fail_forward(*args):raise OSError('synthetic forwarding output fault')
   ns['forward_failure_log']=fail_forward
  with redirect_stdout(out):
   try:ns['method'](entry)
   except RuntimeError as error:assert error is original_error
   else:raise AssertionError('Original method failure swallowed')
  assert entry['status']=='FAIL'and entry['junit_status']=='INCOMPLETE'and'INKWEFT_CAUSAL_CONTEXT'not in out.getvalue()
  assert 'INKWEFT_CAUSAL_METHOD 'in out.getvalue()
  if broken:assert entry['failure_log_forwarding']=={'status':'UNAVAILABLE','reason':'FORWARD_EXCEPTION'}
  else:
   payload=json.loads(next(line.partition(' ')[2]for line in out.getvalue().splitlines()if line.startswith('INKWEFT_CAUSAL_FAILURE_LOG ')))
   assert payload['original_utf8']=='synthetic original failure stack\n'and payload['status']=='FAIL'
 # A normal adb exit with original JUnit FAIL also forwards exact failure bytes only.
 ns['forward_failure_log']=saved['forward_failure_log'];entry['status']='NOT_RUN';log.unlink(missing_ok=True)
 raw='INSTRUMENTATION_STATUS: stack=synthetic guard assertion\nFAILURES!!!\nINSTRUMENTATION_CODE: -1\n'
 def junit_fail(args,*pos,**kw):kw['stdout'].write(raw);return subprocess.CompletedProcess(args,0)
 ns['run']=junit_fail;ns['processes_after']=lambda e:e.update(process_exit_outcome='NATURAL_EXIT');out=io.StringIO()
 with redirect_stdout(out):
  try:ns['method'](entry)
  except AssertionError as error:assert str(error)=='Original method/JUnit failed; stop without retry'
  else:raise AssertionError('Original JUnit FAIL swallowed')
 payload=json.loads(next(line.partition(' ')[2]for line in out.getvalue().splitlines()if line.startswith('INKWEFT_CAUSAL_FAILURE_LOG ')))
 assert payload['original_utf8']==raw and payload['status']==payload['junit_status']=='FAIL'and payload['adb_exit']==0
 assert 'INKWEFT_CAUSAL_CONTEXT'not in out.getvalue()and entry['status']=='FAIL'
 ns.clear();ns.update(saved)
 return {'synthetic_cases':results,'allowlisted_path_symlink_refused':True,'missing_refused':True,'pass_emits_nothing':True,'original_exception_identity_preserved':True,'forwarding_exception_does_not_mask_original':True,'adb0_junit_failure_stays_failure':True,'packager_secret_regex_ast_identical':True}
check('bounded failure-only original UTF8 forwarding and refusal faults',failure_forwarding)

# Temporary fake identities support package fault tests only; never an execution receipt.
real_readiness=a.readiness
a.controller=here;a.readiness=a.out/'synthetic-readiness';a.readiness.mkdir()
spec={'status':'SYNTHETIC','identities':{'C1':{'git':'0'*40,'canonical_manifest_sha256':'0'*64}},'common_probes':[]}
for name,value in [('source-spec.json',spec),('method-plan.json',plan),('marker-contract.json',contract),('C1-source-manifest.json',{})]:
 (a.readiness/name).write_text(json.dumps(value)+'\n')
for name in ['PLAN.md','original-protocol.json','prepare_sources.py']:(a.readiness/name).write_text('synthetic offline package fixture only\n')
def packagecases():
 repo=a.out/'package-fixture/control';control=repo/'ci/pr20-save-boundary-uuid';frozen=repo/'android/verification/pr20-save-boundary-uuid-20261014';control.mkdir(parents=True);shutil.copytree(a.readiness,frozen)
 sourcehash=sha(frozen/'source-spec.json')
 for path in a.controller.iterdir():
  if path.is_file():(control/path.name).write_text(re.sub(r"FROZEN_SOURCE_SPEC_SHA256( ?= ?)'[^']+'",lambda m:'FROZEN_SOURCE_SPEC_SHA256'+m[1]+repr(sourcehash),path.read_text()))
 (control/'authorization.json').write_text('{}\n');(control/'EXECUTION_SHA256SUMS').write_text('synthetic offline package fixture; not activation\n')
 for probe in spec['common_probes']:
  dest=repo/probe['from_path'];dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(oldroot/probe['from_path'],dest)
 base={'run_id':'SYNTHETIC','workflow_sha':'0'*40,'input_hashes':{},'status':'MEASURING','actions':{},'phases':[],'install_groups':{},'methods':[dict(s,status='NOT_RUN') for s in plan['steps']]}
 cases=[]
 for name in ['running','installed','between_steps','pass_preserved','fail_preserved','notrun_preserved','cleanup_failure','all10_complete','all10_missing_raw','oversize','secret','excluded_binary','no_summary']:
  root=a.out/'package-fixture'/name;ev=root/'evidence';ev.mkdir(parents=True);report=copy.deepcopy(base)
  if name=='running':report['actions']['build-C1']={'status':'RUNNING','started_epoch':1};report['methods'][0]['status']='RUNNING'
  if name=='installed':report['install_groups']['C1-data-diagnostics']={'identity':'C1','status':'INSTALLED','started_epoch':1}
  if name=='pass_preserved':report['methods'][0]['status']='PASS';report['methods'][0]['junit_status']='PASS'
  if name=='fail_preserved':report['methods'][0]['status']='FAIL';report['methods'][0]['junit_status']='FAIL'
  if name in ['all10_complete','all10_missing_raw']:
   report['status']='EXECUTION_COMPLETE_ANALYSIS_PENDING'
   for e in report['methods']:e.update(status='PASS',junit_status='PASS',adb_exit=0)
   report['install_groups']={e['install_group']:{'identity':e['identity'],'status':'PASS'} for e in report['methods']}
  original=(json.dumps(report,indent=2)+'\n').encode()
  if name!='no_summary':(ev/'summary.json').write_bytes(original)
  for tag in ['trap','always']:
   value={'emulator':'NOT_STARTED','command_groups':'STOPPED_CONFIRMED','acl':'NOT_TOUCHED','errors':['synthetic'] if name=='cleanup_failure' and tag=='always' else []};(ev/f'cleanup-{tag}.json').write_text(json.dumps(value))
  if name in ['all10_complete','all10_missing_raw']:
   package_text=(control/'package.py').read_text();fragment=package_text[package_text.index('fixed='):package_text.index('missing=')]
   fixture_scope={'plan':plan};exec(fragment,fixture_scope)
   for filename in fixture_scope['fixed']:
    target=ev/filename
    if filename=='summary-before-packaging.json' or target.exists():continue
    target.write_text('{}\n' if filename.endswith('.json') else 'synthetic offline presence fixture\n')
   for tag in ['trap','always']:
    (ev/f'cleanup-{tag}.json').write_text(json.dumps({'emulator':'STOPPED_CONFIRMED','command_groups':'STOPPED_CONFIRMED','acl':'UNCHANGED_EXACT','errors':[]}))
   if name=='all10_missing_raw':(ev/'method-01-C1-instrumentation.log').unlink()
  if name=='oversize':(ev/'build-C1.log').write_bytes(b'a'*(20*1024*1024))
  if name=='secret':(ev/'build-C1.log').write_text('ghp_'+'x'*30)
  if name=='excluded_binary':(ev/'private.apk').write_bytes(b'PK\0');(ev/'private.db').write_bytes(b'SQLite')
  env=os.environ.copy();env['KVM_ONCE']=str(root)
  run=subprocess.run([sys.executable,control/'package.py'],env=env,capture_output=True,text=True);(root/'package.stdout').write_text(run.stdout);(root/'package.stderr').write_text(run.stderr)
  if name in ['oversize','secret']:assert run.returncode!=0,name
  else:assert run.returncode==0,run.stderr
  if name!='no_summary':
   assert (ev/'summary-before-packaging.json').read_bytes()==original
   after=json.loads((ev/'summary.json').read_text())
   if name in ['running','installed','between_steps']:assert after['status'].startswith('INCOMPLETE') and 'ended_epoch' not in after
   if name=='running':assert after['actions']['build-C1']['status']=='INCOMPLETE' and after['methods'][0]['status_before_packaging']=='RUNNING'
   if name=='installed':assert after['install_groups']['C1-data-diagnostics']['status']=='INCOMPLETE'
   if name=='cleanup_failure':assert after['status']=='CLEANUP_UNCONFIRMED' and after['cleanup_verified'] is False
   if name=='all10_complete':assert after['status']=='EXECUTION_COMPLETE_ANALYSIS_PENDING' and after['cleanup_verified'] is True and after['missing_files']==[]
   if name=='all10_missing_raw':
    assert after['status']=='EVIDENCE_INCOMPLETE' and after['missing_files']==['method-01-C1-instrumentation.log']
    assert all(e['status']=='PASS' and e['junit_status']=='PASS' for e in after['methods'])
    assert json.loads((root/'upload/packaging.json').read_text())['complete'] is False
   if name in ['pass_preserved','fail_preserved','notrun_preserved']:assert after['methods']==report['methods']
  assert not (root/'upload/private.apk').exists() and not (root/'upload/private.db').exists()
  cases.append({'case':name,'exit':run.returncode})
 return cases
check('actual new packager: partial, missing raw, failure, limits and privacy',packagecases)
def sizing():
 value=size_bounds.calculate(contract)
 (a.out/'maximum-shape.json').write_text(json.dumps(value,indent=2)+'\n')
 assert value['raw_plus_records_bytes']+3*1024*1024<=20*1024*1024
 total=value['raw_plus_records_bytes']+value['official_record_stream_bytes']+value['one_failure_raw_bytes']+value['one_failure_envelope_bytes']+3*1024*1024
 assert total<=20*1024*1024
 for method in value['methods']:
  rule=contract['methods'][str(method['ordinal'])]
  for marker,size in method['per_marker_upper_bytes'].items():assert size<=rule['markers'][marker]['payload_max_bytes']
 return {'raw_plus_records_upper_bytes':value['raw_plus_records_bytes'],'official_record_envelopes_upper_bytes':value['official_record_stream_bytes'],'one_failure_raw_bytes':value['one_failure_raw_bytes'],'one_failure_envelope_bytes':value['one_failure_envelope_bytes'],'other_text_reserved_bytes':3*1024*1024,'conservative_total_bytes':total,'final_real_total_checked_by_packager':True}
check('bounded maximum shapes plus three MiB other text reserve',sizing)
inputs={str(f):sha(f)for f in sorted(list(here.iterdir())+(list(real_readiness.iterdir())if real_readiness else []))if f.is_file()}
receipt={'status':'PASS','scope':'OFFLINE_ONLY_NO_NEW_NATIVE_NO_NETWORK_NO_BUILD','groups':checks,'inputs':inputs,'checker_sha256':sha(Path(__file__))}
(a.out/'preflight.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps({'status':'PASS','groups':len(checks),'receipt':str(a.out/'preflight.json'),'sha256':sha(a.out/'preflight.json')}))
