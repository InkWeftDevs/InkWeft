"""Independent AUTHOR_ONLY GC marker arithmetic; never executes a controller/device."""
from pathlib import Path
from collections import defaultdict, Counter
import argparse, hashlib, json, math, re

PRODUCER_SHA256='597bed443289598870d063af99731a60db48ca0e38d8e3576b24fcd995cf4055'
COLUMNS=['stage','ownerToken','task','transaction','depth','successRequested','outcome','atNs','cpuNs','thread']
MODES=('same-room','separate-room')
MARKERS=('HISTORY_GC_BOUNDARY_EVENTS','HISTORY_GC_BOUNDARY_TRIAL','HISTORY_GC_BOUNDARY_SUMMARY')
SEGMENTS=['requestToSubmit','writerQueue','workerToSqliteBegin','sqliteBeginCall','sqliteBeginToRoomBegin','roomBeginToReceipt','receiptToSqliteEnd','sqliteEndCall','sqliteEndToRoomEnd','roomEndToRenameReturn','renameReturnToCallEnd']
TRIAL_KEYS=set('sample connection scenario replacesFrozenProbe fixtureSourceVersions fixtureDetachedSources fixtureExplicitRoots fixtureLatestRetained artificialGates committed saveOutcome saveWatchdogMs batchLimit authorBoundaries boundaryCapacityPerDatabase batchCapacity phaseCapacity boundaryScope pureSqliteLockWaitMs gcOwnerToken authorOwnerToken finishOwnerToken initialRevision writerSqlite authorWriterSqlite savedRevision finalRevision contentVerified retainedRevisions status stage failureClass suppressedFailureClasses collectorFailureClass collectorStartNs collectorEndNs saveStartNs saveEndNs saveMs beforeRenameReceiptNs afterRenameTransactionNs firstDeleted firstDeferred firstRootScans firstCommittedBatches finishStartNs finishEndNs finishDeleted finishRootScans finishDeferred deletionTransactionExposed deleteWindowIntersected saveStartedDuringDelete deleteIntervals batchEvents batchDropped phaseDropped workersExited helpersClosed fixtureDeleted boundaries coveredBeforeBatchCommitNs phases'.split())
BATCH_KEYS=set('phase afterSequence candidates expectedDataVersion expectedChanges observedDataVersion observedChanges atNs cpuNs thread callbackNs'.split())
class Invalid(ValueError):pass
def require(ok, why):
    if not ok:raise Invalid(why)
def integer(v):return type(v) is int
def num(v):return type(v) in (int,float) and math.isfinite(v)
def same(a,b):return a==b
def close(a,b):return num(a) and num(b) and math.isclose(a,b,rel_tol=0,abs_tol=1e-8)
def one(es,stage,**kw):
    found=[e for e in es if e['stage']==stage and all(e[k]==v for k,v in kw.items())]
    require(len(found)==1,f'unique {stage} {kw}: {len(found)}');return found[0]
def stats(v,available=True):
    ordered=sorted(v);n=len(v)
    return {'count':n,'p50Ms':ordered[math.ceil(n*.5)-1] if n and available else None,
            'p95Ms':ordered[math.ceil(n*.95)-1] if n and available else None,'maxMs':ordered[-1] if n else None}
def cpu(a,b):
    if 'cpuNs' not in a or 'cpuNs' not in b:return None,'ENDPOINT_CPU_NOT_RECORDED'
    if a['thread']!=b['thread']:return None,'CROSS_THREAD'
    require(b['cpuNs']>=a['cpuNs'],'CPU went backwards')
    return b['cpuNs']-a['cpuNs'],None
def union(intervals,lo,hi):
    points=Counter()
    for a,b in intervals:
        a,b=max(a,lo),min(b,hi)
        if a<b:points[a]+=1;points[b]-=1
    active=0;previous=lo;total=0
    for t,delta in sorted(points.items()):
        if active:total+=t-previous
        active+=delta;previous=t
    require(active==0,'interval sweep failed');return total
def monotonic_by_thread(es):
    previous={}
    for e in es:
        for k in ('atNs','cpuNs','thread'):require(integer(e[k]),'bad clock/thread type')
        require(e['cpuNs']>=0 and e['thread']>0,'invalid CPU/thread')
        p=previous.get(e['thread'])
        if p:require(p['atNs']<=e['atNs'] and p['cpuNs']<=e['cpuNs'],'per-thread clock order')
        previous[e['thread']]=e
def validate_transactions(events,layer):
    stack=[];pending=[];closed=[]
    for e in events:
        if not e['stage'].startswith(layer+'-'):continue
        _,operation,edge=e['stage'].split('-')
        require(operation in ('begin','success','end') and edge in ('enter','exit'),'unknown operation')
        depth=e['depth'];tx=e['transaction']
        if edge=='enter':
            require(e['outcome']=='entered','entry outcome')
            require(depth==len(stack)+(operation=='begin'),'transaction depth')
            if operation=='begin':
                require(tx>0 and (not stack or tx==stack[0]['transaction']),'begin transaction identity')
                require(e['successRequested'] is False,'begin success flag')
            else:
                require(stack and tx==stack[0]['transaction'],'operation without matching begin')
                require(e['successRequested']==stack[-1]['success'],'success flag before operation')
            pending.append(e)
        else:
            require(pending,'exit without entry');start=pending.pop()
            require(start['stage']==f'{layer}-{operation}-enter','operation pair mismatch')
            require(all(start[k]==e[k] for k in ['task','transaction','depth','thread']),'paired identity mismatch')
            require(e['outcome']=='returned' and start['atNs']<=e['atNs'],'failed/reversed operation')
            cpu(start,e)
            if operation=='begin':
                require(e['successRequested'] is False,'begin exit flag')
                stack.append({'transaction':tx,'begin':start,'begun':e,'success':False})
            elif operation=='success':
                require(e['successRequested'] is True,'success exit flag');stack[-1]['success']=True
            else:
                require(e['successRequested']==stack[-1]['success'],'end exit flag')
                frame=stack.pop();closed.append({**frame,'end':start,'ended':e,'depth':depth})
    require(not stack and not pending,'unclosed transaction operations');return closed

def author(events,t):
    require(events,'no author events');monotonic_by_thread(events)
    token=t['authorOwnerToken']
    for e in events:
        require(list(e)==COLUMNS,'column mapping changed')
        require(e['ownerToken']==token,'non-author event')
        for k in ['ownerToken','task','transaction','depth']:require(integer(e[k]) and e[k]>=0,'bad identity field')
        require(type(e['successRequested']) is bool,'bad success flag')
        require(e['stage'] in ['writer-submit','writer-run-enter','writer-run-exit'] or re.fullmatch(r'(room|sqlite)-(begin|success|end)-(enter|exit)',e['stage']),'unexpected author stage')
    submit=one(events,'writer-submit');run=one(events,'writer-run-enter');exit_event=one(events,'writer-run-exit')
    require(submit['outcome']==run['outcome']=='entered' and exit_event['outcome']=='returned','writer outcome')
    require(submit['task']==run['task']==exit_event['task']>0,'writer task mismatch')
    for e in events:
        require(e['task']==run['task'],'author task mismatch')
        if e['stage']!='writer-submit':require(e['thread']==run['thread'],'worker thread mismatch')
        if e['stage'].startswith('writer-'):require(e['depth']==e['transaction']==0 and not e['successRequested'],'writer metadata')
    rooms=validate_transactions(events,'room');sqls=validate_transactions(events,'sqlite')
    room=[x for x in rooms if x['depth']==1];require(len(room)==1,'outer Room count');room=room[0]
    receipt=t['beforeRenameReceiptNs']
    selected=[x for x in sqls if x['depth']==1 and x['begun']['atNs']<=receipt<=x['ended']['atNs']]
    require(len(selected)==1,'SQLite transaction spanning receipt is not unique');sql=selected[0]
    require(room['success'] and sql['success'],'outer transaction not successful')
    for layer,frame in [('room',room),('sqlite',sql)]:
        for op in ['begin','success','end']:
            one(events,f'{layer}-{op}-enter',depth=1,transaction=frame['transaction'])
            one(events,f'{layer}-{op}-exit',depth=1,transaction=frame['transaction'])
    points=[{'atNs':t['saveStartNs']},submit,run,sql['begin'],sql['begun'],room['begun'],{'atNs':receipt},sql['end'],sql['ended'],room['ended'],{'atNs':t['afterRenameTransactionNs']},{'atNs':t['saveEndNs']}]
    times=[p['atNs'] for p in points];require(times==sorted(times),'author segment order')
    require(room['begin']['atNs']<=sql['begin']['atNs'] and room['end']['atNs']<=sql['end']['atNs'],'Room/SQLite nesting')
    require(exit_event['atNs']>=room['ended']['atNs'],'worker exit before Room end')
    pieces=[]
    for name,a,b in zip(SEGMENTS,points,points[1:]):
        ns=b['atNs']-a['atNs'];cn,why=cpu(a,b)
        pieces.append({'name':name,'startNs':a['atNs'],'endNs':b['atNs'],'elapsedNs':ns,'wallMs':ns/1e6,'threadCpuNs':cn,'cpuUnavailableReason':why})
    observed=t['authorBoundaries']
    require(set(observed)=={'databaseTask','sqliteTransaction','roomTransaction','writerThread','segments','sqliteBeginThreadCpuNs','sqliteEndThreadCpuNs','pureSqliteLockWaitMs'},'author summary schema')
    for k in ['databaseTask','sqliteTransaction','roomTransaction','writerThread','sqliteBeginThreadCpuNs','sqliteEndThreadCpuNs']:require(integer(observed[k]),'author summary integer')
    require(all(set(s)=={'name','startNs','endNs','elapsedNs'} and all(integer(s[k]) for k in ['startNs','endNs','elapsedNs']) for s in observed['segments']),'author segment schema')
    require(observed['databaseTask']==run['task'] and observed['writerThread']==run['thread'],'reported author identity')
    require(observed['sqliteTransaction']==sql['transaction'] and observed['roomTransaction']==room['transaction'],'reported transaction identity')
    require(observed['segments']==[{k:x[k] for k in ['name','startNs','endNs','elapsedNs']} for x in pieces],'reported segment mismatch')
    for label,a,b in [('sqliteBeginThreadCpuNs',sql['begin'],sql['begun']),('sqliteEndThreadCpuNs',sql['end'],sql['ended'])]:require(observed[label]==cpu(a,b)[0],'reported CPU mismatch')
    require(observed['pureSqliteLockWaitMs'] is None,'invented pure lock wait')
    total=t['saveEndNs']-t['saveStartNs'];require(sum(x['elapsedNs'] for x in pieces)==total,'duration conservation')
    require(close(t['saveMs'],total/1e6),'reported saveMs mismatch')
    return {'segments':pieces,'saveMs':total/1e6,'writerTask':run['task'],'writerThread':run['thread'],'roomTransaction':room['transaction'],'sqliteTransaction':sql['transaction'],'selectedSqliteBegin':sql['begin'],'selectedSqliteEnd':sql['ended'],'writerExitAfterRenameReturn':exit_event['atNs']>t['afterRenameTransactionNs']}

def trial(t,events):
    require(set(t)==TRIAL_KEYS,'TRIAL fieldset')
    s=t['sample'];require(integer(s) and 0<=s<30,'sample range')
    require(t['status']=='PASS' and t['stage']=='complete','nonPASS trial')
    for k in ['committed','contentVerified','workersExited','helpersClosed','fixtureDeleted']:require(t[k] is True,k)
    require(t['saveOutcome']=='COMMITTED','save outcome')
    for k in ['failureClass','collectorFailureClass','pureSqliteLockWaitMs']:require(t[k] is None,k)
    require(t['suppressedFailureClasses']==[],'suppressed failure')
    expected={'scenario':'AFTER_SCAN_ARRIVAL_BOUNDARIES','replacesFrozenProbe':False,'fixtureSourceVersions':1281,'fixtureDetachedSources':1280,'fixtureExplicitRoots':0,'fixtureLatestRetained':1,'artificialGates':False,'saveWatchdogMs':5000,'batchLimit':256,'boundaryCapacityPerDatabase':512,'batchCapacity':64,'phaseCapacity':32,'boundaryScope':'AUTHOR_ONLY','gcOwnerToken':s*3,'authorOwnerToken':s*3+1,'finishOwnerToken':s*3+2,'batchDropped':0,'phaseDropped':0}
    for k,v in expected.items():require(t[k]==v and type(t[k]) is type(v),f'fixture/contract {k}')
    settings={'page_size':1024,'auto_vacuum':1,'journal_mode':'wal','synchronous':2}
    require(t['writerSqlite']==t['authorWriterSqlite']==settings,'SQLite settings')
    require(all(integer(t[field][k]) for field in ['writerSqlite','authorWriterSqlite'] for k in ['page_size','auto_vacuum','synchronous']),'SQLite settings type')
    for k in ['initialRevision','savedRevision','finalRevision','firstDeleted','firstRootScans','firstCommittedBatches','finishDeleted','finishRootScans']:require(integer(t[k]) and t[k]>=0,'integer field '+k)
    require(t['initialRevision']==2 and t['savedRevision']==t['finalRevision']==3,'revision chain')
    require(t['retainedRevisions']==[1281] and all(integer(v) for v in t['retainedRevisions']),'retention/type')
    clocks=['collectorStartNs','collectorEndNs','saveStartNs','saveEndNs','beforeRenameReceiptNs','afterRenameTransactionNs','finishStartNs','finishEndNs']
    for k in clocks:require(integer(t[k]),'missing/noninteger clock '+k)
    require(t['collectorStartNs']<=t['collectorEndNs']<=t['finishStartNs']<=t['finishEndNs'],'collector/finish order')
    require(t['saveEndNs']<=t['finishStartNs'],'finish precedes author completion')
    a=author(events,t)
    phases=t['phases'];require(len(phases)<=32,'phase capacity')
    require(all(set(p)=={'phase','atNs'} and p['phase'] in ['after-roots','after-stats','after-scan','before-batch-commit','after-batch'] and integer(p['atNs']) for p in phases),'phase schema')
    require([p['phase'] for p in phases[:3]]==['after-roots','after-stats','after-scan'] and sum(p['phase']=='after-stats' for p in phases)==1,'scan phase chain')
    require([p['atNs'] for p in phases]==sorted(p['atNs'] for p in phases),'phase order')
    require(all(t['collectorStartNs']<=p['atNs']<=t['collectorEndNs'] for p in phases),'phase outside collection')
    require(sum(p['phase']=='after-roots' for p in phases)==t['firstRootScans']==1,'first roots count')
    scans=[p['atNs'] for p in phases if p['phase']=='after-scan'];require(len(scans)==1 and scans[0]<=t['saveStartNs'],'natural after-scan arrival')
    require(t['firstCommittedBatches']==sum(p['phase']=='after-batch' for p in phases),'committed batch count')
    batches=t['batchEvents'];require(0<len(batches)<=64,'batch capacity')
    require(all(set(b)==BATCH_KEYS for b in batches),'batch fieldset')
    for b in batches:
        for k in BATCH_KEYS-{'phase'}:require(integer(b[k]),'batch integer '+k)
    monotonic_by_thread(batches)
    require([b['atNs'] for b in batches]==sorted(b['atNs'] for b in batches),'serial GC callback order')
    require(all(t['collectorStartNs']<=b['atNs']<=t['collectorEndNs'] and integer(b['callbackNs']) and b['callbackNs']>=0 for b in batches),'batch timing')
    groups=defaultdict(list)
    for b in batches:
        require(b['phase'] in ['guard-checked','before-delete','after-delete','transaction-returned'],'batch stage')
        require(integer(b['afterSequence']) and b['afterSequence']>=0 and integer(b['candidates']) and 0<=b['candidates']<=256,'batch identity')
        groups[b['afterSequence']].append(b)
    require(list(groups)==sorted(groups),'batch sequence order')
    intervals=[];candidate_sum=0;delete_cpus=[]
    for seq,bs in groups.items():
        phases_here=[b['phase'] for b in bs]
        require(phases_here in [['guard-checked','transaction-returned'],['guard-checked','before-delete','after-delete','transaction-returned']],'batch closure')
        require([b['atNs'] for b in bs]==sorted(b['atNs'] for b in bs),'batch stage order')
        fields=['afterSequence','candidates','expectedDataVersion','expectedChanges','observedDataVersion','observedChanges']
        require(all(all(b[k]==bs[0][k] for k in fields) for b in bs),'batch values changed')
        if len(bs)==4:
            g,begin,end,returned=bs
            require(g['candidates']==256 and g['expectedDataVersion']==g['observedDataVersion'] and g['expectedChanges']==g['observedChanges'],'delete guard/full batch')
            candidate_sum+=g['candidates']
            require(begin['thread']==end['thread'],'DELETE window switched worker')
            delete_cpus.append({'afterSequence':seq,'thread':begin['thread'],'threadCpuNs':cpu(begin,end)[0]})
            overlap=max(t['saveStartNs'],begin['atNs'])<min(t['saveEndNs'],end['atNs'])
            started=begin['atNs']<=t['saveStartNs']<=end['atNs']
            intervals.append({'afterSequence':seq,'candidates':g['candidates'],'deleteStartNs':begin['atNs'],'deleteEndNs':end['atNs'],'transactionReturnedNs':returned['atNs'],'saveIntersected':overlap,'saveStartedDuringDelete':started})
    for d in t['deleteIntervals']:
        ints=['afterSequence','candidates','deleteStartNs','deleteEndNs','transactionReturnedNs'];bools=['saveIntersected','saveStartedDuringDelete']
        require(set(d)==set(ints+bools) and all(integer(d[k]) for k in ints) and all(type(d[k]) is bool for k in bools),'DELETE interval schema/types')
    require(intervals==t['deleteIntervals'],'reported DELETE intervals mismatch')
    require(candidate_sum==t['firstDeleted']==t['firstCommittedBatches']*256 and candidate_sum<=1280,'first deleted count')
    require(list(groups)==list(range(0,candidate_sum+1,256)),'missing/extra GC sequence group')
    last=groups[candidate_sum];require(len(last)==2,'missing terminal empty/deferred guard')
    guard=last[0];matches=guard['expectedDataVersion']==guard['observedDataVersion'] and guard['expectedChanges']==guard['observedChanges']
    # Production checks writerVersion before testing candidates.isEmpty(). An author
    # commit after batch five can therefore defer the terminal empty guard too.
    require(guard['candidates']==(0 if candidate_sum==1280 else 256),'terminal candidate count')
    require(t['firstDeferred'] is (not matches),'terminal guard/defer mismatch')
    require(not matches or candidate_sum==1280,'matched nonempty terminal guard')
    precommit=[p['atNs'] for p in phases if p['phase']=='before-batch-commit'];afterbatch=[p['atNs'] for p in phases if p['phase']=='after-batch']
    require(len(precommit)==len(afterbatch)==len(intervals),'missing commit/after-batch phase')
    for d,pc,ab in zip(intervals,precommit,afterbatch):require(d['deleteEndNs']<=pc<=d['transactionReturnedNs']<=ab,'DELETE/commit/after-batch order')
    require(t['finishDeleted']+candidate_sum==1280 and t['finishDeferred'] is False and t['finishRootScans']==1,'finish count/state')
    require(type(t['firstDeferred']) is bool,'first deferred type')
    covered=[p['atNs'] for p in phases if p['phase']=='before-batch-commit' and t['saveStartNs']<=p['atNs']<=t['saveEndNs']]
    require(covered==t['coveredBeforeBatchCommitNs'] and all(integer(v) for v in t['coveredBeforeBatchCommitNs']),'precommit timestamps/types')
    exposure={'deletionTransactionExposed':bool(covered),'deleteWindowIntersected':any(x['saveIntersected'] for x in intervals),'saveStartedDuringDelete':any(x['saveStartedDuringDelete'] for x in intervals)}
    require(all(type(t[k]) is bool and t[k]==v for k,v in exposure.items()),'reported exposure mismatch')
    spans=[(x['deleteStartNs'],x['deleteEndNs']) for x in intervals]
    return {'sample':s,**a,**exposure,'collectorWallMs':(t['collectorEndNs']-t['collectorStartNs'])/1e6,'finishWallMs':(t['finishEndNs']-t['finishStartNs'])/1e6,'cleanupWallMs':(t['collectorEndNs']-t['collectorStartNs']+t['finishEndNs']-t['finishStartNs'])/1e6,'firstRootScans':t['firstRootScans'],'finishRootScans':t['finishRootScans'],'firstDeleted':t['firstDeleted'],'finishDeleted':t['finishDeleted'],'deleteUnionMs':union(spans,t['collectorStartNs'],t['collectorEndNs'])/1e6,'deleteSaveUnionOverlapMs':union(spans,t['saveStartNs'],t['saveEndNs'])/1e6,'deleteIntervals':intervals,'deleteWindowCpu':delete_cpus,'rawTrial':t,'rawAuthorEvents':events,'pureSqliteLockWaitMs':None}

def distribution(samples,flag=None):
    selected=[x for x in samples if flag is None or x[flag]];vals=[x['saveMs'] for x in selected]
    return {**stats(vals,flag is None or len(vals)>=20),'sampleIndices':[x['sample'] for x in selected],'saveSamplesMs':vals}
def summary(actual,samples,mode):
    expected={'scenario':'AFTER_SCAN_ARRIVAL_BOUNDARIES','replacesFrozenProbe':False,'connection':mode,'plannedTrials':30,'recordedTrials':30,'passedTrials':30,'committedSaves':30,'failedTrials':0,'unknownCommitOutcomes':0,'notStartedSaves':0,'functionalStatus':'PASS','exposureStatus':'SUFFICIENT_EXPOSURE' if sum(x['deletionTransactionExposed'] for x in samples)>=20 else 'INSUFFICIENT_EXPOSURE','minimumExposedSamplesForQuantiles':20,'latencyAcceptance':'NOT_EVALUATED','saveWatchdogMs':5000,'watchdogIsLatencyAcceptance':False,'quantileDefinition':'nearest rank: ceil(p*n)-1, zero based; n=30 p50=14 p95=28','pureSqliteLockWaitMs':None}
    require(set(actual)==set(expected)|{'allSaves','exposedSaves','deleteWindowIntersectedSaves','saveStartedDuringDeleteSaves'},'SUMMARY fieldset')
    for k,v in expected.items():require(actual[k]==v and type(actual[k]) is type(v),'summary '+k)
    distributions={}
    for key,flag in [('allSaves',None),('exposedSaves','deletionTransactionExposed'),('deleteWindowIntersectedSaves','deleteWindowIntersected'),('saveStartedDuringDeleteSaves','saveStartedDuringDelete')]:
        d=distribution(samples,flag);got=actual[key]
        require(set(got)==set(d),'distribution schema')
        for k,v in d.items():
            if isinstance(v,list):
                require(isinstance(got[k],list) and all(integer(z) if k=='sampleIndices' else num(z) for z in got[k]) and got[k]==v,'distribution raw values/order')
            elif v is None:require(got[k] is None,'low exposure quantile fabricated')
            elif k=='count':require(integer(got[k]) and got[k]==v,'distribution count')
            else:require(close(got[k],v),'distribution quantile')
        distributions[key]=d
    return distributions

def analyze(records,input_kind='synthetic'):
    result={'status':'INVALID_OR_INCOMPLETE_GC_DIAGNOSTIC','scope':'DIAGNOSTIC_ONLY_NO_PERFORMANCE_ACCEPTANCE','inputKind':input_kind,'expectedProducerSha256':PRODUCER_SHA256,'sourceBindingRequiresExternalWholeRunReview':True,'pureSqliteLockWaitMs':None,'issues':[],'rawMarkers':records,'modes':{}}
    try:
        grouped={m:[] for m in MODES}
        for r in records:
            require(r['marker'] in MARKERS and isinstance(r['data'],dict),'unknown marker/type')
            mode=r['data']['connection'];require(mode in MODES,'unknown mode');grouped[mode].append(r)
        for mode,rs in grouped.items():
            require(rs and rs[-1]['marker']==MARKERS[2],'missing/late summary')
            summaries=[r['data'] for r in rs if r['marker']==MARKERS[2]];require(len(summaries)==1,'summary count')
            trials=[r['data'] for r in rs if r['marker']==MARKERS[1]]
            require([t['sample'] for t in trials]==list(range(30)),'missing/duplicate/out-of-order trials')
            if any(t.get('status')!='PASS' for t in trials):raise Invalid('failed trial retained; no complete-mode quantiles')
            shards=defaultdict(list);current_sample=0
            for r in rs[:-1]:
                d=r['data'];require(d['sample']==current_sample,'marker/trial order')
                if r['marker']==MARKERS[1]:current_sample+=1
                else:
                    require(r['marker']==MARKERS[0],'unexpected marker')
                    require(set(d)=={'sample','connection','database','part','parts','eventSchema','eventColumns','events'},'EVENT schema')
                    require(d['eventSchema']=='author-boundary-v1' and d['eventColumns']==COLUMNS,'columns/schema mismatch')
                    require(d['database']==('collector' if mode=='same-room' else 'author'),'non-author database shard')
                    require(integer(d['part']) and integer(d['parts']) and 1<=d['parts']<=11,'part metadata')
                    require(isinstance(d['events'],list) and 1<=len(d['events'])<=48,'shard size')
                    require(all(isinstance(e,list) and len(e)==10 for e in d['events']),'row length')
                    shards[current_sample].append(d)
            samples=[]
            for t in trials:
                ps=shards[t['sample']];require(ps,'missing EVENT shards')
                require([x['part'] for x in ps]==list(range(len(ps))) and all(x['parts']==len(ps) for x in ps),'shard closure')
                require(all(len(x['events'])==48 for x in ps[:-1]),'non-final short shard')
                es=[dict(zip(COLUMNS,e)) for p in ps for e in p['events']];require(len(es)<=512,'event capacity')
                dbs=['collector'] if mode=='same-room' else ['collector','author']
                expected_boundaries=[{'database':db,'count':len(es) if db==ps[0]['database'] else 0,'dropped':0,'invalid':False} for db in dbs]
                require(json.dumps(t['boundaries'],sort_keys=True)==json.dumps(expected_boundaries,sort_keys=True),'boundary counts/dropped/invalid/types')
                samples.append(trial(t,es))
            distributions=summary(summaries[0],samples,mode)
            segment_wall={};segment_cpu={}
            for name in SEGMENTS:
                selected=[next(s for s in x['segments'] if s['name']==name) for x in samples]
                segment_wall[name]=stats([s['wallMs'] for s in selected])
                segment_cpu[name]={**stats([s['threadCpuNs']/1e6 for s in selected if s['threadCpuNs'] is not None]),'unavailableCount':sum(s['threadCpuNs'] is None for s in selected),'unavailableReasons':dict(Counter(s['cpuUnavailableReason'] for s in selected if s['threadCpuNs'] is None))}
            result['modes'][mode]={'samples':samples,'saveDistributions':distributions,'firstRootScans':sum(x['firstRootScans'] for x in samples),'finishRootScans':sum(x['finishRootScans'] for x in samples),'allRootScans':sum(x['firstRootScans']+x['finishRootScans'] for x in samples),'collectorTotalMs':sum(x['collectorWallMs'] for x in samples),'finishTotalMs':sum(x['finishWallMs'] for x in samples),'cleanupTotalMs':sum(x['cleanupWallMs'] for x in samples),'deleteUnionSumMs':sum(x['deleteUnionMs'] for x in samples),'deleteSaveOverlapSumMs':sum(x['deleteSaveUnionOverlapMs'] for x in samples),'segmentWallMs':segment_wall,'segmentSameThreadCpuMs':segment_cpu,'rawSummary':summaries[0]}
        result['status']='COMPLETE_GC_AUTHOR_BOUNDARY_DIAGNOSTIC_ONLY'
    except (Invalid,KeyError,TypeError,ValueError,IndexError) as e:
        result['issues'].append(str(e));result['modes']={}
    result['limitations']=['Synthetic input is never native evidence. Provided marker text still requires separate source/run/JUnit/cleanup integrity review.','Single-source instrumented diagnostic, not four-pair acceptance.','Author-only rows omit GC/finish Room/SQLite task traces. GC batch intervals include callback overhead.','Cross-thread or unrecorded endpoint CPU is null. Wall minus CPU is not pure lock wait.','Three exposure definitions are separate. Fewer than20 samples keeps conditional p50/p95 null;0/30 does not establish no blocking.','First and finish cleanup totals are separate; finish after author return is not concurrent save overhead.','Only derived geometry/order statistics are sorted; raw events/saves remain in source order.']
    return result

def parse(paths):
    records=[];inputs=[]
    for path in paths:
        raw=path.read_bytes();text=raw.decode('utf-8-sig');inputs.append({'path':str(path),'sha256':hashlib.sha256(raw).hexdigest(),'bytes':len(raw)})
        for line_no,line in enumerate(text.splitlines(),1):
            match=re.search(r'(HISTORY_GC_BOUNDARY_(?:EVENTS|TRIAL|SUMMARY)) (\{.*\})\s*$',line)
            if not match:
                require('HISTORY_GC_BOUNDARY_' not in line,'malformed/truncated GC marker');continue
            original=match[2]
            def pairs(ps):
                d={}
                for k,v in ps:require(k not in d,'duplicate JSON key');d[k]=v
                return d
            data=json.loads(original,object_pairs_hook=pairs,parse_constant=lambda s:(_ for _ in ()).throw(Invalid('nonfinite JSON')))
            records.append({'marker':match[1],'data':data,'inputPath':str(path),'line':line_no,'originalJson':original,'originalJsonSha256':hashlib.sha256(original.encode()).hexdigest()})
    return records,inputs
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--markers',type=Path,nargs='+',required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--input-kind',choices=['synthetic','provided-marker-text'],required=True);args=p.parse_args()
    require(not args.output.exists(),'output already exists')
    try:
        rs,ins=parse(args.markers);answer=analyze(rs,args.input_kind);answer['inputs']=ins
    except (Invalid,ValueError,UnicodeError) as e:answer={'status':'INVALID_INPUT','issues':[str(e)],'inputKind':args.input_kind}
    answer['analyzerSha256']=hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    args.output.write_text(json.dumps(answer,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'status':answer['status'],'issues':answer.get('issues',[]),'inputKind':args.input_kind}))
    raise SystemExit(0 if answer['status']=='COMPLETE_GC_AUTHOR_BOUNDARY_DIAGNOSTIC_ONLY' else 1)
