"""Native kill/offline probes, only on an explicitly identified disposable emulator."""
import argparse,json,pathlib,re,subprocess

def require_disposable(adb,serial,manager=None,index='5',avd_name='InkWeft-Test'):
 if manager:
  info=json.loads(subprocess.check_output([manager,'info','-v',index]));assert info['name']=='InkWeft-Test' and serial==f"{info['adb_host_ip']}:{info['adb_port']}"
 else:
  assert subprocess.check_output(adb+['shell','getprop','ro.kernel.qemu'],text=True).strip()=='1'
  assert subprocess.check_output(adb+['emu','avd','name'],text=True).splitlines()[0]==avd_name

def main():
 p=argparse.ArgumentParser();p.add_argument('--adb',default='adb');p.add_argument('--serial',required=True);p.add_argument('--output',required=True);p.add_argument('--mumu-manager');p.add_argument('--mumu-index',default='5');p.add_argument('--probe',choices=['cipher','offline','resource'],required=True);p.add_argument('--package',default='org.inkweft.app.a0.workspace');a=p.parse_args()
 adb=[a.adb,'-s',a.serial]
 require_disposable(adb,a.serial,a.mumu_manager,a.mumu_index)
 assert a.package in ['org.inkweft.app.a0.workspace','org.inkweft.app.a0.insertion']
 out=pathlib.Path(a.output);out.mkdir(parents=True,exist_ok=False);results=[]
 def invoke(name,flags,log):
  cmd=adb+['shell','am','instrument','-w','-e','class','org.inkweft.app.'+name]
  for k,v in flags.items():cmd+=['-e',k,v]
  r=subprocess.run(cmd+[a.package+'.test/androidx.test.runner.AndroidJUnitRunner'],capture_output=True,timeout=90);data=r.stdout+r.stderr;(out/(log+'.log')).write_bytes(data);return bool(re.search(rb'OK \(1 test\)',data))
 def clear():subprocess.run(adb+['shell','pm','clear',a.package],check=True,stdout=subprocess.DEVNULL)
 if a.probe in ['cipher','resource']:
  cuts=['after-cipher','after-fsync','before-pointer','after-pointer','after-retire'] if a.probe=='cipher' else ['install:'+c for c in ['after-file','before-registry','after-registry']]+['instance:'+c for c in ['after-author','after-document','before-reference','after-commit']]
  for cut in cuts:
   clear();name='BackupCrashProbe' if a.probe=='cipher' else 'ResourceCrashProbe';flag='crashProbe' if a.probe=='cipher' else 'resourceProbe';flags={flag:'dedicated-emulator','cut':cut,'phase':'prepare'};log=cut.replace(':','-')+'-prepare';invoke(name,flags,log)
   assert b'Process crashed.' in (out/(log+'.log')).read_bytes(), 'Selected process termination was not observed'
   flags['phase']='verify';passed=invoke(name,flags,cut.replace(':','-')+'-verify');results.append({'cut':cut,'passed':passed});print(cut,passed,flush=True)
 else:
  clear()
  try:
   for phase in ['prepare','offline','online']:
    subprocess.run(adb+(['reverse','--remove','tcp:18751'] if phase=='offline' else ['reverse','tcp:18751','tcp:18751']),check=True)
    passed=invoke('OfflineBackupProbe',{'offlineProbe':'dedicated-emulator','phase':phase},phase);results.append({'phase':phase,'passed':passed});print(phase,passed,flush=True)
  finally:subprocess.run(adb+['reverse','tcp:18751','tcp:18751'],check=True)
 (out/'summary.json').write_text(json.dumps(results,indent=2));assert all(r['passed'] for r in results)
if __name__=='__main__':main()
