#!/usr/bin/env python3
"""Offline audit of the frozen synthetic artifact. Never builds or runs Android."""
import csv
import hashlib
import json
import math
from pathlib import Path
import re
import statistics
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
E = ROOT / 'evidence'
OUT = ROOT / 'analysis'
OUT.mkdir(exist_ok=True)
def read(name): return json.loads((E / name).read_text())
def sha(data): return hashlib.sha256(data).hexdigest()
def write(name, value): (OUT / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')
def table(name, rows):
    with (OUT / name).open('w', newline='') as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]), lineterminator='\n'); w.writeheader(); w.writerows(rows)
def quantiles(values):
    a = sorted(values)
    return dict(p50=a[math.ceil(len(a)*.5)-1], p95=a[math.ceil(len(a)*.95)-1], max=a[-1])
def overlap(a, b): return max(a['startNs'], b[0]) < min(a['endNs'], b[1])

manifest = read('manifest.json')
for row in manifest:
    data = (E / row['name']).read_bytes()
    assert len(data) == row['uploaded_bytes'] == row['original_bytes']
    assert sha(data) == row['uploaded_sha256'] == row['original_sha256']
assert read('packaging.json')['complete'] and read('packaging.json')['truncated_files'] == 0
assert set(p.name for p in E.iterdir()) == {r['name'] for r in manifest} | {'manifest.json', 'packaging.json'}
protocol = read('frozen-protocol.json'); summary = read('summary.json'); spec = read('frozen-source-spec.json')
assert sha((E / 'frozen-protocol.json').read_bytes()) == '1065177d7809426d949c4b4b67bd639a47c3fc1943f706a77c2dfabb985e9aba'
assert summary['status'] == 'EXECUTION_COMPLETE_ANALYSIS_PENDING' and summary['cleanup_verified']
assert summary['workflow_sha'] == '371d461cc069f8a090ccc2f844068fae68e0770d'
assert summary['run_attempt'] == '1' and summary['run_id'] == '38379928087'
assert summary['source_spec'] == spec
for item in spec['common_probes']:
    data = (E / ('probe-' + Path(item['path']).name)).read_bytes()
    assert len(data) == item['bytes'] and sha(data) == item['sha256']
assert sha((E / 'baseline-factory.kt').read_bytes()) == spec['baseline_production_override']['sha256']
apks = []
for side, key in [('B', 'baseline_git'), ('C', 'candidate_git')]:
    receipt = read(f'apk-{side}.json')
    assert receipt['base_commit'] == spec[key]
    assert receipt['source_manifest_sha256'] == protocol['source_manifest_sha256'][side]
    assert len(receipt['apks']) == 3
    for a in receipt['apks']:
        apks.append(dict(side=side, path=a['path'], bytes=a['bytes'], sha256=a['sha256']))
        assert a['signature'] == receipt['apks'][0]['signature']
    identity = receipt['apks'][0]['build_identity']
    assert identity['source_commit'] == spec[key] and identity['build_commit'] == summary['workflow_sha'] and not identity['formal']
assert read('apk-B.json')['apks'][0]['mupdf_native'] == read('apk-C.json')['apks'][0]['mupdf_native']
assert len(read('apk-B.json')['apks'][0]['mupdf_native']) == 4
assert read('apk-B.json')['apks'][0]['signature'] == read('apk-C.json')['apks'][0]['signature']
deps = {x['path']: x for x in read('dependencies-C.json')}
assert all(deps[x['path']] == x for x in read('dependencies-B.json'))
assert read('dependencies-B.json') == read('dependencies-C.json')
table('apk-identities.csv', apks)
for tag in ['trap', 'always']:
    receipt = read(f'cleanup-{tag}.json')
    assert receipt == dict(emulator='STOPPED_CONFIRMED', command_groups='STOPPED_CONFIRMED', acl='RESTORED_EXACT', errors=[])
    assert (E / f'kvm-after-{tag}.acl').read_bytes() == (E / 'kvm-before.acl').read_bytes()
initial_env = read('device-before.json'); assert initial_env == read('device-after.json')
for v in range(1, 9):
    for when in ['before', 'after']: assert initial_env == read(f'visit-{v:02d}-environment-{when}.json')

methods = ['local-append', 'library', 'history']
assert [(x['visit'], x['side'], x['name']) for x in summary['methods']] == [(v, s, n) for v, s in enumerate('CBBCBCCB', 1) for n in methods]
metrics = []; saves = []; distributions = []; rasters = []; libraries = []; histories = []; fixtures = {}; evidence_rows = []
def metric(entry, name, value, band):
    metrics.append(dict(pair=entry['pair'], side=entry['side'], visit=entry['visit'], metric=name, value_ms=value, band=band))
def save_distribution(entry, scenario, values, intervals, background=None):
    assert len(values) == len(intervals) == 30 and all(x['committed'] for x in intervals)
    rev_key = 'committedRevision' if 'committedRevision' in intervals[0] else 'revision'
    revisions = [x[rev_key] for x in intervals]
    assert revisions == list(range(revisions[0], revisions[0]+30))
    for i, (value, sample) in enumerate(zip(values, intervals)):
        assert sample['sample'] == i and math.isclose(value, (sample['endNs']-sample['startNs'])/1e6, abs_tol=1e-8)
        saves.append(dict(visit=entry['visit'], pair=entry['pair'], side=entry['side'], scenario=scenario, sample=i,
                          elapsed_ms=value, start_ns=sample['startNs'], end_ns=sample['endNs'], committed=True,
                          revision=revisions[i], background_overlap=None if background is None else overlap(sample, background)))
    q = quantiles(values)
    for kind, value in q.items(): metric(entry, scenario+'.'+kind, value, 'save_'+kind)
    subset = [value for value, sample in zip(values, intervals) if background is not None and overlap(sample, background)]
    subq = quantiles(subset) if subset else dict(p50=None, p95=None, max=None)
    distributions.append(dict(visit=entry['visit'], pair=entry['pair'], side=entry['side'], scenario=scenario, samples=30,
                              **q, overlap_count=None if background is None else len(subset), overlap_p50=subq['p50'],
                              overlap_p95=subq['p95'], overlap_max=subq['max'], overlap_tail_n_at_least_20=len(subset)>=20,
                              started_while_background_active=None if background is None else sum(background[0] <= s['startNs'] < background[1] for s in intervals),
                              crossed_background_end=None if background is None else sum(s['startNs'] < background[1] < s['endNs'] for s in intervals),
                              started_after_background_end=None if background is None else sum(s['startNs'] >= background[1] for s in intervals)))

for entry in summary['methods']:
    assert entry['status'] == entry['junit_status'] == 'PASS'
    prefix = f"visit-{entry['visit']:02d}-{entry['side']}-{entry['name']}"
    raw = (E / (prefix+'-instrumentation.log')).read_text()
    assert sha((E / (prefix+'-instrumentation.log')).read_bytes()) == entry['log_sha256']
    assert re.findall(r'^OK \((\d+) tests?\)\s*$', raw, re.M) == ['1']
    codes = [int(x) for x in re.findall(r'^INSTRUMENTATION_STATUS_CODE: (-?\d+)', raw, re.M)]
    assert codes.count(1) == 1 and 0 in codes and all(x >= 0 for x in codes)
    assert re.search(r'^INSTRUMENTATION_CODE: -1\s*$', raw, re.M)
    cls, method = entry['selection'].split('#')
    assert re.findall(r'^INSTRUMENTATION_STATUS: class=(.*)$', raw, re.M) == [cls]*2
    assert re.findall(r'^INSTRUMENTATION_STATUS: test=(.*)$', raw, re.M) == [method]*2
    assert re.findall(r'^INSTRUMENTATION_STATUS: numtests=(.*)$', raw, re.M) == ['1']*2
    assert 'FAILURES!!!' not in raw and 'INSTRUMENTATION_FAILED' not in raw
    parsed = []
    for line in raw.splitlines():
        m = re.search(r'(KVM_[A-Z_]+|LIBRARY_[A-Z_]+|HISTORY_COLLECTION_EVIDENCE|TILE_LOCAL_EVIDENCE|INDEX_SAVE_PROGRESS) (\{.*\})\s*$', line)
        if m: parsed.append(dict(marker=m[1], data=json.loads(m[2])))
    assert parsed == read(prefix+'-records.json')
    xml = ET.parse(E / (prefix+'-junit.xml')).getroot()
    assert xml.attrib['tests'] == '1' and xml.attrib['failures'] == '0' and len(xml.findall('testcase')) == 1
    assert xml[0].attrib['classname'] == cls and xml[0].attrib['name'] == method
    assert all(x == dict(exit=1, stdout='', stderr='') for x in entry['processes_after'].values())
    def one(marker):
        rows = [x['data'] for x in parsed if x['marker'] == marker]; assert len(rows) == 1; return rows[0]
    fixture = [x['data'] for x in parsed if x['marker'] == 'KVM_PAIR_FIXTURE']
    assert fixture == entry['fixtures'] and len(fixture) == (3 if entry['name'] == 'local-append' else 1)
    for f in fixture:
        assert f['seed'] == entry['seed'] and f['writerSqlite'] == dict(page_size=1024, auto_vacuum=1, journal_mode='wal', synchronous=2)
    key = (entry['pair'], entry['name'])
    if key in fixtures: assert fixtures[key] == fixture
    else: fixtures[key] = fixture
    evidence_rows.append(dict(visit=entry['visit'], side=entry['side'], method=entry['name'], selection=entry['selection'], junit='PASS', fixtures=len(fixture), raw_sha256=entry['log_sha256']))
    if entry['name'] == 'local-append':
        rows = [x['data'] for x in parsed if x['marker'] == 'TILE_LOCAL_EVIDENCE']
        assert [r['layers'] for r in rows] == [1, 4, 8]
        cleanups = [x['data'] for x in parsed if x['marker'] == 'KVM_RASTER_CLEANUP']; assert len(cleanups) == 3
        assert all(x['joined'] and all(x['resources'][k] == 0 for k in ['totalBytes', 'inFlightJobs', 'cacheBytes', 'in_flightBytes', 'activeBytes', 'live_inkBytes']) for x in cleanups)
        assert all(x['resources']['peakTrackedBytes'] <= x['resources']['budgetBytes'] for x in cleanups)
        for item in rows:
            assert item['pixelsEqual'] and item['initialStrokes'] == 320 and item['finalStrokes'] == item['fullDecoded'] == 321
            assert 0 < item['localDecoded'] < 321 and item['localRebuiltTiles'] == 1
            for k in ['firstCompleteMs', 'appendCompleteMs', 'forcedFullCompleteMs']: metric(entry, f"raster.{item['layers']}.{k}", item[k], 'raster_all_phase_times')
            rasters.append(dict(visit=entry['visit'], pair=entry['pair'], side=entry['side'], **{**item, 'nativeStrokeBuilds': item.get('nativeStrokeBuilds')}))
    elif entry['name'] == 'library':
        item = one('LIBRARY_READ_EVIDENCE'); direct = one('KVM_DIRECT_INTERVALS'); index = one('KVM_INDEX_INTERVALS'); obs = item['observeIndexConcurrentSaves']
        assert item['writerSqlite'] == fixture[0]['writerSqlite'] and item['books'] == 500 and item['pages'] == item['coldSourceChecks'] == 20000
        assert item['fullBodyOnDemandMatches'] and obs['indexRowsFinal'] == 20000
        batches = index['batches']; assert [x['rows'] for x in batches] == list(range(64, 20000, 64)) + [20000]
        assert all(b['committedNs'] > a['committedNs'] for a, b in zip(batches, batches[1:]))
        assert abs((batches[-1]['committedNs']-index['observeStartNs'])/1e6-obs['allIndexRowsCommittedMs']) < 1
        save_distribution(entry, 'library.direct', item['saveSamplesMs'], direct['saves'], (direct['coldStartNs'], direct['coldEndNs']))
        save_distribution(entry, 'library.observe', obs['saveSamplesMs'], obs['saveProgressSamples'], (index['observeStartNs'], batches[-1]['committedNs']))
        metric(entry, 'library.coldMs', item['coldMs'], 'search_and_index_completion')
        joint_ms = (max(direct['coldEndNs'], direct['saves'][-1]['endNs'])-direct['coldStartNs'])/1e6
        for k in ['allIndexRowsCommittedMs', 'searchIndexReadyMs']: metric(entry, 'library.'+k, obs[k], 'search_and_index_completion')
        row = dict(visit=entry['visit'], pair=entry['pair'], side=entry['side'], cold_ms=item['coldMs'], header_ms=item['headerReadMs'], header_body_chars=item['headerReturnedBodyChars'],
                   shelf_chars=item['shelfReturnedPreviewChars'], directory_body_chars=item['shelfDirectoryBodyChars'], projection_rows=item['projectionRows'], projection_chars=item['projectionChars'],
                   warm_median_ms=statistics.median(item['warmSamplesMs']), all_index_committed_ms=obs['allIndexRowsCommittedMs'], search_ready_ms=obs['searchIndexReadyMs'],
                   effective_rows_per_second=20000/(obs['allIndexRowsCommittedMs']/1000), first_to_last_incremental_rows_per_second=19936/((batches[-1]['committedNs']-batches[0]['committedNs'])/1e9),
                   cold_and_saves_complete_ms=joint_ms, first_batch_ms=obs['firstCommittedBatchMs'], rows_before_saves=obs['indexRowsBeforeSaves'], rows_after_saves=obs['indexRowsAfterSaves'],
                   saves_with_batch_commit=sum(any(s['startNs'] <= b['committedNs'] <= s['endNs'] for b in batches) for s in obs['saveProgressSamples']),
                   saves_with_source_progress=sum(s['sourceChecksEnd'] > s['sourceChecksStart'] for s in obs['saveProgressSamples']),
                   direct_saves_with_source_progress=sum(s['checksEnd'] > s['checksStart'] for s in direct['saves']),
                   total_observe_source_checks=obs['sourceChecks'], paused_writer_committed=item['writerCompletedDuringPausedSearch'])
        libraries.append(row)
    else:
        item = one('HISTORY_COLLECTION_EVIDENCE'); intervals = one('KVM_HISTORY_INTERVALS')
        assert item['writerSqlite'] == fixture[0]['writerSqlite'] and item['historicalRoots'] == 2000 and item['detachedSources'] == 4096
        assert item['liveFirstPassDeferred'] and intervals['firstDeleted'] == 0 and intervals['finishDeleted'] == 4096 and intervals['finalRevision'] == 61
        save_distribution(entry, 'history.paused', item['pausedScanSaveSamplesMs'], intervals['paused'])
        save_distribution(entry, 'history.live', item['liveSaveSamplesMs'], intervals['live'], (intervals['collectionStartNs'], intervals['collectionEndNs']))
        for k in ['collectionMs', 'liveCollectionAndSavesMs']: metric(entry, 'history.'+k, item[k], 'gc_completion')
        histories.append(dict(visit=entry['visit'], pair=entry['pair'], side=entry['side'], pure_ms=item['collectionMs'], combined_ms=item['liveCollectionAndSavesMs'], root_scans=item['collectionRootScans'],
                              paused_root_scans=item['pausedConcurrentRootScans'], live_and_finish_root_scans=item['liveConcurrentRootScans'], first_deleted=intervals['firstDeleted'], finish_deleted=intervals['finishDeleted'],
                              live_collector_ms=(intervals['collectionEndNs']-intervals['collectionStartNs'])/1e6, finish_collector_ms=(intervals['finishEndNs']-intervals['finishStartNs'])/1e6,
                              saves_during_deleting_finish=sum(overlap(s, (intervals['finishStartNs'], intervals['finishEndNs'])) for s in intervals['live']), final_revision=intervals['finalRevision']))

comparisons = []; paired = []; existing = {x['metric']: x for x in read('paired-metrics.json')['comparisons']}
for name in sorted({m['metric'] for m in metrics}):
    rows = [r for r in metrics if r['metric'] == name]; band = protocol['proposed_time_regression_bands'][rows[0]['band']]; pairs = []
    for pair in range(1, 5):
        values = {r['side']: r['value_ms'] for r in rows if r['pair'] == pair}; b, c = values['B'], values['C']
        p = dict(pair=pair, B_ms=b, C_ms=c, delta_ms=c-b, ratio_C_to_B=c/b, over_band=c-b > max(band['relative']*b, band['absolute_ms'])); pairs.append(p)
        paired.append(dict(metric=name, **p))
    assert pairs == existing[name]['pairs']
    relative = statistics.median(p['ratio_C_to_B']-1 for p in pairs); delta = statistics.median(p['delta_ms'] for p in pairs)
    if sum(p['delta_ms'] > 0 for p in pairs) >= 3 and relative > band['relative'] and delta > band['absolute_ms']: status = 'REGRESSION_SIGNAL'
    elif not any(p['over_band'] for p in pairs): status = 'WITHIN_PROPOSED_BAND'
    else: status = 'INCONCLUSIVE'
    assert status == existing[name]['screening_status']
    comparisons.append(dict(metric=name, B_median_ms=statistics.median(p['B_ms'] for p in pairs), C_median_ms=statistics.median(p['C_ms'] for p in pairs), paired_median_delta_ms=delta,
                            paired_median_percent=relative*100, slower_pairs=sum(p['delta_ms']>0 for p in pairs), over_band_pairs=sum(p['over_band'] for p in pairs), status=status))
assert len(comparisons) == len(existing) == 26 and len(saves) == 960
table('paired-values.csv', paired); table('metrics-summary.csv', comparisons); table('save-distributions.csv', distributions); table('raw-saves-verified.csv', saves)
table('raster-values.csv', rasters); table('library-values.csv', libraries); table('history-values.csv', histories); table('methods-verified.csv', evidence_rows)
write('metrics-summary.json', comparisons)
write('validation.json', dict(status='VERIFIED_WITH_REGRESSION_AND_COVERAGE_GAPS', manifest_files=len(manifest), payload_bytes=sum(r['uploaded_bytes'] for r in manifest), methods=24,
                              junit_tests=24, fixture_records=40, matched_fixture_pairs=20, writer_settings='1024/FULL/WAL/synchronous=2', committed_save_samples=960,
                              apk_receipts=6, apk_binary_rehashed_locally=False, raw_and_derived_records_equal=True, original_and_recomputed_screening_equal=True,
                              original_runner_metrics_recomputed=26, supplementary_direct_joint_time='Derived from existing intervals; descriptive only, excluded from original acceptance metrics',
                              device_environment_snapshots_equal=18, acl_trap_always_exact=True, cleanup_errors=[], regression_metrics=[r['metric'] for r in comparisons if r['status']=='REGRESSION_SIGNAL'],
                              inconclusive_metrics=[r['metric'] for r in comparisons if r['status']=='INCONCLUSIVE'],
                              caveats=['APK binaries intentionally absent; verified runner-generated hash/ELF/signature receipts.', 'Per-run n=30; four pairs are not a population no-regression proof.',
                                       'GC first concurrent pass deletes zero; all deletion happens in finish pass after saves. Sustained delete/write contention unverified.',
                                       'Library cold means first unindexed query after fixture hashing, not OS/file-cache cold.', 'Native pipeline is real Android KVM, not a physical device.',
                                       'Host load and free disk requested by PLAN were not recorded; stage success is not evidence of equal host load.']))
print(json.dumps(dict(methods=24, samples=960, metrics=len(comparisons), regression=[r['metric'] for r in comparisons if r['status']=='REGRESSION_SIGNAL'], inconclusive=[r['metric'] for r in comparisons if r['status']=='INCONCLUSIVE']), ensure_ascii=False))
