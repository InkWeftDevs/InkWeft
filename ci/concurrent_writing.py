"""Fixed-APK overlap measurements; clears only an explicitly named disposable emulator app."""
import argparse,hashlib,itertools,json,pathlib,re,subprocess
from device_fault_probe import require_disposable

def main():
 p=argparse.ArgumentParser();p.add_argument('--adb',default='adb');p.add_argument('--serial',required=True);p.add_argument('--apk',required=True);p.add_argument('--output',required=True);p.add_argument('--package',default='org.inkweft.app.a0.workspace');p.add_argument('--mumu-manager');p.add_argument('--mumu-index',default='5');p.add_argument('--rounds',type=int,default=3);a=p.parse_args()
 assert 1<=a.rounds<=5 and a.package in ['org.inkweft.app.a0.workspace','org.inkweft.app.a0.insertion']
 adb=[a.adb,'-s',a.serial];require_disposable(adb,a.serial,a.mumu_manager,a.mumu_index)
 def read(*args):return subprocess.check_output(adb+list(args),text=True).strip()
 digest=hashlib.sha256(pathlib.Path(a.apk).read_bytes()).hexdigest();installed=read('shell','pm','path',a.package).removeprefix('package:');assert read('shell','sha256sum',installed).split()[0]==digest
 out=pathlib.Path(a.output);out.mkdir(parents=True,exist_ok=False);results=[];samples=list(itertools.product(['writing','map','backup','install'],['ink','pencil','natural','pdf']))
 old_reverse='tcp:18754 tcp:18754' in read('reverse','--list');assert old_reverse or 'tcp:18754' not in read('reverse','--list')
 read('reverse','tcp:18754','tcp:18754')
 try:
  for round in range(a.rounds):
   # Rotate order between rounds without dropping any unfavorable sample.
   for condition,content in samples[round:]+samples[:round]:
    name=f'{round+1}-{condition}-{content}';folder=out/name;folder.mkdir()
    read('shell','am','force-stop',a.package);assert read('shell','pm','clear',a.package)=='Success'
    cmd=adb+['shell','am','instrument','-w','-e','class','org.inkweft.app.ConcurrentWritingTest','-e','perfCondition',condition,'-e','perfContent',content,a.package+'.test/androidx.test.runner.AndroidJUnitRunner']
    try:data=subprocess.run(cmd,capture_output=True,timeout=120).stdout
    except subprocess.TimeoutExpired as e:data=(e.stdout or b'')+b'\nRUNNER_TIMEOUT'
    (folder/'instrumentation.log').write_bytes(data);passed=bool(re.search(rb'^OK \(1 test\)\s*$',data,re.M))
    target=folder/'timeline.json'
    pull=subprocess.run(adb+['pull',f'/sdcard/Android/data/{a.package}/files/overlap-{condition}-{content}.json',str(target)],capture_output=True)
    passed=passed and pull.returncode==0
    results.append(dict(round=round+1,condition=condition,content=content,passed=passed,apk_sha256=digest,timeline=str(target.relative_to(out)) if target.exists() else None))
    (out/'summary.json').write_text(json.dumps(results,indent=2));print(name,'PASS' if passed else 'FAIL',flush=True)
 finally:
  if not old_reverse:read('reverse','--remove','tcp:18754')
 assert all(r['passed'] for r in results)

if __name__=='__main__':main()
