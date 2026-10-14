"""Bind source/readiness inputs to NEW scratch control; no clock binding, approval, or execution."""
import argparse,hashlib,json,shutil,re
from pathlib import Path
R=Path(__file__).resolve().parent
p=argparse.ArgumentParser();p.add_argument('--readiness',required=True,type=Path);p.add_argument('--out',required=True,type=Path);p.add_argument('--plan-git',default='UNBOUND');p.add_argument('--prior-report-git',default='UNBOUND');a=p.parse_args()
assert a.out.resolve().is_relative_to('/workspace/scratch')and not a.out.exists()
assert a.plan_git=='UNBOUND'or re.fullmatch('[0-9a-f]{40}',a.plan_git)
assert a.prior_report_git=='UNBOUND'or re.fullmatch('[0-9a-f]{40}',a.prior_report_git)
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
f=a.readiness;spec=json.loads((f/'source-spec.json').read_text());assert re.fullmatch('[0-9a-f]{40}',spec['identities']['C1']['git'])
replacements={'__MATCH_SOURCE_SPEC_SHA256__':sha(f/'source-spec.json'),'__MATCH_PLAN_SHA256__':sha(f/'PLAN.md'),'__MATCH_MARKER_CONTRACT_SHA256__':sha(f/'marker-contract.json'),'__MATCH_READINESS_GIT__':a.plan_git,'__MATCH_PRIOR_REPORT_GIT__':a.prior_report_git,'__MATCH_GIT__':spec['identities']['C1']['git']}
shutil.copytree(R/'control-template',a.out,ignore=shutil.ignore_patterns('__pycache__','*.pyc'))
c=a.out/'ci/pr20-kvm-triple'
for file in c.iterdir():
 if not file.is_file():continue
 text=file.read_text()
 for old,new in replacements.items():text=text.replace(old,new)
 if file.name in ['run.py','package.py']:text=text.replace("'UNBOUND'","'"+sha(f/'source-spec.json')+"'",1)
 file.write_text(text)
assert json.loads((c/'authorization.json').read_text())['approved']is False
(a.out/'source-binding-receipt.json').write_text(json.dumps({'status':'SOURCE_BOUND_CLOCK_AND_LAUNCH_UNBOUND','readiness':str(f),'plan_git':a.plan_git,'source_spec_sha256':sha(f/'source-spec.json'),'method_plan_sha256':sha(f/'method-plan.json'),'marker_contract_sha256':sha(f/'marker-contract.json'),'C1':spec['identities']['C1']['git'],'clock_anchor':None,'approved':False,'control_hashes':{x.name:sha(x)for x in c.iterdir()if x.is_file()}},indent=2)+'\n')
print(json.dumps({'out':str(a.out),'status':'SOURCE_BOUND_NO_CLOCK_NO_ACTIVATION','plan_git':a.plan_git}))
