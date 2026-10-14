"""Freeze a once-only server receipt into a NEW control copy. Never fetches or activates."""
import argparse,datetime,hashlib,json,re,shutil
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--template',type=Path,required=True);p.add_argument('--receipt',type=Path,required=True);p.add_argument('--out',type=Path,required=True);p.add_argument('--expected-pr-head',required=True);a=p.parse_args()
assert a.out.resolve().is_relative_to('/workspace/scratch') and not a.out.exists()
auth=json.loads((a.template/'authorization.json').read_text())
contract=json.loads((a.template/'clock-contract.json').read_text())
budget=json.loads((a.template/'budget-revision.json').read_text())
assert auth['approved'] is False and re.fullmatch('[0-9a-f]{40}',auth['plan_git'])
assert auth['clock_status']==contract['status']=='__CLOCK_STATUS__'
assert auth['budget_anchor_epoch']==contract['budget_anchor_epoch']==budget['clock_amendment']['budget_anchor_epoch']=='__CLOCK_ANCHOR_EPOCH__'
assert contract['updated_at']==json.loads((a.template/'clock-server-receipt.json').read_text())['updated_at']=='__CLOCK_UPDATED_AT__'
assert auth['phase_cutoff_minutes']==contract['phase_cutoff_minutes']=={'setup':38,'correctness':41,'common':53,'diagnostics':55}
assert contract['job_timeout_minutes']==60 and contract['work_deadline_minutes']==55
assert budget['new_cutoffs_minutes']==[38,41,53,55,60]
assert auth['source_method_amendment']['C1']=='cb7e14218ac83aa8cf6b9d7906051a10da1a6547'
assert auth['method_plan_sha256']==budget['phase_budget_amendment']['method_plan_sha256']=='6660f4f25061440892ad8ff0b7257e09dd311b2df8786e258c873dd21fe15913'
assert "anchor_literal='__CLOCK_ANCHOR_EPOCH__'" in (a.template/'workflow.proposed.yml').read_text()
r=json.loads(a.receipt.read_text());assert set(r)=={'http_status','id','number','node_id','draft','updated_at','head_sha'}
assert r['http_status']==200 and r['id']==4835003832 and r['number']==20 and r['node_id']=='PR_kwDOUnRr3M8AAAABIDBNuA' and r['draft'] is True
assert r['head_sha']==a.expected_pr_head and re.fullmatch('[0-9a-f]{40}',r['head_sha'])
epoch=int(datetime.datetime.strptime(r['updated_at'],'%Y-%m-%dT%H:%M:%SZ').replace(tzinfo=datetime.timezone.utc).timestamp())
assert epoch<=datetime.datetime.now(datetime.timezone.utc).timestamp()<epoch+38*60,'Future/expired: never rebase'
shutil.copytree(a.template,a.out,ignore=shutil.ignore_patterns('__pycache__','*.pyc'))
def write(n,v):(a.out/n).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n')
def sha(n):return hashlib.sha256((a.out/n).read_bytes()).hexdigest()
write('clock-server-receipt.json',r)
values={'__CLOCK_STATUS__':'FROZEN','__CLOCK_BUDGET_STATUS__':'CONTINUE_APPROVED_BUDGET3_MATCH62_FROZEN_NOT_PERFORMANCE_EVIDENCE','__CLOCK_ANCHOR_EPOCH__':epoch,'__CLOCK_UPDATED_AT__':r['updated_at'],'__CLOCK_RECEIPT_HEAD_SHA__':r['head_sha'],'__CLOCK_RECEIPT_SHA256__':sha('clock-server-receipt.json')}
def replace(v):
 if isinstance(v,dict):return {k:replace(x) for k,x in v.items()}
 if isinstance(v,list):return [replace(x) for x in v]
 return values.get(v,v) if isinstance(v,str) else v
for name,key in [('clock-contract.json','__CLOCK_CONTRACT_SHA256__'),('budget-revision.json','__CLOCK_BUDGET_SHA256__'),('authorization.json',None)]:
 d=replace(json.loads((a.out/name).read_text()))
 if name=='authorization.json':
  assert d['approved'] is False and re.fullmatch('[0-9a-f]{40}',d['plan_git'])
  d['approved']=True
  d['scope']='One separately frozen cb7e142 matching candidate;62calls=31original correctness+24unchanged common+3unchanged diagnostics+Unicode+3existing raster correctness;14groups9APKs;38/41/53/55fromimmutablePRanchor,job60;single standard runner/worker/no-build-cache;originalhelper17/ACL/doublecleanup/20MiB3days unchanged.'
  d['continuation']='Root proceeds under existing user authorization relayed in this task: diagnose remaining p50/warm costs, choose evidence-supported minimal fixes, then original full acceptance while preserving raster/GC missing coverage. No new deadline, retries, permissions, sample or threshold changes. One attempt only; root owns activation after independent review.'
 assert '__CLOCK_' not in json.dumps(d);write(name,d)
 if key:values[key]=sha(name)
p=a.out/'workflow.proposed.yml';t=p.read_text()
for k,v in values.items():t=t.replace(k,str(v))
p.write_text(t)
for p in a.out.iterdir():
 if p.is_file():assert '__CLOCK_' not in p.read_text(),p
print(json.dumps({'status':'FROZEN_CLOCK_NOT_ACTIVATED','anchor_utc':r['updated_at'],'epoch':epoch,'hashes':{p.name:sha(p.name) for p in a.out.iterdir() if p.is_file()}},indent=2))
