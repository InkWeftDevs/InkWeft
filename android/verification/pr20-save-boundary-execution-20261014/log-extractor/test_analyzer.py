"""Offline synthetic closure/fault tests. No native run, network or frozen-input edits."""
import argparse,ast,copy,datetime,hashlib,json,re,shutil,sys,subprocess,os
from pathlib import Path
import yaml
sys.dont_write_bytecode=True
import analyze_logs as A
p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True);a=p.parse_args();assert not a.out.exists();a.out.mkdir(parents=True)
here=Path(__file__).resolve().parent;repo=Path('/workspace/InkWeft');original=repo/'android/verification/pr20-save-boundary-20261014';root=a.out/'synthetic-control';frozen=root/'android/verification/pr20-save-boundary-20261014';frozen.parent.mkdir(parents=True);shutil.copytree(original,frozen)
execution=Path('/workspace/InkWeft-save-boundary-run')
control=root/'ci/pr20-save-boundary';control.parent.mkdir(parents=True);shutil.copytree(execution/'ci/pr20-save-boundary',control,ignore=shutil.ignore_patterns('__pycache__'))
expected={'run_id':'900001','job_id':'900002',**A.GIT_BINDING}
# Real reviewed Git objects and frozen files; all log/run/job evidence stays synthetic.
# Only scratch index/refs and negative-test objects are written. No network is used.
def git(*args,input=None):
 return subprocess.check_output(['git','-C',str(root),*args],input=input,stderr=subprocess.PIPE,timeout=30)
git('init','-q')
object_root=Path(subprocess.check_output(['git','-C',str(repo),'rev-parse','--git-common-dir'],text=True).strip())
if not object_root.is_absolute():object_root=repo/object_root
(root/'.git/objects/info/alternates').write_text(str(object_root.resolve()/'objects')+'\n')
git('read-tree',expected['workflow_sha'])
def commit(parent,message):
 tree=git('write-tree').decode().strip()
 return git('-c','user.name=Synthetic Offline','-c','user.email=synthetic@example.invalid','-c','commit.gpgsign=false','commit-tree',tree,'-p',parent,input=(message+'\n').encode()).decode().strip()
prep=expected['prep_sha']
active=root/'.github/workflows/pr20-save-boundary.yml';active.parent.mkdir(parents=True);shutil.copyfile(execution/'.github/workflows/pr20-save-boundary.yml',active)
git('update-ref','refs/heads/synthetic-offline',expected['workflow_sha']);git('symbolic-ref','HEAD','refs/heads/synthetic-offline')
paths=['ci/pr20-save-boundary/'+n for n in ['run.py','markers.py','package.py','cleanup.py','authorization.json','workflow.proposed.yml']]+['.github/workflows/pr20-save-boundary.yml']
binding={'scope':'DIAGNOSTIC_ONLY',**expected,'run_attempt':'1','prep_sha':prep,'run_status':'completed','run_conclusion':'success','job_status':'completed','job_conclusion':'success','input_hashes':{k:A.sha((frozen/n).read_bytes())for k,n in A.INPUT_FILES.items()},'control_hashes':{n:A.sha((root/n).read_bytes())for n in paths}}
F=A.frozen_inputs(frozen,expected['source_commit'],expected['readiness_git'],root,binding);plan=F['plan'];contract=F['contract']
# Reuse only the pure synthetic fixture constructors from the controller checker; never import/execute its checks.
ns={'contract':contract,'plan':plan,'copy':copy,'json':json};source=(repo/'ci/pr20-save-boundary/check_offline.py').read_text()
for n in ast.parse(source).body:
 if isinstance(n,ast.FunctionDef)and n.name in ['specimen','fixtures']:exec(compile(ast.Module([n],type_ignores=[]),'pure-fixture-ast','exec'),ns)
raw=ns['fixtures']();payloads={i:[{'marker':line.partition(' ')[0],'original_json':line.partition(' ')[2]}for line in text.splitlines()]for i,text in raw.items()}
START=1791954000
iso=lambda t:datetime.datetime.fromtimestamp(t,datetime.timezone.utc).isoformat(timespec='milliseconds').replace('+00:00','Z')
clock={'status':'PASS','scope':'DIAGNOSTIC_ONLY','anchor_kind':'runner_first_step','budget_anchor_epoch':START,'first_step_observed_epoch':START+.1,'actual_job_started_epoch':None,'deadlines_minutes':[30,45,55,60],'deadline_epochs':{k:START+m*60 for k,m in [('setup',30),('data',45),('work',55)]}}
events=[(START+.2,'CLOCK',clock)];contexts=[];methods=[];final_methods=[]
for step in plan['steps']:
 i=step['ordinal'];records=[{'marker':p['marker'],'data':json.loads(p['original_json'])}for p in payloads[i]];packages=['org.inkweft.data.test'];selected=[{'package':v,'sha256':A.sha(v.encode()),'signer_sha256':['1'*64]}for v in packages];installed=[{'package':v['package'],'sha256':v['sha256'],'source_apk_sha256':v['sha256']}for v in selected]
 context={'scope':'DIAGNOSTIC_ONLY','run_id':expected['run_id'],'run_attempt':'1','workflow_sha':expected['workflow_sha'],'source_commit':expected['source_commit'],'source_spec_sha256':F['hashes']['source_spec_sha256'],'method_plan_sha256':F['hashes']['method_plan_sha256'],'test_source_sha256':contract['methods'][str(i)]['test_source_sha256'],'source_manifest_sha256':F['spec']['identities']['C1']['canonical_manifest_sha256'],'selected_apks':selected,'installed_apks':installed,'apk_receipt_sha256':'2'*64,'install_log_sha256':'3'*64,'environment_sha256':'4'*64,'ordinal':i,'identity':'C1','seed':step['seed'],'args':step['args'],'install_group':step['install_group'],'selection':step['selection'],'raw_log_sha256':A.sha(('synthetic-method-'+str(i)).encode()),'records_sha256':A.sha(A.compact(records)+b'\n'),'junit_status':'PASS','adb_exit':0,'process_exit_outcome':'NATURAL_EXIT'}
 at=START+10*i;events.append((at,'CONTEXT',context));contexts.append(context);ordered=[]
 for seq,payload in enumerate(payloads[i]):
  original=payload['original_json'];item={'ordinal':i,'identity':'C1','sequence':seq,'marker':payload['marker'],'payload_bytes':len(original.encode()),'payload_sha256':A.sha(original.encode()),'original_json':original};ordered.append({k:item[k]for k in ['sequence','marker','payload_bytes','payload_sha256']});events.append((at+.001*(seq+1),'RECORD',item))
 events.append((at+.2,'END',{'ordinal':i,'identity':'C1','count':len(records),'ordered_payloads':ordered,'ordered_payloads_sha256':A.sha(A.compact(ordered))}))
 method={k:step[k]for k in ['ordinal','phase','identity','selection']};method.update(status='PASS',junit_status='PASS',adb_exit=0,process_exit_outcome='NATURAL_EXIT',error=None);events.append((at+.3,'METHOD',method));methods.append(method)
 final_methods.append({**{k:v for k,v in method.items()if k!='error'},'seed':step['seed'],'log_sha256':context['raw_log_sha256']})
clean={'emulator':'STOPPED_CONFIRMED','command_groups':'STOPPED_CONFIRMED','acl':'RESTORED_EXACT','error_count':0,'before_sha256':'5'*64,'after_sha256':'5'*64,'exact_match':True}
final={'scope':'DIAGNOSTIC_ONLY','run_id':expected['run_id'],'workflow_sha':expected['workflow_sha'],'input_hashes':F['hashes'],'status':'EXECUTION_COMPLETE_ANALYSIS_PENDING','cleanup_verified':True,'cleanup':{'trap':clean,'always':clean.copy()},'methods':final_methods,'install_groups':{s['install_group']:{'identity':'C1','status':'PASS'}for s in plan['steps']},'device_before_sha256':'4'*64,'device_after_sha256':'4'*64,'missing_files':[],'packaging_complete':True,'manifest_sha256':'6'*64,'summary_sha256':'7'*64};events.append((START+180,'FINAL',final))
run={'id':int(expected['run_id']),'head_sha':expected['workflow_sha'],'run_attempt':1,'status':'completed','conclusion':'success','event':'push','head_branch':'codex/kvm-pr20-save-boundary-20261014','path':'.github/workflows/pr20-save-boundary.yml'}
steps=[]
for index,(name,phase)in enumerate(F['phases'].items()):
 start,end=[(1,2),(2,4),(4,6),(7,130)][index];steps.append({'name':name,'status':'completed','conclusion':'success','started_at':iso(START+start),'completed_at':iso(START+end)})
job={'id':int(expected['job_id']),'run_id':int(expected['run_id']),'head_sha':expected['workflow_sha'],'run_attempt':1,'status':'completed','conclusion':'success','started_at':iso(START-2),'completed_at':iso(START+200),'steps':steps};jobs={'total_count':1,'jobs':[job]}
def render(values):return '\n'.join(iso(t)+' '+A.PREFIX+kind+' '+json.dumps(value,separators=(',',':'))for t,kind,value in values)+'\n'
def analyze(values=events,b=binding,r=run,j=jobs):return A.analyze(render(values),F,expected,b,r,j)
checks=[]
def test(name,modify=None):
 values=copy.deepcopy(events);b=copy.deepcopy(binding);r=copy.deepcopy(run);j=copy.deepcopy(jobs)
 if modify:modify(values,b,r,j)
 result=analyze(values,b,r,j);want='INCOMPLETE'if modify else'COMPLETE_SCOPED_SAVE_BOUNDARY_LOG_EVIDENCE';assert result['analysis_status']==want,(name,result['issues']);checks.append({'name':name,'status':'PASS','parser_status':want,'issues':result['issues']});return result
valid=test('synthetic complete 10 methods / 1 APK / 1 group')
def event(values,kind,ordinal=None):return next(v for _,k,v in values if k==kind and (ordinal is None or v.get('ordinal')==ordinal))
test('missing final',lambda e,b,r,j:e.pop())
test('duplicate final',lambda e,b,r,j:e.append(e[-1]))
test('wrong run',lambda e,b,r,j:b.update(run_id='900003'))
test('extra official job',lambda e,b,r,j:(j.update(total_count=2),j['jobs'].append(copy.deepcopy(j['jobs'][0]))))
test('wrong event',lambda e,b,r,j:r.update(event='workflow_dispatch'))
test('wrong branch',lambda e,b,r,j:r.update(head_branch='wrong'))
test('wrong workflow path',lambda e,b,r,j:r.update(path='.github/workflows/wrong.yml'))
test('wrong job',lambda e,b,r,j:j['jobs'][0].update(id=900003))
test('wrong workflow',lambda e,b,r,j:r.update(head_sha='c'*40))
test('wrong source',lambda e,b,r,j:event(e,'CONTEXT',1).update(source_commit='c'*40))
test('wrong test hash',lambda e,b,r,j:event(e,'CONTEXT',1).update(test_source_sha256='c'*64))
test('wrong args',lambda e,b,r,j:event(e,'CONTEXT',10)['args'].append('unexpected'))
test('installed mismatch',lambda e,b,r,j:event(e,'CONTEXT',1)['installed_apks'][0].update(sha256='c'*64))
test('wrong APK package',lambda e,b,r,j:event(e,'CONTEXT',10)['selected_apks'][0].update(package='org.wrong'))
test('different signer',lambda e,b,r,j:event(e,'CONTEXT',10)['selected_apks'][0].update(signer_sha256=['c'*64]))
test('payload hash',lambda e,b,r,j:event(e,'RECORD',10).update(payload_sha256='c'*64))
test('payload length',lambda e,b,r,j:event(e,'RECORD',10).update(payload_bytes=1))
test('unknown marker',lambda e,b,r,j:event(e,'RECORD',10).update(marker='LIBRARY_PRIVATE'))
test('record order',lambda e,b,r,j:event(e,'RECORD',10).update(sequence=1))
test('footer hash',lambda e,b,r,j:event(e,'END',10).update(ordered_payloads_sha256='c'*64))
test('records hash',lambda e,b,r,j:event(e,'CONTEXT',10).update(records_sha256='c'*64))
test('missing END',lambda e,b,r,j:e.__setitem__(slice(None),[v for v in e if not(v[1]=='END'and v[2]['ordinal']==10)]))
test('host FAIL',lambda e,b,r,j:event(e,'METHOD',10).update(status='FAIL',error='synthetic'))
test('JUnit skipped',lambda e,b,r,j:event(e,'METHOD',10).update(junit_status='SKIP'))
test('adb nonzero',lambda e,b,r,j:event(e,'METHOD',10).update(adb_exit=1))
test('helper cleanup not allowed',lambda e,b,r,j:event(e,'METHOD',10).update(process_exit_outcome='AUXILIARY_AM_KILL_CONFIRMED'))
test('group incomplete',lambda e,b,r,j:event(e,'FINAL')['install_groups']['C1-data-diagnostics'].update(status='INSTALLED'))
test('environment changed',lambda e,b,r,j:event(e,'FINAL').update(device_after_sha256='c'*64))
test('cleanup error',lambda e,b,r,j:event(e,'FINAL')['cleanup']['always'].update(error_count=1))
test('cleanup ACL mismatch',lambda e,b,r,j:event(e,'FINAL')['cleanup']['always'].update(after_sha256='c'*64))
test('missing raw file',lambda e,b,r,j:event(e,'FINAL').update(missing_files=['method-10-C1-instrumentation.log']))
test('packaging incomplete',lambda e,b,r,j:event(e,'FINAL').update(packaging_complete=False))
test('official failure',lambda e,b,r,j:(r.update(conclusion='failure'),b.update(run_conclusion='failure')))
test('late setup metadata',lambda e,b,r,j:j['jobs'][0]['steps'][2].update(completed_at=iso(START+1801)))
test('method outside actual phase',lambda e,b,r,j:j['jobs'][0]['steps'][3].update(started_at=iso(START+105)))
test('phase order reversed',lambda e,b,r,j:j['jobs'][0]['steps'][2].update(started_at=iso(START+3)))
test('first context outside data step',lambda e,b,r,j:j['jobs'][0]['steps'][3].update(started_at=iso(START+10.1)))
test('FINAL boolean masquerading as exit code',lambda e,b,r,j:event(e,'FINAL')['methods'][0].update(adb_exit=False))
test('wrong clock cutoffs',lambda e,b,r,j:event(e,'CLOCK')['deadline_epochs'].update(data=START+46*60))
test('job >60min',lambda e,b,r,j:j['jobs'][0].update(completed_at=iso(START+3601)))
test('official run attempt bool',lambda e,b,r,j:r.update(run_attempt=True))
test('official job attempt bool',lambda e,b,r,j:j['jobs'][0].update(run_attempt=True))
test('official job count bool',lambda e,b,r,j:j.update(total_count=True))
test('phase outside actual job span',lambda e,b,r,j:j['jobs'][0]['steps'][3].update(completed_at=iso(START+202)))
test('wrong prep binding',lambda e,b,r,j:b.update(prep_sha='c'*40))
test('wrong readiness binding',lambda e,b,r,j:b.update(readiness_git='c'*40))
test('END ordinal bool',lambda e,b,r,j:event(e,'END',1).update(ordinal=True))
test('cleanup bool error count',lambda e,b,r,j:event(e,'FINAL')['cleanup']['trap'].update(error_count=False))
test('cleanup trap pending',lambda e,b,r,j:event(e,'FINAL')['cleanup']['trap'].update(command_groups='UNKNOWN'))
test('cleanup origins differ',lambda e,b,r,j:event(e,'FINAL')['cleanup']['trap'].update(before_sha256='c'*64,after_sha256='c'*64))
def duplicate(values,kind):
 index=next(i for i,v in enumerate(values)if v[1]==kind);values.insert(index,copy.deepcopy(values[index]))
for kind in ['CLOCK','CONTEXT','RECORD','END','METHOD']:test('duplicate '+kind,lambda e,b,r,j,k=kind:duplicate(e,k))
def bool_footer(values,b,r,j):
 end=event(values,'END',10);end['ordered_payloads'][0]['sequence']=False;end['ordered_payloads_sha256']=A.sha(A.compact(end['ordered_payloads']))
test('rehashed footer bool sequence',bool_footer)
# Rehash malicious payloads to demonstrate schema/JSON enforcement, not only hash rejection.
def rehash_payload(e,value):
 rec=event(e,'RECORD',10);rec['original_json']=value;rec['payload_bytes']=len(value.encode());rec['payload_sha256']=A.sha(value.encode())
for name,raw_value in [('truncated','{"schema":'),('array','[]'),('duplicate key','{"schema":1,"schema":1}'),('nonfinite','{"schema":NaN}'),('unknown key',json.dumps({**json.loads(payloads[10][0]['original_json']),'secret':'untrusted'}))]:test(name,lambda e,b,r,j,v=raw_value:rehash_payload(e,v))
# Missing/failed final method preserves nine validated method files; no promotion.
partial=test('partial METHOD failure retained',lambda e,b,r,j:e.__setitem__(slice(None),[v for v in e if v[1]!='FINAL'and not(v[1]in['RECORD','END']and v[2].get('ordinal')==10)]))
A.write_outputs(a.out/'partial-output',partial);assert partial['analysis_status']=='INCOMPLETE'and len(partial['extracted_records'])==10 and not any(x['whole_run_complete']for x in partial['extracted_records'])
# Realistic early-stop shape: method9 original JUnit/host fails with no forwarded block.
def gc_failure(values,b,r,j):
 failed=copy.deepcopy(methods[8]);failed.update(status='FAIL',junit_status='FAIL',adb_exit=0,error='synthetic original assertion')
 values[:]=[item for item in values if item[1]=='CLOCK'or(item[1]!='FINAL'and item[2].get('ordinal',99)<=8)]+[(START+95,'METHOD',failed)]
 r['conclusion']='failure';j['jobs'][0]['conclusion']='failure';j['jobs'][0]['steps'][3]['conclusion']='failure'
 b['run_conclusion']=b['job_conclusion']='failure'
early=test('actual shape method9 failure retains first eight closures',gc_failure)
assert len(early['state']['blocks'])==len(early['state']['methods'])==8 and len(early['state']['observed_method_receipts'])==9
assert any('Official run/job not successful'in issue for issue in early['issues'])
A.write_outputs(a.out/'method9-failed-prefix',early)
assert len(early['extracted_records'])==8 and not any(v['whole_run_complete']for v in early['extracted_records'])
git_receipt=A.verify_local_git(root,frozen,expected,F,binding)
A.write_outputs(a.out/'complete-output',valid);assert len(valid['extracted_records'])==10 and all(x['whole_run_complete']for x in valid['extracted_records'])
(a.out/'synthetic-job.log').write_text(render(events));(a.out/'binding.json').write_text(json.dumps(binding,indent=2));(a.out/'run.json').write_text(json.dumps(run));(a.out/'jobs.json').write_text(json.dumps(jobs))

# A late file read failure after successful parsing must never retain COMPLETE/exit0.
from unittest.mock import patch
args=['analyze_logs.py','--job-log',str(a.out/'synthetic-job.log'),'--frozen',str(frozen),'--control-root',str(root),'--binding',str(a.out/'binding.json'),'--run-metadata',str(a.out/'run.json'),'--jobs-metadata',str(a.out/'jobs.json'),'--out',str(a.out/'late-io-fault')]
for key,value in expected.items():args+=['--'+key.replace('_','-'),value]
def cli(name,changes=None,complete=False):
 argv=args.copy();argv[argv.index('--out')+1]=str(a.out/name)
 for key,value in (changes or {}).items():argv[argv.index('--'+key)+1]=str(value)
 with patch.object(sys,'argv',argv):code=A.main()
 result=json.loads((a.out/name/'analysis.json').read_text());assert code==int(not complete),(name,result)
 assert result['analysis_status']==('COMPLETE_SCOPED_SAVE_BOUNDARY_LOG_EVIDENCE'if complete else'INCOMPLETE')
 checks.append({'name':name,'status':'PASS','parser_status':result['analysis_status'],'issues':result['issues']});return result
# Positive CLI covers actual reviewed Git chain and bytes, with synthetic log+metadata.
cli('synthetic-full-cli',complete=True)
bom=a.out/'synthetic-job-bom.log';bom.write_bytes(b'\xef\xbb\xbf'+(a.out/'synthetic-job.log').read_bytes())
cli('synthetic-bom-cli',{'job-log':bom},complete=True)
truncated=a.out/'synthetic-truncated-final.log';truncated.write_text(render(events)[:-5])
cli('truncated-final-cli',{'job-log':truncated})
for key in ['workflow-sha','prep-sha','readiness-git','source-commit']:cli('wrong-cli-'+key,{key:'c'*40})
cli('wrong-cli-run-id',{'run-id':'900003'});cli('wrong-cli-job-id',{'job-id':'900003'})

# Original JSON strings retain surrounding whitespace; semantic records stay exact.
spaced=copy.deepcopy(events);rec=event(spaced,'RECORD',10);rehash_payload(spaced,'  '+rec['original_json']+'  ')
end=event(spaced,'END',10);end['ordered_payloads'][0]={k:rec[k]for k in ['sequence','marker','payload_bytes','payload_sha256']};end['ordered_payloads_sha256']=A.sha(A.compact(end['ordered_payloads']))
preserved=analyze(spaced);assert preserved['analysis_status']=='COMPLETE_SCOPED_SAVE_BOUNDARY_LOG_EVIDENCE',preserved['issues']
A.write_outputs(a.out/'original-payload-preservation',preserved)
saved=json.loads((a.out/'original-payload-preservation/method-10-C1-original-payloads.json').read_text())
assert saved[0]['original_json'].encode()==rec['original_json'].encode()and saved[0]['payload_sha256']==rec['payload_sha256']
assert (a.out/'original-payload-preservation/method-10-C1-markers.log').read_bytes().splitlines()[0]==(rec['marker']+' '+rec['original_json']).encode()
assert (a.out/'original-payload-preservation/method-10-C1-records.json').read_bytes()==(a.out/'complete-output/method-10-C1-records.json').read_bytes()
checks.append({'name':'original payload bytes and semantic records preserved separately','status':'PASS','parser_status':'COMPLETE_SCOPED_SAVE_BOUNDARY_LOG_EVIDENCE'})

def git_fault(name,changed_expected=None,changed_frozen=None,changed_binding=None):
 try:A.verify_local_git(root,frozen,changed_expected or expected,changed_frozen or F,changed_binding or binding)
 except (ValueError,subprocess.CalledProcessError)as error:checks.append({'name':name,'status':'PASS','parser_status':'INCOMPLETE','issues':[str(error)]});return
 raise AssertionError(name+' was accepted')
git('update-ref','refs/heads/synthetic-offline',prep);git_fault('local HEAD mismatch');git('update-ref','refs/heads/synthetic-offline',expected['workflow_sha'])
wrong_active=commit(expected['readiness_git'],'SYNTHETIC wrong active parent; never pushed')
git('update-ref','refs/heads/synthetic-offline',wrong_active);git_fault('active wrong exact parent',{**expected,'workflow_sha':wrong_active});git('update-ref','refs/heads/synthetic-offline',expected['workflow_sha'])
wrong_prep=commit(expected['source_commit'],'SYNTHETIC wrong prep parent; never pushed');wrong_active=commit(wrong_prep,'SYNTHETIC active of wrong prep; never pushed')
git('update-ref','refs/heads/synthetic-offline',wrong_active);git_fault('prep wrong exact parent',{**expected,'workflow_sha':wrong_active,'prep_sha':wrong_prep});git('update-ref','refs/heads/synthetic-offline',expected['workflow_sha'])
changed=copy.deepcopy(F);changed['spec']['identities']['C1']['tree']='c'*40;git_fault('source tree mismatch',changed_frozen=changed)
changed=copy.deepcopy(F);path=next(iter(changed['contract']['reviewed_source_sha256']));changed['contract']['reviewed_source_sha256'][path]='c'*64;git_fault('reviewed source Git blob mismatch',changed_frozen=changed)
changed=copy.deepcopy(binding);changed['control_hashes']['ci/pr20-save-boundary/run.py']='c'*64;git_fault('active control Git blob mismatch',changed_binding=changed)
target=frozen/'PLAN.md';saved_bytes=target.read_bytes();target.write_bytes(saved_bytes+b'\nSYNTHETIC mutation\n');git_fault('readiness Git blob mismatch');target.write_bytes(saved_bytes)
target=control/'run.py';saved_bytes=target.read_bytes();target.write_bytes(saved_bytes+b'\n# SYNTHETIC mutation\n');cli('local control byte mismatch');target.write_bytes(saved_bytes)
real_read=Path.read_bytes;reads=[0]
def late_read(path):
 if path==a.out/'synthetic-job.log':
  reads[0]+=1
  if reads[0]==2:raise OSError('synthetic late input read failure')
 return real_read(path)
with patch.object(sys,'argv',args),patch.object(Path,'read_bytes',late_read):code=A.main()
late=json.loads((a.out/'late-io-fault/analysis.json').read_text());assert code==1 and late['analysis_status']=='INCOMPLETE'and late['issues']
checks.append({'name':'post-analysis input read failure downgrades COMPLETE','status':'PASS','parser_status':'INCOMPLETE'})
receipt={'status':'PASS','scope':'OFFLINE_SYNTHETIC_NOT_NATIVE','cases':checks,'analyzer_sha256':A.sha(Path(A.__file__).read_bytes()),'validator_sha256':A.sha(Path(A.frozen_markers.__file__).read_bytes()),'checker_sha256':A.sha(Path(__file__).read_bytes()),'frozen_input_hashes':F['hashes']};(a.out/'selftest-results.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps({'status':'PASS','cases':len(checks),'receipt_sha256':A.sha((a.out/'selftest-results.json').read_bytes())}))
