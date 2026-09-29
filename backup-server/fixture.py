"""Ephemeral synthetic account fixture. Never points at an existing server database."""
import argparse
from pathlib import Path
from server import Store,create_app

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--directory',required=True);parser.add_argument('--port',type=int,default=18751);parser.add_argument('--upload-delay',type=float,default=0);args=parser.parse_args();assert 0<=args.upload_delay<=4
    root=Path(args.directory).resolve();root.mkdir(parents=True,exist_ok=True);db=root/'synthetic.db'
    if db.exists(): raise SystemExit('Fixture refuses to reuse an existing database')
    store=Store(db)
    store.add_user('synthetic-alice','synthetic-alice-password-123')
    store.add_user('synthetic-bob','synthetic-bob-password-456')
    import uvicorn
    app=create_app(db,enable_shadow=True)
    counts={};activity={"uploads":0};controls={"trickle":0.0}
    import asyncio
    from starlette.responses import StreamingResponse
    @app.middleware('http')
    async def count_requests(request,call_next):
        key=request.method+' '+request.url.path
        counts[key]=counts.get(key,0)+1
        if request.method=='PUT' and '/chunks/' in request.url.path:
            activity['uploads']+=1
            try:
                if args.upload_delay:await asyncio.sleep(args.upload_delay)
                response=await call_next(request)
                return await trickle(response)
            finally:activity['uploads']-=1
        response=await call_next(request)
        return await trickle(response) if request.url.path=='/v1/identity' or (request.method=='GET' and '/uploads/' in request.url.path) else response
    async def trickle(response):
        seconds=controls['trickle']
        if not seconds or response.status_code!=200:return response
        data=b''.join([chunk async for chunk in response.body_iterator])
        async def stream():
            step=max(1,(len(data)+9)//10)
            for i in range(0,len(data),step):
                await asyncio.sleep(seconds/10);yield data[i:i+step]
        headers=dict(response.headers);headers.pop('content-length',None)
        return StreamingResponse(stream(),status_code=response.status_code,headers=headers)
    @app.post('/__fixture__/trickle/{seconds}')
    def set_trickle(seconds:float):
        assert 0<=seconds<=25;controls['trickle']=seconds;return {'seconds':seconds}
    @app.get('/__fixture__/counts')
    def request_counts():return counts
    @app.get('/__fixture__/activity')
    def request_activity():return activity
    uvicorn.run(app,host='127.0.0.1',port=args.port,access_log=False)
