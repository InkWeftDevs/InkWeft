import unittest
from android_shards import partition, shard_count, diagnostic_cases


class ShardTest(unittest.TestCase):
    def test_large_run_is_complete_disjoint_and_balanced(self):
        cases = [(f"test-{i}", 1) for i in range(175)]
        parts = [partition(cases, i, 2) for i in range(2)]
        self.assertEqual(175, sum(len(p) for p in parts))
        self.assertEqual(set(cases), set(parts[0]) | set(parts[1]))
        self.assertFalse(set(parts[0]) & set(parts[1]))
        self.assertLessEqual(abs(len(parts[0]) - len(parts[1])), 1)

    def test_small_or_database_only_change_uses_one_device(self):
        for size in [0, 1, 8, 40]:
            self.assertEqual(1, shard_count(size))
        self.assertEqual(2, shard_count(41))
        self.assertEqual([("single", 1)], partition([("single", 1)], 0, 1))

    def test_diagnostic_rounds_are_bounded_and_preserve_each_case_identity(self):
        cases = [("source", 1), ("snapshot", 1), ("save", 1)]
        plan = diagnostic_cases(cases, "focused", 3)
        self.assertEqual(9, len(plan))
        for round_id in (1, 2, 3):
            self.assertEqual(cases, [(name, count) for r, name, count in plan if r == round_id])
        self.assertEqual([(1, "source", 1)], diagnostic_cases(cases[:1], "full", 1))
        for mode, rounds in [("full", 3), ("focused", 0), ("focused", 100)]:
            with self.assertRaises(ValueError):
                diagnostic_cases(cases, mode, rounds)

    def test_invalid_shard_is_rejected_before_device_actions(self):
        for index, count in [(-1, 2), (2, 2), (0, 0)]:
            with self.assertRaises(ValueError):
                partition([], index, count)


if __name__ == "__main__":
    unittest.main()
