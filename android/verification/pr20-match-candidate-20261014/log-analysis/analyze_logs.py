#!/usr/bin/env python3
"""Offline 62-method official-log closure; statistics delegate to frozen analyze.py.
No network, subprocess, Android, or writes to input evidence. Templates fail closed.
"""
import argparse,datetime,hashlib,json,math,re,types
from pathlib import Path
import yaml

PREFIX='INKWEFT_MATCH_'
SCOPE='FORMAL_THREE_SOURCE_COMPARISON'
COMPLETE='COMPLETE_SCOPED_MATCH_LOG_EVIDENCE'
INPUT_FILES={'approved_plan_sha256':'PLAN.md','source_spec_sha256':'source-spec.json','method_plan_sha256':'method-plan.json','original_protocol_sha256':'original-protocol.json','marker_contract_sha256':'marker-contract.json'}
CONTEXT_KEYS='scope run_id run_attempt workflow_sha readiness_git source_commit source_spec_sha256 method_plan_sha256 marker_contract_sha256 test_source_sha256 source_manifest_sha256 selected_apks apk_receipt_sha256 installed_apks install_log_sha256 environment_sha256 ordinal identity seed args install_group selection raw_log_sha256 records_sha256 junit_status adb_exit process_exit_outcome process_exit_observations auxiliary_cleanup process_exit_seconds'
METHOD_KEYS='ordinal phase identity selection status junit_status adb_exit process_exit_outcome error'
FINAL_METHOD_KEYS='ordinal identity phase selection seed status junit_status adb_exit process_exit_outcome log_sha256'
FINAL_KEYS='scope run_id workflow_sha input_hashes status cleanup_verified cleanup methods install_groups device_before_sha256 device_after_sha256 missing_files packaging_complete manifest_sha256 summary_sha256'
PACKAGES=['org.inkweft.app.a0','org.inkweft.app.a0.test','org.inkweft.data.test']
HELPER=PACKAGES[1]
UI='org.inkweft.app.NotebookShelfUiTest#pageSourceEditResetsSecondShelfPageWithoutDirectoryChanges'
CUTOFFS={'setup':38,'correctness':41,'common':53,'diagnostics':55}
PROTOCOL_SHA='1065177d7809426d949c4b4b67bd639a47c3fc1943f706a77c2dfabb985e9aba'

def require(ok,message):
    if not ok:raise ValueError(message)
def sha(data):return hashlib.sha256(data).hexdigest()
def compact(value):return json.dumps(value,ensure_ascii=False,separators=(',',':'),allow_nan=False).encode()
def strict_json(text):
    def pairs(values):
        result={}
        for key,value in values:require(key not in result,'Duplicate JSON key');result[key]=value
        return result
    def bad(value):raise ValueError('Non-finite JSON')
    return json.loads(text,object_pairs_hook=pairs,parse_constant=bad)
def keys(value,expected):require(type(value)is dict and set(value)==set(expected.split()),'Envelope keys differ')
def digest(value):require(type(value)is str and re.fullmatch('[0-9a-f]{64}',value),'Invalid SHA256')
def git(value):require(type(value)is str and re.fullmatch('[0-9a-f]{40}',value),'Invalid Git SHA')
def number(value):return type(value)in[int,float]and math.isfinite(value)
def epoch(value):
    require(type(value)is str and re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?Z',value),'Expected UTC timestamp')
    return datetime.datetime.fromisoformat(value.replace('Z','+00:00')).timestamp()
def module(path,name):
    value=types.ModuleType(name);value.__file__=str(path)
    exec(compile(path.read_bytes(),str(path),'exec'),value.__dict__);return value

def frozen_inputs(directory,expected,control_root,binding):
    require(__debug__,'Python optimized mode disables frozen validator assertions')
    raw={k:(directory/n).read_bytes()for k,n in INPUT_FILES.items()};hashes={k:sha(v)for k,v in raw.items()}
    plan,spec,contract,protocol=(strict_json(raw[k])for k in ['method_plan_sha256','source_spec_sha256','marker_contract_sha256','original_protocol_sha256'])
    require(plan['instrumentation_calls']==plan['expected_junit_methods']==len(plan['steps'])==62,'Fixed 62 methods required')
    require([s['ordinal']for s in plan['steps']]==list(range(1,63)),'Method ordinal order')
    require([sum(s['phase']==p for s in plan['steps'])for p in ['correctness','common-comparison','candidate-diagnostics']]==[31,24,7],'Phase scope differs')
    require(len({s['install_group']for s in plan['steps']})==14 and {s['identity']for s in plan['steps']}=={'B','C0','C1'},'14 groups/three identities required')
    require(spec['status']=='FROZEN_SOURCE_NOT_BUILT_NOT_RUN'and contract['status']=='FROZEN'and set(spec['identities'])=={'B','C0','C1'},'Unfrozen source/contract')
    require(spec['identities']['B']['git']=='a128bb07e34cb82f00eeafc8e62a47bf7b95b128'and spec['identities']['C0']['git']=='d66b60d6cf9587f646acb76dd8a00910b73a6d70'and spec['identities']['C1']['git']==expected['source_commit'],'Source identity differs')
    require(hashes['original_protocol_sha256']==PROTOCOL_SHA,'Public protocol changed')
    base=control_root/'ci/pr20-kvm-triple'
    hashes.update({k:sha((base/n).read_bytes())for k,n in [('clock_contract_sha256','clock-contract.json'),('server_receipt_sha256','clock-server-receipt.json')]})
    require(binding['input_hashes']==hashes,'Frozen input hashes differ')
    require(binding['prepare_sources_sha256']==sha((directory/'prepare_sources.py').read_bytes()),'Preparation script hash differs')
    base=control_root/'ci/pr20-kvm-triple';auth=strict_json((base/'authorization.json').read_bytes())
    require(auth['approved']is True and auth['plan_git']==expected['readiness_git']and all(auth[k]==v for k,v in hashes.items()),'Authorization/input binding differs')
    require(auth['phase_cutoff_minutes']==CUTOFFS,'Budget differs')
    controls=binding['control_hashes'];required={'ci/pr20-kvm-triple/'+f for f in ['run.py','markers.py','package.py','cleanup.py','authorization.json','workflow.proposed.yml','clock-contract.json','clock-server-receipt.json','budget-revision.json']}
    require(type(controls)is dict and required<=set(controls),'Missing control hashes')
    workflows=[p for p in controls if p.startswith('.github/workflows/')and p.endswith('.yml')];require(len(workflows)==1,'Exactly one active workflow required')
    for relative,h in controls.items():
        digest(h);p=Path(relative);require(not p.is_absolute()and'..'not in p.parts,'Unsafe control relative path')
        target=(control_root/p).resolve();require(target.is_relative_to(control_root.resolve())and sha(target.read_bytes())==h,'Control file hash differs: '+relative)
    digest(binding['statistics_sha256']);require(sha((directory/'analyze.py').read_bytes())==binding['statistics_sha256'],'Frozen statistics hash differs')
    manifests={}
    for identity in ['B','C0','C1']:
        manifest=strict_json((directory/(identity+'-source-manifest.json')).read_bytes())
        require(manifest['identity']==identity and manifest['git']==spec['identities'][identity]['git']and len(manifest['files'])==spec['identities'][identity]['file_count'],'Source manifest identity/count differs')
        canonical=sha(json.dumps(manifest['files'],sort_keys=True,separators=(',',':')).encode())
        require(canonical==manifest['canonical_manifest_sha256']==spec['identities'][identity]['canonical_manifest_sha256'],'Canonical source manifest differs')
        manifests[identity]=manifest
    for entry in plan['steps']:
        rule=contract['methods'][str(entry['ordinal'])]
        require((entry['selection'],entry['identity'])==(rule['selection'],rule['identity']),'Method marker mapping differs')
        require(manifests[entry['identity']]['files'][rule['test_source_path']]['sha256']==rule['test_source_sha256'],'Test/source binding differs')
    clock=strict_json((base/'clock-contract.json').read_bytes());receipt=strict_json((base/'clock-server-receipt.json').read_bytes());budget=strict_json((base/'budget-revision.json').read_bytes())
    require(clock['status']=='FROZEN'and clock['anchor_kind']=='github_pull_request_updated_at'and clock['actual_job_started_epoch']is None,'Clock not frozen')
    start=clock['budget_anchor_epoch'];require(type(start)is int and epoch(clock['updated_at'])==start,'Clock timestamp differs')
    require(clock['phase_cutoff_minutes']==CUTOFFS and clock['work_deadline_minutes']==55 and clock['job_timeout_minutes']==60,'Clock budget differs')
    require(sha((base/'clock-contract.json').read_bytes())==auth['clock_contract_sha256']and sha((base/'clock-server-receipt.json').read_bytes())==auth['server_receipt_sha256']==clock['server_receipt_sha256']and auth['budget_anchor_epoch']==start,'Clock hash chain differs')
    require(sha((base/'budget-revision.json').read_bytes())==auth['budget_revision_sha256'],'Budget amendment hash differs')
    require(budget['new_cutoffs_minutes']==[38,41,53,55,60]and budget['unchanged']['task_build_cache']is False,'Budget amendment scope differs')
    require(receipt['http_status']==200 and receipt['number']==20 and receipt['updated_at']==clock['updated_at'],'PR clock receipt differs')
    workflow=yaml.safe_load((control_root/workflows[0]).read_text());require(workflow['permissions']=={'contents':'read'}and len(workflow['jobs'])==1,'Workflow permission/job scope differs')
    job=next(iter(workflow['jobs'].values()));require(job['timeout-minutes']==60 and job['runs-on']=='ubuntu-24.04','Runner budget differs')
    require('github.run_attempt == 1'in job['if']and'github.event.created == false'in job['if'],'Activation guard differs')
    git(binding['prep_sha']);guard=re.findall('[0-9a-f]{40}',job['if']);require(guard==[binding['prep_sha']]and guard[0]!='0'*40,'Exact prep guard not bound')
    phases={}
    for step in job['steps']:
        match=re.search(r'run\.py (setup|build (?:B|C0|C1)|emulator|methods correctness|methods common|methods diagnostics)(?:\s|$)',step.get('run',''))
        if match:phases[step['name']]=match[1].split()[-1]if match[1].startswith('methods ')else'setup'
    require(list(phases.values())==['setup']*5+['correctness','common','diagnostics'],'Workflow phase wrappers differ')
    branches=workflow['on']['push']['branches'];require(type(branches)is list and len(branches)==1,'One fixed push branch required')
    markers=module(base/'markers.py','match_frozen_markers');statistics=module(directory/'analyze.py','match_frozen_statistics')
    return dict(plan=plan,spec=spec,contract=contract,protocol=protocol,hashes=hashes,phases=phases,auth=auth,branch=branches[0],workflow_path=workflows[0],clock=clock,markers=markers,statistics=statistics)

def official_metadata(binding,run,jobs,expected,frozen):
    for key in ['run_id','job_id','workflow_sha','source_commit','readiness_git']:require(binding[key]==expected[key],'Expected official binding differs: '+key)
    require(binding['scope']==SCOPE and str(binding['run_attempt'])=='1','Binding scope/attempt differs')
    require(str(run['id'])==expected['run_id']and run['head_sha']==expected['workflow_sha']and run['run_attempt']==1,'Actual run identity differs')
    require(run['event']=='push'and run['head_branch']==frozen['branch']and run['path']==frozen['workflow_path'],'Official event/branch/workflow differs')
    require(jobs['total_count']==len(jobs['jobs'])==1,'Expected exactly one official job');job=jobs['jobs'][0]
    require(str(job['id'])==expected['job_id']and str(job['run_id'])==expected['run_id']and job['head_sha']==expected['workflow_sha']and job['run_attempt']==1,'Actual job identity differs')
    for key,actual in [('run_status',run['status']),('run_conclusion',run['conclusion']),('job_status',job['status']),('job_conclusion',job['conclusion'])]:require(binding[key]==actual,'Binding metadata differs: '+key)
    begin,end=epoch(job['started_at']),epoch(job['completed_at']);require(frozen['clock']['budget_anchor_epoch']<=begin<=end and end-begin<=3600,'Anchor/job hard 60min boundary differs')
    return job

def process_exit(value,step):
    observations=value['process_exit_observations'];outcome=value['process_exit_outcome'];aux=value['auxiliary_cleanup'];elapsed=value['process_exit_seconds']
    require(type(observations)is list and 1<=len(observations)<=100 and number(elapsed)and elapsed>=0,'Process receipt absent/oversize')
    prior=-1;natural=[];after=[]
    for obs in observations:
        keys(obs,'stage elapsed_seconds packages');at=obs['elapsed_seconds'];require(number(at)and 0<=at and prior<=at<=elapsed,'Process chronology differs');prior=at
        require(type(obs['packages'])is dict and set(obs['packages'])==set(PACKAGES),'Missing process query')
        for item in obs['packages'].values():
            keys(item,'exit stdout stderr');require(type(item['exit'])is int and type(item['stdout'])is str and item['stderr']=='','Bad process transport')
            require((item['exit']==1 and item['stdout']=='')or(item['exit']==0 and re.fullmatch(r'[1-9][0-9]*(?: [1-9][0-9]*)*',item['stdout'])),'Invalid PID/absence')
        if obs['stage']=='natural-exit':require(not after and at<3,'Late/out-of-order natural observation');natural.append(obs)
        else:require(obs['stage']=='after-background-cleanup'and natural,'Unexpected process stage');after.append(obs)
    gone=lambda obs:all(v['exit']==1 for v in obs['packages'].values())
    if outcome=='NATURAL_EXIT':require(aux is None and not after and gone(natural[-1])and elapsed<=3,'Natural exit not closed')
    else:
        require(outcome=='AUXILIARY_AM_KILL_CONFIRMED'and step['ordinal']==17 and step['phase']=='correctness'and step['identity']=='C1'and step['selection']==UI and step['args'][-1]==HELPER+'/androidx.test.runner.AndroidJUnitRunner','Auxiliary exception not authorized')
        require(after and gone(after[-1])and elapsed<=5,'Auxiliary absence/window not closed');keys(aux,'command status started_seconds exit stdout stderr')
        require(aux['command']==['adb','-s','emulator-5554','shell','am','kill','--user','0',HELPER]and aux['status']=='ABSENCE_CONFIRMED'and type(aux['exit'])is int and aux['exit']==0 and aux['stdout']==aux['stderr']=='','Wrong helper cleanup')
        at=aux['started_seconds'];require(number(at)and 2.75<=at and natural[-1]['elapsed_seconds']<=at<=after[0]['elapsed_seconds']<=elapsed and elapsed-at<=2,'Auxiliary chronology/window differs')
        last=natural[-1]['packages'];require(last[HELPER]['exit']==0 and all(v['exit']==1 for k,v in last.items()if k!=HELPER),'Application/data active before auxiliary cleanup')


def parse(text,frozen,expected,state,job):
    plan,contract,spec=(frozen[k]for k in ['plan','contract','spec']);methods=state['methods'];blocks=state['blocks'];active=None;clock=None;final=None;last_time=None
    for line_number,raw in enumerate(text.splitlines(),1):
        stamp=re.match(r'^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?Z)\s+(.*)$',raw);line=stamp[2]if stamp else raw
        if not line.startswith(PREFIX):continue
        state['last_receipt_line']=line_number;require(stamp is not None,'Official receipt lacks UTC timestamp')
        at=epoch(stamp[1]);require(last_time is None or at>=last_time,'Receipt timestamp reversed');last_time=at
        require(epoch(job['started_at'])<=at<=epoch(job['completed_at'])+1,'Receipt outside official job span')
        tag,sep,encoded=line.partition(' ');require(sep and len(encoded.encode())<=2*1024*1024,'Malformed/oversize envelope')
        kind=tag[len(PREFIX):];require(kind in ['CLOCK','CONTEXT','RECORD','END','METHOD','FINAL'],'Unknown match prefix');value=strict_json(encoded);require(final is None,'Receipt after FINAL')
        if kind=='CLOCK':
            require(clock is None and not methods and active is None,'Duplicate/late CLOCK')
            keys(value,'status anchor_kind updated_at clock_contract_sha256 server_receipt_sha256 actual_job_started_epoch first_step_observed_epoch budget_anchor_epoch deadlines_minutes deadline_epochs')
            frozen_clock=frozen['clock'];start=value['budget_anchor_epoch'];require(type(start)is int and start==frozen_clock['budget_anchor_epoch']and value['status']=='PASS','Clock anchor differs')
            for key in ['anchor_kind','updated_at','server_receipt_sha256','actual_job_started_epoch']:require(value[key]==frozen_clock[key],'Clock source differs')
            require(value['clock_contract_sha256']==frozen['auth']['clock_contract_sha256'],'Clock contract hash differs')
            require(number(value['first_step_observed_epoch'])and epoch(job['started_at'])<=value['first_step_observed_epoch']<=at and value['first_step_observed_epoch']<start+38*60,'First step outside job/setup budget')
            deadlines={'KVM_BUDGET_ANCHOR':start,'KVM_SETUP_CUTOFF':start+38*60,'KVM_CORRECTNESS_CUTOFF':start+41*60,'KVM_COMMON_CUTOFF':start+53*60,'KVM_WORK_DEADLINE':start+55*60}
            require(value['deadlines_minutes']==[38,41,53,55,60]and value['deadline_epochs']==deadlines,'Clock cutoffs differ');clock=value;state['clock']=clock;continue
        require(clock is not None,'Missing CLOCK')
        if kind in ['CONTEXT','RECORD','END','METHOD']:
            require(len(methods)<62,'Extra method block');step=plan['steps'][len(methods)];phase={'correctness':'correctness','common-comparison':'common','candidate-diagnostics':'diagnostics'}[step['phase']]
            require(at<=clock['budget_anchor_epoch']+CUTOFFS[phase]*60,'Method receipt after phase/work deadline')
        if kind=='CONTEXT':
            require(active is None,'Nested/duplicate CONTEXT');keys(value,CONTEXT_KEYS)
            require(all(value[k]==step[k]for k in ['ordinal','identity','seed','args','install_group','selection']),'CONTEXT plan mapping differs')
            require(type(value['ordinal'])is int and value['scope']==SCOPE and value['run_id']==expected['run_id']and str(value['run_attempt'])=='1'and value['workflow_sha']==expected['workflow_sha']and value['readiness_git']==expected['readiness_git'],'CONTEXT run identity differs')
            source=spec['identities'][step['identity']];require(value['source_commit']==source['git']and value['source_manifest_sha256']==source['canonical_manifest_sha256'],'CONTEXT source differs')
            for key in ['source_spec_sha256','method_plan_sha256','marker_contract_sha256']:require(value[key]==frozen['hashes'][key],'CONTEXT frozen hash differs')
            require(value['test_source_sha256']==contract['methods'][str(step['ordinal'])]['test_source_sha256'],'CONTEXT test hash differs')
            for k,v in value.items():
                if k.endswith('_sha256'):digest(v)
            require(value['junit_status']=='PASS'and type(value['adb_exit'])is int and value['adb_exit']==0,'CONTEXT JUnit differs');process_exit(value,step)
            selected=value['selected_apks'];installed=value['installed_apks'];require(type(selected)is list and type(installed)is list and [x['package']for x in selected]==[x['package']for x in installed]==PACKAGES,'Three APK package group differs')
            for one,two in zip(selected,installed):
                keys(one,'package sha256 signer_sha256');keys(two,'package sha256 source_apk_sha256');digest(one['sha256']);require(one['sha256']==two['sha256']==two['source_apk_sha256'],'Selected/installed APK mismatch')
                require(type(one['signer_sha256'])is list and len(one['signer_sha256'])==1,'Signer missing');digest(one['signer_sha256'][0])
            active={'context':value,'records':[],'payloads':[],'ordered':[],'ended':False,'method_pass':False,'line':line_number,'first_receipt_epoch':at};blocks.append(active)
        elif kind=='RECORD':
            require(active is not None and not active['ended'],'Record outside open block');keys(value,'ordinal identity sequence marker payload_bytes payload_sha256 original_json')
            require(type(value['ordinal'])is int and value['ordinal']==step['ordinal']and value['identity']==step['identity']and type(value['sequence'])is int and value['sequence']==len(active['records']),'Record order/identity differs')
            rule=contract['methods'][str(step['ordinal'])];marker=value['marker'];require(marker in rule['markers']and type(value['original_json'])is str,'Unexpected marker')
            original=value['original_json'];raw_payload=original.encode();require(type(value['payload_bytes'])is int and value['payload_bytes']==len(raw_payload)<=rule['markers'][marker]['payload_max_bytes']and sha(raw_payload)==value['payload_sha256'],'Original payload size/hash differs')
            data=strict_json(original);schema=contract.get('stage_schemas',{}).get(marker,contract['schemas'][marker])if rule['kind']=='stage'else contract['schemas'][marker];frozen['markers'].validate_shape(data,schema);active['records'].append({'marker':marker,'data':data});active['payloads'].append(original);active['ordered'].append({k:value[k]for k in ['sequence','marker','payload_bytes','payload_sha256']})
            require(len(active['records'])<=sum(r['max_count']for r in rule['markers'].values()),'Too many records')
        elif kind=='END':
            require(active is not None and not active['ended'],'Missing/duplicate END');keys(value,'ordinal identity count ordered_payloads ordered_payloads_sha256')
            require(type(value['ordinal'])is int and value['ordinal']==step['ordinal']and value['identity']==step['identity']and type(value['count'])is int and value['count']==len(active['records']),'END count differs')
            require(type(value['ordered_payloads'])is list,'Invalid footer list')
            for item in value['ordered_payloads']:
                keys(item,'sequence marker payload_bytes payload_sha256');require(type(item['sequence'])is int and type(item['payload_bytes'])is int,'Footer integer type differs')
            require(value['ordered_payloads']==active['ordered']and sha(compact(value['ordered_payloads']))==value['ordered_payloads_sha256'],'Ordered payload footer differs')
            raw='\n'.join(r['marker']+' '+p for r,p in zip(active['records'],active['payloads']));frozen['markers'].safe_marker_payloads(step,raw,contract)
            require(sha(compact(active['records'])+b'\n')==active['context']['records_sha256'],'Complete parsed records hash differs');active['ended']=True;active['end_line']=line_number
        elif kind=='METHOD':
            keys(value,METHOD_KEYS);state['observed_method_receipts'].append(value)
            require(type(value['ordinal'])is int and all(value[k]==step[k]for k in ['ordinal','phase','identity','selection']),'METHOD order differs')
            require(value['status']==value['junit_status']=='PASS'and type(value['adb_exit'])is int and value['adb_exit']==0 and value['process_exit_outcome']in['NATURAL_EXIT','AUXILIARY_AM_KILL_CONFIRMED']and value['error']is None,'METHOD failed/incomplete/skipped')
            require(active is not None and active['ended']and active['context']['ordinal']==step['ordinal']and active['context']['process_exit_outcome']==value['process_exit_outcome'],'Missing complete method block')
            active['method_pass']=True;active['method_receipt_epoch']=at;methods.append(value);active=None
        else:
            state['final_observed']=value;require(active is None and len(methods)==len(blocks)==62,'FINAL before all62 closures');final=value
    require(clock is not None and final is not None and active is None and len(methods)==len(blocks)==62,'Missing/unfinished receipt set')
    keys(final,FINAL_KEYS);require(final['scope']==SCOPE and final['run_id']==expected['run_id']and final['workflow_sha']==expected['workflow_sha']and final['input_hashes']==frozen['hashes'],'FINAL identity/input differs')
    require(final['status']=='EXECUTION_COMPLETE_ANALYSIS_PENDING'and final['cleanup_verified']is True and final['packaging_complete']is True and final['missing_files']==[],'FINAL incomplete execution/evidence/cleanup')
    for key in ['device_before_sha256','device_after_sha256','manifest_sha256','summary_sha256']:digest(final[key])
    require(final['device_before_sha256']==final['device_after_sha256'],'Device environment changed');keys(final['cleanup'],'trap always')
    for clean in final['cleanup'].values():
        keys(clean,'emulator command_groups acl error_count before_sha256 after_sha256 exact_match');require(clean['emulator']==clean['command_groups']=='STOPPED_CONFIRMED'and clean['acl']in['UNCHANGED_EXACT','RESTORED_EXACT']and type(clean['error_count'])is int and clean['error_count']==0 and clean['exact_match']is True,'Cleanup claim not successful')
        digest(clean['before_sha256']);digest(clean['after_sha256']);require(clean['before_sha256']==clean['after_sha256'],'ACL hashes differ')
    require(final['cleanup']['trap']['before_sha256']==final['cleanup']['always']['before_sha256'],'ACL origins differ')
    groups={s['install_group']:{'identity':s['identity'],'status':'PASS'}for s in plan['steps']};require(len(groups)==14 and final['install_groups']==groups,'FINAL install groups differ')
    require(type(final['methods'])is list and len(final['methods'])==62,'FINAL method count')
    for method,receipt,step,block in zip(methods,final['methods'],plan['steps'],blocks):
        keys(receipt,FINAL_METHOD_KEYS);require(all(type(receipt[k])is type(v)and receipt[k]==v for k,v in method.items()if k!='error')and receipt['seed']==step['seed'],'METHOD/FINAL differ')
        digest(receipt['log_sha256']);require(receipt['log_sha256']==block['context']['raw_log_sha256'],'Raw log binding differs');require(block['context']['environment_sha256']==final['device_before_sha256'],'Group environment differs')
    contexts=[b['context']for b in blocks]
    for identity in ['B','C0','C1']:
        entries=[c for c in contexts if c['identity']==identity];require(entries and all(all(c[k]==entries[0][k]for k in ['selected_apks','apk_receipt_sha256','source_manifest_sha256'])for c in entries),'Per-identity APK/source changed')
    for group in groups:
        entries=[c for c in contexts if c['install_group']==group];require(all(all(c[k]==entries[0][k]for k in ['selected_apks','installed_apks','install_log_sha256','environment_sha256'])for c in entries),'Within-group installation changed')
    require(len({a['signer_sha256'][0]for c in contexts for a in c['selected_apks']})==1,'APK signer changed')
    phase_times=[];prior_end=clock['budget_anchor_epoch']
    for name,phase in frozen['phases'].items():
        found=[s for s in job['steps']if s['name']==name];require(len(found)==1,'Missing/duplicate official phase');s=found[0]
        require(s['status']=='completed'and s['conclusion']=='success','Official phase failed');start,end=epoch(s['started_at']),epoch(s['completed_at'])
        require(epoch(job['started_at'])<=start and end<=epoch(job['completed_at'])and prior_end<=start<=end<=clock['budget_anchor_epoch']+CUTOFFS[phase]*60,'Official phase exceeds budget/sequence');prior_end=end
        if phase!='setup':
            for block in blocks:
                expected_phase={'correctness':'correctness','common-comparison':'common','candidate-diagnostics':'diagnostics'}[plan['steps'][block['context']['ordinal']-1]['phase']]
                if expected_phase==phase:require(start<=block['first_receipt_epoch']<=block['method_receipt_epoch']<=end+1,'Method block outside official phase')
        phase_times.append(dict(name=name,phase=phase,start_epoch=start,end_epoch=end,seconds=end-start))
    state['phase_times']=phase_times;state['final']=final


def analyze(text,frozen,expected,binding,run,jobs):
    state={'clock':None,'methods':[],'observed_method_receipts':[],'blocks':[],'final_observed':None,'final':None}
    result={'schema':1,'analysis_status':'INCOMPLETE','scope':SCOPE,'expected':expected,'issues':[],'state':state,'final_acceptance':'NOT_EVALUATED','limitations':['Official metadata/log provenance is supplied externally.','Compact receipts do not independently audit raw JUnit, APK/ELF/dependency files, installed bytes, ACL or artifact manifest.','Raw log hashes are linked, not independently reconstructed; original forwarded JSON and parsed records hashes are checked.','Group post-environment equality is a bound-controller claim; before/global hashes are forwarded.','62-call execution closure and 18-metric screening are distinct; neither is a final product/performance acceptance.']}
    try:
        job=official_metadata(binding,run,jobs,expected,frozen);ok=run['status']==job['status']=='completed'and run['conclusion']==job['conclusion']=='success'
        result['official_statuses']={k:v for k,v in [('run_status',run['status']),('run_conclusion',run['conclusion']),('job_status',job['status']),('job_conclusion',job['conclusion'])]}
        if not ok:result['issues'].append('Official run/job not successful; preserve partial records only')
        parse(text,frozen,expected,state,job)
        if ok:result.update(analysis_status=COMPLETE,official_job_span_seconds=epoch(job['completed_at'])-epoch(job['started_at']))
    except (ValueError,AssertionError,KeyError,TypeError,IndexError,OverflowError,RecursionError)as e:result['issues'].append(type(e).__name__+': '+str(e))
    return result


def calculate(result,frozen):
    """No duplicated formulas: pass exact records to the frozen three-source calculation."""
    passed={m['ordinal']:m for m in result['state']['methods']};observed={m['ordinal']:m for m in result['state']['observed_method_receipts']if type(m.get('ordinal'))is int}
    methods=[]
    for step in frozen['plan']['steps']:
        item=dict(step,status='NOT_RUN');m=passed.get(step['ordinal'])or observed.get(step['ordinal'])
        if m and all(m.get(k)==step[k]for k in ['ordinal','identity','phase','selection']):item.update(m)
        methods.append(item)
    complete=result['analysis_status']==COMPLETE and not result['issues']
    summary={'run_id':result['expected']['run_id'],'workflow_sha':result['expected']['workflow_sha'],'source_spec':frozen['spec'],'methods':methods,'status':'EXECUTION_COMPLETE_ANALYSIS_PENDING'if complete else'INCOMPLETE_LOG_CLOSURE','cleanup_verified':complete}
    records={b['context']['ordinal']:b['records']for b in result['state']['blocks']}
    numeric=frozen['statistics'].analyze(summary,frozen['plan'],frozen['protocol'],records,[]if complete else['Official log closure incomplete; no numerical acceptance promotion'])
    require(complete or numeric['analysis_status']=='INCOMPLETE','Statistics promoted incomplete closure')
    return numeric


def write_outputs(out,result,numeric=None,statistics=None):
    out.mkdir(parents=True,exist_ok=False);ledger=[]
    for b in result.get('state',{}).get('blocks',[]):
        ordinal=b['context']['ordinal'];identity=b['context']['identity'];name=f'method-{ordinal:02d}-{identity}-records.json';data=compact(b['records'])+b'\n';(out/name).write_bytes(data)
        ledger.append(dict(ordinal=ordinal,identity=identity,path=name,sha256=sha(data),record_count=len(b['records']),block_closed=b['ended'],method_pass=b['method_pass'],whole_run_complete=result['analysis_status']==COMPLETE))
        b['record_count']=len(b['records']);b.pop('records');b.pop('payloads')
    result['extracted_records']=ledger
    if numeric is not None:
        target=out/'statistics';target.mkdir()
        for name,key in [('raw-saves','raw_saves'),('save-distributions','save_distributions'),('paired-values','paired_values'),('warm-values','warm_comparisons'),('raster-values','raster'),('library-values','library'),('methods','methods')]:statistics.write_csv(target/(name+'.csv'),numeric[key])
        (target/'analysis.json').write_text(json.dumps(numeric,ensure_ascii=False,indent=2,allow_nan=False)+'\n')
        result['statistics_output']={'path':'statistics/analysis.json','sha256':sha((target/'analysis.json').read_bytes()),'analysis_status':numeric['analysis_status']}
    (out/'analysis.json').write_text(json.dumps(result,ensure_ascii=False,indent=2,allow_nan=False)+'\n')


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for key in ['job-log','frozen','control-root','binding','run-metadata','jobs-metadata','out']:p.add_argument('--'+key,type=Path,required=True)
    for key in ['run-id','job-id','workflow-sha','source-commit','readiness-git']:p.add_argument('--'+key,required=True)
    a=p.parse_args();expected={k:getattr(a,k)for k in ['run_id','job_id','workflow_sha','source_commit','readiness_git']};result={'analysis_status':'INCOMPLETE','issues':[],'expected':expected};numeric=None;frozen=None
    try:
        require(re.fullmatch('[0-9]+',a.run_id)and re.fullmatch('[0-9]+',a.job_id),'Invalid run/job IDs')
        for k in ['workflow_sha','source_commit','readiness_git']:git(expected[k])
        binding=strict_json(a.binding.read_bytes());run=strict_json(a.run_metadata.read_bytes());jobs=strict_json(a.jobs_metadata.read_bytes());frozen=frozen_inputs(a.frozen,expected,a.control_root,binding)
        raw=a.job_log.read_bytes();require(len(raw)<=64*1024*1024,'Log exceeds bounded64MiB');result=analyze(raw.decode('utf-8-sig'),frozen,expected,binding,run,jobs)
        result['input_hashes']=frozen['hashes'];result['files']={str(f):dict(bytes=f.stat().st_size,sha256=sha(f.read_bytes()))for f in [a.job_log,a.binding,a.run_metadata,a.jobs_metadata]};result['statistics_sha256']=binding['statistics_sha256']
        numeric=calculate(result,frozen)
        if numeric['analysis_status']!='COMPLETE_SCREENING_NOT_FINAL_ACCEPTANCE':
            result['analysis_status']='INCOMPLETE';result['issues'].append('Frozen statistical evidence is incomplete; no full-run promotion')
    except (ValueError,AssertionError,OSError,KeyError,TypeError,IndexError,OverflowError,RecursionError)as e:
        result['analysis_status']='INCOMPLETE';result['issues'].append(type(e).__name__+': '+str(e));numeric=None
    result['analyzer_sha256']=sha(Path(__file__).read_bytes());write_outputs(a.out,result,numeric,frozen['statistics']if frozen else None)
    print(json.dumps(dict(analysis_status=result['analysis_status'],issues=result['issues'])));return int(result['analysis_status']!=COMPLETE)
if __name__=='__main__':raise SystemExit(main())
