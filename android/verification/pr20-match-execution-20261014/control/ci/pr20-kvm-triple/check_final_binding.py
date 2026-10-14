"""Local final binding only. No runner/SDK/network execution or authorization creation."""
import argparse,ast,datetime,hashlib,json,re,subprocess,tempfile,time,types
from pathlib import Path
import yaml

REVIEW=Path('/workspace/scratch/pr20-match-candidate-20261014/preparation62/reviewed-final')
REL='android/verification/pr20-match-candidate-20261014'
BRANCH='codex/kvm-pr20-match-candidate-20261014'
ZERO='0'*40
CUTOFF_MINUTES={'setup':38,'correctness':41,'common':53,'diagnostics':55}
INPUT_NAMES={'approved_plan_sha256':'PLAN.md','source_spec_sha256':'source-spec.json','method_plan_sha256':'method-plan.json','original_protocol_sha256':'original-protocol.json','marker_contract_sha256':'marker-contract.json'}
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def load(p):return json.loads(p.read_text())
def git(repo,*args):return subprocess.check_output(['git','-C',str(repo),*args],timeout=30)

def check_binding(repo,readiness_git,prep_sha,expected_pr_head,now=None):
    repo=repo.resolve();here=repo/'ci/pr20-kvm-triple';frozen=repo/REL
    reviewed=REVIEW/'bound-control/ci/pr20-kvm-triple'
    assert all(re.fullmatch('[0-9a-f]{40}',value)for value in [readiness_git,prep_sha,expected_pr_head])
    assert git(repo,'rev-parse',readiness_git+'^{commit}').decode().strip()==readiness_git
    committed=git(repo,'ls-tree','-r','--name-only',readiness_git,'--',REL).decode().splitlines()
    assert committed and all(path.startswith(REL+'/')for path in committed)
    for path in committed:assert (repo/path).read_bytes()==git(repo,'show',readiness_git+':'+path),'Committed readiness differs: '+path
    # Bind the independently reviewed readiness inputs, not merely any self-consistent commit.
    expected=load(REVIEW/'handoff.json')['readiness_hashes']
    for name,digest in expected.items():assert sha(frozen/name)==digest,'Reviewed readiness differs: '+name
    executable=['run.py','markers.py','package.py','cleanup.py']
    for name in executable:assert (here/name).read_bytes()==(reviewed/name).read_bytes(),'Executable changed after review: '+name
    auth=load(here/'authorization.json');budget=load(here/'budget-revision.json');contract=load(here/'clock-contract.json');receipt=load(here/'clock-server-receipt.json')
    for path in [here/name for name in ['authorization.json','budget-revision.json','clock-contract.json','clock-server-receipt.json','workflow.proposed.yml']]:
        assert '__CLOCK_'not in path.read_text()and 'UNBOUND'not in path.read_text(),'Binding placeholder remains: '+path.name
    inputs={k:sha(frozen/n)for k,n in INPUT_NAMES.items()}
    inputs.update(clock_contract_sha256=sha(here/'clock-contract.json'),server_receipt_sha256=sha(here/'clock-server-receipt.json'))
    assert auth['approved']is True and auth['plan_git']==readiness_git
    assert all(auth[k]==v for k,v in inputs.items())
    assert auth['budget_revision_sha256']==sha(here/'budget-revision.json')
    assert auth['phase_cutoff_minutes']==CUTOFF_MINUTES and auth['clock_status']=='FROZEN'
    assert set(receipt)=={'http_status','id','number','node_id','draft','updated_at','head_sha'}
    assert receipt['head_sha']==expected_pr_head,'PR receipt differs from the independently expected PR20 integration head'
    start=contract['budget_anchor_epoch'];assert type(start)is int
    observed=time.time()if now is None else now
    assert start<=observed<start+38*60,'Future or expired anchor; never replace or rebase it'
    assert budget['status']=='CONTINUE_APPROVED_BUDGET3_MATCH62_FROZEN_NOT_PERFORMANCE_EVIDENCE'
    # Only legitimate finalizer changes may differ from the reviewed metadata; this
    # preserves every historical object and the corrected three live scope leaves.
    expected_budget=load(reviewed/'budget-revision.json')
    expected_budget['status']=budget['status']
    for key in ['budget_anchor_epoch','clock_contract_sha256','server_receipt_sha256']:
        expected_budget['clock_amendment'][key]=budget['clock_amendment'][key]
    phase=budget['phase_budget_amendment']
    assert phase['readiness_git']==readiness_git and re.fullmatch('[0-9a-f]{40}',phase['terminal_report_git'])
    for key in ['readiness_git','terminal_report_git']:expected_budget['phase_budget_amendment'][key]=phase[key]
    assert budget==expected_budget,'Unexpected current or historical budget change'
    assert phase['method_plan_sha256']==inputs['method_plan_sha256'] and phase['source_spec_sha256']==inputs['source_spec_sha256'] and phase['plan_sha256']==inputs['approved_plan_sha256']
    expected_auth=load(reviewed/'authorization.json')
    for key in ['approved','plan_git','budget_revision_sha256','clock_contract_sha256','server_receipt_sha256','budget_anchor_epoch','clock_status','scope','continuation']:
        expected_auth[key]=auth[key]
    for section in ['source_method_amendment','phase_budget_override']:
        assert auth[section]['terminal_report_git']==phase['terminal_report_git']
        expected_auth[section]['terminal_report_git']=phase['terminal_report_git']
    assert auth==expected_auth,'Unexpected current or historical authorization change'
    assert '62calls='in auth['scope'] and '38/41/53/55'in auth['scope'] and 'One attempt only'in auth['continuation']
    assert budget['new_cutoffs_minutes']==[38,41,53,55,60] and budget['unchanged']['task_build_cache']is False
    plan=load(frozen/'method-plan.json');markers=load(frozen/'marker-contract.json')
    assert len(plan['steps'])==62 and [s['ordinal']for s in plan['steps']]==list(range(1,63))
    assert [sum(s['phase']==p for s in plan['steps'])for p in ['correctness','common-comparison','candidate-diagnostics']]==[31,24,7]
    assert len({s['install_group']for s in plan['steps']})==14 and markers['method_plan_sha256']==inputs['method_plan_sha256']
    for step in plan['steps']:
        rule=markers['methods'][str(step['ordinal'])];manifest=load(frozen/(step['identity']+'-source-manifest.json'))
        assert rule['selection']==step['selection'] and rule['test_source_sha256']==manifest['files'][rule['test_source_path']]['sha256']
    workflow_path=here/'workflow.proposed.yml';workflow_text=workflow_path.read_text()
    expected_workflow=(reviewed/'workflow.proposed.yml').read_text()
    for old,new in {'__CLOCK_ANCHOR_EPOCH__':str(start),'__CLOCK_UPDATED_AT__':receipt['updated_at'],'__CLOCK_CONTRACT_SHA256__':inputs['clock_contract_sha256'],'__CLOCK_RECEIPT_SHA256__':inputs['server_receipt_sha256'],ZERO:prep_sha}.items():
        expected_workflow=expected_workflow.replace(old,new)
    assert workflow_text==expected_workflow,'Workflow changed beyond frozen clock literals/prep guard'
    wf=yaml.safe_load(workflow_text);job=wf['jobs']['triple']
    assert wf['permissions']=={'contents':'read'}and set(wf['jobs'])=={'triple'}and job['runs-on']=='ubuntu-24.04'and job['timeout-minutes']==60
    assert wf['on']=={'push':{'branches':[BRANCH],'paths':['.github/workflows/pr20-kvm-triple.yml']}}
    assert wf['concurrency']['cancel-in-progress']is False
    assert job['if']==f"github.event_name == 'push' && github.ref == 'refs/heads/{BRANCH}' && github.event.created == false && github.event.before == '{prep_sha}' && github.run_attempt == 1"
    active=repo/'.github/workflows/pr20-kvm-triple.yml'
    if prep_sha==ZERO:assert not active.exists(),'Zero-prep stage cannot contain active workflow'
    else:
        assert git(repo,'rev-parse',prep_sha+'^{commit}').decode().strip()==prep_sha
        if active.exists():assert active.read_bytes()==workflow_path.read_bytes(),'Active/proposed mismatch'
    for step in job['steps']:
        if 'run'in step:
            checked=subprocess.run(['bash','-n'],input=step['run'],capture_output=True,text=True,timeout=10);assert checked.returncode==0,checked.stderr
    lint=subprocess.run(['/workspace/scratch/actionlint-1.7.12/actionlint','-shellcheck=',str(workflow_path)],capture_output=True,text=True,timeout=30)
    assert lint.returncode==0,lint.stdout+lint.stderr
    # Execute only the exact audited validate_clock and seconds AST functions. The
    # synthetic OUT clock models these checked workflow literals; it is NOT an
    # actual runner clock or evidence of a future official job's start time.
    cutoffs={k:start+m*60 for k,m in CUTOFF_MINUTES.items()}
    env={'KVM_BUDGET_ANCHOR':start,'KVM_SETUP_CUTOFF':cutoffs['setup'],'KVM_CORRECTNESS_CUTOFF':cutoffs['correctness'],'KVM_COMMON_CUTOFF':cutoffs['common'],'KVM_WORK_DEADLINE':cutoffs['diagnostics']}
    clock={'status':'PASS','anchor_kind':'github_pull_request_updated_at','updated_at':receipt['updated_at'],'clock_contract_sha256':inputs['clock_contract_sha256'],'server_receipt_sha256':inputs['server_receipt_sha256'],'actual_job_started_epoch':None,'first_step_observed_epoch':observed,'budget_anchor_epoch':start,'deadlines_minutes':[38,41,53,55,60],'deadline_epochs':env}
    tree=ast.parse((here/'run.py').read_text());nodes=[n for n in tree.body if isinstance(n,ast.FunctionDef)and n.name in ['validate_clock','seconds']];assert len(nodes)==2
    with tempfile.TemporaryDirectory(prefix='pr20-binding-clock-',dir='/workspace/scratch')as temp:
        out=Path(temp);(out/'clock.json').write_text(json.dumps(clock))
        scope={'json':json,'hashlib':hashlib,'datetime':datetime,'re':re,'HERE':here,'OUT':out,'START':start,'CUTOFFS':cutoffs,'DEADLINE':cutoffs['diagnostics'],'PHASE':'setup','INPUT_HASHES':inputs,'os':types.SimpleNamespace(environ={k:str(v)for k,v in env.items()}),'time':types.SimpleNamespace(time=lambda:observed),'BudgetAbort':RuntimeError}
        exec(compile(ast.Module(body=nodes,type_ignores=[]),'<audited-local-validate_clock>','exec'),scope)
        scope['validate_clock'](auth)
    return {'status':'PASS','scope':'LOCAL_FINAL_BINDING_ONLY_NOT_JOB_OR_NATIVE_EVIDENCE','repo':str(repo),'readiness_git':readiness_git,'prep_sha':prep_sha,'expected_pr_head':expected_pr_head,'source_git':load(frozen/'source-spec.json')['identities']['C1']['git'],'committed_readiness_files_checked':len(committed),'input_hashes':inputs,'control_hashes':{p.name:sha(p)for p in here.iterdir()if p.is_file()},'clock':{'anchor_epoch':start,'anchor_utc':receipt['updated_at'],'locally_checked_epoch':observed,'actual_job_started_epoch':None,'cutoffs':cutoffs},'approved':True,'active_workflow_present':active.exists(),'actual_runner_invocations':0,'network_calls':0,'launch_authorization_created':False,'pending_external_checks':['No active overlapping run','Prep head had zero workflow runs before activation','Actual job start is at or after immutable anchor','Only one authorized activation/attempt']}

def main():
    p=argparse.ArgumentParser();p.add_argument('--repo',required=True,type=Path);p.add_argument('--readiness-git',required=True);p.add_argument('--prep-sha',required=True);p.add_argument('--expected-pr-head',required=True);p.add_argument('--out',required=True,type=Path);a=p.parse_args()
    assert a.out.resolve().is_relative_to('/workspace/scratch')and not a.out.exists();a.out.parent.mkdir(parents=True,exist_ok=True)
    try:result=check_binding(a.repo,a.readiness_git,a.prep_sha,a.expected_pr_head)
    except Exception as error:result={'status':'FAIL','scope':'LOCAL_FINAL_BINDING_ONLY','error':type(error).__name__+': '+str(error)}
    result['checker_sha256']=sha(Path(__file__));a.out.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print(json.dumps({'status':result['status'],'receipt':str(a.out)}));raise SystemExit(result['status']!='PASS')
if __name__=='__main__':main()
