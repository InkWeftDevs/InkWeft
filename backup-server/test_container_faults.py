import errno
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch
from contextlib import closing

from container_faults import contents, full_volume, readonly_snapshot, seal_snapshot


class SnapshotProbeTest(unittest.TestCase):
    def test_closed_wal_backup_is_sealed_without_changing_live_wal_or_content(self):
        with tempfile.TemporaryDirectory() as directory:
            live = Path(directory) / 'backup.db'
            snapshot = Path(directory) / 'recovery.db'
            with closing(sqlite3.connect(live)) as source:
                source.execute('PRAGMA journal_mode=WAL')
                source.executescript('''
                    CREATE TABLE operations(id,library,state);
                    CREATE TABLE chunks(library,operation,ordinal,data);
                    INSERT INTO operations VALUES('operation','library','PUBLISHED');
                    INSERT INTO chunks VALUES('library','operation',0,X'000102FF');
                ''')
                expected = contents(source)
                with closing(sqlite3.connect(snapshot)) as destination:
                    source.backup(destination)
                self.assertEqual(b'\x02\x02', snapshot.read_bytes()[18:20])
                self.assertFalse(Path(str(snapshot) + '-wal').exists())
                sealed = seal_snapshot(snapshot)
                self.assertEqual('wal', sealed['journal_mode_before'])
                self.assertEqual('delete', sealed['journal_mode'])
                self.assertEqual(expected, sealed['contents'])
                self.assertEqual('wal', source.execute('PRAGMA journal_mode').fetchone()[0])
                self.assertEqual(expected, contents(source))
            # Local host filesystem is writable. Only the mount failure is injected;
            # the read-only SQLite query/write failure is real (not a Docker result).
            original_open = Path.open
            def mount_readonly(path, *args, **kwargs):
                if path.name == 'blocked-write':
                    raise OSError(errno.EROFS, 'Read-only file system')
                return original_open(path, *args, **kwargs)
            with patch.object(Path, 'open', mount_readonly):
                result = readonly_snapshot(snapshot)
            self.assertEqual(expected, result['contents'])
            self.assertEqual('SQLITE_READONLY', result['sqlite_error'])
            def wrong_failure(path, *args, **kwargs):
                if path.name == 'blocked-write':
                    raise OSError(errno.EACCES, 'Permission denied')
                return original_open(path, *args, **kwargs)
            with patch.object(Path, 'open', wrong_failure), self.assertRaises(AssertionError):
                readonly_snapshot(snapshot)
            # A writable mount must fail the probe even though mode=ro rejects SQL.
            with self.assertRaisesRegex(AssertionError, 'filesystem write'):
                readonly_snapshot(snapshot)

    def test_sealing_cannot_be_applied_to_the_live_database_path(self):
        with self.assertRaises(AssertionError):
            seal_snapshot(Path('backup.db'))

    def test_full_probe_refuses_a_non_tmpfs_directory_before_writing(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            with patch.object(Path, 'read_text', return_value='/dev/sda /limited ext4 rw 0 0'), self.assertRaises(AssertionError):
                full_volume(path / 'recovery.db', path)
            self.assertEqual([], list(path.iterdir()))


if __name__ == '__main__':
    unittest.main()
