"""Explicit TLS gate on a disposable official emulator. Never modifies device trust stores."""
import argparse,hashlib,json,re,subprocess
from pathlib import Path
import urllib.request,ssl

def main():
    p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--fixture',required=True);p.add_argument('--output',required=True);a=p.parse_args()
    root=Path(__file__).resolve().parents[1];out=Path(a.output);out.mkdir(parents=True,exist_ok=True)
    adb=['adb','-s',a.serial]
    def call(*args):return subprocess.check_output(adb+list(args),stderr=subprocess.STDOUT).decode(errors='replace').strip()
    assert call('shell','getprop','ro.kernel.qemu')=='1','Dedicated emulator required'
    fingerprint=call('shell','getprop','ro.build.fingerprint');assert 'sdk_gphone' in fingerprint,'Official Google image required'
    package='org.inkweft.app.a0.insertion.tlsprobe'
    paths=[root/'android/app/build/outputs/apk/tlsProbe/app-tlsProbe.apk',root/'android/app/build/outputs/apk/androidTest/tlsProbe/app-tlsProbe-androidTest.apk']
    artifacts=[{'file':f.name,'sha256':hashlib.sha256(f.read_bytes()).hexdigest()} for f in paths]
    for f in paths:call('install','-r',str(f))
    for port in range(18761,18767):call('reverse',f'tcp:{port}',f'tcp:{port}')
    result=call('shell','am','instrument','-w','-e','class','org.inkweft.app.TlsBackupProbe',package+'.test/androidx.test.runner.AndroidJUnitRunner')
    (out/'instrumentation.txt').write_text(result)
    context=ssl.create_default_context(cafile=str(Path(a.fixture)/'ca.pem'))
    with urllib.request.urlopen('https://127.0.0.1:18761/__fixture__/counts',context=context,timeout=5) as response:counts=json.load(response)
    # IDs/paths are synthetic, but the public report needs only per-endpoint totals.
    endpoints=counts['endpoints'];passed=bool(re.search(r'^OK \(5 tests\)',result,re.M))
    passed &= all(endpoints[n]['requests']==0 for n in ['expired','wrong','untrusted','sink']) and endpoints['redirect']['sensitive']==0
    certificates=[]
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes
    for f in sorted(Path(a.fixture).glob('*.pem')):
        c=x509.load_pem_x509_certificate(f.read_bytes());certificates.append({'file':f.name,'sha256':c.fingerprint(hashes.SHA256()).hex(),'pemSha256':hashlib.sha256(f.read_bytes()).hexdigest(),'issuer':c.issuer.rfc4514_string(),'from':str(c.not_valid_before_utc),'until':str(c.not_valid_after_utc)})
    (out/'tls-report.json').write_text(json.dumps({'passed':passed,'executed':5,'fingerprint':fingerprint,'package':package,'artifacts':artifacts,'certificates':certificates,'counts':endpoints,'proxy':call('shell','settings','get','global','http_proxy')},indent=2))
    assert passed,'TLS gate failed; inspect retained evidence'
    print('TLS gate: 5/5; denied endpoints received no HTTP or sensitive data')

if __name__=='__main__':main()
