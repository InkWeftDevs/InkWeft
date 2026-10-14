#!/usr/bin/env python3
"""Strict offline causal-log closure. No network, subprocess, native execution or acceptance classification."""
import argparse,datetime,hashlib,json,re
from pathlib import Path
import yaml
import frozen_markers

PREFIX='INKWEFT_CAUSAL_'
INPUT_FILES={'approved_plan_sha256':'PLAN.md','source_spec_sha256':'source-spec.json','method_plan_sha256':'method-plan.json','original_protocol_sha256':'original-protocol.json','marker_contract_sha256':'marker-contract.json','prepare_sources_sha256':'prepare_sources.py'}
CONTEXT_KEYS='scope run_id run_attempt workflow_sha source_commit source_spec_sha256 method_plan_sha256 test_source_sha256 source_manifest_sha256 selected_apks apk_receipt_sha256 installed_apks install_log_sha256 environment_sha256 ordinal identity seed args install_group selection raw_log_sha256 records_sha256 junit_status adb_exit process_exit_outcome'
METHOD_KEYS='ordinal phase identity selection status junit_status adb_exit process_exit_outcome error'
FINAL_METHOD_KEYS='ordinal identity phase selection seed status junit_status adb_exit process_exit_outcome log_sha256'
FINAL_KEYS='scope run_id workflow_sha input_hashes status cleanup_verified cleanup methods install_groups device_before_sha256 device_after_sha256 missing_files packaging_complete manifest_sha256 summary_sha256'
FROZEN_MARKERS_SHA='3514d2bca1f7b91efe7886ea42763b37eca873c8914938e8ad3dfeb03eaa1592'

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
    require(plan['instrumentation_calls']==plan['expected_junit_methods']==len(plan['steps'])==13,'Fixed 13 methods required')
    require([s['ordinal']for s in plan['steps']]==list(range(1,14)),'Method ordinal order')
    require([s['phase']for s in plan['steps']]==['data-diagnostics']*12+['raster-diagnostics'],'Phase plan differs')
    require({s['identity']for s in plan['steps']}=={'C1'} and len({s['install_group']for s in plan['steps']})==2,'One source/two groups required')
    require([s['ordinal']for s in plan['steps']if s['marker_policy']=='none']==list(range(1,8)),'Guard/marker scope differs')
    require(spec['status']==contract['status']=='FROZEN' and list(spec['identities'])==['C1'] and spec['identities']['C1']['git']==source_commit,'Single frozen source differs')
    require(spec['common_probes']==[] and spec['identities']['C1']['production_overrides']==[],'Unexpected overlay')
    require(hashes['original_protocol_sha256']=='1065177d7809426d949c4b4b67bd639a47c3fc1943f706a77c2dfabb985e9aba','Public protocol changed')
    auth=strict_json((control_root/'ci/pr20-causal/authorization.json').read_bytes())
    require(auth['approved']is True and auth['status']=='FROZEN' and auth['plan_git']==readiness_git,'Readiness/authorization identity differs')
    require(all(auth[k]==v for k,v in hashes.items()) and binding['input_hashes']==hashes,'Frozen input hashes differ')
    require(auth['phase_cutoff_minutes']=={'setup':30,'data':45,'raster':50,'work':55} and auth['job_timeout_minutes']==60,'Budget differs')
    require(auth['task_build_cache']is False and auth['new_permissions']is False and auth['automatic_retry']is False,'Execution scope differs')
    manifest=strict_json((directory/'C1-source-manifest.json').read_bytes())
    canonical=sha(json.dumps(manifest['files'],sort_keys=True,separators=(',',':')).encode())
    require(canonical==manifest['canonical_manifest_sha256']==spec['identities']['C1']['canonical_manifest_sha256'],'Canonical source manifest differs')
    for entry in plan['steps']:
        rule=contract['methods'][str(entry['ordinal'])]
        require(entry['selection']==rule['selection'] and entry['marker_policy']==rule['marker_policy'],'Method marker mapping differs')
        require(manifest['files'][rule['test_source_path']]['sha256']==rule['test_source_sha256'],'Test hash/source binding differs')
    controls=binding['control_hashes'];required={'ci/pr20-causal/'+f for f in ['run.py','markers.py','package.py','cleanup.py','authorization.json','workflow.proposed.yml']}
    require(type(controls)is dict and required<=set(controls),'Missing control hashes')
    workflows=[p for p in controls if p.startswith('.github/workflows/')and p.endswith('.yml')];require(len(workflows)==1,'Exactly one active workflow required')
    for relative,h in controls.items():
        digest(h);p=Path(relative);require(not p.is_absolute()and'..'not in p.parts,'Unsafe control relative path')
        target=(control_root/p).resolve();require(target.is_relative_to(control_root.resolve())and sha(target.read_bytes())==h,'Control file hash differs: '+relative)
    require(controls['ci/pr20-causal/markers.py']==FROZEN_MARKERS_SHA==sha(Path(frozen_markers.__file__).read_bytes()),'Validator differs from frozen controller')
    workflow=yaml.safe_load((control_root/workflows[0]).read_text());require(workflow['permissions']=={'contents':'read'}and len(workflow['jobs'])==1,'Workflow permission/job scope differs')
    job=next(iter(workflow['jobs'].values()));require(job['timeout-minutes']==60 and job['runs-on']=='ubuntu-24.04','Runner budget differs')
    require('github.run_attempt == 1'in job['if'] and 'github.event.created == false'in job['if'],'Activation guard differs')
    guard=re.findall('[0-9a-f]{40}',job['if']);require(guard==[binding['prep_sha']]and guard[0]!='0'*40,'Exact prep guard not bound')
    phases={}
    for step in job['steps']:
        command=step.get('run','');match=re.search(r'run\.py (setup|build C1|emulator|methods data|methods raster)(?:\s|$)',command)
        if match:phases[step['name']]='data'if match[1]=='methods data'else('raster'if match[1]=='methods raster'else'setup')
    require(list(phases.values())==['setup','setup','setup','data','raster'],'Workflow phase wrappers differ')
    branches=workflow['on']['push']['branches'];require(type(branches)is list and len(branches)==1,'One fixed push branch required')
    return {'plan':plan,'spec':spec,'contract':contract,'hashes':hashes,'phases':phases,'auth':auth,'branch':branches[0],'workflow_path':workflows[0]}

def official_metadata(binding,run,jobs,expected,frozen):
    for key in ['run_id','job_id','workflow_sha','source_commit','readiness_git']:
        require(binding[key]==expected[key],'Expected official binding differs: '+key)
    require(binding['scope']=='DIAGNOSTIC_ONLY'and str(binding['run_attempt'])=='1','Binding scope/attempt differs')
    require(str(run['id'])==expected['run_id']and run['head_sha']==expected['workflow_sha']and run['run_attempt']==1,'Actual run identity differs')
    require(run['event']=='push' and run['head_branch']==frozen['branch'] and run['path']==frozen['workflow_path'],'Official event/branch/workflow path differs')
    require(jobs['total_count']==1 and len(jobs['jobs'])==1,'Expected exactly one official job')
    selected=[j for j in jobs['jobs']if str(j['id'])==expected['job_id']];require(len(selected)==1,'Missing/duplicate official job')
    job=selected[0];require(str(job['run_id'])==expected['run_id']and job['head_sha']==expected['workflow_sha']and job['run_attempt']==1,'Actual job identity differs')
    for key,actual in [('run_status',run['status']),('run_conclusion',run['conclusion']),('job_status',job['status']),('job_conclusion',job['conclusion'])]:require(binding[key]==actual,'Binding metadata differs: '+key)
    start,end=epoch(job['started_at']),epoch(job['completed_at']);require(0<=end-start<=3600,'Official job hard 60min budget exceeded')
    return job

def parse(text,frozen,expected,state,job):
    plan,contract,spec=(frozen[k]for k in ['plan','contract','spec']);methods=state['methods'];blocks=state['blocks'];active=None;clock=None;final=None;last_time=None
    for line_number,raw in enumerate(text.splitlines(),1):
        stamp=re.match(r'^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?Z)\s+(.*)$',raw)
        line=stamp[2]if stamp else raw
        if not line.startswith(PREFIX):continue
        state['last_receipt_line']=line_number
        require(stamp is not None,'Official receipt lacks UTC log timestamp')
        at=epoch(stamp[1]);require(last_time is None or at>=last_time,'Official receipt timestamp reversed');last_time=at
        require(epoch(job['started_at'])<=at<=epoch(job['completed_at'])+1,'Receipt outside official job span')
        tag,sep,encoded=line.partition(' ');require(sep and len(encoded.encode())<=2*1024*1024,'Malformed/oversize log envelope')
        kind=tag[len(PREFIX):];require(kind in ['CLOCK','CONTEXT','RECORD','END','METHOD','FINAL'],'Unknown causal prefix')
        value=strict_json(encoded);require(final is None,'Receipt after FINAL')
        if kind=='CLOCK':
            require(clock is None and not methods and active is None,'Duplicate/late CLOCK')
            keys(value,'status scope anchor_kind budget_anchor_epoch first_step_observed_epoch actual_job_started_epoch deadlines_minutes deadline_epochs')
            start=value['budget_anchor_epoch'];require(type(start)is int and start>0 and value['status']=='PASS'and value['scope']=='DIAGNOSTIC_ONLY'and value['anchor_kind']=='runner_first_step','Invalid clock')
            require(start<=value['first_step_observed_epoch']<start+1 and value['actual_job_started_epoch']is None,'Clock anchor differs')
            require(epoch(job['started_at'])<=value['first_step_observed_epoch']<=at,'First step clock outside actual job')
            require(value['deadlines_minutes']==[30,45,50,55,60]and value['deadline_epochs']=={k:start+m*60 for k,m in [('setup',30),('data',45),('raster',50),('work',55)]},'Clock cutoff differs')
            clock=value;state['clock']=value;continue
        require(clock is not None,'Missing CLOCK')
        if kind in ['CONTEXT','RECORD','END','METHOD']:
            require(len(methods)<13,'Extra method block')
            step=plan['steps'][len(methods)];phase='data'if step['phase']=='data-diagnostics'else'raster'
            require(at<=min(clock['deadline_epochs'][phase],clock['deadline_epochs']['work']),'Method receipt after phase/work deadline')
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
            selected=value['selected_apks'];installed=value['installed_apks'];packages=['org.inkweft.data.test']if step['ordinal']<13 else['org.inkweft.app.a0','org.inkweft.app.a0.test']
            require(type(selected)is list and type(installed)is list and [x['package']for x in selected]==[x['package']for x in installed]==packages,'APK package group differs')
            for one,two in zip(selected,installed):
                keys(one,'package sha256 signer_sha256');keys(two,'package sha256 source_apk_sha256');digest(one['sha256']);require(one['sha256']==two['sha256']==two['source_apk_sha256'],'Selected/installed APK mismatch')
                require(type(one['signer_sha256'])is list and len(one['signer_sha256'])==1,'Signer missing');digest(one['signer_sha256'][0])
            active={'context':value,'records':[],'payloads':[],'ordered':[],'ended':False,'method_pass':False,'line':line_number,'first_receipt_epoch':at};blocks.append(active)
        elif kind=='RECORD':
            require(active is not None and not active['ended'],'Record outside open block');keys(value,'ordinal identity sequence marker payload_bytes payload_sha256 original_json')
            require(value['ordinal']==step['ordinal']and value['identity']=='C1'and type(value['sequence'])is int and value['sequence']==len(active['records']),'Record order/identity differs')
            rule=contract['methods'][str(step['ordinal'])];marker=value['marker'];require(marker in rule['markers']and type(value['original_json'])is str,'Unexpected record marker')
            original=value['original_json'];raw_payload=original.encode();require(type(value['payload_bytes'])is int and value['payload_bytes']==len(raw_payload)<=rule['markers'][marker]['payload_max_bytes']and sha(raw_payload)==value['payload_sha256'],'Original payload size/hash differs')
            data=strict_json(original);frozen_markers.validate_shape(data,contract['schemas'][marker]);active['records'].append({'marker':marker,'data':data});active['payloads'].append(original);active['ordered'].append({k:value[k]for k in ['sequence','marker','payload_bytes','payload_sha256']})
            require(len(active['records'])<=sum(r['max_count']for r in rule['markers'].values()),'Too many records')
        elif kind=='END':
            require(active is not None and not active['ended'],'Missing/duplicate END');keys(value,'ordinal identity count ordered_payloads ordered_payloads_sha256')
            require(value['ordinal']==step['ordinal']and value['identity']=='C1'and type(value['count'])is int and value['count']==len(active['records']),'END count differs')
            require(value['ordered_payloads']==active['ordered']and sha(compact(value['ordered_payloads']))==value['ordered_payloads_sha256'],'Ordered payload footer differs')
            raw='\n'.join(r['marker']+' '+p for r,p in zip(active['records'],active['payloads']))
            frozen_markers.safe_marker_payloads(step,raw,contract)
            require(sha(compact(active['records'])+b'\n')==active['context']['records_sha256'],'Complete parsed records hash differs')
            active['ended']=True;active['end_line']=line_number
        elif kind=='METHOD':
            keys(value,METHOD_KEYS);state['observed_method_receipts'].append(value)
            require(type(value['ordinal'])is int and all(value[k]==step[k]for k in ['ordinal','phase','identity','selection']),'METHOD order differs')
            require(value['status']==value['junit_status']=='PASS'and type(value['adb_exit'])is int and value['adb_exit']==0 and value['process_exit_outcome']=='NATURAL_EXIT'and value['error']is None,'METHOD failed/incomplete/skipped')
            require(active is not None and active['ended']and active['context']['ordinal']==step['ordinal'],'Missing complete method block')
            active['method_pass']=True;active['method_receipt_epoch']=at;methods.append(value);active=None
        else:
            state['final_observed']=value
            require(active is None and len(methods)==len(blocks)==13,'FINAL before all 13 closures');final=value
    require(clock is not None and final is not None and active is None and len(methods)==len(blocks)==13,'Missing/unfinished receipt set')
    keys(final,FINAL_KEYS);require(final['scope']=='DIAGNOSTIC_ONLY'and final['run_id']==expected['run_id']and final['workflow_sha']==expected['workflow_sha']and final['input_hashes']==frozen['hashes'],'FINAL input/identity mismatch')
    require(final['status']=='EXECUTION_COMPLETE_ANALYSIS_PENDING'and final['cleanup_verified']is True and final['packaging_complete']is True and final['missing_files']==[],'FINAL incomplete execution/evidence/cleanup')
    for key in ['device_before_sha256','device_after_sha256','manifest_sha256','summary_sha256']:digest(final[key])
    require(final['device_before_sha256']==final['device_after_sha256'],'Device environment changed')
    keys(final['cleanup'],'trap always')
    for clean in final['cleanup'].values():
        keys(clean,'emulator command_groups acl error_count before_sha256 after_sha256 exact_match');require(clean['emulator']==clean['command_groups']=='STOPPED_CONFIRMED'and clean['acl']in ['UNCHANGED_EXACT','RESTORED_EXACT']and type(clean['error_count'])is int and clean['error_count']==0 and clean['exact_match']is True,'Cleanup claim not successful')
        digest(clean['before_sha256']);digest(clean['after_sha256']);require(clean['before_sha256']==clean['after_sha256'],'ACL hashes differ')
    require(final['cleanup']['trap']['before_sha256']==final['cleanup']['always']['before_sha256'],'ACL origins differ')
    require(type(final['methods'])is list and len(final['methods'])==13,'FINAL method count')
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
        if phase in ['data','raster']:
            for block in blocks:
                expected_phase='data'if block['context']['ordinal']<=12 else 'raster'
                if expected_phase==phase:require(start<=block['first_receipt_epoch']<=block['method_receipt_epoch']<=end+1,'Method block outside its official phase step')
        phase_times.append({'name':name,'phase':phase,'start_epoch':start,'end_epoch':end,'seconds':end-start})
    state['phase_times']=phase_times;state['final']=final

def analyze(text,frozen,expected,binding,run,jobs):
    state={'clock':None,'methods':[],'observed_method_receipts':[],'blocks':[],'final_observed':None,'final':None}
    result={'schema':1,'analysis_status':'INCOMPLETE','scope':'DIAGNOSTIC_ONLY','expected':expected,'issues':[],'state':state,'numeric_acceptance':'NOT_EVALUATED','limitations':['Official run/job metadata and decoded-log provenance are supplied externally; no online authenticity check.','Compact receipts do not independently audit APK binaries, raw JUnit files, native libraries, installed bytes or ACL files.','records_sha256 is reconstructed exactly; raw_log_sha256 is only linked between CONTEXT and FINAL.','Group post-environment equality is a bound-controller PASS claim; only its before environment hash is directly forwarded.','All diagnostics are instrumented observations. No formal gate, product speedup, pure lock-wait or additive causal attribution is claimed.']}
    try:
        job=official_metadata(binding,run,jobs,expected,frozen)
        official_success=run['status']==job['status']=='completed'and run['conclusion']==job['conclusion']=='success'
        result['official_statuses']={'run_status':run['status'],'run_conclusion':run['conclusion'],'job_status':job['status'],'job_conclusion':job['conclusion']}
        if not official_success:result['issues'].append('Official run/job not successful; preserve partial records only')
        parse(text,frozen,expected,state,job)
        if not official_success:return result
        result.update(analysis_status='COMPLETE_SCOPED_CAUSAL_LOG_EVIDENCE',official_job_span_seconds=epoch(job['completed_at'])-epoch(job['started_at']))
    except (ValueError,AssertionError,KeyError,TypeError,IndexError,OverflowError,RecursionError)as error:result['issues'].append(type(error).__name__+': '+str(error))
    return result

def write_outputs(out,result):
    out.mkdir(parents=True,exist_ok=False);ledger=[]
    for block in result.get('state',{}).get('blocks',[]):
        ordinal=block['context']['ordinal'];name=f'method-{ordinal:02}-C1-records.json';data=compact(block['records'])+b'\n';(out/name).write_bytes(data)
        ledger.append({'ordinal':ordinal,'path':name,'sha256':sha(data),'record_count':len(block['records']),'block_closed':block['ended'],'method_pass':block['method_pass'],'whole_run_complete':result['analysis_status']=='COMPLETE_SCOPED_CAUSAL_LOG_EVIDENCE'})
        block['record_count']=len(block['records']);block.pop('records');block.pop('payloads')
    result['extracted_records']=ledger
    (out/'analysis.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')

def main():
    p=argparse.ArgumentParser(description=__doc__)
    for key in ['job-log','frozen','control-root','binding','run-metadata','jobs-metadata','out']:p.add_argument('--'+key,type=Path,required=True)
    for key in ['run-id','job-id','workflow-sha','source-commit','readiness-git']:p.add_argument('--'+key,required=True)
    a=p.parse_args();expected={k:getattr(a,k)for k in ['run_id','job_id','workflow_sha','source_commit','readiness_git']}
    result={'analysis_status':'INCOMPLETE','issues':[],'expected':expected}
    try:
        require(re.fullmatch('[0-9]+',a.run_id)and re.fullmatch('[0-9]+',a.job_id),'Invalid numeric run/job identity')
        for key in ['workflow_sha','source_commit','readiness_git']:require(re.fullmatch('[0-9a-f]{40}',expected[key]),'Invalid Git identity')
        binding=strict_json(a.binding.read_bytes());run=strict_json(a.run_metadata.read_bytes());jobs=strict_json(a.jobs_metadata.read_bytes())
        frozen=frozen_inputs(a.frozen,a.source_commit,a.readiness_git,a.control_root,binding)
        raw=a.job_log.read_bytes();require(len(raw)<=64*1024*1024,'Decoded log input exceeds bounded 64MiB local analyzer cap')
        result=analyze(raw.decode(),frozen,expected,binding,run,jobs)
        result['input_hashes']=frozen['hashes'];result['files']={str(f):{'bytes':f.stat().st_size,'sha256':sha(f.read_bytes())}for f in [a.job_log,a.binding,a.run_metadata,a.jobs_metadata]}
    except (ValueError,AssertionError,OSError,KeyError,TypeError,OverflowError,RecursionError)as error:
        result['analysis_status']='INCOMPLETE'
        result['issues'].append(type(error).__name__+': '+str(error))
    result['analyzer_sha256']=sha(Path(__file__).read_bytes());result['frozen_markers_sha256']=sha(Path(frozen_markers.__file__).read_bytes())
    write_outputs(a.out,result);print(json.dumps({'analysis_status':result['analysis_status'],'issues':result['issues']}));return int(result['analysis_status']!='COMPLETE_SCOPED_CAUSAL_LOG_EVIDENCE')
if __name__=='__main__':raise SystemExit(main())
