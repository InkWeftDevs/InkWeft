"""Offline frame/trace readout. Does not connect to a device or alter raw samples."""
import argparse
import csv
import hashlib
import io
import json
from pathlib import Path
import re
import statistics
import subprocess

from editor_frame_performance import summarize


def analyze_sample(folder, row, processor):
    report = json.loads((folder / 'timeline.json').read_text(encoding='utf-8'))
    metrics = summarize(report)
    prefix = report['tracePrefix']
    assert re.fullmatch(r'InkWeft\.perf\.[a-z0-9-]{1,48}', prefix)
    trace = folder / 'editor.perfetto-trace'
    assert hashlib.sha256(trace.read_bytes()).hexdigest() == row['trace_sha256']
    window = f"SELECT ts,dur FROM slice WHERE name='{prefix}.window' AND dur>0"
    target = f"SELECT upid,pid FROM process WHERE cmdline='{report['package']}'"
    queries = [
        "SELECT name,severity,value FROM stats WHERE value!=0 AND severity IN ('error','data_loss') ORDER BY name",
        f"SELECT name,COUNT(*) AS count,ROUND(SUM(dur)/1e6,3) AS duration_ms FROM slice WHERE name GLOB '{prefix}.*' GROUP BY name ORDER BY name",
        f"""WITH w AS ({window}),p AS ({target})
SELECT t.tid,t.name,CASE WHEN t.tid=p.pid THEN 'main' WHEN t.name='RenderThread' THEN 'render' ELSE 'other' END AS role,
ROUND(SUM(MIN(s.ts+s.dur,w.ts+w.dur)-MAX(s.ts,w.ts))/1e6,3) AS cpu_ms
FROM sched s JOIN thread t USING(utid) JOIN p USING(upid),w
WHERE s.dur>0 AND s.ts<w.ts+w.dur AND s.ts+s.dur>w.ts GROUP BY t.utid ORDER BY cpu_ms DESC""",
        f"""WITH w AS ({window}),p AS ({target})
SELECT st.state,ROUND(SUM(MIN(st.ts+st.dur,w.ts+w.dur)-MAX(st.ts,w.ts))/1e6,3) AS duration_ms
FROM thread_state st JOIN thread t USING(utid) JOIN p USING(upid),w
WHERE t.tid=p.pid AND st.dur>0 AND st.ts<w.ts+w.dur AND st.ts+st.dur>w.ts GROUP BY st.state ORDER BY duration_ms DESC""",
        f"""WITH w AS ({window}),p AS ({target})
SELECT s.name,COUNT(*) AS count,ROUND(SUM(MIN(s.ts+s.dur,w.ts+w.dur)-MAX(s.ts,w.ts))/1e6,3) AS duration_ms
FROM slice s JOIN thread_track tr ON tr.id=s.track_id JOIN thread t USING(utid) JOIN p USING(upid),w
WHERE s.dur>0 AND s.ts<w.ts+w.dur AND s.ts+s.dur>w.ts AND (s.name GLOB '* GC*' OR s.name GLOB 'GC*' OR s.name GLOB '*garbage*')
GROUP BY s.name ORDER BY duration_ms DESC""",
        f"""SELECT save.id,COUNT(input.id) AS input_callbacks,
ROUND((save.ts+save.dur-MAX(input.ts+input.dur))/1e6,3) AS confirmation_after_input_ms
FROM slice save JOIN slice input ON input.name='{prefix}.input' AND input.ts>=save.ts AND input.ts+input.dur<=save.ts+save.dur
WHERE save.name='{prefix}.save' AND save.dur>0 GROUP BY save.id ORDER BY save.ts""",
    ]
    sql = folder / 'trace-analysis.sql'; sql.write_text(';\n\n'.join(queries) + ';\n')
    completed = subprocess.run([processor, 'query', '-f', str(sql), str(trace)], capture_output=True, timeout=90)
    (folder / 'trace-analysis.csv').write_bytes(completed.stdout)
    (folder / 'trace-analysis.log').write_bytes(completed.stderr)
    completed.check_returncode()
    blocks = re.split(r'\n\s*\n', completed.stdout.decode('utf-8').replace('\r', '').strip())
    assert len(blocks) == len(queries), 'Unexpected trace processor result sets'
    sections = {name: list(csv.DictReader(io.StringIO(block))) for name, block in
                zip(('health', 'markers', 'cpu_threads', 'main_thread_states', 'gc_slices', 'save_confirmation'), blocks)}
    counts = {item['name']: int(item['count']) for item in sections['markers']}
    assert counts[prefix + '.window'] == 1 and counts[prefix + '.input'] == 192 and counts[prefix + '.save'] == 24
    confirmations = sections['save_confirmation']
    assert len(confirmations) == 24 and all(int(item['input_callbacks']) == 8 for item in confirmations)
    window_ms = (report['windowEnd'] - report['windowStart']) / 1e6
    roles = {role: sum(float(item['cpu_ms']) for item in sections['cpu_threads'] if item['role'] == role)
             for role in ('main', 'render', 'other')}
    assert roles['main'] > 0 and roles['render'] > 0, 'Target CPU tracks missing'
    result = {'run_id': row['run_id'], 'round': row['round'], 'condition': row['condition'],
              'trace_health': 'PASS' if not sections['health'] else 'PARTIAL', 'trace': sections,
              'window_ms': window_ms, 'frame_metrics': metrics, 'cpu_ms': roles,
              'cpu_percent_of_window': {role: value / window_ms * 100 for role, value in roles.items()},
              'confirmation_after_input_p50_ms': statistics.median(float(item['confirmation_after_input_ms']) for item in confirmations)}
    (folder / 'trace-analysis.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--summary', required=True); parser.add_argument('--trace-processor', required=True)
    args = parser.parse_args(); source = Path(args.summary).resolve(); folder = source.parent
    summary = json.loads(source.read_text(encoding='utf-8'))
    assert summary['samples'] and all(row['passed'] for row in summary['samples'])
    samples = []
    for row in summary['samples']:
        assert re.fullmatch(r'[a-z0-9-]{1,48}', row['run_id'])
        samples.append(analyze_sample(folder / row['run_id'], row, args.trace_processor))
    conditions = {}
    for condition in sorted({row['condition'] for row in samples}):
        group = [row for row in samples if row['condition'] == condition]
        p95 = [row['frame_metrics']['frame_p95_ms'] for row in group]
        conditions[condition] = {'rounds': len(group), 'frame_p95_ms_by_round': p95,
                                 'median_round_frame_p95_ms': statistics.median(p95),
                                 'dropped_frame_reports': sum(row['frame_metrics']['dropped_reports'] for row in group),
                                 'work_call_overlap_inputs_by_round': [row['frame_metrics']['work_call_overlap_inputs'] for row in group],
                                 'median_main_cpu_percent': statistics.median(row['cpu_percent_of_window']['main'] for row in group),
                                 'median_render_cpu_percent': statistics.median(row['cpu_percent_of_window']['render'] for row in group),
                                 'median_other_threads_cpu_percent': statistics.median(row['cpu_percent_of_window']['other'] for row in group)}
    result = {'status': 'PASS' if all(row['trace_health'] == 'PASS' for row in samples) else 'PARTIAL',
              'label': summary['label'], 'apk_sha256': summary['apk_sha256'], 'device': summary['device'],
              'trace_processor_version': subprocess.check_output([args.trace_processor, '--version'], encoding='utf-8').strip(),
              'conditions': conditions, 'samples': samples,
              'limits': ['Three rounds are observations, not a product SLO or statistical confidence claim.',
                         'Injected input-to-confirmation includes seven 16 ms pacing intervals; trace confirmation after the last input also includes database polling and test dispatch.',
                         'PNG operation duration includes foreground-yield waits and idempotent installation. Decode-call overlap is reported separately.',
                         'Encryption-call duration includes crypto and file I/O; aggregate worker CPU tracks alone do not identify a specific implementation hotspot.',
                         'MuMu synthetic measurements do not establish real-pen latency or physical-device acceptance.']}
    output = folder / 'analysis.json'; output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'status': result['status'], 'conditions': conditions, 'output': str(output)}, ensure_ascii=False))


if __name__ == '__main__':
    main()
