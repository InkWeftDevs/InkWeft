"""Freeze two committed source trees into a NEW scratch directory, without build/network/native."""
import argparse,copy,hashlib,io,json,subprocess,tarfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--candidate',required=True);p.add_argument('--repo',type=Path,default=Path('/workspace/InkWeft'));p.add_argument('--out',type=Path,required=True);a=p.parse_args()
here=Path(__file__).resolve().parent
parent='445654c90a4c78893f158fdc4eca4313f3320a7f';relative='android/verification/pr20-candidate3-readiness-20261014/'
def git(*args):return subprocess.check_output(['git','-C',str(a.repo),*args])
def sha(data):return hashlib.sha256(data).hexdigest()
def write(name,value):(a.out/name).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n')
assert a.out.resolve().is_relative_to('/workspace/scratch') and not a.out.exists()
assert len(a.candidate)==40 and git('rev-parse',a.candidate).decode().strip()==a.candidate
oldraw=git('show',parent+':'+relative+'source-spec.json');assert sha(oldraw)=='d8ba1042334d8a6386ed6d1d54fbaad4a00fc6d76acc7fbb39005e6bcf897ba3'
old=json.loads(oldraw);previous=old['identities']['C1'];assert previous['git']=='d66b60d6cf9587f646acb76dd8a00910b73a6d70'
spec={'schema':1,'status':'FROZEN_SOURCE_ONLY_SCREENING_NOT_RUN','materialized_scope':['android','ci'],'excluded_from_build_inputs':['android/verification'],
      'identities':{x:dict(git=g,tree=git('rev-parse',g+'^{tree}').decode().strip(),production_overrides=[]) for x,g in [('C0',previous['git']),('C1',a.candidate)]},
      'common_probes':[copy.deepcopy(x) for x in old['common_probes'] if x['path'].endswith('/KvmLibraryPairProbe.kt')],
      'native_execution':'NOT_RUN','version_aliases':{'C0':'previous_candidate_d66','C1':'empty_draft_cte_candidate'}}
assert len(spec['common_probes'])==1
assert spec['common_probes'][0]['sha256']=='90525d56efe48b32441be890e29c021d0f699fe7d82389d1e049283cfb10f98c'
a.out.mkdir(parents=True)
allfiles={}
for label,identity in spec['identities'].items():
 files={}
 with tarfile.open(fileobj=io.BytesIO(git('archive','--format=tar',identity['git'],'android','ci'))) as archive:
  for entry in archive:
   assert entry.isfile() or entry.isdir(),'No links/devices in source archive'
   assert not Path(entry.name).is_absolute() and '..' not in Path(entry.name).parts
   if entry.isfile() and not entry.name.startswith('android/verification/'):
    content=archive.extractfile(entry).read();files[entry.name]={'bytes':len(content),'sha256':sha(content)}
 for probe in spec['common_probes']:
  content=git('show',probe['from_git']+':'+probe['from_path']);assert sha(content)==probe['sha256'] and len(content)==probe['bytes']
  files[probe['path']]={'bytes':len(content),'sha256':sha(content)}
 files=dict(sorted(files.items()));canonical=sha(json.dumps(files,sort_keys=True,separators=(',',':')).encode())
 identity.update(canonical_manifest_sha256=canonical,file_count=len(files));allfiles[label]=files
 write(label+'-source-manifest.json',{'identity':label,'git':identity['git'],'canonical_manifest_sha256':canonical,'files':files})
# C0 equals the previous formal C1 materialization after removing the intentionally omitted app probe overlay.
prior=json.loads(git('show',parent+':'+relative+'C1-source-manifest.json'))['files']
app_probe=next(x for x in old['common_probes'] if '/app/' in x['path'])['path']
try:
 content=git('show',previous['git']+':'+app_probe);prior[app_probe]={'bytes':len(content),'sha256':sha(content)}
except subprocess.CalledProcessError:prior.pop(app_probe,None)
assert allfiles['C0']==prior,'C0 content drift beyond excluded app-probe overlay'
delta={x:{'before':allfiles['C0'].get(x),'after':allfiles['C1'].get(x)} for x in sorted(set(allfiles['C0'])|set(allfiles['C1'])) if allfiles['C0'].get(x)!=allfiles['C1'].get(x)}
write('source-spec.json',spec);write('candidate-source-delta.json',{'from_git':previous['git'],'to_git':a.candidate,'file_deltas':delta})
for name in ['prepare_sources.py','method-plan.json','original-protocol.json','marker-contract.json']:
 (a.out/name).write_bytes((here/'readiness-template'/name).read_bytes())
plan=json.loads((a.out/'method-plan.json').read_text());plan['status']='FROZEN_SCREENING_NOT_RUN';plan['identities']['C1']={'alias':'empty_draft_cte_candidate','git':a.candidate};write('method-plan.json',plan)
contract=json.loads((a.out/'marker-contract.json').read_text());contract['status']='FROZEN_SCREENING_LOG_SCHEMA';write('marker-contract.json',contract)
(a.out/'PLAN.md').write_text((here/'PLAN.template.md').read_text().replace('__SCREEN_CANDIDATE_GIT__',a.candidate))
print(json.dumps({'status':'SOURCE_ONLY_NOT_BUILT_NOT_RUN','out':str(a.out),'identities':spec['identities'],'source_spec_sha256':sha((a.out/'source-spec.json').read_bytes()),'method_plan_sha256':sha((a.out/'method-plan.json').read_bytes()),'changed_paths':list(delta)},indent=2))
