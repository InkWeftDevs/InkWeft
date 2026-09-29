"""Disposable loopback TLS endpoints. CA private keys remain in the caller-owned scratch directory."""
import argparse,datetime,ipaddress,json,ssl,threading,time
from pathlib import Path
from cryptography import x509
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID
from server import Store,create_app
import uvicorn

def generate(root):
    root.mkdir(parents=True,exist_ok=True)
    if (root/'ca.pem').exists():return
    now=datetime.datetime.now(datetime.timezone.utc)
    def ca(name):
        key=rsa.generate_private_key(65537,2048);subject=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,name)])
        cert=x509.CertificateBuilder().subject_name(subject).issuer_name(subject).public_key(key.public_key()).serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(days=1)).not_valid_after(now+datetime.timedelta(days=3)).add_extension(x509.BasicConstraints(ca=True,path_length=0),True).sign(key,hashes.SHA256())
        return key,cert
    key,cert=ca('InkWeft isolated test CA');rogue_key,rogue=ca('Untrusted fixture CA')
    (root/'ca.pem').write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    for name in ('valid','expired','wrong','untrusted'):
        issuer_key,issuer=(rogue_key,rogue) if name=='untrusted' else (key,cert)
        leaf=rsa.generate_private_key(65537,2048)
        hosts=[x509.DNSName('wrong.invalid')] if name=='wrong' else [x509.DNSName('localhost'),x509.IPAddress(ipaddress.ip_address('127.0.0.1'))]
        signed=x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,name)])).issuer_name(issuer.subject).public_key(leaf.public_key()).serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(days=2)).not_valid_after(now+datetime.timedelta(days=-1 if name=='expired' else 2)).add_extension(x509.SubjectAlternativeName(hosts),False).sign(issuer_key,hashes.SHA256())
        (root/(name+'.pem')).write_bytes(signed.public_bytes(serialization.Encoding.PEM))
        (root/(name+'.key')).write_bytes(leaf.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
    res=root/'res';(res/'raw').mkdir(parents=True,exist_ok=True);(res/'xml').mkdir(exist_ok=True)
    (res/'raw'/'fixture_ca.pem').write_bytes((root/'ca.pem').read_bytes())
    (res/'xml'/'network_security_config.xml').write_text('<network-security-config><base-config cleartextTrafficPermitted="false"><trust-anchors><certificates src="system"/></trust-anchors></base-config><domain-config cleartextTrafficPermitted="false"><domain>localhost</domain><domain>127.0.0.1</domain><trust-anchors><certificates src="@raw/fixture_ca"/></trust-anchors></domain-config></network-security-config>')

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--directory',required=True);p.add_argument('--generate-only',action='store_true');a=p.parse_args();root=Path(a.directory).resolve();generate(root)
    if a.generate_only:raise SystemExit(0)
    db=root/'tls-synthetic.db'
    if not db.exists():
        store=Store(db);store.add_user('synthetic-alice','synthetic-alice-password-123');store.add_user('synthetic-bob','synthetic-bob-password-456')
    for offset,name in enumerate(('valid','expired','wrong','untrusted','redirect')):
        app=create_app(db,enable_shadow=True)
        if name=='redirect':
            from starlette.responses import RedirectResponse
            @app.middleware('http')
            async def redirect(request,call_next):return RedirectResponse('http://127.0.0.1:18766/blocked',status_code=307)
        certname='valid' if name=='redirect' else name
        config=uvicorn.Config(app,host='127.0.0.1',port=18761+offset,ssl_keyfile=str(root/(certname+'.key')),ssl_certfile=str(root/(certname+'.pem')),access_log=False)
        threading.Thread(target=uvicorn.Server(config).run,daemon=True).start()
    while True:time.sleep(1)
