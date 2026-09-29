"""Build/check a local declarative pack. Python stdlib only; no extraction to filesystem."""
import argparse, hashlib, io, json, struct, zipfile, zlib
from pathlib import Path

def check(path):
    data=Path(path).read_bytes()
    if len(data)>8*1024*1024:raise ValueError('compressed budget')
    total=0;files={}
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        if not 1<=len(z.infolist())<=256:raise ValueError('entry count')
        for entry in z.infolist():
            name=entry.filename
            if (not name or any(c not in 'abcdefghijklmnopqrstuvwxyz0123456789/_.-' for c in name)
                or any(p in ('','.','..') for p in name.split('/')) or name in files
                or (entry.external_attr>>16)&0xf000 not in (0,0x8000)
                or not name.endswith(('.json','.png'))):raise ValueError('path/type/link')
            out=bytearray()
            with z.open(entry) as f:
                while chunk:=f.read(8192):
                    total+=len(chunk);out.extend(chunk)
                    if len(out)>8*1024*1024 or total>32*1024*1024 or total>len(data)*20:raise ValueError('expansion budget')
            files[name]=bytes(out)
    def budget(value,depth=0):
        if depth>16:raise ValueError('json depth')
        if isinstance(value,str) and len(value)>4096:raise ValueError('string budget')
        children=value.values() if isinstance(value,dict) else value if isinstance(value,list) else []
        count=1+sum(budget(v,depth+1) for v in children)
        if count>8192:raise ValueError('node budget')
        return count
    def unique(pairs):
        result={}
        for k,v in pairs:
            if k in result:raise ValueError('duplicate json key')
            result[k]=v
        return result
    manifest=json.loads(files['manifest.json'],object_pairs_hook=unique);budget(manifest)
    if manifest['format']!='inkweft.resource-pack.v1' or manifest['id'].startswith('org.inkweft'):raise ValueError('format/namespace')
    declared=manifest.get('files',{})
    if set(declared)!=set(files)-{'manifest.json'}:raise ValueError('manifest file list')
    for name,entry in declared.items():
        if entry['bytes']!=len(files[name]) or entry['sha256']!=hashlib.sha256(files[name]).hexdigest():raise ValueError('file integrity')
    for name,png in files.items():
        if name.endswith('.png'):
            if png[:8]!=b'\x89PNG\r\n\x1a\n':raise ValueError('PNG')
            w,h=struct.unpack('>II',png[16:24])
            if not 0<w<=2048 or not 0<h<=2048 or w*h*4>16*1024*1024:raise ValueError('pixels')
    return {'id':manifest['id'],'version':manifest['version'],'bytes':len(data),'expanded':total,'sha256':hashlib.sha256(data).hexdigest(),'source':'local unsigned'}

def build(source,output):
    source=Path(source);output=Path(output)
    if output.exists():raise ValueError('output already exists')
    manifest=json.loads((source/'manifest.json').read_text(encoding='utf-8'))
    names=['manifest.json']+[r['image'] for r in manifest['resources'] if 'image' in r]
    with zipfile.ZipFile(output,'w',compression=zipfile.ZIP_STORED) as z:
        for name in sorted(set(names)):
            raw=source/name;path=raw.resolve()
            if not path.is_relative_to(source.resolve()) or raw.is_symlink():raise ValueError('source path')
            z.writestr(zipfile.ZipInfo(name,(2026,9,29,0,0,0)),path.read_bytes())
    try:return check(output)
    except Exception:output.unlink();raise

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('input');p.add_argument('--output');a=p.parse_args()
    print(json.dumps(build(a.input,a.output) if a.output else check(a.input),ensure_ascii=False,indent=2))
