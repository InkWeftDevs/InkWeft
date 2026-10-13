#!/usr/bin/env python3
"""Read explicitly selected Android logs; write summaries only below /workspace/scratch.

Never launches Android, Gradle, or subprocesses. Missing / old-format inputs cannot
produce paired performance values. See pr20-closure-evidence/performance-summary-usage.md.
"""
import argparse
import datetime
import hashlib
import json
import math
from pathlib import Path
import re
import statistics

SCRATCH = Path('/workspace/scratch').resolve()
DEFAULT = SCRATCH / 'pr20-closure-evidence'
MARKERS = {'library': 'LIBRARY_READ_EVIDENCE', 'history': 'HISTORY_COLLECTION_EVIDENCE',
           'raster': 'RASTER_PAIRED_EVIDENCE'}
RASTER_CASES = ['initial'] + [f'transform-{cycle}-{step}' for cycle in range(3)
                            for step in range(4)] + ['separated-append']
DEFINITIONS = {
    'library': {'sampleCount': 30, 'p50SortedZeroBasedIndex': 15,
                'p50Definition': 'Upper middle of 30 ordered samples; not average of middle two',
                'p95SortedZeroBasedIndex': 28, 'maxSortedZeroBasedIndex': 29},
    'history': {'sampleCount': 30, 'p50SortedZeroBasedIndex': 14,
                'p50Definition': 'Lower middle of 30 ordered samples; not average of middle two',
                'p95SortedZeroBasedIndex': 28, 'maxSortedZeroBasedIndex': 29},
    'rasterTransforms': {'sampleCount': 12,
                        'medianDefinition': 'Arithmetic mean of sorted positions 5 and 6 (zero based)'},
}
DEFINITIONS['libraryObserveIndexConcurrentSaves'] = dict(DEFINITIONS['library'])


def load_json(path):
    return json.loads(path.read_text())


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def records(raw, marker):
    return [json.loads(value) for value in re.findall(re.escape(marker) + r' (\{[^\n]+\})', raw)]


def number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def sample_check(row, prefix, kind, errors):
    """Inspect a sorted copy; the original input array stays unchanged in measurements."""
    samples = row.get(prefix + 'SamplesMs')
    if not isinstance(samples, list) or len(samples) != 30 or not all(number(v) and v >= 0 for v in samples):
        errors.append(prefix + 'SamplesMs must contain 30 finite nonnegative original-order samples')
        return
    ordered = sorted(samples)
    for suffix, index in [('P50Ms', DEFINITIONS[kind]['p50SortedZeroBasedIndex']),
                          ('P95Ms', 28), ('MaxMs', 29)]:
        reported = row.get(prefix + suffix)
        if not number(reported) or not math.isclose(reported, ordered[index], rel_tol=1e-9, abs_tol=1e-9):
            errors.append(prefix + suffix + ' disagrees with the preserved raw samples')


def observe_index_check(row):
    """Describe progress across measured save windows; never infer absent overlap."""
    group = row.get('observeIndexConcurrentSaves')
    assessment = {'status': 'NOT_RECORDED', 'issues': [], 'progressPerSave': None,
                  'indexRowsAdvancedDuringSaveSeries': None, 'saveWindowsWithIndexProgress': None,
                  'saveWindowsWithSourceProgress': None, 'saveWindowsWithBothProgress': None,
                  'overlapInterpretation': 'No automatic-index/save scenario was recorded'}
    if 'observeIndexConcurrentSaves' not in row:
        return assessment
    errors = assessment['issues']
    if not isinstance(group, dict):
        assessment.update(status='INVALID', issues=['Nested evidence must be an object'])
        return assessment
    sample_check(group, 'save', 'library', errors)
    if group.get('saveCount') != 30:
        errors.append('saveCount must be 30')
    progress = group.get('saveProgressSamples')
    if not isinstance(progress, list) or len(progress) != 30:
        errors.append('saveProgressSamples must contain 30 original-order objects')
    count_keys = ['indexRowsBeforeSaves', 'indexRowsAfterSaves', 'indexRowsFinal', 'sourceChecks']
    if any(type(group.get(k)) is not int or group[k] < 0 for k in count_keys):
        errors.append('Overall progress counters must be nonnegative integers')
    if errors:
        assessment['status'] = 'INVALID'
        return assessment
    before, after, final = (group[k] for k in count_keys[:3])
    if not 0 < before <= after <= final or final != row.get('pages'):
        errors.append('Index counts must advance monotonically from a committed batch to all fixture pages')
    times = [group.get(k) for k in ['firstCommittedBatchMs', 'allIndexRowsCommittedMs', 'searchIndexReadyMs']]
    if not all(number(v) and v >= 0 for v in times) or times != sorted(times):
        errors.append('First committed batch, all committed rows, and saves/index/search joint-completion times must be ordered')
    previous_index, previous_source = before, 0
    advances = []
    for index, sample in enumerate(progress):
        if not isinstance(sample, dict):
            errors.append(f'Save progress sample {index} is not an object')
            continue
        keys = ['indexRowsStart', 'indexRowsEnd', 'sourceChecksStart', 'sourceChecksEnd']
        if any(type(sample.get(k)) is not int or sample[k] < 0 for k in keys):
            errors.append(f'Save progress sample {index} has invalid counters')
            continue
        start_index, end_index, start_source, end_source = (sample[k] for k in keys)
        if not previous_index <= start_index <= end_index <= after:
            errors.append(f'Save progress sample {index} has inconsistent index boundaries')
        if not previous_source <= start_source <= end_source <= group['sourceChecks']:
            errors.append(f'Save progress sample {index} has inconsistent source-check boundaries')
        if sample.get('sample') != index or not number(sample.get('elapsedMs')) or not math.isclose(
                sample['elapsedMs'], group['saveSamplesMs'][index], rel_tol=1e-9, abs_tol=1e-9):
            errors.append(f'Save progress sample {index} differs from the original-order duration array')
        advances.append({'sample': index, 'indexRowsAdvanced': end_index - start_index,
                         'sourceChecksAdvanced': end_source - start_source})
        previous_index, previous_source = end_index, end_source
    if errors:
        assessment['status'] = 'INVALID'
        return assessment
    index_windows = sum(v['indexRowsAdvanced'] > 0 for v in advances)
    source_windows = sum(v['sourceChecksAdvanced'] > 0 for v in advances)
    both_windows = sum(v['indexRowsAdvanced'] > 0 and v['sourceChecksAdvanced'] > 0 for v in advances)
    assessment.update(status='RECORDED', progressPerSave=advances,
                      indexRowsAdvancedDuringSaveSeries=after - before,
                      saveWindowsWithIndexProgress=index_windows,
                      saveWindowsWithSourceProgress=source_windows,
                      saveWindowsWithBothProgress=both_windows)
    if after == before:
        interpretation = 'No index progress during the save series; three-way overlap is not established'
    elif not index_windows:
        interpretation = 'Index progressed only outside the individual measured save windows; three-way overlap is not established'
    elif not both_windows:
        interpretation = 'No save window records both index and source-check progress; three-way overlap is not established'
    else:
        interpretation = 'Both index and source-check counters advanced across some enclosing save windows; this is windowed progress evidence, not simultaneous CPU execution or lock-wait measurement'
    assessment['overlapInterpretation'] = interpretation
    return assessment


def device(receipt):
    cmd = receipt.get('command', [])
    serial = cmd[cmd.index('-s') + 1] if '-s' in cmd and cmd.index('-s') + 1 < len(cmd) else None
    return {'serial': serial, 'environment': receipt.get('environment')}


def nullable_total(rows, key):
    values = [row.get(key) for row in rows]
    return sum(values) if values and all(number(v) for v in values) else None


def read_run(entry, kind, side, manifest_dir):
    log_value = entry.get('logs', {}).get(kind)
    result = {'side': side, 'label': entry.get('label'), 'status': 'NOT_RUN', 'issues': [],
              'writerSqlite': None, 'measurements': None, 'aggregate': None, 'receipt': None}
    if not log_value:
        result['issues'].append('No explicit log selected')
        return result
    log = (manifest_dir / log_value).resolve()
    receipt_path = log.with_suffix('.json')
    result['log'] = str(log)
    result['receiptPath'] = str(receipt_path)
    if not log.is_file() or not receipt_path.is_file():
        result['issues'].append('Log or matching runner receipt is absent')
        return result
    result.update(logSha256=digest(log), receiptSha256=digest(receipt_path))
    try:
        receipt = load_json(receipt_path)
        raw = log.read_text(errors='replace')
        parsed = records(raw, MARKERS[kind])
        settings = records(raw, 'RASTER_SQLITE_EVIDENCE') if kind == 'raster' else parsed
    except (ValueError, TypeError) as error:
        result.update(status='INVALID', issues=[f'Cannot parse input: {error}'])
        return result
    result['receipt'] = {key: receipt.get(key) for key in ['phase', 'selection', 'started_utc', 'seconds',
                          'exit_code', 'expected', 'executed', 'failures', 'passed', 'environment']}
    result['recordedDevice'] = device(receipt)
    result['recordCounts'] = {'measurements': len(parsed), 'sqliteSettings': len(settings)}
    sql = settings[0].get('writerSqlite') if len(settings) == 1 else None
    result['writerSqlite'] = sql
    # A native-writer PRAGMA record is mandatory. Do not silently accept older
    # sqlite/read-connection settings, expected defaults, or absent metadata.
    if not isinstance(sql, dict) or any(k not in sql for k in ['page_size', 'auto_vacuum', 'journal_mode', 'synchronous']):
        result.update(status='FORMAT_UNVERIFIED', issues=['Missing complete primary-writer SQLite settings; measurements excluded'])
        return result
    if sql['page_size'] != 1024 or sql['auto_vacuum'] != 1:
        result.update(status='EXCLUDED_FORMAT', issues=['Only measured page_size=1024 and auto_vacuum=1 (FULL) belong to this new comparison'])
        return result
    errors = result['issues']
    junit = re.findall(r'^OK \((\d+) tests?\)\s*$', raw, re.M)
    if not (receipt.get('passed') is True and receipt.get('executed') == 1 and
            receipt.get('failures') == 0 and receipt.get('exit_code') == 0 and
            junit == ['1'] and 'FAILURES!!!' not in raw and 'RUNNER_TIMEOUT' not in raw):
        errors.append('Raw JUnit and runner receipt do not both confirm exactly one passing test')
    if len(parsed) != (14 if kind == 'raster' else 1):
        errors.append('Unexpected number of complete measurement records')
    result['measurements'] = parsed  # Retain original arrays and all emitted fields verbatim.
    if kind in ('library', 'history') and len(parsed) == 1:
        row = parsed[0]
        expected = {'books': 500, 'pages': 20000, 'saveCount': 30, 'fullBodyOnDemandMatches': True} if kind == 'library' else {
            'historicalRoots': 2000, 'detachedSources': 4096, 'pausedScanSaves': 30, 'baseline': side == 'baseline'}
        for key, value in expected.items():
            if row.get(key) != value:
                errors.append(f'Fixture / assertion mismatch: {key}')
        for prefix in (['save'] if kind == 'library' else ['pausedScanSave', 'liveSave']):
            sample_check(row, prefix, kind, errors)
        if kind == 'library':
            observed = observe_index_check(row)
            result['observeIndexConcurrentSavesAssessment'] = observed
            errors.extend('observeIndexConcurrentSaves: ' + issue for issue in observed['issues'])
            result['indexingProtocolFromEvidenceFields'] = 'automatic-observe' if 'observeIndexConcurrentSaves' in row else 'prior-manual-builder'
    if kind == 'raster':
        if [r.get('case') for r in parsed] != RASTER_CASES:
            errors.append('Raster case order / complete case set differs from the 14-case probe')
        if any(r.get('pixelsEqual') is not True for r in parsed):
            errors.append('Raster output equality not confirmed for every case')
        # Normalize absent optional counters to explicit null, never to zero.
        for row in parsed:
            for key in ['reusedStrokes', 'geometryBytes', 'nativeStrokeBuilds']:
                row.setdefault(key, None)
        transforms = [r for r in parsed if str(r.get('case', '')).startswith('transform-')]
        complete = [r.get('completeMs') for r in transforms]
        result['aggregate'] = {'transformCases': len(transforms),
            'transformMedianCompleteMs': statistics.median(complete) if len(complete) == 12 and all(number(v) for v in complete) else None,
            **{key + 'Total': nullable_total(transforms, key) for key in
               ['decoded', 'snapshotReadRows', 'rebuilt', 'reusedStrokes', 'nativeStrokeBuilds']},
            'coldNativeStrokeBuilds': None,
            'coldNativeStrokeBuildsNote': 'The shared probe does not emit the cold reference native shape counter'}
    result['status'] = 'VERIFIED_NATIVE_RUN' if not errors else 'INVALID_OR_FAILED'
    return result


def change(baseline, candidate):
    return 100 * (candidate / baseline - 1) if number(baseline) and number(candidate) and baseline != 0 else None


def compare(b, c, kind):
    result = {'status': 'UNPAIRED', 'issues': [], 'changesPercent': None}
    if any(x['status'] != 'VERIFIED_NATIVE_RUN' for x in (b, c)):
        result['issues'].append('Two verified native runs are required')
        return result
    if b['writerSqlite'] != c['writerSqlite']:
        result['issues'].append('Actual writer SQLite settings differ')
    bd, cd = b['recordedDevice'], c['recordedDevice']
    if not bd['serial'] or not bd['environment'] or bd != cd:
        result['issues'].append('Matching recorded emulator serial and environment are not established')
    if kind == 'library' and b['indexingProtocolFromEvidenceFields'] != c['indexingProtocolFromEvidenceFields']:
        result['issues'].append('Library probes differ: manual indexing versus automatic observe indexing changes the pre-warm workload')
    if result['issues']:
        return result
    result['status'] = 'PAIRED_RECORDED_ENVIRONMENT'
    if kind == 'raster':
        result['changesPercent'] = [{'case': br['case'], **{key: change(br.get(key), cr.get(key)) for key in
            ['completeMs', 'decoded', 'snapshotReadRows', 'rebuilt', 'nativeStrokeBuilds']}}
            for br, cr in zip(b['measurements'], c['measurements'])]
        result['transformMedianCompleteChangePercent'] = change(b['aggregate']['transformMedianCompleteMs'], c['aggregate']['transformMedianCompleteMs'])
    else:
        keys = ['coldMs', 'warmMedianMs', 'headerReadMs', 'saveP50Ms', 'saveP95Ms', 'saveMaxMs'] if kind == 'library' else [
            'collectionRootScans', 'collectionMs', 'pausedConcurrentRootScans', 'liveConcurrentRootScans',
            'liveCollectionAndSavesMs', 'pausedScanSaveP50Ms', 'pausedScanSaveP95Ms', 'pausedScanSaveMaxMs',
            'liveSaveP50Ms', 'liveSaveP95Ms', 'liveSaveMaxMs']
        result['changesPercent'] = {key: change(b['measurements'][0].get(key), c['measurements'][0].get(key)) for key in keys}
        if kind == 'library':
            bg, cg = (run['measurements'][0].get('observeIndexConcurrentSaves') for run in (b, c))
            result['observeIndexConcurrentSavesChangesPercent'] = {
                key: change(bg.get(key), cg.get(key)) for key in ['saveP50Ms', 'saveP95Ms', 'saveMaxMs',
                    'firstCommittedBatchMs', 'allIndexRowsCommittedMs', 'searchIndexReadyMs']
            } if isinstance(bg, dict) and isinstance(cg, dict) else None
    return result


def summarize(manifest, manifest_path):
    pairs = []
    for requested in manifest.get('pairs', []):
        pair = {'id': requested['id'], 'probes': {}}
        for kind in MARKERS:
            b = read_run(requested.get('baseline', {}), kind, 'baseline', manifest_path.parent)
            c = read_run(requested.get('candidate', {}), kind, 'candidate', manifest_path.parent)
            pair['probes'][kind] = {'baseline': b, 'candidate': c, 'comparison': compare(b, c, kind)}
        pairs.append(pair)
    return {'schema': 1, 'generatedUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'status': 'NOT_RUN' if not pairs else 'SEE_PER_PROBE_STATUS',
        'inputManifest': str(manifest_path), 'inputManifestSha256': digest(manifest_path),
        'provenanceReferences': manifest.get('provenance_references', []),
        'environmentReferences': manifest.get('environment_references', []),
        'sourceAndEnvironmentReferencesNotAutomaticallyAttested': True,
        'requiredFormat': {'page_size': 1024, 'auto_vacuum': 1, 'auto_vacuum_name': 'FULL'},
        'percentileDefinitions': DEFINITIONS, 'pairs': pairs,
        'limits': [
            'Only explicitly selected logs are read. Older 4096/INCREMENTAL or missing-format measurements are excluded.',
            'Matching receipt device/environment and actual SQLite settings do not prove equal display, font scale, timeout multiplier, thermal/load conditions, no overlapping builds, or matching APK/source hashes; inspect referenced environment/provenance evidence.',
            'Native software-emulator observations are not physical-device performance. Passed compilation is never input evidence.',
            'Raw saveSamplesMs, pausedScanSaveSamplesMs and liveSaveSamplesMs retain their original emission/operation order; only copies are sorted to check reported quantiles.',
            'Nested observeIndexConcurrentSaves.saveSamplesMs and saveProgressSamples are a separate second set of 30 original-order saves. Its p50 is sorted[15], p95 sorted[28], max sorted[29]; it is never pooled with the initial cold-read save set.',
            'Automatic-index counters are recorded before/after the series and every save; per-save deltas describe enclosing sampling windows. Zero index or source progress must not be described as proven three-way overlap. Positive deltas do not measure simultaneous execution or SQL lock waiting.',
            'firstCommittedBatchMs and allIndexRowsCommittedMs measure observe-start to the actual committed-batch callbacks. searchIndexReadyMs is sampled only after all 30 saves finish and both allRowsCommitted.await() and observedIndexReady.await() return: it is saves/index/search joint-completion time, not the first zero-pending callback instant or pure index duration.',
            'A manual-builder library probe cannot be paired with the newer automatic-observe probe. Identical raw fixture size does not remove the difference in workload preceding warm queries.',
            'Library p50 uses sorted[15]; GC p50 uses sorted[14]. Neither is the arithmetic median of 30. No samples or medians are pooled across runs or these different probe windows.',
            'Library saves arrive sequentially during the actual cold read; actual overlap differs if a writer blocks. writerCompletedDuringPausedSearch observes a separate 750 ms paused-read window.',
            'GC pausedScanSave samples exclude time waiting to reach the paused root-scan gate; each timed save has a 5 s budget. liveCollectionAndSavesMs includes unpaused collection, 30 serial saves and a subsequent finishing collection; it is not isolated GC duration.',
            'GC collectionMs is the separate uncontended full cleanup; root scan counters count after-roots callbacks, not every SQL statement. observerNotifications is not a save latency.',
            'Library read-scale fields count returned rows/UTF-16 characters, not OS storage reads. Full directory metadata can still be read; on-demand body correctness is separately asserted.',
            'Raster decoded/snapshotReadRows/nativeStrokeBuilds measure different stages. Missing counters and their totals remain null. Each case compares output with a full cold rebuild in the same run; not with the other revision bitmap.',
            'Raster transform median combines twelve distinct specified pan/zoom steps; it is not twelve repeated independent samples. This script does not cover the separate TileLocalRedrawTest or all acceptance tests.',
            'Percent changes are candidate / baseline minus one, times 100; negative values mean reductions for time/work counts. Zero denominators or unavailable counters yield null.',
        ]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, default=DEFAULT / 'performance-summary-input.json')
    parser.add_argument('--output', type=Path, default=DEFAULT / 'paired-performance-summary-new-format.json')
    args = parser.parse_args()
    manifest_path, output = args.manifest.resolve(), args.output.resolve()
    if SCRATCH not in output.parents:
        parser.error('Output must be below /workspace/scratch')
    manifest = load_json(manifest_path)
    if manifest.get('schema') != 1:
        parser.error('Manifest schema must be 1')
    summary = summarize(manifest, manifest_path)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(summary, ensure_ascii=False, indent=2, allow_nan=False) + '\n')
    print(json.dumps({'output': str(output), 'status': summary['status'], 'pairs': len(summary['pairs'])}))


if __name__ == '__main__':
    main()
