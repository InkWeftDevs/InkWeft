from pathlib import Path
import json,hashlib,math,statistics as st,collections,subprocess
R=Path('/workspace/scratch/pr20-match-execution-20261014');O=R/'search-review';E=R/'extracted';REPO=Path('/workspace/InkWeft')
P=REPO/'android/verification/pr20-match-candidate-20261014'
def load(p):return json.loads(p.read_text())
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def q(v,p):return sorted(v)[math.ceil(len(v)*p)-1]
def dist(v):return {'n':len(v),'p50':q(v,.5),'p95':q(v,.95),'max':max(v)}
def eq(a,b):assert math.isclose(a,b,abs_tol=1e-6,rel_tol=1e-12),(a,b)
binding=load(R/'binding.json');ex=load(E/'analysis.json');existing=load(E/'statistics/analysis.json');plan=load(P/'method-plan.json');spec=load(P/'source-spec.json');protocol=load(P/'original-protocol.json')
for n,k in [('method-plan.json','method_plan_sha256'),('source-spec.json','source_spec_sha256'),('original-protocol.json','original_protocol_sha256')]:
 assert sha(P/n)==binding['input_hashes'][k]
 assert subprocess.check_output(['git','show',binding['readiness_git']+':'+str((P/n).relative_to(REPO))],cwd=REPO)==(P/n).read_bytes()
assert ex['analysis_status']=='COMPLETE_SCOPED_MATCH_LOG_EVIDENCE' and not ex['issues']
assert sha(R/'job-decoded.log')==ex['files'][str(R/'job-decoded.log')]['sha256']
records=collections.defaultdict(list);payloads=0
for line in (R/'job-decoded.log').read_text().splitlines():
 if ' INKWEFT_MATCH_RECORD ' not in line:continue
 x=json.loads(line.split(' INKWEFT_MATCH_RECORD ',1)[1]);b=x['original_json'].encode();assert len(b)==x['payload_bytes'];assert hashlib.sha256(b).hexdigest()==x['payload_sha256'];k=(x['ordinal'],x['identity']);assert x['sequence']==len(records[k]);records[k].append({'marker':x['marker'],'data':json.loads(x['original_json'])});payloads+=1
for e in ex['extracted_records']:
 f=E/e['path'];assert sha(f)==e['sha256'];assert load(f)==records[(e['ordinal'],e['identity'])];assert e['record_count']==len(load(f));assert e['whole_run_complete'] and e['block_closed'] and e['method_pass']
metrics={};libs=[];rasters=[];total_saves=0
for step in plan['steps']:
 if step['phase']!='common-comparison':continue
 ordinal=step['ordinal'];identity=step['identity'];seed=int(step['seed']);rr=records[(ordinal,identity)];by=collections.defaultdict(list)
 for r in rr:by[r['marker']].append(r['data'])
 if 'LIBRARY_READ_EVIDENCE' not in by:
  assert len(by['TILE_LOCAL_EVIDENCE'])==3
  for r in by['TILE_LOCAL_EVIDENCE']:
   for field in ['firstCompleteMs','appendCompleteMs','forcedFullCompleteMs']:metrics[(identity,seed,f"raster.{r['layers']}.{field}")]=r[field]
  rasters.append({'ordinal':ordinal,'identity':identity,'seed':seed,'rows':by['TILE_LOCAL_EVIDENCE']});continue
 assert len(by['LIBRARY_READ_EVIDENCE'])==1;e=by['LIBRARY_READ_EVIDENCE'][0];o=e['observeIndexConcurrentSaves'];di=by['KVM_DIRECT_INTERVALS'][0];ii=by['KVM_INDEX_INTERVALS'][0];fixture=by['KVM_PAIR_FIXTURE'][0]
 assert fixture['seed']==seed;assert fixture['rowCounts']=={'notes':501,'note_revisions':501,'notebook_workspace':501,'notebook_pages':20001,'page_search_text':20000,'library_search_pending':20000}
 assert e['writerSqlite']==fixture['writerSqlite']=={'page_size':1024,'auto_vacuum':1,'journal_mode':'wal','synchronous':2}
 assert e['books']==500 and e['pages']==20000 and e['noteBodyChars']==20020 and e['coldSourceChecks']==20000
 assert o['indexRowsFinal']==20000 and e['fullBodyOnDemandMatches']
 def save_group(raw,durations,revisions):
  assert len(raw)==len(durations)==30
  for i,(s,v,rev) in enumerate(zip(raw,durations,revisions)):
   assert s['sample']==i and s['committed'] is True and s['committedRevision']==rev
   assert s['startNs']<s['endNs'];eq((s['endNs']-s['startNs'])/1e6,v);eq(s['elapsedMs'],v)
   if i:assert raw[i-1]['endNs']<=s['startNs']
  return dist(durations)
 d=save_group(di['saves'],e['saveSamplesMs'],range(2,32));od=save_group(o['saveProgressSamples'],o['saveSamplesMs'],range(33,63));total_saves+=60
 for group,v,reported in [('direct',d,e),('observe',od,o)]:
  for t in ['p50','p95','max']:metrics[(identity,seed,f'library.{group}.{t}')]=v[t]
  # Legacy embedded p50 is upper-middle; keep separate instead of trusting it.
  ds=reported['saveSamplesMs'];eq(reported['saveP50Ms'],sorted(ds)[15]);eq(reported['saveP95Ms'],v['p95']);eq(reported['saveMaxMs'],v['max'])
 assert len(e['warmSamplesMs'])==5;warm=st.median(e['warmSamplesMs']);eq(warm,e['warmMedianMs'])
 bs=ii['batches'];assert len(bs)==313 and bs[0]['rows']==64 and bs[-1]['rows']==20000
 for i,b in enumerate(bs):
  assert b['rows']==min((i+1)*64,20000)
  if i:assert bs[i-1]['committedNs']<b['committedNs']
 for f,v in [('coldMs',e['coldMs']),('allIndexRowsCommittedMs',o['allIndexRowsCommittedMs']),('searchIndexReadyMs',o['searchIndexReadyMs'])]:metrics[(identity,seed,'library.'+f)]=v
 metrics[(identity,seed,'warm')]=warm
 # Batch callback timestamp slightly precedes measured milestone timestamp; preserve both.
 first=(bs[0]['committedNs']-ii['observeStartNs'])/1e6;last=(bs[-1]['committedNs']-ii['observeStartNs'])/1e6
 assert first<=o['firstCommittedBatchMs'] and last<=o['allIndexRowsCommittedMs']<=o['searchIndexReadyMs']
 overlap=[]
 for s in o['saveProgressSamples']:
  assert s['indexRowsEnd']>=s['indexRowsStart'] and s['sourceChecksEnd']>=s['sourceChecksStart']
  ev=[b for b in bs if s['startNs']<=b['committedNs']<=s['endNs']]
  overlap.append({'sample':s['sample'],'elapsedMs':s['elapsedMs'],'rowsAdvanced':s['indexRowsEnd']-s['indexRowsStart'],'checksAdvanced':s['sourceChecksEnd']-s['sourceChecksStart'],'committedCallbacksWithin':len(ev)})
 direct_overlap=sum(s['startNs']<di['coldEndNs'] and s['endNs']>di['coldStartNs'] for s in di['saves'])
 libs.append({'ordinal':ordinal,'identity':identity,'seed':seed,'fixture':fixture,'evidence':e,'direct':d,'observe':od,'directRaw':di['saves'],'observeRaw':o['saveProgressSamples'],'warmRawMs':e['warmSamplesMs'],'warmMedianMs':warm,'firstCallbackMs':first,'lastCallbackMs':last,'firstToFinalRowsPerSecond':19936e3/(last-first),'totalIndexRowsPerSecond':20000e3/o['allIndexRowsCommittedMs'],'directJointMs':(max(di['coldEndNs'],max(s['endNs'] for s in di['saves']))-di['coldStartNs'])/1e6,'observeJointMs':max(o['searchIndexReadyMs'],(o['saveProgressSamples'][-1]['endNs']-ii['observeStartNs'])/1e6),'directSaveWindowsIntersectCold':direct_overlap,'directSavesWithChecksAdvanced':sum(s['checksEnd']>s['checksStart'] for s in di['saves']),'observeOverlap':overlap,'observeSavesWithIndexRowsAdvanced':sum(s['rowsAdvanced']>0 for s in overlap),'observeSavesWithSourceChecksAdvanced':sum(s['checksAdvanced']>0 for s in overlap),'observeSavesWithBothAdvanced':sum(s['rowsAdvanced']>0 and s['checksAdvanced']>0 for s in overlap),'observeSavesWithIndexCallbackWithin':sum(s['committedCallbacksWithin']>0 for s in overlap),'allIndexCallbacks':bs})
assert total_saves==720 and len(libs)==12 and len(rasters)==12
for seed in [4101,4102,4103,4104]:assert len({l['fixture']['logicalSha256'] for l in libs if l['seed']==seed})==1
bands=protocol['proposed_time_regression_bands'];comparisons=[];warms=[]
for left,right in [('B','C0'),('B','C1'),('C0','C1')]:
 for metric in sorted({m[2] for m in metrics if m[2]!='warm'}):
  if metric.startswith('raster.'):band=bands['raster_all_phase_times']
  elif metric.split('.')[-1] in ['p50','p95','max']:band=bands['save_'+metric.split('.')[-1]]
  else:band=bands['search_and_index_completion']
  pairs=[]
  for seed in [4101,4102,4103,4104]:
   a,b=metrics[left,seed,metric],metrics[right,seed,metric];delta=b-a;relative=b/a-1;pairs.append({'seed':seed,'leftMs':a,'rightMs':b,'deltaMs':delta,'relativeChange':relative,'overBand':delta>band['absolute_ms'] and relative>band['relative']})
  md=st.median(p['deltaMs'] for p in pairs);mr=st.median(p['relativeChange'] for p in pairs)
  status='REGRESSION_SIGNAL' if sum(p['deltaMs']>0 for p in pairs)>=3 and md>band['absolute_ms'] and mr>band['relative'] else ('INCONCLUSIVE' if any(p['overBand'] for p in pairs) else 'WITHIN_PROPOSED_BAND')
  c={'comparison':left+'->'+right,'metric':metric,'band':band,'pairs':pairs,'medianDeltaMs':md,'medianRelativeChange':mr,'classification':status};comparisons.append(c)
  old=next(x for x in existing['comparisons'] if x['comparison']==c['comparison'] and x['metric']==metric);assert status==old['screening_status'];eq(md,old['median_delta_ms']);eq(mr,old['median_relative_change'])
  for x,y in zip(pairs,old['pairs']):
   eq(x['leftMs'],y['left_ms']);eq(x['rightMs'],y['right_ms']);assert x['overBand']==y['over_band']
 pairs=[{'seed':s,'leftMs':metrics[left,s,'warm'],'rightMs':metrics[right,s,'warm'],'deltaMs':metrics[right,s,'warm']-metrics[left,s,'warm'],'relativeChange':metrics[right,s,'warm']/metrics[left,s,'warm']-1} for s in [4101,4102,4103,4104]]
 warms.append({'comparison':left+'->'+right,'status':'DESCRIPTIVE_NO_PREDECLARED_BAND','pairs':pairs,'medianDeltaMs':st.median(p['deltaMs'] for p in pairs),'medianRelativeChange':st.median(p['relativeChange'] for p in pairs)})
assert len(comparisons)==54
aggregates=[]
for metric in sorted({x[2] for x in metrics}):
 aggregates.append({'metric':metric,**{i:{'values':[metrics[i,s,metric] for s in [4101,4102,4103,4104]],'medianOfFourSeeds':st.median(metrics[i,s,metric] for s in [4101,4102,4103,4104])} for i in ['B','C0','C1']}})
result={'status':'INDEPENDENT_RAW_RECOMPUTATION_MATCHES_ALL_54_CLASSIFICATIONS','notFinalAcceptance':True,'binding':binding,'officialLog':{'bytes':(R/'job-decoded.log').stat().st_size,'sha256':sha(R/'job-decoded.log')},'counts':{'validatedOriginalJsonPayloads':payloads,'validatedExtractedMethods':len(ex['extracted_records']),'commonCalls':24,'libraryCalls':12,'saveSamples':total_saves,'comparisonCount':len(comparisons)},'quantile':'nearest rank ceil(n*p)-1; n=30: indices14/28/29; legacy upper-middle p50 retained only','sourceSpec':spec,'aggregates':aggregates,'comparisons':comparisons,'warmComparisons':warms,'library':libs,'rasterClassificationInputs':rasters,'receiptMethods':ex['state']['methods'],'limitations':ex['limitations'],'issues':[]}
(O/'search-review.json').write_text(json.dumps(result,indent=2)+'\n')
print('VERIFIED',result['counts']);print('ALL CLASSIFICATIONS',dict(collections.Counter(c['classification'] for c in comparisons)))
for a in aggregates:
 if a['metric'].startswith('library.') or a['metric']=='warm':print(a['metric'],*[i+'='+str(round(a[i]['medianOfFourSeeds'],6)) for i in ['B','C0','C1']])
for c in comparisons:
 if c['metric'].startswith('library.') and c['comparison'] in ['B->C1','C0->C1']:print(c['comparison'],c['metric'],c['classification'],round(c['medianDeltaMs'],6),round(c['medianRelativeChange']*100,3),[p['overBand'] for p in c['pairs']])
print('WARM',warms)
