from pathlib import Path
import json,collections,statistics as st,math,hashlib
O=Path('/workspace/scratch/pr20-match-execution-20261014/search-review');p=O.parent/'extracted/method-58-C1-records.json';records=json.loads(p.read_text());chunks=[r['data'] for r in records if r['marker']=='LIBRARY_STAGE_DIAGNOSTICS'];assert len({c['originNs'] for c in chunks})==1;origin=chunks[0]['originNs'];ev=[];saves=[]
for c in chunks:
 for e in c['events']:ev.append(dict(e,atNs=origin+e['elapsedNs'],checkpoint=c['checkpoint'],part=c['part']))
 saves+=c['saves']
evidence=next(r['data'] for r in records if r['marker']=='LIBRARY_READ_EVIDENCE')
assert len(saves)==60 and all(s['committed'] and s['outcome']=='committed' for s in saves)
def stat(v):
 if not v:return {'n':0}
 x=sorted(v);return {'n':len(x),'p50':x[math.ceil(len(x)*.5)-1],'p95':x[math.ceil(len(x)*.95)-1],'max':max(x),'sum':sum(x)}
def overlap(a,b,c,d):return max(0,min(b,d)-max(a,c))/1e6
def union(ints):
 ints=sorted(ints);out=[]
 for a,b in ints:
  if not out or a>out[-1][1]:out.append([a,b])
  else:out[-1][1]=max(out[-1][1],b)
 return out
by=collections.defaultdict(list)
for e in ev:
 if e['readId']:by[e['readId']].append(e)
phases=[('dispatch','read-requested','read-start'),('open','read-start','connection-open'),('begin','connection-open','snapshot-open'),('drafts','snapshot-open','drafts-ready'),('scope','scope-start','scope-end'),('hitsSetup','scope-end','hits-ready'),('pageHits','page-hits-start','page-hits-end'),('cardHits','card-hits-start','card-hits-end'),('page','page-start','page-end'),('metadata','metadata-start','metadata-end'),('pending','pending-start','pending-end')]
reads=[]
for rid,es in sorted(by.items()):
 d={e['stage']:e for e in es};first=es[0];r={'readId':rid,'generation':first['generation'],'version':first['sourceVersion'],'stages':es,'ioStarted':'read-start' in d,'requested':'read-requested' in d,'terminal':next((s for s in ['read-cancel','read-end','read-error'] if s in d),None),'phases':{}}
 for name,start,end in phases:
  if start in d and end in d:r['phases'][name]={'startNs':d[start]['atNs'],'endNs':d[end]['atNs'],'wallMs':(d[end]['atNs']-d[start]['atNs'])/1e6}
 if r['ioStarted'] and r['terminal']:
  r['phases']['io']={'startNs':d['read-start']['atNs'],'endNs':d[r['terminal']]['atNs'],'wallMs':(d[r['terminal']]['atNs']-d['read-start']['atNs'])/1e6}
 if r['terminal']=='read-cancel':
  exclude={'snapshot-closed','connection-closed','read-cancel','observe-read-cancel'}
  r['lastWorkBeforeCancel']=next(e['stage'] for e in reversed(es) if e['stage'] not in exclude)
 reads.append(r)
observe_saves=[s for s in saves if s['phase']=='observe'];wa=min(s['startNs'] for s in observe_saves);wz=max(s['endNs'] for s in observe_saves);ors=[r for r in reads if r['generation']==2];cancelled=[r for r in ors if r['terminal']=='read-cancel'];phase_summary={}
for name,_,_ in phases+[('io','','')]:
 complete=[r['phases'][name] for r in ors if name in r['phases']]
 clipped=[(max(v['startNs'],wa),min(v['endNs'],wz)) for v in complete if v['startNs']<wz and v['endNs']>wa]
 phase_summary[name]={'completeObserveIntervals':len(complete),'cancelledReadsCompletedIntervals':sum(name in r['phases'] for r in cancelled),'allObserveWallMs':stat([v['wallMs'] for v in complete]),'cancelledReadWallMs':stat([r['phases'][name]['wallMs'] for r in cancelled if name in r['phases']]),'saveWindowOverlapMs':sum((b-a)/1e6 for a,b in clipped),'saveWindowUnionOverlapMs':sum((b-a)/1e6 for a,b in union(clipped))}
ss=[]
for s in observe_saves:
 d=dict(s);d['elapsedMs']=(s['endNs']-s['startNs'])/1e6;d['phaseOverlapMs']={n:sum(overlap(s['startNs'],s['endNs'],r['phases'][n]['startNs'],r['phases'][n]['endNs']) for r in ors if n in r['phases']) for n in phase_summary};d['eventsWithin']=[e for e in ev if s['startNs']<=e['atNs']<=s['endNs']];d['progress']=evidence['observeIndexConcurrentSaves']['saveProgressSamples'][s['sample']];ss.append(d)
warm=[r for r in reads if any(e['checkpoint'].startswith('warm-') for e in r['stages'])]
res={'scope':'C1_ONLY_INSTRUMENTED_METHOD58_NOT_PUBLIC_COMPARISON','inputSha256':hashlib.sha256(p.read_bytes()).hexdigest(),'originNs':origin,'observeSaveWindow':{'startNs':wa,'endNs':wz,'elapsedMs':(wz-wa)/1e6},'observeSaveMs':stat([(s['endNs']-s['startNs'])/1e6 for s in observe_saves]),'counts':{'observeScheduled':sum(e['stage']=='observe-scheduled' for e in ev),'observeReadRequested':sum(r['requested'] for r in ors),'observeIoStarted':sum(r['ioStarted'] for r in ors),'observeIoCancelled':len(cancelled),'observeIoEnded':sum(r['terminal']=='read-end' for r in ors),'observationCancelledBeforeIo':sum(not r['ioStarted'] and any(e['stage']=='observe-read-cancel' for e in r['stages']) for r in ors),'initialDebounceCancelled':sum(e['stage']=='observe-debounce-cancel' for e in ev),'observePageStartsDuringSaveWindow':sum(e['stage']=='page-start' and e['generation']==2 and wa<=e['atNs']<=wz for e in ev),'observeMetadataStartsDuringSaveWindow':sum(e['stage']=='metadata-start' and e['generation']==2 and wa<=e['atNs']<=wz for e in ev)},'lastWorkBeforeCancel':dict(collections.Counter(r['lastWorkBeforeCancel'] for r in cancelled)),'phaseSummary':phase_summary,'reads':reads,'observeSaves':ss,'warm':{'rawFullReadMs':evidence['warmSamplesMs'],'pageMs':[r['phases']['page']['wallMs'] for r in warm],'metadataMs':[r['phases']['metadata']['wallMs'] for r in warm]},'evidence':evidence,'limitations':['No threadId/threadCPU in stage58 events.','No writer-request/acquired/receipt/transaction-returned or per-batch writer events in stage58.','Unclosed stage intervals are not extended to cancellation or save end.','Initial and index-completed successful reads after save window are retained, not counted as save-time page work.']}
(O/'stage58-analysis.json').write_text(json.dumps(res,indent=2)+'\n');print(json.dumps({k:v for k,v in res.items() if k not in ['reads','observeSaves','evidence','phaseSummary']},indent=2));print('PHASES')
for k,v in phase_summary.items():print(k,v['cancelledReadsCompletedIntervals'],v['cancelledReadWallMs'],'overlapMs',v['saveWindowOverlapMs'])
print('TOPSAVE',[(s['sample'],s['elapsedMs'],s['phaseOverlapMs'],s['progress']) for s in sorted(ss,key=lambda x:x['elapsedMs'],reverse=True)[:3]])
