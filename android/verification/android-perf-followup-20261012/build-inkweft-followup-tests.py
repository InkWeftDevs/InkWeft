import datetime,json,pathlib,subprocess,time
root=pathlib.Path('/workspace/scratch/android-perf-evidence')
items=[('baseline-followup','/workspace/scratch/inkweft-baseline-a128bb07/android'),('candidate-followup','/workspace/InkWeft/android')]
for phase, directory in items:
    command=['bash','/workspace/scratch/run-inkweft-gradle',':data-local:assembleDebugAndroidTest',':app:assembleDebugAndroidTest',':app:lintDebug']
    before=time.monotonic(); started=datetime.datetime.now(datetime.timezone.utc).isoformat()
    print('START '+phase+' test APK build',flush=True)
    with (root/(phase+'-build.log')).open('w') as log:
        run=subprocess.run(command,cwd=directory,stdout=log,stderr=subprocess.STDOUT,timeout=1800)
    receipt={'command':command,'cwd':directory,'started_utc':started,'seconds':time.monotonic()-before,'exit_code':run.returncode,'scope':'Only follow-up test APKs and lint. Production source unchanged; prior JVM results are not re-executed by this command.'}
    (root/(phase+'-build.json')).write_text(json.dumps(receipt,indent=2)+'\n')
    print(json.dumps(receipt),flush=True)
    if run.returncode: raise SystemExit(run.returncode)
    subprocess.run(['python3','/workspace/scratch/record-inkweft-build.py',phase,directory],check=True)
    p=root/(phase+'-jvm-and-lint-status.json'); data=json.loads(p.read_text()); data['jvm_result_origin']='Previous fixture-final JVM run; not re-run in this follow-up test-APK build';p.write_text(json.dumps(data,indent=2)+'\n')
    old=json.loads((root/(phase.split('-')[0]+'-fixture-final-artifacts.json')).read_text())
    new=json.loads((root/(phase+'-artifacts.json')).read_text())
    assert old[0]['sha256']==new[0]['sha256'],'Measured production APK changed unexpectedly'
print('COMPLETE both matched follow-up test builds; production APK hashes unchanged',flush=True)
