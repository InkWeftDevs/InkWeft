"""Clearly synthetic legal-shape/fault cases, never native measurements."""
import copy,hashlib,importlib.util,json,math,tempfile
from pathlib import Path
HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('gc_analysis',HERE/'analyze.py');a=importlib.util.module_from_spec(spec);spec.loader.exec_module(a)
M=1_000_000
def distribution(trials,field=None):
    selected=[t for t in trials if field is None or t[field]];v=[t['saveMs'] for t in selected];sv=sorted(v);n=len(v)
    enabled=field is None or n>=20
    return {'count':n,'sampleIndices':[t['sample'] for t in selected],'saveSamplesMs':v,'maxMs':sv[-1] if n else None,'p50Ms':sv[math.ceil(n*.5)-1] if n and enabled else None,'p95Ms':sv[math.ceil(n*.95)-1] if n and enabled else None}
def fixture(exposures=(20,19)):
    records=[]
    for mode_index,mode in enumerate(a.MODES):
        trials=[]
        for sample in range(30):
            origin=(mode_index+1)*10**12+sample*10**9
            ns=lambda t:origin+int(t*M)
            selected=sample<exposures[mode_index]
            task=sample+1;room=sample*3+1;sql=sample*3+2;owner=sample*3+1
            raw=[('writer-submit',100,0,0,False,'entered',11),('writer-run-enter',110,0,0,False,'entered',22),('room-begin-enter',115,room,1,False,'entered',22),('sqlite-begin-enter',120,sql,1,False,'entered',22),('sqlite-begin-exit',125,sql,1,False,'returned',22),('room-begin-exit',130,room,1,False,'returned',22),('room-success-enter',160,room,1,False,'entered',22),('sqlite-success-enter',161,sql,1,False,'entered',22),('sqlite-success-exit',162,sql,1,True,'returned',22),('room-success-exit',163,room,1,True,'returned',22),('room-end-enter',164,room,1,True,'entered',22),('sqlite-end-enter',165,sql,1,True,'entered',22),('sqlite-end-exit',170,sql,1,True,'returned',22),('room-end-exit',175,room,1,True,'returned',22),('writer-run-exit',180,0,0,False,'returned',22)]
            events=[[stage,owner,task,tx,depth,success,outcome,ns(t),(i+1)*1000,thread] for i,(stage,t,tx,depth,success,outcome,thread) in enumerate(raw)]
            points=[90,100,110,120,125,130,150,165,170,175,190+sample,200+sample]
            segments=[{'name':name,'startNs':ns(x),'endNs':ns(y),'elapsedNs':int((y-x)*M)} for name,x,y in zip(a.SEGMENTS,points,points[1:])]
            phases=[{'phase':'after-roots','atNs':ns(50)},{'phase':'after-stats','atNs':ns(55)},{'phase':'after-scan','atNs':ns(60)}]
            batches=[];intervals=[]
            def batch(phase,seq,count,t,changed=False):
                batches.append({'phase':phase,'afterSequence':seq,'candidates':count,'expectedDataVersion':10,'expectedChanges':20,'observedDataVersion':11 if changed else 10,'observedChanges':20,'atNs':ns(t),'cpuNs':int(t*1000),'thread':33,'callbackNs':100})
            if selected:
                typ=sample%3;begin,end=(80,100) if typ==0 else (80,89) if typ==1 else (91,100)
                for phase,t in [('guard-checked',begin-1),('before-delete',begin),('after-delete',end),('transaction-returned',102)]:batch(phase,0,256,t)
                phases.extend([{'phase':'before-batch-commit','atNs':ns(101)},{'phase':'after-batch','atNs':ns(103)}])
                intervals.append({'afterSequence':0,'candidates':256,'deleteStartNs':ns(begin),'deleteEndNs':ns(end),'transactionReturnedNs':ns(102),'saveIntersected':end>90,'saveStartedDuringDelete':begin<=90<=end})
                batch('guard-checked',256,256,201,True);batch('transaction-returned',256,256,202,True)
                first_deleted=256;collector_end=205
            else:
                for i in range(5):
                    begin=65+i*4;end=begin+1
                    for phase,t in [('guard-checked',begin),('before-delete',begin),('after-delete',end),('transaction-returned',end+1)]:batch(phase,i*256,256,t)
                    phases.extend([{'phase':'before-batch-commit','atNs':ns(end+1)},{'phase':'after-batch','atNs':ns(end+2)}])
                    intervals.append({'afterSequence':i*256,'candidates':256,'deleteStartNs':ns(begin),'deleteEndNs':ns(end),'transactionReturnedNs':ns(end+1),'saveIntersected':False,'saveStartedDuringDelete':False})
                batch('guard-checked',1280,0,85);batch('transaction-returned',1280,0,86)
                first_deleted=1280;collector_end=87
            end=200+sample;finish=max(end,collector_end)+10
            databases=['collector'] if mode=='same-room' else ['collector','author'];db='collector' if mode=='same-room' else 'author'
            settings={'page_size':1024,'auto_vacuum':1,'journal_mode':'wal','synchronous':2}
            trial={'sample':sample,'connection':mode,'scenario':'AFTER_SCAN_ARRIVAL_BOUNDARIES','replacesFrozenProbe':False,'fixtureSourceVersions':1281,'fixtureDetachedSources':1280,'fixtureExplicitRoots':0,'fixtureLatestRetained':1,'artificialGates':False,'committed':True,'saveOutcome':'COMMITTED','saveWatchdogMs':5000,'batchLimit':256,'boundaryCapacityPerDatabase':512,'batchCapacity':64,'phaseCapacity':32,'boundaryScope':'AUTHOR_ONLY','pureSqliteLockWaitMs':None,'gcOwnerToken':sample*3,'authorOwnerToken':owner,'finishOwnerToken':sample*3+2,'initialRevision':2,'writerSqlite':settings,'authorWriterSqlite':settings,'savedRevision':3,'finalRevision':3,'contentVerified':True,'retainedRevisions':[1281],'status':'PASS','stage':'complete','failureClass':None,'suppressedFailureClasses':[],'collectorFailureClass':None,'collectorStartNs':ns(0),'collectorEndNs':ns(collector_end),'saveStartNs':ns(90),'saveEndNs':ns(end),'saveMs':float(end-90),'beforeRenameReceiptNs':ns(150),'afterRenameTransactionNs':ns(190+sample),'firstDeleted':first_deleted,'firstDeferred':selected,'firstRootScans':1,'firstCommittedBatches':first_deleted//256,'finishStartNs':ns(finish),'finishEndNs':ns(finish+50),'finishDeleted':1280-first_deleted,'finishRootScans':1,'finishDeferred':False,'deletionTransactionExposed':selected,'deleteWindowIntersected':any(i['saveIntersected'] for i in intervals),'saveStartedDuringDelete':any(i['saveStartedDuringDelete'] for i in intervals),'deleteIntervals':intervals,'batchEvents':batches,'batchDropped':0,'phaseDropped':0,'workersExited':True,'helpersClosed':True,'fixtureDeleted':True,'boundaries':[{'database':d,'count':len(events) if d==db else 0,'dropped':0,'invalid':False} for d in databases],'coveredBeforeBatchCommitNs':[ns(101)] if selected else [],'phases':phases,'authorBoundaries':{'databaseTask':task,'sqliteTransaction':sql,'roomTransaction':room,'writerThread':22,'segments':segments,'sqliteBeginThreadCpuNs':1000,'sqliteEndThreadCpuNs':1000,'pureSqliteLockWaitMs':None}}
            event={'sample':sample,'connection':mode,'database':db,'part':0,'parts':1,'eventSchema':'author-boundary-v1','eventColumns':a.COLUMNS.copy(),'events':events}
            records.extend([{'marker':a.MARKERS[0],'data':event},{'marker':a.MARKERS[1],'data':trial}]);trials.append(trial)
        summary={'scenario':'AFTER_SCAN_ARRIVAL_BOUNDARIES','replacesFrozenProbe':False,'connection':mode,'plannedTrials':30,'recordedTrials':30,'passedTrials':30,'committedSaves':30,'failedTrials':0,'unknownCommitOutcomes':0,'notStartedSaves':0,'functionalStatus':'PASS','exposureStatus':'SUFFICIENT_EXPOSURE' if exposures[mode_index]>=20 else 'INSUFFICIENT_EXPOSURE','minimumExposedSamplesForQuantiles':20,'latencyAcceptance':'NOT_EVALUATED','saveWatchdogMs':5000,'watchdogIsLatencyAcceptance':False,'quantileDefinition':'nearest rank: ceil(p*n)-1, zero based; n=30 p50=14 p95=28','pureSqliteLockWaitMs':None}
        for key,field in [('allSaves',None),('exposedSaves','deletionTransactionExposed'),('deleteWindowIntersectedSaves','deleteWindowIntersected'),('saveStartedDuringDeleteSaves','saveStartedDuringDelete')]:summary[key]=distribution(trials,field)
        records.append({'marker':a.MARKERS[2],'data':summary})
    return records

results=[]
def check(name,records,valid=False):
    outcome=a.analyze(records,'synthetic');good=outcome['status']=='COMPLETE_GC_AUTHOR_BOUNDARY_DIAGNOSTIC_ONLY'
    assert good==valid,(name,outcome['issues'])
    if not valid:assert outcome['modes']=={} and outcome['rawMarkers']==records
    results.append({'case':name,'expected':'ACCEPT_SYNTHETIC_ONLY' if valid else 'REJECT_OR_INCOMPLETE','passed':True,'issues':outcome['issues']});return outcome
baseline=fixture();answer=check('legal20_and19',baseline,True)
assert answer['modes']['same-room']['saveDistributions']['allSaves']['p50Ms']==124
assert answer['modes']['same-room']['saveDistributions']['allSaves']['p95Ms']==138
assert answer['modes']['same-room']['saveDistributions']['allSaves']['maxMs']==139
assert answer['modes']['same-room']['saveDistributions']['exposedSaves']['p50Ms'] is not None
assert answer['modes']['separate-room']['saveDistributions']['exposedSaves']['p50Ms'] is None
assert all(m['allRootScans']==60 for m in answer['modes'].values())
for exposures in [(0,0),(19,19),(20,20),(30,30)]:
    x=check('exposure_'+str(exposures),fixture(exposures),True)
    if exposures==(0,0):assert x['modes']['same-room']['saveDistributions']['exposedSaves']['maxMs'] is None
def extended_author(prelude=False,nested=0):
    x=copy.deepcopy(baseline);es=x[0]['data']['events'];origin=10**12
    def e(stage,t,tx,depth,success,outcome):return [stage,1,1,tx,depth,success,outcome,origin+int(t*M),0,22]
    extra=[]
    if prelude:
        extra=[e('sqlite-begin-enter',116,999,1,False,'entered'),e('sqlite-begin-exit',117,999,1,False,'returned'),e('sqlite-success-enter',117.3,999,1,False,'entered'),e('sqlite-success-exit',117.4,999,1,True,'returned'),e('sqlite-end-enter',118,999,1,True,'entered'),e('sqlite-end-exit',119,999,1,True,'returned')]
    for n in range(nested):
        st=131+n*4
        roles=[('room-begin-enter',1,False,'entered'),('sqlite-begin-enter',2,False,'entered'),('sqlite-begin-exit',2,False,'returned'),('room-begin-exit',1,False,'returned'),('room-success-enter',1,False,'entered'),('sqlite-success-enter',2,False,'entered'),('sqlite-success-exit',2,True,'returned'),('room-success-exit',1,True,'returned'),('room-end-enter',1,True,'entered'),('sqlite-end-enter',2,True,'entered'),('sqlite-end-exit',2,True,'returned'),('room-end-exit',1,True,'returned')]
        extra.extend(e(stage,st+i*.2,tx,2,success,outcome) for i,(stage,tx,success,outcome) in enumerate(roles))
    es=sorted(es+extra,key=lambda row:row[7]) # Construct synthetic causal input, never sort analyzed raw.
    for i,row in enumerate(es):row[8]=(i+1)*1000
    x[1]['data']['boundaries'][0]['count']=len(es)
    parts=[]
    for i,start in enumerate(range(0,len(es),48)):
        d=copy.deepcopy(x[0]);d['data'].update(part=i,parts=math.ceil(len(es)/48),events=es[start:start+48]);parts.append(d)
    return parts+x[1:]
check('actual_pre_sqlite_transaction_selection',extended_author(prelude=True),True)
check('nested_room_sqlite_transactions',extended_author(nested=1),True)
check('prelude_plus_three_nested_and_two_shards',extended_author(prelude=True,nested=3),True)
x=copy.deepcopy(baseline);t=x[1]['data'];t['batchEvents'][2]['atNs']=t['saveStartNs'];t['deleteIntervals'][0]['deleteEndNs']=t['saveStartNs'];t['deleteIntervals'][0]['saveIntersected']=False;t['deleteWindowIntersected']=False
ts=[r['data'] for r in x if r['marker']==a.MARKERS[1] and r['data']['connection']=='same-room']
for key,field in [('allSaves',None),('exposedSaves','deletionTransactionExposed'),('deleteWindowIntersectedSaves','deleteWindowIntersected'),('saveStartedDuringDeleteSaves','saveStartedDuringDelete')]:x[60]['data'][key]=distribution(ts,field)
check('inclusive_start_at_delete_end_but_no_positive_overlap',x,True)
# A natural author commit can invalidate the final empty guard after all five batches.
# Move that synthetic guard beyond the author's committed transaction; never claim a measurement.
x=fixture((0,0));t=x[1]['data'];origin=10**12
for b,at in zip(t['batchEvents'][-2:],[210,211]):
    b.update(atNs=origin+at*M,cpuNs=at*1000,observedChanges=21)
t.update(firstDeferred=True,collectorEndNs=origin+220*M,finishStartNs=origin+230*M,finishEndNs=origin+280*M)
check('full_delete_empty_guard_changed_after_author_commit',x,True)
y=copy.deepcopy(x);y[1]['data']['firstDeferred']=False;check('changed_empty_guard_false_defer',y)
y=copy.deepcopy(x)
for b in y[1]['data']['batchEvents'][-2:]:b['observedChanges']=20
check('matched_empty_guard_true_defer',y)
y=copy.deepcopy(baseline)
for b in y[1]['data']['batchEvents'][-2:]:b['observedDataVersion']=10
y[1]['data']['firstDeferred']=False
check('matched_nonempty_terminal_guard',y)
for i in range(15):
    x=copy.deepcopy(baseline);del x[0]['data']['events'][i];x[1]['data']['boundaries'][0]['count']-=1;check(f'delete_stage_{i}_recounted',x)
    x=copy.deepcopy(baseline);x[0]['data']['events'].insert(i,x[0]['data']['events'][i].copy());x[1]['data']['boundaries'][0]['count']+=1;check(f'duplicate_stage_{i}_recounted',x)
faults={
 'interval_bool_sequence':lambda x:x[1]['data']['deleteIntervals'][0].__setitem__('afterSequence',False),
 'interval_float_candidates':lambda x:x[1]['data']['deleteIntervals'][0].__setitem__('candidates',256.0),
 'retained_float_revision':lambda x:x[1]['data'].__setitem__('retainedRevisions',[1281.0]),
 'covered_float_timestamp':lambda x:x[1]['data']['coveredBeforeBatchCommitNs'].__setitem__(0,float(x[1]['data']['coveredBeforeBatchCommitNs'][0])),
 'boundary_float_count':lambda x:x[1]['data']['boundaries'][0].__setitem__('count',15.0),
 'boundary_bool_dropped':lambda x:x[1]['data']['boundaries'][0].__setitem__('dropped',False),
 'boundary_integer_invalid':lambda x:x[1]['data']['boundaries'][0].__setitem__('invalid',0),
 'wrong_columns':lambda x:x[0]['data']['eventColumns'].reverse(),
 'short_row':lambda x:x[0]['data']['events'][0].pop(),
 'wrong_owner':lambda x:x[0]['data']['events'][3].__setitem__(1,999),
 'wrong_task':lambda x:x[0]['data']['events'][3].__setitem__(2,999),
 'wrong_transaction':lambda x:x[0]['data']['events'][4].__setitem__(3,999),
 'wrong_depth':lambda x:x[0]['data']['events'][4].__setitem__(4,2),
 'wrong_thread':lambda x:x[0]['data']['events'][4].__setitem__(9,77),
 'cpu_backwards':lambda x:x[0]['data']['events'][4].__setitem__(8,0),
 'wall_backwards':lambda x:x[0]['data']['events'][4].__setitem__(7,0),
 'failure_outcome':lambda x:x[0]['data']['events'][4].__setitem__(6,'threw'),
 'failure_trial':lambda x:x[1]['data'].__setitem__('status','FAIL'),
 'drop_overflow':lambda x:x[1]['data']['boundaries'][0].__setitem__('dropped',1),
 'invalid_trace':lambda x:x[1]['data']['boundaries'][0].__setitem__('invalid',True),
 'bad_part':lambda x:x[0]['data'].__setitem__('parts',2),
 'wrong_database':lambda x:x[0]['data'].__setitem__('database','author'),
 'wrong_segment':lambda x:x[1]['data']['authorBoundaries']['segments'][2].__setitem__('elapsedNs',0),
 'wrong_reported_cpu':lambda x:x[1]['data']['authorBoundaries'].__setitem__('sqliteBeginThreadCpuNs',0),
 'wrong_saveMs':lambda x:x[1]['data'].__setitem__('saveMs',1),
 'wrong_delete_overlap':lambda x:x[1]['data'].__setitem__('deleteWindowIntersected',False),
 'wrong_precommit':lambda x:x[1]['data'].__setitem__('coveredBeforeBatchCommitNs',[]),
 'missing_after_delete':lambda x:x[1]['data']['batchEvents'].pop(2),
 'changed_guard':lambda x:x[1]['data']['batchEvents'][1].__setitem__('observedChanges',99),
 'wrong_cleanup_sum':lambda x:x[1]['data'].__setitem__('finishDeleted',0),
 'missing_after_stats':lambda x:x[1]['data']['phases'].pop(1),
 'missing_precommit':lambda x:x[1]['data']['phases'].pop(3),
 'precommit_after_return':lambda x:x[1]['data']['phases'][3].__setitem__('atNs',x[1]['data']['batchEvents'][3]['atNs']+M),
 'missing_terminal_guard':lambda x:x[1]['data']['batchEvents'].__delitem__(slice(4,6)),
 'wrong_deferred_flag':lambda x:x[1]['data'].__setitem__('firstDeferred',False),
 'unknown_trial_field':lambda x:x[1]['data'].__setitem__('extra',1),
 'bool_as_scan_count':lambda x:x[1]['data'].__setitem__('firstRootScans',True),
 'unknown_batch_field':lambda x:x[1]['data']['batchEvents'][0].__setitem__('extra',1),
 'bool_as_author_task':lambda x:x[1]['data']['authorBoundaries'].__setitem__('databaseTask',True),
 'bool_as_sqlite_setting':lambda x:x[1]['data']['writerSqlite'].__setitem__('auto_vacuum',True),
 'invented_lockwait':lambda x:x[1]['data'].__setitem__('pureSqliteLockWaitMs',0),
 'summary_bad_quantile':lambda x:x[60]['data']['allSaves'].__setitem__('p50Ms',1),
 'low_exposure_quantile':lambda x:x[-1]['data']['exposedSaves'].__setitem__('p95Ms',1),
 'summary_wrong_exposure':lambda x:x[60]['data'].__setitem__('exposureStatus','INSUFFICIENT_EXPOSURE'),
 'missing_summary':lambda x:x.pop(),
 'missing_trial':lambda x:x.pop(1),
 'duplicate_summary':lambda x:x.append(copy.deepcopy(x[-1])),
}
for name,mutate in faults.items():x=copy.deepcopy(baseline);mutate(x);check(name,x)
with tempfile.TemporaryDirectory() as td:
    f=Path(td)/'synthetic-markers.log'
    text=''.join(r['marker']+' '+json.dumps(r['data'],separators=(',',':'))+'\n' for r in baseline)
    f.write_text(text);rs,ins=a.parse([f]);check('literal_marker_roundtrip',rs,True)
    for name,content in [('duplicate_json_key','HISTORY_GC_BOUNDARY_TRIAL {"connection":"same-room","connection":"same-room"}\n'),('truncated_marker','HISTORY_GC_BOUNDARY_EVENTS {\n'),('nonfinite','HISTORY_GC_BOUNDARY_TRIAL {"connection":"same-room","x":NaN}\n')]:
        f.write_text(content)
        try:a.parse([f])
        except (ValueError,a.Invalid):results.append({'case':name,'expected':'REJECT_INPUT','passed':True})
        else:raise AssertionError(name)
(HERE/'synthetic-markers.log').write_text(''.join(r['marker']+' '+json.dumps(r['data'],separators=(',',':'))+'\n' for r in baseline))
(HERE/'selftest-results.json').write_text(json.dumps({'scope':'SYNTHETIC_ONLY_NOT_NATIVE','status':'PASS','cases':len(results),'analyzerSha256':hashlib.sha256((HERE/'analyze.py').read_bytes()).hexdigest(),'testSha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),'results':results},indent=2)+'\n')
print(json.dumps({'status':'PASS','scope':'SYNTHETIC_ONLY_NOT_NATIVE','cases':len(results)}))
