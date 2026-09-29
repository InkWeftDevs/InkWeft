import unittest
import uuid
from model import Client,Relay,Identity,CursorExpired,wrap_sync_key,unwrap_sync_key

def ident():return str(uuid.uuid4())
class SyncExperiment(unittest.TestCase):
    def setUp(self):
        self.a=Identity('server','issuer','alice','library','a');self.b=Identity('server','issuer','alice','library','b')
        self.keys={1:b'a'*32};self.relay=Relay(self.a.scope(),{'a','b'});self.A=Client(self.a,self.keys.copy());self.B=Client(self.b,self.keys.copy())
    def test_offline_conflicts_replay_tombstone_and_manual_resolution(self):
        x=ident();original=self.A.author([dict(id=x,kind='note',text='original')]);self.A.sync(self.relay);self.B.sync(self.relay)
        self.A.author([dict(id=x,kind='note',text='human correction')]);self.B.author([dict(id=x,kind='note',deleted=True)])
        self.A.sync(self.relay);self.B.sync(self.relay);self.A.sync(self.relay)
        self.assertEqual(2,len(self.A.records[x]));self.assertEqual(self.A.records,self.B.records)
        self.assertEqual(1,self.relay.publish(self.a,original));self.A.sync(self.relay);self.assertEqual(2,len(self.A.records[x]))
        self.A.author([dict(id=x,kind='note',text='explicitly keep corrected version')]);self.A.sync(self.relay);self.B.sync(self.relay)
        self.assertEqual(1,len(self.B.records[x]));self.assertEqual(self.A.records,self.B.records)
    def test_reference_closure_missing_attachment_unsupported_and_wrong_account(self):
        ids={k:ident() for k in ('note','page','ink','attachment','card','source','live_map','resource')}
        batch=[dict(id=v,kind=k,refs=[]) for k,v in ids.items()]
        batch[-2]['refs']=[dict(id=ids['card'],kind='card'),dict(id=ids['resource'],kind='resource')]
        batch[-3]['refs']=[dict(id=ids['ink'],kind='ink'),dict(id=ids['attachment'],kind='attachment')]
        self.A.author(batch);self.A.sync(self.relay);self.B.sync(self.relay);self.assertEqual(self.A.records,self.B.records)
        before=repr(self.B.records)
        with self.assertRaises(ValueError):self.B.author([dict(id=ident(),kind='source',refs=[dict(id=ident(),kind='attachment')])])
        with self.assertRaises(ValueError):self.B.author([dict(id=ident(),kind='unknown')])
        self.assertEqual(before,repr(self.B.records))
        wrong=Client(Identity('server','issuer','bob','library','a'),self.keys)
        with self.assertRaises(PermissionError):wrong.sync(self.relay)
        self.assertEqual({},wrong.records)
    def test_cursor_expiry_key_rotation_and_revoked_device(self):
        self.A.author([dict(id=ident(),kind='note')]);self.A.sync(self.relay)
        self.relay.floor=1
        with self.assertRaises(CursorExpired):self.B.sync(self.relay)
        self.assertEqual(0,self.B.cursor);self.relay.floor=0;self.B.sync(self.relay)
        self.A.keys[2]=b'b'*32;self.A.current=2;self.A.author([dict(id=ident(),kind='page')]);self.A.sync(self.relay)
        cursor=self.B.cursor
        with self.assertRaises(ValueError):self.B.sync(self.relay)
        self.assertEqual(cursor,self.B.cursor);self.B.keys[2]=b'b'*32;self.B.sync(self.relay)
        self.assertEqual(self.A.records,self.B.records)
        self.relay.devices.remove('b')
        with self.assertRaises(PermissionError):self.B.sync(self.relay)
    def test_manual_correction_priority_and_device_key_envelope(self):
        x=ident();self.A.author([dict(id=x,kind='ink',text='manual')]);self.A.sync(self.relay);self.B.sync(self.relay)
        self.B.author([dict(id=x,kind='ink',text='derived recognition',origin='derived')]);self.B.sync(self.relay);self.A.sync(self.relay)
        self.assertEqual('manual',self.A.preferred(x)[0]['text']);self.assertEqual(2,len(self.A.records[x]))
        grant=wrap_sync_key(self.a,1,b's'*32,b'd'*32)
        self.assertEqual(b's'*32,unwrap_sync_key(self.a,grant,b'd'*32))
        with self.assertRaises(PermissionError):unwrap_sync_key(self.b,grant,b'd'*32)
        with self.assertRaises(Exception):unwrap_sync_key(self.a,grant,b'x'*32)
if __name__=='__main__':unittest.main()
