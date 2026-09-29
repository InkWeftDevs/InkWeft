import unittest
from editor_frame_performance import performance_package, summarize, trace_config


class EditorFrameRunnerTest(unittest.TestCase):
    def test_only_separate_performance_packages_can_be_reset_or_traced(self):
        for package in ('org.inkweft.app.a0', 'org.inkweft.app.a0.workspace', 'org.inkweft.app.a0.insertion', 'unrelated.performance'):
            with self.assertRaises(ValueError):
                performance_package(package)
            with self.assertRaises(ValueError):
                trace_config(package)
        config = trace_config('org.inkweft.app.a0.performance')
        self.assertIn('duration_ms: 180000', config)
        self.assertIn('max_file_size_bytes: 67108864', config)

    def test_summary_uses_vsync_window_and_preserves_missing_reports(self):
        report = {'status': 'PASS', 'package': 'org.inkweft.app.a0.performance', 'profileable': True, 'debuggable': False,
                  'condition': 'encryption', 'computeOverlapsEveryInput': True, 'savedStrokes': 624,
                  'windowStart': 100, 'windowEnd': 200, 'droppedReports': 3,
                  'frames': [{'intendedVsyncNs': at, 'totalNs': duration, 'deadlineNs': 16666667, 'firstDraw': first}
                             for at, duration, first in [(50, 999999999, 0), (100, 10000000, 0), (150, 20000000, 0), (170, 888888888, 1)]],
                  'events': [{'kind': 'input-and-confirmed-save', 'start': 0, 'end': 10000000} for _ in range(24)]}
        row = summarize(report)
        self.assertEqual(2, row['frame_count']); self.assertEqual(20, row['frame_p95_ms'])
        self.assertEqual(1, row['deadline_misses']); self.assertEqual(3, row['dropped_reports'])
        report['computeOverlapsEveryInput'] = False
        with self.assertRaises(AssertionError):
            summarize(report)
        report.update(condition='png', computeOverlapsEveryInput=True)
        report['events'] = [{'kind': 'input-and-confirmed-save', 'start': 100 + index * 4, 'end': 103 + index * 4} for index in range(24)]
        report['events'] += [{'kind': 'png', 'start': 90, 'end': 210}, {'kind': 'png-decode-verify', 'start': 100, 'end': 101}]
        row = summarize(report)
        self.assertEqual(24, row['background_operation_overlap_inputs'])
        self.assertEqual(1, row['work_call_overlap_inputs'])


if __name__ == '__main__':
    unittest.main()
