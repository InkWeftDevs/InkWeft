#!/usr/bin/env python3
"""Offline analysis of the frozen PR20 candidate3 58-method triple run; never runs commands or accesses a network.

Usage: python pr20-triple-analyze.py --evidence DIR --out NEWDIR
Only common-comparison records enter the 18 original screening metrics. Derived XML
is not independent execution evidence. Input files are never modified.
"""
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
import re
import statistics

SEEDS = (4101, 4102, 4103, 4104)
IDENTITIES = ('B', 'C0', 'C1')
COMPARISONS = (('B', 'C0', 'order-unbalanced ancillary'),
               ('B', 'C1', 'primary total effect'), ('C0', 'C1', 'primary current change'))
PLAN_SHA = 'e1d02f73ed8b417949282a729de8133f16deb2fda47a6ae5ec9f2f3671da9346'
PROTOCOL_SHA = '1065177d7809426d949c4b4b67bd639a47c3fc1943f706a77c2dfabb985e9aba'
SOURCE_SPEC_SHA = 'd8ba1042334d8a6386ed6d1d54fbaad4a00fc6d76acc7fbb39005e6bcf897ba3'
VERSION_ALIASES = {'B': 'controlled_baseline', 'C0': 'previous_candidate', 'C1': 'new_candidate'}
METRICS = {f'raster.{layer}.{phase}': 'raster_all_phase_times'
           for layer in (1, 4, 8) for phase in ('firstCompleteMs', 'appendCompleteMs', 'forcedFullCompleteMs')}
METRICS.update({f'library.{scenario}.{q}': 'save_' + q
                for scenario in ('direct', 'observe') for q in ('p50', 'p95', 'max')})
METRICS.update({f'library.{name}': 'search_and_index_completion'
                for name in ('coldMs', 'allIndexRowsCommittedMs', 'searchIndexReadyMs')})


def quantiles(values):
    assert values and all(isinstance(x, (int, float)) and math.isfinite(x) and x >= 0 for x in values)
    ordered = sorted(values)
    return {'p50': ordered[math.ceil(len(values) * .5) - 1],
            'p95': ordered[math.ceil(len(values) * .95) - 1], 'max': ordered[-1]}


def classify(pairs, band):
    if len(pairs) != 4 or any(p['relative_change'] is None for p in pairs):
        return 'INCOMPLETE'
    if (sum(p['delta_ms'] > 0 for p in pairs) >= 3
            and statistics.median(p['relative_change'] for p in pairs) > band['relative']
            and statistics.median(p['delta_ms'] for p in pairs) > band['absolute_ms']):
        return 'REGRESSION_SIGNAL'
    return 'INCONCLUSIVE' if any(p['over_band'] for p in pairs) else 'WITHIN_PROPOSED_BAND'


def raw_junit_valid(entry, raw):
    cls, method = entry['selection'].split('#')
    codes = [int(x) for x in re.findall(r'^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$', raw, re.M)]
    def fields(key):
        return [x.strip() for x in re.findall(r'^INSTRUMENTATION_STATUS: ' + key + r'=(.*)$', raw, re.M)]
    return (re.findall(r'^OK \((\d+) tests?\)\s*$', raw, re.M) == ['1']
            and codes.count(1) == 1 and 0 in codes and all(x in (0, 1) for x in codes)
            and fields('class') == [cls, cls] and fields('test') == [method, method]
            and fields('numtests') == ['1', '1']
            and re.findall(r'^INSTRUMENTATION_CODE: (-?\d+)\s*$', raw, re.M) == ['-1']
            and 'FAILURES!!!' not in raw and 'INSTRUMENTATION_FAILED' not in raw)


def raw_records(raw):
    records = []
    for line in raw.splitlines():
        match = re.search(r'((?:KVM|LIBRARY|HISTORY|TILE|INDEX)_[A-Z0-9_]+) (\{.*\}|\[.*\])\s*$', line)
        if match:
            try:
                records.append({'marker': match[1], 'data': json.loads(match[2])})
            except json.JSONDecodeError as error:
                records.append({'marker': match[1], 'raw': match[2], 'parse_error': str(error)})
    return records


def analyze(summary, plan, protocol, records_by_ordinal, input_issues=()):
    """Pure calculation entry point; also permits in-memory validation with old records."""
    issues = list(input_issues)
    observations, raw_saves, distributions, library, raster, fixtures = [], [], [], [], [], []
    diagnostic_records, methods = [], []
    expected = {x['ordinal']: x for x in plan['steps']}
    actual = summary.get('methods', [])
    if len(actual) != 58 or {x.get('ordinal') for x in actual} != set(range(1, 59)):
        issues.append('Expected exactly 58 methods with ordinal set 1..58')
    if (plan.get('instrumentation_calls') != 58 or plan.get('expected_junit_methods') != 58
            or len(plan.get('steps', [])) != 58 or set(expected) != set(range(1, 59))
            or [sum(x.get('phase') == phase for x in actual) for phase in
                ('correctness', 'common-comparison', 'candidate-diagnostics')] != [31, 24, 3]):
        issues.append('Frozen 58-method / 31-24-3 phase contract mismatch')
    valid_common = set()

    def one(records, marker):
        values = [x['data'] for x in records if x.get('marker') == marker and 'data' in x]
        assert len(values) == 1, (marker, 'expected one record', len(values))
        return values[0]

    for entry in actual:
        ordinal = entry.get('ordinal')
        base = {k: entry.get(k) for k in ('ordinal', 'phase', 'identity', 'install_group', 'selection', 'seed')}
        base.update(version_alias=VERSION_ALIASES.get(entry.get('identity')),
                    source_commit=summary.get('source_spec', {}).get('identities', {}).get(entry.get('identity'), {}).get('git'))
        methods.append(dict(base, status=entry.get('status'), junit_status=entry.get('junit_status'),
                            adb_exit=entry.get('adb_exit'), seconds=entry.get('seconds')))
        matches_plan = ordinal in expected and all(entry.get(k) == v for k, v in expected[ordinal].items())
        if not matches_plan:
            issues.append(f'Method {ordinal}: frozen plan identity mismatch')
        records = records_by_ordinal.get(ordinal, [])
        if entry.get('phase') != 'common-comparison':
            if entry.get('phase') == 'candidate-diagnostics':
                diagnostic_records.append(dict(base, status=entry.get('status'), records=records,
                    scope='Candidate-only raw evidence; no comparison bands or phase-causality inference'))
            continue
        if not matches_plan or entry.get('status') != 'PASS':
            continue
        try:
            assert not any('parse_error' in x for x in records), 'Malformed marker'
            local = '.KvmLocalAppendPairProbe#' in entry['selection']
            fs = [r['data'] for r in records if r.get('marker') == 'KVM_PAIR_FIXTURE']
            assert len(fs) == (3 if local else 1), 'Fixture record count'
            assert all(f['seed'] == entry['seed'] for f in fs), 'Fixture seed mismatch'
            for f in fs:
                assert f['writerSqlite'] == {'page_size': 1024, 'auto_vacuum': 1, 'journal_mode': 'wal', 'synchronous': 2}
            # Assemble method-local outputs, publishing them only after all checks pass.
            obs, rs, ds, libs, rasters = [], [], [], [], []
            def add(key, value):
                assert key in METRICS and isinstance(value, (int, float)) and math.isfinite(value) and value >= 0
                obs.append(dict(base, metric=key, value_ms=value, band=METRICS[key]))
            if local:
                tiles = [r['data'] for r in records if r.get('marker') == 'TILE_LOCAL_EVIDENCE']
                clean = [r['data'] for r in records if r.get('marker') == 'KVM_RASTER_CLEANUP']
                assert sorted(t['layers'] for t in tiles) == [1, 4, 8]
                assert sorted(t['layers'] for t in clean) == [1, 4, 8]
                for t in tiles:
                    c = next(x for x in clean if x['layers'] == t['layers'])
                    assert t['pixelsEqual'] is True and c['joined'] is True
                    assert c['resources']['totalBytes'] == 0 and c['resources']['inFlightJobs'] == 0
                    for phase in ('firstCompleteMs', 'appendCompleteMs', 'forcedFullCompleteMs'):
                        add(f"raster.{t['layers']}.{phase}", t[phase])
                    rasters.append(dict(base, **t, nativeStrokeBuildsAvailable='nativeStrokeBuilds' in t,
                                        cleanup=c, pixel_scope='Local versus forced full within this identity; no cross-version hash'))
            else:
                item = one(records, 'LIBRARY_READ_EVIDENCE')
                direct = one(records, 'KVM_DIRECT_INTERVALS')
                index = one(records, 'KVM_INDEX_INTERVALS')
                observe = item['observeIndexConcurrentSaves']
                assert item['writerSqlite'] == fs[0]['writerSqlite'], 'Writer settings changed'
                assert (item['books'], item['pages']) == (500, 20000)
                assert len(item['warmSamplesMs']) == 5
                quantiles(item['warmSamplesMs'])  # Validate values; warm has no acceptance band.
                assert statistics.median(item['warmSamplesMs']) == item['warmMedianMs']
                batches = index['batches']
                assert len(batches) == 313 and [b['rows'] for b in batches] == list(range(64, 20000, 64)) + [20000]
                assert all(a['committedNs'] <= b['committedNs'] for a, b in zip(batches, batches[1:]))
                assert observe['indexRowsFinal'] == 20000
                add('library.coldMs', item['coldMs'])
                for name in ('allIndexRowsCommittedMs', 'searchIndexReadyMs'):
                    add('library.' + name, observe[name])
                for scenario, values, intervals, start, end in (
                    ('direct', item['saveSamplesMs'], direct['saves'], direct['coldStartNs'], direct['coldEndNs']),
                    ('observe', observe['saveSamplesMs'], observe['saveProgressSamples'], index['observeStartNs'], batches[-1]['committedNs'])):
                    assert len(values) == len(intervals) == 30 and end >= start
                    qs = quantiles(values)
                    for q, value in qs.items():
                        add('library.' + scenario + '.' + q, value)
                    previous_revision = None
                    for i, (value, sample) in enumerate(zip(values, intervals)):
                        assert sample['sample'] == i and sample['committed'] is True
                        a, b = sample['startNs'], sample['endNs']
                        assert b >= a and math.isclose(value, (b-a)/1e6, rel_tol=1e-9, abs_tol=1e-6)
                        assert math.isclose(value, sample['elapsedMs'], rel_tol=1e-9, abs_tol=1e-6)
                        revision = sample['committedRevision']
                        assert previous_revision is None or revision == previous_revision + 1
                        previous_revision = revision
                        overlap = max(a, start) < min(b, end)
                        rs.append(dict(base, scenario=scenario, sample=i, elapsed_ms=value, start_ns=a, end_ns=b,
                            committed=True, committed_revision=revision, background_start_ns=start, background_end_ns=end,
                            background_overlap=overlap, wholly_within_background=a >= start and b <= end,
                            index_batch_commits_during_save=sum(a <= x['committedNs'] <= b for x in batches) if scenario == 'observe' else None,
                            source_checks_start=sample.get('checksStart', sample.get('sourceChecksStart')),
                            source_checks_end=sample.get('checksEnd', sample.get('sourceChecksEnd')),
                            index_rows_start=sample.get('indexRowsStart'), index_rows_end=sample.get('indexRowsEnd')))
                    exposed = [r['elapsed_ms'] for r in rs if r['scenario'] == scenario and r['background_overlap']]
                    ds.append(dict(base, scenario=scenario, count=30, **qs, overlapping=len(exposed),
                        exposed_max=max(exposed) if exposed else None,
                        exposed_quantiles=quantiles(exposed) if len(exposed) >= 20 else None,
                        exposure_status='DESCRIPTIVE_SUBSET_ONLY' if len(exposed) >= 20 else 'INSUFFICIENT_EXPOSURE',
                        limitation='Interval overlap/progress is not SQLite lock wait; samples are not independent trials'))
                libs.append(dict(base, evidence=item, direct_intervals=direct, index_intervals=index,
                    first_64_ms=observe['firstCommittedBatchMs'], index_rows_per_second=20000/(observe['allIndexRowsCommittedMs']/1000),
                    warm_samples_ms=item['warmSamplesMs'], warm_median_ms=statistics.median(item['warmSamplesMs']),
                    direct_joint_ms=(max(direct['coldEndNs'], max(s['endNs'] for s in direct['saves']))-direct['coldStartNs'])/1e6,
                    scope='Warm/first64/throughput/direct joint are descriptive, not extra original screening metrics'))
            fixtures.append(dict(base, fixtures=fs))
            observations.extend(obs); raw_saves.extend(rs); distributions.extend(ds); library.extend(libs); raster.extend(rasters)
            valid_common.add(ordinal)
        except (AssertionError, KeyError, TypeError, ValueError, ZeroDivisionError, StopIteration) as error:
            issues.append(f'Method {ordinal}: invalid or incomplete common evidence: {error}')

    # Match all three actual logical inputs within each seed and common method.
    for seed in SEEDS:
        for kind in ('KvmLocalAppendPairProbe', 'KvmLibraryPairProbe'):
            rows = [x for x in fixtures if x['seed'] == seed and kind in x['selection']]
            if len(rows) != 3 or {x['identity'] for x in rows} != set(IDENTITIES):
                issues.append(f'{seed}/{kind}: incomplete three-way fixture comparison')
            elif any(x['fixtures'] != rows[0]['fixtures'] for x in rows[1:]):
                issues.append(f'{seed}/{kind}: fixture hash/rows/writer mismatch')
    execution_complete = (summary.get('status') == 'EXECUTION_COMPLETE_ANALYSIS_PENDING'
                          and len(actual) == 58 and all(x.get('status') == 'PASS' for x in actual)
                          and summary.get('cleanup_verified') is True and len(valid_common) == 24)
    complete = execution_complete and not issues
    comparisons, pair_rows, warm = [], [], []
    bands = protocol['proposed_time_regression_bands']
    for left, right, role in COMPARISONS:
        for metric, band_name in METRICS.items():
            band = bands[band_name]; pairs = []
            for seed in SEEDS:
                values = {side: [r['value_ms'] for r in observations if r['seed'] == seed and r['identity'] == side and r['metric'] == metric]
                          for side in (left, right)}
                if any(len(v) != 1 for v in values.values()):
                    continue
                a, b = values[left][0], values[right][0]; delta = b-a
                pairs.append({'seed': seed, 'left_ms': a, 'right_ms': b, 'delta_ms': delta,
                              'relative_change': b/a-1 if a else None,
                              'over_band': delta > max(band['relative']*a, band['absolute_ms'])})
            observed = classify(pairs, band)
            result = {'comparison': left+'->'+right, 'role': role, 'metric': metric, 'band': band, 'pairs': pairs,
                      'left_alias': VERSION_ALIASES[left], 'right_alias': VERSION_ALIASES[right],
                      'left_commit': summary['source_spec']['identities'][left]['git'],
                      'right_commit': summary['source_spec']['identities'][right]['git'],
                      'observed_screening_status': observed,
                      'screening_status': observed if complete else 'INCOMPLETE',
                      'median_delta_ms': statistics.median(p['delta_ms'] for p in pairs) if len(pairs) == 4 else None,
                      'median_relative_change': statistics.median(p['relative_change'] for p in pairs) if len(pairs) == 4 and all(p['relative_change'] is not None for p in pairs) else None}
            comparisons.append(result)
            pair_rows.extend(dict(comparison=left+'->'+right, metric=metric,
                                  left_alias=result['left_alias'], right_alias=result['right_alias'],
                                  left_commit=result['left_commit'], right_commit=result['right_commit'], **p) for p in pairs)
        for seed in SEEDS:
            a = [x for x in library if x['seed'] == seed and x['identity'] == left]
            b = [x for x in library if x['seed'] == seed and x['identity'] == right]
            if len(a) == len(b) == 1:
                av, bv = a[0]['warm_median_ms'], b[0]['warm_median_ms']
                warm.append({'comparison': left+'->'+right, 'seed': seed, 'left_raw_ms': a[0]['warm_samples_ms'],
                    'left_alias': VERSION_ALIASES[left], 'right_alias': VERSION_ALIASES[right],
                    'left_commit': a[0]['source_commit'], 'right_commit': b[0]['source_commit'],
                    'right_raw_ms': b[0]['warm_samples_ms'], 'left_median_ms': av, 'right_median_ms': bv,
                    'delta_ms': bv-av, 'relative_change': bv/av-1 if av else None, 'status': 'DESCRIPTIVE_NO_PREDECLARED_BAND'})
    return {'schema': 1, 'run_id': summary.get('run_id'), 'workflow_sha': summary.get('workflow_sha'),
            'source_spec': summary.get('source_spec'), 'execution_status': summary.get('status'),
            'version_aliases': {key: {'alias': VERSION_ALIASES[key], 'source_commit': summary['source_spec']['identities'][key]['git']} for key in IDENTITIES},
            'analysis_status': 'COMPLETE_SCREENING_NOT_FINAL_ACCEPTANCE' if complete else 'INCOMPLETE',
            'original_metric_count': 18, 'comparison_count': 54, 'planned_common_calls': 24,
            'valid_common_calls': len(valid_common), 'planned_save_samples': 720, 'parsed_save_samples': len(raw_saves),
            'issues': issues, 'methods': methods, 'comparisons': comparisons, 'paired_values': pair_rows,
            'raw_saves': raw_saves, 'save_distributions': distributions, 'library': library, 'warm_comparisons': warm,
            'raster': raster, 'fixtures': fixtures, 'candidate_diagnostics_raw': diagnostic_records,
            'limitations': [
                'Only the 24 common calls enter 18 original metrics; old GC and candidate-only diagnostics are excluded.',
                'Four seed blocks are the comparison units; do not pool 720 saves or warm repetitions into independent trials.',
                'B->C0 has 3/1 order imbalance and is ancillary. C1 versus B/C0 is counterbalanced.',
                'Cold means unindexed first query after fixture hashing, not cold OS/file caches.',
                'Warm has no separately predeclared numeric band. Native counter absence is null/absent, never zero.',
                'No sustained-contention tail claim from unequal or insufficient overlap; no causal lock/CPU inference.',
                'Natural GC and stage diagnostics remain unmodified candidate-only raw evidence; inspect partial/unknown commit states.',
                'Incomplete execution or input issues override acceptance status; observed numerical signals remain visible.',
                'Independent APK/source/environment/privacy/ACL verification remains required; XML is derived, not a second run.']}


def write_csv(path, rows):
    keys = list(dict.fromkeys(key for row in rows for key in row))
    with path.open('w', newline='', encoding='utf-8') as stream:
        writer = csv.DictWriter(stream, fieldnames=keys)
        writer.writeheader()
        for row in rows:
            writer.writerow({k: json.dumps(v, ensure_ascii=False, separators=(',', ':')) if isinstance(v, (list, dict)) else v for k, v in row.items()})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', required=True, type=Path)
    parser.add_argument('--out', required=True, type=Path)
    args = parser.parse_args()
    if not all(re.fullmatch(r'[0-9a-f]{64}', value) for value in (PLAN_SHA, PROTOCOL_SHA, SOURCE_SPEC_SHA)):
        parser.error('Finalize all frozen hashes before reading or publishing a new analysis')
    if args.out.exists():
        parser.error('--out must be a new directory; no existing evidence/output is overwritten')
    source = args.evidence.resolve(); output = args.out.resolve()
    if output == source or source in output.parents:
        parser.error('--out must be outside the input evidence directory')
    issues, provenance = [], []
    def read(name, expected_hash=None):
        path = source/name; data = path.read_bytes(); digest = hashlib.sha256(data).hexdigest()
        provenance.append({'name': name, 'bytes': len(data), 'sha256': digest})
        if expected_hash and digest != expected_hash:
            issues.append(name + ': frozen hash mismatch')
        return json.loads(data)
    summary = read('summary.json')
    plan = read('frozen-method-plan.json', PLAN_SHA)
    protocol = read('frozen-original-protocol.json', PROTOCOL_SHA)
    source_spec = read('frozen-source-spec.json', SOURCE_SPEC_SHA)
    if summary.get('source_spec') != source_spec:
        issues.append('summary source identities differ from frozen source-spec')
    records = {}
    for entry in summary.get('methods', []):
        ordinal = entry.get('ordinal'); identity = entry.get('identity')
        prefix = f'method-{ordinal:02d}-{identity}'
        rp = source/(prefix+'-records.json')
        if rp.exists():
            records[ordinal] = read(rp.name)
        elif entry.get('status') == 'PASS':
            issues.append(prefix + ': PASS but records missing')
        lp = source/(prefix+'-instrumentation.log')
        if lp.exists():
            data = lp.read_bytes(); sha = hashlib.sha256(data).hexdigest()
            provenance.append({'name': lp.name, 'bytes': len(data), 'sha256': sha})
            if entry.get('log_sha256') and sha != entry['log_sha256']:
                issues.append(prefix + ': raw log hash mismatch')
            raw = data.decode('utf-8')
            parsed = raw_records(raw)
            if rp.exists() and parsed != records.get(ordinal):
                issues.append(prefix + ': stored records differ from original raw markers')
            if entry.get('status') == 'PASS' and (entry.get('adb_exit') != 0 or entry.get('junit_status') != 'PASS'
                                                or not raw_junit_valid(entry, raw)):
                issues.append(prefix + ': raw JUnit identity/result mismatch')
        elif entry.get('status') == 'PASS':
            issues.append(prefix + ': PASS but raw JUnit missing')
    result = analyze(summary, plan, protocol, records, issues)
    result['analysis_script_sha256'] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    result['input_provenance'] = provenance
    output.mkdir(parents=True)
    for name, rows in [('raw-saves', result['raw_saves']), ('save-distributions', result['save_distributions']),
                       ('paired-values', result['paired_values']), ('warm-values', result['warm_comparisons']),
                       ('raster-values', result['raster']), ('library-values', result['library']), ('methods', result['methods'])]:
        write_csv(output/(name+'.csv'), rows)
    (output/'analysis.json').write_text(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False)+'\n')
    (output/'candidate-diagnostics-raw.json').write_text(json.dumps(result['candidate_diagnostics_raw'], ensure_ascii=False, indent=2)+'\n')
    print(json.dumps({k: result[k] for k in ['analysis_status', 'original_metric_count', 'comparison_count', 'valid_common_calls', 'parsed_save_samples', 'issues']}))


if __name__ == '__main__':
    main()
