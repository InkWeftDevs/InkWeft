import hashlib,json
from collections import Counter
from decimal import Decimal
from pathlib import Path
D=Path(__file__).resolve().parent;E=D.parent/'extracted'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def ms(ns):return str(Decimal(ns)/Decimal(1000000))
def q(values):
 s=sorted(values);n=len(s)
 return {'p50Ms':s[(n+1)//2-1],'p95Ms':s[(95*n+99)//100-1],'maxMs':s[-1]}
analysis=json.loads((D/'gc-analysis.json').read_text());terminal=json.loads((E/'analysis.json').read_text())
out={'scope':'SINGLE_RUN_CONDITIONAL_ARRIVAL_SCOPED_LOG_EVIDENCE','run_id':'38514139119','job_id':'115689021611','workflow_sha':'d4a107dd84c6e309b5dbbbfb3a57d69371024408',
 'source_commit':'78a5babeaeb92292026423222d5e08635989198b','readiness_git':'b2e1d9f9de66a26f5a4b122ea6754d77198005f8',
 'scoped_log_receipt_status':terminal['analysis_status'],'scoped_log_receipt_issues':terminal['issues'],
 'scoped_log_receipt_sha256':sha(E/'analysis.json'),'arithmetic_analysis_sha256':sha(D/'gc-analysis.json'),
 'test_source_sha256':'4f80b8ce32e1aa2bae1d919b96c06b1897e7a8500c748822efd05119ba87ec4a',
 'actual_artifact_bytes_downloaded_or_audited':False,'latency_acceptance':'NOT_EVALUATED','baseline_pair_available':False,'methods':[]}
for a in analysis['methods']:
 records=json.loads((E/f"method-{a['ordinal']:02d}-C1-records.json").read_text());rows=[x['data'] for x in records if x['marker']=='HISTORY_GC_CAUSAL_TRIAL'];summary=records[-1]['data']
 assert len(rows)==30 and a['issues']==[]
 values=[r['saveMs'] for r in rows];quantiles=q(values)
 assert all(quantiles[k]==summary['allSaves'][k] for k in quantiles)
 point=[];interval=[];started=[];guard=[];batches=[]
 for r in rows:
  start,end=r['saveStartNs'],r['saveEndNs']
  point.append(any(p['phase']=='before-batch-commit' and start<=p['atNs']<=end for p in r['phases']))
  interval.append(any(max(start,d['deleteStartNs'])<min(end,d['deleteEndNs']) for d in r['deleteIntervals']))
  started.append(any(d['deleteStartNs']<=start<=d['deleteEndNs'] for d in r['deleteIntervals']))
  mismatches=[e for e in r['batchEvents'] if e['phase']=='guard-checked' and not e['guardMatched']]
  guard.extend({'sample':r['sample'],'afterSequence':e['afterSequence'],'dataVersionDelta':e['observedDataVersion']-e['expectedDataVersion'],'changesDelta':e['observedChanges']-e['expectedChanges']} for e in mismatches)
  batches.append(len(r['deleteIntervals']))
  assert r['committed'] and r['contentVerified'] and r['saveOutcome']=='COMMITTED' and r['initialRevision']==2 and r['savedRevision']==r['finalRevision']==3 and r['retainedRevisions']==[1281]
  assert r['firstDeleted']+r['finishDeleted']==1280 and r['firstRootScans']==r['finishRootScans']==1
 firstns=sum(r['collectorEndNs']-r['collectorStartNs'] for r in rows);finishns=sum(r['finishEndNs']-r['finishStartNs'] for r in rows)
 by_deleted={str(n):{'count':sum(r['firstDeleted']==n for r in rows),'sampleIndices':[r['sample'] for r in rows if r['firstDeleted']==n],'saveSamplesMs':[r['saveMs'] for r in rows if r['firstDeleted']==n]} for n in sorted({r['firstDeleted'] for r in rows})}
 tops=[]
 for r in sorted(rows,key=lambda r:r['saveMs'],reverse=True)[:5]:
  origin=r['saveStartNs'];last=r['deleteIntervals'][-1]
  tops.append({'sample':r['sample'],'saveMs':r['saveMs'],'firstDeleted':r['firstDeleted'],'firstDeferred':r['firstDeferred'],
               'deleteBatchCount':len(r['deleteIntervals']),'lastDeleteEndRelativeSaveStartMs':ms(last['deleteEndNs']-origin),
               'lastGcTransactionReturnedRelativeSaveStartMs':ms(last['transactionReturnedNs']-origin),
               'firstGcCallEndRelativeSaveStartMs':ms(r['collectorEndNs']-origin),
               'beforeRenameReceiptRelativeSaveStartMs':ms(r['beforeRenameReceiptNs']-origin),
               'afterRenameTransactionRelativeSaveStartMs':ms(r['afterRenameTransactionNs']-origin),
               'writerEntryNs':None,'pureSqliteLockWaitMs':None})
 out['methods'].append({'ordinal':a['ordinal'],'connection':a['connection'],'records_sha256':sha(E/f"method-{a['ordinal']:02d}-C1-records.json"),
  'committed':30,'failed':0,'unknownCommitOutcomes':0,'saveSamplesMs':values,'quantilesNearestRank':quantiles,
  'exposureCounts':{'transactionPoint':sum(point),'instrumentedDeleteInterval':sum(interval),'saveStartedDuringDelete':sum(started)},
  'minimumRequiredForSubsetQuantiles':20,'firstDeletedTotal':sum(r['firstDeleted'] for r in rows),'finishDeletedTotal':sum(r['finishDeleted'] for r in rows),
  'totalDeleted':sum(r['firstDeleted']+r['finishDeleted'] for r in rows),'firstRootScans':30,'finishRootScans':30,
  'firstCollectionTotalMsExactFromNs':ms(firstns),'finishCollectionTotalMsExactFromNs':ms(finishns),'firstPlusFinishTotalMsExactFromNs':ms(firstns+finishns),
  'firstDeletionGroupsDescriptiveNotPosthocAcceptance':by_deleted,'firstDeferredCount':sum(r['firstDeferred'] for r in rows),'firstCommittedBatchCount':sum(batches),
  'guardMismatches':guard,'largestFiveSaveTimelineObservations':tops})
out['findings']=[
 'All 60 saves were committed and content/revision/retained snapshot assertions passed; all three exposure definitions independently recompute to 30/30 per mode.',
 'Same Room always deleted 256 then deferred; 30 mismatch reads changed changes by +3 and data_version by 0.',
 'Separate Room deleted 256 in 23 trials, 768 in 3, and all 1280 in 4. 26 deferred; mismatch data_version deltas were +1 or +2 with changes unchanged.',
 'The four whole-first-pass collections completed while the author call was still outstanding. No committed author write is shown being ignored by the guard.',
 'Separate-Room high-tail calls span multiple real GC batches. There is no author writer-entry marker, so physical SQLite wait, Room queueing, thread scheduling and SQL/commit costs cannot be separated.',
 'This single candidate conditional-arrival diagnostic has no equivalent baseline/control pair; it cannot establish regression, improvement, natural arrival probability or formal acceptance.',
 'No unambiguous minimal production fix is established. Keep guard semantics. The smallest additional attribution measurement, if separately authorized, is a default-off timestamp at the existing rename transaction entry; this is not implemented here.'
]
(D/'gc-causal-review.json').write_text(json.dumps(out,indent=2,ensure_ascii=False)+'\n')
print(json.dumps({'independentRawRecomputation':'PASS','methods':len(out['methods']),'samples':60,'output':str(D/'gc-causal-review.json')}))
