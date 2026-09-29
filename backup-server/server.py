"""InkWeft experimental encrypted backup directory. No plaintext keys or author documents."""
import argparse
import asyncio
import logging
import threading
from contextlib import contextmanager
import getpass
import hashlib
import json
import os
from pathlib import Path
import secrets
import sqlite3
import time
import uuid

from fastapi import FastAPI, Depends, HTTPException, Request, Response
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from pydantic import BaseModel, Field
from pwdlib import PasswordHash

CHUNK_LIMIT = 1024 * 1024
LIBRARY_LIMIT = 512 * CHUNK_LIMIT
ACCOUNT_LIMIT = 2 * 1024**3
GLOBAL_LIMIT = 8 * 1024**3
PENDING_SECONDS = 7 * 86400
logger = logging.getLogger("inkweft.backup")
HASHER = PasswordHash.recommended()
SCHEMA = '''
CREATE TABLE IF NOT EXISTS identity(id TEXT PRIMARY KEY);
CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY, name TEXT UNIQUE NOT NULL, password TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS sessions(token TEXT PRIMARY KEY, user TEXT NOT NULL REFERENCES users(id), device TEXT NOT NULL, expires INTEGER NOT NULL, revoked INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS libraries(id TEXT PRIMARY KEY, owner TEXT NOT NULL REFERENCES users(id));
CREATE TABLE IF NOT EXISTS operations(id TEXT NOT NULL, library TEXT NOT NULL REFERENCES libraries(id), kind TEXT NOT NULL, digest TEXT NOT NULL, manifest TEXT NOT NULL, state TEXT NOT NULL, created INTEGER NOT NULL, PRIMARY KEY(library,id));
CREATE TABLE IF NOT EXISTS chunks(library TEXT NOT NULL, operation TEXT NOT NULL, ordinal INTEGER NOT NULL, data BLOB NOT NULL, PRIMARY KEY(library,operation,ordinal), FOREIGN KEY(library,operation) REFERENCES operations(library,id));
CREATE TABLE IF NOT EXISTS attempts(name TEXT NOT NULL, at INTEGER NOT NULL);
PRAGMA user_version=1;
'''


def canonical(value): return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False)
def digest(value): return hashlib.sha256(value).hexdigest()


class Store:
    def __init__(self, path):
        self.path = str(Path(path).resolve())
        Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        with self.db() as c:
            version = c.execute('PRAGMA user_version').fetchone()[0]
            if version not in (0, 1): raise RuntimeError('Unsupported server schema')
            c.executescript(SCHEMA)
            if not c.execute('SELECT id FROM identity').fetchone():
                c.execute('INSERT INTO identity VALUES(?)', (str(uuid.uuid4()),))
            self.server_id = c.execute('SELECT id FROM identity').fetchone()[0]

    @contextmanager
    def db(self):
        c = sqlite3.connect(self.path, timeout=15)
        c.row_factory = sqlite3.Row
        c.execute('PRAGMA foreign_keys=ON')
        c.execute('PRAGMA journal_mode=WAL')
        c.execute('PRAGMA synchronous=FULL')
        try:
            with c: yield c
        finally: c.close()

    def add_user(self, name, password):
        if not 1 <= len(name) <= 100 or len(password) < 12: raise ValueError('Name and password (12+ characters) required')
        user = str(uuid.uuid4())
        with self.db() as c:
            c.execute('INSERT INTO users VALUES(?,?,?)', (user, name, HASHER.hash(password)))
        return user


class Login(BaseModel):
    username: str = Field(min_length=1, max_length=100)
    password: str = Field(min_length=1, max_length=1024)
    device: uuid.UUID


class Manifest(BaseModel):
    format: str = Field(pattern=r'^inkweft\.encrypted-backup\.v1$')
    chunks: list[str] = Field(min_length=1, max_length=512)
    bytes: int = Field(gt=0, le=LIBRARY_LIMIT)


def create_app(path, before_publish=lambda: None, enable_shadow=False):
    store = Store(path)
    app = FastAPI(title='墨织加密备份实验服务', version='0.1.0', docs_url=None, redoc_url=None)
    app.state.store = store
    security = HTTPBearer(auto_error=False)
    password_slots=threading.BoundedSemaphore(4)

    # Bounded body readers; SQLite BEGIN IMMEDIATE serializes quota, publish and delete.
    # Receipts are retained indefinitely in v1; payload GC never removes operation identity.
    active_requests = 0

    @app.middleware('http')
    async def size_limit(request, call_next):
        nonlocal active_requests
        correlation = str(uuid.uuid4())
        if active_requests >= 32:
            return Response(status_code=429, headers={'Retry-After': '2', 'X-Request-ID': correlation})
        active_requests += 1
        try:
            async with asyncio.timeout(30):
                body = bytearray()
                async for chunk in request.stream():
                    if len(body)+len(chunk) > CHUNK_LIMIT + 32768:
                        return Response(status_code=413, headers={'X-Request-ID': correlation})
                    body.extend(chunk)
                request._body = bytes(body)
                result = await call_next(request)
        except TimeoutError:
            result = Response(status_code=408)
        except sqlite3.OperationalError:
            result = Response('storage unavailable', status_code=507)
        finally:
            active_requests -= 1
        result.headers['X-Request-ID'] = correlation
        # No URL, title, request body, account name or credentials in diagnostics.
        logger.info('request=%s phase=http status=%s', correlation, result.status_code)
        return result

    def expire_pending(c):
        expired = c.execute("SELECT library,id FROM operations WHERE kind='UPLOAD' AND state='PENDING' AND created<?",
                            (int(time.time())-PENDING_SECONDS,)).fetchall()
        for row in expired:
            c.execute("UPDATE operations SET state='EXPIRED' WHERE library=? AND id=?", tuple(row))
            c.execute('DELETE FROM chunks WHERE library=? AND operation=?', tuple(row))

    @app.get('/health/live')
    def live(): return {'alive': True}

    @app.get('/health/ready')
    def ready():
        with store.db() as c:
            c.execute('BEGIN IMMEDIATE')
            c.execute('UPDATE identity SET id=id')
        return {'writable': True, 'schema': 1}

    def session(credentials: HTTPAuthorizationCredentials | None = Depends(security)):
        if not credentials or credentials.scheme.lower() != 'bearer': raise HTTPException(401, 'Session required')
        key = digest(credentials.credentials.encode())
        with store.db() as c:
            row = c.execute('SELECT * FROM sessions WHERE token=? AND revoked=0 AND expires>?', (key, int(time.time()))).fetchone()
        if row is None: raise HTTPException(401, 'Session expired or revoked')
        return dict(row)

    def authorize(c, library, user):
        row = c.execute('SELECT owner FROM libraries WHERE id=?', (str(library),)).fetchone()
        if row is None or row['owner'] != user['user']: raise HTTPException(404, 'Library unavailable')

    def operation(c, library, op, user):
        authorize(c, library, user)
        row = c.execute('SELECT * FROM operations WHERE library=? AND id=?', (str(library), str(op))).fetchone()
        if row is None: raise HTTPException(404, 'Operation unavailable')
        return row

    def receipt(row):
        return {'operation': row['id'], 'library': row['library'], 'kind': row['kind'], 'digest': row['digest'], 'state': row['state']}

    @app.get('/v1/identity')
    def identity(): return {'server': store.server_id, 'issuer': 'urn:inkweft:server:'+store.server_id, 'protocol': 1}

    @app.post('/v1/sessions')
    def login(request: Login):
        now = int(time.time())
        with store.db() as c:
            c.execute('DELETE FROM attempts WHERE at<?', (now-60,))
            if c.execute('SELECT COUNT(*) FROM attempts WHERE name=?', (request.username,)).fetchone()[0] >= 5: raise HTTPException(429, 'Try later')
            c.execute('INSERT INTO attempts VALUES(?,?)', (request.username, now))
            row = c.execute('SELECT * FROM users WHERE name=?', (request.username,)).fetchone()
        if not password_slots.acquire(blocking=False):raise HTTPException(429,'Try later')
        try:
            if row is None or not HASHER.verify(request.password, row['password']): raise HTTPException(401,'Login failed')
        finally:password_slots.release()
        token = secrets.token_urlsafe(32)
        with store.db() as c:
            c.execute('DELETE FROM attempts WHERE name=?', (request.username,))
            c.execute('INSERT INTO sessions VALUES(?,?,?,?,0)', (digest(token.encode()), row['id'], str(request.device), now+86400))
        return {'token': token, 'user': row['id'], 'device': str(request.device), 'server': store.server_id, 'issuer': 'urn:inkweft:server:'+store.server_id, 'expires': now+86400}

    @app.delete('/v1/sessions/current')
    def logout(user=Depends(session)):
        with store.db() as c: c.execute('UPDATE sessions SET revoked=1 WHERE token=?', (user['token'],))
        return {'revoked': True}

    @app.delete('/v1/devices/{device}')
    def revoke(device:uuid.UUID, user=Depends(session)):
        with store.db() as c: c.execute('UPDATE sessions SET revoked=1 WHERE user=? AND device=?', (user['user'], str(device)))
        return {'revoked': True}

    @app.put('/v1/libraries/{library}')
    def create_library(library:uuid.UUID, user=Depends(session)):
        with store.db() as c:
            c.execute('BEGIN IMMEDIATE')
            exists=c.execute('SELECT * FROM libraries WHERE id=?', (str(library),)).fetchone()
            if exists: authorize(c, library, user)
            else:
                if c.execute('SELECT COUNT(*) FROM libraries WHERE owner=?',(user['user'],)).fetchone()[0]>=16:
                    raise HTTPException(507,'Library quota reached')
                c.execute('INSERT INTO libraries VALUES(?,?)', (str(library), user['user']))
        return {'library': str(library)}

    @app.put('/v1/libraries/{library}/uploads/{op}')
    def begin(library:uuid.UUID, op:uuid.UUID, manifest:Manifest, user=Depends(session)):
        value=manifest.model_dump()
        if any(len(h)!=64 or any(ch not in '0123456789abcdef' for ch in h) for h in value['chunks']): raise HTTPException(422, 'Invalid chunk hash')
        if not (len(value['chunks'])-1)*CHUNK_LIMIT < value['bytes'] <= len(value['chunks'])*CHUNK_LIMIT: raise HTTPException(422, 'Invalid chunk size')
        encoded=canonical(value); fingerprint=digest(encoded.encode())
        with store.db() as c:
            c.execute('BEGIN IMMEDIATE');authorize(c, library, user)
            existing=c.execute('SELECT * FROM operations WHERE library=? AND id=?',(str(library),str(op))).fetchone()
            if existing:
                if existing['digest']!=fingerprint or existing['kind']!='UPLOAD': raise HTTPException(409, 'Operation payload changed')
                return receipt(existing)
            expire_pending(c)
            count=c.execute("SELECT COUNT(*) FROM operations WHERE library=? AND kind='UPLOAD' AND state IN ('PENDING','PUBLISHED')",(str(library),)).fetchone()[0]
            if count>=10: raise HTTPException(507, 'Version quota reached; explicitly delete an old version')
            reserved=c.execute("SELECT o.manifest,l.owner,o.state FROM operations o JOIN libraries l ON l.id=o.library WHERE o.kind='UPLOAD' AND o.state IN ('PENDING','PUBLISHED')").fetchall()
            if sum(json.loads(r['manifest'])['bytes'] for r in reserved)+value['bytes']>GLOBAL_LIMIT:
                raise HTTPException(507,'Server storage budget reached')
            own=[r for r in reserved if r['owner']==user['user']]
            if sum(json.loads(r['manifest'])['bytes'] for r in own)+value['bytes']>ACCOUNT_LIMIT or sum(r['state']=='PENDING' for r in own)>=4:
                raise HTTPException(507,'Account storage or pending upload budget reached')
            c.execute('INSERT INTO operations VALUES(?,?,?,?,?,?,?)',(str(op),str(library),'UPLOAD',fingerprint,encoded,'PENDING',int(time.time())))
            return receipt(operation(c,library,op,user))

    @app.put('/v1/libraries/{library}/uploads/{op}/chunks/{ordinal}')
    async def put_chunk(library:uuid.UUID,op:uuid.UUID,ordinal:int,request:Request,user=Depends(session)):
        data=await request.body()
        with store.db() as c:
            c.execute('BEGIN IMMEDIATE');row=operation(c,library,op,user);m=json.loads(row['manifest'])
            if row['kind']!='UPLOAD' or row['state'] not in ('PENDING','PUBLISHED'): raise HTTPException(409,'Upload unavailable')
            if not 0<=ordinal<len(m['chunks']): raise HTTPException(422,'Chunk index')
            expected=CHUNK_LIMIT if ordinal<len(m['chunks'])-1 else m['bytes']-ordinal*CHUNK_LIMIT
            if len(data)!=expected or digest(data)!=m['chunks'][ordinal]: raise HTTPException(422,'Chunk integrity')
            exists=c.execute('SELECT data FROM chunks WHERE library=? AND operation=? AND ordinal=?',(str(library),str(op),ordinal)).fetchone()
            if exists and exists['data']!=data: raise HTTPException(409,'Chunk changed')
            if not exists:
                if row['state']!='PENDING': raise HTTPException(409,'Published version immutable')
                c.execute('INSERT INTO chunks VALUES(?,?,?,?)',(str(library),str(op),ordinal,data))
        return {'stored':ordinal}

    @app.post('/v1/libraries/{library}/uploads/{op}/publish')
    def publish(library:uuid.UUID,op:uuid.UUID,user=Depends(session)):
        with store.db() as c:
            c.execute('BEGIN IMMEDIATE');row=operation(c,library,op,user)
            if row['kind']!='UPLOAD' or row['state'] not in ('PENDING','PUBLISHED'): raise HTTPException(409,'Upload unavailable')
            if row['state']=='PENDING':
                m=json.loads(row['manifest']);count=0;total=0
                for chunk in c.execute('SELECT ordinal,data FROM chunks WHERE library=? AND operation=? ORDER BY ordinal',(str(library),str(op))):
                    if chunk['ordinal']!=count or digest(chunk['data'])!=m['chunks'][count]: raise HTTPException(409,'Chunk integrity')
                    total+=len(chunk['data']);count+=1
                if count!=len(m['chunks']) or total!=m['bytes']: raise HTTPException(409,'Upload incomplete')
                before_publish()
                c.execute("UPDATE operations SET state='PUBLISHED' WHERE library=? AND id=?",(str(library),str(op)))
            return receipt(operation(c,library,op,user))

    @app.get('/v1/libraries/{library}/operations/{op}')
    def lookup(library:uuid.UUID,op:uuid.UUID,user=Depends(session)):
        with store.db() as c: return receipt(operation(c,library,op,user))

    @app.get('/v1/libraries/{library}/uploads/{op}')
    def uploaded_chunks(library:uuid.UUID,op:uuid.UUID,user=Depends(session)):
        with store.db() as c:
            row=operation(c,library,op,user)
            if row['kind']!='UPLOAD': raise HTTPException(409,'Not an upload')
            return receipt(row)|{'manifest':json.loads(row['manifest']), 'received':[
                r['ordinal'] for r in c.execute('SELECT ordinal FROM chunks WHERE library=? AND operation=? ORDER BY ordinal',(str(library),str(op)))]}

    @app.get('/v1/libraries/{library}/versions')
    def versions(library:uuid.UUID,include_pending:bool=False,user=Depends(session)):
        with store.db() as c:
            authorize(c,library,user)
            return [receipt(r)|{'created':r['created'],'bytes':json.loads(r['manifest'])['bytes'],'format':json.loads(r['manifest'])['format']} for r in c.execute("SELECT * FROM operations WHERE library=? AND kind='UPLOAD' AND (state='PUBLISHED' OR (? AND state='PENDING')) ORDER BY created DESC,id",(str(library),include_pending))]

    @app.get('/v1/libraries/{library}/versions/{op}')
    def manifest(library:uuid.UUID,op:uuid.UUID,user=Depends(session)):
        with store.db() as c:
            row=operation(c,library,op,user)
            if row['kind']!='UPLOAD' or row['state']!='PUBLISHED': raise HTTPException(404,'Version unavailable')
            return json.loads(row['manifest'])

    @app.get('/v1/libraries/{library}/versions/{op}/chunks/{ordinal}')
    def download(library:uuid.UUID,op:uuid.UUID,ordinal:int,user=Depends(session)):
        with store.db() as c:
            row=operation(c,library,op,user)
            if row['kind']!='UPLOAD' or row['state']!='PUBLISHED': raise HTTPException(404,'Version unavailable')
            chunk=c.execute('SELECT data FROM chunks WHERE library=? AND operation=? AND ordinal=?',(str(library),str(op),ordinal)).fetchone()
            if chunk is None: raise HTTPException(404,'Chunk unavailable')
            return Response(chunk['data'],media_type='application/octet-stream')

    @app.delete('/v1/libraries/{library}/versions/{target}')
    def delete(library:uuid.UUID,target:uuid.UUID,operation_id:uuid.UUID,expected_state:str|None=None,user=Depends(session)):
        if expected_state not in (None,'PENDING'):raise HTTPException(422,'Invalid precondition')
        payload=canonical({'target':str(target)}|({'expected':'PENDING'} if expected_state else {}));fingerprint=digest(payload.encode())
        with store.db() as c:
            c.execute('BEGIN IMMEDIATE');authorize(c,library,user)
            prior=c.execute('SELECT * FROM operations WHERE library=? AND id=?',(str(library),str(operation_id))).fetchone()
            if prior:
                if prior['kind']!='DELETE' or prior['digest']!=fingerprint: raise HTTPException(409,'Operation payload changed')
                return receipt(prior)
            row=operation(c,library,target,user)
            if row['kind']!='UPLOAD': raise HTTPException(409,'Invalid target')
            if expected_state and row['state']!=expected_state:raise HTTPException(409,'Upload already completed; confirm version deletion')
            c.execute("UPDATE operations SET state='DELETED' WHERE library=? AND id=?",(str(library),str(target)))
            c.execute('DELETE FROM chunks WHERE library=? AND operation=?',(str(library),str(target)))
            c.execute('INSERT INTO operations VALUES(?,?,?,?,?,?,?)',(str(operation_id),str(library),'DELETE',fingerprint,payload,'DELETED',int(time.time())))
            return receipt(operation(c,library,operation_id,user))
    if enable_shadow:
        from shadow import mount
        mount(app,store,session,authorize)
    return app


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--db',default=os.environ.get('INKWEFT_BACKUP_DB','state/backup.db'));p.add_argument('--add-user');p.add_argument('--reset-password');p.add_argument('--backup-to');p.add_argument('--port',type=int,default=18751);p.add_argument('--host',default='127.0.0.1');a=p.parse_args()
    store=Store(a.db)
    if a.add_user: print('Created user',store.add_user(a.add_user,getpass.getpass('Password (12+ characters): ')))
    elif a.reset_password:
        password=getpass.getpass('New password (12+ characters): ')
        if len(password)<12: raise ValueError('Password too short')
        with store.db() as c:
            user=c.execute('SELECT id FROM users WHERE name=?',(a.reset_password,)).fetchone()
            if user is None: raise ValueError('User not found')
            c.execute('UPDATE users SET password=? WHERE id=?',(HASHER.hash(password),user['id']));c.execute('UPDATE sessions SET revoked=1 WHERE user=?',(user['id'],))
        print('Authentication reset; encrypted backups still require the separate recovery key.')
    elif a.backup_to:
        target=Path(a.backup_to)
        if target.exists(): raise ValueError('Backup destination already exists')
        with store.db() as source,sqlite3.connect(target) as output: source.backup(output)
        print('Server snapshot saved')
    else:
        import uvicorn
        uvicorn.run(create_app(a.db),host=a.host,port=a.port,access_log=False)
