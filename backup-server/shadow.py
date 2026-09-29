"""Opt-in durable encrypted shadow relay. Authentication is supplied by the backup host."""
import hashlib,json,sqlite3,uuid,base64,re
from contextlib import closing
from pathlib import Path
from fastapi import Depends,HTTPException,Request

class ShadowRelay:
    def __init__(self,path):
        self.path=str(path)
        with closing(sqlite3.connect(self.path)) as c:
            c.executescript("CREATE TABLE IF NOT EXISTS events(seq INTEGER PRIMARY KEY AUTOINCREMENT,owner TEXT NOT NULL,library TEXT NOT NULL,operation TEXT NOT NULL,digest TEXT NOT NULL,envelope TEXT NOT NULL,UNIQUE(owner,library,operation)); CREATE TABLE IF NOT EXISTS cursors(owner TEXT NOT NULL,library TEXT NOT NULL,floor INTEGER NOT NULL,PRIMARY KEY(owner,library));")
    def publish(self,owner,library,device,server,raw):
        if len(raw)>1048576:raise ValueError('SHADOW_BUDGET')
        text=raw.decode();e=json.loads(text)
        if set(e)!={'schema','format','scope','device','operation','key_version','nonce','ciphertext'}:raise ValueError('SHADOW_FIELDS')
        if e['format']!='inkweft.shadow-rows.v2' or e['scope']!=[server,'urn:inkweft:server:'+server,owner,library] or e['device']!=device:raise PermissionError('SHADOW_SCOPE')
        if not isinstance(e['schema'],str) or not re.fullmatch('[0-9a-f]{64}',e['schema']) or type(e['key_version']) is not int or not 1<=e['key_version']<=1000000:raise ValueError('SHADOW_HEADER')
        if len(base64.b64decode(e['nonce'],validate=True))!=12 or len(base64.b64decode(e['ciphertext'],validate=True))<16:raise ValueError('SHADOW_CIPHER')
        uuid.UUID(e['operation']);fingerprint=hashlib.sha256(raw).hexdigest()
        with closing(sqlite3.connect(self.path)) as c, c:
            c.execute('BEGIN IMMEDIATE')
            old=c.execute('SELECT seq,digest FROM events WHERE owner=? AND library=? AND operation=?',(owner,library,e['operation'])).fetchone()
            if old:
                if old[1]!=fingerprint:raise ValueError('SHADOW_REPLAY_CHANGED')
                return old[0]
            # ponytail: bounded 64 MiB laboratory; replace scans with transactional counters before public scale.
            owner_bytes=c.execute('SELECT COALESCE(SUM(length(envelope)),0) FROM events WHERE owner=?',(owner,)).fetchone()[0]
            if owner_bytes+len(raw)>16*1024*1024:raise ValueError('SHADOW_ACCOUNT_QUOTA')
            size=c.execute('SELECT COALESCE(SUM(length(envelope)),0) FROM events').fetchone()[0]
            if size+len(raw)>64*1024*1024:raise ValueError('SHADOW_QUOTA')
            return c.execute('INSERT INTO events(owner,library,operation,digest,envelope) VALUES (?,?,?,?,?)',(owner,library,e['operation'],fingerprint,text)).lastrowid
    def pull(self,owner,library,cursor):
        with closing(sqlite3.connect(self.path)) as c:
            floor=c.execute('SELECT floor FROM cursors WHERE owner=? AND library=?',(owner,library)).fetchone()
            if floor and cursor<floor[0]:raise ValueError('SHADOW_CURSOR_EXPIRED')
            latest=c.execute('SELECT COALESCE(MAX(seq),0) FROM events WHERE owner=? AND library=?',(owner,library)).fetchone()[0]
            if cursor<0 or cursor>latest:raise ValueError('SHADOW_CURSOR')
            row=c.execute('SELECT seq,envelope FROM events WHERE owner=? AND library=? AND seq>? ORDER BY seq LIMIT 1',(owner,library,cursor)).fetchone()
            return {'cursor':row[0] if row else cursor,'latest':latest,'envelopes':[row[1]] if row else []}

def mount(app,store,session,authorize):
    relay=ShadowRelay(Path(store.path).with_suffix('.shadow.db'))
    @app.post('/v1/libraries/{library}/shadow/events')
    async def publish(library:uuid.UUID,request:Request,user=Depends(session)):
        with store.db() as c:authorize(c,library,user)
        raw=await request.body()
        try:return {'cursor':relay.publish(user['user'],str(library),user['device'],store.server_id,raw)}
        except PermissionError:raise HTTPException(403,'Shadow identity rejected')
        except (ValueError,KeyError,TypeError,UnicodeError):raise HTTPException(409,'Shadow envelope rejected')
    @app.get('/v1/libraries/{library}/shadow/events')
    def pull(library:uuid.UUID,cursor:int=0,user=Depends(session)):
        with store.db() as c:authorize(c,library,user)
        try:return relay.pull(user['user'],str(library),cursor)
        except ValueError:raise HTTPException(409,'Shadow cursor requires explicit rebase')
