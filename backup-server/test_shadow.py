from contextlib import closing
import base64,json,sqlite3,tempfile,unittest,uuid,subprocess,sys
from pathlib import Path
from fastapi.testclient import TestClient
from server import Store,create_app
from shadow import ShadowRelay

class ShadowTest(unittest.TestCase):
 def test_persistent_replay_cursor_and_closed_default(self):
  with tempfile.TemporaryDirectory() as tmp:
   root=Path(tmp);store=Store(root/'backup.db');store.add_user('synthetic','synthetic-password-123');device=str(uuid.uuid4());library=str(uuid.uuid4())
   with TestClient(create_app(store.path,enable_shadow=True)) as c:
    identity=c.post('/v1/sessions',json={'username':'synthetic','password':'synthetic-password-123','device':device}).json();headers={'Authorization':'Bearer '+identity['token']}
    self.assertEqual(200,c.put('/v1/libraries/'+library,headers=headers).status_code)
    envelope={'schema':'a'*64,'format':'inkweft.shadow-rows.v1','scope':[identity['server'],identity['issuer'],identity['user'],library],'device':device,'operation':str(uuid.uuid4()),'key_version':1,'nonce':base64.b64encode(b'0'*12).decode(),'ciphertext':base64.b64encode(b'1'*32).decode()}
    url='/v1/libraries/'+library+'/shadow/events';body=json.dumps(envelope).encode()
    first=c.post(url,headers=headers,content=body);self.assertEqual(200,first.status_code);self.assertEqual(first.json(),c.post(url,headers=headers,content=body).json())
    self.assertEqual(409,c.post(url,headers=headers,json=envelope|{'future':1}).status_code)
    expected=c.get(url,headers=headers).json()
    relay_path=Path(store.path).with_suffix('.shadow.db')
    # Reopen in an independent interpreter, not a second in-memory Relay instance.
    code="import json,sys;from shadow import ShadowRelay;print(json.dumps(ShadowRelay(sys.argv[1]).pull(sys.argv[2],sys.argv[3],0)))"
    result=subprocess.check_output([sys.executable,'-c',code,str(relay_path),identity['user'],library],cwd=Path(__file__).parent,text=True)
    self.assertEqual(expected,json.loads(result))
    c.delete('/v1/sessions/current',headers=headers);self.assertEqual(401,c.get(url,headers=headers).status_code)
   with TestClient(create_app(store.path)) as c:self.assertEqual(404,c.get(url).status_code)
   with closing(sqlite3.connect(relay_path)) as db,db:db.execute('INSERT INTO cursors VALUES (?,?,?)',(identity['user'],library,1))
   with self.assertRaisesRegex(ValueError,'EXPIRED'):ShadowRelay(relay_path).pull(identity['user'],library,0)
if __name__=='__main__':unittest.main()
