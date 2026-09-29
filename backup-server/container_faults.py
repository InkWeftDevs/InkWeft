"""Synthetic snapshot probes, streamed into the disposable container on stdin."""
import errno
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import sys
from contextlib import closing


def file_evidence(path):
    path = Path(path)
    result = {'path': str(path), 'exists': path.exists()}
    if path.exists():
        info = path.stat()
        result.update(size=info.st_size, mode=oct(info.st_mode & 0o777), uid=info.st_uid,
                      readable=os.access(path, os.R_OK))
        if path.is_file():
            data = path.read_bytes()
            result['sha256'] = hashlib.sha256(data).hexdigest()
            if data.startswith(b'SQLite format 3\x00'):
                result['header_write_version'], result['header_read_version'] = data[18:20]
        else:
            result['traversable'] = os.access(path, os.X_OK)
    return result


def snapshot_evidence(path):
    return {'sqlite_version': sqlite3.sqlite_version, 'uid': getattr(os, 'getuid', lambda: None)(),
            'directory': file_evidence(path.parent), 'database': file_evidence(path),
            'sidecars': [file_evidence(Path(str(path) + suffix)) for suffix in ('-wal', '-shm', '-journal')]}


def contents(connection):
    assert connection.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
    data = hashlib.sha256()
    for statement in connection.iterdump():
        data.update(statement.encode())
    operations = connection.execute('SELECT * FROM operations ORDER BY library,id').fetchall()
    cipher = hashlib.sha256()
    chunks = 0
    for library, operation, ordinal, value in connection.execute(
            'SELECT library,operation,ordinal,data FROM chunks ORDER BY library,operation,ordinal'):
        cipher.update(json.dumps([library, operation, ordinal, len(value)]).encode())
        cipher.update(value)
        chunks += 1
    return {'data_sha256': data.hexdigest(),
            'operations_sha256': hashlib.sha256(json.dumps(operations).encode()).hexdigest(),
            'ciphertext_sha256': cipher.hexdigest(), 'operations': len(operations), 'chunks': chunks}


def seal_snapshot(path):
    # Only the independent, completed CLI backup is changed; never the live WAL database.
    assert path.name == 'recovery.db'
    with closing(sqlite3.connect(path)) as connection:
        before = contents(connection)
        mode = connection.execute('PRAGMA journal_mode').fetchone()[0]
        assert connection.execute('PRAGMA journal_mode=DELETE').fetchone()[0] == 'delete'
        assert contents(connection) == before
    assert not any(Path(str(path) + suffix).exists() for suffix in ('-wal', '-shm', '-journal'))
    return {'status': 'PASS', 'journal_mode_before': mode, 'journal_mode': 'delete',
            'contents': before, 'sealed': snapshot_evidence(path)}


def readonly_snapshot(path):
    before = file_evidence(path)
    with closing(sqlite3.connect(path.as_uri() + '?mode=ro', uri=True)) as connection:
        assert connection.execute('PRAGMA journal_mode').fetchone()[0] == 'delete'
        preserved = contents(connection)
        try:
            connection.execute('CREATE TABLE blocked_write(x)')
        except sqlite3.OperationalError as error:
            assert error.sqlite_errorcode == sqlite3.SQLITE_READONLY, error.sqlite_errorname
        else:
            raise AssertionError('SQLite accepted a write through a read-only connection')
    try:
        with (path.parent / 'blocked-write').open('xb'):
            pass
    except OSError as error:
        assert error.errno == errno.EROFS, error.errno
    else:
        raise AssertionError('read-only mount accepted a filesystem write')
    assert file_evidence(path) == before
    return {'status': 'PASS', 'contents': preserved, 'sqlite_error': 'SQLITE_READONLY',
            'filesystem_error': 'EROFS', 'snapshot_unchanged': True}


def full_volume(source, directory):
    # Refuse to fill anything except the explicitly mounted private 8 MiB tmpfs.
    mounts = [line.split() for line in Path('/proc/mounts').read_text().splitlines()]
    assert any(row[1] == str(directory) and row[2] == 'tmpfs' for row in mounts)
    capacity = os.statvfs(directory)
    assert capacity.f_blocks * capacity.f_frsize <= 8 * 1024**2
    target = directory / 'backup.db'
    shutil.copyfile(source, target)
    with closing(sqlite3.connect(target)) as connection:
        # Exercise the service's WAL + FULL durability settings on the disposable copy.
        assert connection.execute('PRAGMA journal_mode=WAL').fetchone()[0] == 'wal'
        connection.execute('PRAGMA synchronous=FULL')
        before = contents(connection)
        try:
            with (directory / 'fill').open('wb', buffering=0) as fill:
                while True:
                    fill.write(bytes(65536))
        except OSError as error:
            assert error.errno == errno.ENOSPC, error.errno
        try:
            connection.execute('BEGIN IMMEDIATE')
            connection.execute('CREATE TABLE must_not_commit(x)')
            connection.execute("UPDATE operations SET state='DELETED' WHERE state='PUBLISHED'")
            connection.commit()
        except sqlite3.OperationalError as error:
            assert error.sqlite_errorcode == sqlite3.SQLITE_FULL, error.sqlite_errorname
            connection.rollback()
        else:
            raise AssertionError('full mount accepted a transaction')
        (directory / 'fill').unlink()
        assert not connection.execute("SELECT 1 FROM sqlite_master WHERE name='must_not_commit'").fetchall()
        assert contents(connection) == before
    # Reopen after the failed connection closes, so rollback durability is also checked.
    with closing(sqlite3.connect(target)) as connection:
        assert contents(connection) == before
    return {'status': 'PASS', 'scope': '8 MiB private tmpfs only', 'contents': before,
            'filesystem_error': 'ENOSPC', 'sqlite_error': 'SQLITE_FULL', 'transaction_not_committed': True}


if __name__ == '__main__':
    mode = sys.argv[1]
    path = Path('/state/recovery.db' if mode == 'seal' else '/source/recovery.db')
    report = {'status': 'RUNNING'}
    try:
        report['before'] = snapshot_evidence(path)
        report.update(seal_snapshot(path) if mode == 'seal' else readonly_snapshot(path) if mode == 'readonly'
                      else full_volume(path, Path('/limited')) if mode == 'full' else {'status': 'INVALID_MODE'})
        assert report['status'] == 'PASS'
    except Exception as error:
        report.update(status='FAIL', error_type=type(error).__name__)
        if isinstance(error, sqlite3.Error):
            report['sqlite_error'] = error.sqlite_errorname
        raise
    finally:
        print(json.dumps(report))
