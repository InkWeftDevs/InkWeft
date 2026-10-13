"""Synthetic, offline checks only. Creates no run evidence and performs no Android/network work."""
from pathlib import Path
import copy,importlib.util,json,hashlib,datetime,zipfile,tempfile,subprocess,sys
ROOT=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('verifier',ROOT/'verify_artifact.py');v=importlib.util.module_from_spec(spec);spec.loader.exec_module(v)
work=Path(tempfile.mkdtemp(prefix='synthetic-',dir=ROOT));trusted=v.trusted_inputs(Path('/workspace/InkWeft-native-candidate2-budget3'))
def data(x):return (json.dumps(x,indent=2,ensure_ascii=False)+'\n').encode()
base=dict(trusted['required']);auth=trusted['auth'];cutoffs={p:v.ANCHOR+n*60 for p,n in auth['phase_cutoff_minutes'].items()}
clock={'status':'PASS','anchor_kind':'github_pull_request_updated_at','updated_at':'2026-10-13T17:42:19Z','budget_anchor_epoch':v.ANCHOR,'clock_contract_sha256':auth['clock_contract_sha256'],'server_receipt_sha256':auth['server_receipt_sha256'],'actual_job_started_epoch':None,'first_step_observed_epoch':v.ANCHOR+135,'deadline_epochs':{'KVM_BUDGET_ANCHOR':v.ANCHOR,'KVM_SETUP_CUTOFF':cutoffs['setup'],'KVM_CORRECTNESS_CUTOFF':cutoffs['correctness'],'KVM_COMMON_CUTOFF':cutoffs['common'],'KVM_WORK_DEADLINE':cutoffs['diagnostics']},'deadlines_minutes':[38,41,53,55,60]}
base['clock.json']=data(clock)
summary={'run_id':str(v.RUN_ID),'run_attempt':'1','workflow_sha':v.ACTIVE,'source_spec':trusted['spec'],
 'input_hashes':{k:auth[k] for k in ['approved_plan_sha256','source_spec_sha256','method_plan_sha256','original_protocol_sha256','clock_contract_sha256','server_receipt_sha256']},
 'version_aliases':{k:{'alias':a,'source_commit':trusted['spec']['identities'][k]['git']} for k,a in [('B','controlled_baseline'),('C0','previous_candidate'),('C1','new_candidate')]},
 'budget_anchor_epoch':v.ANCHOR,'actual_job_started_epoch':None,'phase_cutoff_epochs':cutoffs,'work_deadline_epoch':cutoffs['diagnostics'],
 'status':'BLOCKED_OR_FAILED','error':'SYNTHETIC_NOT_AN_ACTUAL_RUN','actions':{},'phases':[],'install_groups':{},'methods':[dict(s,status='NOT_RUN') for s in trusted['plan']['steps']]}
base['summary-before-packaging.json']=data(summary)
summary['cleanup_verified']=True;base['summary.json']=data(summary)
for tag in ['trap','always']:base['cleanup-'+tag+'.json']=data({'emulator':'NOT_STARTED','command_groups':'NOT_STARTED','acl':'NOT_TOUCHED','errors':[]})
checks=[]
def archive_case(name,edit=None,reject=False,duplicate=None,meta_edit=None):
 payload=copy.deepcopy(base)
 if edit:edit(payload)
 manifest=[{'name':n,'original_bytes':len(b),'uploaded_bytes':len(b),'original_sha256':v.sha(b),'uploaded_sha256':v.sha(b)} for n,b in payload.items()]
 payload['manifest.json']=data(manifest);payload['packaging.json']=data({'files':len(manifest),'bytes_before_manifest':sum(len(b) for b in payload.values())-len(payload['manifest.json']),'complete':True,'truncated_files':0,'rejected_names':[],'retention_days':3})
 archive=work/(name+'.zip')
 with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
  for n,b in payload.items():z.writestr(n,b)
  if duplicate:z.writestr(duplicate,payload[duplicate])
 meta={'total_count':1,'artifacts':[{'id':1,'name':f'pr20-kvm-triple-{v.RUN_ID}','expired':False,'size_in_bytes':archive.stat().st_size,'digest':'sha256:'+v.sha(archive.read_bytes()),'workflow_run':{'id':v.RUN_ID,'head_sha':v.ACTIVE,'head_branch':'codex/kvm-pr20-candidate2-budget3-20261013'}}]}
 if meta_edit:meta_edit(meta)
 err=None
 try:received,receipt=v.read_archive(archive,meta,trusted)
 except Exception as e:err=type(e).__name__+': '+str(e)
 assert bool(err)==reject,(name,err)
 checks.append({'case':name,'status':'PASS','expected_rejection':reject,'error':err})
 (work/(name+'-metadata.json')).write_bytes(data(meta))
 return payload,archive,work/(name+'-metadata.json')
valid,archive,metadata=archive_case('valid-synthetic')
audit=v.audit_execution(valid,trusted);assert not audit['issues'] and audit['all57_methods_pass'] is False and audit['method_status_counts']=={'NOT_RUN':57}
checks.append({'case':'NOT_RUN_not_promoted','status':'PASS'})
archive_case('source-tamper',lambda p:p.__setitem__('frozen-source-spec.json',b'{}'),True)
archive_case('wrong-run',reject=True,meta_edit=lambda m:m['artifacts'][0]['workflow_run'].update(id=1))
archive_case('wrong-digest',reject=True,meta_edit=lambda m:m['artifacts'][0].update(digest='sha256:'+'0'*64))
archive_case('non-whitelisted-apk',lambda p:p.__setitem__('payload.apk',b'no binary'),True)
archive_case('path-traversal',lambda p:p.__setitem__('../outside.txt',b'x'),True)
archive_case('credential-pattern',lambda p:p.__setitem__('host.json',data({'fake':'gh'+'p_'+'A'*36})),True)
archive_case('oversize',lambda p:p.__setitem__('build-B.log',b'x'*(v.MAX_BYTES+1)),True)
archive_case('duplicate',reject=True,duplicate='clock.json')
for field,value in [('budget_anchor_epoch',v.ANCHOR+1),('actual_job_started_epoch',v.ANCHOR),('deadline_epochs',{})]:
 payload=copy.deepcopy(valid);changed=copy.deepcopy(clock);changed[field]=value;payload['clock.json']=data(changed)
 result=v.audit_execution(payload,trusted);assert result['issues'];checks.append({'case':'clock-'+field,'status':'PASS','issues':result['issues']})
# A raw PASS followed by a host FAIL stays host FAIL, while its original raw result remains PASS.
payload=copy.deepcopy(valid);failed=copy.deepcopy(summary);entry=failed['methods'][0];cls,method=entry['selection'].split('#')
raw=f'INSTRUMENTATION_STATUS: class={cls}\nINSTRUMENTATION_STATUS: test={method}\nINSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS: class={cls}\nINSTRUMENTATION_STATUS: test={method}\nINSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n'
entry.update(status='FAIL',junit_status='PASS',adb_exit=0,log_sha256=v.sha(raw.encode()),junit_ok_counts=['1'],status_codes=[1,0],reported_classes=[cls,cls],reported_methods=[method,method],reported_test_counts=['1','1'],started_epoch=v.ANCHOR+120,seconds=.1)
prefix=f"method-01-{entry['identity']}";payload[prefix+'-instrumentation.log']=raw.encode();payload[prefix+'-records.json']=data([])
payload[prefix+'-junit.xml']=f'<testsuite tests="1" failures="0" errors="1"><testcase classname="{cls}" name="{method}"/></testsuite>'.encode()
payload['summary.json']=data(failed);prior=copy.deepcopy(failed);prior.pop('cleanup_verified');payload['summary-before-packaging.json']=data(prior)
result=v.audit_execution(payload,trusted);assert result['issues']==[] and result['method_status_counts']=={'FAIL':1,'NOT_RUN':56} and result['raw_junit_status_counts']['PASS']==1
checks.append({'case':'raw_PASS_and_host_FAIL_preserved','status':'PASS'})
# Failing cleanup must never be promoted to verified, even if the summary says verified.
payload=copy.deepcopy(valid);payload['cleanup-always.json']=data({'emulator':'STOP_UNCONFIRMED','command_groups':'STOP_UNCONFIRMED','acl':'RESTORATION_UNCONFIRMED','errors':['SYNTHETIC']})
result=v.audit_execution(payload,trusted);assert not result['cleanup']['both_verified'] and result['issues'];checks.append({'case':'cleanup_failure_not_promoted','status':'PASS'})
jobs={'jobs':[{'id':v.JOB_ID,'run_id':v.RUN_ID,'head_sha':v.ACTIVE,'run_attempt':1,'status':'completed','started_at':'2026-10-13T17:44:33Z','completed_at':'2026-10-13T17:44:34Z','conclusion':'failure'}]}
result=v.audit_execution(valid,trusted,jobs);assert not result['issues'] and result['clock']['anchor_lead_seconds']==134;checks.append({'case':'synthetic_terminal_metadata_134s_lead','status':'PASS'})
# CLI performs extraction only after all byte/source checks and refuses reuse of the destination.
cmd=[sys.executable,str(ROOT/'verify_artifact.py'),'--archive',str(archive),'--metadata',str(metadata),'--out',str(work/'cli-evidence')]
first=subprocess.run(cmd,text=True,capture_output=True);assert first.returncode==0,first.stderr
second=subprocess.run(cmd,text=True,capture_output=True);assert second.returncode!=0 and 'overwrite' in second.stderr
checks.append({'case':'actual_CLI_and_no_overwrite','status':'PASS'})
# Independent-review regressions: never accept a false completed ledger.
payload=copy.deepcopy(valid)
for name in ['summary.json','summary-before-packaging.json']:
 changed=json.loads(payload[name]);changed['status']='EXECUTION_COMPLETE_ANALYSIS_PENDING';payload[name]=data(changed)
result=v.audit_execution(payload,trusted)
assert all(any(fragment in issue for issue in result['issues']) for fragment in ['without57 PASS','without all9 APK','without14 PASS'])
checks.append({'case':'false_completion_with57_NOT_RUN_rejected','status':'PASS','issues':result['issues']})
# An outer timeout can kill Python after creating partial raw and before finally persists fields.
payload=copy.deepcopy(valid);original=copy.deepcopy(summary);original.pop('cleanup_verified')
original['status']='MEASURING';original['methods'][0].update(status='RUNNING',started_epoch=v.ANCHOR+120)
normalized=copy.deepcopy(original);normalized.update(status_before_packaging='MEASURING',status='INCOMPLETE_OUTER_TIMEOUT_OR_INTERRUPTION',cleanup_verified=True)
normalized['methods'][0].update(status_before_packaging='RUNNING',status='INCOMPLETE')
payload['summary-before-packaging.json']=data(original);payload['summary.json']=data(normalized)
prefix='method-01-'+original['methods'][0]['identity'];payload[prefix+'-instrumentation.log']=b'INSTRUMENTATION_STATUS: class=partial\n'
result=v.audit_execution(payload,trusted)
assert result['issues']==[] and result['method_status_counts']=={'INCOMPLETE':1,'NOT_RUN':56}
assert result['methods'][0]['junit_status'] is None and result['methods'][0]['observed_raw_parser_status']=='INCOMPLETE'
assert any('records unpersisted' in p for p in result['pending']) and any('XML unpersisted' in p for p in result['pending'])
checks.append({'case':'outer_kill_partial_raw_is_pending_not_contradiction','status':'PASS'})
# The exception permits absence only; an existing digest or derived field contradiction is still rejected.
for ledger in [original,normalized]:ledger['methods'][0]['log_sha256']='0'*64
payload['summary-before-packaging.json']=data(original);payload['summary.json']=data(normalized)
result=v.audit_execution(payload,trusted);assert any('raw digest mismatch' in issue for issue in result['issues'])
checks.append({'case':'outer_kill_existing_digest_mismatch_rejected','status':'PASS'})
# Reuse old17 raw only as explicit test fixtures, never as evidence that this new run executed.
historical=Path('/workspace/scratch/pr20-triple-run-38419007471/evidence')
ledger=json.loads((historical/'summary.json').read_text());payload=copy.deepcopy(valid);partial=copy.deepcopy(summary)
for index in range(17):
 entry=copy.deepcopy(ledger['methods'][index]);assert all(entry.get(k)==val for k,val in trusted['plan']['steps'][index].items())
 entry['started_epoch']=v.ANCHOR+120+index*20;partial['methods'][index]=entry
 prefix=f"method-{entry['ordinal']:02d}-{entry['identity']}"
 for suffix in ['-instrumentation.log','-records.json','-junit.xml']:payload[prefix+suffix]=(historical/(prefix+suffix)).read_bytes()
def helper_case(name,edit):
 updated=copy.deepcopy(partial);edit(updated['methods'][16]);prior=copy.deepcopy(updated);prior.pop('cleanup_verified')
 contents=copy.deepcopy(payload);contents['summary.json']=data(updated);contents['summary-before-packaging.json']=data(prior)
 audit=v.audit_execution(contents,trusted);assert any('auxiliary outcome/receipt mismatch' in i for i in audit['issues']),audit['issues']
 checks.append({'case':name,'status':'PASS'})
assert partial['methods'][16]['process_exit_outcome']=='AUXILIARY_AM_KILL_CONFIRMED' and 'auxiliary_cleanup' in partial['methods'][16]
helper_case('AUX_outcome_without_receipt_rejected',lambda e:e.pop('auxiliary_cleanup'))
helper_case('NATURAL_outcome_with_aux_receipt_rejected',lambda e:e.update(process_exit_outcome='NATURAL_EXIT'))
# Keep package's claims normalization distinct from independent byte-for-byte ACL verification.
for case,grant,claim,after in [('before_but_NOT_TOUCHED',None,'NOT_TOUCHED',None),
 ('grant_changed_but_UNCHANGED',b'changed\n','UNCHANGED_EXACT',b'original\n'),
 ('restored_claim_but_after_mismatch',b'changed\n','RESTORED_EXACT',b'wrong\n')]:
 contents=copy.deepcopy(valid);contents['kvm-before.acl']=b'original\n'
 if grant is not None:contents['kvm-granted.acl']=grant
 for tag in ['trap','always']:
  cleanup=json.loads(contents['cleanup-'+tag+'.json']);cleanup['acl']=claim;contents['cleanup-'+tag+'.json']=data(cleanup)
  if after is not None:contents['kvm-after-'+tag+'.acl']=after
 audit=v.audit_execution(contents,trusted)
 assert audit['cleanup']['receipt_claims_verified'] is True and audit['cleanup']['both_verified'] is False
 assert any('ACL exact restoration/state' in i for i in audit['issues'])
 checks.append({'case':'ACL_'+case+'_rejected','status':'PASS'})
contents=copy.deepcopy(valid);contents['kvm-before.acl']=b'original\n';contents['kvm-granted.acl']=b'changed\n'
for tag in ['trap','always']:
 cleanup=json.loads(contents['cleanup-'+tag+'.json']);cleanup['acl']='RESTORED_EXACT';contents['cleanup-'+tag+'.json']=data(cleanup);contents['kvm-after-'+tag+'.acl']=b'original\n'
audit=v.audit_execution(contents,trusted);assert not audit['issues'] and audit['cleanup']['both_verified']
checks.append({'case':'ACL_restored_exact_both_receipts_positive','status':'PASS'})
# Actual completion-audit AST:57PASS can precede final group/environment persistence.
# Exercise only this tail branch; this fixture is not a fabricated full57 raw execution.
tree=v.ast.parse((ROOT/'verify_artifact.py').read_text());function=next(n for n in tree.body if isinstance(n,v.ast.FunctionDef) and n.name=='audit_execution')
start=next(i for i,n in enumerate(function.body) if isinstance(n,v.ast.Assign) and isinstance(n.targets[0],v.ast.Name) and n.targets[0].id=='complete')
end=next(i for i,n in enumerate(function.body[start:],start) if isinstance(n,v.ast.Expr) and isinstance(n.value,v.ast.Call) and isinstance(n.value.func,v.ast.Attribute) and n.value.func.attr=='update')
late_groups={s['install_group']:{'status':'PASS'} for s in trusted['plan']['steps']};last=next(reversed(late_groups));late_groups[last]={'status':'INCOMPLETE','status_before_packaging':'INSTALLED'}
late_issues=[];scope={'entries':[dict(s,status='PASS') for s in trusted['plan']['steps']],
 'original':{'status':'MEASURING'},'summary':{'status':'INCOMPLETE_OUTER_TIMEOUT_OR_INTERRUPTION'},
 'apk_receipts':{k:{} for k in ['B','C0','C1']},'groups':late_groups,'payload':{},'clean':[True,True],
 'pending':[],'note':lambda condition,message:late_issues.append(message) if not condition else None}
exec(compile(v.ast.Module(body=function.body[start:end],type_ignores=[]),'<actual-final-group-interruption-audit>','exec'),scope)
assert scope['complete'] is True and scope['claims_complete'] is False and not late_issues
assert scope['pending'] and scope['groups'][last]['status']=='INCOMPLETE'
checks.append({'case':'57PASS_final_group_interrupted_is_not_false_completion','status':'PASS','scope':'actual completion-audit AST branch, not full raw execution'})
# New budget3 identity/clock amendment cases, retaining all27 prior regressions above.
baseauth=copy.deepcopy(trusted['auth']);basebudget=json.loads(trusted['required']['budget-revision.json'])
basecontract=copy.deepcopy(trusted['contract']);basereceipt=json.loads(trusted['required']['clock-server-receipt.json'])
assert v.budget_override(baseauth,basebudget,basecontract,basereceipt)['effective_cutoffs_minutes']==[38,41,53,55,60]
checks.append({'case':'real_committed_budget3_override_binds_historical_PLAN','status':'PASS'})
def reject_budget(name,change):
 a,b,c,r=copy.deepcopy((baseauth,basebudget,basecontract,basereceipt));change(a,b,c,r)
 error=None
 try:v.budget_override(a,b,c,r)
 except (ValueError,KeyError) as exc:error=str(exc)
 assert error,name
 checks.append({'case':name,'status':'PASS','expected_rejection':True,'error':error})
reject_budget('missing_explicit_auth_override',lambda a,b,c,r:a.pop('phase_budget_override'))
reject_budget('missing_explicit_budget_override',lambda a,b,c,r:b.pop('phase_budget_amendment'))
reject_budget('stale_clock2_offsets',lambda a,b,c,r:c.update(phase_cutoff_minutes={'setup':32,'correctness':38,'common':52,'diagnostics':55}))
reject_budget('historical_PLAN_replaced',lambda a,b,c,r:b['phase_budget_amendment'].update(historical_plan_sha256='0'*64))
reject_budget('source_scope_changed',lambda a,b,c,r:b['phase_budget_amendment'].update(source_spec_sha256='0'*64))
reject_budget('method_scope_changed',lambda a,b,c,r:b['phase_budget_amendment'].update(method_plan_sha256='0'*64))
reject_budget('cache_enabled',lambda a,b,c,r:b['phase_budget_amendment'].update(task_build_cache=True))
reject_budget('wrong_prior_timeout_identity',lambda a,b,c,r:b['phase_budget_amendment'].update(prior_run='38441089594'))
reject_budget('cleanup_budget_reduced',lambda a,b,c,r:b['cleanup_budget'].update(nominal_max_seconds_including_kill_grace=200))
reject_budget('clock_rebase_permitted',lambda a,b,c,r:b['clock_amendment'].update(no_rebase=False))
reject_budget('missing_new_authorization',lambda a,b,c,r:b['phase_budget_amendment'].update(user_authorization_quote_as_relayed=''))
reject_budget('wrong_real_PR_head',lambda a,b,c,r:r.update(head_sha='d30fc6c805b58951d657f2505381f4f503aba1ef'))
archive_case('old-clock2-branch-rejected',reject=True,meta_edit=lambda m:m['artifacts'][0]['workflow_run'].update(head_branch='codex/kvm-pr20-candidate2-clock2-20261013'))
archive_case('old-clock2-active-rejected',reject=True,meta_edit=lambda m:m['artifacts'][0]['workflow_run'].update(head_sha='9907a8f96fe0d20061f0a7deba6a69d4416ced50'))
# Changing both ledger clock cutoffs cannot silently reactivate the historical budget.
contents=copy.deepcopy(valid);changed=copy.deepcopy(clock);changed['deadlines_minutes']=[32,38,52,55,60];contents['clock.json']=data(changed)
result=v.audit_execution(contents,trusted);assert 'clock deadlines mismatch' in result['issues']
checks.append({'case':'raw_clock_uses_old_budget_rejected','status':'PASS'})
report={'status':'PASS','scope':'Synthetic/offline preparation only; no actual run38441089594 ZIP is present or verified.','checks':checks,'verifier_sha256':v.sha((ROOT/'verify_artifact.py').read_bytes()),'work':str(work),'network_requests':0,'native_or_build_commands':0}
(ROOT/'synthetic-check.json').write_bytes(data(report));print(json.dumps({'status':'PASS','checks':len(checks),'report':str(ROOT/'synthetic-check.json')}))
