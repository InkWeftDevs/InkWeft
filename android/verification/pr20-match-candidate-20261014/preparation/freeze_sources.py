"""Freeze exact committed source inputs to a NEW scratch directory. Git read-only, no build/network."""
import argparse,copy,hashlib,io,json,subprocess,tarfile
from pathlib import Path
R=Path(__file__).resolve().parent
P=argparse.ArgumentParser();P.add_argument('--candidate',required=True);P.add_argument('--out',type=Path,required=True);P.add_argument('--repo',type=Path,default=Path('/workspace/InkWeft'));a=P.parse_args()
assert a.out.resolve().is_relative_to('/workspace/scratch') and not a.out.exists()
OLD='445654c90a4c78893f158fdc4eca4313f3320a7f';REL='android/verification/pr20-candidate3-readiness-20261014/'
def git(*args):return subprocess.check_output(['git','-C',str(a.repo),*args])
def sha(b):return hashlib.sha256(b).hexdigest()
def write(n,v):(a.out/n).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n')
assert len(a.candidate)==40 and git('rev-parse',a.candidate).decode().strip()==a.candidate
prior=json.loads(git('show',OLD+':'+REL+'source-spec.json'))
assert sha(git('show',OLD+':'+REL+'source-spec.json'))=='d8ba1042334d8a6386ed6d1d54fbaad4a00fc6d76acc7fbb39005e6bcf897ba3'
spec=copy.deepcopy(prior);spec['identities']['C0']=copy.deepcopy(prior['identities']['C1']);spec['identities']['C1']=copy.deepcopy(spec['identities']['C0'])
spec['identities']['C1'].update(git=a.candidate,tree=git('rev-parse',a.candidate+'^{tree}').decode().strip())
spec['status']='FROZEN_SOURCE_NOT_BUILT_NOT_RUN';spec['version_aliases']={'B':'controlled_baseline','C0':'previous_complete_candidate_d66b60d','C1':'matching_and_frame_pencil_candidate'}
spec['notes']=['B and common probes remain byte-identical to the prior full58 source manifest.','C0 equals prior full58 C1=d66, not the old45b slot.','C1 contains the entire committed delta from d66, including prior default-off diagnostic hooks; do not claim one-helper-only causal isolation.','62 methods preserve all old1..58 fields and add Unicode/NativeReuse/Frame/originaldense only.','No build/native/network operation is performed by freezing.']
a.out.mkdir(parents=True)
manifests={}
for label,identity in spec['identities'].items():
 files={}
 with tarfile.open(fileobj=io.BytesIO(git('archive','--format=tar',identity['git'],'android','ci')))as archive:
  for entry in archive:
   assert entry.isfile()or entry.isdir(),'Source links/devices rejected'
   if entry.isfile()and not entry.name.startswith('android/verification/'):
    data=archive.extractfile(entry).read();files[entry.name]={'bytes':len(data),'sha256':sha(data)}
 for overlay in spec['common_probes']+identity['production_overrides']:
  data=git('show',overlay['from_git']+':'+overlay['from_path']);assert len(data)==overlay['bytes']and sha(data)==overlay['sha256'];files[overlay['path']]={'bytes':len(data),'sha256':sha(data)}
 files=dict(sorted(files.items()));canonical=sha(json.dumps(files,sort_keys=True,separators=(',',':')).encode())
 if label in ['B','C0']:
  priorlabel='B'if label=='B'else'C1';p=json.loads(git('show',OLD+':'+REL+priorlabel+'-source-manifest.json'))
  assert p['files']==files and p['canonical_manifest_sha256']==canonical
 identity.update(canonical_manifest_sha256=canonical,file_count=len(files))
 manifests[label]={'identity':label,'git':identity['git'],'canonical_manifest_sha256':canonical,'files':files};write(label+'-source-manifest.json',manifests[label])
write('source-spec.json',spec)
for name in ['method-plan.json','PLAN.md','original-protocol.json','prepare_sources.py']:(a.out/name).write_bytes((R/'readiness-template'/name).read_bytes())
for name in ['original-protocol.json','prepare_sources.py']:assert(a.out/name).read_bytes()==git('show',OLD+':'+REL+name)
plan=json.loads((a.out/'method-plan.json').read_text());oldplan=json.loads(git('show',OLD+':'+REL+'method-plan.json'));assert plan['steps'][:58]==oldplan['steps']
# Method-plan bytes deliberately do not change when source status becomes frozen.
contract=json.loads((R/'readiness-template/marker-contract.json').read_text());contract['status']='FROZEN'
for step in plan['steps']:
 rule=contract['methods'][str(step['ordinal'])];rule['test_source_sha256']=manifests[step['identity']]['files'][rule['test_source_path']]['sha256']
contract['notes']=[n for n in contract['notes']if 'unbound'not in n.lower()]
write('marker-contract.json',contract)
text=(a.out/'PLAN.md').read_text().replace('UNBOUND: root must freeze C1, review complete differences, bind source manifest/marker test hashes/readiness Git, and freeze a new immutable PR-server clock before any execution. No actual launch authorization is produced by this template.','Source and marker test hashes frozen locally; native NOT_RUN. Readiness commit, authorization, independent review and a new immutable PR-server clock remain required before execution. This source freeze is not launch authorization.').replace('C1=UNBOUND','C1='+a.candidate)
(a.out/'PLAN.md').write_text(text)
source_sha=sha((a.out/'source-spec.json').read_bytes())
text=(R/'readiness-template/analyze.template.py').read_text().replace('__MATCH_SOURCE_SPEC_SHA256__',source_sha);assert '__MATCH_'not in text;(a.out/'analyze.py').write_text(text)
before=manifests['C0']['files'];after=manifests['C1']['files'];changes={k:{'before':before.get(k),'after':after.get(k)}for k in sorted(set(before)|set(after))if before.get(k)!=after.get(k)}
write('candidate-source-delta.json',{'from_git':spec['identities']['C0']['git'],'to_git':a.candidate,'file_deltas':changes,'production_paths':[k for k in changes if '/src/main/'in k],'notes':'Full transitive delta; default-off diagnostic hooks are not omitted.'})
# Reconstruct with the unchanged original preparer, offline; no Gradle/ADB.
reconstructed=a.out.parent/(a.out.name+'-reconstructed');assert not reconstructed.exists()
result=subprocess.run(['python3',str(a.out/'prepare_sources.py'),'--repo',str(a.repo),'--out',str(reconstructed)],capture_output=True,text=True)
(a.out/'reconstruction.stdout.log').write_text(result.stdout);(a.out/'reconstruction.stderr.log').write_text(result.stderr);assert result.returncode==0,result.stderr
verified={}
for label,manifest in manifests.items():
 for name,expected in manifest['files'].items():
  data=(reconstructed/label/name).read_bytes();assert len(data)==expected['bytes']and sha(data)==expected['sha256'],(label,name)
 verified[label]={'files_matched':len(manifest['files']),'canonical_manifest_sha256':manifest['canonical_manifest_sha256']}
write('source-freeze-receipt.json',{'status':'THREE_SOURCE_RECONSTRUCTION_VERIFIED_NO_BUILD_NATIVE','candidate':a.candidate,'parent_readiness_git':OLD,'verified':verified,'source_spec_sha256':source_sha,'method_plan_sha256':sha((a.out/'method-plan.json').read_bytes()),'marker_contract_sha256':sha((a.out/'marker-contract.json').read_bytes()),'original58_preserved':True,'new_total_methods':62,'production_delta_count':len([k for k in changes if '/src/main/'in k])})
print(json.dumps({'out':str(a.out),'source_spec_sha256':source_sha,'method_plan_sha256':sha((a.out/'method-plan.json').read_bytes()),'analyze_sha256':sha((a.out/'analyze.py').read_bytes()),'verified':verified,'production_delta_count':len([k for k in changes if '/src/main/'in k])},indent=2))
