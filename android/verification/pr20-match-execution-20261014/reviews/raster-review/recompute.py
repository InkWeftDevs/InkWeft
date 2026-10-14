#!/usr/bin/env python3
"""Read-only independent raster audit of the fixed run 38520196523 compact receipts."""
import collections, hashlib, json, math, pathlib, re, statistics, subprocess
ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = pathlib.Path(__file__).resolve().parent
EX = ROOT/'extracted'
def sha(b): return hashlib.sha256(b).hexdigest()
def load(p): return json.loads(p.read_text())
def eq(a,b):
    if isinstance(a,float) or isinstance(b,float): assert math.isclose(a,b,rel_tol=1e-12,abs_tol=1e-9),(a,b)
    else: assert a==b,(a,b)
a=load(EX/'analysis.json'); frozen=load(EX/'statistics/analysis.json')
assert a['analysis_status']=='COMPLETE_SCOPED_MATCH_LOG_EVIDENCE' and not a['issues']
assert all(v in ('completed','success') for v in a['official_statuses'].values())
f=a['state']['final'];assert f['packaging_complete'] and f['missing_files']==[] and f['cleanup_verified']
assert len(f['methods'])==62 and all(m['status']=='PASS' and m['junit_status']=='PASS' and m['adb_exit']==0 for m in f['methods'])
assert f['device_before_sha256']==f['device_after_sha256']
for c in f['cleanup'].values(): assert c['error_count']==0 and c['exact_match'] and c['before_sha256']==c['after_sha256']
for p,v in a['files'].items():
    data=pathlib.Path(p).read_bytes();eq(sha(data),v['sha256']);eq(len(data),v['bytes'])
raw=(ROOT/'job-decoded.log').read_text(); envelopes=collections.defaultdict(list); raw_methods={}
for line in raw.splitlines():
    if 'INKWEFT_MATCH_RECORD ' in line:
        e=json.loads(line.split('INKWEFT_MATCH_RECORD ',1)[1]);envelopes[e['ordinal']].append(e)
    if 'INKWEFT_MATCH_METHOD ' in line:
        m=json.loads(line.split('INKWEFT_MATCH_METHOD ',1)[1]);assert m['ordinal'] not in raw_methods;raw_methods[m['ordinal']]=m
assert len(raw_methods)==62 and all(m['status']=='PASS' for m in raw_methods.values())
blocks={b['context']['ordinal']:b for b in a['state']['blocks']}; ledgers={e['ordinal']:e for e in a['extracted_records']}
ordinals=list(range(32,55,2))+[60,61,62]
records={};bindings=[];proofs=[]
for n in ordinals:
    b=blocks[n];c=b['context'];e=ledgers[n];data=(EX/e['path']).read_bytes(); rr=json.loads(data)
    assert b['ended'] and b['method_pass'] and e['whole_run_complete'] and e['block_closed'] and e['method_pass']
    eq(sha(data),e['sha256']);eq(len(rr),e['record_count']);eq(len(rr),b['record_count'])
    eq(sha((json.dumps(rr,ensure_ascii=False,separators=(',',':'))+'\n').encode()),c['records_sha256'])
    ee=envelopes[n];eq(len(ee),len(rr));eq(len(b['ordered']),len(rr))
    for i,(record,env,order) in enumerate(zip(rr,ee,b['ordered'])):
        eq(env['sequence'],i);eq(env['identity'],c['identity']);eq(env['marker'],record['marker'])
        payload=env['original_json'].encode();eq(sha(payload),env['payload_sha256']);eq(len(payload),env['payload_bytes']);eq(json.loads(payload),record['data'])
        eq({k:env[k] for k in order},order)
    assert c['process_exit_outcome']=='NATURAL_EXIT' and c['auxiliary_cleanup'] is None
    eq(c['source_commit'],frozen['source_spec']['identities'][c['identity']]['git'])
    eq(c['source_manifest_sha256'],frozen['source_spec']['identities'][c['identity']]['canonical_manifest_sha256'])
    for installed in c['installed_apks']:
        selected=next(x for x in c['selected_apks'] if x['package']==installed['package'])
        eq(selected['sha256'],installed['sha256']);eq(installed['source_apk_sha256'],installed['sha256'])
    records[n]=rr;bindings.append(c);proofs.append(dict(ordinal=n,path=e['path'],sha256=sha(data),records_sha256=c['records_sha256'],original_payload_hashes_verified=True))
assert len({c['environment_sha256'] for c in bindings})==1
for ident in ('B','C0','C1'):
    common=[c for c in bindings if c['identity']==ident and c['ordinal']<60]
    assert len(common)==4
    assert len({json.dumps(c['selected_apks'],sort_keys=True) for c in common})==1
    assert len({c['test_source_sha256'] for c in common})==1
    eq(common[0]['test_source_sha256'],'a916d2470d54f069d1d6071d0b56a15ac6b07d05ab0b033d0aa7d4420fa74338')
rows=[]
for n in range(32,55,2):
    c=blocks[n]['context'];rr=records[n];eq(len(rr),9)
    for j,layer in enumerate((1,4,8)):
        fixture,tile,cleanup=rr[j*3:j*3+3]
        eq([fixture['marker'],tile['marker'],cleanup['marker']],['KVM_PAIR_FIXTURE','TILE_LOCAL_EVIDENCE','KVM_RASTER_CLEANUP'])
        t=tile['data'];cl=cleanup['data'];eq(t['layers'],layer);eq(cl['layers'],layer)
        assert cl['joined'] and cl['resources']['totalBytes']==0 and cl['resources']['inFlightJobs']==0
        assert t['pixelsEqual'] and t['localRebuiltTiles']==1 and t['fullDecoded']==321
        eq(t['fullRebuiltTiles'],48 if layer==8 else 24)
        eq(t['localDecoded'],{1:13,4:21,8:11}[layer] if c['identity']=='B' else 1)
        if c['identity']=='B': assert 'nativeStrokeBuilds' not in t
        else: eq(t['nativeStrokeBuilds'],{1:13,4:21,8:11}[layer])
        eq(fixture['data']['seed'],c['seed']);eq(fixture['data']['writerSqlite'],{'page_size':1024,'auto_vacuum':1,'journal_mode':'wal','synchronous':2})
        rows.append(dict(ordinal=n,identity=c['identity'],seed=c['seed'],**dict(t,nativeStrokeBuilds=t.get('nativeStrokeBuilds')),fixture=fixture['data'],cleanup=cl))
for seed in (4101,4102,4103,4104):
    for layer in (1,4,8):
        trio=[r for r in rows if r['seed']==seed and r['layers']==layer]
        eq(len(trio),3);assert len({r['fixture']['logicalSha256'] for r in trio})==1
# Recompute from records, then compare the frozen output (never use its classifications as inputs).
comparisons=[]
for left,right in [('B','C0'),('B','C1'),('C0','C1')]:
    for layer in (1,4,8):
        for metric in ('firstCompleteMs','appendCompleteMs','forcedFullCompleteMs'):
            pairs=[]
            for seed in (4101,4102,4103,4104):
                lv=next(r[metric] for r in rows if (r['identity'],r['seed'],r['layers'])==(left,seed,layer))
                rv=next(r[metric] for r in rows if (r['identity'],r['seed'],r['layers'])==(right,seed,layer))
                delta=rv-lv;pairs.append(dict(seed=seed,left_ms=lv,right_ms=rv,delta_ms=delta,relative_change=delta/lv,over_band=delta>max(lv*.1,5)))
            med=statistics.median(p['delta_ms'] for p in pairs);rel=statistics.median(p['relative_change'] for p in pairs)
            status='REGRESSION_SIGNAL' if sum(p['delta_ms']>0 for p in pairs)>=3 and rel>.1 and med>5 else ('INCONCLUSIVE' if any(p['over_band'] for p in pairs) else 'WITHIN_PROPOSED_BAND')
            result=dict(comparison=left+'->'+right,metric=f'raster.{layer}.{metric}',band=dict(relative=.1,absolute_ms=5),pairs=pairs,median_delta_ms=med,median_relative_change=rel,screening_status=status)
            old=next(x for x in frozen['comparisons'] if x['comparison']==result['comparison'] and x['metric']==result['metric'])
            for k in ('median_delta_ms','median_relative_change','screening_status'):eq(result[k],old[k])
            for newpair,oldpair in zip(pairs,old['pairs']):
                for k in newpair:eq(newpair[k],oldpair[k])
            comparisons.append(result)
native=[r['data'] for r in records[60]]
eq([r['nativeStrokeBuilds'] for r in native],[2,2,1,0]);eq([r['perTileReferenceBuilds'] for r in native],[24,32,16,0])
assert all(r['pixelsEqual'] and r['tiles']==16 and r['rebuilt']==16 for r in native)
frames=[r['data'] for r in records[61] if r['marker']=='RASTER_PAIRED_EVIDENCE'];eq(len(frames),14);assert all(r['pixelsEqual'] for r in frames)
transforms=[r for r in frames if r['case'].startswith('transform-')];eq(len(transforms),12)
sums={k:sum(r[k] for r in transforms) for k in ('decoded','snapshotReadRows','rebuilt','coldDecoded','coldSnapshotReadRows','coldRebuilt','reusedStrokes','nativeStrokeBuilds')}
transform_summary=dict(count=12,sums=sums,medianCompleteMs=statistics.median(r['completeMs'] for r in transforms),medianColdMs=statistics.median(r['coldMs'] for r in transforms),slowerThanOwnCold=[r['case'] for r in transforms if r['completeMs']>r['coldMs']],interpretation='12 different transformations in one sequence, not repeat samples or an APK baseline comparison; no p95 or significance claim')
dense=[r['data'] for r in records[62]];eq([r['stage'] for r in dense],['fixture-start','fixture-complete','editor-window-complete','frame-start','frame-complete','verified-and-released'])
d=dense[4];assert d['frameElapsedMs']<30000 and d['frameDeadlineMs']==30000
assert all(r['fixtureStrokes']==1201 and r['fixturePoints']==76802 and r['frameDeadlineMs']==30000 for r in dense)
eq(sum(b['rows'] for b in d['snapshotReads']),1201);eq(sum(b['points'] for b in d['snapshotReads']),76802);assert max(b['rows'] for b in d['snapshotReads'])==256
stats={k:(float(v) if '.' in v else int(v)) for k,v in re.findall(r'(\w+)=([\d.]+)',d['publishedStats'])}
source_checks=[]
for n,name in ((60,'NativeStrokeTileReuseTest.kt'),(61,'RasterPerformanceProbe.kt'),(62,'StreamedInkRasterTest.kt')):
    path='android/app/src/androidTest/java/org/inkweft/app/'+name
    source=subprocess.check_output(['git','show',a['expected']['source_commit']+':'+path],cwd='/workspace/InkWeft');eq(sha(source),blocks[n]['context']['test_source_sha256'])
    source_checks.append(dict(ordinal=n,path=path,sha256=sha(source),matches_context=True))
common_source=subprocess.check_output(['git','show','e1a813aa24be20df0901f6aff9554632e1f531b7:android/verification/pr20-kvm-paired-20261013/evidence/probe-KvmLocalAppendPairProbe.kt'],cwd='/workspace/InkWeft')
eq(sha(common_source),'a916d2470d54f069d1d6071d0b56a15ac6b07d05ab0b033d0aa7d4420fa74338')
assert b'jobs.joinAll()' in common_source
limitations=a['limitations']+[
 'Original frozen 10% and 5 ms screening bands retained; four seed pairs are not a significance test.',
 'All pixel equality compares two render paths inside one identity; no cross-APK or physical display capture equivalence claim.',
 'Common stage values are append-only per-layer accumulated intervals, potentially overlapping; do not sum as wall time or attribute forced-full regression from them.',
 'B native construction counters are absent, represented as null; retained Kotlin geometry is not a cross-frame native Stroke cache.',
 'Added60 per-tile oracle is a test reference implementation, not an installed baseline APK.',
 'Added60/61 use best-effort timeout-or-null global job-count cleanup, not captured-worker strict joins; natural process exit is a separate observation.',
 'Added62 strict bounded count/pending/geometry assertions passed, but it does not capture and join owned Job objects.',
 'Historical single-layer append regression and TCG dense deadline failures remain evidence; this KVM run cannot establish their unique causes.',
 'Current B->C1 8-layer forced-full REGRESSION_SIGNAL and C0->C1 8-layer first/full INCONCLUSIVE remain unresolved; no production fix inferred from counts alone.',
 'C1 includes the whole committed delta from d66 including default-off diagnostics and search changes; no causal attribution to lazy Pencil renderer alone.'
]
result=dict(schema=1,status='COMPLETE_INDEPENDENT_RASTER_RECOMPUTATION_NOT_FINAL_ACCEPTANCE',expected=a['expected'],inputs={str(p.relative_to(ROOT)):sha(p.read_bytes()) for p in (EX/'analysis.json',EX/'statistics/analysis.json',ROOT/'job-decoded.log')},scope='fixed run only; raw compact log evidence independently recomputed, no builds/network/tests',source_spec=frozen['source_spec'],bindings=bindings,payload_validation=proofs,source_checks=source_checks,counts=dict(common_calls=12,common_layer_records=36,common_pixel_checks=36,strict_common_cleanup_records=36,comparisons=27,paired_values=108),classification_counts={c:dict(collections.Counter(x['screening_status'] for x in comparisons if x['comparison']==c)) for c in ('B->C0','B->C1','C0->C1')},comparisons=comparisons,common_raw=rows,added60=dict(records=native,method=raw_methods[60],cleanup='best-effort withTimeoutOrNull(5000) global count; not strict owned Job join'),added61=dict(records=frames,transform_summary=transform_summary,sqlite=[r['data'] for r in records[61] if r['marker']=='RASTER_SQLITE_EVIDENCE'][0],method=raw_methods[61],cleanup='best-effort withTimeoutOrNull(5000) global count; not strict owned Job join'),added62=dict(records=dense,publishedStats=stats,freezeTotalMs=sum(b['freezeMs'] for b in d['snapshotReads']),decodeTotalMs=sum(b['decodeMs'] for b in d['snapshotReads']),method=raw_methods[62],cleanup='strict withTimeout(5000) count restoration, pending=false and geometry restoration assertions; not captured Job.join'),other_raster_correctness=[m for m in a['state']['methods'] if 19<=m['ordinal']<=30],limitations=limitations)
(OUT/'report.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
def fmt(x):return f'{x:.6f}'
lines=['# Independent raster review: run 38520196523','',
 'The fixed run completed all 62 methods, but raster performance acceptance is not fully closed. Independent recomputation agrees with all 27 frozen raster classifications and all 108 seed-paired values. B→C1 has eight metrics within the proposed band and one regression signal: 8-layer forced full. C0→C1 has seven within and two inconclusive: 8-layer first/full. No sample was dropped and no threshold changed.','',
 '## Evidence and identity','',
 f"Run `{a['expected']['run_id']}`, job `{a['expected']['job_id']}`, workflow `{a['expected']['workflow_sha']}`, readiness `{a['expected']['readiness_git']}`. Strict parser: `COMPLETE_SCOPED_MATCH_LOG_EVIDENCE`, issues `[]`; official run/job completed successfully; packaging has no missing files and both cleanup receipts succeeded. This is scoped log evidence, not an independent APK/ZIP/JUnit audit.",'',
 'B=`a128bb07e34cb82f00eeafc8e62a47bf7b95b128`, C0=`d66b60d6cf9587f646acb76dd8a00910b73a6d70`, C1=`cb7e14218ac83aa8cf6b9d7906051a10da1a6547`. B includes the disclosed `PreservingOpenHelperFactory.kt` production override from `7949011`; it is a controlled baseline, not an untouched old APK. Common probe bytes are identical (`a916d247…`). The context source-manifest and selected/installed APK hashes match per identity across all calls. Common fixture logical hashes match across the three identities for every seed/layer; writer SQLite is 1024/1/wal/2. All relevant context environment hashes match (`bd8a6a68…`); that does not measure host scheduling or CPU cycles. Full hashes and all contexts are in [report.json](report.json).','',
 'The audit re-hashed the supplied official log and provenance files, verified each selected RECORD original JSON byte count/hash/order against its END ledger, reconstructed canonical records SHA, and checked the extracted compact-file SHA. Source assertions for methods 60–62 were read from the exact C1 git commit and matched the context test hashes. Inputs were not modified.','',
 '## Fixed four-seed screening','',
 'Bands remain 10% and 5 ms. `REGRESSION_SIGNAL` means at least three positive deltas, paired median relative change >10%, and paired median absolute change >5 ms. Otherwise any single pair above max(10%,5 ms) is `INCONCLUSIVE`; the rest are `WITHIN_PROPOSED_BAND`. These are frozen screening labels, not significance estimates. B→C0 is an order-unbalanced ancillary comparison (9/9 within), retained in JSON.','',
 '| Layer / metric | B→C1 median Δ ms / % | Status | C0→C1 median Δ ms / % | Status |','|---|---:|---|---:|---|']
for layer in (1,4,8):
 for metric in ('firstCompleteMs','appendCompleteMs','forcedFullCompleteMs'):
    x=next(c for c in comparisons if c['comparison']=='B->C1' and c['metric']==f'raster.{layer}.{metric}')
    y=next(c for c in comparisons if c['comparison']=='C0->C1' and c['metric']==x['metric'])
    lines.append(f"| {layer} / {metric} | {x['median_delta_ms']:+.6f} / {x['median_relative_change']*100:+.6f}% | {x['screening_status']} | {y['median_delta_ms']:+.6f} / {y['median_relative_change']*100:+.6f}% | {y['screening_status']} |")
lines += ['', 'The 8-layer forced-full B→C1 deltas for seeds 4101–4104 are **−21.328534, +61.678235, +16.397921, +65.572811 ms**. Against C0, all four forced-full deltas are positive: **+23.892312, +35.328744, +12.896216, +43.148379 ms**; the median relative change is 8.926004%, so the unchanged rule returns INCONCLUSIVE. Neither pattern is explained by identical decode/rebuild counts. Do not call it noise or claim the drawing optimization fully accepted.','',
 'The previous full run’s 45b→d66 single-layer append signal (+9.468752 ms/+26.372281%) remains part of the history. This run compares d66→cb7: deltas −16.120839, −18.079657, +0.618621, +1.204933 ms; median −7.751109 ms/−11.901483%, within the band. The latest candidate does not reproduce that screening signal in these four current pairs; that does not identify the old cause or prove lazy Pencil construction alone produced the difference. These runs are not pooled.','',
 '## What the counters and pixels prove','',
 'All 36 local-versus-full pixel comparisons passed. For every seed, B local decoded payloads are 13/21/11 for 1/4/8 layers; both C0 and C1 decode 1/1/1. Each local update rebuilds exactly one tile; each forced full decodes 321 strokes and rebuilds 24/24/48 tiles. Candidate native Stroke constructions remain 13/21/11: old Kotlin payload/geometry is reused, but native Stroke objects are still constructed for the strokes actually drawn in each frame. B has no native counter; its value is null, never zero. These counters establish reduced payload decode work and correct local output, not universal wall-time improvement or cross-frame native reuse.','',
 'All 36 common cleanup rows report joined=true, totalBytes=0 and inFlightJobs=0. The frozen probe captures actual Jobs, cancels/joins them, checks completion and worker/geometry restoration, then emits the receipt. It also tests a destructive visibility update cannot display stale pixels while the replacement is pending and matches a complete reference afterward. Additional methods 19–30 all PASS: cache epochs/head/payload/masks/undo, revision jumps, bounded caches, viewport/zoom readiness, interrupted pan join, clear during pending work, and omitted strokes.','',
 '## Added method 60: native reuse oracle','',
 '| Phase | Candidate native builds | Per-tile reference builds | Rebuilt tiles | Pixels equal |','|---|---:|---:|---:|---|']
for r in native:lines.append(f"| {r['phase']} | {r['nativeStrokeBuilds']} | {r['perTileReferenceBuilds']} | {r['rebuilt']} | true |")
lines += ['', 'The oracle independently executes the prior per-tile construction loop inside the C1 test; it is not the installed B APK. Mixed PEN/HIGHLIGHTER/PENCIL, fractional transforms/zoom, clipping/finite paper, omitted strokes and allowed-only PENCIL states pass whole-bitmap comparisons. Native construction reuse is within the current stroke’s dirty-tile loop. Lazy Pencil creation is exercised by this mixed fixture, but avoided allocation count/heap savings or speed are not measured. Cleanup uses clear plus timeout-or-null global-count polling; PASS alone does not prove a strict owned-Job join here.','',
 '## Added method 61: all 14 frame cases','',
 '| Case | Active ms | Own cold ms | Decode/read | Rebuild active/cold | Native builds | Reused geometry | Pixels |','|---|---:|---:|---:|---:|---:|---:|---|']
for r in frames:lines.append(f"| {r['case']} | {r['completeMs']:.6f} | {r['coldMs']:.6f} | {r['decoded']}/{r['snapshotReadRows']} | {r['rebuilt']}/{r['coldRebuilt']} | {r['nativeStrokeBuilds']} | {r['reusedStrokes']} | true |")
lines += ['', f"Across the 12 different transforms, active decode/read totals are {sums['decoded']}/{sums['snapshotReadRows']}, compared with {sums['coldDecoded']}/{sums['coldSnapshotReadRows']} for independent cold renders; active rebuild/native totals are {sums['rebuilt']}/{sums['nativeStrokeBuilds']}. Descriptive median active/cold duration is {transform_summary['medianCompleteMs']:.6f}/{transform_summary['medianColdMs']:.6f} ms. This is one sequence of differing transforms, not twelve repeated samples and not a B→C1 timing comparison. Initial active rendering and transform-0-2/transform-2-1 are slower than their own cold references and remain in the table.",'',
 'The separated append reads/decodes only two new payloads, reuses 22 old geometries and rebuilds two tiles; its independent cold reference reads/decodes 322 and rebuilds 20. This supports narrow dirty-region reads plus append-safe geometry reuse. Fractional moves/scale still require fresh tile rasterization where exact pixels cannot be reused; preview is explicitly not frameReady/capture-ready. This evidence does not establish hardware display latency or cross-frame native caching. Cleanup has the same best-effort timeout-or-null limitation as method 60.','',
 '## Added method 62: original dense deadline','',
 f"The original 1,201 strokes / 76,802 points fixture passed its unchanged 30,000 ms frame gate in **{d['frameElapsedMs']:.6f} ms**. Fixture and bounded editor-window setup remain outside that gate. The editor initially held only 128 strokes / 8,192 points. Five snapshot batches read 256, 256, 256, 256, 177 rows (all 1,201 strokes) and the output pixel for the final red stroke passed the original check.",'',
 '| Stage | Elapsed ms from test evidence start |','|---|---:|']
for r in dense:lines.append(f"| {r['stage']} | {r['elapsedMs']:.6f} |")
lines += ['', f"Published BuildStats: decoded/native={stats['decoded']}/{stats['nativeStrokeBuilds']}, tiles/rebuilt={stats['tiles']}/{stats['rebuilt']}, metadata={stats['metadataMs']:.6f} ms, raster={stats['rasterMs']:.6f} ms, publication={stats['publicationMs']:.6f} ms, bitmapBytes={stats['bitmapBytes']}, geometryBytes={stats['geometryBytes']}. Snapshot freeze/decode sums are {result['added62']['freezeTotalMs']:.6f}/{result['added62']['decodeTotalMs']:.6f} ms, already inside frame work, not extra time to add. One change callback is recorded.",'',
 'verified-and-released is emitted only after clear, strict withTimeout(5 s) worker-count restoration, pending=false, and geometry-reservation restoration assertions. This proves the original bounded release assertions on this successful path; it is not a captured-Job join nor a full resource-snapshot zero assertion. All added methods exited naturally according to the process receipts; natural exit is a separate fact. Historical software-emulator dense failures remain preserved. One current KVM pass closes this run’s original functional deadline, without isolating the prior timeout cause or proving a cross-version dense speedup.','',
 '## Remaining scope and next minimum evidence','',
 'No evidence supports another production algorithm change from this run alone. The residual 8-layer forced-full regression concerns a cold rebuild; the public marker’s metadata/raster/publication fields describe the local append and sum per-layer intervals that may overlap. They cannot explain the cold-frame regression. The minimum useful follow-up is an independently selected 8-layer forced-full diagnostic using the existing default-off observer: collect each frame’s metadata stage, synchronous native preparation/construction wall plus same-thread CPU, worker-to-publication and enqueue/callback/ready timestamps. Bind source/APK/fixture/environment and compare the same forced-full phase across sources. Keep all samples and the public probe/thresholds unchanged. Only a repeatable work increase or identified redundant synchronous work justifies a targeted fix; scheduling intervals alone do not identify CPU work or an environmental cause.','',
 'The lazy Pencil renderer remains a source-supported elimination of unnecessary eager construction for pen-only frames. This run confirms mixed-pencil output but does not directly count its allocations. Geometry cache, sparse reads and per-stroke tile reuse have explicit work-count/pixel evidence; universal timing improvement, hardware screen behavior, and strict added60/61 owned-worker release remain outside that evidence.','',
 '## Complete common raw ledger (milliseconds)','',
 '| Ordinal/source/seed/layers | First | Append | Forced full | Append metadata | Append raster | Append publication | Decode/native | Local/full rebuild |','|---|---:|---:|---:|---:|---:|---:|---:|---:|']
for r in rows:lines.append(f"| {r['ordinal']}/{r['identity']}/{r['seed']}/{r['layers']} | {r['firstCompleteMs']:.6f} | {r['appendCompleteMs']:.6f} | {r['forcedFullCompleteMs']:.6f} | {r['metadataMs']:.6f} | {r['rasterMs']:.6f} | {r['publicationMs']:.6f} | {r['localDecoded']}/{r['nativeStrokeBuilds'] if r['nativeStrokeBuilds'] is not None else 'null'} | {r['localRebuiltTiles']}/{r['fullRebuiltTiles']} |")
lines += ['', 'Reproduce this read-only audit with `python raster-review/recompute.py` from this evidence root. [Machine-readable report](report.json) retains all contexts, original records, paired values, phase values, cleanup records and limitations. [Strict extraction receipt](../extracted/analysis.json), [frozen statistics](../extracted/statistics/analysis.json), [official decoded log](../job-decoded.log).','']
(OUT/'report.md').write_text('\n'.join(lines))
print(json.dumps(dict(status=result['status'],classification_counts=result['classification_counts'],transform_summary=transform_summary,outputs={p.name:sha(p.read_bytes()) for p in (OUT/'report.md',OUT/'report.json')}),indent=2))
