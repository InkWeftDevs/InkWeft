"""Real SQLite lock waits must not block the ASGI loop or lose retry receipts."""
import asyncio
from contextlib import contextmanager
import hashlib
from pathlib import Path
import tempfile
import threading
import time
import unittest
import uuid

import anyio
import httpx
from server import create_app, digest


class ChunkWorkerTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='inkweft-chunk-workers-')
        self.app=create_app(Path(self.temp.name)/'server.db')
        self.store=self.app.state.store
        self.library=str(uuid.uuid4());self.op=str(uuid.uuid4());user=str(uuid.uuid4())
        self.headers={'Authorization':'Bearer synthetic-chunk-worker-token'}
        with self.store.db() as c:
            c.execute('INSERT INTO users VALUES(?,?,?)',(user,'synthetic','unused'))
            c.execute('INSERT INTO sessions VALUES(?,?,?,?,0)',(digest(b'synthetic-chunk-worker-token'),user,str(uuid.uuid4()),int(time.time())+60))
            c.execute('INSERT INTO libraries VALUES(?,?)',(self.library,user))
        self.http=httpx.AsyncClient(transport=httpx.ASGITransport(app=self.app),base_url='http://fixture',headers=self.headers)
        self.base=f'/v1/libraries/{self.library}/uploads/{self.op}'
        self.data=b'synthetic encrypted chunk'
        response=await self.http.put(self.base,json={'format':'inkweft.encrypted-backup.v1','bytes':len(self.data),'chunks':[hashlib.sha256(self.data).hexdigest()]})
        self.assertEqual(200,response.status_code)

    async def asyncTearDown(self):
        await self.http.aclose();self.temp.cleanup()

    async def test_writer_lock_keeps_live_health_responsive_and_chunk_workers_bounded(self):
        entered=threading.Event();release=threading.Event();worker_count=0;maximum=0
        real_db=self.store.db;lock=threading.Lock()
        # Track the actual complete DB unit, including the real BEGIN IMMEDIATE wait.
        @contextmanager
        def observed_db():
            nonlocal worker_count,maximum
            with real_db() as c:
                class Connection:
                    def execute(inner,statement,*args):
                        nonlocal worker_count,maximum
                        if statement=='BEGIN IMMEDIATE':
                            with lock:worker_count+=1;maximum=max(maximum,worker_count)
                            inner.writing=True
                            entered.set()
                        return c.execute(statement,*args)
                tracked=Connection()
                try:yield tracked
                finally:
                    # Session SELECT connections never enter the chunk write unit.
                    if getattr(tracked,'writing',False):
                        with lock:worker_count-=1
        # A dedicated connection owns/releases its lock in the same thread.
        ready=threading.Event()
        def hold_lock():
            with real_db() as c:
                c.execute('BEGIN IMMEDIATE');ready.set();release.wait(3)
        holder=threading.Thread(target=hold_lock);holder.start();self.assertTrue(ready.wait(2))
        self.store.db=observed_db
        uploads=[]
        try:
            uploads=[asyncio.create_task(self.http.put(self.base+'/chunks/0',content=self.data)) for _ in range(8)]
            self.assertTrue(await anyio.to_thread.run_sync(entered.wait,2))
            self.assertTrue(holder.is_alive(),'The SQLite lock timed out before the event loop could resume')
            started=time.monotonic()
            health=await asyncio.wait_for(self.http.get('/health/live'),.5)
            self.assertEqual(200,health.status_code);self.assertLess(time.monotonic()-started,.5)
            self.assertLessEqual(maximum,4)
        finally:
            release.set()
            replies=await asyncio.gather(*uploads)
            holder.join(3);self.store.db=real_db
        self.assertTrue(all(r.status_code==200 for r in replies))
        self.assertEqual([0],(await self.http.get(self.base)).json()['received'])

    async def test_cancelled_response_recovers_committed_chunk_by_receipt_and_retry(self):
        inserted=threading.Event();release=threading.Event();real_db=self.store.db
        @contextmanager
        def delayed_commit():
            with real_db() as c:
                class Connection:
                    def execute(inner,statement,*args):
                        result=c.execute(statement,*args)
                        if statement=='INSERT INTO chunks VALUES(?,?,?,?)':
                            inserted.set();release.wait(3)
                        return result
                yield Connection()
        self.store.db=delayed_commit
        response=[];scope=None
        async def upload():
            nonlocal scope
            with anyio.CancelScope() as scope:
                response.append(await self.http.put(self.base+'/chunks/0',content=self.data))
        try:
            async with anyio.create_task_group() as group:
                group.start_soon(upload)
                self.assertTrue(await anyio.to_thread.run_sync(inserted.wait,2))
                scope.cancel();release.set()
        finally:release.set();self.store.db=real_db
        self.assertEqual([],response)
        self.assertEqual([0],(await self.http.get(self.base)).json()['received'])
        retry=await self.http.put(self.base+'/chunks/0',content=self.data)
        self.assertEqual({'stored':0},retry.json())
        self.assertEqual('PUBLISHED',(await self.http.post(self.base+'/publish')).json()['state'])


if __name__=='__main__':unittest.main()
