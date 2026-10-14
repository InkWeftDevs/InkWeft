"""Bind frozen screening inputs into a NEW scratch control dir; does not activate a workflow."""
import argparse,hashlib,json,re,shutil
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--readiness-dir',type=Path,required=True);p.add_argument('--plan-git',required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
here=Path(__file__).resolve().parent
assert a.out.resolve().is_relative_to('/workspace/scratch') and not a.out.exists()
assert re.fullmatch('[0-9a-f]{40}',a.plan_git)
spec=json.loads((a.readiness_dir/'source-spec.json').read_text());assert set(spec['identities'])=={'C0','C1'}
hashes={key:hashlib.sha256((a.readiness_dir/name).read_bytes()).hexdigest() for key,name in [('approved_plan_sha256','PLAN.md'),('source_spec_sha256','source-spec.json'),('method_plan_sha256','method-plan.json'),('original_protocol_sha256','original-protocol.json'),('marker_contract_sha256','marker-contract.json'),('prepare_sources_sha256','prepare_sources.py')]}
a.out.mkdir(parents=True)
for file in (here/'controller-template').iterdir():
 if file.is_file():
  text=file.read_text().replace('__SCREEN_SOURCE_SPEC_SHA256__',hashes['source_spec_sha256']);(a.out/file.name).write_text(text)
auth={'approved':True,'scope':'SCREENING_ONLY','plan_git':a.plan_git,**hashes,'phase_cutoff_minutes':{'setup':30,'correctness':35,'common':50,'work':55},'job_timeout_minutes':60,'task_build_cache':False,'new_permissions':False,'automatic_retry':False,'authorization_scope':'Existing user continuation authorizes bounded focused native screening; root controls one isolated prep-parent/attempt1 activation after independent review.'}
(a.out/'authorization.json').write_text(json.dumps(auth,indent=2)+'\n')
print(json.dumps({'status':'BOUND_NOT_ACTIVATED','out':str(a.out),'input_hashes':hashes,'pending':['root final review/checker','real prep commit in proposed workflow guard','EXECUTION_SHA256SUMS and active workflow exact copy']},indent=2))
