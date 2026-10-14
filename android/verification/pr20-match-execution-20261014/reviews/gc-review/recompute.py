#!/usr/bin/env python3
"""Read only this run's original compact log/records; write this review JSON only."""
import collections,hashlib,json,math
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
OUT=Path(__file__).resolve().parent
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
compact=lambda x:json.dumps(x,ensure_ascii=False,separators=(',',':'),allow_nan=False).encode()
load=lambda p:json.loads(p.read_bytes())
a=load(ROOT/'extracted/analysis.json');assert a['analysis_status']=='COMPLETE_SCOPED_MATCH_LOG_EVIDENCE' and a['issues']==[]
binding=load(ROOT/'binding.json');acq=load(ROOT/'official-acquisition.json')
assert sha(ROOT/'job-decoded.log')==acq['original_sha256']==acq['saved_sha256']
raw={n:collections.defaultdict(list) for n in (56,57)}
for line in (ROOT/'job-decoded.log').read_text(encoding='utf-8-sig').splitlines():
 parts=line.split(' ',2)
 if len(parts)!=3 or not parts[1].startswith('INKWEFT_MATCH_'):continue
 v=json.loads(parts[2]);n=v.get('ordinal')
 if n in raw:raw[n][parts[1].removeprefix('INKWEFT_MATCH_')].append(v)
res={'status':'RECORDS_CONSISTENT_INSUFFICIENT_EXPOSURE_NOT_ACCEPTANCE','scope':'ONLY_THIS_RUN_C1_NATURAL_GC_METHODS_56_57','run':a['expected'],'official_job_span_seconds':a['official_job_span_seconds'],'input_files':{},'groups':[],'issues':[], 'statistical_limits':{'minimum_exposed_samples_for_quantiles':20,'competition_quantiles_evaluated':False,'all_save_summary_numbers':'Recomputed source summary only; descriptive of all30 calls, not delete competition.','no_pooling':True,'no_baseline_candidate_competition_conclusion':True,'pure_sqlite_lock_wait_ms':None}}
for p in [ROOT/'job-decoded.log',ROOT/'binding.json',ROOT/'run-terminal.json',ROOT/'jobs-terminal.json',ROOT/'official-acquisition.json',ROOT/'extracted/analysis.json']:
 res['input_files'][str(p.relative_to(ROOT))]={'sha256':sha(p),'bytes':p.stat().st_size}
for n,mode in [(56,'same-room'),(57,'separate-room')]:
 p=ROOT/f'extracted/method-{n}-C1-records.json';rows=load(p);assert len(rows)==31
 ledger=next(e for e in a['extracted_records']if e['ordinal']==n);block=next(b for b in a['state']['blocks']if b['context']['ordinal']==n);context=block['context']
 assert block['ended']and block['method_pass']and ledger['whole_run_complete']
 assert len(raw[n]['CONTEXT'])==len(raw[n]['END'])==len(raw[n]['METHOD'])==1
 assert context==raw[n]['CONTEXT'][0]and raw[n]['METHOD'][0]['status']=='PASS'
 ordered=[];decoded=[]
 for seq,r in enumerate(raw[n]['RECORD']):
  assert r['sequence']==seq and r['identity']=='C1'
  encoded=r['original_json'].encode();assert r['payload_bytes']==len(encoded) and r['payload_sha256']==hashlib.sha256(encoded).hexdigest()
  decoded.append({'marker':r['marker'],'data':json.loads(r['original_json'])})
  ordered.append({k:r[k]for k in ['sequence','marker','payload_bytes','payload_sha256']})
 end=raw[n]['END'][0];assert end['count']==31 and end['ordered_payloads']==ordered and end['ordered_payloads_sha256']==hashlib.sha256(compact(ordered)).hexdigest()
 assert decoded==rows and compact(rows)+b'\n'==p.read_bytes()
 assert sha(p)==context['records_sha256']==ledger['sha256']
 res['input_files'][str(p.relative_to(ROOT))]={'sha256':sha(p),'bytes':p.stat().st_size}
 assert all(x['marker']=='HISTORY_DELETION_EXPOSURE_TRIAL'for x in rows[:-1]);assert rows[-1]['marker']=='HISTORY_DELETION_EXPOSURE_SUMMARY'
 trials=[x['data']for x in rows[:-1]];summary=rows[-1]['data'];assert [t['sample']for t in trials]==list(range(30))
 exposed=[];costs=[];details=[]
 for t in trials:
  assert t['connection']==mode and t['status']=='PASS'and t['stage']=='complete'
  assert t['saveOutcome']=='COMMITTED'and t['committed'] is True and t['contentVerified']is True
  assert t['savedRevision']==t['finalRevision']==t['initialRevision']+1==3 and t['retainedRevisions']==[1281]
  assert t['failureClass'] is None and t['collectorFailureClass']is None and t['suppressedFailureClasses']==[]
  assert t['artificialGates']is False and t['fixtureSourceVersions']==1281 and t['fixtureDetachedSources']==1280 and t['fixtureExplicitRoots']==0
  assert t['writerSqlite']==t['authorWriterSqlite']=={'page_size':1024,'auto_vacuum':1,'journal_mode':'wal','synchronous':2}
  start,end_ns=t['saveStartNs'],t['saveEndNs'];assert start<=t['beforeRenameReceiptNs']<=end_ns
  assert t['saveMs']==(end_ns-start)/1e6
  phases=t['phases'];assert [x['atNs']for x in phases]==sorted(x['atNs']for x in phases)
  assert all(t['collectorStartNs']<=x['atNs']<=t['collectorEndNs']for x in phases)
  assert t['collectorEndNs']<=t['finishStartNs'] and end_ns<=t['finishStartNs']<=t['finishEndNs']
  assert t['firstRootScans']==sum(x['phase']=='after-roots'for x in phases)==1 and t['finishRootScans']==1
  commits=[x['atNs']for x in phases if x['phase']=='before-batch-commit'];afters=[x['atNs']for x in phases if x['phase']=='after-batch']
  assert len(commits)==len(afters)==t['firstCommittedBatches'] and t['firstDeleted']==256*len(commits)
  assert t['firstDeleted']+t['finishDeleted']==1280
  assert [x['phase']for x in phases]==['after-roots','after-stats','after-scan']+['before-batch-commit','after-batch']*len(commits)
  covered=[x for x in commits if start<=x<=end_ns];assert covered==t['coveredBeforeBatchCommitNs'] and bool(covered)==t['deletionTransactionExposed']
  if covered:exposed.append(t)
  first_ns=t['collectorEndNs']-t['collectorStartNs'];finish_ns=t['finishEndNs']-t['finishStartNs'];costs.append((first_ns,finish_ns))
  brackets=[{'batch':i+1,'lower_ns':afters[i-1],'upper_ns':commits[i],'contains_full_delete_loop_by_source_order':True}for i in range(1,len(commits))if start<=afters[i-1]<commits[i]<=end_ns]
  if commits:details.append({'sample':t['sample'],'save_ms':t['saveMs'],'save_start_ns':start,'save_end_ns':end_ns,'precommit_points_during_call':covered,'first_deleted':t['firstDeleted'],'first_deferred':t['firstDeferred'],'committed_batches':len(commits),'bracketed_full_delete_loops_during_call':brackets,'exact_delete_interval_observed':False,'relative_to_save_start_ms':{'first_before_commit':(commits[0]-start)/1e6,'last_before_commit':(commits[-1]-start)/1e6,'last_after_batch':(afters[-1]-start)/1e6,'collector_end':(t['collectorEndNs']-start)/1e6,'before_rename_receipt':(t['beforeRenameReceiptNs']-start)/1e6,'save_end':t['saveMs']}})
 samples=[t['saveMs']for t in trials];sorted_s=sorted(samples)
 source_all=summary['allSaves'];assert source_all['count']==30 and source_all['sampleIndices']==list(range(30))and source_all['saveSamplesMs']==samples
 assert source_all['p50Ms']==sorted_s[14]and source_all['p95Ms']==sorted_s[28]and source_all['maxMs']==sorted_s[29]
 e=summary['exposedSaves'];assert e['count']==len(exposed)and e['sampleIndices']==[t['sample']for t in exposed]and e['saveSamplesMs']==[t['saveMs']for t in exposed]
 assert summary['minimumExposedSamplesForQuantiles']==20 and len(exposed)<20 and summary['exposureStatus']=='INSUFFICIENT_EXPOSURE'
 assert e['p50Ms']is None and e['p95Ms']is None and e['maxMs']==max([t['saveMs']for t in exposed],default=None)
 assert summary['functionalStatus']=='PASS'and summary['committedSaves']==summary['passedTrials']==summary['recordedTrials']==summary['plannedTrials']==30
 assert summary['latencyAcceptance']=='NOT_EVALUATED'and summary['watchdogIsLatencyAcceptance']is False
 g={'ordinal':n,'connection':mode,'trials':30,'passed':30,'committed':30,'content_verified':30,'failed':0,'commit_unknown':0,'test_source_sha256':context['test_source_sha256'],'context':context,'exposure':{'precommit_point_trials':len(exposed),'actual_delete_exact_intervals_measured':False,'source_order_proven_delete_during_pending_call_trials':sum(bool(d['bracketed_full_delete_loops_during_call'])for d in details),'source_order_proven_complete_delete_loops_during_call':sum(len(d['bracketed_full_delete_loops_during_call'])for d in details),'threshold':20,'status':summary['exposureStatus'],'samples_original_order':e['saveSamplesMs'],'indices':e['sampleIndices'],'p50_ms':None,'p95_ms':None,'max_ms':e['maxMs']},'first':{'root_scans':sum(t['firstRootScans']for t in trials),'deleted':sum(t['firstDeleted']for t in trials),'committed_batches':sum(t['firstCommittedBatches']for t in trials),'deferred_count':sum(t['firstDeferred']for t in trials),'elapsed_total_ms':sum(x[0]for x in costs)/1e6},'finish':{'root_scans':sum(t['finishRootScans']for t in trials),'deleted':sum(t['finishDeleted']for t in trials),'elapsed_total_ms':sum(x[1]for x in costs)/1e6},'total':{'root_scans':sum(t['firstRootScans']+t['finishRootScans']for t in trials),'deleted':sum(t['firstDeleted']+t['finishDeleted']for t in trials),'elapsed_total_ms':sum(sum(x)for x in costs)/1e6},'per_trial_gc_cost_ms':[{'sample':i,'first_ms':f/1e6,'finish_ms':z/1e6,'sum_ms':(f+z)/1e6}for i,(f,z)in enumerate(costs)],'all_saves_original_order_ms':samples,'all_saves_summary_verified_not_competition':source_all,'first_deleted_original_order':[t['firstDeleted']for t in trials],'first_deferred_original_order':[t['firstDeferred']for t in trials],'deletion_details':details,'all_trials_original':trials}
 res['groups'].append(g)
manifest_path=Path('/workspace/scratch/pr20-match-candidate-20261014/preparation62/frozen-readiness/C1-source-manifest.json')
manifest=load(manifest_path);res['source_bindings']={'manifest_path':str(manifest_path),'manifest_sha256':sha(manifest_path),'files':{}}
for relative in ['android/data-local/src/main/kotlin/org/inkweft/data/HistoryStorage.kt','android/data-local/src/androidTest/kotlin/org/inkweft/data/HistoryDeletionExposureTest.kt']:
 p=Path('/workspace/InkWeft')/relative;actual=sha(p);assert actual==manifest['files'][relative]['sha256'];res['source_bindings']['files'][relative]=actual
assert res['source_bindings']['files']['android/data-local/src/androidTest/kotlin/org/inkweft/data/HistoryDeletionExposureTest.kt']==res['groups'][0]['test_source_sha256']==res['groups'][1]['test_source_sha256']
res['historical_context_not_pooled']={'pure_gc':'Prior final-paired-history-review:17->1 roots; different fixture/environment; not rerun here.','conditional_gc':'Prior causal run38514139119 methods11/12:30/30 each; different arrival trigger/probe/source/run; not a baseline comparison.'}
(OUT/'gc-review.json').write_text(json.dumps(res,ensure_ascii=False,indent=2,allow_nan=False)+'\n')
print(json.dumps({'status':res['status'],'groups':[{k:g[k]for k in ['ordinal','connection','exposure','first','finish','total','deletion_details']}for g in res['groups']]},ensure_ascii=False,indent=2))
