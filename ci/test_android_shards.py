import unittest
from android_shards import partition, shard_count


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

    def test_invalid_shard_is_rejected_before_device_actions(self):
        for index, count in [(-1, 2), (2, 2), (0, 0)]:
            with self.assertRaises(ValueError):
                partition([], index, count)


if __name__ == "__main__":
    unittest.main()
