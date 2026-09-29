import unittest
from beauty_quality_report import evaluate, edits


class BeautyQualityReportTest(unittest.TestCase):
    def row(self, **changes):
        return dict(id='s1', writer='writer1', split='development', source_sha256='a'*64,
                    consent=True, reference='与其寻找123', hypothesis='与其寻找12 3', automatic=False, **changes)

    def test_spaces_count_and_empty_evaluation_is_not_pass(self):
        r = evaluate([self.row()])
        self.assertEqual(1, r['splits']['development']['edits'])
        self.assertEqual(7, r['splits']['development']['characters'])
        self.assertEqual('NOT_RUN', r['splits']['evaluation']['status'])
        self.assertIsNone(r['splits']['evaluation']['cer'])

    def test_no_writer_or_source_leakage(self):
        first = self.row()
        for second in [dict(first, id='s2', split='evaluation', source_sha256='b'*64),
                       dict(first, id='s2', split='evaluation', writer='writer2')]:
            with self.assertRaises(ValueError): evaluate([first, second])

    def test_coverage_and_accepted_errors(self):
        first = dict(self.row(), automatic=True)
        second = dict(self.row(), id='s2', writer='writer2', reference='甲', hypothesis='甲')
        r = evaluate([first, second])['splits']['development']
        self.assertEqual(.5, r['automatic_segment_coverage'])
        self.assertEqual(7/8, r['automatic_character_coverage'])
        self.assertEqual(1, r['accepted_wrong_segments'])
        self.assertEqual(1/7, r['accepted_cer'])

    def test_combining_nfc_and_consent(self):
        self.assertEqual((1, []), edits('é', 'e\u0301'))
        with self.assertRaises(ValueError): evaluate([dict(self.row(), consent=False)])


if __name__ == '__main__': unittest.main()
