"""Read-only verification and command plan. This script never copies, commits, pushes or activates."""
import argparse,hashlib,json,re,subprocess
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--source-git');p.add_argument('--repo',type=Path,default=Path('/workspace/InkWeft'));a=p.parse_args()
root=Path(__file__).resolve().parent;controller=root/'controller'
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
for line in (root/'CONTROLLER_SHA256SUMS').read_text().splitlines():
 expected,name=line.split('  ',1);assert sha(controller/name)==expected, 'Reviewed controller changed: '+name
contract=json.loads((controller/'marker-contract.template.json').read_text());assert contract['status']=='UNBOUND'
auth=json.loads((controller/'authorization.json').read_text());assert auth['approved']is False and auth['status']=='UNBOUND'
assert '0'*40 in (controller/'workflow.proposed.yml').read_text()
assert not (a.repo/'.github/workflows/pr20-save-boundary.yml').exists(), 'Do not overlay an active workflow'
for path,expected in contract['reviewed_source_sha256'].items():
 data=(a.repo/path).read_bytes()if a.source_git is None else subprocess.check_output(['git','-C',str(a.repo),'show',a.source_git+':'+path])
 assert hashlib.sha256(data).hexdigest()==expected, 'Six-file compile2 source binding mismatch: '+path
if a.source_git:assert re.fullmatch('[0-9a-f]{40}',a.source_git)
print(json.dumps({'status':'PASS_READ_ONLY_NO_COPY_NO_FREEZE_NO_BIND_NO_PUSH_NO_RUN','source_git':a.source_git or 'NOT_YET_BOUND','reviewed_source_files':6,'controller_files':len((root/'CONTROLLER_SHA256SUMS').read_text().splitlines()),'next':'Follow ROOT-HANDOFF.md with real source/readiness commits. No active workflow is created by these commands.'},indent=2))
