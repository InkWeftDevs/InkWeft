"""Independent synthetic review; no Android/device data or performance claims."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
import analyze as a
import test_analyze as supplied


def payload(markers, mode, kind):
    return next(m['value'] for m in markers if m['value']['mode'] == mode and m['kind'] == kind)


def reads(markers, mode='ACTIVE_SEARCH'):
    return payload(markers, mode, 'READS')['events']


def update_reads(markers, rows, mode='ACTIVE_SEARCH'):
    payload(markers, mode, 'READS')['events'] = rows
    payload(markers, mode, 'END')['recordedReadEvents'] = len(rows)


def remove_connection_lifecycle(markers):
    names = {'read-requested', 'read-start', 'connection-open', 'connection-closed'}
    update_reads(markers, [r for r in reads(markers) if a.READ_STAGES[r[1]] not in names])


def snapshot_closed_on_other_thread(markers):
    next(r for r in reads(markers) if a.READ_STAGES[r[1]] == 'snapshot-closed')[6] = 77


def extra_control_hold(markers):
    rows = reads(markers)
    rows.append([0, a.READ_STAGES.index('hold-enter'), 0, -1, 3000000, 1000, 3])
    update_reads(markers, rows)


def unsubmitted_worker_exit(markers):
    parts = [m['value'] for m in markers if m['value']['mode'] == 'IDLE' and m['kind'] == 'EVENTS']
    assert len(parts[-1]['events']) < 96
    parts[-1]['events'].append([a.STAGES.index('writer-run-exit'), -1, 900000, 0, 0, 0, 1,
                               3000000, 1000000, 2])
    payload(markers, 'IDLE', 'END')['recordedEvents'] += 1


def change_control_read_id(markers, mode):
    next(row for row in reads(markers, mode) if row[0] == 0)[2] = 1


def change_read_row(markers, stage, index, value, mode='ACTIVE_SEARCH'):
    next(row for row in reads(markers, mode) if a.READ_STAGES[row[1]] == stage)[index] = value


def change_boundary_row(markers, stage, index, value):
    row = next(row for m in markers if m['kind'] == 'EVENTS' and m['value']['mode'] == 'IDLE'
               for row in m['value']['events'] if a.STAGES[row[0]] == stage)
    row[index] = value


CASES = {
    'start_revision_boolean': lambda m: payload(m, 'IDLE', 'RESULT').update(startRevision=True),
    'final_revision_float': lambda m: payload(m, 'IDLE', 'RESULT').update(finalRevision=31.0),
    'committed_count_float': lambda m: payload(m, 'IDLE', 'END').update(committedSaves=30.0),
    'drop_count_boolean': lambda m: payload(m, 'IDLE', 'END').update(droppedEvents=False),
    'sqlite_setting_boolean': lambda m: payload(m, 'IDLE', 'RESULT')['fixture']['writerSqlite'].update(auto_vacuum=True),
    'fixture_count_float': lambda m: payload(m, 'IDLE', 'RESULT')['fixture']['rowCounts'].update(notes=501.0),
    'active_missing_connection_lifecycle': remove_connection_lifecycle,
    'active_snapshot_closed_other_thread': snapshot_closed_on_other_thread,
    'active_extra_manual_hold': extra_control_hold,
    'unsubmitted_writer_exit': unsubmitted_worker_exit,
    'idle_control_read_identity': lambda m: change_control_read_id(m, 'IDLE'),
    'held_control_read_identity': lambda m: change_control_read_id(m, 'HELD_SNAPSHOT'),
    'active_control_read_identity': lambda m: change_control_read_id(m, 'ACTIVE_SEARCH'),
    'reader_revision_boolean': lambda m: payload(m, 'IDLE', 'RESULT').update(readerBeforeRevision=True),
    'cleanup_count_boolean': lambda m: payload(m, 'IDLE', 'END').update(cleanupErrors=False),
    'read_drop_count_boolean': lambda m: payload(m, 'IDLE', 'END').update(droppedReadEvents=False),
    'read_counter_float': lambda m: change_read_row(m, 'scope-start', 5, 15.0),
    'active_wrong_query_owner': lambda m: change_read_row(m, 'page-hits-start', 0, 0),
    'active_wrong_query_read_identity': lambda m: change_read_row(m, 'page-hits-start', 2, 0),
    'active_native_thread_switch': lambda m: change_read_row(m, 'scope-start', 6, 76),
    'active_insufficient_query_coverage': lambda m: change_read_row(m, 'page-hits-end', 4, 1020000),
    'held_changed_snapshot_revision': lambda m: change_read_row(m, 'pin-recheck', 3, 31, 'HELD_SNAPSHOT'),
    'held_cpu_counter_reversed': lambda m: change_read_row(m, 'hold-exit', 5, 0, 'HELD_SNAPSHOT'),
    'save_end_precedes_return': lambda m: payload(m, 'IDLE', 'RESULT')['saves'][0].__setitem__(2, 1000000),
    'author_boundary_task_mismatch': lambda m: change_boundary_row(m, 'save-head-read', 2, 555),
    'author_premature_success': lambda m: change_boundary_row(m, 'sqlite-begin-enter', 5, 1),
    'author_boundary_clock_reversed': lambda m: change_boundary_row(m, 'sqlite-end-exit', 7, 0),
    'writer_cleanup_incomplete': lambda m: payload(m, 'IDLE', 'END').update(writersExited=False),
    'held_owner_changed': lambda m: change_read_row(m, 'hold-enter', 0, 1, 'HELD_SNAPSHOT'),
    'idled_snapshot_not_pinned': lambda m: change_read_row(m, 'pin-read', 3, 0, 'IDLE'),
}


def main():
    findings = []
    for name, mutate in CASES.items():
        markers = copy.deepcopy(supplied.synthetic())
        mutate(markers)
        try:
            result = a.analyze(markers)
        except (ValueError, KeyError, TypeError) as error:
            findings.append({'case': name, 'accepted': False, 'error': str(error)})
        else:
            findings.append({'case': name, 'accepted': True, 'status': result['status']})

    # Independently predetermined arithmetic: a 30-vector in reverse order avoids
    # accidentally relying on sorting by input order. Nearest-rank positions 15/29/30.
    quantiles = a.prior.quantiles(list(range(30, 0, -1)))
    assert (quantiles['p50'], quantiles['p95'], quantiles['max']) == (15, 29, 30)
    event = lambda wall, cpu, thread: {'wallNs': wall, 'threadCpuNs': cpu, 'threadId': thread}
    same = a.prior.segment('known', event(1000000, 2000000, 3), event(8500000, 2250000, 3))
    cross = a.prior.segment('known', event(1000000, 2000000, 3), event(8500000, 2250000, 4))
    assert same['wallMs'] == 7.5 and same['threadCpuMs'] == 0.25
    assert cross['wallMs'] == 7.5 and cross['threadCpuMs'] is None
    baseline = a.analyze(copy.deepcopy(supplied.synthetic()))
    segments = baseline['modes']['IDLE']['samples'][0]['segments']
    # Synthetic event n has wall=start+n; the twelve chain gaps are known explicitly.
    expected_ns = [1, 1, 1, 1, 1, 1, 1, 5, 5, 1, 1, 2]
    assert [round(s['wallMs'] * 1e6) for s in segments] == expected_ns
    assert sum(expected_ns) == 21
    assert baseline['modes']['IDLE']['samples'][0]['outside_request_before_ms'] == 0.000001
    assert baseline['modes']['IDLE']['samples'][0]['outside_return_after_ms'] == 0.000001
    assert baseline['modes']['IDLE']['saveWallMs']['p50'] == 0.000023
    variable = copy.deepcopy(supplied.synthetic())
    for marker in variable:
        if marker['kind'] == 'RESULT':
            for sample, save in enumerate(marker['value']['saves']):
                save[2] += sample
    varying_result = a.analyze(variable)
    for mode in a.MODES:
        q = varying_result['modes'][mode]['saveWallMs']
        assert (q['p50'], q['p95'], q['max']) == (0.000037, 0.000051, 0.000052)
        assert varying_result['modes'][mode]['outlierSampleOrder'] == list(range(29, -1, -1))
    # The source permits a caller/post-coroutine endpoint on another thread. Equal
    # monotonic stamps must not make it part of the native connection's thread span.
    legitimate = copy.deepcopy(supplied.synthetic())
    active = reads(legitimate)
    active[-1][4] = active[-2][4]
    active[-1][6] = 99
    active[-1][5] = 1
    try:
        a.analyze(legitimate)
        valid_equal_stamp = {'accepted': True}
    except (ValueError, KeyError, TypeError) as error:
        valid_equal_stamp = {'accepted': False, 'error': str(error)}
    output = {
        'status': 'PASS_SYNTHETIC_REVIEW' if not any(f['accepted'] for f in findings)
                  and valid_equal_stamp['accepted'] else 'REVIEW_GAPS_FOUND',
        'scope': 'independent synthetic malformed-input and arithmetic checks only',
        'source_sha256': {name: hashlib.sha256((ROOT/name).read_bytes()).hexdigest()
                          for name in ('analyze.py', 'boundary_segments.py', 'test_analyze.py')},
        'arithmetic': {'quantiles_30_reverse': quantiles, 'known_same_thread': same,
                       'known_cross_thread': cross, 'twelve_segment_ns': expected_ns,
                       'segment_sum_ns': 21, 'whole_call_ns': 23,
                       'varying_whole_call_ns': [37, 51, 52]},
        'faults': findings,
        'valid_equal_stamp_read_end_thread_change': valid_equal_stamp,
        'native_execution': False,
    }
    (Path(__file__).parent/'results.json').write_text(json.dumps(output, indent=2)+'\n')
    print(json.dumps(output, indent=2))
    assert output['status'] == 'PASS_SYNTHETIC_REVIEW', 'See recorded invalid/valid input outcomes'


if __name__ == '__main__':
    main()
