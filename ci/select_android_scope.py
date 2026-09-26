"""Use last push only after that exact prior head passed this workflow; otherwise PR baseline."""
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.request
from android_plan import plan

event=json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text())
head=event.get("pull_request",{}).get("head",{}).get("sha",os.environ["GITHUB_SHA"])
base=event.get("pull_request",{}).get("base",{}).get("sha") or event.get("before")
full=os.environ["GITHUB_EVENT_NAME"]=="workflow_dispatch" and event.get("inputs",{}).get("mode","full")=="full"
reason="PR base / push range"
before=event.get("before","")
if os.environ["GITHUB_EVENT_NAME"]=="pull_request" and event.get("action")=="synchronize" and re.fullmatch(r"[0-9a-f]{40}",before):
    try:
        subprocess.run(["git","merge-base","--is-ancestor",before,head],check=True,capture_output=True)
        url=f"https://api.github.com/repos/{os.environ['GITHUB_REPOSITORY']}/actions/workflows/android-a0.yml/runs?head_sha={before}&status=success&per_page=5"
        request=urllib.request.Request(url,headers={"Authorization":"Bearer "+os.environ["GH_TOKEN"],"Accept":"application/vnd.github+json"})
        with urllib.request.urlopen(request,timeout=8) as response: runs=json.load(response)["workflow_runs"]
        if any(r["head_sha"]==before and r["conclusion"]=="success" for r in runs):
            base=before;reason="incremental from verified previous PR head"
    except Exception:
        reason="previous head not verified; conservative PR base"
if not base or not re.fullmatch(r"[0-9a-f]{40}",base) or set(base)=={"0"}:
    full=True;paths=[]
else:
    paths=subprocess.check_output(["git","diff","--name-only","--no-renames",base,head],text=True).splitlines()
if os.environ["GITHUB_EVENT_NAME"]=="push" and plan(paths)["build"]:
    full=True  # Main receives a complete current-version integration check.
result=plan(paths,full)
result.update(base=base,head=head,selection_reason=reason)
Path("android-plan.json").write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding="utf-8")
with open(os.environ["GITHUB_OUTPUT"],"a") as out:
    for key in ["build","device"]:out.write(f"{key}={str(result[key]).lower()}\n")
with open(os.environ["GITHUB_STEP_SUMMARY"],"a") as out:
    out.write("## 本次验证范围\n\n```json\n"+json.dumps(result,ensure_ascii=False,indent=2)+"\n```\n")
print(json.dumps(result,ensure_ascii=False))
