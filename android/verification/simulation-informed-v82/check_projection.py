"""Check the production ID projection on disposable host SQLite, not Room."""
import json
import re
import sqlite3
from pathlib import Path

android=Path(__file__).resolve().parents[2]
source=(android/'data-local/src/main/kotlin/org/inkweft/data/InkRepository.kt').read_text()
query=re.search(r'@Query\("([^"\n]+)"\) suspend fun strokeIds',source).group(1)
db=sqlite3.connect(':memory:')
db.execute('CREATE TABLE ink_strokes(id TEXT PRIMARY KEY,noteId TEXT,payload BLOB,pointCount INTEGER,visible INTEGER,createdRevision INTEGER)')
db.executemany('INSERT INTO ink_strokes VALUES(?,?,?,?,?,?)',[
    ('third','page',b'original hidden bytes',10,0,2),
    ('second','page',b'original visible bytes',20,1,1),
    ('first','page',b'original hidden bytes',30,0,1),
    ('foreign','other',b'other page',5,1,0)])
reads=[]
def guard(action,table,column,*unused):
    if action==sqlite3.SQLITE_READ:
        reads.append((table,column))
        if table=='ink_strokes' and column=='payload':return sqlite3.SQLITE_DENY
    return sqlite3.SQLITE_OK
db.set_authorizer(guard)
assert [r[0] for r in db.execute(query,{'id':'page'})]==['first','second','third']
assert [r[0] for r in db.execute(query,{'id':'other'})]==['foreign']
assert list(db.execute(query,{'id':'missing'}))==[]
assert ('ink_strokes','payload') not in reads
try:db.execute('SELECT payload FROM ink_strokes').fetchall()
except sqlite3.DatabaseError:blocked=True
else:blocked=False
assert blocked
print(json.dumps({'scope':'HOST_SQLITE_NOT_ANDROID_ROOM','status':'PASS','query':query,
    'checks':['includes hidden rows and stable revision/id order','page isolation','missing page empty result','authorizer blocks payload reads'],
    'projection_payload_reads':0},indent=2))
