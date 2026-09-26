"""Install once and capture evidence from the same instrumentation run, before cleanup."""
import json
from pathlib import Path
import re
import subprocess

root=Path(__file__).resolve().parents[1]
plan=json.loads((root/"android-plan.json").read_text())
out=root/"android/build/evidence";out.mkdir(parents=True,exist_ok=True)

def adb(*args): return subprocess.check_output(["adb",*args],text=True,errors="replace")

for module,key,runner in [("data-local","room","org.inkweft.data.test/androidx.test.runner.AndroidJUnitRunner"),
                          ("app","app","org.inkweft.app.a0.insertion.test/androidx.test.runner.AndroidJUnitRunner")]:
    classes=plan[key]
    if not classes: continue
    if module=="app": subprocess.run(["adb","install","-r",str(root/"android/app/build/outputs/apk/debug/app-debug.apk")],check=True)
    apk=root/f"android/{module}/build/outputs/apk/androidTest/debug/{module}-debug-androidTest.apk"
    subprocess.run(["adb","install","-r",str(apk)],check=True)
    result=adb("shell","am","instrument","-w","-e","class",",".join(classes),runner)
    (out/f"{key}-instrumentation.txt").write_text(result,encoding="utf-8")
    print(result)
    # Test runner exit code alone is not proof: failures can still exit zero.
    match=re.search(r"^OK \((\d+) tests?\)\s*$",result,re.M)
    if not match or int(match.group(1))!=plan[f"expected_{key}"] or "FAILURES!!!" in result:
        raise SystemExit(f"{key}: missing complete successful result, expected {plan[f'expected_{key}']}")
    if key=="app":
        subprocess.run(["adb","pull","/sdcard/Android/data/org.inkweft.app.a0.insertion/files/.",str(out)],check=True)
(out/"scope.json").write_text(json.dumps(plan,ensure_ascii=False,indent=2),encoding="utf-8")
