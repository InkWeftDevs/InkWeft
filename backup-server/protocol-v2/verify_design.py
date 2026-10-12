"""Executable arithmetic for CONTRACT.md; no server, credentials or author data access."""
import json
import unittest

PLAIN_LIMIT = 8 * 1024**3
CRYPTO_BLOCK = 256 * 1024
WIRE_BLOCK = 1024**2
HEAD_BOUND = 4106
SAFETY = 32 * 1024**2


def cipher_bound(plain):
    if not 1 <= plain <= PLAIN_LIMIT:
        raise ValueError('plain capacity')
    return plain + ((plain + CRYPTO_BLOCK - 1) // CRYPTO_BLOCK) * 20 + HEAD_BOUND


def missing_ranges(cipher_bytes, received):
    if not 1 <= cipher_bytes <= cipher_bound(PLAIN_LIMIT):
        raise ValueError('cipher capacity')
    count = (cipher_bytes + WIRE_BLOCK - 1) // WIRE_BLOCK
    if len(received) != len(set(received)) or any(type(i) is not int or not 0 <= i < count for i in received):
        raise ValueError('received set')
    present = set(received)
    return [(i, i * WIRE_BLOCK, min(WIRE_BLOCK, cipher_bytes - i * WIRE_BLOCK))
            for i in range(count) if i not in present]


def restore_peak(cipher_bytes, plain_bytes, staging_bytes, final_growth):
    if min(cipher_bytes, plain_bytes, staging_bytes, final_growth) < 0:
        raise ValueError('space')
    # Conservative first implementation retains all phases until final import commits.
    return cipher_bytes + plain_bytes + staging_bytes + final_growth + SAFETY


class ContractTest(unittest.TestCase):
    def test_maximum_is_beyond_signed_32_bit_and_fits_nonce_and_request(self):
        cipher = cipher_bound(PLAIN_LIMIT)
        self.assertGreater(cipher, 2**32)
        self.assertEqual(32768, (PLAIN_LIMIT + CRYPTO_BLOCK - 1) // CRYPTO_BLOCK)
        self.assertEqual(8193, (cipher + WIRE_BLOCK - 1) // WIRE_BLOCK)
        manifest = {'format': 'inkweft.encrypted-backup.v2', 'bytes': cipher, 'chunks': ['f' * 64] * 8193}
        self.assertLess(len(json.dumps(manifest, separators=(',', ':')).encode()), WIRE_BLOCK)
        self.assertRaises(ValueError, cipher_bound, PLAIN_LIMIT + 1)

    def test_large_resume_reads_only_missing_offsets_including_tail(self):
        cipher = cipher_bound(PLAIN_LIMIT)
        missing = missing_ranges(cipher, list(range(8192)))
        self.assertEqual([(8192, PLAIN_LIMIT, cipher - PLAIN_LIMIT)], missing)
        self.assertEqual([], missing_ranges(cipher, list(range(8193))))
        self.assertRaises(ValueError, missing_ranges, cipher, [8193])
        self.assertRaises(ValueError, missing_ranges, cipher, [0, 0])

    def test_crypto_and_wire_boundaries(self):
        for plain in [1, CRYPTO_BLOCK - 1, CRYPTO_BLOCK, CRYPTO_BLOCK + 1, 600 * WIRE_BLOCK, 2 * 1024**3, PLAIN_LIMIT]:
            cipher = cipher_bound(plain)
            chunks = missing_ranges(cipher, [])
            self.assertEqual(cipher, sum(length for _, _, length in chunks))
            self.assertTrue(all(0 < length <= WIRE_BLOCK for _, _, length in chunks))
            self.assertEqual(cipher, chunks[-1][1] + chunks[-1][2])

    def test_restore_budget_includes_staging_and_final_growth(self):
        cipher = cipher_bound(PLAIN_LIMIT)
        peak = restore_peak(cipher, PLAIN_LIMIT, PLAIN_LIMIT, PLAIN_LIMIT)
        self.assertGreater(peak, cipher * 2)
        self.assertGreater(peak, 32 * 1024**3)


if __name__ == '__main__':
    unittest.main(verbosity=2)
