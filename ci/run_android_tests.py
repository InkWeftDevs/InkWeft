"""Install once and capture evidence from the same instrumentation run, before cleanup."""
import argparse
import json
from pathlib import Path
import re
import subprocess
from android_device import prepare_case
from android_shards import partition
from android_plan import select_methods

parser=argparse.ArgumentParser()
parser.add_argument("--shard-index",type=int,default=0)
parser.add_argument("--shard-count",type=int,default=1)
args=parser.parse_args()
partition([],args.shard_index,args.shard_count)

root=Path(__file__).resolve().parents[1]
plan=json.loads((root/"android-plan.json").read_text())
out=root/"android/build/evidence";out.mkdir(parents=True,exist_ok=True)

def adb(*args): return subprocess.check_output(["adb",*args],text=True,errors="replace",timeout=180)

for module,key,runner in [("data-local","room","org.inkweft.data.test/androidx.test.runner.AndroidJUnitRunner"),
                          ("app","app","org.inkweft.app.a0.insertion.test/androidx.test.runner.AndroidJUnitRunner")]:
    classes=plan[key]
    if not classes or (key=="room" and args.shard_index!=0): continue
    if module=="app": subprocess.run(["adb","install","-r",str(root/"android/app/build/outputs/apk/debug/app-debug.apk")],check=True)
    apk=root/f"android/{module}/build/outputs/apk/androidTest/debug/{module}-debug-androidTest.apk"
    subprocess.run(["adb","install","-r",str(apk)],check=True)
    # Disposable CI emulator only: isolate app methods so changed preferences,
    # font scale or an interrupted prior Activity cannot poison later cases.
    cases=[]
    if key=="app":
        serial=adb("get-serialno").strip()
        if not serial.startswith("emulator-"): raise SystemExit("App isolation requires the disposable CI emulator")
        for name in classes:
            source=next((root/"android/app/src/androidTest").rglob(name.rsplit(".",1)[1]+".kt"))
            methods=re.findall(r"@Test(?:\([^)]*\))?\s+fun\s+(\w+)",source.read_text(encoding="utf-8"))
            if not methods: raise SystemExit(f"Cannot enumerate tests: {name}")
            methods=select_methods(methods,plan.get("app_methods",{}).get(name))
            cases.extend((name+"#"+method,1) for method in methods)
        if len(cases)!=plan["expected_app"]: raise SystemExit("Test inventory mismatch")
    else: cases=[(",".join(classes),plan["expected_room"])]
    if key=="app": cases=partition(cases,args.shard_index,args.shard_count)
    expected_shard=sum(expected for _,expected in cases)
    failures=[];total=0
    infrastructure=[]
    def summary():
        (out/f"{key}-summary.json").write_text(json.dumps({"expected":expected_shard,"planned_total":plan[f"expected_{key}"],"shard_index":args.shard_index,"shard_count":args.shard_count,"passed":total,"failures":failures,"infrastructure":infrastructure},indent=2),encoding="utf-8")
    summary()
    for index,(selection,expected) in enumerate(cases):
        case_dir=out/f"{key}-{index:03d}";case_dir.mkdir(exist_ok=True)
        if key=="app":
            setup=[]
            try:
                prepare_case(serial,"org.inkweft.app.a0.insertion",setup.append)
            except (RuntimeError,subprocess.SubprocessError) as error:
                infrastructure.append({"before":selection,"error":str(error)});summary()
                raise
            finally:
                (case_dir/"setup.txt").write_text("\n".join(setup),encoding="utf-8")
        try:
            result=adb("shell","am","instrument","-w","-e","class",selection,runner)
        except subprocess.TimeoutExpired as error:
            raw=error.stdout or b""
            result=(raw.decode("utf-8",errors="replace") if isinstance(raw,bytes) else raw)+"\nRUNNER_TIMEOUT\n"
            subprocess.run(["adb","shell","am","force-stop",runner.split("/")[0]],check=True)
        (case_dir/"instrumentation.txt").write_text(result,encoding="utf-8")
        (case_dir/"selection.txt").write_text(selection,encoding="utf-8")
        match=re.search(r"^OK \((\d+) tests?\)\s*$",result,re.M)
        passed=bool(match and int(match.group(1))==expected and "FAILURES!!!" not in result)
        print(f"{selection}: {'PASS' if passed else 'FAIL'}",flush=True)
        if passed: total+=expected
        else: failures.append(selection);print(result)
        summary()
        if key=="app": subprocess.run(["adb","pull","/sdcard/Android/data/org.inkweft.app.a0.insertion/files/.",str(case_dir)],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    summary()
    if failures or total!=expected_shard: raise SystemExit(f"{key}: {len(failures)} failing selections; original assertions and logs retained")

(out/"scope.json").write_text(json.dumps(plan,ensure_ascii=False,indent=2),encoding="utf-8")
