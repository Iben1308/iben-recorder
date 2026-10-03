#!/usr/bin/env python3
"""Execute RecordIndex's actual SQL on SQLite: every previous schema -> v5.

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
assert [int(version) for version, _ in upgrade_calls] == [2, 3, 4, 5]
for version, method in upgrade_calls:
    groups[int(version)] = statements(body(method))
assert re.findall(r'\b(add\w+)\(db\);', create_body) == [method for _, method in upgrade_calls]
assert '"recordings.db", null, 5' in source


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
                   "verified_hash='sha256',remote_name='alternate-name.m4a' WHERE id='record-2'")
    if version >= 3:
        db.execute("UPDATE records SET playback_ms=15000,listened=1,heard_ranges='0:15000' WHERE id='record-2'")
    if version >= 4:
        db.execute("INSERT INTO cloud_deletions(record_id,target,done) VALUES('record-2','account-a',0)")
        db.execute("INSERT INTO cloud_deletions(record_id,target,done) VALUES('record-2','account-b',1)")


with tempfile.TemporaryDirectory(prefix='iben-schema-') as directory:
    for version in [1, 2, 3, 4]:
        path = Path(directory) / f'v{version}.db'
        with sqlite3.connect(path) as db:
            schema(db, 1, version)
            seed(db, version)
            old_columns = ','.join(columns(db))
            old_records = db.execute(f'SELECT {old_columns} FROM records ORDER BY id').fetchall()
            old_deletions = db.execute('SELECT * FROM cloud_deletions ORDER BY target').fetchall() if version >= 4 else []
            schema(db, version + 1, 5)
            assert db.execute(f'SELECT {old_columns} FROM records ORDER BY id').fetchall() == old_records
            assert db.execute('SELECT * FROM cloud_deletions ORDER BY target').fetchall() == old_deletions
            assert db.execute('SELECT important FROM records').fetchall() == [(0,)] * 4
            assert db.execute('SELECT COUNT(*) FROM bookmarks').fetchone() == (0,)
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
            assert reopened.execute("SELECT important FROM records WHERE id='record-2'").fetchone() == (1,)
            assert reopened.execute("SELECT id FROM bookmarks WHERE record_id='record-2' ORDER BY position_ms,id").fetchall() == [
                ('earlier',), ('same-time',), ('later',)]
            assert reopened.execute("SELECT label FROM bookmarks WHERE id='later'").fetchone() == ('Кінець / Koniec / End',)
            assert reopened.execute('SELECT * FROM cloud_deletions ORDER BY target').fetchall() == old_deletions
            # Local deletion removes only that recording's metadata in one transaction.
            with reopened:
                for table in ['bookmarks', 'cloud_deletions']:
                    reopened.execute(f'DELETE FROM {table} WHERE record_id=?', ('record-2',))
                reopened.execute('DELETE FROM records WHERE id=?', ('record-2',))
            assert reopened.execute('SELECT id FROM bookmarks').fetchall() == [('active',)]
            assert reopened.execute('SELECT COUNT(*) FROM records').fetchone() == (3,)

    with sqlite3.connect(':memory:') as fresh:
        schema(fresh, 1, 5)
        assert columns(fresh)[-1] == 'important'
        assert len(list(fresh.execute('PRAGMA table_info(bookmarks)'))) == 4

print('PASS: SQLite v1/v2/v3/v4 -> v5 preserves all old rows, receipts, playback and per-target deletion state; important/bookmarks persist after reopen')
