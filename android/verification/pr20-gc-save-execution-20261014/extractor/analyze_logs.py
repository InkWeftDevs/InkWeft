#!/usr/bin/env python3
"""Strict offline causal-log closure. Only local read-only Git checks; no network, native execution or acceptance classification."""
import argparse,base64,binascii,datetime,hashlib,json,re,subprocess,sys
from pathlib import Path
import yaml
sys.dont_write_bytecode=True
import frozen_markers

PREFIX='INKWEFT_CAUSAL_'
INPUT_FILES={'approved_plan_sha256':'PLAN.md','source_spec_sha256':'source-spec.json','method_plan_sha256':'method-plan.json','original_protocol_sha256':'original-protocol.json','marker_contract_sha256':'marker-contract.json','prepare_sources_sha256':'prepare_sources.py'}
CONTEXT_KEYS='scope run_id run_attempt workflow_sha source_commit source_spec_sha256 method_plan_sha256 test_source_sha256 source_manifest_sha256 selected_apks apk_receipt_sha256 installed_apks install_log_sha256 environment_sha256 ordinal identity seed args install_group selection raw_log_sha256 records_sha256 junit_status adb_exit process_exit_outcome'
METHOD_KEYS='ordinal phase identity selection status junit_status adb_exit process_exit_outcome error'
FINAL_METHOD_KEYS='ordinal identity phase selection seed status junit_status adb_exit process_exit_outcome log_sha256'
FINAL_KEYS='scope run_id workflow_sha input_hashes status cleanup_verified cleanup methods install_groups device_before_sha256 device_after_sha256 missing_files packaging_complete manifest_sha256 summary_sha256'
BINDING_STATUS='BOUND'
FROZEN_MARKERS_SHA='850707164f4c89ae1920c1f9c99ad9e1a68be7f71b8c456b437671ddada96bda'
GIT_BINDING={'source_commit':'fcc8dd39f029f4bf04f4c3f1bccc1e3ed4bfc353','readiness_git':'fbc502eb49c15018bb1ab2694dc256b7a0f9fc25','prep_sha':'083a892133d818cf364865d3ee3edc1fdb6413c0','workflow_sha':'c3483b74e5db7cb7928d26445bd37af51700284c'}
RUN_BINDING={'run_id':'38553018139','job_id':'115815277181'}
EXPECTED_SELECTIONS=[
    'org.inkweft.data.HistoryCollectionTest#'+name for name in [
        'sameRoomAuthorQueuedBeforeDeleteCommitPreservesNewRoot',
        'separateRoomAuthorQueuedBeforeDeleteCommitPreservesNewRoot',
        'interruptedBatchRollsBackAndReopenedCollectionResumes',
        'malformedTransformAfterAFullRootBatchAbortsBeforeAnyDeletion',
        'missingRoomGuardLogAbortsWithoutDeletingCandidates']
]+['org.inkweft.data.HistoryDeletionBoundaryDiagnosticsTest#'+name for name in
   ['sameRoomNaturalArrivalBoundaries','separateRoomNaturalArrivalBoundaries']]+[
    'org.inkweft.data.LibrarySnapshotHoldDiagnosticsTest#idleHeldSnapshotAndActiveSearchSaveBoundaries']
FAILURE_BINDING_KEYS='run_id run_attempt workflow_sha source_commit source_spec_sha256 method_plan_sha256 test_source_sha256 ordinal identity selection status junit_status adb_exit process_exit_outcome'
FAILURE_REASONS={'NORMAL_FORWARD_ALREADY_STARTED','RAW_OVERSIZE','HASH_MISMATCH','SENSITIVE','INVALID_UTF8','ENCODED_OVERSIZE'}
SECRET=re.compile(rb'gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[A-Z0-9]{16}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|(?:ssh-rsa|ssh-ed25519) [A-Za-z0-9+/]{40,}')

def require(ok,message):
    if not ok:raise ValueError(message)
def sha(data):return hashlib.sha256(data).hexdigest()
def compact(value):return json.dumps(value,ensure_ascii=False,separators=(',',':')).encode()
def strict_json(text):
    def pairs(values):
        result={}
        for key,value in values:require(key not in result,'Duplicate JSON key');result[key]=value
        return result
    def bad(value):raise ValueError('Non-finite JSON')
    return json.loads(text,object_pairs_hook=pairs,parse_constant=bad)
def keys(value,expected):require(type(value)is dict and set(value)==set(expected.split()),'Envelope keys differ')
def digest(value):require(type(value)is str and re.fullmatch('[0-9a-f]{64}',value),'Invalid SHA256')
def epoch(value):
    require(type(value)is str and re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?Z',value),'Expected UTC timestamp')
    return datetime.datetime.fromisoformat(value.replace('Z','+00:00')).timestamp()

def frozen_inputs(directory,source_commit,readiness_git,control_root,binding):
    values={key:(directory/name).read_bytes()for key,name in INPUT_FILES.items()}
    hashes={k:sha(v)for k,v in values.items()};plan=strict_json(values['method_plan_sha256']);spec=strict_json(values['source_spec_sha256']);contract=strict_json(values['marker_contract_sha256'])
    require(plan['instrumentation_calls']==plan['expected_junit_methods']==len(plan['steps'])==8,'Fixed 8 methods required')
    require([s['ordinal']for s in plan['steps']]==list(range(1,9)),'Method ordinal order')
    require([s['selection']for s in plan['steps']]==EXPECTED_SELECTIONS,'Exact eight-method selection differs')
    require([s['phase']for s in plan['steps']]==['data-diagnostics']*8,'Phase plan differs')
    require({s['identity']for s in plan['steps']}=={'C1'} and len({s['install_group']for s in plan['steps']})==1,'One source/one group required')
    require([s['ordinal']for s in plan['steps']if s['marker_policy']=='none']==list(range(1,6)),'Guard/marker scope differs')
    require([s['ordinal']for s in plan['steps']if s['marker_policy']=='required']==[6,7,8],'Diagnostic marker scope differs')
    require(contract.get('review_status')=='READY','Draft marker semantics must not be bound')
    require(spec['status']==contract['status']=='FROZEN' and list(spec['identities'])==['C1'] and spec['identities']['C1']['git']==source_commit,'Single frozen source differs')
    require(spec['common_probes']==[] and spec['identities']['C1']['production_overrides']==[],'Unexpected overlay')
    require(hashes['original_protocol_sha256']=='1065177d7809426d949c4b4b67bd639a47c3fc1943f706a77c2dfabb985e9aba','Public protocol changed')
    auth=strict_json((control_root/'ci/pr20-gc-save/authorization.json').read_bytes())
    require(auth['approved']is True and auth['status']=='FROZEN' and auth['plan_git']==readiness_git,'Readiness/authorization identity differs')
    require(all(auth[k]==v for k,v in hashes.items()) and binding['input_hashes']==hashes,'Frozen input hashes differ')
    require(auth['phase_cutoff_minutes']=={'setup':30,'data':45,'work':55} and auth['job_timeout_minutes']==60,'Budget differs')
    require(auth['task_build_cache']is False and auth['new_permissions']is False and auth['automatic_retry']is False,'Execution scope differs')
    manifest=strict_json((directory/'C1-source-manifest.json').read_bytes())
    canonical=sha(json.dumps(manifest['files'],sort_keys=True,separators=(',',':')).encode())
    require(canonical==manifest['canonical_manifest_sha256']==spec['identities']['C1']['canonical_manifest_sha256'],'Canonical source manifest differs')
    require(source_commit==GIT_BINDING['source_commit']and readiness_git==GIT_BINDING['readiness_git'],'Reviewed source/readiness differs')
    require(type(contract['reviewed_source_sha256'])is dict and len(contract['reviewed_source_sha256'])==10,'Missing reviewed source files')
    for path,expected_hash in contract['reviewed_source_sha256'].items():require(manifest['files'][path]['sha256']==expected_hash,'Reviewed production/test hash differs')
    for entry in plan['steps']:
        rule=contract['methods'][str(entry['ordinal'])]
        require(entry['selection']==rule['selection'] and entry['marker_policy']==rule['marker_policy'],'Method marker mapping differs')
        require(manifest['files'][rule['test_source_path']]['sha256']==rule['test_source_sha256'],'Test hash/source binding differs')
    controls=binding['control_hashes'];required={'ci/pr20-gc-save/'+f for f in ['run.py','markers.py','package.py','cleanup.py','authorization.json','workflow.proposed.yml']}
    require(type(controls)is dict and required<=set(controls),'Missing control hashes')
    workflows=[p for p in controls if p.startswith('.github/workflows/')and p.endswith('.yml')];require(len(workflows)==1,'Exactly one active workflow required')
    for relative,h in controls.items():
        digest(h);p=Path(relative);require(not p.is_absolute()and'..'not in p.parts,'Unsafe control relative path')
        target=(control_root/p).resolve();require(target.is_relative_to(control_root.resolve())and sha(target.read_bytes())==h,'Control file hash differs: '+relative)
    require(controls['ci/pr20-gc-save/markers.py']==FROZEN_MARKERS_SHA==sha(Path(frozen_markers.__file__).read_bytes()),'Validator differs from frozen controller')
    require(workflows==['.github/workflows/pr20-gc-save.yml'],'Unexpected active workflow path')
    require((control_root/workflows[0]).read_bytes()==(control_root/'ci/pr20-gc-save/workflow.proposed.yml').read_bytes(),'Active/proposed workflow bytes differ')
    workflow=yaml.safe_load((control_root/workflows[0]).read_text());require(workflow['permissions']=={'contents':'read'}and len(workflow['jobs'])==1,'Workflow permission/job scope differs')
    job=next(iter(workflow['jobs'].values()));require(job['timeout-minutes']==60 and job['runs-on']=='ubuntu-24.04','Runner budget differs')
    require('github.run_attempt == 1'in job['if'] and 'github.event.created == false'in job['if'],'Activation guard differs')
    guard=re.findall('[0-9a-f]{40}',job['if']);require(guard==[binding['prep_sha']]and guard[0]!='0'*40,'Exact prep guard not bound')
    phases={}
    for step in job['steps']:
        command=step.get('run','');match=re.search(r'run\.py (setup|build C1|emulator|methods data)(?:\s|$)',command)
        if match:phases[step['name']]='data'if match[1]=='methods data'else'setup'
    require(list(phases.values())==['setup','setup','setup','data'],'Workflow phase wrappers differ')
    branches=workflow['on']['push']['branches'];require(type(branches)is list and len(branches)==1,'One fixed push branch required')
    return {'plan':plan,'spec':spec,'contract':contract,'hashes':hashes,'phases':phases,'auth':auth,'branch':branches[0],'workflow_path':workflows[0]}

def verify_local_git(control_root,directory,expected,frozen,binding):
    def git(*args):
        return subprocess.check_output(['git','-C',str(control_root),*args],stderr=subprocess.PIPE,timeout=30)
    require(git('rev-parse','HEAD').decode().strip()==expected['workflow_sha'],'Local active HEAD differs')
    active_parents=git('rev-list','--parents','-n','1',expected['workflow_sha']).decode().split()
    require(active_parents==[expected['workflow_sha'],expected['prep_sha']],'Active commit must have exactly the reviewed prep parent')
    prep_parents=git('rev-list','--parents','-n','1',expected['prep_sha']).decode().split()
    require(prep_parents==[expected['prep_sha'],expected['readiness_git']],'Prep commit must have exactly the reviewed readiness parent')
    git('merge-base','--is-ancestor',expected['source_commit'],expected['readiness_git'])
    require(git('rev-parse',expected['source_commit']+'^{tree}').decode().strip()==frozen['spec']['identities']['C1']['tree'],'Actual source Git tree differs')
    for name in [*INPUT_FILES.values(),'C1-source-manifest.json','SHA256SUMS']:
        require(git('show',expected['readiness_git']+':android/verification/pr20-gc-save-20261014/'+name)==(directory/name).read_bytes(),'Readiness Git blob differs: '+name)
    for path,expected_hash in frozen['contract']['reviewed_source_sha256'].items():
        require(sha(git('show',expected['source_commit']+':'+path))==expected_hash,'Reviewed source Git blob differs: '+path)
    for relative,expected_hash in binding['control_hashes'].items():
        require(sha(git('show',expected['workflow_sha']+':'+relative))==expected_hash,'Active control Git blob differs: '+relative)
    return {'active':expected['workflow_sha'],'prep':expected['prep_sha'],'readiness':expected['readiness_git'],'source':expected['source_commit'],'readiness_files_verified':8,'reviewed_source_files_verified':len(frozen['contract']['reviewed_source_sha256']),'control_files_verified':len(binding['control_hashes']),'network':False,'git_operations':'read-only'}

def official_metadata(binding,run,jobs,expected,frozen):
    for key in ['run_id','job_id','workflow_sha','source_commit','readiness_git','prep_sha']:
        require(binding[key]==expected[key],'Expected official binding differs: '+key)
    require(binding['scope']=='DIAGNOSTIC_ONLY'and str(binding['run_attempt'])=='1','Binding scope/attempt differs')
    require(str(run['id'])==expected['run_id']and run['head_sha']==expected['workflow_sha']and type(run['run_attempt'])is int and run['run_attempt']==1,'Actual run identity differs')
    require(run['event']=='push' and run['head_branch']==frozen['branch'] and run['path']==frozen['workflow_path'],'Official event/branch/workflow path differs')
    require(type(jobs['total_count'])is int and jobs['total_count']==1 and len(jobs['jobs'])==1,'Expected exactly one official job')
    selected=[j for j in jobs['jobs']if str(j['id'])==expected['job_id']];require(len(selected)==1,'Missing/duplicate official job')
    job=selected[0];require(str(job['run_id'])==expected['run_id']and job['head_sha']==expected['workflow_sha']and type(job['run_attempt'])is int and job['run_attempt']==1,'Actual job identity differs')
    for key,actual in [('run_status',run['status']),('run_conclusion',run['conclusion']),('job_status',job['status']),('job_conclusion',job['conclusion'])]:require(binding[key]==actual,'Binding metadata differs: '+key)
    start,end=epoch(job['started_at']),epoch(job['completed_at']);require(0<=end-start<=3600,'Official job hard 60min budget exceeded')
    return job

def failure_text(value,step,frozen,expected,state,active,original,line_number):
    """Audit the all-or-refuse failure stream independently of its producer."""
    require(type(value)is dict and type(value.get('schema'))is int and value['schema']==2
            and value.get('scope')=='DIAGNOSTIC_FAILURE_ONLY','Failure schema/scope')
    bounds=frozen['contract']['transport']['methods'][str(step['ordinal'])]
    require(frozen['contract']['transport']['kind']=='MUTUALLY_EXCLUSIVE_NORMAL_OR_FULL_BASE64_FAILURE'
            and frozen['contract']['transport']['chunk_bytes']==32768,'Failure transport contract')
    kind=value.get('kind');require(kind in ['BEGIN','PART','END','REFUSED'],'Failure kind');complete_data=None
    if kind in ['BEGIN','REFUSED']:
        require(not state['failures'],'Duplicate failure stream')
        keys(value,'schema scope binding raw_bytes raw_sha256 kind '+('encoding chunk_bytes parts'if kind=='BEGIN'else'reason'))
        binding=value['binding'];keys(binding,FAILURE_BINDING_KEYS)
        require(type(binding['run_attempt'])is int and binding['run_attempt']==1,'Failure attempt')
        require(type(binding['ordinal'])is int and all(binding[k]==step[k]for k in ['ordinal','identity','selection']),'Failure method identity')
        require(all(binding[k]==expected[k]for k in ['run_id','workflow_sha','source_commit']),'Failure run/source identity')
        require(all(binding[k]==frozen['hashes'][k]for k in ['source_spec_sha256','method_plan_sha256']),'Failure frozen input hash')
        require(binding['test_source_sha256']==frozen['contract']['methods'][str(step['ordinal'])]['test_source_sha256'],'Failure test hash')
        require(binding['status']in ['FAIL','INCOMPLETE']and binding['junit_status']in ['PASS','FAIL','INCOMPLETE',None],'Failure status')
        require(binding['adb_exit']is None or type(binding['adb_exit'])is int,'Failure adb exit type')
        require(binding['process_exit_outcome']in ['NATURAL_EXIT','UNCONFIRMED',None],'Failure process outcome')
        require(type(value['raw_bytes'])is int and value['raw_bytes']>=0,'Failure raw length')
        if value['raw_sha256']is not None:digest(value['raw_sha256'])
        if kind=='BEGIN':
            require(active is None,'Normal and complete failure forwarding are mutually exclusive')
            require(value['encoding']=='base64-utf8'and type(value['chunk_bytes'])is int and value['chunk_bytes']==32768,'Failure encoding')
            require(value['raw_bytes']<=bounds['failure_raw_max_bytes'],'Failure original exceeds frozen cap')
            digest(value['raw_sha256'])
            require(type(value['parts'])is int and value['parts']==(value['raw_bytes']+32767)//32768,'Failure part count')
            context=sha(json.dumps(value,ensure_ascii=True,separators=(',',':')).encode())
        else:
            require(value['reason']in FAILURE_REASONS,'Failure refusal reason')
            require((active is not None)==(value['reason']=='NORMAL_FORWARD_ALREADY_STARTED'),'Failure refusal/normal-stream mismatch')
            if value['reason']=='RAW_OVERSIZE':require(value['raw_bytes']>bounds['failure_raw_max_bytes'],'Unproven oversize refusal')
            if active is not None:require(value['raw_sha256']==active['context']['raw_log_sha256'],'Refusal/normal original differs')
            context=None
        state['failures'].append({'binding':binding,'begin':value,'context_sha256':context,'chunks':[],
            'original_envelopes':[],'wire_bytes':0,'line':line_number,'status':'OPEN'if kind=='BEGIN'else'REFUSED',
            'matched_method_receipt':False})
    else:
        require(len(state['failures'])==1 and state['failures'][0]['status']=='OPEN','Failure PART/END outside open stream')
    failure=state['failures'][0];begin=failure['begin']
    if kind=='PART':
        keys(value,'schema scope kind context_sha256 part parts raw_bytes raw_sha256 base64')
        index=len(failure['chunks'])
        require(value['context_sha256']==failure['context_sha256']and type(value['part'])is int and value['part']==index,'Failure context/part order')
        require(type(value['parts'])is int and value['parts']==begin['parts']and index<begin['parts'],'Failure parts mismatch')
        require(type(value['base64'])is str and len(value['base64'])<=43692,'Failure base64 bounds')
        chunk=base64.b64decode(value['base64'],validate=True)
        require(base64.b64encode(chunk).decode('ascii')==value['base64'],'Noncanonical base64')
        require(type(value['raw_bytes'])is int and value['raw_bytes']==len(chunk)==min(32768,begin['raw_bytes']-32768*index),'Failure chunk size')
        require(sha(chunk)==value['raw_sha256'],'Failure chunk hash')
        failure['chunks'].append(chunk)
    elif kind=='END':
        keys(value,'schema scope kind context_sha256 parts raw_bytes raw_sha256')
        require(type(value['parts'])is int and type(value['raw_bytes'])is int,'Failure footer integer types')
        require(value['context_sha256']==failure['context_sha256']and value['parts']==begin['parts']==len(failure['chunks'])
                and value['raw_bytes']==begin['raw_bytes']and value['raw_sha256']==begin['raw_sha256'],'Failure footer mismatch')
        data=b''.join(failure['chunks'])
        require(len(data)==begin['raw_bytes']and sha(data)==begin['raw_sha256'],'Failure complete original hash')
        data.decode('utf-8',errors='strict');require(not SECRET.search(data),'Failure original matches fixed sensitive pattern')
        complete_data=data
    failure['original_envelopes'].append({'original_json':original,'payload_bytes':len(original.encode()),'payload_sha256':sha(original.encode())})
    # Include the same conservative official line framing reserved by the producer.
    failure['wire_bytes']+=len((PREFIX+'FAILURE_TEXT '+original+'\n').encode())+64
    require(failure['wire_bytes']<=bounds['failure_wire_max_bytes'],'Failure wire cap exceeded')
    if complete_data is not None:failure['raw']=complete_data;failure['status']='FORWARDED'


def audit_failed_final(final,state,frozen,expected,job,clock):
    """Check a failure terminal without mistaking it for eight successful calls."""
    keys(final,FINAL_KEYS)
    require(final['scope']=='DIAGNOSTIC_ONLY'and final['run_id']==expected['run_id']
            and final['workflow_sha']==expected['workflow_sha']and final['input_hashes']==frozen['hashes'],'Failed FINAL identity/hash')
    require(final['status']in ['BUDGET_ABORTED','BLOCKED_OR_FAILED','INCOMPLETE_OUTER_TIMEOUT_OR_INTERRUPTION',
            'INCOMPLETE_EXECUTION_NOT_FINISHED','CLEANUP_UNCONFIRMED','EVIDENCE_INCOMPLETE'],'Failed FINAL status')
    require(type(final['packaging_complete'])is bool and type(final['missing_files'])is list
            and all(type(name)is str and Path(name).name==name and name not in ['.','..']for name in final['missing_files']),'Failed FINAL packaging fields')
    require(not final['packaging_complete']or not final['missing_files'],'Packaging claim contradicts missing files')
    for key in ['manifest_sha256','summary_sha256']:digest(final[key])
    for key in ['device_before_sha256','device_after_sha256']:
        if final[key]is not None:digest(final[key])
    if final['device_after_sha256']is not None:
        require(final['device_before_sha256']==final['device_after_sha256'],'Failed FINAL device changed')
    keys(final['cleanup'],'trap always')
    require(final['cleanup_verified']is True,'Failed FINAL cleanup not confirmed')
    for clean in final['cleanup'].values():
        keys(clean,'emulator command_groups acl error_count before_sha256 after_sha256 exact_match')
        require(clean['emulator']==clean['command_groups']=='STOPPED_CONFIRMED'
                and clean['acl']in ['UNCHANGED_EXACT','RESTORED_EXACT']and type(clean['error_count'])is int
                and clean['error_count']==0 and clean['exact_match']is True,'Failed FINAL cleanup/ACL')
        digest(clean['before_sha256']);digest(clean['after_sha256'])
        require(clean['before_sha256']==clean['after_sha256'],'Failed FINAL ACL bytes differ')
    require(final['cleanup']['trap']['before_sha256']==final['cleanup']['always']['before_sha256'],'Failed FINAL ACL origins differ')
    plan=frozen['plan']['steps'];failed=state['failed_method']['receipt'];ordinal=failed['ordinal']
    require(type(final['methods'])is list and len(final['methods'])==8,'Failed FINAL needs all eight states')
    for receipt,step in zip(final['methods'],plan):
        keys(receipt,FINAL_METHOD_KEYS)
        require(all(type(receipt[k])is type(step[k])and receipt[k]==step[k]
                    for k in ['ordinal','identity','phase','selection','seed']),'Failed FINAL method mapping')
        if receipt['ordinal']<ordinal:
            method=state['methods'][receipt['ordinal']-1];block=state['blocks'][receipt['ordinal']-1]
            require(all(type(receipt[k])is type(v)and receipt[k]==v for k,v in method.items()if k!='error'),'Failed FINAL prior METHOD differs')
            require(receipt['log_sha256']==block['context']['raw_log_sha256'],'Failed FINAL prior raw hash')
        elif receipt['ordinal']==ordinal:
            require(all(type(receipt[k])is type(v)and receipt[k]==v for k,v in failed.items()if k!='error'),'Failed FINAL failed METHOD differs')
            if state['failures']and state['failures'][0]['status']=='FORWARDED':
                require(receipt['log_sha256']==state['failures'][0]['begin']['raw_sha256'],'Failed FINAL original hash differs')
            if receipt['log_sha256']is not None:digest(receipt['log_sha256'])
            normal=[b for b in state['blocks']if b['context']['ordinal']==ordinal]
            if normal:require(receipt['log_sha256']==normal[0]['context']['raw_log_sha256'],'Failed FINAL normal original differs')
        else:
            require(receipt['status']=='NOT_RUN'and all(receipt[k]is None for k in
                    ['junit_status','adb_exit','process_exit_outcome','log_sha256']),'Method ran after first failure')
    groups={s['install_group']for s in plan};require(set(final['install_groups'])==groups,'Failed FINAL install group set')
    for value in final['install_groups'].values():
        keys(value,'identity status');require(value['identity']=='C1'and value['status']in ['INCOMPLETE','FAIL'],'Failed FINAL install group status')
    contexts=[b['context']for b in state['blocks']]
    if contexts:
        require(all(all(c[k]==contexts[0][k]for k in ['selected_apks','installed_apks','apk_receipt_sha256',
                    'install_log_sha256','environment_sha256','source_manifest_sha256'])for c in contexts),'Failed FINAL group identity changed')
        require(contexts[0]['environment_sha256']==final['device_before_sha256'],'Failed FINAL before environment hash')
    phase_times=[];prior_end=clock['budget_anchor_epoch']
    for name,phase in frozen['phases'].items():
        matches=[s for s in job['steps']if s['name']==name];require(len(matches)==1,'Failed FINAL official phase missing/duplicate')
        step=matches[0];start,end=epoch(step['started_at']),epoch(step['completed_at'])
        require(step['status']=='completed'and prior_end<=start<=end<=epoch(job['completed_at'])+1
                and epoch(job['started_at'])<=start,'Failed FINAL official phase span/order')
        prior_end=end
        if phase=='setup':
            require(step['conclusion']=='success'and end<=clock['deadline_epochs']['setup'],'Failed FINAL earlier setup incomplete/late')
        else:
            require(step['conclusion']in ['failure','timed_out','cancelled'],'Failed FINAL data phase outcome')
            for block in state['blocks']:
                require(start<=block['first_receipt_epoch']<=end+1,'Failed FINAL context outside official data step')
                if block['method_pass']:require(block['method_receipt_epoch']<=end+1,'Failed FINAL METHOD outside data step')
            require(start<=state['failed_method']['epoch']<=end+1,'Failed METHOD outside data phase')
        phase_times.append({'name':name,'phase':phase,'start_epoch':start,'end_epoch':end,
            'within_success_deadline':end<=min(clock['deadline_epochs'][phase],clock['deadline_epochs']['work'])})
    return {'status':'FAILED_TERMINAL_AUDITED_NOT_PASS','passed_prefix':len(state['methods']),
        'first_failed_ordinal':ordinal,'successors_not_run':8-ordinal,'dual_cleanup_exact':True,
        'failure_text_audited':state['failed_method']['failure_text_audited'],
        'packaging_complete':final['packaging_complete'],'missing_files':final['missing_files'],'phase_times':phase_times}


def parse(text,frozen,expected,state,job):
    plan,contract,spec=(frozen[k]for k in ['plan','contract','spec']);methods=state['methods'];blocks=state['blocks'];active=None;clock=None;final=None;last_time=None
    for line_number,raw in enumerate(text.split('\n'),1):
        stamp=re.match(r'^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?Z)\s+(.*)$',raw)
        line=stamp[2]if stamp else raw
        if not line.startswith(PREFIX):continue
        state['last_receipt_line']=line_number
        require(stamp is not None,'Official receipt lacks UTC log timestamp')
        at=epoch(stamp[1]);require(last_time is None or at>=last_time,'Official receipt timestamp reversed');last_time=at
        require(epoch(job['started_at'])<=at<=epoch(job['completed_at'])+1,'Receipt outside official job span')
        tag,sep,encoded=line.partition(' ');require(sep and len(encoded.encode())<=2*1024*1024,'Malformed/oversize log envelope')
        kind=tag[len(PREFIX):];require(kind in ['CLOCK','CONTEXT','RECORD','END','METHOD','FINAL','FAILURE_TEXT'],'Unknown causal prefix')
        value=strict_json(encoded);require(final is None,'Receipt after FINAL')
        require(state.get('failed_method')is None or kind=='FINAL','Receipt after failed method; fail-stop violated')
        if kind=='CLOCK':
            require(clock is None and not methods and active is None,'Duplicate/late CLOCK')
            keys(value,'status scope anchor_kind budget_anchor_epoch first_step_observed_epoch actual_job_started_epoch deadlines_minutes deadline_epochs')
            start=value['budget_anchor_epoch'];require(type(start)is int and start>0 and value['status']=='PASS'and value['scope']=='DIAGNOSTIC_ONLY'and value['anchor_kind']=='runner_first_step','Invalid clock')
            require(start<=value['first_step_observed_epoch']<start+1 and value['actual_job_started_epoch']is None,'Clock anchor differs')
            require(epoch(job['started_at'])<=value['first_step_observed_epoch']<=at,'First step clock outside actual job')
            require(value['deadlines_minutes']==[30,45,55,60]and value['deadline_epochs']=={k:start+m*60 for k,m in [('setup',30),('data',45),('work',55)]},'Clock cutoff differs')
            clock=value;state['clock']=value;continue
        require(clock is not None,'Missing CLOCK')
        if kind in ['CONTEXT','RECORD','END','METHOD','FAILURE_TEXT']:
            require(len(methods)<8,'Extra method block')
            step=plan['steps'][len(methods)];phase='data'
            # Late failure diagnostics remain bounded by the real job span above;
            # they never widen the deadlines for successful method evidence.
            if kind!='FAILURE_TEXT'and not(kind=='METHOD'and type(value)is dict and value.get('status')!='PASS'):
                require(at<=min(clock['deadline_epochs'][phase],clock['deadline_epochs']['work']),'Method receipt after phase/work deadline')
        if kind=='FAILURE_TEXT':
            failure_text(value,step,frozen,expected,state,active,encoded,line_number)
            continue
        if kind in ['CONTEXT','RECORD','END']:require(not state['failures'],'Validated marker block after failure envelope')
        if kind=='CONTEXT':
            require(active is None,'Nested/duplicate CONTEXT');keys(value,CONTEXT_KEYS)
            require(all(value[k]==step[k]for k in ['ordinal','identity','seed','args','install_group','selection']),'CONTEXT plan mapping differs')
            require(type(value['ordinal'])is int and value['identity']=='C1','Invalid method identity')
            require(value['scope']=='DIAGNOSTIC_ONLY'and value['run_id']==expected['run_id']and str(value['run_attempt'])=='1'and value['workflow_sha']==expected['workflow_sha'],'CONTEXT run identity differs')
            require(value['source_commit']==expected['source_commit']and value['source_manifest_sha256']==spec['identities']['C1']['canonical_manifest_sha256'],'CONTEXT source differs')
            for key in ['source_spec_sha256','method_plan_sha256']:require(value[key]==frozen['hashes'][key],'CONTEXT frozen hash differs')
            require(value['test_source_sha256']==contract['methods'][str(step['ordinal'])]['test_source_sha256'],'CONTEXT test hash differs')
            for k,v in value.items():
                if k.endswith('_sha256'):digest(v)
            require(value['junit_status']=='PASS'and type(value['adb_exit'])is int and value['adb_exit']==0 and value['process_exit_outcome']=='NATURAL_EXIT','CONTEXT JUnit/isolation differs')
            selected=value['selected_apks'];installed=value['installed_apks'];packages=['org.inkweft.data.test']
            require(type(selected)is list and type(installed)is list and [x['package']for x in selected]==[x['package']for x in installed]==packages,'APK package group differs')
            for one,two in zip(selected,installed):
                keys(one,'package sha256 signer_sha256');keys(two,'package sha256 source_apk_sha256');digest(one['sha256']);require(one['sha256']==two['sha256']==two['source_apk_sha256'],'Selected/installed APK mismatch')
                require(type(one['signer_sha256'])is list and len(one['signer_sha256'])==1,'Signer missing');digest(one['signer_sha256'][0])
            active={'context':value,'records':[],'payloads':[],'ordered':[],'ended':False,'method_pass':False,'line':line_number,'first_receipt_epoch':at};blocks.append(active)
        elif kind=='RECORD':
            require(active is not None and not active['ended'],'Record outside open block');keys(value,'ordinal identity sequence marker payload_bytes payload_sha256 original_json')
            require(type(value['ordinal'])is int and value['ordinal']==step['ordinal']and value['identity']=='C1'and type(value['sequence'])is int and value['sequence']==len(active['records']),'Record order/identity differs')
            rule=contract['methods'][str(step['ordinal'])];marker=value['marker'];require(marker in rule['markers']and type(value['original_json'])is str,'Unexpected record marker')
            original=value['original_json'];raw_payload=original.encode();require(type(value['payload_bytes'])is int and value['payload_bytes']==len(raw_payload)<=rule['markers'][marker]['payload_max_bytes']and sha(raw_payload)==value['payload_sha256'],'Original payload size/hash differs')
            data=strict_json(original)
            if rule['kind']!='gc-boundary':frozen_markers.validate_shape(data,contract['schemas'][marker])
            active['records'].append({'marker':marker,'data':data});active['payloads'].append(original);active['ordered'].append({k:value[k]for k in ['sequence','marker','payload_bytes','payload_sha256']})
            require(len(active['records'])<=sum(r['max_count']for r in rule['markers'].values()),'Too many records')
        elif kind=='END':
            require(active is not None and not active['ended'],'Missing/duplicate END');keys(value,'ordinal identity count ordered_payloads ordered_payloads_sha256')
            require(type(value['ordinal'])is int and value['ordinal']==step['ordinal']and value['identity']=='C1'and type(value['count'])is int and value['count']==len(active['records']),'END count differs')
            require(type(value['ordered_payloads'])is list,'Invalid footer list')
            for item in value['ordered_payloads']:
                keys(item,'sequence marker payload_bytes payload_sha256')
                require(type(item['sequence'])is int and type(item['payload_bytes'])is int and item['sequence']>=0 and item['payload_bytes']>=0 and type(item['marker'])is str,'Invalid footer types')
                digest(item['payload_sha256'])
            require(value['ordered_payloads']==active['ordered']and sha(compact(value['ordered_payloads']))==value['ordered_payloads_sha256'],'Ordered payload footer differs')
            raw='\n'.join(r['marker']+' '+p for r,p in zip(active['records'],active['payloads']))
            frozen_markers.safe_marker_payloads(step,raw,contract)
            require(sha(compact(active['records'])+b'\n')==active['context']['records_sha256'],'Complete parsed records hash differs')
            active['ended']=True;active['end_line']=line_number
        elif kind=='METHOD':
            keys(value,METHOD_KEYS);state['observed_method_receipts'].append(value)
            require(type(value['ordinal'])is int and all(value[k]==step[k]for k in ['ordinal','phase','identity','selection']),'METHOD order differs')
            if state['failures']:
                failure=state['failures'][0];payload=failure['binding']
                require(failure['status']in ['FORWARDED','REFUSED'],'Incomplete failure text stream before METHOD')
                require(all(type(value[k])is type(payload[k])and value[k]==payload[k]for k in ['ordinal','identity','selection','status','junit_status','adb_exit','process_exit_outcome']),'Failure envelope/METHOD differ')
                failure['matched_method_receipt']=True
            if value['status']!='PASS':
                require(value['status']in ['FAIL','INCOMPLETE']and value['junit_status']in ['PASS','FAIL','INCOMPLETE',None],'Non-PASS METHOD status')
                require(value['adb_exit']is None or type(value['adb_exit'])is int,'Non-PASS METHOD exit type')
                require(value['process_exit_outcome']in ['NATURAL_EXIT','UNCONFIRMED',None],'Non-PASS METHOD process outcome')
                require(value['error']is None or type(value['error'])is str,'Non-PASS METHOD error type')
                state['failed_method']={'receipt':value,'line':line_number,'epoch':at,'failure_text_audited':bool(state['failures'])}
                continue
            require(value['status']==value['junit_status']=='PASS'and type(value['adb_exit'])is int and value['adb_exit']==0 and value['process_exit_outcome']=='NATURAL_EXIT'and value['error']is None,'METHOD failed/incomplete/skipped')
            require(active is not None and active['ended']and active['context']['ordinal']==step['ordinal'],'Missing complete method block')
            active['method_pass']=True;active['method_receipt_epoch']=at;methods.append(value);active=None
        else:
            state['final_observed']=value
            if state.get('failed_method')is not None:
                final=value;state['failed_final_audit']=audit_failed_final(value,state,frozen,expected,job,clock)
            else:
                require(active is None and len(methods)==len(blocks)==8,'FINAL before all 8 closures');final=value
    if state.get('failed_method')is not None:
        require(final is not None,'Failed run lacks FINAL cleanup/evidence receipt')
        require(False,'First non-PASS method: failure evidence and terminal audit never promote whole-run PASS')
    require(clock is not None and final is not None and active is None and len(methods)==len(blocks)==8,'Missing/unfinished receipt set')
    require(not state['failures'],'Failure evidence cannot complete a whole run')
    keys(final,FINAL_KEYS);require(final['scope']=='DIAGNOSTIC_ONLY'and final['run_id']==expected['run_id']and final['workflow_sha']==expected['workflow_sha']and final['input_hashes']==frozen['hashes'],'FINAL input/identity mismatch')
    require(final['status']=='EXECUTION_COMPLETE_ANALYSIS_PENDING'and final['cleanup_verified']is True and final['packaging_complete']is True and final['missing_files']==[],'FINAL incomplete execution/evidence/cleanup')
    for key in ['device_before_sha256','device_after_sha256','manifest_sha256','summary_sha256']:digest(final[key])
    require(final['device_before_sha256']==final['device_after_sha256'],'Device environment changed')
    keys(final['cleanup'],'trap always')
    for clean in final['cleanup'].values():
        keys(clean,'emulator command_groups acl error_count before_sha256 after_sha256 exact_match');require(clean['emulator']==clean['command_groups']=='STOPPED_CONFIRMED'and clean['acl']in ['UNCHANGED_EXACT','RESTORED_EXACT']and type(clean['error_count'])is int and clean['error_count']==0 and clean['exact_match']is True,'Cleanup claim not successful')
        digest(clean['before_sha256']);digest(clean['after_sha256']);require(clean['before_sha256']==clean['after_sha256'],'ACL hashes differ')
    require(final['cleanup']['trap']['before_sha256']==final['cleanup']['always']['before_sha256'],'ACL origins differ')
    require(type(final['methods'])is list and len(final['methods'])==8,'FINAL method count')
    groups={s['install_group']:{'identity':'C1','status':'PASS'}for s in plan['steps']};require(final['install_groups']==groups,'FINAL install groups differ')
    for method,receipt,step,block in zip(methods,final['methods'],plan['steps'],blocks):
        keys(receipt,FINAL_METHOD_KEYS);require(all(type(receipt[k])is type(v)and receipt[k]==v for k,v in method.items()if k!='error')and receipt['seed']==step['seed'],'METHOD/FINAL differ')
        digest(receipt['log_sha256']);require(receipt['log_sha256']==block['context']['raw_log_sha256'],'Raw log binding differs')
        require(block['context']['environment_sha256']==final['device_before_sha256'],'Group environment differs')
    contexts=[b['context']for b in blocks];require(len({c['apk_receipt_sha256']for c in contexts})==1,'Single source APK receipt changed')
    for group in groups:
        entries=[c for c in contexts if c['install_group']==group]
        require(all(all(c[k]==entries[0][k]for k in ['selected_apks','installed_apks','install_log_sha256','environment_sha256','source_manifest_sha256'])for c in entries),'Within-group installation/source changed')
    signers={a['signer_sha256'][0]for c in contexts for a in c['selected_apks']};require(len(signers)==1,'APK signers differ')
    steps=job['steps'];phase_times=[];prior_end=clock['budget_anchor_epoch']
    for name,phase in frozen['phases'].items():
        found=[s for s in steps if s['name']==name];require(len(found)==1,'Missing/duplicate official phase step')
        s=found[0];require(s['status']=='completed'and s['conclusion']=='success','Official phase failed')
        start,end=epoch(s['started_at']),epoch(s['completed_at']);require(prior_end<=start<=end<=min(clock['deadline_epochs'][phase],clock['deadline_epochs']['work']),'Official phase exceeds clock budget or sequence');prior_end=end
        require(epoch(job['started_at'])<=start<=end<=epoch(job['completed_at'])+1,'Official phase outside actual job span')
        if phase == 'data':
            for block in blocks:
                expected_phase='data'
                if expected_phase==phase:require(start<=block['first_receipt_epoch']<=block['method_receipt_epoch']<=end+1,'Method block outside its official phase step')
        phase_times.append({'name':name,'phase':phase,'start_epoch':start,'end_epoch':end,'seconds':end-start})
    state['phase_times']=phase_times;state['final']=final

def analyze(text,frozen,expected,binding,run,jobs):
    state={'clock':None,'methods':[],'observed_method_receipts':[],'blocks':[],'failures':[],'final_observed':None,'final':None}
    result={'schema':1,'analysis_status':'INCOMPLETE','binding_status':BINDING_STATUS,'scope':'DIAGNOSTIC_ONLY','expected':expected,'issues':[],'state':state,'numeric_acceptance':'NOT_EVALUATED','limitations':['Official run/job metadata and decoded-log provenance are supplied externally; no online authenticity check.','Compact receipts do not independently audit APK binaries, raw JUnit files, native libraries, installed bytes or ACL files.','records_sha256 is reconstructed exactly; raw_log_sha256 is only linked between CONTEXT and FINAL.','Group post-environment equality is a bound-controller PASS claim; only its before environment hash is directly forwarded.','All diagnostics are instrumented observations. No formal gate, product speedup, pure lock-wait or additive causal attribution is claimed.']}
    try:
        job=official_metadata(binding,run,jobs,expected,frozen)
        official_success=run['status']==job['status']=='completed'and run['conclusion']==job['conclusion']=='success'
        result['official_statuses']={'run_status':run['status'],'run_conclusion':run['conclusion'],'job_status':job['status'],'job_conclusion':job['conclusion']}
        if not official_success:result['issues'].append('Official run/job not successful; preserve partial records only')
        parse(text,frozen,expected,state,job)
        if not official_success:return result
        result.update(analysis_status='COMPLETE_SCOPED_GC_SAVE_LOG_EVIDENCE'if BINDING_STATUS=='BOUND'else'SYNTHETIC_STRUCTURE_COMPLETE_UNBOUND',official_job_span_seconds=epoch(job['completed_at'])-epoch(job['started_at']))
    except (ValueError,AssertionError,KeyError,TypeError,IndexError,OverflowError,RecursionError,UnicodeError,binascii.Error)as error:result['issues'].append(type(error).__name__+': '+str(error))
    return result

def write_outputs(out,result):
    out.mkdir(parents=True,exist_ok=False);ledger=[]
    for block in result.get('state',{}).get('blocks',[]):
        ordinal=block['context']['ordinal'];name=f'method-{ordinal:02}-C1-records.json';data=compact(block['records'])+b'\n';(out/name).write_bytes(data)
        original_name=f'method-{ordinal:02}-C1-original-payloads.json'
        originals=[{'marker':record['marker'],'original_json':payload,'payload_bytes':len(payload.encode()),'payload_sha256':sha(payload.encode())}for record,payload in zip(block['records'],block['payloads'])]
        original_data=compact(originals)+b'\n';(out/original_name).write_bytes(original_data)
        marker_name=f'method-{ordinal:02}-C1-markers.log';marker_data=''.join(record['marker']+' '+payload+'\n'for record,payload in zip(block['records'],block['payloads'])).encode();(out/marker_name).write_bytes(marker_data)
        ledger.append({'ordinal':ordinal,'path':name,'sha256':sha(data),'original_payloads_path':original_name,'original_payloads_sha256':sha(original_data),'markers_path':marker_name,'markers_sha256':sha(marker_data),'record_count':len(block['records']),'block_closed':block['ended'],'marker_validation_complete':block['ended'],'method_pass':block['method_pass'],'whole_run_complete':result['analysis_status']=='COMPLETE_SCOPED_GC_SAVE_LOG_EVIDENCE'})
        block['record_count']=len(block['records']);block.pop('records');block.pop('payloads')
    result['extracted_records']=ledger
    failures=[]
    for failure in result.get('state',{}).get('failures',[]):
        ordinal=failure['binding']['ordinal'];name=f'failure-method-{ordinal:02}-C1-original-envelopes.json'
        envelopes=failure['original_envelopes']
        if failure['status']=='OPEN':
            # An interrupted stream is not a validated UTF-8/secret-checked original.
            # Retain its original-envelope hashes; the supplied job log retains bytes.
            envelopes=[{k:v for k,v in envelope.items()if k!='original_json'}for envelope in envelopes]
        data=compact(envelopes)+b'\n';(out/name).write_bytes(data)
        item={'ordinal':ordinal,'envelope_path':name,'envelope_sha256':sha(data),'forwarding_status':failure['status'],
              'refusal_reason':failure['begin'].get('reason'),'matched_method_receipt':failure['matched_method_receipt'],'whole_run_complete':False}
        if failure['status']=='FORWARDED':
            raw_name=f'failure-method-{ordinal:02}-C1-instrumentation.log';raw=failure['raw'];(out/raw_name).write_bytes(raw)
            item.update(raw_path=raw_name,raw_bytes=len(raw),raw_sha256=sha(raw))
        failure.pop('raw',None);failure.pop('chunks');failure.pop('original_envelopes');failures.append(item)
    result['failure_evidence']=failures
    (out/'analysis.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')

def main():
    p=argparse.ArgumentParser(description=__doc__)
    for key in ['job-log','frozen','control-root','binding','run-metadata','jobs-metadata','out']:p.add_argument('--'+key,type=Path,required=True)
    for key in ['run-id','job-id','workflow-sha','source-commit','readiness-git','prep-sha']:p.add_argument('--'+key,required=True)
    a=p.parse_args();expected={k:getattr(a,k)for k in ['run_id','job_id','workflow_sha','source_commit','readiness_git','prep_sha']}
    result={'analysis_status':'INCOMPLETE','binding_status':BINDING_STATUS,'issues':[],'expected':expected}
    try:
        require(BINDING_STATUS=='BOUND','UNBOUND: reviewed readiness/prep/active/validator bindings are not fixed; no real-run closure')
        require(re.fullmatch('[0-9]+',a.run_id)and re.fullmatch('[0-9]+',a.job_id),'Invalid numeric run/job identity')
        for key in ['workflow_sha','source_commit','readiness_git','prep_sha']:require(re.fullmatch('[0-9a-f]{40}',expected[key]),'Invalid Git identity')
        require(all(expected[key]==value for key,value in GIT_BINDING.items()),'Reviewed source/readiness/prep/active chain differs')
        require(all(expected[key]==value for key,value in RUN_BINDING.items()),'Reviewed official run/job differs')
        binding=strict_json(a.binding.read_bytes());run=strict_json(a.run_metadata.read_bytes());jobs=strict_json(a.jobs_metadata.read_bytes())
        frozen=frozen_inputs(a.frozen,a.source_commit,a.readiness_git,a.control_root,binding)
        local_git=verify_local_git(a.control_root,a.frozen,expected,frozen,binding)
        raw=a.job_log.read_bytes();require(len(raw)<=64*1024*1024,'Decoded log input exceeds bounded 64MiB local analyzer cap')
        result=analyze(raw.decode('utf-8-sig'),frozen,expected,binding,run,jobs)
        result['local_git_binding']=local_git
        result['input_hashes']=frozen['hashes'];result['files']={str(f):{'bytes':f.stat().st_size,'sha256':sha(f.read_bytes())}for f in [a.job_log,a.binding,a.run_metadata,a.jobs_metadata]}
    except (ValueError,AssertionError,OSError,KeyError,TypeError,OverflowError,RecursionError,subprocess.CalledProcessError,subprocess.TimeoutExpired)as error:
        result['analysis_status']='INCOMPLETE'
        result['issues'].append(type(error).__name__+': '+str(error))
    result['analyzer_sha256']=sha(Path(__file__).read_bytes());result['frozen_markers_sha256']=sha(Path(frozen_markers.__file__).read_bytes())
    write_outputs(a.out,result);print(json.dumps({'analysis_status':result['analysis_status'],'issues':result['issues']}));return int(result['analysis_status']!='COMPLETE_SCOPED_GC_SAVE_LOG_EVIDENCE')
if __name__=='__main__':raise SystemExit(main())
