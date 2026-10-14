#!/usr/bin/env python3
"""Read-only, one-run GC diagnostic arithmetic; never native execution or acceptance."""
import argparse
import hashlib
import json
import math
import re
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCHEMA = HERE.parent / 'history-marker-schemas.json'
TRIAL = 'HISTORY_GC_CAUSAL_TRIAL'
SUMMARY = 'HISTORY_GC_CAUSAL_SUMMARY'
METHODS = {11: ('same-room', 'sameRoomWriterConditionalDeleteArrival'),
           12: ('separate-room', 'separateRoomWriterConditionalDeleteArrival')}
EXPECTED_TEST_SHA = '4f80b8ce32e1aa2bae1d919b96c06b1897e7a8500c748822efd05119ba87ec4a'


def pairs(items):
    out = {}
    for key, value in items:
        if key in out:
            raise ValueError('Duplicate JSON key: ' + key)
        out[key] = value
    return out


def loads(text):
    def reject(value):
        raise ValueError('Non-finite JSON number: ' + value)
    return json.loads(text, object_pairs_hook=pairs, parse_constant=reject)


def load(path):
    return loads(path.read_text(encoding='utf-8'))


def file_info(path):
    return {'name': path.name, 'bytes': path.stat().st_size,
            'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}


def shape(value, spec, path='$'):
    """Only the frozen contract's small JSON Schema subset; no code from artifacts."""
    types = spec.get('type', [])
    types = [types] if isinstance(types, str) else types
    actual = ('null' if value is None else 'boolean' if type(value) is bool else
              'integer' if type(value) is int else 'number' if type(value) is float else
              'string' if type(value) is str else 'array' if type(value) is list else
              'object' if type(value) is dict else 'unknown')
    if types and actual not in types and not (actual == 'integer' and 'number' in types):
        raise ValueError(f'{path}: expected {types}, got {actual}')
    if 'const' in spec and (type(value) is not type(spec['const']) or value != spec['const']):
        raise ValueError(path + ': const mismatch')
    if 'enum' in spec and value not in spec['enum']:
        raise ValueError(path + ': enum mismatch')
    if value is None:
        return
    if actual == 'object':
        if not set(spec.get('required', [])).issubset(value):
            raise ValueError(path + ': missing required fields')
        if spec.get('additionalProperties') is False and set(value) - set(spec['properties']):
            raise ValueError(path + ': unknown fields')
        for key, item in value.items():
            if key in spec.get('properties', {}):
                shape(item, spec['properties'][key], path + '.' + key)
    elif actual == 'array':
        if not spec.get('minItems', 0) <= len(value) <= spec.get('maxItems', math.inf):
            raise ValueError(path + ': array size')
        for index, item in enumerate(value):
            shape(item, spec['items'], f'{path}[{index}]')
    elif actual in ('integer', 'number'):
        if not math.isfinite(value) or not spec.get('minimum', -math.inf) <= value <= spec.get('maximum', math.inf):
            raise ValueError(path + ': numeric bounds')
    elif actual == 'string':
        if len(value) > spec.get('maxLength', math.inf) or ('pattern' in spec and not re.search(spec['pattern'], value)):
            raise ValueError(path + ': string bounds')


def distribution(rows, quantiles):
    samples = [r['saveMs'] for r in rows]
    ordered = sorted(samples)
    n = len(samples)
    return {'count': n, 'sampleIndices': [r['sample'] for r in rows], 'saveSamplesMs': samples,
            'maxMs': max(samples) if samples else None,
            'p50Ms': ordered[(n + 1) // 2 - 1] if quantiles and n else None,
            'p95Ms': ordered[(95 * n + 99) // 100 - 1] if quantiles and n else None}


def close(a, b):
    return type(a) in (int, float) and type(b) in (int, float) and math.isclose(a, b, rel_tol=1e-12, abs_tol=1e-8)


def equivalent(a, b):
    if type(a) in (int, float) and type(b) in (int, float):
        return close(a, b)
    if type(a) is not type(b):
        return False
    if isinstance(a, dict):
        return a.keys() == b.keys() and all(equivalent(a[k], b[k]) for k in a)
    if isinstance(a, list):
        return len(a) == len(b) and all(equivalent(x, y) for x, y in zip(a, b))
    return a == b


def analyze_trial(row, mode, issues):
    sample = row['sample']
    def check(ok, message):
        if not ok:
            issues.append(f'sample {sample}: {message}')
    check(row['connection'] == mode, 'connection mismatch')
    start, end = row['saveStartNs'], row['saveEndNs']
    bounded = start is not None and end is not None
    check(not bounded or start <= end, 'negative save duration')
    if bounded:
        check(close(row['saveMs'], (end - start) / 1e6), 'saveMs mismatch')
    else:
        check(row['saveMs'] is None, 'saveMs without both endpoints')
    phases, events = row['phases'], row['batchEvents']
    check([p['atNs'] for p in phases] == sorted(p['atNs'] for p in phases), 'phase timestamps not monotonic')
    check([e['atNs'] for e in events] == sorted(e['atNs'] for e in events), 'batch timestamps not monotonic')
    covered = [p['atNs'] for p in phases if p['phase'] == 'before-batch-commit' and bounded and start <= p['atNs'] <= end]
    check(row['coveredBeforeBatchCommitNs'] == covered, 'covered precommit points mismatch')
    check(row['deletionTransactionExposed'] == bool(covered), 'transaction point exposure mismatch')
    check(row['firstRootScans'] == sum(p['phase'] == 'after-roots' for p in phases), 'root scan count mismatch')
    check(row['firstCommittedBatches'] == sum(p['phase'] == 'after-batch' for p in phases), 'committed batch count mismatch')
    intervals = []
    guards = []
    for i, event in enumerate(events):
        matched = event['expectedDataVersion'] == event['observedDataVersion'] and event['expectedChanges'] == event['observedChanges']
        check(event['guardMatched'] == matched, 'guardMatched mismatch')
        if event['phase'] == 'guard-checked':
            guards.append({'afterSequence': event['afterSequence'], 'candidates': event['candidates'],
                           'atNs': event['atNs'], 'matched': matched,
                           'dataVersionDelta': event['observedDataVersion'] - event['expectedDataVersion'],
                           'changesDelta': event['observedChanges'] - event['expectedChanges']})
        if event['phase'] != 'before-delete':
            continue
        seq = event['afterSequence']
        previous = [e for e in events[:i] if e['afterSequence'] == seq and e['phase'] == 'guard-checked']
        check(len(previous) == 1 and previous[0]['guardMatched'] and matched, 'DELETE missing successful guard')
        later = events[i + 1:]
        deleted = [e for e in later if e['afterSequence'] == seq and e['phase'] == 'after-delete']
        returned = [e for e in later if e['afterSequence'] == seq and e['phase'] == 'transaction-returned']
        if row['status'] == 'PASS':
            check(len(deleted) == len(returned) == 1, 'DELETE missing unique end/transaction return')
        delete_end = deleted[0]['atNs'] if deleted else None
        returned_at = returned[0]['atNs'] if returned else None
        interval = {'afterSequence': seq, 'candidates': event['candidates'], 'deleteStartNs': event['atNs'],
                    'deleteEndNs': delete_end, 'transactionReturnedNs': returned_at,
                    'saveStartedDuringDelete': start is not None and delete_end is not None and event['atNs'] <= start <= delete_end}
        intervals.append(interval)
        check(delete_end is None or event['atNs'] <= delete_end, 'negative DELETE interval')
        check(returned_at is None or delete_end is None or delete_end <= returned_at, 'return before DELETE end')
    check(equivalent(row['deleteIntervals'], intervals), 'recorded DELETE intervals differ from batch events')
    overlaps = [x for x in intervals if bounded and x['deleteEndNs'] is not None and
                max(start, x['deleteStartNs']) < min(end, x['deleteEndNs'])]
    started_during = any(x['saveStartedDuringDelete'] for x in intervals)
    # A writer call interval overlapping this instrumented window is not a measurement of pure lock wait.
    passed = row['status'] == 'PASS'
    if passed:
        check(row['committed'] is True and row['saveOutcome'] == 'COMMITTED', 'PASS without Committed')
        check(row.get('initialRevision') == 2 and row.get('savedRevision') == row.get('finalRevision') == 3, 'revision mismatch')
        check(row.get('contentVerified') is True and row.get('retainedRevisions') == [1281], 'content/retained verification missing')
        check(row['failureClass'] is None and row['collectorFailureClass'] is None and not row['suppressedFailureClasses'], 'PASS with failure classes')
        check(row['stage'] == row['authorStage'] == 'complete', 'PASS stage mismatch')
        settings = {'page_size': 1024, 'auto_vacuum': 1, 'journal_mode': 'wal', 'synchronous': 2}
        check(row.get('writerSqlite') == row.get('authorWriterSqlite') == settings, 'writer settings mismatch')
        deleted, finish = row['firstDeleted'], row['finishDeleted']
        check(deleted is not None and 256 <= deleted <= 1280 and deleted % 256 == 0, 'first deletion count invalid')
        check(finish is not None and deleted is not None and deleted + finish == 1280, 'total deletion count invalid')
        check(row['finishDeferred'] is False, 'finish deferred')
        check(row['firstDeferred'] == any(not g['matched'] for g in guards), 'first defer lacks matching guard evidence')
        check(sum(not g['matched'] for g in guards) <= 1, 'more than one mismatch despite immediate defer')
        check(deleted == sum(e['candidates'] for e in events if e['phase'] == 'before-delete'), 'candidate count differs from committed deletion count')
        check(deleted == 256 * row['firstCommittedBatches'], 'batch/delete count mismatch')
        check(row['firstRootScans'] == row['finishRootScans'] == 1, 'PASS not one scan per call')
        check(bounded and row['beforeRenameReceiptNs'] is not None and row['afterRenameTransactionNs'] is not None and
              start <= row['beforeRenameReceiptNs'] <= row['afterRenameTransactionNs'] <= end, 'author phase ordering')
        check(row['authorReadyNs'] is not None and row['collectorStartNs'] is not None and row['authorReadyNs'] <= row['collectorStartNs'], 'author not ready before collection')
        check(row['notificationNs'] is not None and start is not None and row['notificationNs'] <= start, 'save precedes notification')
        if intervals:
            check(row['notificationNs'] is not None and intervals[0]['deleteStartNs'] <= row['notificationNs'], 'notification precedes accepted DELETE batch')
        check(row['finishStartNs'] is not None and row['collectorEndNs'] is not None and end is not None and
              row['finishStartNs'] >= max(row['collectorEndNs'], end), 'finish starts before first call/save completion')
    durations = {}
    for name, a, b in [('firstCollectionMs', 'collectorStartNs', 'collectorEndNs'), ('finishCollectionMs', 'finishStartNs', 'finishEndNs')]:
        durations[name] = (row[b] - row[a]) / 1e6 if row[a] is not None and row[b] is not None else None
        check(durations[name] is None or durations[name] >= 0, 'negative collection duration')
    durations['firstPlusFinishMs'] = sum(durations.values()) if all(v is not None for v in durations.values()) else None
    return {'sample': sample, 'saveMs': row['saveMs'], 'transactionPointExposed': bool(covered),
            'actualDeleteIntervalOverlapped': bool(overlaps), 'saveStartedDuringDelete': started_during,
            'coveredBeforeBatchCommitNs': covered, 'overlappedDeleteSequences': [x['afterSequence'] for x in overlaps],
            'guards': guards, **durations}


def analyze_method(evidence, ordinal, mode, method, schemas, terminal=None, expected=None):
    issues = []
    prefix = f'method-{ordinal:02d}-C1'
    files = {kind: evidence / (prefix + suffix) for kind, suffix in {
        'records': '-records.json', 'raw': '-instrumentation.log', 'forwarding': '-log-forwarding.json', 'junit': '-junit.xml'}.items()}
    out = {'ordinal': ordinal, 'connection': mode, 'selection': 'org.inkweft.data.HistoryDeletionCausalTest#' + method,
           'issues': issues, 'files': {k: file_info(p) for k, p in files.items() if p.is_file()}}
    out['evidenceScope'] = 'SCOPED_FORWARDED_LOG_RECORDS' if terminal is not None else 'LOCAL_RAW_RECORDS_AND_RECEIPTS'
    for key, path in files.items():
        if not path.is_file() and (terminal is None or key == 'records'):
            issues.append('missing ' + key)
    if not files['records'].is_file():
        return out
    try:
        records = load(files['records'])
        if not isinstance(records, list):
            raise ValueError('records must be a list')
        for i, item in enumerate(records):
            if not isinstance(item, dict) or set(item) != {'marker', 'data'} or item['marker'] not in schemas:
                raise ValueError(f'record {i}: invalid wrapper/marker')
            shape(item['data'], schemas[item['marker']], f'record[{i}]')
    except (ValueError, KeyError, TypeError) as error:
        issues.append('records invalid: ' + str(error))
        return out
    rows = [x['data'] for x in records if x['marker'] == TRIAL]
    summaries = [x['data'] for x in records if x['marker'] == SUMMARY]
    out['rawTrials'] = rows
    out['rawSummaries'] = summaries
    if terminal is not None:
        if terminal.get('analysis_status') != 'COMPLETE_SCOPED_CAUSAL_LOG_EVIDENCE' or terminal.get('issues'):
            issues.append('terminal receipt not complete scoped causal log evidence')
        for key, value in expected.items():
            if terminal.get('expected', {}).get(key) != value:
                issues.append('terminal expected identity mismatch: ' + key)
        blocks = [b for b in terminal.get('state', {}).get('blocks', []) if b.get('context', {}).get('ordinal') == ordinal]
        extracted = [x for x in terminal.get('extracted_records', []) if x.get('ordinal') == ordinal]
        if len(blocks) != 1 or len(extracted) != 1:
            issues.append('terminal receipt missing unique block/extraction')
        else:
            block, receipt = blocks[0], extracted[0]
            context = block.get('context', {})
            out['forwardingContext'] = context
            for key, value in {'ordinal': ordinal, 'identity': 'C1', 'selection': out['selection'],
                               'test_source_sha256': EXPECTED_TEST_SHA, 'junit_status': 'PASS', 'adb_exit': 0,
                               'run_id': expected['run_id'], 'source_commit': expected['source_commit'],
                               'workflow_sha': expected['workflow_sha']}.items():
                if context.get(key) != value:
                    issues.append('terminal context mismatch: ' + key)
            if not (block.get('ended') is True and block.get('method_pass') is True):
                issues.append('terminal block/method not closed PASS')
            ordered = block.get('ordered', [])
            if [x.get('sequence') for x in ordered] != list(range(len(records))) or [x.get('marker') for x in ordered] != [x['marker'] for x in records]:
                issues.append('terminal ordered marker list mismatch')
            for key, value in {'path': files['records'].name, 'sha256': out['files']['records']['sha256'],
                               'record_count': len(records), 'block_closed': True, 'method_pass': True, 'whole_run_complete': True}.items():
                if receipt.get(key) != value:
                    issues.append('terminal extraction mismatch: ' + key)
            if context.get('records_sha256') != out['files']['records']['sha256']:
                issues.append('terminal context records SHA mismatch')
    if len(summaries) != 1:
        issues.append('expected one summary')
    if [x['marker'] for x in records] != [TRIAL] * len(rows) + [SUMMARY]:
        issues.append('unexpected marker order')
    if [r['sample'] for r in rows] != list(range(len(rows))):
        issues.append('sample order/gap/duplicate')
    if files['raw'].is_file():
        parsed = []
        for line in files['raw'].read_text().splitlines():
            match = re.search(r'((?:KVM|LIBRARY|HISTORY|TILE|INDEX|RASTER)_[A-Z0-9_]+)(.*)$', line)
            if match:
                try:
                    if match[1] not in schemas or len(match[2].encode()) > 65536:
                        raise ValueError('unknown/oversized marker')
                    parsed.append({'marker': match[1], 'data': loads(match[2].strip())})
                except ValueError as error:
                    issues.append('raw marker: ' + str(error))
        if not equivalent(parsed, records):
            issues.append('raw markers differ from records')
    if files['forwarding'].is_file():
        forwarding = load(files['forwarding'])
        context = forwarding.get('context', {})
        out['forwardingContext'] = context
        for key, expected in {'ordinal': ordinal, 'identity': 'C1', 'selection': out['selection'],
                              'test_source_sha256': EXPECTED_TEST_SHA, 'junit_status': 'PASS', 'adb_exit': 0}.items():
            if context.get(key) != expected:
                issues.append('forwarding context mismatch: ' + key)
        for key, name in [('raw_log_sha256', 'raw'), ('records_sha256', 'records')]:
            if name in out['files'] and context.get(key) != out['files'][name]['sha256']:
                issues.append('forwarding byte hash mismatch: ' + key)
    if files['junit'].is_file():
        try:
            suite = ET.fromstring(files['junit'].read_bytes())
            if suite.attrib.get('tests') != '1' or suite.attrib.get('failures') != '0' or suite.attrib.get('errors') != '0' or list(suite.iter('failure')) or list(suite.iter('error')):
                issues.append('JUnit not one complete PASS')
        except ET.ParseError as error:
            issues.append('JUnit malformed: ' + str(error))
    recomputed = [analyze_trial(r, mode, issues) for r in rows]
    out['recomputedTrials'] = recomputed
    complete = len(rows) == 30 and all(r['status'] == 'PASS' for r in rows)
    timed = [r for r in rows if r['saveMs'] is not None]
    point = [r for r, x in zip(rows, recomputed) if r['saveMs'] is not None and x['transactionPointExposed']]
    overlap = [r for r, x in zip(rows, recomputed) if r['saveMs'] is not None and x['actualDeleteIntervalOverlapped']]
    started = [r for r, x in zip(rows, recomputed) if r['saveMs'] is not None and x['saveStartedDuringDelete']]
    summary = {'recordedTrials': len(rows), 'passedTrials': sum(r['status'] == 'PASS' for r in rows),
               'failedTrials': sum(r['status'] == 'FAIL' for r in rows), 'committedSaves': sum(r['committed'] is True for r in rows),
               'unknownCommitOutcomes': sum(r['committed'] is None for r in rows), 'notStartedSaves': sum(r['saveOutcome'] == 'NOT_STARTED' for r in rows),
               'functionalStatus': 'PASS' if complete else 'FAILED_INCOMPLETE',
               'exposureStatus': 'RUN_INCOMPLETE' if not complete else 'INSUFFICIENT_EXPOSURE' if len(point) < 20 else 'SUFFICIENT_EXPOSURE',
               'allSaves': distribution(timed, complete and len(timed) == 30),
               'exposedSaves': distribution(point, complete and len(point) >= 20)}
    if len(summaries) == 1:
        for key, value in summary.items():
            if not equivalent(summaries[0].get(key), value):
                issues.append('summary mismatch: ' + key)
        if summaries[0]['connection'] != mode:
            issues.append('summary connection mismatch')
    out['recomputedSummary'] = summary
    out['instrumentedDeleteIntervalExposure'] = distribution(overlap, complete and len(overlap) >= 20)
    out['saveStartedDuringDeleteExposure'] = distribution(started, complete and len(started) >= 20)
    out['deleteIntervalExposureStatus'] = ('RUN_INCOMPLETE' if not complete else
        'SUFFICIENT_EXPOSURE' if len(overlap) >= 20 else 'INSUFFICIENT_EXPOSURE')
    metrics = ['firstDeleted', 'finishDeleted', 'firstRootScans', 'finishRootScans', 'firstCommittedBatches']
    out['collection'] = {key: {'rawOrdered': [r[key] for r in rows], 'sumKnown': sum(r[key] for r in rows if r[key] is not None),
                              'unknownCount': sum(r[key] is None for r in rows)} for key in metrics}
    for key in ['firstCollectionMs', 'finishCollectionMs', 'firstPlusFinishMs']:
        values = [x[key] for x in recomputed]
        out['collection'][key] = {'rawOrdered': values, 'sumKnown': sum(x for x in values if x is not None), 'unknownCount': values.count(None)}
    out['firstDeferred'] = {'true': sum(r['firstDeferred'] is True for r in rows),
                            'false': sum(r['firstDeferred'] is False for r in rows), 'unknown': sum(r['firstDeferred'] is None for r in rows)}
    out['guardMismatches'] = [{'sample': x['sample'], **g} for x in recomputed for g in x['guards'] if not g['matched']]
    out['status'] = 'RECORDS_CONSISTENT' if not issues and complete else 'INCOMPLETE_OR_INCONSISTENT'
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', type=Path, required=True, help='Locally verified raw evidence directory; never downloaded here')
    parser.add_argument('--output', type=Path, required=True, help='Scratch output directory, distinct from evidence')
    parser.add_argument('--integrity-receipt', type=Path, help='Optional parent audit receipt; linked, not reinterpreted as native acceptance')
    parser.add_argument('--terminal-analysis-json', type=Path, help='Validated runtime analyze_logs.py receipt for records-only official-log extraction')
    parser.add_argument('--expected-run-id')
    parser.add_argument('--expected-job-id')
    parser.add_argument('--expected-workflow-sha')
    parser.add_argument('--expected-source-commit', default='78a5babeaeb92292026423222d5e08635989198b')
    parser.add_argument('--expected-readiness-git', default='b2e1d9f9de66a26f5a4b122ea6754d77198005f8')
    args = parser.parse_args()
    if args.output.resolve() == args.evidence.resolve() or args.evidence.resolve() in args.output.resolve().parents:
        parser.error('Output must not be inside the input evidence directory')
    if args.terminal_analysis_json and not all((args.expected_run_id, args.expected_job_id, args.expected_workflow_sha)):
        parser.error('Terminal mode requires explicit real run, job and workflow identity; no invented defaults')
    terminal = load(args.terminal_analysis_json) if args.terminal_analysis_json else None
    expected = {'run_id': args.expected_run_id, 'job_id': args.expected_job_id, 'workflow_sha': args.expected_workflow_sha,
                'source_commit': args.expected_source_commit, 'readiness_git': args.expected_readiness_git}
    schema = load(SCHEMA)
    result = {'scope': 'CONDITIONAL_DELETE_ARRIVAL_ONLY', 'status': 'PENDING_INPUT_ANALYSIS',
              'latencyAcceptance': 'NOT_EVALUATED', 'naturalAcceptanceUnchanged': True,
              'externalIntegrity': {'status': 'NOT_ASSESSED_BY_THIS_SCRIPT'}, 'schema': file_info(SCHEMA),
              'definitions': {'transactionPointExposure': 'saveStartNs <= before-batch-commit.atNs <= saveEndNs',
                              'instrumentedDeleteIntervalOverlap': 'max(saveStartNs, deleteStartNs) < min(saveEndNs, deleteEndNs); strictly positive interval intersection',
                              'saveStartedDuringDelete': 'deleteStartNs <= saveStartNs <= deleteEndNs',
                              'quantiles': 'nearest rank ceil(p*n)-1; n30 p50 sorted[14], p95 sorted[28], max sorted[29]; exposed subsets need >=20 and complete30',
                              'collectionTime': 'sum of first call and post-save finish call durations; excludes fixture, interval between calls, and verification',
                              'pureSqliteLockWait': None},
              'limitations': ['A conditional arrival diagnostic is not the natural after-scan distribution or B-to-C1 acceptance.',
                              'Instrumented DELETE windows include observer/clock overhead; they do not identify pure SQL lock wait.',
                              'Precommit point coverage alone does not prove overlap with the DELETE loop.',
                              'End-to-end save time includes scheduling, Room/SQLite acquisition, SQL and commit.',
                              'External run/APK/source/ACL/cleanup integrity is separately audited by the parent; this script does not certify it.',
                              'No pooling with prior 45b/d66 runs or public performance calls.'], 'methods': []}
    if args.terminal_analysis_json:
        result['externalIntegrity']['scopedLogReceipt'] = file_info(args.terminal_analysis_json)
        result['externalIntegrity']['expected'] = expected
        result['externalIntegrity']['artifactBytesAudited'] = False
    if args.integrity_receipt:
        result['externalIntegrity']['providedReceipt'] = file_info(args.integrity_receipt)
    for ordinal, (mode, method) in METHODS.items():
        try:
            result['methods'].append(analyze_method(args.evidence, ordinal, mode, method, schema['markers'], terminal, expected))
        except (ValueError, KeyError, TypeError, OSError, IndexError) as error:
            result['methods'].append({'ordinal': ordinal, 'connection': mode, 'status': 'INCOMPLETE_OR_INCONSISTENT',
                                      'issues': ['analysis input error: ' + str(error)]})
    consistent = all(x.get('status') == 'RECORDS_CONSISTENT' for x in result['methods'])
    result['status'] = 'CONDITIONAL_RECORDS_CONSISTENT_NOT_ACCEPTANCE' if consistent else 'INCOMPLETE_OR_INCONSISTENT'
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / 'gc-analysis.json').write_text(json.dumps(result, indent=2, ensure_ascii=False, allow_nan=False) + '\n')
    text = ['# GC conditional-arrival record analysis', '', result['status'], '',
            'This is diagnostic arithmetic, not natural-load latency acceptance or an artifact-integrity verdict.', '',
            '| Connection | Samples / committed | Precommit point / DELETE interval / start-during-DELETE | All-save p50 / p95 / max ms | Root scans first + finish | Collection first + finish ms |',
            '|---|---:|---:|---|---:|---:|']
    for method in result['methods']:
        if 'recomputedSummary' not in method:
            text.append(f"| {method['connection']} | incomplete | — | — | — | — |")
            continue
        s, c = method['recomputedSummary'], method['collection']
        d = s['allSaves']
        text.append(f"| {method['connection']} | {s['recordedTrials']} / {s['committedSaves']} | {s['exposedSaves']['count']} / {method['instrumentedDeleteIntervalExposure']['count']} / {method['saveStartedDuringDeleteExposure']['count']} | {d['p50Ms']} / {d['p95Ms']} / {d['maxMs']} | {c['firstRootScans']['sumKnown']} + {c['finishRootScans']['sumKnown']} | {c['firstCollectionMs']['sumKnown']} + {c['finishCollectionMs']['sumKnown']} |")
    text.extend(['', 'All raw sample arrays, interval counts, guard deltas, deleted counts and recomputed summaries are retained in gc-analysis.json.', '',
                 'Exposed-subset quantiles are null below 20 or when the 30-trial method is incomplete. Pure SQLite lock wait remains unknown.', '', 'Issues:'])
    for method in result['methods']:
        text.extend(f"- {method['connection']}: {issue}" for issue in method['issues'])
    if not any(x['issues'] for x in result['methods']):
        text.append('- None in the checked records; external run integrity still requires its independent receipt.')
    (args.output / 'gc-analysis.md').write_text('\n'.join(text) + '\n')
    print(json.dumps({'status': result['status'], 'output': str(args.output), 'issues': sum(len(x['issues']) for x in result['methods'])}))
    return 0 if consistent else 2


if __name__ == '__main__':
    raise SystemExit(main())
