#!/usr/bin/env python3
"""Require actual Android instrumentation and native AAR evidence; no connectivity claim."""
from pathlib import Path
import hashlib,json,os,re,zipfile

out=Path('build-output/probe')
text=(out/'instrumentation.txt').read_text()
assert re.search(r'OK \(\s*3 tests\s*\)',text),'Android instrumentation did not pass all three tests'
assert 'FAILURES!!!' not in text and 'INSTRUMENTATION_FAILED' not in text
proof=json.loads((out/'transport-probe.json').read_text())
assert proof['fixture_application_id']=='com.darsbridge.transportprobe'
assert proof['controller_instances']==1 and proof['all_assertions_reached'] is True
assert proof['tor_end_to_end_tested'] is False and proof['production_browser_modified'] is False
results=proof['results']
assert len(results)==3 and {x['transport'] for x in results}=={'obfs4','webtunnel','obfs4-restart-and-invalid-method'}
for row in results:
    assert all(row[k] is True for k in ('loopback_only','socks5_negotiated','listener_closed_on_stop','passed'))
    assert row['socks_connect_requests_sent']==0
restart=next(x for x in results if x['transport']=='obfs4-restart-and-invalid-method')
assert restart['restart_verified'] is True and restart['unknown_transport_rejected'] is True
aar_files=list((Path.home()/'.gradle/caches/modules-2/files-2.1/com.netzarchitekten/IPtProxy/5.7.0').glob('**/IPtProxy-5.7.0.aar'))
assert aar_files,'Missing actual resolved AAR'
assert len({hashlib.sha256(p.read_bytes()).hexdigest() for p in aar_files})==1
aar=aar_files[0]
with zipfile.ZipFile(aar) as z:
    assert z.testzip() is None
    assert 'classes.jar' in z.namelist()
    native={n:hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if n.startswith('jni/') and n.endswith('/libgojni.so')}
    assert 'jni/x86_64/libgojni.so' in native and 'jni/arm64-v8a/libgojni.so' in native
lint=Path('darsbridge-v012/transport-probe/app/build/reports/lint-results-debug.txt').read_text()
summary=re.search(r'(\d+) errors, (\d+) warnings',lint)
if not summary and 'No issues found' in lint:summary=(None,'0','0')
assert summary and int(summary[1])==0
proof.update(stage='NATIVE_LOCAL_PROBE_PASSED_BROWSER_INTEGRATION_PENDING',code_sha=os.environ['GITHUB_SHA'],
    run_url=os.environ['GITHUB_SERVER_URL']+'/'+os.environ['GITHUB_REPOSITORY']+'/actions/runs/'+os.environ['GITHUB_RUN_ID'],
    instrumentation_tests=3,lint={'errors':int(summary[1]),'warnings':int(summary[2])},
    aar_sha256=hashlib.sha256(aar.read_bytes()).hexdigest(),native_library_sha256=native,
    phone_tested=False,production_apk_produced=False,
    limitations=['Only loopback listeners and SOCKS5 negotiation were exercised','No bridge address, external SOCKS CONNECT, Tor circuit or user network was tested','ARM64 library presence and checksum are recorded; execution was on the stated emulator ABI'])
(out/'TRANSPORT_PROOF.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(proof,ensure_ascii=False,indent=2))
