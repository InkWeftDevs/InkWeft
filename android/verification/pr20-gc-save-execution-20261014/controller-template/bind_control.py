"""Bind an independently reviewed readiness commit into a new scratch controller; no activation."""
import argparse,hashlib,json,re,shutil,subprocess
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--repo',type=Path,required=True);p.add_argument('--plan-git',required=True);p.add_argument('--readiness',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
assert re.fullmatch('[0-9a-f]{40}',a.plan_git) and a.out.resolve().is_relative_to('/workspace/scratch') and not a.out.exists()
here=Path(__file__).resolve().parent;relative='android/verification/pr20-gc-save-20261014'
for path in a.readiness.iterdir():
 assert path.is_file() and subprocess.check_output(['git','-C',str(a.repo),'show',a.plan_git+':'+relative+'/'+path.name])==path.read_bytes(), 'Readiness commit mismatch'
spec=json.loads((a.readiness/'source-spec.json').read_text());assert spec['status']=='FROZEN'
a.out.mkdir(parents=True)
for name in ['run.py','markers.py','package.py','cleanup.py','workflow.proposed.yml']:
 data=(here/name).read_text()
 if name in ['run.py','package.py']:data=data.replace("'UNBOUND'","'"+hashlib.sha256((a.readiness/'source-spec.json').read_bytes()).hexdigest()+"'")
 (a.out/name).write_text(data)
auth=json.loads((here/'authorization.json').read_text());auth.update(approved=True,status='FROZEN',plan_git=a.plan_git)
for key,name in [('approved_plan_sha256','PLAN.md'),('source_spec_sha256','source-spec.json'),('method_plan_sha256','method-plan.json'),('original_protocol_sha256','original-protocol.json'),('marker_contract_sha256','marker-contract.json'),('prepare_sources_sha256','prepare_sources.py')]:auth[key]=hashlib.sha256((a.readiness/name).read_bytes()).hexdigest()
(a.out/'authorization.json').write_text(json.dumps(auth,indent=2)+'\n')
assert all('UNBOUND' not in f.read_text()for f in a.out.iterdir())
print(json.dumps({'status':'BOUND_NOT_ACTIVATED','source_git':spec['identities']['C1']['git'],'plan_git':a.plan_git,'out':str(a.out),'guard':'Still zero; root binds exact prep parent after final review'}))
