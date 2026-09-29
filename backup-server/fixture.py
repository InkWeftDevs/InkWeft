"""Ephemeral synthetic account fixture. Never points at an existing server database."""
import argparse
from pathlib import Path
from server import Store,create_app

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--directory',required=True);parser.add_argument('--port',type=int,default=18751);args=parser.parse_args()
    root=Path(args.directory).resolve();root.mkdir(parents=True,exist_ok=True);db=root/'synthetic.db'
    if db.exists(): raise SystemExit('Fixture refuses to reuse an existing database')
    store=Store(db)
    store.add_user('synthetic-alice','synthetic-alice-password-123')
    store.add_user('synthetic-bob','synthetic-bob-password-456')
    import uvicorn
    app=create_app(db)
    counts={}
    @app.middleware('http')
    async def count_requests(request,call_next):
        key=request.method+' '+request.url.path
        counts[key]=counts.get(key,0)+1
        return await call_next(request)
    @app.get('/__fixture__/counts')
    def request_counts():return counts
    uvicorn.run(app,host='127.0.0.1',port=args.port,access_log=False)
