"""Offline scratch checks only: real source receipts and synthetic package failure paths."""
import argparse,hashlib,json,os,re,shutil,subprocess,sys
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True);a=p.parse_args();assert not a.out.exists() and a.out.resolve().is_relative_to('/workspace/scratch');a.out.mkdir()
root=Path('/workspace/scratch/pr20-raster-screen-ci-20261014');h=root/'controller/ci/pr20-raster-screen';frozen=root/'source-preview';sha=lambda b:hashlib.sha256(b).hexdigest();checks=[]
def check(name,detail):checks.append(dict(name=name,status='PASS',detail=detail))
spec=json.loads((frozen/'source-spec.json').read_text());counts={}
for identity,item in spec['identities'].items():
 assert item['production_overrides']==[]
 expected=json.loads((frozen/(identity+'-source-manifest.json')).read_text())
 actual={p.relative_to(root/'reconstructed-preview'/identity).as_posix():{'bytes':p.stat().st_size,'sha256':sha(p.read_bytes())} for prefix in ['android','ci'] for p in (root/'reconstructed-preview'/identity/prefix).rglob('*') if p.is_file()}
 assert actual==expected['files'];counts[identity]=len(actual)
 assert sha(json.dumps(actual,sort_keys=True,separators=(',',':')).encode())==item['canonical_manifest_sha256']
check('real source reconstruction binds all files',counts)
# These source guards must fail before materializing any source output.
for mode in ['same-source','override']:
 copy=a.out/mode;shutil.copytree(frozen,copy);s=json.loads((copy/'source-spec.json').read_text())
 if mode=='same-source':s['identities']['C1']=s['identities']['C2']
 else:s['identities']['C1']['production_overrides']=[{'path':spec['only_production_delta']}]
 (copy/'source-spec.json').write_text(json.dumps(s));out=copy/'sources';q=subprocess.run([sys.executable,copy/'prepare_sources.py','--repo','/workspace/InkWeft','--out',out],capture_output=True,text=True)
 assert q.returncode and not out.exists();check('source refusal '+mode,{'exit':q.returncode})
# Binding cannot silently use readiness files from an unrelated committed tree.
q=subprocess.run([sys.executable,h/'bind_control.py','--repo','/workspace/InkWeft','--plan-git',spec['identities']['C2']['git'],'--readiness',frozen,'--out',a.out/'bound'],capture_output=True,text=True)
assert q.returncode and not (a.out/'bound').exists();check('uncommitted readiness binder refusal',{'exit':q.returncode})
layout=a.out/'package-layout';target=layout/'ci/pr20-raster-screen';target.mkdir(parents=True)
for name in ['run.py','markers.py','package.py','cleanup.py','failure_text.py','size_bounds.py','workflow.proposed.yml','authorization.json']:
 data=(h/name).read_text()
 if name in ['run.py','package.py']:data=data.replace("'UNBOUND'",repr(sha((frozen/'source-spec.json').read_bytes())))
 (target/name).write_text(data)
(target/'EXECUTION_SHA256SUMS').write_text('SYNTHETIC NOT EXECUTED\n');shutil.copytree(frozen,layout/'android/verification/pr20-raster-screen-20261014')
summary={'run_id':'SYNTHETIC','workflow_sha':'0'*40,'input_hashes':{},'status':'EXECUTION_COMPLETE_ANALYSIS_PENDING','actions':{},'phases':[],'install_groups':{},'methods':[]}
def execute(case,files=None):
 home=a.out/('package-'+case);e=home/'evidence';e.mkdir(parents=True)
 (e/'summary.json').write_text(json.dumps(summary));
 for tag in ['trap','always']:(e/f'cleanup-{tag}.json').write_text(json.dumps(dict(errors=[],emulator='NOT_STARTED',command_groups='NOT_STARTED',acl='NOT_TOUCHED')))
 for name,data in (files or {}).items():(e/name).write_bytes(data)
 env=os.environ.copy();env['KVM_ONCE']=str(home);q=subprocess.run([sys.executable,target/'package.py'],env=env,capture_output=True,text=True);(home/'stdout.txt').write_text(q.stdout);(home/'stderr.txt').write_text(q.stderr)
 return q,home
q,home=execute('missing');assert q.returncode==1
r=json.loads((home/'upload/summary.json').read_text());pack=json.loads((home/'upload/packaging.json').read_text());assert r['status']=='EVIDENCE_INCOMPLETE' and pack['complete'] is False and pack['truncated_files']==0
missing=pack['missing_files'];check('missing evidence cannot claim completion',{'missing':len(missing),'status':r['status']})
files={name:b'{}\n' for name in missing}
q,home=execute('complete',files);assert q.returncode==0,q.stderr
pack=json.loads((home/'upload/packaging.json').read_text());assert pack['complete'] and pack['truncated_files']==0
receipt=json.loads(q.stdout.splitlines()[0]);assert receipt['joint_bytes_upper']<=20*1024*1024 and receipt['shared_bytes']<=3*1024*1024
for item in json.loads((home/'upload/manifest.json').read_text()):assert item['original_sha256']==item['uploaded_sha256']==sha((home/'upload'/item['name']).read_bytes())
check('complete synthetic package retains every byte and enforces joint cap',receipt)
# Failures must not be mislabeled as completion or silently shortened.
for case,extra in [('oversize',{'method-12-C1-instrumentation.log':b'x'*(20*1024*1024)}),('shared-oversize',{'build-C2.log':b'x'*(3*1024*1024)}),('secret',{'build-C2.log':('gh'+'p_'+'x'*24).encode()}),('invalid-utf8',{'build-C2.log':b'\xff'})]:
 q,home=execute(case,dict(files,**extra));assert q.returncode!=0
 if 'oversize'in case:
  r=json.loads((home/'upload/packaging-failure.json').read_text());assert r['status']=='EVIDENCE_OVERSIZE_NOT_UPLOADED' and len(list((home/'upload').iterdir()))==1
 elif case=='secret':
  assert not (home/'upload/build-C2.log').exists();assert not json.loads((home/'upload/packaging.json').read_text())['complete']
 check('package refusal '+case,{'exit':q.returncode,'original_bytes':{n:(home/'evidence'/n).stat().st_size for n in extra}})
# A path-based shared-byte escape must also fail.
q,home=execute('symlink-base',files);shutil.rmtree(home/'upload');(home/'evidence/build-C2.log').unlink();(home/'target.txt').write_text('synthetic');(home/'evidence/build-C2.log').symlink_to(home/'target.txt')
env=os.environ.copy();env['KVM_ONCE']=str(home);q=subprocess.run([sys.executable,target/'package.py'],env=env,capture_output=True,text=True);assert q.returncode!=0;check('package symlink refusal',{'exit':q.returncode})
(a.out/'checks.json').write_text(json.dumps({'status':'OFFLINE_SOURCE_AND_SYNTHETIC_PACKAGE_PASS_NOT_NATIVE','checks':checks,'frozen_sha256':{p.name:sha(p.read_bytes())for p in frozen.iterdir()if p.is_file()},'controller_sha256':{p.name:sha(p.read_bytes())for p in h.iterdir()if p.is_file()}},indent=2)+'\n')
print(json.dumps({'checks':len(checks),'status':'OFFLINE_SOURCE_AND_SYNTHETIC_PACKAGE_PASS_NOT_NATIVE'}))
