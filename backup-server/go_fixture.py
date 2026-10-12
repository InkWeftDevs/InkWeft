"""Disposable Go protocol endpoint plus the existing v1/shadow/TLS acceptance fixtures.
The Python application is test infrastructure; production deployment runs only the Go binary.
"""
import atexit
import hashlib
import os
from pathlib import Path
import sqlite3
import subprocess
import time
import httpx
from fastapi import Response
from server import Store, create_app


def start_go(root, port):
    root=Path(root);root.mkdir(parents=True,exist_ok=True)
    executable=Path(os.environ.get('INKWEFT_BACKUP_BIN',str(Path(__file__).parent/'bin'/('inkweft-backup.exe' if os.name=='nt' else 'inkweft-backup')))).resolve()
    if not executable.is_file():raise RuntimeError('Build the Go acceptance binary first (INKWEFT_BACKUP_BIN)')
    db=root/'go-v2.db'
    if db.exists() or (root/'v1-reference.db').exists():raise RuntimeError('Fixture refuses an existing data directory; use a new synthetic directory')
    command=[str(executable),'--db',str(db),'--port',str(port),'--fixture']
    log=(root/'go-service.log').open('ab')
    process=subprocess.Popen(command,stdout=log,stderr=log,creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
    (root/'go-process.json').write_text(__import__('json').dumps({'pid':process.pid,'binary':str(executable),'database':str(db),'port':port}),encoding='utf-8')
    def stop():
        if process.poll() is None:
            process.terminate()
            try:process.wait(timeout=10)
            except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)
        log.close()
    atexit.register(stop)
    base=f'http://127.0.0.1:{port}'
    for _ in range(100):
        if process.poll() is not None:raise RuntimeError('Go fixture stopped; see go-service.log')
        try:
            with httpx.Client(timeout=1,trust_env=False) as h:h.get(base+'/health/ready').raise_for_status()
            break
        except httpx.HTTPError:time.sleep(.1)
    else:stop();raise RuntimeError('Go fixture unavailable')
    return db,base


def fixture_app(root, go_db, base):
    root=Path(root);legacy_db=root/'v1-reference.db';store=Store(legacy_db)
    with sqlite3.connect(go_db) as c:
        identity=c.execute('SELECT id FROM identity').fetchone()[0]
        users=c.execute('SELECT id,name FROM users').fetchall()
    with store.db() as c:
        c.execute('UPDATE identity SET id=?',(identity,))
        for user,name in users:
            if not c.execute('SELECT 1 FROM users WHERE id=?',(user,)).fetchone():
                # Legacy authentication is served by Go. These rows are FK identities only.
                c.execute('INSERT INTO users VALUES(?,?,?)',(user,name,'fixture-only-authentication-in-go'))
    app=create_app(legacy_db,enable_shadow=True)
    @app.middleware('http')
    async def relay(request,call_next):
        path=request.url.path
        if not (path.startswith('/v2/') or path in ('/v1/identity','/v1/sessions','/v1/sessions/current') or path.startswith('/v1/devices/') or path.startswith('/health/')):
            return await call_next(request)
        # Synthetic-only TLS/slow-socket relay, with bounded bodies and no access/body logs.
        body=await request.body()
        if len(body)>1048576+32768:return Response(status_code=413)
        headers={key:request.headers[key] for key in ('authorization','content-type') if key in request.headers}
        async with httpx.AsyncClient(timeout=35,follow_redirects=False,trust_env=False) as http:
            reply=await http.request(request.method,base+path,params=list(request.query_params.multi_items()),headers=headers,content=body)
        if path.startswith('/v1/sessions') or path.startswith('/v1/devices/'):
            # Preserve the old shadow fixtures' authorization semantics using the Go-issued
            # synthetic sessions. No production schema or data directory is touched.
            with sqlite3.connect(go_db) as source,store.db() as target:
                for row in source.execute('SELECT token,user,device,expires,revoked FROM sessions'):
                    target.execute('INSERT OR REPLACE INTO sessions VALUES(?,?,?,?,?)',row)
        if path=='/v1/identity':
            # The v1 backup/reference fixture has its own declared test protocol.
            value=reply.json();value['protocol']=1
            return Response(__import__('json').dumps(value),media_type='application/json')
        return Response(reply.content,status_code=reply.status_code,headers={k:v for k,v in reply.headers.items() if k.lower() in ('content-type','retry-after','x-request-id')})
    return app
