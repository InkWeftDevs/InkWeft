import pathlib,json,hashlib,statistics,subprocess,datetime,zipfile
R=pathlib.Path('/workspace/InkWeft');O=pathlib.Path(__file__).parent;E=R/'android/verification/pr20-match-execution-20261014';C=pathlib.Path('/workspace/.setup/gradle/caches/modules-2/files-2.1')
sha=lambda b:hashlib.sha256(b).hexdigest()
raw=(E/'official/job-decoded.log').read_bytes();contexts={};envelopes={};completion_intervals={};last=None
for line in raw.decode('utf-8-sig').splitlines():
 if ' INKWEFT_MATCH_CONTEXT ' in line:
  j=json.loads(line.split(' INKWEFT_MATCH_CONTEXT ',1)[1]);contexts[j['ordinal']]=j
 if ' INKWEFT_MATCH_RECORD ' in line:
  j=json.loads(line.split(' INKWEFT_MATCH_RECORD ',1)[1]);envelopes.setdefault(j['ordinal'],[]).append(j)
 if ' INKWEFT_MATCH_METHOD ' in line:
  j=json.loads(line.split(' INKWEFT_MATCH_METHOD ',1)[1]);now=datetime.datetime.fromisoformat(line.split(' ',1)[0].replace('Z','+00:00'))
  if last is not None:completion_intervals[j['ordinal']]=(now-last).total_seconds()
  last=now
pairs=[];inputs={};payload_verified=0
for seed in range(4101,4105):
 vals={}
 for ident in ['B','C1']:
  ctx=next(c for c in contexts.values() if c['seed']==seed and c['identity']==ident and 'KvmLocalAppendPairProbe' in c['selection'])
  n=ctx['ordinal'];p=next((E/'extracted').glob(f'method-{n:02}-*-records.json'));rows=json.loads(p.read_text());inputs[str(p.relative_to(R))]=sha(p.read_bytes())
  assert sha((json.dumps(rows,ensure_ascii=False,separators=(',',':'))+'\n').encode())==ctx['records_sha256']
  for row,env in zip(rows,envelopes[n]):
   payload=env['original_json'].encode();assert sha(payload)==env['payload_sha256'] and len(payload)==env['payload_bytes'];assert json.loads(payload)==row['data'];payload_verified+=1
  vals[ident]=next(r['data']['forcedFullCompleteMs'] for r in rows if r['marker']=='TILE_LOCAL_EVIDENCE' and r['data']['layers']==8)
 delta=vals['C1']-vals['B'];pairs.append(dict(seed=seed,**vals,delta_ms=delta,relative_change=delta/vals['B'],positive=delta>0,individual_over_band=delta>max(.1*vals['B'],5)))
med=statistics.median(x['delta_ms'] for x in pairs);rel=statistics.median(x['relative_change'] for x in pairs)
assert sum(p['positive'] for p in pairs)==3 and sum(p['individual_over_band'] for p in pairs)==2 and med>5 and rel>.1
rulepaths=['android/verification/pr20-kvm-paired-20261013/evidence/frozen-PLAN.md','android/verification/pr20-match-execution-20261014/reviews/rule-audit/pre-run-original/analyze.py','android/verification/pr20-match-candidate-20261014/analyze.py']
for f in rulepaths:inputs[f]=sha((R/f).read_bytes())
plan=(R/rulepaths[0]).read_bytes();assert plan==subprocess.check_output(['git','show','c37ba03232b1a0985e85cbaec4d349dbab945919:ci/pr20-kvm-paired/PLAN.md'],cwd=R)
old=R/'android/verification/pr20-kvm-paired-20261013/evidence';native_old=json.loads((old/'apk-C.json').read_text())['apks'][0]['mupdf_native'];deps=[]
for d in json.loads((old/'dependencies-C.json').read_text()):
 if not d['path'].startswith('com.artifex.mupdf/'):continue
 p=C/d['path'];assert p.stat().st_size==d['bytes'] and sha(p.read_bytes())==d['sha256'];entry=dict(d,local_path=str(p),matches_archived_dependency=True)
 if p.suffix=='.aar':
  with zipfile.ZipFile(p) as z:
   entry['native']=[]
   for n in native_old:
    path='jni/'+n['path'][4:];b=z.read(path);assert b[:4]==b'\x7fELF' and len(b)==n['bytes'] and sha(b)==n['sha256'];entry['native'].append(dict(n,aar_path=path,matches_archived_apk=True))
 deps.append(entry)
contractpath=R/'android/verification/pr20-match-candidate-20261014/marker-contract.json';contract=json.loads(contractpath.read_text());methods=[]
for old_n in [60,61,62,None,26,28]:
 src=contract['methods'][str(old_n or 62)];v=json.loads(json.dumps(src));v['source_manifest_pending']=True;v['historical_ordinal']=old_n;v['runs']=1;v['seed']=None;v['expected_junit_methods']=1
 if old_n is None:v['selection']='org.inkweft.app.StreamedInkRasterTest#completeFrameCrossesBothTheEditorPointWindowAndDatabaseBatchBoundary';v['case']='bounded-two-batch';v['fixtureStrokes']=258;v['fixturePoints']=8226
 elif old_n==62:v['case']='original-dense';v['fixtureStrokes']=1201;v['fixturePoints']=76802
 v['test_source_sha256']=sha((R/v['test_source_path']).read_bytes());v['args']=['adb','-s','emulator-5554','shell','am','instrument','-w','-r','-e','class',v['selection'],'org.inkweft.app.a0.test/androidx.test.runner.AndroidJUnitRunner'];v['prior_adjacent_completion_interval_seconds']=completion_intervals.get(old_n)
 methods.append(v)
result=dict(scope='READ_ONLY_PROPOSAL_NOT_NATIVE_EXECUTION',read_head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=R,text=True).strip(),e310_commit='e31063c4cd5ddf70b456e83697e539482849361a',owned_job_methods=methods,method_count=6,successful_record_count=32,successful_original_json_max_bytes=sum(v['method_payload_max_bytes'] for v in methods),old_marker_contract_sha256=sha(contractpath.read_bytes()),schema_source=str(contractpath.relative_to(R)),schema_note='Bounded-two-batch requires a distinct STREAMED_FRAME_STAGE schema/case/fixture constants; original-dense schema is not silently widened.',historical_timing_note='Adjacent METHOD completion receipt difference includes method and small controller overhead; not exact instrumentation duration or native execution of e310.',mupdf=deps,official_coordinate='com.artifex.mupdf:fitz:1.28.5',declared_repository='https://maven.ghostscript.com',rule_review=dict(pairs=pairs,positive_pairs=3,individual_over_band_pairs=2,paired_median_delta_ms=med,paired_median_relative=rel,frozen_status='REGRESSION_SIGNAL',aggregate_original_counts='45/5/4',original_envelope_payloads_verified=payload_verified,original_plan_matches_pre_run_git=True),input_hashes=inputs,official_log_sha256=sha(raw),network_used=False,production_changed=False,ci_started=False)
(O/'scope-evidence.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'methods':6,'records':32,'successful_original_json_max_bytes':result['successful_original_json_max_bytes'],'source_head':result['read_head'],'verified_full_payloads':payload_verified,'paired_median_delta_ms':med,'paired_median_relative':rel},indent=2))
