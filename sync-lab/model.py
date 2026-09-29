"""Isolated synthetic v1 record-sync experiment; never reads an Android author library."""
from dataclasses import dataclass
import copy
import hashlib
import json
import os
import uuid
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

KINDS={'note','page','ink','attachment','card','source','live_map','resource'}
def canonical(value):return json.dumps(value,sort_keys=True,separators=(',',':')).encode()
class CursorExpired(ValueError):pass

@dataclass(frozen=True)
class Identity:
    server:str
    issuer:str
    account:str
    library:str
    device:str
    def scope(self):return (self.server,self.issuer,self.account,self.library)

def wrap_sync_key(identity,version,key,device_key):
    header={'format':'inkweft.sync-grant.v1','scope':list(identity.scope()),'device':identity.device,'version':version}
    nonce=os.urandom(12)
    return dict(header,nonce=nonce.hex(),wrapped=AESGCM(device_key).encrypt(nonce,key,canonical(header)).hex())

def unwrap_sync_key(identity,grant,device_key):
    header={k:grant[k] for k in ('format','scope','device','version')}
    if header['format']!='inkweft.sync-grant.v1' or header['scope']!=list(identity.scope()) or header['device']!=identity.device:raise PermissionError('grant binding')
    key=AESGCM(device_key).decrypt(bytes.fromhex(grant['nonce']),bytes.fromhex(grant['wrapped']),canonical(header))
    if len(key)!=32:raise ValueError('key size')
    return key

class Relay:
    def __init__(self,scope,devices):self.scope=scope;self.devices=set(devices);self.events=[];self.operations={};self.floor=0
    def authorize(self,identity):
        if identity.scope()!=self.scope or identity.device not in self.devices:raise PermissionError('identity')
    def publish(self,identity,envelope):
        self.authorize(identity)
        if envelope['scope']!=list(self.scope) or envelope['device']!=identity.device:raise PermissionError('binding')
        op=envelope['operation'];uuid.UUID(op)
        old=self.operations.get(op)
        if old is not None:
            if old!=envelope:raise ValueError('operation payload changed')
            return self.events.index(old)+1
        if len(canonical(envelope))>1024*1024:raise ValueError('budget')
        self.operations[op]=copy.deepcopy(envelope);self.events.append(copy.deepcopy(envelope));return len(self.events)
    def pull(self,identity,cursor):
        self.authorize(identity)
        if cursor<self.floor:raise CursorExpired('explicit snapshot rebase required')
        if cursor>len(self.events):raise ValueError('cursor')
        return len(self.events),copy.deepcopy(self.events[cursor:])

class Client:
    def __init__(self,identity,keys,current=1):
        self.identity=identity;self.keys=keys;self.current=current;self.cursor=0;self.records={};self.applied={};self.queue=[]
    def author(self,changes):
        # Parent revisions express causality. A correction explicitly supersedes observed heads only.
        mutations=[]
        for change in changes:
            kind=change['kind']
            if kind not in KINDS:raise ValueError('unsupported type')
            ident=change['id'];uuid.UUID(ident)
            mutations.append(dict(change,origin=change.get('origin','human'),revision=str(uuid.uuid4()),parents=sorted(self.records.get(ident,{}))))
        operation=str(uuid.uuid4());header={'format':'inkweft.record-sync.v1','scope':list(self.identity.scope()),'device':self.identity.device,'operation':operation,'key_version':self.current}
        nonce=os.urandom(12) # A separate per-library sync key; never uses backup-container nonces.
        encrypted=AESGCM(self.keys[self.current]).encrypt(nonce,canonical(mutations),canonical(header))
        envelope=dict(header,nonce=nonce.hex(),ciphertext=encrypted.hex())
        self.apply([envelope]);self.queue.append(envelope);return envelope
    def apply(self,envelopes):
        records=copy.deepcopy(self.records);applied=dict(self.applied)
        for e in envelopes:
            if e['format']!='inkweft.record-sync.v1' or e['scope']!=list(self.identity.scope()):raise PermissionError('scope')
            fingerprint=hashlib.sha256(canonical(e)).hexdigest()
            if e['operation'] in applied:
                if applied[e['operation']]!=fingerprint:raise ValueError('replay changed')
                continue
            header={k:e[k] for k in ('format','scope','device','operation','key_version')}
            if e['key_version'] not in self.keys:raise ValueError('key version required')
            changes=json.loads(AESGCM(self.keys[e['key_version']]).decrypt(bytes.fromhex(e['nonce']),bytes.fromhex(e['ciphertext']),canonical(header)))
            if not 1<=len(changes)<=256:raise ValueError('batch budget')
            for c in changes:
                if c['kind'] not in KINDS:raise ValueError('unsupported type')
                if c.get('deleted',False) and c.get('refs'):raise ValueError('tombstone references')
                heads=records.setdefault(c['id'],{})
                if heads and any(h['kind']!=c['kind'] for h in heads.values()):raise ValueError('type changed')
                if c['origin'] not in ('human','derived'):raise ValueError('origin')
                for parent in c['parents']:
                    if c['origin']=='derived' and heads.get(parent,{}).get('origin')=='human':continue
                    heads.pop(parent,None)
                heads[c['revision']]=c
            applied[e['operation']]=fingerprint
        # Validate the complete received transaction, not each record before its dependency arrives.
        # A delete/edit conflict retains the tombstone AND the edited branch; no time-winner rule.
        for heads in records.values():
            for c in heads.values():
                for ref in c.get('refs',[]):
                    target=records.get(ref['id'],{})
                    if not any(t['kind']==ref['kind'] and not t.get('deleted',False) for t in target.values()):
                        raise ValueError('missing closure')
        self.records=records;self.applied=applied
    def preferred(self,identity):
        heads=list(self.records.get(identity,{}).values())
        human=[h for h in heads if h['origin']=='human']
        return human or heads # Multiple manual revisions still require explicit resolution.
    def sync(self,relay):
        relay.authorize(self.identity)
        # Pull first. On a key/closure error retain both cursor and unpublished local work.
        cursor,events=relay.pull(self.identity,self.cursor);self.apply(events);self.cursor=cursor
        while self.queue:
            relay.publish(self.identity,self.queue[0]);self.queue.pop(0)
        cursor,events=relay.pull(self.identity,self.cursor);self.apply(events);self.cursor=cursor
