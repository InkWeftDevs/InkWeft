"""Offline synthetic 62-call closure and fault cases. Historical payloads are fixtures only."""
import argparse,ast,copy,datetime,json,shutil,sys
from pathlib import Path
from unittest.mock import patch
import analyze_logs as A
p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True);a=p.parse_args();assert not a.out.exists();a.out.mkdir(parents=True)
P=Path('/workspace/scratch/pr20-match-candidate-20261014/preparation62');root=a.out/'synthetic-control';frozen=root/'frozen';shutil.copytree(P/'frozen-readiness',frozen)
control=root/'ci/pr20-kvm-triple';control.parent.mkdir(parents=True);shutil.copytree(P/'reviewed-final/bound-control/ci/pr20-kvm-triple',control,ignore=shutil.ignore_patterns('__pycache__'))
START=1791942801
iso=lambda t:datetime.datetime.fromtimestamp(t,datetime.timezone.utc).isoformat(timespec='milliseconds').replace('+00:00','Z')
load=lambda p:json.loads(p.read_text())
def save(p,x):p.write_text(json.dumps(x,indent=2)+'\n')
expected={'run_id':'900001','job_id':'900002','workflow_sha':'a'*40,'source_commit':load(frozen/'source-spec.json')['identities']['C1']['git'],'readiness_git':'c'*40};prep='b'*40
receipt=load(control/'clock-server-receipt.json');receipt.update(updated_at=iso(START).replace('.000',''),head_sha=expected['readiness_git']);save(control/'clock-server-receipt.json',receipt)
clock=load(control/'clock-contract.json');clock.update(status='FROZEN',updated_at=receipt['updated_at'],budget_anchor_epoch=START,server_receipt_sha256=A.sha((control/'clock-server-receipt.json').read_bytes()));save(control/'clock-contract.json',clock)
budget=load(control/'budget-revision.json');budget.update(status='FROZEN');budget['clock_amendment'].update(budget_anchor_epoch=START,clock_contract_sha256=A.sha((control/'clock-contract.json').read_bytes()),server_receipt_sha256=clock['server_receipt_sha256']);save(control/'budget-revision.json',budget)
hashes={k:A.sha((frozen/n).read_bytes())for k,n in A.INPUT_FILES.items()};hashes.update(clock_contract_sha256=A.sha((control/'clock-contract.json').read_bytes()),server_receipt_sha256=clock['server_receipt_sha256'])
auth=load(control/'authorization.json');auth.update(hashes,approved=True,plan_git=expected['readiness_git'],budget_revision_sha256=A.sha((control/'budget-revision.json').read_bytes()),budget_anchor_epoch=START,clock_status='FROZEN');save(control/'authorization.json',auth)
w=(control/'workflow.proposed.yml').read_text();w=w.replace('0'*40,prep)
for old,new in [('__CLOCK_ANCHOR_EPOCH__',str(START)),('__CLOCK_UPDATED_AT__',receipt['updated_at']),('__CLOCK_CONTRACT_SHA256__',hashes['clock_contract_sha256']),('__CLOCK_RECEIPT_SHA256__',hashes['server_receipt_sha256'])]:w=w.replace(old,new)
active=root/'.github/workflows/pr20-kvm-triple.yml';active.parent.mkdir(parents=True);active.write_text(w)
paths=['ci/pr20-kvm-triple/'+n for n in ['run.py','markers.py','package.py','cleanup.py','authorization.json','workflow.proposed.yml','clock-contract.json','clock-server-receipt.json','budget-revision.json']]+['.github/workflows/pr20-kvm-triple.yml']
binding={'scope':A.SCOPE,**expected,'run_attempt':'1','prep_sha':prep,'run_status':'completed','run_conclusion':'success','job_status':'completed','job_conclusion':'success','input_hashes':hashes,'prepare_sources_sha256':A.sha((frozen/'prepare_sources.py').read_bytes()),'statistics_sha256':A.sha((frozen/'analyze.py').read_bytes()),'control_hashes':{n:A.sha((root/n).read_bytes())for n in paths}}
input_snapshot=lambda:{str(p):A.sha(p.read_bytes())for base in [frozen,control]for p in base.rglob('*')if p.is_file()}
inputs_before=input_snapshot()
F=A.frozen_inputs(frozen,expected,root,binding);plan=F['plan'];contract=F['contract'];RAW=Path('/workspace/scratch/pr20-candidate3-results-20261014/raw');payloads={};fixture_files=[]
for step in plan['steps'][:58]:
 path=RAW/f"method-{step['ordinal']:02d}-{step['identity']}-instrumentation.log";fixture_files.append(path);payloads[step['ordinal']]=F['markers'].safe_marker_payloads(step,path.read_text(),contract)
u=Path('/workspace/scratch/pr20-causal-execution-20261014/extracted/method-08-C1-records.json');fixture_files.append(u);raw='\n'.join(x['marker']+' '+json.dumps(x['data'],separators=(',',':'))for x in load(u));payloads[59]=F['markers'].safe_marker_payloads(plan['steps'][58],raw,contract)
for ordinal,name in [(60,'native-reuse'),(61,'frame')]:
 path=Path('/workspace/scratch/pr20-closure-evidence')/f'candidate-raster-hex-v1-{name}-targeted.log';fixture_files.append(path);payloads[ordinal]=F['markers'].safe_marker_payloads(plan['steps'][ordinal-1],path.read_text(),contract)
num=lambda rows:{'frameElapsedMs':1.0,'rows':rows,'points':2,'freezeMs':0.1,'decodeMs':0.1}
stats='BuildStats(tiles=1, rebuilt=1, decoded=1201, bitmapBytes=800000, metadataMs=1.0, rasterMs=1.0, publicationMs=1.0, reusedStrokes=0, geometryBytes=0, nativeStrokeBuilds=1201)'
dense=[]
for stage in ['fixture-start','fixture-complete','editor-window-complete','frame-start','frame-complete','verified-and-released']:
 d={'case':'original-dense','stage':stage,'elapsedMs':1.0,'fixtureStrokes':1201,'fixturePoints':76802,'frameDeadlineMs':30000}
 if stage=='editor-window-complete':d.update(windowStrokes=128,windowPoints=8192)
 if stage=='frame-complete':d.update(frameElapsedMs=1.0,snapshotReads=[num(256)]*4+[num(177)],snapshotRows=1201,changeCallbacks=1,publishedStats=stats)
 dense.append('STREAMED_FRAME_STAGE '+json.dumps(d,separators=(',',':')))
payloads[62]=F['markers'].safe_marker_payloads(plan['steps'][61],'\n'.join(dense),contract)
clock_receipt={k:clock[k]for k in ['status','anchor_kind','updated_at','server_receipt_sha256','actual_job_started_epoch','budget_anchor_epoch']};clock_receipt.update(status='PASS',clock_contract_sha256=hashes['clock_contract_sha256'],first_step_observed_epoch=START+10.1,deadlines_minutes=[38,41,53,55,60],deadline_epochs={k:START+m*60 for k,m in [('KVM_BUDGET_ANCHOR',0),('KVM_SETUP_CUTOFF',38),('KVM_CORRECTNESS_CUTOFF',41),('KVM_COMMON_CUTOFF',53),('KVM_WORK_DEADLINE',55)]})
events=[(START+10.2,'CLOCK',clock_receipt)];contexts=[];methods=[];final_methods=[];oldsummary=load(RAW/'summary.json')
for step in plan['steps']:
 i=step['ordinal'];identity=step['identity'];records=[{'marker':p['marker'],'data':json.loads(p['original_json'])}for p in payloads[i]];selected=[{'package':v,'sha256':A.sha((identity+v).encode()),'signer_sha256':['1'*64]}for v in A.PACKAGES];installed=[{'package':v['package'],'sha256':v['sha256'],'source_apk_sha256':v['sha256']}for v in selected]
 proc={'process_exit_outcome':'NATURAL_EXIT','process_exit_seconds':.02,'process_exit_observations':[{'stage':'natural-exit','elapsed_seconds':.01,'packages':{p:{'exit':1,'stdout':'','stderr':''}for p in A.PACKAGES}}],'auxiliary_cleanup':None}
 if i==17:proc={k:oldsummary['methods'][16][k]for k in proc}
 context={'scope':A.SCOPE,'run_id':expected['run_id'],'run_attempt':'1','workflow_sha':expected['workflow_sha'],'readiness_git':expected['readiness_git'],'source_commit':F['spec']['identities'][identity]['git'],'source_spec_sha256':hashes['source_spec_sha256'],'method_plan_sha256':hashes['method_plan_sha256'],'marker_contract_sha256':hashes['marker_contract_sha256'],'test_source_sha256':contract['methods'][str(i)]['test_source_sha256'],'source_manifest_sha256':F['spec']['identities'][identity]['canonical_manifest_sha256'],'selected_apks':selected,'installed_apks':installed,'apk_receipt_sha256':A.sha(identity.encode()),'install_log_sha256':A.sha(step['install_group'].encode()),'environment_sha256':'4'*64,'ordinal':i,'identity':identity,'seed':step['seed'],'args':step['args'],'install_group':step['install_group'],'selection':step['selection'],'raw_log_sha256':A.sha(('synthetic-method-'+str(i)).encode()),'records_sha256':A.sha(A.compact(records)+b'\n'),'junit_status':'PASS','adb_exit':0,**proc}
 at=START+(100+i if i<=31 else 200+i-31 if i<=55 else 300+i-55);events.append((at,'CONTEXT',context));contexts.append(context);ordered=[]
 for seq,payload in enumerate(payloads[i]):
  original=payload['original_json'];item={'ordinal':i,'identity':identity,'sequence':seq,'marker':payload['marker'],'payload_bytes':len(original.encode()),'payload_sha256':A.sha(original.encode()),'original_json':original};ordered.append({k:item[k]for k in ['sequence','marker','payload_bytes','payload_sha256']});events.append((at+.0001*(seq+1),'RECORD',item))
 events.append((at+.2,'END',{'ordinal':i,'identity':identity,'count':len(records),'ordered_payloads':ordered,'ordered_payloads_sha256':A.sha(A.compact(ordered))}))
 method={k:step[k]for k in ['ordinal','phase','identity','selection']};method.update(status='PASS',junit_status='PASS',adb_exit=0,process_exit_outcome=proc['process_exit_outcome'],error=None);events.append((at+.3,'METHOD',method));methods.append(method)
 final_methods.append({**{k:v for k,v in method.items()if k!='error'},'seed':step['seed'],'log_sha256':context['raw_log_sha256']})
clean={'emulator':'STOPPED_CONFIRMED','command_groups':'STOPPED_CONFIRMED','acl':'RESTORED_EXACT','error_count':0,'before_sha256':'5'*64,'after_sha256':'5'*64,'exact_match':True}
final={'scope':A.SCOPE,'run_id':expected['run_id'],'workflow_sha':expected['workflow_sha'],'input_hashes':hashes,'status':'EXECUTION_COMPLETE_ANALYSIS_PENDING','cleanup_verified':True,'cleanup':{'trap':clean,'always':clean.copy()},'methods':final_methods,'install_groups':{s['install_group']:{'identity':s['identity'],'status':'PASS'}for s in plan['steps']},'device_before_sha256':'4'*64,'device_after_sha256':'4'*64,'missing_files':[],'packaging_complete':True,'manifest_sha256':'6'*64,'summary_sha256':'7'*64};events.append((START+360,'FINAL',final))
run={'id':int(expected['run_id']),'head_sha':expected['workflow_sha'],'run_attempt':1,'status':'completed','conclusion':'success','event':'push','head_branch':F['branch'],'path':F['workflow_path']}
steps=[]
for index,(name,phase)in enumerate(F['phases'].items()):
 start,end=[(11,12),(12,13),(13,14),(14,15),(15,16),(90,150),(190,250),(290,350)][index];steps.append({'name':name,'status':'completed','conclusion':'success','started_at':iso(START+start),'completed_at':iso(START+end)})
job={'id':int(expected['job_id']),'run_id':int(expected['run_id']),'head_sha':expected['workflow_sha'],'run_attempt':1,'status':'completed','conclusion':'success','started_at':iso(START+9),'completed_at':iso(START+400),'steps':steps};jobs={'total_count':1,'jobs':[job]}
def render(values):return '\n'.join(iso(t)+' '+A.PREFIX+kind+' '+json.dumps(value,separators=(',',':'))for t,kind,value in values)+'\n'
def event(values,kind,ordinal=None):return next(v for _,k,v in values if k==kind and (ordinal is None or v.get('ordinal')==ordinal))
checks=[]
def test(name,modify=None):
 values=copy.deepcopy(events);b=copy.deepcopy(binding);r=copy.deepcopy(run);j=copy.deepcopy(jobs)
 if modify:modify(values,b,r,j)
 result=A.analyze(render(values),F,expected,b,r,j);want='INCOMPLETE'if modify else A.COMPLETE;assert result['analysis_status']==want,(name,result['issues']);checks.append({'name':name,'status':'PASS','parser_status':want,'issues':result['issues']});return result
valid=test('synthetic 62 methods / 9 APK / 14 groups');numeric=A.calculate(valid,F);assert numeric['analysis_status']=='COMPLETE_SCREENING_NOT_FINAL_ACCEPTANCE',numeric['issues'];assert len(numeric['raw_saves'])==720 and len(numeric['comparisons'])==54
# Faults alter the envelope/official metadata; all full-run status must be INCOMPLETE.
test('missing FINAL',lambda e,b,r,j:e.pop())
test('duplicate FINAL',lambda e,b,r,j:e.append(e[-1]))
test('wrong binding run',lambda e,b,r,j:b.update(run_id='900003'))
test('extra official job',lambda e,b,r,j:(j.update(total_count=2),j['jobs'].append(copy.deepcopy(j['jobs'][0]))))
for key,val in [('event','workflow_dispatch'),('head_branch','wrong'),('path','.github/workflows/wrong.yml'),('head_sha','c'*40)]:test('wrong official '+key,lambda e,b,r,j,k=key,v=val:r.update({k:v}))
test('wrong job ID',lambda e,b,r,j:j['jobs'][0].update(id=900003))
test('anchor after job start',lambda e,b,r,j:j['jobs'][0].update(started_at=iso(START-1)))
test('job longer than60min',lambda e,b,r,j:j['jobs'][0].update(completed_at=iso(START+3610)))
for key,value in [('source_commit','d'*40),('test_source_sha256','c'*64),('readiness_git','d'*40),('source_manifest_sha256','c'*64),('marker_contract_sha256','c'*64)]:test('wrong context '+key,lambda e,b,r,j,k=key,v=value:event(e,'CONTEXT',1).update({k:v}))
test('wrong method args',lambda e,b,r,j:event(e,'CONTEXT',33)['args'].append('extra'))
test('wrong seed',lambda e,b,r,j:event(e,'CONTEXT',33).update(seed=4109))
test('installed hash mismatch',lambda e,b,r,j:event(e,'CONTEXT',1)['installed_apks'][0].update(sha256='c'*64))
test('wrong APK package',lambda e,b,r,j:event(e,'CONTEXT',1)['selected_apks'][0].update(package='org.wrong'))
test('cross-identity signer mismatch',lambda e,b,r,j:event(e,'CONTEXT',33)['selected_apks'][0].update(signer_sha256=['c'*64]))
test('identity APK changed coherently',lambda e,b,r,j:(event(e,'CONTEXT',2)['selected_apks'][0].update(sha256='c'*64),event(e,'CONTEXT',2)['installed_apks'][0].update(sha256='c'*64,source_apk_sha256='c'*64)))
for key,value in [('payload_sha256','c'*64),('payload_bytes',1),('marker','LIBRARY_PRIVATE'),('sequence',1)]:test('record '+key,lambda e,b,r,j,k=key,v=value:event(e,'RECORD',59).update({k:v}))
test('footer hash',lambda e,b,r,j:event(e,'END',59).update(ordered_payloads_sha256='c'*64))
test('parsed records hash',lambda e,b,r,j:event(e,'CONTEXT',59).update(records_sha256='c'*64))
test('missing END on zero-record method',lambda e,b,r,j:e.__setitem__(slice(None),[v for v in e if not(v[1]=='END'and v[2]['ordinal']==1)]))
for key,value in [('status','FAIL'),('junit_status','SKIP'),('adb_exit',1)]:test('method '+key,lambda e,b,r,j,k=key,v=value:event(e,'METHOD',62).update({k:v}))
test('method17 forced stop disallowed',lambda e,b,r,j:event(e,'CONTEXT',17)['auxiliary_cleanup']['command'].__setitem__(5,'force-stop'))
test('natural missing observations',lambda e,b,r,j:event(e,'CONTEXT',1).update(process_exit_observations=[]))
test('helper before observe window',lambda e,b,r,j:event(e,'CONTEXT',17)['auxiliary_cleanup'].update(started_seconds=.1))
test('helper still alive',lambda e,b,r,j:event(e,'CONTEXT',17)['process_exit_observations'][-1]['packages'][A.HELPER].update(exit=0,stdout='123'))
test('group incomplete',lambda e,b,r,j:event(e,'FINAL')['install_groups']['C1-diagnostics'].update(status='INSTALLED'))
test('environment changed',lambda e,b,r,j:event(e,'FINAL').update(device_after_sha256='c'*64))
test('group environment changed',lambda e,b,r,j:event(e,'CONTEXT',1).update(environment_sha256='c'*64))
test('cleanup error',lambda e,b,r,j:event(e,'FINAL')['cleanup']['always'].update(error_count=1))
test('ACL bytes unequal claim',lambda e,b,r,j:event(e,'FINAL')['cleanup']['always'].update(after_sha256='c'*64))
test('missing original log',lambda e,b,r,j:event(e,'FINAL').update(missing_files=['method-62-C1-instrumentation.log']))
test('packaging incomplete',lambda e,b,r,j:event(e,'FINAL').update(packaging_complete=False))
test('official failure',lambda e,b,r,j:(r.update(conclusion='failure'),b.update(run_conclusion='failure')))
test('late setup boundary',lambda e,b,r,j:j['jobs'][0]['steps'][4].update(completed_at=iso(START+38*60+1)))
test('first context before actual correctness',lambda e,b,r,j:j['jobs'][0]['steps'][5].update(started_at=iso(START+101.1)))
test('phase order reversed',lambda e,b,r,j:j['jobs'][0]['steps'][2].update(started_at=iso(START+12)))
test('clock cutoff changed',lambda e,b,r,j:event(e,'CLOCK')['deadline_epochs'].update(KVM_SETUP_CUTOFF=START+39*60))
test('clock firststep before job',lambda e,b,r,j:event(e,'CLOCK').update(first_step_observed_epoch=START+8))
test('FINAL bool pretending exit code',lambda e,b,r,j:event(e,'FINAL')['methods'][0].update(adb_exit=False))
def malicious(e,value):
 rec=event(e,'RECORD',59);rec.update(original_json=value,payload_bytes=len(value.encode()),payload_sha256=A.sha(value.encode()))
for name,raw in [('truncated','{"schemaVersion":'),('array','[]'),('duplicate','{"schemaVersion":1,"schemaVersion":1}'),('nonfinite','{"schemaVersion":NaN}'),('unexpected key',json.dumps({**json.loads(payloads[59][0]['original_json']),'secret':'untrusted'}))]:test(name,lambda e,b,r,j,v=raw:malicious(e,v))
test('extra method before FINAL',lambda e,b,r,j:e.insert(-1,(START+359,'METHOD',{**methods[-1],'ordinal':63})))
test('missing record within native oracle',lambda e,b,r,j:e.__setitem__(slice(None),[v for v in e if not(v[1]=='RECORD'and v[2].get('ordinal')==60 and v[2].get('sequence')==3)]))
test('missing required dense final stage',lambda e,b,r,j:e.__setitem__(slice(None),[v for v in e if not(v[1]=='RECORD'and v[2].get('ordinal')==62 and v[2].get('sequence')==5)]))
test('payload limit',lambda e,b,r,j:malicious(e,' '* (contract['methods']['59']['markers']['LIBRARY_CAUSAL_UNICODE']['payload_max_bytes']+1)))
test('END bool is not integer ordinal',lambda e,b,r,j:event(e,'END',1).update(ordinal=True))
def footer_bool(e,b,r,j):
 v=event(e,'END',59);v['ordered_payloads'][0]['sequence']=False;v['ordered_payloads_sha256']=A.sha(A.compact(v['ordered_payloads']))
test('footer bool is not integer sequence even rehashed',footer_bool)
test('negative process observation elapsed',lambda e,b,r,j:event(e,'CONTEXT',1)['process_exit_observations'][0].update(elapsed_seconds=-.1))
test('official setup before actual job',lambda e,b,r,j:j['jobs'][0]['steps'][0].update(started_at=iso(START+1)))
# Every common method can have valid data while a final correctness failure blocks complete screening.
def dense_failure(values,b,r,j):
 failed=copy.deepcopy(methods[61]);failed.update(status='FAIL',junit_status='FAIL',error='synthetic dense original timeout')
 values[:]=[v for v in values if v[1]=='CLOCK'or(v[1]!='FINAL'and v[2].get('ordinal',99)<=61)]+[(START+307.3,'METHOD',failed)]
 r['conclusion']=j['jobs'][0]['conclusion']=b['run_conclusion']=b['job_conclusion']='failure';j['jobs'][0]['steps'][7]['conclusion']='failure'
partial=test('dense failure retains61 closures but no numerical promotion',dense_failure);partial_numeric=A.calculate(partial,F);assert partial_numeric['analysis_status']=='INCOMPLETE'and len(partial_numeric['raw_saves'])==720
assert len(partial['state']['methods'])==61 and len(partial['state']['observed_method_receipts'])==62
A.write_outputs(a.out/'failed-prefix',partial,partial_numeric,F['statistics']);assert len(partial['extracted_records'])==61 and not any(x['whole_run_complete']for x in partial['extracted_records'])
# Unfrozen trusted inputs fail before executing imported frozen helpers.
changed=copy.deepcopy(binding);changed['statistics_sha256']='d'*64
try:A.frozen_inputs(frozen,expected,root,changed);raise AssertionError('Accepted changed statistics')
except ValueError:checks.append({'name':'frozen statistics hash required','status':'PASS'})
A.write_outputs(a.out/'complete-output',valid,numeric,F['statistics']);assert len(valid['extracted_records'])==62 and all(x['whole_run_complete']for x in valid['extracted_records'])
(a.out/'synthetic-job.log').write_text(render(events));save(a.out/'binding.json',binding);save(a.out/'run.json',run);save(a.out/'jobs.json',jobs)
args=['analyze_logs.py','--job-log',str(a.out/'synthetic-job.log'),'--frozen',str(frozen),'--control-root',str(root),'--binding',str(a.out/'binding.json'),'--run-metadata',str(a.out/'run.json'),'--jobs-metadata',str(a.out/'jobs.json'),'--out',str(a.out/'late-io-fault')]
for key,value in expected.items():args+=['--'+key.replace('_','-'),value]
real_read=Path.read_bytes;reads=[0]
def late_read(path):
 if path==a.out/'synthetic-job.log':
  reads[0]+=1
  if reads[0]==2:raise OSError('synthetic late input read failure')
 return real_read(path)
with patch.object(sys,'argv',args),patch.object(Path,'read_bytes',late_read):code=A.main()
late=load(a.out/'late-io-fault/analysis.json');assert code==1 and late['analysis_status']=='INCOMPLETE'and late['issues'];checks.append({'name':'late I/O cannot retain COMPLETE','status':'PASS'})
# Statistical logic is unchanged; only count/source/single-ended labels differ from original58.
old=ast.parse(Path('/workspace/InkWeft-native-candidate3/android/verification/pr20-candidate3-readiness-20261014/analyze.py').read_text());new=ast.parse((frozen/'analyze.py').read_text());functions=lambda tree:{n.name:ast.dump(n,include_attributes=False)for n in tree.body if isinstance(n,ast.FunctionDef)};of,nf=functions(old),functions(new)
assert set(of)==set(nf)and [k for k in of if of[k]!=nf[k]]==['analyze']
# Match all paired numeric rows to the original58 same records, without copying formulas.
oldstat=A.module(Path('/workspace/InkWeft-native-candidate3/android/verification/pr20-candidate3-readiness-20261014/analyze.py'),'original58_stats');oldplan=load(Path('/workspace/InkWeft-native-candidate3/android/verification/pr20-candidate3-readiness-20261014/method-plan.json'));oldrecords={s['ordinal']:load(RAW/f"method-{s['ordinal']:02d}-{s['identity']}-records.json")for s in oldplan['steps']};oldnumeric=oldstat.analyze(oldsummary,oldplan,F['protocol'],oldrecords)
for key in ['comparisons','paired_values','raw_saves','save_distributions','warm_comparisons']:
 def drop_identity(x):
  if isinstance(x,list):return [drop_identity(v)for v in x]
  if isinstance(x,dict):return {k:drop_identity(v)for k,v in x.items()if k not in ['source_commit','source_baseline','source_candidate','left_commit','right_commit','run_id','workflow_sha']}
  return x
 assert drop_identity(oldnumeric[key])==drop_identity(numeric[key]),('arithmetic differs',key)
checks.append({'name':'original frozen arithmetic / 720 raw samples / 54 comparisons exact','status':'PASS'})
assert input_snapshot()==inputs_before,'Input content or directory entries changed (including bytecode cache)'
checks.append({'name':'frozen/control files read-only including no bytecode cache','status':'PASS'})
receipt={'status':'PASS','scope':'OFFLINE_SYNTHETIC_NOT_NEW_NATIVE','cases':checks,'analyzer_sha256':A.sha(Path(A.__file__).read_bytes()),'checker_sha256':A.sha(Path(__file__).read_bytes()),'validator_sha256':A.sha((control/'markers.py').read_bytes()),'statistics_sha256':binding['statistics_sha256'],'frozen_input_hashes':hashes,'historical_fixture_hashes':{str(v):A.sha(v.read_bytes())for v in fixture_files},'synthetic_dense':'Only shape/closure, values1.0 are invented parser data, no device execution.'};save(a.out/'selftest-results.json',receipt);print(json.dumps({'status':'PASS','cases':len(checks),'receipt_sha256':A.sha((a.out/'selftest-results.json').read_bytes())}))
