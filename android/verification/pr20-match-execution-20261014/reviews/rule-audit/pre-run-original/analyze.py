"""Deterministic descriptive analysis of existing logs only, after device cleanup."""
import csv
import json
import math
import os
from pathlib import Path
import statistics

root=Path(os.environ['KVM_ONCE'])/'evidence';here=Path(__file__).resolve().parent
summary=json.loads((root/'summary.json').read_text());protocol=json.loads((here/'protocol.json').read_text())
bands=protocol['proposed_time_regression_bands'];observations=[];save_rows=[]
def add(entry,key,value,band):
    observations.append({'pair':entry['pair'],'side':entry['side'],'visit':entry['visit'],'metric':key,'value_ms':value,'band':band})
def quantiles(values):
    ordered=sorted(values)
    return {'p50':ordered[math.ceil(len(values)*.5)-1],'p95':ordered[math.ceil(len(values)*.95)-1],'max':ordered[-1]}
def saves(entry,scenario,values,intervals=None,background=None):
    assert len(values)==30
    for kind,value in quantiles(values).items():add(entry,scenario+'.'+kind,value,'save_'+kind)
    for i,value in enumerate(values):
        sample=(intervals or [{}]*len(values))[i]
        start=sample.get('startNs');end=sample.get('endNs')
        overlap=None if start is None or background is None else max(start,background[0])<min(end,background[1])
        save_rows.append({'pair':entry['pair'],'side':entry['side'],'visit':entry['visit'],'scenario':scenario,'sample':i,
                          'elapsed_ms':value,'start_ns':start,'end_ns':end,'background_overlap':overlap})

for entry in summary['methods']:
    if entry['status']!='PASS':continue
    path=root/f"visit-{entry['visit']:02d}-{entry['side']}-{entry['name']}-records.json"
    records=json.loads(path.read_text())
    def one(marker):
        rows=[x['data'] for x in records if x['marker']==marker];assert len(rows)==1,(marker,len(rows));return rows[0]
    if entry['name']=='local-append':
        for item in [x['data'] for x in records if x['marker']=='TILE_LOCAL_EVIDENCE']:
            for key in ['firstCompleteMs','appendCompleteMs','forcedFullCompleteMs']:
                add(entry,f"raster.{item['layers']}.{key}",item[key],'raster_all_phase_times')
    elif entry['name']=='library':
        item=one('LIBRARY_READ_EVIDENCE');direct=one('KVM_DIRECT_INTERVALS');index=one('KVM_INDEX_INTERVALS');observe=item['observeIndexConcurrentSaves']
        add(entry,'library.coldMs',item['coldMs'],'search_and_index_completion')
        saves(entry,'library.direct',item['saveSamplesMs'],direct['saves'],(direct['coldStartNs'],direct['coldEndNs']))
        final_ns=index['batches'][-1]['committedNs']
        saves(entry,'library.observe',observe['saveSamplesMs'],observe['saveProgressSamples'],(index['observeStartNs'],final_ns))
        for key in ['allIndexRowsCommittedMs','searchIndexReadyMs']:
            add(entry,'library.'+key,observe[key],'search_and_index_completion')
        observations.append({'pair':entry['pair'],'side':entry['side'],'visit':entry['visit'],'metric':'library.effective_index_rows_per_second',
                             'value':20000/(observe['allIndexRowsCommittedMs']/1000),'band':None,'scope':'subscribe through committed 20k rows, includes concurrent saves and scheduling'})
    else:
        item=one('HISTORY_COLLECTION_EVIDENCE');intervals=one('KVM_HISTORY_INTERVALS')
        for key in ['collectionMs','liveCollectionAndSavesMs']:add(entry,'history.'+key,item[key],'gc_completion')
        saves(entry,'history.paused',item['pausedScanSaveSamplesMs'],intervals['paused'])
        saves(entry,'history.live',item['liveSaveSamplesMs'],intervals['live'],(intervals['collectionStartNs'],intervals['collectionEndNs']))

comparisons=[]
for key in sorted({x['metric'] for x in observations if x['band']}):
    rows=[x for x in observations if x['metric']==key];pairs=[];band=bands[rows[0]['band']]
    for pair in range(1,5):
        sides={x['side']:x['value_ms'] for x in rows if x['pair']==pair}
        if set(sides)!={'B','C'}:continue
        b,c=sides['B'],sides['C'];delta=c-b;ratio=c/b if b else None
        pairs.append({'pair':pair,'B_ms':b,'C_ms':c,'delta_ms':delta,'ratio_C_to_B':ratio,
                      'over_band':delta>max(band['relative']*b,band['absolute_ms'])})
    if len(pairs)!=4:status='INCOMPLETE'
    elif (sum(p['delta_ms']>0 for p in pairs)>=3 and
          statistics.median(p['ratio_C_to_B']-1 for p in pairs)>band['relative'] and
          statistics.median(p['delta_ms'] for p in pairs)>band['absolute_ms']):status='REGRESSION_SIGNAL'
    elif not any(p['over_band'] for p in pairs):status='WITHIN_PROPOSED_BAND'
    else:status='INCONCLUSIVE'
    comparisons.append({'metric':key,'band':band,'pairs':pairs,'screening_status':status})

with (root/'raw-saves.csv').open('w',newline='') as f:
    writer=csv.DictWriter(f,fieldnames=['pair','side','visit','scenario','sample','elapsed_ms','start_ns','end_ns','background_overlap']);writer.writeheader();writer.writerows(save_rows)
exposure=[]
for visit in range(1,9):
    for scenario in ['library.direct','library.observe','history.live']:
        rows=[x for x in save_rows if x['visit']==visit and x['scenario']==scenario]
        if rows:exposure.append({'visit':visit,'scenario':scenario,'samples':len(rows),'overlapping':sum(x['background_overlap'] is True for x in rows),
                                'no_overlap':sum(x['background_overlap'] is False for x in rows),'tail_subset_reliable':sum(x['background_overlap'] is True for x in rows)>=20})
output={'execution_status':summary['status'],'completed_method_calls':sum(x['status']=='PASS' for x in summary['methods']),
        'planned_method_calls':24,'observations':observations,'comparisons':comparisons,'actual_overlap':exposure,
        'limitation':'Provisional engineering screening only. Final acceptance also requires all 24 raw JUnit results, matched inputs/environments, exact cleanup, and exposure review. Four pairs cannot prove no regression; missing/short overlap cannot close sustained-contention tails.'}
(root/'paired-metrics.json').write_text(json.dumps(output,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'completed':output['completed_method_calls'],'planned':24,'metrics':len(comparisons),'not_final_acceptance':True}))
