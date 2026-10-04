#!/usr/bin/env python3
"""Execute RecordIndex's actual SQL on SQLite: every previous schema -> v7.

This checks migration/data semantics, not the Android SQLiteOpenHelper runtime.
"""
import json
from pathlib import Path
import re
import sqlite3
import tempfile

root = Path(__file__).resolve().parents[1]
source = (root / 'app/src/main/java/ua/iben/recorder/RecordIndex.java').read_text()


def body(name):
    match = re.search(r'\b' + name + r'\(SQLiteDatabase db(?:, [^)]*)?\)\s*\{([^}]+)\}', source)
    assert match, name
    return match.group(1)


def statements(method_body):
    return [''.join(json.loads(part) for part in re.findall(r'"(?:[^"\\]|\\.)*"', expression))
            for expression in re.findall(r'db\.execSQL\((.*?)\);', method_body, re.S)]


create_body = body('onCreate')
groups = {1: statements(create_body)}
upgrade_calls = re.findall(r'if \(oldVersion < (\d)\) (\w+)\(db\);', body('onUpgrade'))
assert [int(version) for version, _ in upgrade_calls] == [2, 3, 4, 5, 6, 7]
for version, method in upgrade_calls:
    groups[int(version)] = statements(body(method))
assert re.findall(r'\b(add\w+)\(db\);', create_body) == [method for _, method in upgrade_calls]
assert '"recordings.db", null, 7' in source


def schema(db, start, end):
    for version in range(start, end + 1):
        for sql in groups[version]:
            db.execute(sql)


def columns(db):
    return [row[1] for row in db.execute('PRAGMA table_info(records)')]


def seed(db, version):
    for index, state in enumerate([0, 1, 2, 3]):
        db.execute('INSERT INTO records(id,start_ms,zone,duration_ms,final_name,state) VALUES(?,?,?,?,?,?)',
                   (f'record-{index}', 100 + index, 'Europe/Kyiv', 60000, f'file-{index}.m4a', state))
    if version >= 2:
        db.execute("UPDATE records SET verified_target='account-a',verified_size=4096,verified_modified=123,"
                   "verified_hash='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',remote_name='alternate-name.m4a' WHERE id='record-2'")
    if version >= 3:
        db.execute("UPDATE records SET playback_ms=15000,listened=1,heard_ranges='0:15000' WHERE id='record-2'")
    if version >= 4:
        db.execute("INSERT INTO cloud_deletions(record_id,target,done) VALUES('record-2','account-a',0)")
        db.execute("INSERT INTO cloud_deletions(record_id,target,done) VALUES('record-2','account-b',1)")


with tempfile.TemporaryDirectory(prefix='iben-schema-') as directory:
    for version in [1, 2, 3, 4, 5, 6]:
        path = Path(directory) / f'v{version}.db'
        with sqlite3.connect(path) as db:
            schema(db, 1, version)
            seed(db, version)
            if version >= 5:
                db.execute("UPDATE records SET important=1 WHERE id='record-1'")
                db.execute("INSERT INTO bookmarks(id,record_id,position_ms,label) VALUES('legacy','record-1',123,'Старе')")
            if version>=6:
                db.execute("UPDATE records SET receipt_kind=1 WHERE verified_hash IS NOT NULL")
            old_columns = ','.join(columns(db))
            old_records = db.execute(f'SELECT {old_columns} FROM records ORDER BY id').fetchall()
            old_deletions = db.execute('SELECT * FROM cloud_deletions ORDER BY target').fetchall() if version >= 4 else []
            schema(db, version + 1, 7)
            assert db.execute(f'SELECT {old_columns} FROM records ORDER BY id').fetchall() == old_records
            assert db.execute('SELECT * FROM cloud_deletions ORDER BY target').fetchall() == old_deletions
            assert db.execute('SELECT important FROM records ORDER BY id').fetchall() == ([(0,), (1,), (0,), (0,)] if version >= 5 else [(0,)]*4)
            assert db.execute('SELECT COUNT(*) FROM bookmarks').fetchone() == (1 if version >= 5 else 0,)
            assert db.execute('SELECT receipt_kind FROM records ORDER BY id').fetchall() == ([(0,), (0,), (1,), (0,)] if version >= 2 else [(0,)]*4)
            if version>=2:
                assert db.execute("SELECT target,remote_name,record_id,size,present FROM cloud_files").fetchall()==[('account-a','alternate-name.m4a','record-2',4096,1)]
            else:
                assert db.execute("SELECT count(*) FROM cloud_files").fetchone()==(0,)
            assert db.execute('SELECT verified_etag FROM records').fetchall() == [(None,)]*4
            db.execute("UPDATE records SET receipt_kind=2,verified_target='account-a',verified_size=8192,verified_modified=456,verified_hash=NULL,verified_etag='\"opaque-version\"' WHERE id='record-3'")
            if version == 1:
                assert db.execute('SELECT verified_target,verified_size,verified_hash FROM records WHERE id=?',
                                  ('record-2',)).fetchone() == (None, -1, None)
            db.execute("UPDATE records SET important=1 WHERE id='record-2'")
            db.executemany('INSERT INTO bookmarks(id,record_id,position_ms,label) VALUES(?,?,?,?)', [
                ('later', 'record-2', 59000, 'Кінець / Koniec / End'),
                ('earlier', 'record-2', 1000, 'Початок'),
                ('same-time', 'record-2', 1000, ''),
                ('active', 'record-0', 4000, 'Під час запису')])
        with sqlite3.connect(path) as reopened:
            assert reopened.execute('PRAGMA integrity_check').fetchone() == ('ok',)
            assert reopened.execute("SELECT receipt_kind,verified_hash,verified_etag FROM records WHERE id='record-3'").fetchone() == (2,None,'"opaque-version"')
            if version >= 5:
                assert reopened.execute("SELECT label FROM bookmarks WHERE id='legacy'").fetchone() == ('Старе',)
            assert reopened.execute("SELECT important FROM records WHERE id='record-2'").fetchone() == (1,)
            assert reopened.execute("SELECT id FROM bookmarks WHERE record_id='record-2' ORDER BY position_ms,id").fetchall() == [
                ('earlier',), ('same-time',), ('later',)]
            assert reopened.execute("SELECT label FROM bookmarks WHERE id='later'").fetchone() == ('Кінець / Koniec / End',)
            assert reopened.execute('SELECT * FROM cloud_deletions ORDER BY target').fetchall() == old_deletions
            # Local deletion removes only that recording's metadata in one transaction.
            with reopened:
                for table in ['bookmarks', 'cloud_deletions', 'cloud_files']:
                    reopened.execute(f'DELETE FROM {table} WHERE record_id=?', ('record-2',))
                reopened.execute('DELETE FROM records WHERE id=?', ('record-2',))
            assert reopened.execute('SELECT id FROM bookmarks ORDER BY id').fetchall() == ([('active',), ('legacy',)] if version >= 5 else [('active',)])
            assert reopened.execute('SELECT COUNT(*) FROM records').fetchone() == (3,)

    with sqlite3.connect(':memory:') as fresh:
        schema(fresh, 1, 7)
        assert columns(fresh)[-2:] == ['receipt_kind', 'verified_etag']
        assert len(list(fresh.execute('PRAGMA table_info(bookmarks)'))) == 4

# Execute the actual migration on metadata-only receipts and completed cloud deletion.
with sqlite3.connect(':memory:') as db:
    schema(db,1,6)
    seed(db,6)
    db.execute('UPDATE records SET receipt_kind=2,verified_hash=NULL,verified_etag=? WHERE id=?', ('"etag"','record-2'))
    db.execute("UPDATE cloud_deletions SET done=1 WHERE record_id='record-2' AND target='account-a'")
    schema(db,7,7)
    assert db.execute('SELECT present,etag FROM cloud_files').fetchone()==(0,'"etag"')
    db.execute("UPDATE cloud_files SET present=1,seen_ms=200")
    db.execute("UPDATE cloud_files SET present=0 WHERE target=? AND seen_ms<=?",('account-a',100))
    assert db.execute('SELECT present FROM cloud_files').fetchone()==(1,) # newer upload wins
    db.execute("INSERT INTO cloud_files(target,remote_name,record_id,size) VALUES('account-b','same.m4a','record-2',123)")
    db.execute("UPDATE cloud_files SET present=0 WHERE target='account-a'")
    query=re.search(r'rawQuery\("(SELECT 1 FROM cloud_files[^"\n]+)"',source).group(1)
    assert db.execute(query,('record-2',)).fetchone()==(1,) # another account still retains history
    db.execute("UPDATE cloud_files SET present=0 WHERE target='account-b'")
    assert db.execute(query,('record-2',)).fetchone() is None
    assert db.execute('PRAGMA integrity_check').fetchone()==('ok',)

print('PASS: SQLite v1/v2/v3/v4/v5/v6 -> v7 preserves rows, content receipts, playback, deletion state and bookmarks; metadata receipts, cloud catalogue and per-account retention remain distinct after reopen')
